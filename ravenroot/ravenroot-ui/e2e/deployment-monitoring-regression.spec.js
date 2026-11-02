import { expect, test } from '@playwright/test';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';
import { PID, deferred, deploymentService, interruptMockDeploymentObservation } from './deployment-run-fixture.mjs';
const graph = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="kind" for="node" attr.name="kind" attr.type="string"/><key id="b" for="node" attr.name="behavior" attr.type="string"/><graph id="mixed" edgedefault="directed"><node id="start"><data key="kind">START</data></node><node id="source"><data key="kind">BEHAVIOR</data><data key="b">external.consume</data></node><node id="end"><data key="kind">END</data></node><node id="error"><data key="kind">ERROR</data></node><edge id="first" source="start" target="source"/><edge id="second" source="source" target="end"/></graph></graphml>`;
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
    const edge = { ...event, type: 'EDGE_TRAVERSED', eventType: 'EDGE_TRAVERSED', sequence: 2, id: '2', nodeId: null, edgeId: 'first' };
    await route.fulfill({ contentType: 'text/event-stream', body: `id: 1\nevent: execution\ndata: ${JSON.stringify(event)}\n\nid: 2\nevent: execution\ndata: ${JSON.stringify(edge)}\n\n` });
  });
  await boot(page);
  await interruptMockDeploymentObservation(page); // This case exercises the retained legacy fallback.
  await page.evaluate(() => { window.ravenroot.activeDocument().deploymentRun.registrationIdentity = 'persistent-registration'; });
  if (mode === 'monitoring') await page.locator('#btn-monitoring').click();
  await page.evaluate(() => window.ravenroot.activeDocument()); // capture the new presentation before traffic
  traffic.resolve();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().execution.events.length)).toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.getElementById('source').data('runtimeState'))).toBe('completed');
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('source').style('border-color'))).toBe('rgb(35,134,54)');
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('first').style('line-color'))).toBe('rgb(35,134,54)');
  await expect(page.locator('#btn-program-approve')).toBeHidden();
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
  await expect.poll(() => page.evaluate(() => window.ravenroot?.activeDocument()?.deploymentRunStatus)).toBe('UNKNOWN');
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
    expect(new URL(route.request().url()).searchParams.get('include')).toBe('diagnostics');
    const source = { deploymentId: status.deploymentId, graphVersion: status.graphVersion, incarnationId: status.runtimeIncarnationId };
    const frames = [
      `event: lifecycle\ndata: ${JSON.stringify({ ...source, lifecycle: 'READY', cursor: 'c'.repeat(43) })}\n\n`,
      `id: hosted-1\nevent: execution\ndata: ${JSON.stringify({ ...source, event: { type: 'NODE_COMPLETED', executionId: 'hosted-traversal', traversalId: 'hosted-traversal', processInstanceId: 'hosted-process', invocationId: 'hosted-visit', output: 'Hosted log visible', outputRedacted: false, outputTruncated: false, occurredAt: '2026-10-25T12:00:00Z', nodeId: 'source', activeInstances: 0, inFlightArrivals: 0 } })}\n\n`,
    ];
    return route.fulfill({ contentType: 'text/event-stream', body: frames.join('') });
  });
  await boot(page);
  expect(order.indexOf('observe')).toBeLessThan(order.indexOf('start'));
  await expect(page.locator('#activity-log')).toContainText('Hosted log visible');
  await expect(page.locator('.doc-pane--active .doc-run-state')).toContainText('Listening');
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.getElementById('source').data('runtimeState'))).toBe('completed');
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.nodes.find(node => node.id === 'source').runtimeState)).toBe('completed');
});


test('missing saved registration explains recovery and starts a separate document only on explicit command', async ({ page }) => {
  const backend = await deploymentService(page); await boot(page);
  page.removeAllListeners('dialog');
  page.on('dialog', dialog => dialog.message().startsWith('The previous deployment') ? dialog.dismiss() : dialog.accept());
  const original = await page.evaluate(async () => {
    const owner = window.ravenroot.activeDocument();
    owner.deploymentRun.registrationIdentity = 'retained-registration';
    await window.ravenroot.flushWorkspacePersistence();
    return { id: owner.id, binding: { ...owner.deploymentRun } };
  });
  backend.held.clear();
  await page.locator('#btn-run').click();
  await expect(page.locator('#activity-log')).toContainText('The saved deployment is unavailable');
  expect(backend.calls.filter(call => call.method === 'POST' && !call.action)).toHaveLength(1);
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Run as new deployment…', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
  const current = await page.evaluate(() => ({ id: window.ravenroot.activeDocument().id, binding: window.ravenroot.activeDocument().deploymentRun }));
  expect(current.id).not.toBe(original.id);
  expect(current.binding.deploymentId).not.toBe(original.binding.deploymentId);
  expect(current.binding.initialStartRequestId).not.toBe(original.binding.initialStartRequestId);
  const retained = await page.evaluate(id => window.ravenroot.workspace.documents.find(doc => doc.id === id)?.deploymentRun, original.id);
  expect(retained.deploymentId).toBe(original.binding.deploymentId);
  expect(retained.traversalId).toBe(original.binding.traversalId);
  expect(backend.calls.filter(call => call.method === 'POST' && !call.action)).toHaveLength(2);
});


test('reload of a removed deployment restores truthful context and Run offers recovery directly', async ({ page }) => {
  const backend = await deploymentService(page); await boot(page);
  let recover = false; const prompts = [];
  page.removeAllListeners('dialog');
  page.on('dialog', dialog => {
    prompts.push(dialog.message());
    return dialog.message().startsWith('The previous deployment') && !recover ? dialog.dismiss() : dialog.accept();
  });
  const original = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.deploymentRun.registrationIdentity = 'retained-registration';
    return { id: owner.id, binding: { ...owner.deploymentRun } };
  });
  backend.held.clear();
  await page.locator('#btn-run').click();
  await expect(page.locator('#activity-log')).toContainText('The saved deployment is unavailable');
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  const writes = backend.calls.filter(call => call.method === 'POST').length;
  await page.reload();
  await expect(page.locator('#activity-log')).toContainText('Restored 2 saved documents');
  await expect(page.locator('#activity-log')).not.toContainText('Minimal Start');
  expect(backend.calls.filter(call => call.method === 'POST')).toHaveLength(writes);
  prompts.length = 0; recover = true;
  await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.deploymentId)).not.toBe(original.binding.deploymentId);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
  const current = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
  expect(current.deploymentId).not.toBe(original.binding.deploymentId);
  expect(current.initialStartRequestId).not.toBe(original.binding.initialStartRequestId);
  expect(prompts).toHaveLength(2); // Normal Run and explicit recovery, no third approval.
  expect(prompts[1]).toContain(original.binding.deploymentId);
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id).deploymentRun.traversalId, original.id)).toBe(original.binding.traversalId);
  expect(backend.calls.filter(call => call.method === 'POST' && !call.action)).toHaveLength(2);
});
