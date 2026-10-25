import { expect, test } from '@playwright/test';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';
import { PID, deferred, deploymentService } from './deployment-run-fixture.mjs';
const graph = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="kind" for="node" attr.name="kind" attr.type="string"/><key id="b" for="node" attr.name="behavior" attr.type="string"/><graph id="mixed" edgedefault="directed"><node id="start"><data key="kind">START</data></node><node id="source"><data key="kind">BEHAVIOR</data><data key="b">external.consume</data></node><node id="end"><data key="kind">END</data></node><node id="error"><data key="kind">ERROR</data></node><edge source="start" target="source"/><edge source="source" target="end"/></graph></graphml>`;
async function boot(page) {
  await keepAuthenticationInMemory(page);
  page.on('dialog', dialog => dialog.accept());
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => window.ravenroot?.workspacePersistence().authority.tenantId)).toBe('tenant');
  await page.locator('#file-inp').setInputFiles({ name: 'monitor.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph) });
  await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
}
for (const mode of ['design', 'monitoring']) test(`Run paints durable events in ${mode} without polling hosted START as a one-shot`, async ({ page }, testInfo) => {
  const backend = await deploymentService(page), traffic = deferred(), resultReads = [];
  await page.route('**/v1/executions/**', route => { resultReads.push(route.request().url()); return route.fulfill({ status: 404, json: { code: 'UNKNOWN_EXECUTION', error: 'unknown execution' } }); });
  await page.route('**/v1/events**', async route => {
    await traffic.promise;
    const status = [...backend.held.values()][0];
    const event = { type: 'NODE_COMPLETED', eventType: 'NODE_COMPLETED', schemaVersion: 1, sequence: 1, id: '1', source: 'RING', engineId: 'fixture',
      fallback: false, publicReason: null, message: null, messageRedacted: false, messageTruncated: false, processingDuration: null,
      graphVersion: status.graphVersion, deploymentId: 'persistent-registration', executionId: '30000000-0000-4000-8000-000000000001', traversalId: '30000000-0000-4000-8000-000000000001',
      processInstanceId: '40000000-0000-4000-8000-000000000001', nodeId: 'source', activeInstances: 0, inFlightArrivals: 0, occurredAt: '2026-10-25T12:00:00Z' };
    await route.fulfill({ contentType: 'text/event-stream', body: `id: 1\nevent: execution\ndata: ${JSON.stringify(event)}\n\n` });
  });
  await boot(page);
  await page.evaluate(() => { window.ravenroot.activeDocument().deploymentRun.registrationIdentity = 'persistent-registration'; });
  if (mode === 'monitoring') await page.locator('#btn-monitoring').click();
  await page.evaluate(() => window.ravenroot.activeDocument()); // capture the new presentation before traffic
  traffic.resolve();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().execution.events.length)).toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.getElementById('source').data('runtimeState'))).toBe('completed');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().execution.executionId)).toBeNull();
  expect(resultReads).toEqual([]);
  await expect(page.locator('.doc-history-dialog[open]')).toHaveCount(0);
  if (mode === 'monitoring') {
    await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.nodes.find(n => n.id === 'source').runtimeState)).toBe('completed');
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.nodeSelection.filter(n => n.id === 'source').attr('stroke'))).toMatch(/#238636|rgb\(35, 134, 54\)/);
  }
  await page.screenshot({ path: testInfo.outputPath(`durable-${mode}.png`) });
});

test('reload observes missing deployment without activation and keeps Stop disabled', async ({ page }) => {
  const backend = await deploymentService(page); await boot(page);
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  backend.held.clear(); const mutations = backend.calls.filter(call => call.method === 'POST').length;
  await page.reload();
  await expect.poll(() => page.evaluate(() => window.ravenroot?.activeDocument()?.deploymentRunStatus)).toBe('STOPPED');
  await expect(page.locator('#btn-stop')).toBeDisabled();
  await expect(page.locator('#btn-run')).toBeEnabled();
  expect(backend.calls.filter(call => call.method === 'POST')).toHaveLength(mutations);
});

test('History opens separately and returns to the same elastic graph', async ({ page }) => {
  await deploymentService(page); await boot(page);
  await page.route('**/v1/deployments/*/history?**', async route => {
    const binding = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
    const view = new URL(route.request().url()).searchParams.get('view');
    await route.fulfill({ json: { source: { kind: 'deployment', deploymentId: binding.deploymentId, incarnationId: binding.incarnationId, graphVersion: binding.graphVersion }, view,
      items: view === 'policy' ? [{ maxPageSize: 10 }] : [], nextCursor: null, availability: 'AVAILABLE' } });
  });
  await page.locator('#btn-monitoring').click(); await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await page.locator('#btn-history').click(); await expect(page.locator('.doc-history-dialog[open]')).toBeVisible();
  await expect(page.locator('.doc-history-dialog')).toContainText('Business histories');
  await page.getByRole('button', { name: 'Return to graph', exact: true }).click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
});

for (const mode of ['design', 'monitoring']) test(`Open read-only view paints the attached deployment in ${mode}`, async ({ page }) => {
  const backend = await deploymentService(page), traffic = deferred(); await boot(page);
  const binding = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
  await page.route('**/v1/deployments', route => route.fulfill({ json: { deployments: [...backend.held.values()] } }));
  await page.route('**/v1/deployments/*/events?**', async route => {
    await traffic.promise;
    const frame = { type: 'execution', deploymentId: binding.deploymentId, graphVersion: binding.graphVersion, incarnationId: binding.incarnationId,
      cursor: 'source-1', processInstanceId: PID, event: { type: 'NODE_COMPLETED', executionId: 'source-traversal', nodeId: 'source', activeInstances: 0, inFlightArrivals: 0 } };
    await route.fulfill({ contentType: 'text/event-stream', body: `id: source-1\nevent: execution\ndata: ${JSON.stringify(frame)}\n\n` });
  });
  await page.locator('#menu-run').click(); await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator(`[data-deployment-view="${binding.deploymentId}"]`).click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().graph.format)).toBe('deployment');
  if (mode === 'monitoring') await page.locator('#btn-monitoring').click();
  traffic.resolve();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.getElementById('source').data('runtimeState'))).toBe('completed');
  if (mode === 'monitoring') {
    await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.nodes.find(n => n.id === 'source').runtimeState)).toBe('completed');
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.nodeSelection.filter(n => n.id === 'source').attr('stroke'))).toMatch(/#238636|rgb\(35, 134, 54\)/);
  }
});

test('Test still paints an edited draft that also has an older deployment binding', async ({ page }) => {
  await deploymentService(page); const traffic = deferred();
  await page.route('**/v1/events**', async route => {
    await traffic.promise;
    await route.fulfill({ contentType: 'text/event-stream', body: `id: 1\nevent: execution\ndata: ${JSON.stringify({ type: 'NODE_COMPLETED',
      executionId: 'draft-test', graphVersion: 'draft-v2', nodeId: 'source', activeInstances: 0, inFlightArrivals: 0, sequence: 1 })}\n\n` });
  });
  await boot(page);
  await page.locator('#btn-modify').click();
  await page.evaluate(() => { window.cy.getElementById('source').select(); });
  await page.locator('#node-editor input[name="name"]').fill('Edited draft source');
  await page.locator('#node-editor input[name="name"]').press('Tab');
  await page.route('**/v1/executions**', route => route.fulfill({ json: route.request().method() === 'POST'
    ? { executionId: 'draft-test', graphVersion: 'draft-v2', executionPolicy: 'TEST_PASSTHROUGH' }
    : { executionId: 'draft-test', status: 'COMPLETED' } }));
  await page.locator('#btn-play').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().execution.executionId)).toBe('draft-test');
  traffic.resolve();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.getElementById('source').data('runtimeState'))).toBe('completed');
});

test('hosted traversal activity uses the deployment stream while the one-shot stream is silent', async ({ page }) => {
  const backend = await deploymentService(page), order = [];
  page.on('request', request => {
    if (request.url().includes('/events?graphVersion=')) order.push('observe');
    if (request.url().endsWith('/initial-start')) order.push('start');
  });
  await page.route('**/v1/deployments/*/events?**', route => {
    const status = [...backend.held.values()][0];
    const source = { deploymentId: status.deploymentId, graphVersion: status.graphVersion, incarnationId: status.runtimeIncarnationId };
    const frames = [
      `event: lifecycle\ndata: ${JSON.stringify({ ...source, lifecycle: 'READY' })}\n\n`,
      `id: hosted-1\nevent: execution\ndata: ${JSON.stringify({ ...source, event: { type: 'NODE_COMPLETED', executionId: 'hosted-traversal', nodeId: 'source', activeInstances: 0, inFlightArrivals: 0 } })}\n\n`,
    ];
    return route.fulfill({ contentType: 'text/event-stream', body: frames.join('') });
  });
  await boot(page);
  expect(order.indexOf('observe')).toBeLessThan(order.indexOf('start'));
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.getElementById('source').data('runtimeState'))).toBe('completed');
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.nodes.find(node => node.id === 'source').runtimeState)).toBe('completed');
});
