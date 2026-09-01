import { expect, test } from '@playwright/test';

async function open(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
}

async function pinFixture(page) {
  await page.evaluate(() => {
    window.cy.stop(true);
    const positions = {
      start: { x: 120, y: 130 }, dosomething: { x: 400, y: 130 },
      error: { x: 690, y: 330 }, end: { x: 690, y: 80 },
    };
    Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
    window.cy.fit(undefined, 100);
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function edgePoint(page, id) {
  return page.evaluate(edgeId => {
    const point = window.cy.getElementById(edgeId).renderedMidpoint();
    const canvas = window.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }, id);
}

const selectedIds = page => page.evaluate(() =>
  window.cy.$(':selected').map(element => element.id()).sort());

const edgeVisual = (page, id) => page.evaluate(edgeId => {
  const edge = window.cy.getElementById(edgeId);
  return {
    selected: edge.selected(),
    width: edge.style('width'),
    lineColor: edge.style('line-color'),
    lineStyle: edge.style('line-style'),
    opacity: edge.style('opacity'),
    underlayColor: edge.style('underlay-color'),
    underlayOpacity: edge.style('underlay-opacity'),
    underlayPadding: edge.style('underlay-padding'),
    zoom: window.cy.zoom(),
  };
}, id);

async function clickEdge(page, id) {
  const point = await edgePoint(page, id);
  await page.mouse.click(point.x, point.y);
  await expect.poll(() => selectedIds(page)).toEqual([id]);
}

for (const renderer of [{ name: 'Design', selector: '#btn-design' }]) {
  test(`${renderer.name} makes a clicked edge unmistakable and binds its endpoints to the Inspector`, async ({ page }) => {
    await open(page);
    await page.evaluate(() => window.ravenroot.setApplicationTheme('dark'));
    await page.locator(renderer.selector).click();
    await expect(page.locator('.doc-pane--active')).toHaveAttribute('aria-busy', 'true');
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
      timeout: 10_000,
    });
    await pinFixture(page);

    const normal = await edgeVisual(page, 'edge-start-dosomething');
    await clickEdge(page, 'edge-start-dosomething');

    await expect(page.locator('#b-sel')).toHaveText('Start → Do something');
    await expect(page.locator('#info-title')).toHaveText('Start → Do something · continue');
    await expect(page.locator('#info-body')).toContainText('edge-start-dosomething');
    await expect(page.locator('#info-body')).toContainText('start');
    await expect(page.locator('#info-body')).toContainText('dosomething');

    let selected = await edgeVisual(page, 'edge-start-dosomething');
    expect(selected).toMatchObject({ selected: true, width: normal.width, lineColor: normal.lineColor });
    expect(parseFloat(selected.underlayPadding)).toBeGreaterThanOrEqual(5);
    expect(parseFloat(selected.underlayOpacity)).toBeGreaterThan(0.3);

    // Theme and zoom are paint/viewport changes. They must retain the same exact selected edge and
    // the non-colour halo footprint while adapting its contrast colour to the new canvas.
    const darkUnderlay = selected.underlayColor;
    await page.evaluate(() => {
      window.ravenroot.setApplicationTheme('light');
      window.cy.zoom({ level: 0.45, renderedPosition: { x: 400, y: 250 } });
    });
    await expect.poll(() => selectedIds(page)).toEqual(['edge-start-dosomething']);
    selected = await edgeVisual(page, 'edge-start-dosomething');
    expect(selected.zoom).toBeCloseTo(0.45, 4);
    expect(selected.underlayColor).not.toBe(darkUnderlay);
    expect(parseFloat(selected.underlayPadding)).toBeGreaterThanOrEqual(5);

    await page.evaluate(() => { window.cy.fit(undefined, 100); });
    await clickEdge(page, 'edge-dosomething-error');
    expect(await edgeVisual(page, 'edge-start-dosomething')).toMatchObject({ selected: false });
    await expect(page.locator('#b-sel')).toHaveText('Do something → Error');
    await expect(page.locator('#info-title')).toHaveText('Do something → Error · failed');

    // Failed and runtime-traced edges retain their semantic dash/colour/width. Selection adds a
    // separate silhouette and remains visible if the rest of a hover/search trace is dimmed.
    const failed = await edgeVisual(page, 'edge-dosomething-error');
    expect(failed.lineStyle).toBe('dashed');
    await page.evaluate(() => {
      window.cy.getElementById('edge-dosomething-error').addClass('trace-edge dim');
    });
    const traced = await edgeVisual(page, 'edge-dosomething-error');
    expect(traced).toMatchObject({ selected: true, lineStyle: 'dashed', opacity: '1' });
    expect(traced.lineColor).not.toBe(failed.lineColor);
    expect(parseFloat(traced.underlayPadding)).toBeGreaterThanOrEqual(5);

    const canvas = await page.locator('.doc-pane--shown .doc-canvas').boundingBox();
    await page.mouse.click(canvas.x + 8, canvas.y + 8);
    await expect.poll(() => selectedIds(page)).toEqual([]);
    await expect(page.locator('#b-sel')).toHaveText('—');
  });
}
