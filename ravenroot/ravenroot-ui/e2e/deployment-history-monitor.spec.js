import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
const source = { kind: 'deployment', deploymentId: 'orders', graphVersion: 'v1', incarnationId: 'inc-2' };
const projection = { viewerSourceVersion: '2', lifecycle: 'READY', source,
  projection: { viewerContractVersion: '1.0', graphId: 'orders', graphVersionId: 'v1', canonicalDigest: 'v1', nodes: [
    { id: 'start', kind: 'START', label: 'Start', layout: { x: 80, y: 100, width: 80, height: 80 } },
    { id: 'worker', kind: 'BEHAVIOR', label: 'Worker', layout: { x: 280, y: 100, width: 120, height: 52 } },
    { id: 'end', kind: 'END', label: 'End', layout: { x: 480, y: 100, width: 80, height: 80 } }],
  edges: [{ id: 'route', source: 'start', target: 'worker' }, { id: 'loop', source: 'worker', target: 'worker' }, { id: 'finish', source: 'worker', target: 'end' }] } };
for (const cap of [1, 10]) test(`shared monitor bounds thousands of records and keeps partial coverage truthful at cap ${cap}`, async ({ page }, testInfo) => {
  await page.goto('/');
  await page.evaluate(async ({ projection, cap }) => {
    const link = document.createElement('link'); link.rel = 'stylesheet'; link.href = '/embed-viewer.css'; document.head.append(link);
    await new Promise(resolve => link.addEventListener('load', resolve, { once: true }));
    document.body.replaceChildren(); const host = document.createElement('main'); document.body.append(host);
    window.historyMeasurements = []; const { createDeploymentHistoryMonitor } = await import('/embed-viewer.js');
    window.monitor = createDeploymentHistoryMonitor({ container: host, projection,
      read: async query => {
        const began = performance.now(), offset = Number(query.cursor || 0), limit = Number(query.limit), view = query.view;
        let all = [];
        if (view === 'policy') all = [{ maxPageSize: cap }];
        if (view === 'histories') all = Array.from({ length: 3000 }, (_, i) => ({ applicationCorrelationId: `operation-${i}`, publicReference: 'same-file.csv', lifecycle: 'OPEN', state: 'AWAITING_EXTERNAL_EVENT' }));
        if (view === 'traversals' || view === 'uncorrelated-traversals') all = Array.from({ length: 3 }, (_, i) => ({ processInstanceId: 'same-process', traversalId: `traversal-${i}`, status: 'COMPLETED' }));
        if (view === 'nodes') all = ['start', 'worker', 'end'].map(nodeId => ({ nodeId, visitCount: nodeId === 'worker' ? 1000 : 1, activeVisits: 0, completedVisits: nodeId === 'worker' ? 1000 : 1, failedVisits: 0, attemptCount: nodeId === 'worker' ? 2000 : 1 }));
        if (view === 'edges') all = ['route', 'loop', 'finish'].map(edgeId => ({ edgeId, traversalCount: edgeId === 'loop' ? 999 : 1 }));
        if (view === 'visits') all = Array.from({ length: 1000 }, (_, i) => ({ invocationId: `visit-${i}`, nodeId: query.nodeId, status: 'COMPLETED', attemptCount: 2, parentCount: 1 }));
        if (view === 'attempts') all = [{ attemptId: 'failed', status: 'FAILED', ordinal: 1 }, { attemptId: 'retry', status: 'COMPLETED', ordinal: 2 }];
        const response = { source: projection.source, view, items: all.slice(offset, offset + limit), nextCursor: offset + limit < all.length ? String(offset + limit) : null, availability: 'AVAILABLE' };
        window.historyMeasurements.push({ view, limit, invocationId: query.invocationId, rows: response.items.length, bytes: JSON.stringify(response).length, latency: performance.now() - began }); return response;
      } }); await window.monitor.ready;
  }, { projection, cap });
  const histories = page.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Business histories', exact: true }) });
  await expect(histories.locator('li')).toHaveCount(cap);
  for (let i = 0; i < 4; i++) { await histories.getByRole('button', { name: 'Next page' }).click(); await expect(histories.locator('li')).toHaveCount(cap); }
  let retainedDetachedButtons = null;
  if (cap === 10) {
    await page.evaluate(() => { window.oldHistoryButtons = []; });
    for (let i = 0; i < 295; i++) {
      await histories.locator('li button').first().evaluate(node => window.oldHistoryButtons.push(new WeakRef(node)));
      await histories.getByRole('button', { name: 'Next page' }).click();
      await expect(histories.locator('li')).toHaveCount(10);
    }
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('HeapProfiler.collectGarbage'); await cdp.send('HeapProfiler.collectGarbage');
    retainedDetachedButtons = await page.evaluate(() => window.oldHistoryButtons.filter(ref => { const node = ref.deref(); return node && !node.isConnected; }).length);
    expect(retainedDetachedButtons).toBeLessThanOrEqual(2);
    await cdp.detach();
  }
  await histories.locator('li button').first().click();
  const traversals = page.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Actual traversals', exact: true }) });
  await traversals.locator('li button').first().click();
  const preview = page.locator('.history-traversal'); await expect(preview.locator('[data-design-element="worker"]')).toBeVisible();
  if (cap === 1) {
    await expect(preview).toContainText('Omitted activity is unknown');
    expect(await page.evaluate(() => window.historyMeasurements.filter(r => r.view === 'nodes').length)).toBe(1);
    await preview.locator('[data-design-element="worker"]').focus(); await page.keyboard.press('Enter');
    await expect(preview.locator('.history-inspector')).toContainText('not yet loaded');
    await preview.getByRole('button', { name: 'Load more node coverage' }).click();
    await preview.getByRole('button', { name: 'Load more node coverage' }).click();
    await preview.getByRole('button', { name: 'Load more edge coverage' }).click();
    await preview.getByRole('button', { name: 'Load more edge coverage' }).click();
  }
  await expect(preview).toContainText('Complete coverage: 3 visited nodes and 3 traversed edges');
  await preview.locator('[data-design-element="worker"]').click();
  const visits = preview.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Visits', exact: true }) });
  await expect(visits.locator('li')).toHaveCount(cap); await visits.locator('li button').first().click();
  await expect(preview.getByRole('heading', { name: 'Attempts', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(preview.locator('.history-inspector')).toBeVisible();
  expect(await page.locator('body').evaluate(body => body.scrollWidth <= innerWidth + 2)).toBe(true);
  const measurements = await page.evaluate(() => ({ requests: window.historyMeasurements, listRows: document.querySelectorAll('.history-page li').length, previews: document.querySelectorAll('.history-preview').length, dom: document.querySelectorAll('*').length }));
  measurements.retainedDetachedButtons = retainedDetachedButtons;
  expect(measurements.requests.every(row => row.rows <= cap)).toBe(true); expect(measurements.listRows).toBeLessThanOrEqual(cap * 7); expect(measurements.previews).toBe(1); expect(measurements.dom).toBeLessThan(1100);
  const evidence = testInfo.outputPath(`history-bounds-cap-${cap}.json`); writeFileSync(evidence, JSON.stringify(measurements, null, 2));
  await testInfo.attach(`history-bounds-cap-${cap}.json`, { path: evidence, contentType: 'application/json' });
  const screenshot = testInfo.outputPath(`history-cap-${cap}.png`); await page.screenshot({ path: screenshot, fullPage: true });
  await testInfo.attach(`history-cap-${cap}.png`, { path: screenshot, contentType: 'image/png' });
});

test('retired shared monitor rejects a late browser transport that ignores abort', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async projection => {
    document.body.replaceChildren(); const host = document.createElement('main'); document.body.append(host);
    const { createDeploymentHistoryMonitor } = await import('/embed-viewer.js');
    window.retiredRead = null; window.requestCount = 0;
    window.retiredMonitor = createDeploymentHistoryMonitor({ container: host, projection, read: query => {
      window.requestCount++;
      if (query.view === 'policy') return Promise.resolve({ source: projection.source, view: 'policy', items: [{ maxPageSize: 1 }], availability: 'AVAILABLE', nextCursor: null });
      return new Promise(resolve => { if (query.view === 'histories') window.retiredRead = () => resolve({ source: projection.source, view: 'histories', items: [{ applicationCorrelationId: 'secret-stale-operation' }], availability: 'AVAILABLE', nextCursor: null }); });
    } }); await window.retiredMonitor.ready;
  }, projection);
  await expect.poll(() => page.evaluate(() => typeof window.retiredRead)).toBe('function');
  await page.evaluate(() => { window.retiredMonitor.destroy(); window.retiredRead(); });
  await expect(page.locator('main')).toBeEmpty();
  expect(await page.evaluate(() => window.requestCount)).toBe(3);
});
