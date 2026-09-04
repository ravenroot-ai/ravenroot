import { expect, test } from '@playwright/test';

// Closing a document must never masquerade as Stop. A document whose Run started a local
// listener session (an effective-SOURCE graph) is owned by the server, not by the browser
// tab, and keeps running until an explicit Stop -- `closeDocument` only ever detaches this browser's
// own observation of it. This suite pins the interaction that makes that distinction real and
// visible: closing a document with an active
// session opens an explicit dialog naming the difference, with a genuine three-way choice (Stop and
// Close / Close and keep running / Cancel) rather than a single "OK" a person could click through
// without reading.
//
// A source-less graph's Run is a real one-shot execution rather than a session, so there is nothing
// for THIS dialog to
// say about it; the coverage for that case moved to the "no active session closes immediately" test
// below. A deployment registered through the separate Deployments panel is tenant-scoped, not
// document-scoped (see `deployment-panel.js`), so it has no relationship to any open document at all:
// this suite also pins that closing a document never sends a `/v1/deployments/**` request of any
// kind, DELETE (undeploy) included -- the "never as Stop" guarantee this suite is named for now reads
// as "never as Stop, and never as Undeploy either", because Undeploy exists only as
// an explicit button on that panel.

const SOURCE_CATALOG = JSON.stringify([{
  behavior: 'external.consume', displayName: 'External consumer', category: 'Sources',
  description: 'Receives external events.', visualType: 'actor', agentic: false,
  capabilities: [], properties: [], defaultNature: 'SOURCE', allowedNatures: ['SOURCE'],
  natureProperty: 'runtime.nature',
}]);

const graph = behavior => `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <graph id="close-not-stop" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    ${behavior ? `<node id="source"><data key="kind">BEHAVIOR</data><data key="behavior">${behavior}</data></node>` : ''}
    <node id="end"><data key="kind">END</data></node>
    ${behavior
      ? '<edge source="start" target="source"><data key="outcome">continue</data></edge><edge source="source" target="end"><data key="outcome">continue</data></edge>'
      : '<edge source="start" target="end"><data key="outcome">continue</data></edge>'}
  </graph>
</graphml>`;

async function stubRuntime(page, { failStop = false } = {}) {
  const sourceCalls = [];
  const deploymentCalls = [];
  const executionCalls = [];
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: SOURCE_CATALOG,
  }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
  // Run on a non-SOURCE graph is a one-shot execution rather than a deployment registration, so
  // this fixture supplies the execution route used by the test below.
  await page.route('**/v1/executions**', async route => {
    executionCalls.push({ method: route.request().method(), url: route.request().url() });
    await route.fulfill({
      status: 202, contentType: 'application/json',
      body: JSON.stringify({ executionId: 'one-shot', graphVersion: 'v1', executionPolicy: 'STANDARD' }),
    });
  });
  await page.route('**/v1/source-sessions**', async route => {
    const request = route.request();
    sourceCalls.push({ method: request.method(), url: request.url() });
    if (request.method() === 'DELETE' && failStop) {
      await route.fulfill({
        status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'unavailable' }),
      });
      return;
    }
    const sessionId = new URL(request.url()).searchParams.get('id')
      || decodeURIComponent(new URL(request.url()).pathname.split('/').at(-1));
    const state = request.method() === 'POST' ? 'STARTING'
      : request.method() === 'DELETE' ? 'STOPPED' : 'LISTENING';
    await route.fulfill({
      status: request.method() === 'POST' ? 202 : 200, contentType: 'application/json',
      body: JSON.stringify({ sessionId, state, sourceCount: 1, scope: 'LOCAL_PROCESS', diagnostic: null }),
    });
  });
  await page.route('**/v1/deployments**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const segments = url.pathname.split('/').filter(Boolean);
    const command = segments.length > 3 ? segments.at(-1) : null;
    const deploymentId = url.searchParams.get('id') || (segments.length >= 3 ? decodeURIComponent(segments[2]) : '');
    deploymentCalls.push({ method: request.method(), url: request.url() });
    const state = command === 'start' ? 'READY' : command === 'stop' ? 'STOPPED' : 'REGISTERED';
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ deploymentId, state, sourceCount: 0, scope: 'LOCAL_PROCESS', diagnostic: null }),
    });
  });
  return { sourceCalls, deploymentCalls, executionCalls };
}

async function openGraphAndRun(page, xml, name) {
  await page.locator('#file-inp').setInputFiles({ name, mimeType: 'application/xml', buffer: Buffer.from(xml) });
  await expect(page.locator('#btn-run')).toBeEnabled();
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening');
}

async function requestCloseActiveDocument(page) {
  const id = await page.evaluate(() => window.ravenroot.workspace.activeId);
  await page.evaluate(documentId => window.ravenroot.requestCloseDocument(documentId), id);
  return id;
}

test('closing a document with an active SOURCE listener opens the stop-vs-close choice, not a plain close', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'listener.graphml');

  await requestCloseActiveDocument(page);

  const dialog = page.locator('#active-deployment-dialog');
  await expect(dialog).toBeVisible();
  await expect(page.locator('#active-deployment-description')).toContainText('only stops watching');
  await expect(page.locator('#active-deployment-description')).toContainText('keeps running in this server process');
  await expect(page.locator('#active-deployment-description')).toContainText('LOCAL_PROCESS');
  // Not a plain acknowledgement: three distinguishable actions, none of them a bare "OK".
  await expect(page.locator('[data-active-deployment-action="stop-and-close"]')).toBeVisible();
  await expect(page.locator('[data-active-deployment-action="close"]')).toBeVisible();
  await expect(page.locator('[data-active-deployment-action="cancel"]')).toBeVisible();
  expect(calls.sourceCalls.filter(call => call.method === 'DELETE')).toHaveLength(0);
  // Undeploy lives only on the separate Deployments panel; opening/inspecting this dialog must never
  // reach /v1/deployments at all -- it has no relationship to a source-session listener.
  expect(calls.deploymentCalls).toHaveLength(0);
});

test('"Close (keep running)" detaches observation only -- no Stop and no Undeploy is ever sent', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'kept-running.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="close"]').click();

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  await expect.poll(() => page.evaluate(documentId => window.ravenroot.workspace.find(documentId), id)).toBeNull();
  // Give any accidental async Stop/Undeploy a chance to fire before asserting its absence.
  await page.waitForTimeout(150);
  expect(calls.sourceCalls.filter(call => call.method === 'DELETE')).toHaveLength(0);
  expect(calls.deploymentCalls).toHaveLength(0);
});

test('"Stop and Close" reaches authoritative STOPPED before the document actually closes, and never undeploys', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'stopped-and-closed.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="stop-and-close"]').click();

  await expect.poll(() => calls.sourceCalls.some(call => call.method === 'DELETE'), { timeout: 15_000 }).toBe(true);
  await expect.poll(() => page.evaluate(documentId => window.ravenroot.workspace.find(documentId), id),
    { timeout: 15_000 }).toBeNull();
  // Stop is a source-session DELETE, never a /v1/deployments request of any kind.
  expect(calls.deploymentCalls).toHaveLength(0);
});

test('Cancel leaves the document open and sends nothing', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'cancelled-close.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="cancel"]').click();

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  expect(await page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(true);
  expect(calls.sourceCalls.filter(call => call.method === 'DELETE')).toHaveLength(0);
  expect(calls.deploymentCalls).toHaveLength(0);
});

test('a failed Stop and Close keeps the document open', async ({ page }) => {
  const calls = await stubRuntime(page, { failStop: true });
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'failed-stop.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="stop-and-close"]').click();

  await expect.poll(() => calls.sourceCalls.filter(call => call.method === 'DELETE').length).toBe(1);
  expect(await page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(true);
  expect(calls.deploymentCalls).toHaveLength(0);
});

// A source-less graph's Run is a real one-shot execution, not a session of any kind, so there is
// nothing running for the document to ask about on close, exactly like the "never run" case below.
// This also means Run
// itself must never touch /v1/deployments: that route is reached only through the separate
// Deployments panel now (see deployment-panel.spec.js), never through this button.
test('closing a document after Run on a non-SOURCE graph closes immediately -- Run was a one-shot execution, not a session', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await page.locator('#file-inp').setInputFiles({
    name: 'one-shot-close.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph('')),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect.poll(() => calls.executionCalls.filter(call => call.method === 'POST').length).toBe(1);
  expect(calls.executionCalls.find(call => call.method === 'POST').url).toContain('mode=run');

  const id = await requestCloseActiveDocument(page);

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  await expect.poll(() => page.evaluate(documentId => window.ravenroot.workspace.find(documentId), id)).toBeNull();
  expect(calls.deploymentCalls).toHaveLength(0);
});

test('closing a document with no active session closes immediately, with no dialog at all', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await page.locator('#file-inp').setInputFiles({
    name: 'never-run.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph('')),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();
  const id = await requestCloseActiveDocument(page);

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  await expect.poll(() => page.evaluate(documentId => window.ravenroot.workspace.find(documentId), id)).toBeNull();
  expect(calls.deploymentCalls).toHaveLength(0);
});
