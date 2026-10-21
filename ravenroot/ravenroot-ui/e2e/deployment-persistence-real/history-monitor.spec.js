import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
test.use({ hasTouch: true });
const ORIGIN = process.env.RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN;
const CAP = Number(process.env.RAVENROOT_HISTORY_PAGE_CAP);
const OUTPUT = process.env.RAVENROOT_HISTORY_EVIDENCE_DIR;
test('native monitor reads bounded real persistent histories, visits and retry attempts', async ({ page }) => {
  test.setTimeout(60_000); page.setDefaultTimeout(10_000);
  if (!ORIGIN || !OUTPUT || ![1, 10].includes(CAP)) throw new Error('requires real history server harness');
  page.on('dialog', dialog => dialog.accept());
  const failures = [], pages = [], pending = [];
  page.on('pageerror', error => failures.push(error.message));
  page.on('response', response => {
    const url = new URL(response.url()); if (!url.pathname.endsWith('/history')) return;
    pending.push((async () => { const body = await response.body(), data = JSON.parse(body);
      pages.push({ view: url.searchParams.get('view'), requestLimit: Number(url.searchParams.get('limit')), bytes: body.length,
        rows: data.items?.length, status: response.status(), duration: response.request().timing().responseEnd }); })());
  });
  await page.goto(ORIGIN); await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });
  await page.evaluate(theme => window.ravenroot.setApplicationTheme(theme), CAP === 1 ? 'dark' : 'light');
  await page.locator('#menu-run').click(); await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator('[data-deployment-view="history-browser"]').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument()?.graph?.viewerBinding?.deploymentId)).toBe('history-browser');
  let lostView = CAP === 10;
  if (lostView) await page.route('**/v1/deployments/history-browser/view', route => {
    if (lostView) { lostView = false; return route.fulfill({ status: 503, json: { error: 'TEMPORARILY_UNAVAILABLE' } }); }
    return route.continue();
  });
  await page.locator('[data-menu="layout"]').click(); await page.getByRole('menuitemradio', { name: 'Monitoring', exact: true }).click();
  if (CAP === 10) await page.getByRole('button', { name: 'Retry deployment view', exact: true }).click();
  const monitor = page.locator('.doc-history'); await expect(monitor).toBeVisible();
  const histories = monitor.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Business histories', exact: true }) });
  await expect(histories.locator('li')).toHaveCount(CAP);
  await expect(histories.locator('li button').first()).toContainText('same-file.csv');
  await expect(histories.locator('li button').first()).toContainText('operation-0000');
  await expect(histories.locator('li button').first()).toContainText('AWAITING_EXTERNAL_EVENT');
  const firstBusiness = await histories.locator('li button').first().innerText();
  await histories.getByRole('button', { name: 'Next page' }).click();
  await expect(histories.locator('li button').first()).not.toHaveText(firstBusiness);
  await expect(histories.locator('li button').first()).toContainText('same-file.csv');
  await histories.getByRole('button', { name: 'Previous page' }).click();
  await expect(histories.locator('li button').first()).toHaveText(firstBusiness);
  await histories.locator('li button').first().click();
  const traversals = monitor.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Actual traversals', exact: true }) });
  await traversals.locator('li button').first().click();
  const preview = monitor.locator('.history-traversal'); await expect(preview.locator('[data-design-element="worker"]')).toBeVisible();
  if (CAP === 1) {
    await expect(preview).toContainText('Omitted activity is unknown');
    await preview.locator('[data-design-element="worker"]').focus(); await page.keyboard.press('Enter');
    await expect(preview.locator('.history-inspector')).toContainText('not yet loaded');
    for (const name of ['Load more node coverage', 'Load more edge coverage']) for (let n = 0; n < 2; n++) {
      const more = preview.getByRole('button', { name }); await expect(more).toBeEnabled(); await more.click();
      await expect.poll(() => pages.filter(page => page.view === (name.includes('node') ? 'nodes' : 'edges')).length).toBe(n + 2);
    }
  }
  await expect(preview).toContainText('Complete coverage: 3 visited nodes and 3 traversed edges');
  await preview.locator('[data-design-element="worker"]').click();
  await expect(preview.locator('.history-node-totals')).toHaveText('Total visits 1,000 · Attempts 2,000 · Active 0 · Failed visits 0');
  const visits = preview.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Visits', exact: true }) });
  await expect(visits.locator('li')).toHaveCount(CAP);
  const firstVisit = await visits.locator('li button').first().innerText(); expect(firstVisit).toContain('Visit'); expect(firstVisit).toContain('worker');
  await visits.getByRole('button', { name: 'Next page' }).click();
  await expect(visits.locator('li button').first()).not.toHaveText(firstVisit);
  await visits.getByRole('button', { name: 'Previous page' }).click(); await expect(visits.locator('li button').first()).toHaveText(firstVisit);
  await visits.locator('li button').first().click();
  const attempts = preview.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Attempts', exact: true }) });
  await expect(attempts).toContainText('FAILED'); if (CAP === 1) await attempts.getByRole('button', { name: 'Next page' }).click();
  await expect(attempts).toContainText('COMPLETED');
  await expect(preview.locator('.history-inspector')).toContainText('Causal parents');
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(preview.locator('.history-inspector')).toBeVisible();
  const graphCanvas = preview.locator('[data-viewer-canvas]'); await graphCanvas.scrollIntoViewIfNeeded();
  await expect.poll(() => graphCanvas.evaluate(node => { const cy = node._cyreg.cy, box = cy.elements().renderedBoundingBox();
    return box.x1 >= 0 && box.y1 >= 0 && box.x2 <= node.clientWidth + 1 && box.y2 <= node.clientHeight + 1; })).toBe(true);
  const topology = await graphCanvas.evaluate(node => { const cy = node._cyreg.cy; return { nodes: cy.nodes().length, edges: cy.edges().length,
    loopCount: cy.getElementById('loop').data('runtimeCount'), workerState: cy.getElementById('worker').data('runtimeState'), point: cy.getElementById('worker').renderedPosition() }; });
  expect(topology).toMatchObject({ nodes: 4, edges: 3, loopCount: 999, workerState: 'completed' });
  const graphBox = await graphCanvas.boundingBox(); await page.touchscreen.tap(graphBox.x + topology.point.x, graphBox.y + topology.point.y);
  await expect(preview.locator('.history-inspector h4')).toHaveText('worker');
  const visitControl = visits.locator('li button').first(); await visitControl.scrollIntoViewIfNeeded();
  const target = await visitControl.boundingBox(); expect(target.height).toBeGreaterThanOrEqual(44); expect(target.width).toBeGreaterThanOrEqual(44);
  expect(await visitControl.evaluate(node => { const b = node.getBoundingClientRect(); const top = node.ownerDocument.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2); return top === node || node.contains(top); })).toBe(true);
  await page.touchscreen.tap(target.x + target.width / 2, target.y + target.height / 2); await expect(attempts).toContainText('FAILED');
  const contrast = await monitor.evaluate(node => { const style = getComputedStyle(node); const l = color => color.match(/\d+(?:\.\d+)?/g).slice(0, 3).map(Number).map(c => c / 255).map(c => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4).reduce((a, b, i) => a + b * [.2126, .7152, .0722][i], 0); const a = l(style.color), b = l(style.backgroundColor); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05); });
  expect(contrast).toBeGreaterThanOrEqual(4.5);
  await page.screenshot({ path: join(OUTPUT, `history-inspector-cap-${CAP}.png`), fullPage: true });
  await preview.locator('[data-viewer-canvas]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(OUTPUT, `history-graph-cap-${CAP}.png`), fullPage: true });

  const dom = await monitor.evaluate(node => ({ elements: node.querySelectorAll('*').length, rows: node.querySelectorAll('.history-page li').length,
    previews: node.querySelectorAll('.history-preview').length, scrollWidth: node.scrollWidth, width: node.clientWidth }));
  await Promise.all(pending);
  expect(failures).toEqual([]); expect(pages.length).toBeLessThan(25); expect(pages.every(page => page.status === 200 && page.rows <= page.requestLimit && page.requestLimit <= CAP)).toBe(true);
  expect(Math.max(...pages.map(page => page.bytes))).toBeLessThan(20_000); expect(dom.elements).toBeLessThan(1400); expect(dom.rows).toBeLessThanOrEqual(CAP * 7); expect(dom.previews).toBe(1); expect(dom.scrollWidth).toBeLessThanOrEqual(dom.width + 2);
  writeFileSync(join(OUTPUT, `history-bounds-cap-${CAP}.json`), JSON.stringify({ seededHistories: 2000, workerVisits: 1000, workerAttempts: 2000, reopenedSqlite: CAP === 1, contrast, topology, dom, pages }, null, 2));
  await monitor.evaluate(node => { node.scrollTop = 0; });
  await page.screenshot({ path: join(OUTPUT, `history-monitor-cap-${CAP}.png`), fullPage: true });
  await monitor.locator(':scope > details').nth(1).locator(':scope > summary').click();
  if (CAP === 1) { await traversals.getByRole('button', { name: 'Next page' }).click(); await traversals.locator('li button').first().click(); }
  else await traversals.locator('li button').nth(1).click();
  const repeated = monitor.locator('.history-traversal'); await expect(repeated).toHaveCount(2);
  await expect(repeated.nth(1)).toContainText('Complete coverage: 1 visited nodes and 0 traversed edges');
  const repeatedFacts = await repeated.evaluateAll(previews => previews.map(root => { const canvas = root.querySelector('[data-viewer-canvas]'), cy = canvas._cyreg.cy;
    return { traversal: root.dataset.traversalId, nodes: cy.nodes().length, edges: cy.edges().length, loopCount: cy.getElementById('loop').data('runtimeCount'), workerState: cy.getElementById('worker').data('runtimeState') }; }));
  expect(repeatedFacts[0]).toMatchObject({ nodes: 4, edges: 3, loopCount: 999, workerState: 'completed' });
  expect(repeatedFacts[1]).toMatchObject({ nodes: 4, edges: 3, loopCount: 0, workerState: 'idle' });
  writeFileSync(join(OUTPUT, `history-two-traversals-cap-${CAP}.json`), JSON.stringify(repeatedFacts, null, 2));
  await repeated.nth(1).locator('[data-viewer-canvas]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(OUTPUT, `history-two-traversals-cap-${CAP}.png`), fullPage: true });
  if (CAP === 10) {
    await page.getByRole('button', { name: 'Return to graph', exact: true }).click(); await page.setViewportSize({ width: 1280, height: 900 });
    const draft = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="k" for="node" attr.name="kind" attr.type="string"/><key id="n" for="node" attr.name="name" attr.type="string"/><graph id="draft" edgedefault="directed"><node id="start"><data key="k">START</data></node><node id="end"><data key="k">END</data><data key="n">Original end</data></node><edge id="done" source="start" target="end"/></graph></graphml>`;
    await page.locator('#file-inp').setInputFiles({ name: 'editable.graphml', mimeType: 'application/xml', buffer: Buffer.from(draft) });
    await page.locator('#btn-run').click(); await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument()?.deploymentRun?.traversalId)).toBeTruthy();
    const original = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
    const mutations = []; page.on('request', request => { if (request.method() === 'POST' && request.url().includes('/v1/deployments')) mutations.push(request.url()); });
    await page.locator('[data-menu="layout"]').click(); await page.getByRole('menuitemradio', { name: 'Monitoring', exact: true }).click();
    await expect(page.locator('.doc-history-draft').filter({ visible: true })).toContainText('Monitoring shows the pinned deployment');
    await page.getByRole('button', { name: 'Return to graph', exact: true }).click();
    await page.locator('#btn-modify').click(); await page.evaluate(() => { window.cy.getElementById('end').select(); });
    const name = page.locator('#node-editor input[name="name"]'); await name.fill('Draft only end'); await name.press('Tab');
    await page.locator('[data-menu="layout"]').click(); await page.getByRole('menuitemradio', { name: 'Monitoring', exact: true }).click();
    await expect(page.locator('.doc-history-draft').filter({ visible: true })).toContainText('Your draft differs from this deployment');
    const current = page.locator('.doc-history-dialog[open] .doc-history');
    const business = current.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Business histories', exact: true }) });
    await business.locator('li button').first().click();
    const actual = current.locator('.history-page').filter({ has: page.getByRole('heading', { name: 'Actual traversals', exact: true }) }); await actual.locator('li button').first().click();
    await expect(current.getByRole('button', { name: 'Inspect node Original end', exact: true })).toBeVisible(); await expect(current).not.toContainText('Draft only end');
    expect(await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun.graphVersion)).toBe(original.graphVersion); expect(mutations).toEqual([]);
    await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: join(OUTPUT, 'history-draft-divergence.png'), fullPage: true });
  }

});
