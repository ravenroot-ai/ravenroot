import { expect, test } from '@playwright/test';

test.use({ viewport: { width: 1800, height: 900 } });

async function open(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => window.cy?.nodes().length)).toBe(4);
  await page.waitForFunction(() => window.cy && !window.cy.scratch('_rrLayoutRunning'));
}

async function canvasBox(page, documentId) {
  const selector = documentId
    ? `.doc-pane[data-document-id="${documentId}"] .doc-canvas`
    : '.doc-pane.doc-pane--active .doc-canvas';
  return page.locator(selector).boundingBox();
}

async function viewportState(page, documentId) {
  return page.evaluate(id => {
    const owner = id ? window.ravenroot.workspace.find(id) : window.ravenroot.activeDocument();
    const target = owner.cy;
    return {
      id: owner.id,
      zoom: target.zoom(),
      pan: target.pan(),
      userPanning: target.userPanningEnabled(),
      userZooming: target.userZoomingEnabled(),
      selected: target.$(':selected').map(element => element.id()).sort(),
      nodes: target.nodes().map(node => [node.id(), node.position()]),
      edges: target.edges().map(edge => [edge.id(), edge.source().id(), edge.target().id()]),
      history: owner.history.depth(),
    };
  }, documentId);
}

async function physicalWheel(page, box, deltaY = -12) {
  const point = { x: box.x + box.width * 0.37, y: box.y + box.height * 0.43 };
  await page.mouse.move(point.x, point.y);
  for (let index = 0; index < 4; index += 1) await page.mouse.wheel(0, deltaY);
  await page.waitForTimeout(80);
  return point;
}

async function physicalPinch(page, box, scaleFactor = 1.22) {
  const session = await page.context().newCDPSession(page);
  const point = { x: box.x + box.width * 0.61, y: box.y + box.height * 0.39 };
  await session.send('Input.synthesizePinchGesture', {
    x: point.x, y: point.y, scaleFactor, relativeSpeed: 800, gestureSourceType: 'touch',
  });
  await page.waitForTimeout(80);
  await session.detach();
  return point;
}

function expectAnchorStable(before, after, box, point, tolerance = 2) {
  const local = { x: point.x - box.x, y: point.y - box.y };
  const model = {
    x: (local.x - before.pan.x) / before.zoom,
    y: (local.y - before.pan.y) / before.zoom,
  };
  expect(model.x * after.zoom + after.pan.x).toBeCloseTo(local.x, 0);
  expect(model.y * after.zoom + after.pan.y).toBeCloseTo(local.y, 0);
  expect(Math.abs(model.x * after.zoom + after.pan.x - local.x)).toBeLessThan(tolerance);
  expect(Math.abs(model.y * after.zoom + after.pan.y - local.y)).toBeLessThan(tolerance);
}

function expectDocumentUnchanged(before, after) {
  expect(after.selected).toEqual(before.selected);
  expect(after.nodes).toEqual(before.nodes);
  expect(after.edges).toEqual(before.edges);
  expect(after.history).toEqual(before.history);
}

test('physical wheel and pinch zoom around their pointer in Selection, Editing and Navigation', async ({ page }) => {
  await open(page);
  const box = await canvasBox(page);

  for (const mode of ['selection', 'editing', 'navigation']) {
    if (mode === 'editing') await page.locator('#btn-modify').click();
    if (mode === 'navigation') await page.locator('#btn-navigation').click();
    for (const [selectionName, selectedIds] of [
      ['empty', []], ['single', ['start']], ['multiple', ['start', 'end']],
    ]) {
      await page.evaluate(ids => {
        window.cy.$(':selected').unselect();
        ids.forEach(id => window.cy.getElementById(id).select());
        window.cy.zoom(1);
        window.cy.center();
      }, selectedIds);

      const wheelBefore = await viewportState(page);
      const wheelPoint = await physicalWheel(page, box);
      const wheelAfter = await viewportState(page);
      expect(wheelAfter.zoom, `${mode}/${selectionName} wheel zoom`).toBeGreaterThan(wheelBefore.zoom);
      expectAnchorStable(wheelBefore, wheelAfter, box, wheelPoint);
      expectDocumentUnchanged(wheelBefore, wheelAfter);
      expect(wheelAfter.userPanning).toBe(mode === 'navigation');
      expect(wheelAfter.userZooming).toBe(true);

      const pinchBefore = wheelAfter;
      const pinchPoint = await physicalPinch(page, box);
      const pinchAfter = await viewportState(page);
      expect(pinchAfter.zoom, `${mode}/${selectionName} pinch zoom`).toBeGreaterThan(pinchBefore.zoom);
      expectAnchorStable(pinchBefore, pinchAfter, box, pinchPoint, 3);
      expectDocumentUnchanged(pinchBefore, pinchAfter);
      expect(pinchAfter.userPanning).toBe(mode === 'navigation');
    }

    if (mode === 'editing') await page.locator('#btn-modify').click();
    if (mode === 'navigation') await page.locator('#btn-navigation').click();
  }

  await expect(page.locator('#b-zoom')).toHaveText(`${Math.round((await viewportState(page)).zoom * 100)}%`);
  const minimapBefore = await page.evaluate(() => window.ravenroot.minimapSnapshot()?.paintCount || 0);
  await physicalWheel(page, box);
  await expect.poll(() => page.evaluate(() => window.ravenroot.minimapSnapshot()?.paintCount || 0))
    .toBeGreaterThan(minimapBefore);
});

test('zoom controls are keyboard-accessible, clamped, and suspended without disturbing an edge gesture', async ({ page }) => {
  await open(page);
  const zoomIn = page.locator('#zoom-ctrl [data-command-id="view.zoomIn"]');
  const fit = page.locator('#zoom-ctrl [data-command-id="view.fit"]');
  const zoomOut = page.locator('#zoom-ctrl [data-command-id="view.zoomOut"]');
  await expect(zoomIn).toHaveAttribute('aria-label', 'Zoom in');
  await expect(fit).toHaveAttribute('aria-label', 'Fit graph');
  await expect(zoomOut).toHaveAttribute('aria-label', 'Zoom out');
  await zoomIn.focus();
  await expect(zoomIn).toBeFocused();
  expect(await zoomIn.evaluate(element => parseFloat(getComputedStyle(element).outlineWidth)))
    .toBeGreaterThanOrEqual(2);
  const beforeKeyboard = (await viewportState(page)).zoom;
  await zoomIn.press('Space');
  expect((await viewportState(page)).zoom).toBeGreaterThan(beforeKeyboard);

  await page.evaluate(() => { window.cy.zoom(4.95); });
  await zoomIn.click();
  expect((await viewportState(page)).zoom).toBe(5);
  await page.evaluate(() => { window.cy.zoom(0.051); });
  await zoomOut.click();
  expect((await viewportState(page)).zoom).toBe(0.05);
  await fit.click();
  expect((await viewportState(page)).zoom).toBeGreaterThan(0.05);

  await page.locator('#btn-modify').click();
  const start = await page.evaluate(() => {
    const rect = window.cy.container().getBoundingClientRect();
    const point = window.cy.getElementById('start').renderedPosition();
    return { x: rect.x + point.x, y: rect.y + point.y };
  });
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.mouse.move(start.x + 100, start.y + 90, { steps: 8 });
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'dragging');
  await expect(zoomIn).toBeDisabled();
  await expect(fit).toBeDisabled();
  await expect(zoomOut).toBeDisabled();
  const gestureBox = await canvasBox(page);
  await page.mouse.move(gestureBox.x + gestureBox.width * 0.37,
    gestureBox.y + gestureBox.height * 0.43, { steps: 4 });
  const gestureBefore = await viewportState(page);
  const gestureOwner = await page.locator('#cy-wrap').evaluate(element => ({
    source: element.dataset.edgeGestureSource,
    documentId: element.dataset.edgeGestureDocument,
  }));
  await physicalWheel(page, gestureBox);
  await physicalPinch(page, gestureBox);
  await page.keyboard.press('f');
  const gestureAfter = await viewportState(page);
  expect(gestureAfter).toEqual(gestureBefore);
  await expect(page.locator('#cy-wrap')).not.toHaveAttribute('data-edge-gesture-state', 'idle');
  expect(await page.locator('#cy-wrap').evaluate(element => ({
    source: element.dataset.edgeGestureSource,
    documentId: element.dataset.edgeGestureDocument,
  }))).toEqual(gestureOwner);
  const end = await page.evaluate(() => {
    const rect = window.cy.container().getBoundingClientRect();
    const point = window.cy.getElementById('end').renderedPosition();
    return { x: rect.x + point.x, y: rect.y + point.y };
  });
  await page.mouse.move(end.x, end.y, { steps: 8 });
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'target-valid');
  expect(await page.locator('#cy-wrap').evaluate(element => ({
    source: element.dataset.edgeGestureSource,
    documentId: element.dataset.edgeGestureDocument,
  }))).toEqual(gestureOwner);
  await page.keyboard.press('Escape');
  await page.mouse.up();
  await expect(zoomIn).toBeEnabled();
  const restored = await viewportState(page);
  expectDocumentUnchanged(gestureBefore, restored);
  expect(restored.userZooming).toBe(true);
});

test('physical wheel stays with its document owner and wheel outside the canvas scrolls normally', async ({ page }) => {
  await open(page);
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await page.evaluate(() => { window.ravenroot.setWorkspaceLayout('horizontal'); });
  await expect.poll(() => page.locator('.doc-pane.doc-pane--shown').count()).toBe(2);
  const activeBefore = await page.evaluate(() => window.ravenroot.workspace.activeId);
  expect(activeBefore).toBe(second);
  const firstBefore = await viewportState(page, first);
  const secondBefore = await viewportState(page, second);
  const badgeBefore = await page.locator('#b-zoom').textContent();
  await physicalWheel(page, await canvasBox(page, first));
  const firstAfter = await viewportState(page, first);
  const secondAfter = await viewportState(page, second);
  expect(firstAfter.zoom).toBeGreaterThan(firstBefore.zoom);
  expect(secondAfter).toEqual(secondBefore);
  expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(second);
  await expect(page.locator('#b-zoom')).toHaveText(badgeBefore);

  await page.evaluate(() => {
    const scroll = document.getElementById('sidebar-scroll');
    const spacer = document.createElement('div');
    spacer.dataset.zoomScrollFixture = 'true';
    spacer.style.height = '1200px';
    scroll.append(spacer);
    scroll.scrollTop = 0;
  });
  const sidebar = await page.locator('#sidebar-scroll').boundingBox();
  await page.mouse.move(sidebar.x + 20, sidebar.y + 30);
  await page.mouse.wheel(0, 300);
  await expect.poll(() => page.locator('#sidebar-scroll').evaluate(element => element.scrollTop))
    .toBeGreaterThan(0);
});
