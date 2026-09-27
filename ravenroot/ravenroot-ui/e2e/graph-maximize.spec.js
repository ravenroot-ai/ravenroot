import { expect, test } from '@playwright/test';
import path from 'node:path';

const evidencePath = name => process.env.RR_VISUAL_EVIDENCE_DIR
  ? path.join(process.env.RR_VISUAL_EVIDENCE_DIR, name) : undefined;

test('graph document maximize restores the same viewport, selection, pane geometry and focus', async ({ page }, testInfo) => {
  await page.goto('/');
  await page.waitForFunction(() => window.cy && window.ravenroot?.workspace.active);
  await page.evaluate(() => {
    const source = window.ravenroot.workspace.active;
    window.ravenroot.openDocument({ name: 'second.graphml', graph: structuredClone(source.graph) });
  });
  await page.waitForFunction(() => !window.cy.scratch('_rrLayoutRunning'));
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.evaluate(() => {
    window.cy.zoom(1.37); window.cy.pan({ x: 47, y: -31 });
    window.cy.nodes().first().select();
  });
  const before = await page.evaluate(() => ({
    activeId: window.ravenroot.workspace.active.id,
    zoom: window.cy.zoom(), pan: window.cy.pan(), selected: window.cy.$(':selected').map(item => item.id()),
    rect: window.ravenroot.workspace.active.pane.getBoundingClientRect().toJSON(),
  }));
  const maximize = page.locator('.doc-pane--active [data-pane-document-maximize]');
  await expect(maximize).toHaveAttribute('aria-label', /Maximize second\.graphml/);
  await maximize.click();
  await expect(page.locator('html')).toHaveClass(/graph-document-maximized/);
  await expect(maximize).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.doc-pane--shown:not(.doc-pane--maximized)')).toBeHidden();
  const hiddenHeaderChildren = await page.locator(
    '.doc-pane--maximized .doc-pane-header > :not(.doc-pane-maximize)')
    .evaluateAll(elements => elements.map(element => ({
      className: element.className, display: getComputedStyle(element).display,
      hidden: element.hidden, style: element.getAttribute('style'),
    })));
  expect(hiddenHeaderChildren.map(element => element.display))
    .toEqual(['none', 'none', 'none', 'none']);
  expect(await maximize.boundingBox()).toMatchObject({ width: 24, height: 24 });
  await expect.poll(() => page.evaluate(() => window.cy.zoom())).toBeCloseTo(before.zoom, 8);
  const maximizedViewport = await page.evaluate(() => ({ zoom: window.cy.zoom(), pan: window.cy.pan() }));
  expect(maximizedViewport.pan).toEqual(before.pan);
  const maximized = await page.locator('#cy-wrap').boundingBox();
  expect(maximized).toMatchObject({ x: 0, y: 0, width: 1280, height: 720 });
  for (const theme of ['dark', 'light']) {
    await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
    await expect.poll(() => page.evaluate(() => window.ravenroot.applicationTheme())).toBe(theme);
    const nodeColor = await page.evaluate(() => window.cy.nodes().first().style('color'));
    expect(nodeColor).toBe(theme === 'dark' ? 'rgb(230,237,243)' : 'rgb(31,35,40)');
    await testInfo.attach(`workspace-${theme}-maximized.png`, {
      body: await page.screenshot(), contentType: 'image/png',
    });
    if (evidencePath(`workspace-${theme}-maximized.png`)) {
      await page.screenshot({ path: evidencePath(`workspace-${theme}-maximized.png`) });
    }
  }
  await page.keyboard.press('Escape');
  await expect(page.locator('html')).not.toHaveClass(/graph-document-maximized/);
  await expect(maximize).toBeFocused();
  const restored = await page.evaluate(() => ({
    activeId: window.ravenroot.workspace.active.id,
    zoom: window.cy.zoom(), pan: window.cy.pan(), selected: window.cy.$(':selected').map(item => item.id()),
    rect: window.ravenroot.workspace.active.pane.getBoundingClientRect().toJSON(),
  }));
  expect(restored.activeId).toBe(before.activeId);
  expect(restored.zoom).toBeCloseTo(before.zoom, 8);
  expect(restored.pan).toEqual(before.pan);
  expect(restored.selected).toEqual(before.selected);
  expect(restored.rect.width).toBeCloseTo(before.rect.width, 1);
  expect(restored.rect.height).toBeCloseTo(before.rect.height, 1);
  for (const theme of ['dark', 'light']) {
    await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
    await testInfo.attach(`workspace-${theme}-normal.png`, {
      body: await page.screenshot(), contentType: 'image/png',
    });
    if (evidencePath(`workspace-${theme}-normal.png`)) {
      await page.screenshot({ path: evidencePath(`workspace-${theme}-normal.png`) });
    }
  }
});

for (const theme of ['dark', 'light']) {
  test(`rapid maximize restoration and document teardown remain owned in ${theme}`, async ({ page }) => {
    await page.goto('/'); await page.waitForFunction(() => window.cy && window.ravenroot?.workspace.active);
    await page.waitForFunction(() => !window.cy.scratch('_rrLayoutRunning'));
    await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
    await page.evaluate(() => {
      window.cy.zoom(1.37); window.cy.pan({ x: 47, y: -31 });
      const button = document.querySelector('.doc-pane--active [data-pane-document-maximize]');
      button.click(); button.click(); button.click();
    });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    expect(await page.evaluate(() => ({ zoom: window.cy.zoom(), pan: window.cy.pan() })))
      .toEqual({ zoom: 1.37, pan: { x: 47, y: -31 } });
    await page.evaluate(() => { window.cy.zoom(1.61); window.cy.pan({ x: 91, y: -77 }); });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    expect(await page.evaluate(() => ({ zoom: window.cy.zoom(), pan: window.cy.pan() })))
      .toEqual({ zoom: 1.61, pan: { x: 91, y: -77 } });
    await page.keyboard.press('Escape');
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.active.maximizeViewport)).toBe(null);
    expect(await page.evaluate(() => ({ zoom: window.cy.zoom(), pan: window.cy.pan() })))
      .toEqual({ zoom: 1.61, pan: { x: 91, y: -77 } });
    await page.locator('.doc-pane--active [data-pane-document-maximize]').click();
    await page.evaluate(() => window.ravenroot.replaceActiveDocumentFromText(
      window.ravenroot.serializeGraphML(window.ravenroot.workspace.active.graph), 'replacement.graphml'));
    await expect(page.locator('html')).not.toHaveClass(/graph-document-maximized/);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.active.maximizeViewport)).toBe(null);
    await page.locator('.doc-pane--active [data-pane-document-maximize]').click();
    await page.evaluate(() => window.ravenroot.closeDocument(window.ravenroot.workspace.activeId));
    await expect(page.locator('html')).not.toHaveClass(/graph-document-maximized/);
    expect(await page.evaluate(() => window.ravenroot.workspace.documents.length)).toBe(0);
  });

  test(`Monitoring renderer viewport and transform follow repeated maximize in ${theme}`, async ({ page }) => {
    await page.goto('/'); await page.waitForFunction(() => window.cy && window.ravenroot?.workspace.active);
    await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
    await page.locator('#btn-monitoring').click();
    const svg = page.locator('.doc-pane--active svg').filter({ has: page.locator('.d3-nodes') });
    await expect(svg).toBeVisible();
    const geometry = () => svg.evaluate(element => ({ width: Number(element.getAttribute('width')),
      height: Number(element.getAttribute('height')), box: element.getAttribute('viewBox'),
      hostWidth: element.parentElement.clientWidth, hostHeight: element.parentElement.clientHeight,
      transform: element.__zoom.toString() }));
    let expectedTransform = (await geometry()).transform;
    for (let cycle = 0; cycle < 2; cycle++) {
      await page.locator('.doc-pane--active [data-pane-document-maximize]').click();
      await expect.poll(async () => { const g = await geometry(); return g.box === `0 0 ${g.hostWidth} ${g.hostHeight}`; }).toBe(true);
      expect((await geometry()).transform).toBe(expectedTransform);
      await page.evaluate(() => {
        const renderer = window.ravenroot.workspace.active.renderer.elasticMount;
        renderer.zoomBy(1.2); renderer.panBy({ x: 31, y: -17 });
      });
      const navigatedTransform = (await geometry()).transform;
      expect(navigatedTransform).not.toBe(expectedTransform);
      expectedTransform = navigatedTransform;
      await page.keyboard.press('Escape');
      await expect.poll(async () => { const g = await geometry(); return g.box === `0 0 ${g.hostWidth} ${g.hostHeight}`; }).toBe(true);
      expect((await geometry()).transform).toBe(expectedTransform);
    }
  });
}
