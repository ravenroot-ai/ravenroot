import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

// Saving must preserve what the author writes in the source field. Defects, one fixture set:
//
// 1. An UNCONDITIONAL `sourceField.value = ...` in `applyLanguageCatalog` when
// `GET /v1/program-languages` resolves races with the author. That promise resolves whenever the network happens to
// answer -- after the author has usually already started typing, because writing code is the
// point of opening this panel. The starter silently replaced live keystrokes, and Build
// pressed afterward created an artifact from the starter, not from what the author wrote or
// believed they were looking at. `languageDelayMs` below is what turns that "usually" into a
// guaranteed, deterministic race in this test, instead of a timing accident nobody can pin down.
// 2. The server never returns a created artifact's source (ADR 0005: "source is accepted on
// creation but is never returned by list or lifecycle responses") and reopening a node gave the
// author no way to see it again -- source is now stored in the DOCUMENT itself, next to
// `artifactId`, and this is the only place the client can ever recover it from.
// 3. When the connected registry no longer has an artifact this document still names, the panel must
// distinguish missing source from recovered code; otherwise starter content can be mistaken for the
// authored source.
// 4. Storing source IN the document only closes the loop
// if the document gives it back byte for byte. Two independent leaks did not: `graph-parsers.js`
// trimmed every custom property's text on GraphML import (destroying a leading/trailing newline
// or first-line indentation -- and every starter ends in '\n', so this was the ordinary case,
// not an edge one), and the Source textarea's own markup lost a leading newline in `storedSource`
// to a genuine HTML parsing rule (a <textarea>'s first LINE FEED token is dropped by the parser)
// before any JS ever ran. Both produce the same failure shape: Build
// after a reopen makes an artifact whose sha256 does not match what the document names, with a
// 201 and a green pipeline -- just one step later than the race. Also folded in here: the
// "no silent substitution" rule, now reachable because `language` is a real
// document property. The test uses a language other than `javascript`, which is both the catalog's
// first entry and the fixture's hardcoded default, so neutralizing `storedLanguage` makes it fail.
//
// Each test below independently proves one item above.

const PROGRAM_CATALOG = JSON.stringify([{
  behavior: 'program', displayName: 'Governed program', category: 'Programming',
  description: 'Builds and executes tenant-scoped source through the governed sandbox runtime.',
  visualType: 'handler', agentic: false, capabilities: ['programmable', 'artifact-governed', 'sandbox-required'],
  properties: [
    { name: 'language', displayName: 'Language', type: 'STRING', required: true },
    { name: 'source', displayName: 'Source', type: 'TEXT', required: true },
    { name: 'testPayload', displayName: 'Test payload', type: 'TEXT', required: false,
      defaultValue: 'test payload' },
    { name: 'artifactId', displayName: 'Artifact ID', type: 'STRING', required: false },
  ],
}]);

const DEFAULT_LANGUAGES = [
  { id: 'javascript', displayName: 'JavaScript', exampleSource: 'JS_STARTER_EXAMPLE\n' },
  { id: 'python', displayName: 'Python', exampleSource: 'PY_STARTER_EXAMPLE\n' },
];

let service;
let buildRequests;
let buildResults;
let buildPollRequests;
let approvalRequests;
let legacyLifecycleRequests;
let artifactsByContent;
let buildsById;
let activeBuildsBySubmission;
let nextArtifactId;
let nextBuildId;
// 0 by default (most tests are not about the race itself); the one test that IS about the race
// overrides this before starting the service.
let languageDelayMs;
// Empty by default: no artifact this document names is "already known" to the registry until a
// test creates one. The one test about a forgotten artifact serves `[]` here regardless of what was
// created, simulating a registry that has been restarted and remembers nothing.
// `DEFAULT_LANGUAGES` unless a test reassigns it mid-run -- the "runtime dropped a language" test
// starts with both, saves an artifact in the one about to disappear, then narrows this before the
// reopen to simulate an adapter that no longer declares it.
let currentLanguages;
let buildDelayMs;
let nodeCatalogDelayMs;
let dualControl;
let retiredSources;
let executionRequests;

function startService() {
  buildRequests = [];
  buildResults = [];
  buildPollRequests = [];
  approvalRequests = [];
  legacyLifecycleRequests = [];
  artifactsByContent = new Map();
  buildsById = new Map();
  activeBuildsBySubmission = new Map();
  nextArtifactId = 1;
  nextBuildId = 1;
  executionRequests = 0;
  return new Promise((resolve, reject) => {
    service = createServer((request, response) => {
      const headers = {
        'Access-Control-Allow-Origin': UI_ORIGIN,
        Vary: 'Origin',
        'Content-Type': 'application/json; charset=utf-8',
      };
      if (request.method === 'OPTIONS') {
        response.writeHead(204, {
          ...headers,
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        });
        response.end();
        return;
      }
      if (request.url === '/v1/node-types') {
        setTimeout(() => {
          response.writeHead(200, headers);
          response.end(PROGRAM_CATALOG);
        }, nodeCatalogDelayMs);
        return;
      }
      if (request.url === '/v1/events') {
        response.writeHead(204, headers);
        response.end();
        return;
      }
      if (request.url === '/v1/program-languages') {
        setTimeout(() => {
          response.writeHead(200, headers);
          response.end(JSON.stringify(currentLanguages));
        }, languageDelayMs);
        return;
      }
      if (request.method === 'POST' && request.url.startsWith('/v1/executions')) {
        executionRequests += 1;
        response.writeHead(202, headers);
        response.end('{"executionId":"unexpected","graphVersion":"unexpected"}');
        return;
      }
      if (request.method === 'POST' && request.url === '/v1/program-artifacts/build') {
        let body = '';
        request.on('data', chunk => { body += chunk; });
        request.on('end', () => {
          const submission = JSON.parse(body);
          buildRequests.push(submission);
          setTimeout(() => {
            const signature = JSON.stringify(submission.programs);
            let build = activeBuildsBySubmission.get(signature);
            if (!build || durableBuildSnapshot(build).terminal) {
              build = createDurableBuild(submission.programs, signature);
            }
            buildResults.push(build.outcomes);
            const snapshot = durableBuildSnapshot(build);
            response.writeHead(snapshot.terminal ? 200 : 202, headers);
            response.end(JSON.stringify(snapshot));
          }, buildDelayMs);
        });
        return;
      }
      if (request.method === 'GET' && request.url.startsWith('/v1/program-artifacts/builds/')) {
        const buildId = decodeURIComponent(request.url.slice('/v1/program-artifacts/builds/'.length));
        buildPollRequests.push(buildId);
        const build = buildsById.get(buildId);
        if (!build) {
          response.writeHead(404, headers);
          response.end('{"error":"UNKNOWN_RESOURCE"}');
          return;
        }
        const approvalPaused = build.step >= 4 && !build.approved
          && build.outcomes.some(result => result.approvalRequired);
        if (!durableBuildSnapshot(build).terminal && !approvalPaused) {
          build.step += 1;
        }
        const snapshot = durableBuildSnapshot(build);
        response.writeHead(snapshot.terminal ? 200 : 202, headers);
        response.end(JSON.stringify(snapshot));
        return;
      }
      if (request.method === 'POST' && request.url === '/v1/program-artifacts/approve-batch') {
        let body = '';
        request.on('data', chunk => { body += chunk; });
        request.on('end', () => {
          const approval = JSON.parse(body);
          approvalRequests.push(approval);
          const artifacts = approval.artifactIds.map(id => {
            const entry = [...artifactsByContent.values()].find(candidate => candidate.id === id);
            if (!entry) return null;
            entry.state = 'ACTIVE';
            return entryToArtifact(entry);
          }).filter(Boolean);
          buildsById.forEach(build => {
            if (build.outcomes.some(result => approval.artifactIds.includes(result.artifactId))) {
              build.approved = true;
            }
          });
          response.writeHead(200, headers);
          response.end(JSON.stringify({ artifacts }));
        });
        return;
      }
      if (request.url.startsWith('/v1/program-artifacts')) {
        legacyLifecycleRequests.push({ method: request.method, url: request.url });
        response.writeHead(500, headers).end('{"error":"LEGACY_ROUTE_USED"}');
        return;
      }
      response.writeHead(404, headers).end('{}');
    });
    service.once('error', reject).listen(SERVICE_PORT, '127.0.0.1', resolve);
  });
}

// The DTO the real server actually returns (verified against
// GeneratedArtifact's JSON mapping): id, language, sha256, state, revision, createdAt, updatedAt,
// metadata. Deliberately NO `source` field -- reproducing that omission is what makes the "the panel
// must not invent a recovered source" assertions in these tests meaningful.
function entryToArtifact(entry) {
  const now = new Date().toISOString();
  return {
    id: entry.id,
    language: entry.language,
    sha256: `server-owned-${entry.id}`,
    state: entry.state,
    revision: 1,
    createdAt: now,
    updatedAt: now,
    metadata: {},
  };
}

function parsedTestPayload(text) {
  try { return JSON.parse(text); } catch { return text; }
}

function buildProgramOutcome(program) {
  const key = `${program.language}\0${program.source}`;
  let entry = artifactsByContent.get(key);
  const reused = Boolean(entry);
  if (!entry) {
    entry = {
      id: `server-artifact-${nextArtifactId++}`, language: program.language,
      source: program.source, state: dualControl ? 'TESTED' : 'ACTIVE', payload: program.testPayload,
    };
    artifactsByContent.set(key, entry);
  }
  const common = {
    nodeId: program.nodeId, artifactId: entry.id,
    sourceDigest: `server-source-${entry.id}`, payloadDigest: `server-payload-${program.testPayload.length}`,
    reused, smokeOutput: { payload: parsedTestPayload(program.testPayload) }, diagnostic: '',
  };
  if (retiredSources.has(program.source)) {
    entry.state = 'RETIRED';
    return { ...common, terminalPhase: 'RETIRED',
      diagnostic: 'retired content cannot be rebuilt or resurrected' };
  }
  if (program.source === 'syntax failure') {
    entry.state = 'GENERATED';
    return { ...common, terminalPhase: 'FAILED', failureStep: 2,
      smokeOutput: null, diagnostic: 'fixture syntax error at line 3' };
  }
  if (program.source === 'smoke failure') {
    entry.state = 'VALIDATED';
    return { ...common, terminalPhase: 'FAILED', failureStep: 3,
      smokeOutput: null, diagnostic: 'SMOKE_FIXTURE_FAILURE: sample refused' };
  }
  const payloadChanged = entry.payload !== program.testPayload;
  entry.payload = program.testPayload;
  if (dualControl && entry.state !== 'ACTIVE') {
    entry.state = 'TESTED';
    return { ...common, approvalRequired: true,
      diagnostic: 'independent graph-level approval is required' };
  }
  entry.state = 'ACTIVE';
  return { ...common,
    smokeOutput: payloadChanged ? { payload: parsedTestPayload(program.testPayload), requalified: true }
      : common.smokeOutput };
}

function createDurableBuild(programs, signature) {
  const id = `server-build-${nextBuildId++}`;
  const build = {
    id, signature, step: 0, approved: false, createdAt: new Date().toISOString(),
    outcomes: programs.map(buildProgramOutcome),
  };
  buildsById.set(id, build);
  activeBuildsBySubmission.set(signature, build);
  return build;
}

function observableBuildProgram(build, outcome) {
  let phase;
  let revision = build.step + 1;
  let terminal = false;
  let ready = false;
  let diagnostic = '';
  if (outcome.terminalPhase === 'RETIRED' && build.step >= 1) {
    phase = 'RETIRED'; revision = 2; terminal = true; diagnostic = outcome.diagnostic;
  } else if (outcome.terminalPhase === 'FAILED' && build.step >= outcome.failureStep) {
    phase = 'FAILED'; revision = outcome.failureStep + 1; terminal = true; diagnostic = outcome.diagnostic;
  } else if (build.step === 0) {
    phase = 'REGISTER';
  } else if (build.step === 1) {
    phase = 'VALIDATE';
  } else if (build.step === 2) {
    phase = 'SMOKE_TEST';
  } else if (build.step === 3) {
    phase = 'APPROVE_BY_POLICY';
  } else if (outcome.approvalRequired && !build.approved) {
    phase = 'APPROVAL_REQUIRED'; revision = 5; diagnostic = outcome.diagnostic;
  } else if ((!outcome.approvalRequired && build.step === 4)
      || (outcome.approvalRequired && build.step === 5)) {
    phase = 'ACTIVATE';
  } else {
    phase = 'READY'; terminal = true; ready = true;
  }
  const updatedAt = new Date(Date.parse(build.createdAt) + revision).toISOString();
  return {
    nodeId: outcome.nodeId,
    artifactId: build.step >= 1 ? outcome.artifactId : null,
    sourceDigest: outcome.sourceDigest,
    payloadDigest: outcome.payloadDigest,
    phase,
    revision,
    createdAt: build.createdAt,
    updatedAt,
    terminal,
    ready,
    reused: outcome.reused,
    smokeOutput: build.step >= 3 ? outcome.smokeOutput : null,
    diagnostic,
  };
}

function durableBuildSnapshot(build) {
  const programs = build.outcomes.map(outcome => observableBuildProgram(build, outcome));
  const terminal = programs.every(program => program.terminal);
  if (terminal) activeBuildsBySubmission.delete(build.signature);
  return {
    buildId: build.id,
    revision: Math.max(...programs.map(program => program.revision)),
    createdAt: build.createdAt,
    updatedAt: programs.reduce((latest, program) => program.updatedAt > latest ? program.updatedAt : latest,
      build.createdAt),
    terminal,
    programs,
  };
}

function externalProgramGraphMl(count, { changedSource = -1, changedPayload = -1 } = {}) {
  const nodes = Array.from({ length: count }, (_, index) => {
    const source = index === changedSource ? `changed source ${index}` : `source ${index}`;
    const payload = index === changedPayload ? '{&quot;changed&quot;:true}' : 'test payload';
    return `<node id="program-${index}"><data key="kind">BEHAVIOR</data>`
      + `<data key="behavior">program</data><data key="language">javascript</data>`
      + `<data key="source">${source}</data><data key="testPayload">${payload}</data></node>`;
  }).join('');
  return `<?xml version="1.0" encoding="UTF-8"?>
    <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
      <key id="kind" for="node" attr.name="kind" attr.type="string"/>
      <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
      <key id="language" for="node" attr.name="language" attr.type="string"/>
      <key id="source" for="node" attr.name="source" attr.type="string"/>
      <key id="testPayload" for="node" attr.name="testPayload" attr.type="string"/>
      <graph id="program-batch" edgedefault="directed">${nodes}</graph>
    </graphml>`;
}

async function connectAndModify(page) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
}

async function addProgramNode(page) {
  await page.locator('#node-catalog [data-catalog-add="program"]').click();
  await page.waitForSelector('.program-workspace');
}

test.beforeEach(async () => {
  languageDelayMs = 0;
  buildDelayMs = 0;
  nodeCatalogDelayMs = 0;
  dualControl = false;
  retiredSources = new Set();
  currentLanguages = DEFAULT_LANGUAGES;
});

test.afterEach(async () => new Promise(resolve => service.close(resolve)));

test('source written before the language catalog resolves survives, and the created artifact carries ITS sha256, not the starter\'s', async ({ page }) => {
  languageDelayMs = 700;
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);

  const sourceField = page.locator('.program-source');
  // The premise: the catalog genuinely has not answered yet, so this test is exercising the race,
  // not a coincidence of scheduling.
  await expect(page.locator('.program-language')).toBeDisabled();

  const AUTHOR_SOURCE = 'function handle(executionId, nodeId, payload, attributes) {\n'
    + '  return { hello: "from the author, not the starter" };\n}\n';
  await sourceField.fill(AUTHOR_SOURCE);

  // Let the delayed response land. An unconditional
  // `sourceField.value = exampleSourceForLanguage(...)` would overwrite the author's source now.
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });
  await expect(sourceField).toHaveValue(AUTHOR_SOURCE);

  const created = page.waitForResponse(response =>
    response.url().endsWith('/v1/program-artifacts/build') && response.request().method() === 'POST');
  await page.locator('[data-program-operation="build"]').click();
  const response = await created;
  const result = (await response.json()).programs[0];

  expect(result.phase).toBe('REGISTER');
  expect(result.artifactId).toBeNull();
  expect(result.sourceDigest).toBe('server-source-server-artifact-1');
  expect(buildRequests).toHaveLength(1);
  expect(buildRequests[0].programs[0].source).toBe(AUTHOR_SOURCE);
  await expect(page.locator('.program-status')).toContainText('server-artifact-1');
  await expect(page.locator('.program-status')).toContainText('READY');
  await expect(page.locator('.program-timeline')).toContainText('REGISTER');
  await expect(page.locator('.program-timeline')).toContainText('READY');
  await expect(page.locator('.program-timeline')).toContainText('revision 6');
  // The field itself is still exactly what the author wrote -- Create does not mutate it either.
  await expect(sourceField).toHaveValue(AUTHOR_SOURCE);
});

test('switching language after writing something asks before replacing it, and declining preserves the text', async ({ page }) => {
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });

  const sourceField = page.locator('.program-source');
  const AUTHOR_SOURCE = 'this is my own code, do not touch it';
  await sourceField.fill(AUTHOR_SOURCE);

  page.once('dialog', dialog => {
    expect(dialog.type()).toBe('confirm');
    dialog.dismiss();
  });
  await page.locator('.program-language').selectOption('python');
  await expect(sourceField).toHaveValue(AUTHOR_SOURCE);

  // Accepting is the one place the starter is still allowed to replace real text -- but only
  // because the author was asked, and only after they said yes.
  page.once('dialog', dialog => dialog.accept());
  await page.locator('.program-language').selectOption('javascript');
  await expect(sourceField).toHaveValue('JS_STARTER_EXAMPLE\n');
});

test('source and language are saved in the node\'s own properties and survive Save and the reopen', async ({ page }) => {
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });

  // `AUTHOR_SOURCE` starts with '\n' on purpose. This never goes through GraphML export/import -- Save
  // here only updates the in-memory document and `showNodeInfo` re-renders straight from it -- so
  // if this specific leading newline survives, the proof is isolated to the Source textarea's own
  // markup (programWorkspaceContentHtml's leading-newline compensation), not conflated with the
  // graph-parsers.js round-trip behavior the export/import test below covers.
  const AUTHOR_SOURCE = '\nfunction handle() { return 42; }\n';
  await page.locator('.program-source').fill(AUTHOR_SOURCE);
  await expect(page.locator('.program-language')).toHaveValue('javascript');

  // Build is one explicit preview of the same server-owned graph operation that runs after Save.
  const created = page.waitForResponse(response =>
    response.url().endsWith('/v1/program-artifacts/build') && response.request().method() === 'POST');
  await page.locator('[data-program-operation="build"]').click();
  await created;
  await expect(page.locator('.program-status')).toContainText('server-artifact-1');

  await page.locator('#node-editor input[name="id"]').fill('program-node-1');
  await page.locator('#node-editor button[type="submit"]').click();

  // The submit handler re-renders the Inspector against the saved model (`showNodeInfo`) rather
  // than closing it -- the same "round trip" shape node-runtime-nature.spec.js already uses for the
  // runtime-nature property, applied here to `source`/`language`. `program-node-1` must actually
  // exist now, or the assertions below would be reading the same never-submitted form right back.
  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue('program-node-1');
  expect(await page.evaluate(() => window.cy.nodes().map(node => node.id()))).toContain('program-node-1');
  await expect(page.locator('.program-language')).toHaveValue('javascript', { timeout: 5_000 });
  await expect(page.locator('.program-language')).toBeEnabled();
  // The starter's SECOND catalog load must respect the reloaded, non-empty field exactly like the
  // first one did -- this exercises the same guard a second time.
  await expect(page.locator('.program-source')).toHaveValue(AUTHOR_SOURCE);
  await expect(page.locator('.program-artifact-id')).toHaveValue('server-artifact-1');
});

test('a server-retired binding stays gated with the exact diagnostic and authored source', async ({ page }) => {
  retiredSources.add('source 0');
  await startService();
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#file-inp').setInputFiles({
    name: 'retired.graphml', mimeType: 'application/xml', buffer: Buffer.from(externalProgramGraphMl(1)),
  });

  await expect(page.locator('#program-readiness-summary')).toContainText('1 failed', { timeout: 10_000 });
  await expect(page.locator('.program-status')).toContainText('RETIRED');
  await expect(page.locator('.program-status')).toContainText('cannot be rebuilt or resurrected');
  expect(await page.evaluate(() => window.cy.getElementById('program-0').data('properties').source)).toBe('source 0');
  expect(legacyLifecycleRequests).toHaveLength(0);
});

test('byte fidelity survives save, export, reimport and re-create -- leading newline, trailing newline and first-line indentation all round-trip exactly', async ({ page }) => {
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });

  // All three byte-fidelity hazards appear in one string: a leading newline (the
  // textarea's own HTML-parsing quirk), a trailing newline (every starter has one, so this is the
  // ordinary case, not an edge one), and indentation on the very first content line (what a naive
  // `.trim()` on GraphML import destroys first, being adjacent to the trimmed edge).
  const BYTE_FIDELITY_SOURCE = '\n   function handle() {\n     return 42;\n   }\n';
  await page.locator('.program-source').fill(BYTE_FIDELITY_SOURCE);

  const created = page.waitForResponse(response =>
    response.url().endsWith('/v1/program-artifacts/build') && response.request().method() === 'POST');
  await page.locator('[data-program-operation="build"]').click();
  const firstResult = (await (await created).json()).programs[0];
  expect(firstResult.phase).toBe('REGISTER');
  await expect(page.locator('.program-artifact-id')).toHaveValue('server-artifact-1');

  await page.locator('#node-editor input[name="id"]').fill('byte-fidelity-node');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');
  expect(await page.evaluate(() => window.cy.nodes().map(node => node.id())))
    .toContain('byte-fidelity-node');

  const downloadPromise = page.waitForEvent('download');
  await page.locator('#btn-export').click();
  const download = await downloadPromise;
  const exportedGraphml = readFileSync(await download.path(), 'utf8');
  // If THIS fails, the corruption is on the write side (graph-document.js#setData), not the read
  // side the rest of this test targets -- `setData` writes `String(value)` verbatim, so it should
  // never trim, but a regression there would otherwise be indistinguishable from one in the parser.
  expect(exportedGraphml).toContain(BYTE_FIDELITY_SOURCE);
  expect(exportedGraphml).toContain('attr.name="testPayload"');
  expect(exportedGraphml).toContain('>test payload</data>');
  expect(exportedGraphml).not.toMatch(/sha256|sourceDigest|payloadDigest|programPhase|\bREADY\b/);

  // Reimport as a fresh document: `#file-inp`'s handler (`onFileInput` -> `loadFileObj` ->
  // `openDocument`) opens a NEW workspace tab rather than replacing the active one, so nothing here
  // discards the document just exported -- this is a genuinely independent parse of the GraphML
  // text, through the same `simpleProperties` that builds `node.properties` for the whole app.
  // `window.ravenroot.activeDocument()` (not the bare `window.cy` global) is the stable multi-pane
  // hook `replace-active-workspace.spec.js` uses for the same reason: with a second document now
  // open, waiting on and addressing THE ACTIVE one by name is what actually settles once loading
  // finishes, where `window.cy` alone left this test racing the new pane's own setup.
  await page.locator('#file-inp').setInputFiles({
    name: 'byte-fidelity-roundtrip.graphml',
    mimeType: 'application/xml',
    buffer: Buffer.from(exportedGraphml),
  });
  await expect.poll(() => page.evaluate(() => {
    const active = window.ravenroot.activeDocument();
    return active?.name === 'byte-fidelity-roundtrip.graphml' && !active.cy.scratch('_rrLayoutRunning');
  })).toBe(true);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().cy.nodes().map(node => node.id())))
    .toContain('byte-fidelity-node');

  // Modify is per-DOCUMENT state, not global: opening a file for viewing must not silently put it
  // in an editable state, so the freshly imported document starts with Modify OFF regardless of
  // the original document's own setting. A tap with Modify off opens the read-only info summary,
  // not `#node-editor`/`.program-workspace` -- measured directly while diagnosing this test's first
  // failure, which hung waiting on a panel that a read-only tap was never going to produce.
  await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    window.ravenroot.activeDocument().cy.getElementById('byte-fidelity-node').emit('tap');
  });
  await page.waitForSelector('.program-workspace');

  // What is shown after reopen, following the real save -> export -> reimport path, must be
  // byte-for-byte what the author wrote -- not `.trim()`'s idea of it.
  await expect(page.locator('.program-source')).toHaveValue(BYTE_FIDELITY_SOURCE);

  await expect(page.locator('.program-language')).toHaveValue('javascript', { timeout: 5_000 });

  const buildsBeforeRebuild = buildRequests.length;
  await page.locator('[data-program-operation="build"]').click();
  await expect(page.locator('.program-status')).toContainText('READY');
  expect(buildRequests).toHaveLength(buildsBeforeRebuild + 1);
  expect(buildResults.at(-1)[0]).toMatchObject({ reused: true, artifactId: 'server-artifact-1' });
  expect(legacyLifecycleRequests).toHaveLength(0);
});

test('the language the document names -- not the runtime catalog\'s first entry -- is what a reopen with no matching artifact restores', async ({ page }) => {
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });

  // Explicitly the SECOND catalog entry, never the default: if `storedLanguage` were neutralized,
  // `defaultLanguageId`'s fallback (first declared language) would show 'javascript' here instead,
  // and this test -- unlike an assertion that only ever checks 'javascript', which is both the
  // catalog's first entry and this fixture's default, and so cannot go red -- would catch it.
  page.once('dialog', dialog => dialog.accept()); // Source already holds the JS starter
  await page.locator('.program-language').selectOption('python');
  await expect(page.locator('.program-source')).toHaveValue('PY_STARTER_EXAMPLE\n');

  const created = page.waitForResponse(response =>
    response.url().endsWith('/v1/program-artifacts/build') && response.request().method() === 'POST');
  await page.locator('[data-program-operation="build"]').click();
  await created;
  await expect(page.locator('.program-status')).toContainText('READY');

  await page.locator('#node-editor input[name="id"]').fill('python-node');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');

  // Reopening reads the authored language from GraphML properties; artifact list lookup is no
  // longer part of the browser path.
  await page.evaluate(() => { window.cy.getElementById('python-node').emit('tap'); });
  await page.waitForSelector('.program-workspace');

  await expect(page.locator('.program-language')).toHaveValue('python', { timeout: 5_000 });
  await expect(page.locator('.program-language')).toBeEnabled();
});

test('a document-named language the runtime no longer supports is stated explicitly, never silently swapped', async ({ page }) => {
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });

  page.once('dialog', dialog => dialog.accept());
  await page.locator('.program-language').selectOption('python');

  const created = page.waitForResponse(response =>
    response.url().endsWith('/v1/program-artifacts/build') && response.request().method() === 'POST');
  await page.locator('[data-program-operation="build"]').click();
  await created;
  await expect(page.locator('.program-status')).toContainText('READY');

  await page.locator('#node-editor input[name="id"]').fill('dropped-language-node');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');

  // Leave the saved node before reopening it. A repeat tap on the already-selected node is
  // intentionally debounced, so exercise the actual two-node selection transition instead.
  await page.evaluate(() => { window.cy.getElementById('start').emit('tap'); });
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue('start');

  // The runtime no longer declares python at all; the browser does not consult an artifact listing
  // to replace the document's authored language.
  currentLanguages = [DEFAULT_LANGUAGES[0]]; // javascript only
  const refreshedLanguages = page.waitForResponse(response =>
    response.url().endsWith('/v1/program-languages'));
  await page.evaluate(() => { window.cy.getElementById('dropped-language-node').emit('tap'); });
  await refreshedLanguages;
  await page.waitForSelector('.program-workspace');

  // The author must see the unsupported language instead of having it substituted.
  // The select necessarily shows a real, usable language (javascript, the only one left) -- but the
  // substitution must be NAMED, not folded silently into the ordinary status text as if nothing
  // had happened.
  await expect(page.locator('.program-language-status')).toContainText('python', { timeout: 5_000 });
  await expect(page.locator('.program-language-status')).toContainText('no longer declares support');
  await expect(page.locator('.program-language')).toHaveValue('javascript');
});

test('canonical testPayload stays one string on the wire and the server reports structured or literal output', async ({ page }) => {
  await startService();
  await connectAndModify(page);
  await addProgramNode(page);
  await expect(page.locator('.program-language')).toBeEnabled({ timeout: 5_000 });

  await page.locator('.program-source').fill('json source');
  await page.locator('.program-test-payload').fill('{"ready":true,"count":2}');
  await page.locator('[data-program-operation="build"]').click();
  await expect(page.locator('.program-status')).toContainText('READY');
  await expect(page.locator('.program-output')).toContainText('"ready":true');
  expect(buildRequests[0].programs[0].testPayload).toBe('{"ready":true,"count":2}');

  await page.locator('.program-source').fill('literal source');
  await page.locator('.program-test-payload').fill('plain sample text');
  await page.locator('[data-program-operation="build"]').click();
  await expect(page.locator('.program-output')).toContainText('plain sample text');
  expect(buildRequests[1].programs[0].testPayload).toBe('plain sample text');

  const largeLiteral = 'x'.repeat(9_000);
  await page.locator('.program-source').fill('bounded output source');
  await page.locator('.program-test-payload').fill(largeLiteral);
  await page.locator('[data-program-operation="build"]').click();
  await expect(page.locator('.program-status')).toContainText('READY');
  await expect(page.locator('.program-output')).toContainText('(output truncated)');
  expect((await page.locator('.program-output').textContent()).length).toBeLessThan(8_300);
  expect(buildRequests[2].programs[0].testPayload).toBe(largeLiteral);
  expect(legacyLifecycleRequests).toHaveLength(0);
});

test('external 20-node GraphML uses one modal batch, default-false READY, server reuse and changed binding results', async ({ page }) => {
  buildDelayMs = 250;
  await startService();
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');

  await page.locator('#file-inp').setInputFiles({
    name: 'twenty-programs.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(externalProgramGraphMl(20)),
  });
  const overlay = page.locator('.program-readiness-overlay:not([hidden])');
  await expect(overlay).toBeVisible();
  await expect(overlay).toContainText('Preparing program artifacts');
  await expect(overlay.locator('button, input, select, textarea')).toHaveCount(0);
  expect(await page.evaluate(() => document.querySelector('.doc-pane--active').inert)).toBe(true);
  expect(await page.evaluate(() => {
    const box = document.querySelector('.program-readiness-overlay:not([hidden])').getBoundingClientRect();
    return document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
      .closest('.program-readiness-overlay')?.className;
  })).toContain('program-readiness-overlay');
  await expect(overlay).toContainText(/REGISTER|VALIDATE|SMOKE_TEST|APPROVE_BY_POLICY|ACTIVATE/,
    { timeout: 10_000 });
  await expect(page.locator('#program-readiness-summary')).toContainText('Programs 20/20 ready', { timeout: 20_000 });
  await expect(overlay).toBeHidden();
  expect(await page.evaluate(() => document.querySelector('.doc-pane--active').inert)).toBe(false);
  expect(buildRequests).toHaveLength(1);
  expect(buildRequests[0].programs).toHaveLength(20);
  expect(buildRequests[0].programs.every(program => program.testPayload === 'test payload')).toBe(true);
  expect(buildResults[0].every(result => !result.reused)).toBe(true);
  expect(buildPollRequests).toHaveLength(5);
  expect(approvalRequests).toHaveLength(0);
  expect(legacyLifecycleRequests).toHaveLength(0);

  await page.locator('#file-inp').setInputFiles({
    name: 'twenty-programs-reopened.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(externalProgramGraphMl(20)),
  });
  await expect(page.locator('#program-readiness-summary')).toContainText('Programs 20/20 ready', { timeout: 20_000 });
  expect(buildRequests).toHaveLength(2);
  expect(buildResults[1].every(result => result.reused)).toBe(true);
  expect(legacyLifecycleRequests).toHaveLength(0);

  await page.locator('#file-inp').setInputFiles({
    name: 'twenty-programs-one-payload.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(externalProgramGraphMl(20, { changedPayload: 7 })),
  });
  await expect(page.locator('#program-readiness-summary')).toContainText('Programs 20/20 ready', { timeout: 20_000 });
  expect(buildRequests).toHaveLength(3);
  expect(buildResults[2].filter(result => result.smokeOutput?.requalified)).toHaveLength(1);
  expect(buildResults[2].every(result => result.reused)).toBe(true);

  await page.locator('#file-inp').setInputFiles({
    name: 'twenty-programs-one-changed.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(externalProgramGraphMl(20, { changedSource: 7 })),
  });
  await expect(page.locator('#program-readiness-summary')).toContainText('Programs 20/20 ready', { timeout: 20_000 });
  expect(buildRequests).toHaveLength(4);
  expect(buildResults[3].filter(result => !result.reused)).toHaveLength(1);
  expect(buildResults[3].find(result => !result.reused).nodeId).toBe('program-7');
  expect(legacyLifecycleRequests).toHaveLength(0);
});

test('competing open and catalog triggers post once, and reopen rejoins the durable build', async ({ page }) => {
  nodeCatalogDelayMs = 40;
  buildDelayMs = 200;
  await startService();
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');

  const graphml = externalProgramGraphMl(20);
  await page.locator('#file-inp').setInputFiles({
    name: 'race-programs.graphml', mimeType: 'application/xml', buffer: Buffer.from(graphml),
  });
  const overlay = page.locator('.program-readiness-overlay:not([hidden])');
  await expect(overlay).toContainText('REGISTER', { timeout: 10_000 });
  expect(buildRequests).toHaveLength(1);

  await page.reload();
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#file-inp').setInputFiles({
    name: 'race-programs-reopened.graphml', mimeType: 'application/xml', buffer: Buffer.from(graphml),
  });
  await expect(page.locator('#program-readiness-summary')).toContainText('Programs 20/20 ready',
    { timeout: 20_000 });

  expect(buildRequests).toHaveLength(2);
  expect(buildRequests[0]).toEqual(buildRequests[1]);
  expect(buildsById.size).toBe(1);
  expect(new Set(buildPollRequests)).toEqual(new Set(['server-build-1']));

  await page.locator('#btn-modify').click();
  await page.evaluate(() => { window.cy.getElementById('program-0').emit('tap'); });
  await page.waitForSelector('#node-editor');
  await page.locator('#node-editor button[type="submit"]').click();
  await new Promise(resolve => setTimeout(resolve, 300));
  expect(buildRequests).toHaveLength(2);
  expect(legacyLifecycleRequests).toHaveLength(0);
});

test('dual control pauses new content and uses one independently authenticated graph batch approval', async ({ page }) => {
  dualControl = true;
  await startService();
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#file-inp').setInputFiles({
    name: 'dual-control.graphml', mimeType: 'application/xml', buffer: Buffer.from(externalProgramGraphMl(2)),
  });

  await expect(page.locator('#program-readiness-summary')).toContainText('2 awaiting approval', { timeout: 10_000 });
  await page.locator('#btn-program-approve').click();
  await expect(page.locator('#program-readiness-summary')).toContainText('Programs 2/2 ready');
  expect(approvalRequests).toHaveLength(1);
  expect(approvalRequests[0].artifactIds).toHaveLength(2);
  expect(approvalRequests[0].reason).toContain('Graph-level approval');
  const terminalPollCount = buildPollRequests.length;
  expect(terminalPollCount).toBeGreaterThanOrEqual(5);
  await new Promise(resolve => setTimeout(resolve, 300));
  expect(buildPollRequests).toHaveLength(terminalPollCount);
  expect(new Set(buildPollRequests)).toEqual(new Set(['server-build-1']));
  expect(legacyLifecycleRequests).toHaveLength(0);
});

test('FAILED and RETIRED results select/highlight nodes, retain exact diagnostics, and gate execution', async ({ page }) => {
  retiredSources.add('retired source');
  await startService();
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  const graphml = externalProgramGraphMl(3)
    .replace('source 0', 'syntax failure')
    .replace('source 1', 'smoke failure')
    .replace('source 2', 'retired source');
  await page.locator('#file-inp').setInputFiles({
    name: 'program-failures.graphml', mimeType: 'application/xml', buffer: Buffer.from(graphml),
  });

  await expect(page.locator('#program-readiness-summary')).toContainText('3 failed', { timeout: 10_000 });
  expect(await page.evaluate(() => window.cy.nodes().filter(node => node.data('runtimeState') === 'failed')
    .map(node => node.id()).sort())).toEqual(['program-0', 'program-1', 'program-2']);
  expect(await page.evaluate(() => window.cy.nodes(':selected').map(node => node.id())))
    .toHaveLength(1);
  const activity = page.locator('#activity-log');
  await expect(activity).toContainText('fixture syntax error at line 3');
  await expect(activity).toContainText('SMOKE_FIXTURE_FAILURE');
  await expect(activity).toContainText('retired content cannot be rebuilt or resurrected');
  await expect(page.locator('.program-timeline')).toContainText(/FAILED|RETIRED/);

  await page.locator('#btn-run').click();
  await expect(page.locator('#info-body')).toContainText('Cannot execute');
  await expect(page.locator('#info-body')).toContainText(/fixture syntax error|SMOKE_FIXTURE_FAILURE|retired content/);
  expect(executionRequests).toBe(0);
  expect(legacyLifecycleRequests).toHaveLength(0);
});
