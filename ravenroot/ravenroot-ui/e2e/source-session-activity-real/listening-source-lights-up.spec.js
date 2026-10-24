import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
const ORIGIN = process.env.RAVENROOT_SOURCE_SESSION_ORIGIN;
const GRAPH_PATH = process.env.RAVENROOT_SOURCE_SESSION_GRAPH;
const WINDOW_MS = Number(process.env.RAVENROOT_SOURCE_SESSION_WINDOW_MS || 22_000);
const OUTPUT_DIR = process.env.RAVENROOT_SOURCE_SESSION_OUTPUT_DIR || '.';
function requireHarnessEnvironment() { if (!ORIGIN || !GRAPH_PATH) throw new Error('requires the real server/source/store harness'); }
test('one refused start produces one actionable row and focuses its node property', async ({ page }) => {
  requireHarnessEnvironment();
  const graph = (await readFile(GRAPH_PATH, 'utf8'))
    .replace('<data key="intervalMs">700</data>', '<data key="intervalMs">not-an-integer</data>');
  const sourceStarts = [];
  page.on('request', request => {
    if (request.method() === 'POST' && (/\/v1\/deployments(?:\/|$)/).test(new URL(request.url()).pathname)) {
      sourceStarts.push(request.url());
    }
  });

  await page.goto(`${ORIGIN}/`);
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });
  await page.locator('#file-inp').setInputFiles({
    name: 'inadmissible-source.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();
  await page.locator('#btn-run').click();

  const refusal = page.locator('#activity-log .activity-entry.failed')
    .filter({ has: page.locator('.activity-title', { hasText: 'Graph admission refused' }) });
  await expect(refusal).toHaveCount(1);
  await expect(refusal.locator('.activity-detail')).toContainText('PROPERTY_TYPE_INVALID');
  await expect(refusal.locator('.activity-detail')).toContainText('property intervalMs');
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('consume').selected())).toBe(true);
  await expect(page.locator('[data-catalog-property="intervalMs"]')).toBeFocused();
  expect(sourceStarts, 'inspection must refuse before any listener-start request').toEqual([]);
});


test('real SOURCE traffic stays with its originating document and appears on exact pinned histories', async ({ page }) => {
  requireHarnessEnvironment(); test.setTimeout(90_000);
  const errors = [], commands = [];
  page.on('pageerror', error => errors.push(String(error)));
  page.on('dialog', dialog => dialog.accept());
  page.on('request', request => { if (request.method() === 'POST' && /\/v1\/deployments(?:\/|$)/.test(new URL(request.url()).pathname))
    commands.push({ path: new URL(request.url()).pathname, headers: request.headers() }); });
  await page.goto(ORIGIN); await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });
  await page.locator('#file-inp').setInputFiles({ name: 'listening-source.graphml', mimeType: 'application/xml', buffer: Buffer.from(await readFile(GRAPH_PATH)) });
  await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => { const b = window.ravenroot.activeDocument().deploymentRun;
    return Boolean(b?.phase === 'READY' && b.processInstanceId && b.traversalId); }), { timeout: 30_000 }).toBe(true);
  const origin = await page.evaluate(() => ({ id: window.ravenroot.activeDocument().id, binding: { ...window.ravenroot.activeDocument().deploymentRun } }));
  const began = Date.now();
  if (await page.locator('.doc-history-dialog[open]').count()) await page.getByRole('button', { name: 'Return to graph', exact: true }).click();
  await page.locator('#btn-modify').click(); await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="id"]').fill('local-draft-only');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.evaluate(() => window.ravenroot.openDocument({ name: 'focused-other.graphml' }));
  const other = await page.evaluate(() => window.ravenroot.activeDocument().id);
  // The recent-event endpoint is a bounded tail, so sample it throughout the observation window.
  // These test-only sets prove distinct real wire identities without requiring cumulative UI payload retention.
  const traffic = await page.evaluate(async ({ binding, remaining }) => {
    const source = new Map(), logs = new Set(), deadline = Date.now() + remaining; let latest;
    do {
    const response = await fetch('/v1/events/recent?include=diagnostics'); if (!response.ok) throw new Error('recent events refused');
    const body = await response.json(), events = body.events.filter(e => e.deploymentId === binding.deploymentId && e.graphVersion === binding.graphVersion);
    for (const event of events) {
      if (event.type === 'NODE_STARTED' && event.nodeId === 'consume') { source.set(event.processInstanceId, true); latest = event; }
      if (event.type === 'NODE_COMPLETED' && event.nodeId === 'log-admission') logs.add(event.processInstanceId);
    }
    if (source.size > 128 || logs.size > 128) throw new Error('unexpected unbounded test traffic');
    if (Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 500));
    } while (Date.now() < deadline);
    return { sourceProcesses: [...source.keys()], logProcesses: [...logs], latest };
  }, { binding: origin.binding, remaining: Math.max(0, WINDOW_MS - (Date.now() - began)) });
  const observed = { ...traffic, ...await page.evaluate(({ id, other }) => {
    const owner = window.ravenroot.workspace.find(id), foreign = window.ravenroot.workspace.find(other);
    return { processOwners: owner.sourceSession.processOwners.size,
      recentTraversals: new Set(owner.execution.events.map(e => e.traversalId || e.executionId)).size,
      foreignEvents: foreign.execution.events.length, activeId: window.ravenroot.activeDocument().id,
      sourceDraftState: owner.cy.getElementById('consume').data('runtimeState') || 'idle',
      logDraftState: owner.cy.getElementById('log-admission').data('runtimeState') || 'idle',
      localEdit: Boolean(owner.graph.nodeMap['local-draft-only']), binding: { ...owner.deploymentRun } };
  }, { id: origin.id, other }) };
  expect(observed.sourceProcesses.length).toBeGreaterThanOrEqual(20); expect(observed.logProcesses.length).toBeGreaterThanOrEqual(20);
  expect(observed.processOwners).toBeGreaterThanOrEqual(20); expect(observed.recentTraversals).toBeGreaterThan(1);
  expect(observed).toMatchObject({ foreignEvents: 0, activeId: other, sourceDraftState: 'idle', logDraftState: 'idle', localEdit: true, binding: origin.binding });
  await page.evaluate(id => window.ravenroot.activateDocument(id), origin.id);
  await expect(page.locator('#activity-log .activity-entry.output-value').last()).toContainText(/admitted message-\d+/, { timeout: 10_000 });
  await page.locator('[data-menu="layout"]').click(); await page.getByRole('menuitemradio', { name: 'Monitoring', exact: true }).click();
  const monitor = page.locator('.doc-history'); await expect(monitor).toBeVisible();
  await expect(page.locator('.doc-history-draft').filter({ visible: true })).toContainText('Your draft differs from this deployment');
  const histories = monitor.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Business histories', exact: true }) });
  await histories.getByRole('button', { name: 'Refresh page', exact: true }).click();
  await expect(histories.locator('li')).toHaveCount(10);
  const firstPage = await histories.locator('li button').allTextContents();
  await histories.getByRole('button', { name: 'Next page', exact: true }).click();
  await expect(histories.locator('li')).toHaveCount(10);
  await expect.poll(() => histories.locator('li button').allTextContents()).not.toEqual(firstPage);
  await histories.getByLabel('Exact application ID', { exact: true }).fill(observed.latest.applicationCorrelationId);
  await histories.getByRole('button', { name: 'Search histories', exact: true }).click();
  await expect(histories.locator('li')).toHaveCount(1); await histories.locator('li button').click();
  const traversals = monitor.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Actual traversals', exact: true }) });
  await expect(traversals.locator('li')).toHaveCount(1); await expect(traversals).toContainText('Entry consume');
  await traversals.locator('li button').click();
  const preview = monitor.locator('.history-traversal'); const canvas = preview.locator('[data-viewer-canvas]');
  await expect(preview).toContainText('Complete coverage: 3 visited nodes and 2 traversed edges');
  await canvas.scrollIntoViewIfNeeded();
  await expect.poll(() => canvas.evaluate(node => { const cy = node._cyreg.cy, b = cy.elements().renderedBoundingBox();
    return b.x1 >= 0 && b.y1 >= 0 && b.x2 <= node.clientWidth + 1 && b.y2 <= node.clientHeight + 1; })).toBe(true);
  const topology = await canvas.evaluate(node => { const cy = node._cyreg.cy; return { nodes: cy.nodes().length, edges: cy.edges().length,
    source: cy.getElementById('consume').data('runtimeState'), log: cy.getElementById('log-admission').data('runtimeState'),
    entryCount: cy.edges().filter(e => e.source().id() === 'start').first().data('runtimeCount'),
    sourceCount: cy.edges().filter(e => e.source().id() === 'consume').first().data('runtimeCount'), point: cy.getElementById('log-admission').renderedPosition() }; });
  expect(topology).toMatchObject({ nodes: 5, edges: 3, source: 'completed', log: 'completed', entryCount: 0, sourceCount: 1 });
  const box = await canvas.boundingBox(); await page.mouse.click(box.x + topology.point.x, box.y + topology.point.y);
  await expect(preview.locator('.history-inspector h4')).toHaveText('log-admission');
  await expect(preview.locator('.history-node-totals')).toHaveText('Total visits 1 · Attempts 1 · Active 0 · Failed visits 0');
  await preview.locator('[data-design-element="consume"]').click();
  await expect(preview.locator('.history-node-totals')).toHaveText('Total visits 1 · Attempts 1 · Active 0 · Failed visits 0');
  await page.screenshot({ path: join(OUTPUT_DIR, 'listening-source-pinned-history.png'), fullPage: true });
  await page.getByRole('button', { name: 'Return to graph', exact: true }).click(); await page.locator('#btn-stop').click();
  await expect(page.locator('.doc-pane--active .doc-run-binding')).toContainText('Stopped', { timeout: 30_000 });
  const stop = commands.filter(c => c.path.endsWith('/stop')); expect(stop).toHaveLength(1);
  expect(stop[0].headers['x-ravenroot-deployment-incarnation']).toBe(origin.binding.incarnationId);
  expect(stop[0].headers['x-ravenroot-graph-version']).toBe(origin.binding.graphVersion);
  expect(commands.filter(c => c.path.endsWith('/initial-start'))).toHaveLength(1);
  await writeFile(join(OUTPUT_DIR, 'source-origin-pinned-history-evidence.json'), JSON.stringify({ origin, observed, topology, commands }, null, 2));
  expect(errors).toEqual([]);
});
