import { expect, test } from '@playwright/test';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';
import { PID, deferred, deploymentService } from './deployment-run-fixture.mjs';
test.beforeEach(async ({ page }) => keepAuthenticationInMemory(page));

// First Run binds the originating document to a server deployment, with or without SOURCEs.
// Closing detaches observation; only explicit Stop may control that exact binding, never undeploy.
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

async function stubRuntime(page, options = {}) {
  const backend = await deploymentService(page, options);
  const sourceCalls = [], executionCalls = [];
  await page.route('**/v1/source-sessions**', route => { sourceCalls.push(route.request().method()); return route.fulfill({ status: 500, json: { error: 'Run must use its deployment binding' } }); });
  await page.route('**/v1/executions?**', route => { executionCalls.push({ method: route.request().method(), url: route.request().url() }); return route.fulfill({ status: 202, json: { executionId: '20000000-0000-0000-0000-000000000002', graphVersion: 'v1', executionPolicy: 'STANDARD' } }); });
  return { sourceCalls, executionCalls, deploymentCalls: backend.calls, backend };
}
const stops = calls => calls.deploymentCalls.filter(call => call.action === 'stop');
const undeploys = calls => calls.deploymentCalls.filter(call => call.method === 'DELETE');

async function openGraphAndRun(page, xml, name) {
  await page.locator('#file-inp').setInputFiles({ name, mimeType: 'application/xml', buffer: Buffer.from(xml) });
  await expect(page.locator('#btn-run')).toBeEnabled();
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
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
  await expect(page.locator('#active-deployment-description')).toContainText('may still be running on the server');
  await expect(page.locator('#active-deployment-description')).toContainText('Closing never undeploys');
  // Not a plain acknowledgement: three distinguishable actions, none of them a bare "OK".
  await expect(page.locator('[data-active-deployment-action="stop-and-close"]')).toBeVisible();
  await expect(page.locator('[data-active-deployment-action="close"]')).toBeVisible();
  await expect(page.locator('[data-active-deployment-action="cancel"]')).toBeVisible();
  expect(stops(calls)).toHaveLength(0);
  // Inspecting the close choice must neither Stop nor Undeploy the saved binding.
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
});

test('"Close (keep running)" detaches observation only -- no Stop and no Undeploy is ever sent', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'kept-running.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="close"]').click();

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  await expect.poll(() => page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(false);
  // Give any accidental async Stop/Undeploy a chance to fire before asserting its absence.
  await page.waitForTimeout(150);
  expect(stops(calls)).toHaveLength(0);
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
});

test('"Stop and Close" reaches authoritative STOPPED before the document actually closes, and never undeploys', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'stopped-and-closed.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="stop-and-close"]').click();

  await expect.poll(() => stops(calls).length > 0, { timeout: 15_000 }).toBe(true);
  await expect.poll(() => page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id),
    { timeout: 15_000 }).toBe(false);
  // Explicit Stop targets the saved deployment; it never removes the registration.
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
});

test('Cancel leaves the document open and sends nothing', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'cancelled-close.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="cancel"]').click();

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  expect(await page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(true);
  expect(stops(calls)).toHaveLength(0);
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
});

test('a failed Stop and Close keeps the document open', async ({ page }) => {
  const calls = await stubRuntime(page, { failStop: true });
  await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'failed-stop.graphml');
  const id = await requestCloseActiveDocument(page);

  await page.locator('[data-active-deployment-action="stop-and-close"]').click();

  await expect.poll(() => stops(calls).length).toBe(1);
  expect(await page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(true);
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
});

test('closing a document after one-shot Test closes immediately without stopping or undeploying', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await page.locator('#file-inp').setInputFiles({
    name: 'one-shot-close.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph('')),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-play').click();
  await expect.poll(() => calls.executionCalls.filter(call => call.method === 'POST').length).toBe(1);
  expect(new URL(calls.executionCalls.find(call => call.method === 'POST').url).searchParams.get('mode')).toBeNull();

  const id = await requestCloseActiveDocument(page);

  await expect(page.locator('#active-deployment-dialog')).toBeHidden();
  await expect.poll(() => page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(false);
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
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
  await expect.poll(() => page.evaluate(documentId => Boolean(window.ravenroot.workspace.find(documentId)), id)).toBe(false);
  expect(undeploys(calls)).toHaveLength(0);
  expect(calls.sourceCalls).toHaveLength(0);
});

test('non-SOURCE Run still owns a deployment binding and Keep Running preserves it', async ({ page }) => {
  const calls = await stubRuntime(page); await page.goto('/');
  await openGraphAndRun(page, graph(''), 'bound-source-less.graphml');
  const id = await requestCloseActiveDocument(page);
  await expect(page.locator('#active-deployment-dialog')).toBeVisible();
  await page.locator('[data-active-deployment-action="close"]').click();
  await expect.poll(() => page.evaluate(id => Boolean(window.ravenroot.workspace.find(id)), id)).toBe(false);
  expect(calls.executionCalls.filter(call => call.method === 'POST')).toHaveLength(0);
  expect(stops(calls)).toHaveLength(0); expect(undeploys(calls)).toHaveLength(0);
  expect(calls.backend.held.size).toBe(1);
});

test('Stop and Close uses the captured origin even after focus moves to another document', async ({ page }) => {
  const body = deferred();
  await page.exposeFunction('__holdOriginStopBody', () => body.promise);
  await page.addInitScript(() => {
    const fetch = globalThis.fetch.bind(globalThis);
    globalThis.__originStopBodyWaiting = false;
    globalThis.fetch = async (input, options) => {
      const response = await fetch(input, options);
      if (options?.method === 'POST' && /\/v1\/deployments\/[^/]+\/stop$/.test(new URL(String(input), location.href).pathname)) {
        const text = response.text.bind(response);
        response.text = async () => {
          globalThis.__originStopBodyWaiting = true;
          await globalThis.__holdOriginStopBody();
          return text();
        };
      }
      return response;
    };
  });
  const calls = await stubRuntime(page); await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'origin.graphml');
  const binding = await page.evaluate(() => {
    const run = window.ravenroot.activeDocument().deploymentRun;
    return { deploymentId: run.deploymentId, incarnationId: run.incarnationId, graphVersion: run.graphVersion };
  });
  expect(binding.incarnationId).toBe('runtime-1');
  expect(binding.graphVersion).toHaveLength(64);
  const origin = await requestCloseActiveDocument(page);
  const other = await page.evaluate(() => window.ravenroot.openDocument({ name: 'other.graphml' }));
  const stopped = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/v1/deployments/${binding.deploymentId}/stop`);
  await page.locator('[data-active-deployment-action="stop-and-close"]').click();
  const response = await stopped;
  expect(response.status()).toBe(200);
  const headers = await response.request().allHeaders();
  expect(headers['x-ravenroot-deployment-incarnation']).toBe(binding.incarnationId);
  expect(headers['x-ravenroot-graph-version']).toBe(binding.graphVersion);
  await expect.poll(() => page.evaluate(() => globalThis.__originStopBodyWaiting)).toBe(true);
  try {
    expect(await page.evaluate(id => Boolean(window.ravenroot.workspace.find(id)), origin)).toBe(true);
    expect(await page.evaluate(id => window.ravenroot.workspace.find(id).deploymentRunStopPending, origin)).toBe(true);
    expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(other);
    expect(stops(calls)).toHaveLength(1);
    // Begin the close observation while the real response body is still held. Returning a rich
    // Document here serializes its graph/viewer/DOM and can prevent the pending Stop from settling.
    // The console event only orders the test's release; it grants no runtime/lifecycle authority.
    const entered = page.waitForEvent('console', message => message.text() === 'origin-close-observer-entered');
    const observed = page.waitForResponse(read => read.request().method() === 'GET'
      && new URL(read.url()).pathname === `/v1/deployments/${binding.deploymentId}`);
    const closed = expect.poll(() => page.evaluate(id => {
      console.debug('origin-close-observer-entered');
      return Boolean(window.ravenroot.workspace.find(id));
    }, origin)).toBe(false);
    // Attach both tasks immediately: observer errors and response errors must fail the test.
    await Promise.all([closed, (async () => {
      await entered;
      body.resolve();
      expect((await (await observed).json()).state).toBe('STOPPED');
    })()]);
  } finally { body.resolve(); }
  expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(other);
  expect(stops(calls)).toHaveLength(1); expect(undeploys(calls)).toHaveLength(0);
  expect(calls.backend.calls.filter(call => call.action === 'initial-start')).toHaveLength(1);
  expect(calls.backend.calls.filter(call => call.action === 'start')).toHaveLength(1);
  expect(calls.sourceCalls).toHaveLength(0);
});

for (const field of ['tenantId', 'graphVersion', 'registrationIdentity']) test(`changed ${field} keeps the origin open and sends no Stop`, async ({ page }) => {
  const calls = await stubRuntime(page); await page.goto('/');
  await openGraphAndRun(page, graph('external.consume'), 'origin.graphml');
  const id = await requestCloseActiveDocument(page);
  const status = [...calls.backend.held.values()][0];
  if (field === 'registrationIdentity') { status.continuity = 'DURABLE'; status.registrationIdentity = 'replacement'; }
  else status[field] = field === 'graphVersion' ? 'f'.repeat(64) : 'sibling';
  await page.locator('[data-active-deployment-action="stop-and-close"]').click();
  await expect(page.locator('#activity-log')).toContainText('could not be stopped');
  expect(await page.evaluate(id => Boolean(window.ravenroot.workspace.find(id)), id)).toBe(true);
  expect(stops(calls)).toHaveLength(0); expect(undeploys(calls)).toHaveLength(0);
});
