import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

async function open(page, { editing = false } = {}) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  if (editing) await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    window.cy.stop(true);
    const positions = {
      start: { x: 120, y: 120 }, dosomething: { x: 400, y: 120 },
      end: { x: 680, y: 80 }, error: { x: 680, y: 330 },
    };
    Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
    window.cy.fit(undefined, 100);
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function nodePoint(page, id) {
  return page.evaluate(nodeId => {
    const point = window.cy.getElementById(nodeId).renderedPosition();
    const canvas = window.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }, id);
}

const graphState = page => page.evaluate(() => ({
  nodes: window.cy.nodes().map(node => node.id()).sort(),
  edges: window.cy.edges().map(edge => edge.id()).sort(),
  selected: window.cy.$(':selected').map(element => element.id()).sort(),
  historyDepth: window.ravenroot.activeDocument().history.depth(),
}));

async function pressReadyUndoShortcut(page) {
  const control = page.locator('#btn-undo');
  await expect(control).toBeEnabled();
  await expect(control).toHaveAttribute('title', /^Undo /);
  const shortcut = await control.getAttribute('aria-keyshortcuts');
  expect(shortcut).toMatch(/^(Meta|Control)\+z$/i);
  await page.keyboard.press(shortcut);
}

const overlayState = page => page.evaluate(() => {
  const owner = window.ravenroot.activeDocument();
  const root = owner.cy.container().querySelector('.graph-node-actions-overlay');
  const bar = root?.querySelector('.graph-node-actions');
  const bridge = root?.querySelector('.graph-node-actions-bridge');
  const menu = root?.querySelector('.graph-node-action-menu');
  const style = bar && !bar.hidden ? getComputedStyle(bar) : null;
  return {
    documentId: root?.dataset.documentId,
    rootHidden: root?.hidden,
    barHidden: bar?.hidden,
    bridgeHidden: bridge?.hidden,
    menuHidden: menu?.hidden,
    pointerEvents: style?.pointerEvents,
    rect: bar && !bar.hidden ? bar.getBoundingClientRect().toJSON() : null,
    buttons: bar ? [...bar.querySelectorAll('button')].map(button => ({
      action: button.dataset.nodeAction,
      hidden: button.hidden,
      label: button.getAttribute('aria-label'),
      tooltip: button.dataset.tooltip,
      width: button.getBoundingClientRect().width,
      height: button.getBoundingClientRect().height,
    })) : [],
  };
});

test('fine-pointer hover exposes a stable semantic minibar and trace uses the existing full-path contract', async ({ page }) => {
  await open(page, { editing: true });
  const start = await nodePoint(page, 'start');
  await page.mouse.move(start.x, start.y);

  await expect.poll(() => overlayState(page)).toMatchObject({
    rootHidden: false, barHidden: false, pointerEvents: 'auto',
    buttons: [
      { action: 'trace', hidden: false, label: 'Trace full path from Start', tooltip: 'Trace full path from Start' },
      { action: 'delete', hidden: false, label: 'Delete Start', tooltip: 'Delete Start' },
      { action: 'duplicate', hidden: true, label: 'Duplicate Start', tooltip: 'Duplicate Start' },
      { action: 'more', hidden: false, label: 'More node actions', tooltip: 'More node actions' },
    ],
  });
  const shown = await overlayState(page);
  expect(shown.documentId).toBeTruthy();
  for (const button of shown.buttons.filter(button => !button.hidden)) {
    expect(button.width).toBeCloseTo(20.7, 1);
    expect(button.height).toBeCloseTo(19.55, 1);
  }

  // Crossing the eight-pixel projection gap must not trigger the node's mouseout cleanup before the
  // toolbar receives pointerenter. This is the real node → DOM-overlay transition, not a DOM event stub.
  const trace = page.getByRole('button', { name: 'Trace full path from Start' });
  const traceBox = await trace.boundingBox();
  await page.mouse.move(traceBox.x + traceBox.width / 2, traceBox.y + traceBox.height / 2, { steps: 8 });
  await page.waitForTimeout(130);
  await expect(trace).toBeVisible();
  await trace.click();

  await expect.poll(() => overlayState(page)).toMatchObject({ rootHidden: true, barHidden: true });
  await expect.poll(() => page.evaluate(() => ({
    nodes: window.cy.nodes('.trace-node').map(node => node.id()).sort(),
    edges: window.cy.edges('.trace-edge').map(edge => edge.id()).sort(),
    start: window.cy.getElementById('start').hasClass('trace-start'),
    selected: window.cy.$(':selected').map(element => element.id()),
  }))).toEqual({
    nodes: ['dosomething', 'end', 'error', 'start'],
    edges: ['edge-dosomething-end', 'edge-dosomething-error', 'edge-start-dosomething'],
    start: true,
    selected: [],
  });
});

test('duplicate is one canonical history step and overlay/context menu share the same action', async ({ page }) => {
  await open(page, { editing: true });
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(worker.x, worker.y);
  const duplicate = page.getByRole('button', { name: 'Duplicate Do something' });
  await expect(duplicate).toBeVisible();
  const before = await graphState(page);
  const sourcePosition = await page.evaluate(() => {
    const source = window.ravenroot.activeDocument().graph.nodeMap.dosomething;
    return { x: source.ox, y: source.oy };
  });
  await duplicate.click();
  await expect.poll(() => graphState(page)).toEqual({
    nodes: ['dosomething', 'dosomething-copy-1', 'end', 'error', 'start'],
    edges: before.edges,
    selected: ['dosomething-copy-1'],
    historyDepth: before.historyDepth + 1,
  });
  expect(await page.evaluate(() => window.cy.getElementById('dosomething-copy-1').position()))
    .toEqual({ x: sourcePosition.x + 32, y: sourcePosition.y + 32 });
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toEqual(before);
  await page.locator('#btn-redo').click();
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['dosomething', 'dosomething-copy-1', 'end', 'error', 'start'],
    edges: before.edges,
  });

  await page.evaluate(() => { window.cy.$(':selected').unselect(); });
  await page.mouse.click(worker.x, worker.y, { button: 'right' });
  const menu = page.getByRole('menu', { name: 'Actions for Do something' });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: 'Duplicate Do something' })).toBeEnabled();
  await expect(menu.getByRole('menuitem', { name: 'Trace full path from Do something' })).toBeEnabled();
  await page.keyboard.press('Escape');
  await expect(menu).toBeHidden();
});

test('delete acts on only the hovered node without click-through and remains one undo step', async ({ page }) => {
  await open(page, { editing: true });
  await page.evaluate(() => { window.cy.getElementById('end').select(); });
  const before = await graphState(page);
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(worker.x, worker.y);
  const remove = page.getByRole('button', { name: 'Delete Do something' });
  await expect(remove).toBeVisible();
  await remove.click();

  await expect.poll(() => graphState(page)).toEqual({
    nodes: ['end', 'error', 'start'], edges: [], selected: ['end'],
    historyDepth: before.historyDepth + 1,
  });
  await expect.poll(() => overlayState(page)).toMatchObject({ rootHidden: true, barHidden: true });
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['dosomething', 'end', 'error', 'start'],
    edges: ['edge-dosomething-end', 'edge-dosomething-error', 'edge-start-dosomething'],
  });

  const start = await nodePoint(page, 'start');
  await page.mouse.move(start.x, start.y);
  await expect(page.getByRole('button', { name: 'Delete Start' })).toBeVisible();
  await page.mouse.click(start.x, start.y);
  await expect.poll(() => graphState(page)).toMatchObject({ selected: ['start'] });
  await expect.poll(() => overlayState(page)).toMatchObject({ rootHidden: true, barHidden: true });
});

test('mouse More hands each enabled menuitem a fresh canonical action sequence', async ({ page }) => {
  await open(page, { editing: true });
  const worker = await nodePoint(page, 'dosomething');
  const openMore = async () => {
    await page.mouse.move(2, 2);
    await page.mouse.move(worker.x, worker.y);
    await page.getByRole('button', { name: 'More node actions' }).click();
    await expect(page.getByRole('menu', { name: 'Actions for Do something' })).toBeVisible();
  };
  const expectMenuitemOwnsItsPoint = async name => {
    const item = page.getByRole('menuitem', { name });
    await expect(item).toBeVisible();
    expect(await item.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const top = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
      return top === element || element.contains(top);
    })).toBe(true);
    return item;
  };
  const before = await graphState(page);
  await openMore();
  await (await expectMenuitemOwnsItsPoint('Trace full path from Do something')).click();
  expect(await graphState(page)).toEqual(before);

  await openMore();
  await (await expectMenuitemOwnsItsPoint('Duplicate Do something')).click();
  await expect.poll(() => graphState(page)).toMatchObject({ historyDepth: before.historyDepth + 1 });
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toEqual(before);

  await openMore();
  await (await expectMenuitemOwnsItsPoint('Delete Do something')).click();
  await expect.poll(() => graphState(page)).toMatchObject({ historyDepth: before.historyDepth + 1 });
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: before.nodes, edges: before.edges, historyDepth: before.historyDepth,
  });
});

test('a hidden-between-clicks action owns the full double-click sequence', async ({ page }) => {
  await open(page, { editing: true });
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(worker.x, worker.y);
  const duplicate = page.getByRole('button', { name: 'Duplicate Do something' });
  await expect(duplicate).toBeVisible();
  const box = await duplicate.boundingBox();
  const before = await page.evaluate(() => {
    const container = window.cy.container();
    window.__nodeActionStageEvents = 0;
    const leaked = event => {
      if (!event.target.closest?.('.graph-node-actions-overlay')) window.__nodeActionStageEvents += 1;
    };
    ['pointerdown', 'pointerup', 'click', 'dblclick'].forEach(type =>
      container.addEventListener(type, leaked, true));
    window.cy.on('tap', event => {
      if (event.target === window.cy) window.__nodeActionStageEvents += 1;
    });
    return {
      graph: {
        nodes: window.cy.nodes().map(node => node.id()).sort(),
        edges: window.cy.edges().map(edge => edge.id()).sort(),
        depth: window.ravenroot.activeDocument().history.depth(),
        positions: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])),
      },
      pan: window.cy.pan(), zoom: window.cy.zoom(),
    };
  });
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  // The first click of the native double-click activates and hides the action. The second press and
  // terminal dblclick still belong to that physical sequence even though its DOM owner is gone.
  await page.mouse.dblclick(x, y);
  await expect.poll(() => page.evaluate(() => ({
    nodes: window.cy.nodes().map(node => node.id()).sort(),
    edges: window.cy.edges().map(edge => edge.id()).sort(),
    selected: window.cy.nodes(':selected').map(node => node.id()),
    depth: window.ravenroot.activeDocument().history.depth(),
    pan: window.cy.pan(), zoom: window.cy.zoom(),
    stageEvents: window.__nodeActionStageEvents,
    edgeGesture: document.querySelector('#cy-wrap')?.dataset.edgeGestureState || 'idle',
    positions: Object.fromEntries(window.cy.nodes()
      .filter(node => !node.id().startsWith('dosomething-copy-'))
      .map(node => [node.id(), node.position()])),
    pointerCaptureOwners: [...document.querySelectorAll('*')]
      .filter(element => element.hasPointerCapture?.(1)).length,
  }))).toEqual({
    nodes: [...before.graph.nodes, 'dosomething-copy-1'].sort(),
    edges: before.graph.edges,
    selected: ['dosomething-copy-1'],
    depth: before.graph.depth + 1,
    pan: before.pan, zoom: before.zoom,
    positions: before.graph.positions,
    pointerCaptureOwners: 0,
    stageEvents: 0, edgeGesture: 'idle',
  });
  const blocked = await page.evaluate(() => ({
    nodes: window.cy.nodes().length,
    selected: window.cy.$(':selected').map(element => element.id()).sort(),
    stageEvents: window.__nodeActionStageEvents,
  }));
  // A new detail=1 sequence has no authority from the completed action and reaches the stage
  // immediately; no wall-clock expiry is involved.
  await page.mouse.click(x, y);
  await expect.poll(() => page.evaluate(previous => {
    const selected = window.cy.$(':selected').map(element => element.id()).sort();
    return window.__nodeActionStageEvents > previous.stageEvents
      || window.cy.nodes().length !== previous.nodes
      || JSON.stringify(selected) !== JSON.stringify(previous.selected);
  }, blocked)).toBe(true);
});

test('vertical toolbar keeps the 115% geometry at 100% and 66% zoom, flips, clamps and keeps its bridge', async ({ page }) => {
  await open(page, { editing: true });
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  const toolbar = page.locator('.doc-pane--active .graph-node-actions');
  await expect(toolbar).toBeVisible();
  await expect(toolbar).toHaveAttribute('aria-orientation', 'vertical');
  const geometry = await page.evaluate(() => {
    const container = window.cy.container();
    const node = window.cy.getElementById('dosomething');
    const root = container.querySelector('.graph-node-actions-overlay');
    const rect = element => element.getBoundingClientRect().toJSON();
    return {
      container: rect(container), node: node.renderedBoundingBox({ includeLabels: false }),
      bar: rect(root.querySelector('.graph-node-actions')),
      bridge: rect(root.querySelector('.graph-node-actions-bridge')),
      visibleActions: [...root.querySelectorAll('.graph-node-actions button')]
        .filter(button => !button.hidden).map(button => ({
          action: button.dataset.nodeAction,
          rect: rect(button),
        })),
    };
  });
  expect(geometry.visibleActions.map(({ action }) => action))
    .toEqual(['trace', 'delete', 'duplicate', 'more']);
  expect(new Set(geometry.visibleActions.map(({ rect }) => rect.x)).size).toBe(1);
  for (let index = 1; index < geometry.visibleActions.length; index += 1) {
    expect(geometry.visibleActions[index].rect.y).toBeGreaterThan(geometry.visibleActions[index - 1].rect.y);
  }
  expect(geometry.bar.width).toBeCloseTo(25.3, 1);
  expect(geometry.bar.height).toBeCloseTo(86.25, 1);
  expect(geometry.bridge.width).toBeCloseTo(9.2, 1);
  expect(geometry.bar.x).toBeGreaterThanOrEqual(geometry.container.x + 4.5);
  expect(geometry.bar.x + geometry.bar.width)
    .toBeLessThanOrEqual(geometry.container.x + geometry.container.width - 4.5);

  await page.evaluate(() => {
    window.cy.zoom({ level: 0.66, renderedPosition: { x: 400, y: 260 } });
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
  const zoomedGeometry = await page.evaluate(() => {
    const root = document.querySelector('.doc-pane--active .graph-node-actions-overlay');
    const rect = element => element.getBoundingClientRect().toJSON();
    return {
      bar: rect(root.querySelector('.graph-node-actions')),
      bridge: rect(root.querySelector('.graph-node-actions-bridge')),
      buttons: [...root.querySelectorAll('.graph-node-actions button')]
        .filter(button => !button.hidden).map(button => rect(button)),
    };
  });
  expect(zoomedGeometry.bar.width).toBeCloseTo(25.3, 1);
  expect(zoomedGeometry.bar.height).toBeCloseTo(86.25, 1);
  expect(zoomedGeometry.bridge.width).toBeCloseTo(9.2, 1);
  for (const button of zoomedGeometry.buttons) {
    expect(button.width).toBeCloseTo(20.7, 1);
    expect(button.height).toBeCloseTo(19.55, 1);
  }

  const bridge = page.locator('.doc-pane--active .graph-node-actions-bridge');
  const bridgeBox = await bridge.boundingBox();
  await page.mouse.move(bridgeBox.x + bridgeBox.width / 2, bridgeBox.y + bridgeBox.height / 2, { steps: 8 });
  await page.waitForTimeout(150);
  await expect(page.locator('.doc-pane--active .graph-node-actions')).toBeVisible();
  const before = await graphState(page);
  for (const pointerType of ['mouse', 'touch', 'pen']) {
    await bridge.dispatchEvent('pointerdown', { pointerType, bubbles: true, clientX: bridgeBox.x, clientY: bridgeBox.y });
    await bridge.dispatchEvent('pointercancel', { pointerType, bubbles: true, clientX: bridgeBox.x, clientY: bridgeBox.y });
  }
  await bridge.dispatchEvent('click', { bubbles: true });
  await bridge.dispatchEvent('auxclick', { bubbles: true, button: 1 });
  await bridge.dispatchEvent('contextmenu', { bubbles: true, button: 2 });
  expect(await graphState(page)).toEqual(before);
  await page.mouse.move(2, 2);
  await page.waitForTimeout(150);
  await expect(page.locator('.doc-pane--active .graph-node-actions')).toBeHidden();

  await page.evaluate(() => {
    const node = window.cy.getElementById('dosomething');
    const current = node.renderedPosition();
    const width = window.cy.container().clientWidth;
    window.cy.panBy({ x: width - current.x - 12, y: 0 });
  });
  const nearRight = await nodePoint(page, 'dosomething');
  await page.mouse.move(nearRight.x, nearRight.y);
  await expect(page.getByRole('button', { name: 'Duplicate Do something' })).toBeVisible();
  const flipped = await page.evaluate(() => {
    const node = window.cy.getElementById('dosomething').renderedBoundingBox({ includeLabels: false });
    const bar = document.querySelector('.doc-pane--active .graph-node-actions').getBoundingClientRect();
    const bridge = document.querySelector('.doc-pane--active .graph-node-actions-bridge').getBoundingClientRect();
    const container = window.cy.container().getBoundingClientRect();
    return {
      nodeCenterX: container.left + node.x1 + node.w / 2,
      barRight: bar.right, bridgeLeft: bridge.left, bridgeRight: bridge.right,
      bridgeWidth: bridge.width,
    };
  });
  expect(flipped.barRight).toBeCloseTo(flipped.bridgeLeft, 1);
  expect(flipped.bridgeRight).toBeLessThan(flipped.nodeCenterX);
  expect(flipped.bridgeWidth).toBeCloseTo(9.2, 1);
});

test('global minibar size applies live to mounted and new overlays and persists across reloads', async ({ page }) => {
  await open(page, { editing: true });
  const slider = page.getByRole('slider', { name: 'Node minibar size' });
  await expect(slider).toHaveValue('115');
  await expect(page.locator('#node-action-scale-val')).toHaveText('115');

  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(worker.x, worker.y);
  const duplicate = page.getByRole('button', { name: 'Duplicate Do something' });
  await expect(duplicate).toBeVisible();
  await slider.fill('145');
  await expect(slider).toHaveValue('145');
  await expect(page.locator('#node-action-scale-val')).toHaveText('145');
  await expect.poll(async () => (await duplicate.boundingBox())?.width).toBeCloseTo(26.1, 1);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('ravenroot.node-actions.scale.v1'))))
    .toEqual({ version: 1, percent: 145 });

  await page.evaluate(() => window.ravenroot.openDocument());
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  const newOverlay = page.locator('.doc-pane--active .graph-node-actions');
  await expect(newOverlay).toBeVisible();
  expect((await newOverlay.boundingBox()).width).toBeCloseTo(31.9, 1);

  await page.reload();
  await expect(slider).toHaveValue('145');
  await expect(page.locator('#node-action-scale-val')).toHaveText('145');
  await expect.poll(() => page.evaluate(() => document.documentElement.dataset.nodeActionScale)).toBe('145');
});

test('corrupt, out-of-range and unavailable minibar preferences safely recover to 115%', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('ravenroot.node-actions.scale.v1', JSON.stringify({ version: 1, percent: 900 }));
  });
  await open(page);
  await expect(page.getByRole('slider', { name: 'Node minibar size' })).toHaveValue('115');

  const unavailable = await page.context().newPage();
  await unavailable.addInitScript(() => {
    const original = Storage.prototype.getItem;
    Storage.prototype.getItem = function (key) {
      if (key === 'ravenroot.node-actions.scale.v1') throw new DOMException('denied', 'SecurityError');
      return original.call(this, key);
    };
  });
  await unavailable.route('**/v1/node-types', route => route.fulfill({ status: 200,
    contentType: 'application/json; charset=utf-8', body: '[]' }));
  await unavailable.route('**/v1/events', route => route.fulfill({ status: 200,
    contentType: 'text/event-stream', body: '' }));
  await unavailable.goto('/');
  await expect(unavailable.getByRole('slider', { name: 'Node minibar size' })).toHaveValue('115');
  await unavailable.close();
});

test('a delayed sequence stays owned through hide and releases on its matching terminal event', async ({ page }) => {
  await open(page, { editing: true });
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(worker.x, worker.y);
  const duplicate = page.getByRole('button', { name: 'Duplicate Do something' });
  const box = await duplicate.boundingBox();
  const point = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  const before = await graphState(page);
  await page.evaluate(() => {
    window.__nodeActionStageEvents = 0;
    const container = window.cy.container();
    ['pointerup', 'click'].forEach(type => container.addEventListener(type, () => {
      window.__nodeActionStageEvents += 1;
    }, true));
  });
  await page.mouse.move(point.x, point.y);
  await page.mouse.down();
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.layoutMode = 'elastic';
    owner.cy.emit('render');
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
  await expect(page.locator('.doc-pane--active .graph-node-actions-overlay')).toBeHidden();
  await page.waitForTimeout(650);
  await page.mouse.up();
  await page.evaluate(() => { window.ravenroot.activeDocument().layoutMode = 'dagre'; });
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: [...before.nodes, 'dosomething-copy-1'].sort(),
    edges: before.edges,
    historyDepth: before.historyDepth + 1,
  });
  expect(await page.evaluate(() => window.__nodeActionStageEvents)).toBe(0);

  // A spatially distinct next gesture is not ambiguous, irrespective of the elapsed time. Its FIRST
  // sequence completes a semantic stage edit (and one undo), proving pointerdown was not swallowed.
  await page.evaluate(() => { window.cy.$(':selected').unselect(); });
  const beforeStage = await graphState(page);
  const canvas = await page.locator('#cy-wrap').boundingBox();
  await page.mouse.click(canvas.x + canvas.width / 2, canvas.y + canvas.height - 36);
  await expect.poll(() => page.evaluate(() => window.__nodeActionStageEvents)).toBeGreaterThan(0);
  await expect.poll(() => graphState(page)).toMatchObject({
    historyDepth: beforeStage.historyDepth + 1,
  });
  expect((await graphState(page)).nodes).toHaveLength(beforeStage.nodes.length + 1);
  await pressReadyUndoShortcut(page);
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: beforeStage.nodes, edges: beforeStage.edges, historyDepth: beforeStage.historyDepth,
  });
});

test('outside pointer dismissal is owner-scoped and does not consume the intended outside action', async ({ page }) => {
  await open(page, { editing: true });
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.click(worker.x, worker.y, { button: 'right' });
  const menu = page.getByRole('menu', { name: 'Actions for Do something' });
  await expect(menu).toBeVisible();
  const canvas = await page.locator('#cy-wrap').boundingBox();
  const before = await graphState(page);
  await page.mouse.click(canvas.x + 8, canvas.y + 8);
  await expect(menu).toBeHidden();
  await expect.poll(() => page.evaluate(() => ({
    hiddenMenuFocused: Boolean(document.activeElement?.closest?.('.graph-node-action-menu[hidden]')),
    bodyFocused: document.activeElement === document.body,
    ownerFocused: Boolean(document.activeElement?.matches?.('#cy-wrap, .doc-pane')),
  }))).toEqual({ hiddenMenuFocused: false, bodyFocused: false, ownerFocused: true });
  const afterCanvas = await graphState(page);
  expect(afterCanvas.historyDepth - before.historyDepth).toBeLessThanOrEqual(1);

  await page.mouse.click(worker.x, worker.y, { button: 'right' });
  await expect(menu).toBeVisible();
  await page.locator('#btn-navigation').click();
  await expect(menu).toBeHidden();
  await expect(page.locator('#btn-navigation')).toBeFocused();
  await expect(page.locator('.graph-node-action-menu:visible')).toHaveCount(0);

  await page.mouse.click(worker.x, worker.y, { button: 'right' });
  await expect(menu).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(menu).toBeHidden();
  await expect.poll(() => page.evaluate(() => document.activeElement?.classList.contains('doc-pane')))
    .toBe(true);
});

test('destroy during a claimed press retains only the anchor until terminal release', async ({ page }) => {
  await page.addInitScript(() => {
    const types = new Set([
      'pointerdown', 'pointermove', 'pointerup', 'pointercancel',
      'click', 'dblclick', 'auxclick', 'contextmenu',
    ]);
    const listeners = new Map([...types].map(type => [type, new Set()]));
    const add = EventTarget.prototype.addEventListener;
    const remove = EventTarget.prototype.removeEventListener;
    EventTarget.prototype.addEventListener = function (type, listener, options) {
      if (this === window && types.has(type) && (options === true || options?.capture)) {
        listeners.get(type).add(listener);
      }
      return add.call(this, type, listener, options);
    };
    EventTarget.prototype.removeEventListener = function (type, listener, options) {
      if (this === window && types.has(type) && (options === true || options?.capture)) {
        listeners.get(type).delete(listener);
      }
      return remove.call(this, type, listener, options);
    };
    window.__nodeActionWindowListenerCount = () => [...listeners.values()]
      .reduce((sum, set) => sum + set.size, 0);
  });
  await open(page, { editing: true });
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await page.evaluate(() => window.ravenroot.openDocument());
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(worker.x, worker.y);
  const trace = page.getByRole('button', { name: 'Trace full path from Do something' });
  await expect(trace).toBeVisible();
  const box = await trace.boundingBox();
  const visibleListeners = await page.evaluate(() => window.__nodeActionWindowListenerCount());
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.evaluate(id => window.ravenroot.closeDocument(id), first);
  expect(await page.evaluate(() => window.__nodeActionWindowListenerCount())).toBe(visibleListeners);
  await page.mouse.up();
  expect(await page.evaluate(() => window.__nodeActionWindowListenerCount())).toBe(visibleListeners - 8);
  await expect(page.locator(`.graph-node-actions-overlay[data-document-id="${first}"]`)).toHaveCount(0);
  expect(await page.evaluate(id => window.ravenroot.documents().some(document_ => document_.id === id), first))
    .toBe(false);
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await page.evaluate(() => { window.cy.$(':selected').unselect(); });
  const beforeNext = await graphState(page);
  const canvas = await page.locator('#cy-wrap').boundingBox();
  await page.mouse.click(canvas.x + canvas.width / 2, canvas.y + canvas.height - 36);
  await expect.poll(() => graphState(page)).toMatchObject({
    historyDepth: beforeNext.historyDepth + 1,
  });
  expect((await graphState(page)).nodes).toHaveLength(beforeNext.nodes.length + 1);
});

test('vertical toolbar and textual menu own keyboard focus without a trap', async ({ page }) => {
  await open(page, { editing: true });
  const worker = await nodePoint(page, 'dosomething');
  await page.mouse.move(2, 2);
  await page.mouse.move(worker.x, worker.y);
  const trace = page.getByRole('button', { name: 'Trace full path from Do something' });
  await trace.focus();
  await page.keyboard.press('ArrowRight');
  await expect(trace).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('button', { name: 'Delete Do something' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('button', { name: 'Duplicate Do something' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('button', { name: 'More node actions' })).toBeFocused();
  await page.keyboard.press('Enter');
  const menu = page.getByRole('menu', { name: 'Actions for Do something' });
  await expect(menu.getByRole('menuitem', { name: 'Trace full path from Do something' })).toBeFocused();
  await page.keyboard.press('ArrowUp');
  await expect(menu.getByRole('menuitem', { name: 'Delete Do something' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(menu.getByRole('menuitem', { name: 'Trace full path from Do something' })).toBeFocused();
  await page.keyboard.press('End');
  await expect(menu.getByRole('menuitem', { name: 'Delete Do something' })).toBeFocused();
  await page.keyboard.press('Home');
  await expect(menu.getByRole('menuitem', { name: 'Trace full path from Do something' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(menu.getByRole('menuitem', { name: 'Duplicate Do something' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(menu).toBeHidden();
  await expect.poll(() => page.evaluate(() => document.activeElement?.classList.contains('doc-pane'))).toBe(true);

  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Tab');
  await expect(page.locator('.doc-pane--active .graph-node-action:focus')).toHaveCount(1);
  await page.keyboard.press('Shift+Tab');
  await expect(page.locator('.doc-pane--active .graph-node-actions')).toBeHidden();
  await expect.poll(() => page.evaluate(() => document.activeElement?.matches('.graph-node-action'))).toBe(false);
});

test.describe('coarse pointer node actions', () => {
  test.use({ hasTouch: true, viewport: { width: 768, height: 1024 } });

  test('keeps a 44px minimum More target and the global control reachable on a coarse viewport', async ({ page }) => {
    await open(page, { editing: true });
    const scaleSlider = page.getByRole('slider', { name: 'Node minibar size' });
    await expect(scaleSlider).toBeVisible();
    const scaleBox = await scaleSlider.boundingBox();
    expect(scaleBox.x).toBeGreaterThanOrEqual(0);
    expect(scaleBox.x + scaleBox.width).toBeLessThanOrEqual(768);
    await page.locator('#cy-wrap').focus();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Tab');
    const more = page.getByRole('button', { name: 'More node actions' });
    await expect(more).toBeFocused();
    const geometry = await page.evaluate(() => {
      const bar = document.querySelector('.doc-pane--active .graph-node-actions');
      const buttons = [...bar.querySelectorAll('button')].map(button => ({
        action: button.dataset.nodeAction,
        display: getComputedStyle(button).display,
        rect: button.getBoundingClientRect().toJSON(),
      }));
      return { bar: bar.getBoundingClientRect().toJSON(), buttons };
    });
    expect(geometry.buttons.filter(button => button.display !== 'none').map(button => button.action))
      .toEqual(['more']);
    expect(geometry.buttons.find(button => button.action === 'more').rect.width).toBeCloseTo(50.6, 1);
    expect(geometry.buttons.find(button => button.action === 'more').rect.height).toBeCloseTo(50.6, 1);
    expect(geometry.bar.width).toBeCloseTo(57.5, 1);
    expect(geometry.bar.height).toBeCloseTo(57.5, 1);
    await page.keyboard.press('Enter');
    const menu = page.getByRole('menu');
    const items = menu.getByRole('menuitem');
    await expect(items).toHaveCount(3);
    const boxes = await items.evaluateAll(elements => elements.map(element => element.getBoundingClientRect().toJSON()));
    for (const box of boxes) expect(box.height).toBeGreaterThanOrEqual(50.5);
    for (let index = 1; index < boxes.length; index += 1) {
      expect(boxes[index].top).toBeGreaterThanOrEqual(boxes[index - 1].bottom);
    }
  });

  test('a real touchscreen tap selects, exposes More, and executes one canonical history action', async ({ page }) => {
    await open(page, { editing: true });
    const worker = await nodePoint(page, 'dosomething');
    const before = await graphState(page);
    await page.touchscreen.tap(worker.x, worker.y);
    await expect.poll(() => graphState(page)).toMatchObject({ selected: ['dosomething'] });
    const more = page.getByRole('button', { name: 'More node actions' });
    await expect(more).toBeVisible();
    const moreBox = await more.boundingBox();
    await page.touchscreen.tap(moreBox.x + moreBox.width / 2, moreBox.y + moreBox.height / 2);
    const duplicate = page.getByRole('menuitem', { name: 'Duplicate Do something' });
    await expect(duplicate).toBeVisible();
    const duplicateBox = await duplicate.boundingBox();
    await page.touchscreen.tap(
      duplicateBox.x + duplicateBox.width / 2, duplicateBox.y + duplicateBox.height / 2);
    await expect.poll(() => graphState(page)).toMatchObject({
      nodes: [...before.nodes, 'dosomething-copy-1'].sort(),
      edges: before.edges,
      historyDepth: before.historyDepth + 1,
    });
    await pressReadyUndoShortcut(page);
    await expect.poll(() => graphState(page)).toMatchObject({
      nodes: before.nodes, edges: before.edges, historyDepth: before.historyDepth,
    });

    await page.touchscreen.tap(worker.x, worker.y);
    await expect(more).toBeVisible();
    const reopened = await more.boundingBox();
    await page.touchscreen.tap(reopened.x + reopened.width / 2, reopened.y + reopened.height / 2);
    await expect(page.getByRole('menu')).toBeVisible();
    const canvas = await page.locator('#cy-wrap').boundingBox();
    await page.touchscreen.tap(canvas.x + 8, canvas.y + 8);
    await expect(page.getByRole('menu')).toBeHidden();
  });

  test('touch cancel releases ownership and a trusted pen path can reopen the selected affordance', async ({ page }) => {
    await open(page, { editing: true });
    const worker = await nodePoint(page, 'dosomething');
    await page.touchscreen.tap(worker.x, worker.y);
    const more = page.getByRole('button', { name: 'More node actions' });
    const box = await more.boundingBox();
    const client = await page.context().newCDPSession(page);
    await client.send('Input.dispatchTouchEvent', {
      type: 'touchStart', touchPoints: [{ x: box.x + 22, y: box.y + 22, id: 41 }],
    });
    await client.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
    await expect(page.getByRole('menu')).toBeHidden();

    await client.send('Input.dispatchMouseEvent', {
      type: 'mouseMoved', x: box.x + 22, y: box.y + 22, pointerType: 'pen',
    });
    await client.send('Input.dispatchMouseEvent', {
      type: 'mousePressed', x: box.x + 22, y: box.y + 22,
      button: 'left', buttons: 1, clickCount: 1, pointerType: 'pen',
    });
    await client.send('Input.dispatchMouseEvent', {
      type: 'mouseReleased', x: box.x + 22, y: box.y + 22,
      button: 'left', buttons: 0, clickCount: 1, pointerType: 'pen',
    });
    await expect(page.getByRole('menu', { name: 'Actions for Do something' })).toBeVisible();
  });
});

test('closing a document cancels overlay async work and removes global and canvas listeners', async ({ page }) => {
  await page.addInitScript(() => {
    const trackedTypes = new Set([
      'pointerdown', 'pointermove', 'pointerup', 'pointercancel',
      'click', 'dblclick', 'auxclick', 'contextmenu',
    ]);
    const documentListeners = new Map([...trackedTypes].map(type => [type, new Set()]));
    const canvasLeaveListeners = [];
    const originalAdd = EventTarget.prototype.addEventListener;
    const originalRemove = EventTarget.prototype.removeEventListener;
    EventTarget.prototype.addEventListener = function (type, listener, options) {
      if (this === window && trackedTypes.has(type) && (options === true || options?.capture)) {
        documentListeners.get(type).add(listener);
      }
      if (type === 'pointerleave' && this.classList?.contains('doc-canvas')) {
        canvasLeaveListeners.push({ target: this, listener });
      }
      return originalAdd.call(this, type, listener, options);
    };
    EventTarget.prototype.removeEventListener = function (type, listener, options) {
      if (this === window && trackedTypes.has(type) && (options === true || options?.capture)) {
        documentListeners.get(type).delete(listener);
      }
      if (type === 'pointerleave' && this.classList?.contains('doc-canvas')) {
        const index = canvasLeaveListeners.findIndex(item => item.target === this && item.listener === listener);
        if (index >= 0) canvasLeaveListeners.splice(index, 1);
      }
      return originalRemove.call(this, type, listener, options);
    };
    const overlayRafs = new Set();
    const overlayTimers = new Set();
    const originalRaf = window.requestAnimationFrame;
    const originalCancelRaf = window.cancelAnimationFrame;
    const originalTimeout = window.setTimeout;
    const originalClearTimeout = window.clearTimeout;
    let cancelledRafs = 0;
    let clearedTimers = 0;
    let trackingAsync = false;
    window.requestAnimationFrame = callback => {
      const handle = originalRaf(callback);
      if (trackingAsync) overlayRafs.add(handle);
      return handle;
    };
    window.cancelAnimationFrame = handle => {
      if (overlayRafs.delete(handle)) cancelledRafs += 1;
      return originalCancelRaf(handle);
    };
    window.setTimeout = (callback, delay, ...args) => {
      const handle = originalTimeout(callback, delay, ...args);
      if (trackingAsync) overlayTimers.add(handle);
      return handle;
    };
    window.clearTimeout = handle => {
      if (overlayTimers.delete(handle)) clearedTimers += 1;
      return originalClearTimeout(handle);
    };
    window.__nodeActionTeardown = {
      reset() {
        overlayRafs.clear(); overlayTimers.clear(); cancelledRafs = 0; clearedTimers = 0;
        trackingAsync = true;
      },
      snapshot() {
        return {
          documentListeners: [...documentListeners.values()].reduce((sum, listeners) => sum + listeners.size, 0),
          canvasLeaveListeners: canvasLeaveListeners.length,
          pendingRafs: overlayRafs.size,
          pendingTimers: overlayTimers.size,
          cancelledRafs,
          clearedTimers,
        };
      },
    };
  });
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await open(page, { editing: true });
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await page.evaluate(documentId => {
    window.ravenroot.openDocument();
    window.ravenroot.activateDocument(documentId);
  }, first);
  await page.evaluate(() => new Promise(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.doc-pane--active .graph-node-actions')).toBeVisible();
  const firstVisible = await page.evaluate(() => window.__nodeActionTeardown.snapshot().documentListeners);
  await page.evaluate(() => window.ravenroot.openDocument());
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.doc-pane--active .graph-node-actions')).toBeVisible();
  expect(await page.evaluate(() => window.__nodeActionTeardown.snapshot().documentListeners))
    .toBe(firstVisible);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(() => window.__nodeActionTeardown.snapshot().documentListeners))
    .toBe(firstVisible);
  await page.evaluate(documentId => window.ravenroot.activateDocument(documentId), first);
  await page.keyboard.press('Escape');
  expect(await page.evaluate(() => window.__nodeActionTeardown.snapshot().documentListeners))
    .toBe(firstVisible - 8);
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  expect(await page.evaluate(() => window.__nodeActionTeardown.snapshot().documentListeners))
    .toBe(firstVisible);
  const teardown = await page.evaluate(documentId => {
    const tracker = window.__nodeActionTeardown;
    tracker.reset();
    const owner = window.ravenroot.workspace.find(documentId);
    const instance = owner.cy;
    instance.emit('pan');
    instance.container().dispatchEvent(new PointerEvent('pointerleave'));
    const before = tracker.snapshot();
    const closed = window.ravenroot.closeDocument(documentId);
    return { before, after: tracker.snapshot(), closed, destroyed: instance.destroyed() };
  }, first);
  expect(teardown).toMatchObject({ closed: true, destroyed: true });
  expect(teardown.before.pendingRafs).toBeGreaterThanOrEqual(1);
  expect(teardown.before.pendingTimers).toBeGreaterThanOrEqual(1);
  expect(teardown.after.cancelledRafs).toBeGreaterThanOrEqual(1);
  expect(teardown.after.clearedTimers).toBeGreaterThanOrEqual(1);
  expect(teardown.before.documentListeners - teardown.after.documentListeners).toBe(8);
  expect(teardown.before.canvasLeaveListeners - teardown.after.canvasLeaveListeners).toBe(1);
  await page.waitForTimeout(150);
  await expect(page.locator(`.graph-node-actions-overlay[data-document-id="${first}"]`)).toHaveCount(0);
  const beforeLastRenderer = await page.evaluate(() => window.__nodeActionTeardown.snapshot());
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  const afterLastRenderer = await page.evaluate(() => window.__nodeActionTeardown.snapshot());
  expect(beforeLastRenderer.documentListeners - afterLastRenderer.documentListeners).toBe(0);
  expect(pageErrors).toEqual([]);
});

test.describe('forced-colors node actions', () => {
  test.use({ forcedColors: 'active', colorScheme: 'dark' });

  test('keeps the feature accessible and emits a visual artifact', async ({ page }, testInfo) => {
    await open(page, { editing: true });
    const worker = await nodePoint(page, 'dosomething');
    await page.mouse.move(worker.x, worker.y);
    const duplicate = page.getByRole('button', { name: 'Duplicate Do something' });
    await expect(duplicate).toBeVisible();
    await duplicate.focus();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('.doc-pane--active .graph-node-action:focus')).toHaveCount(1);
    const forced = await page.locator('.doc-pane--active .graph-node-actions').evaluate(element => ({
      borderStyle: getComputedStyle(element).borderStyle,
      borderWidth: getComputedStyle(element).borderWidth,
      outlineStyle: getComputedStyle(element).outlineStyle,
      focusOutline: getComputedStyle(document.activeElement).outlineStyle,
    }));
    expect(forced.outlineStyle).toBe('solid');
    expect(forced.focusOutline).not.toBe('none');
    const axe = await new AxeBuilder({ page }).include('.doc-pane--active .graph-node-actions-overlay').analyze();
    expect(axe.violations).toEqual([]);
    const path = testInfo.outputPath('node-actions-forced-colors.png');
    const screenshot = await page.screenshot({ path, fullPage: true });
    expect(screenshot.byteLength).toBeGreaterThan(10_000);
    await testInfo.attach('node-actions-forced-colors', { path, contentType: 'image/png' });
  });
});

test('read-only, keyboard, theme, zoom and document ownership keep the minibar honest', async ({ page }) => {
  await open(page);
  let start = await nodePoint(page, 'start');
  await page.mouse.move(start.x, start.y);
  await expect(page.getByRole('button', { name: 'Trace full path from Start' })).toBeVisible();
  await expect(page.locator('.graph-node-action[data-node-action="delete"]')).toBeHidden();

  const dark = await overlayState(page);
  await page.evaluate(() => {
    window.ravenroot.setApplicationTheme('light');
    window.cy.zoom({ level: 0.55, renderedPosition: { x: 400, y: 260 } });
  });
  await expect.poll(() => overlayState(page).then(state => state.rect)).not.toEqual(dark.rect);
  await expect(page.getByRole('button', { name: 'Trace full path from Start' })).toBeVisible();

  await page.mouse.move(4, 4);
  await expect.poll(() => overlayState(page)).toMatchObject({ rootHidden: true, barHidden: true });
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('ArrowRight');
  const keyboardTrace = page.getByRole('button', { name: 'Trace full path from Do something' });
  await expect(keyboardTrace).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(keyboardTrace).toBeFocused();
  await page.keyboard.press('Enter');
  await expect.poll(() => overlayState(page)).toMatchObject({ rootHidden: true, barHidden: true });
  await expect.poll(() => page.evaluate(() => document.activeElement?.classList.contains('doc-pane'))).toBe(true);

  await page.locator('#btn-new').click();
  const ownership = await page.evaluate(() => ({
    activeId: window.ravenroot.activeDocument().id,
    documents: window.ravenroot.documents().map(document_ => document_.id).sort(),
    overlays: [...document.querySelectorAll('.graph-node-actions-overlay')]
      .map(root => root.dataset.documentId).sort(),
  }));
  expect(ownership.overlays).toEqual(ownership.documents);
  await page.locator('#menu-file').click();
  await page.getByRole('menuitem', { name: 'Close Document' }).click();
  await expect.poll(() => page.evaluate(closedId => ({
    documentExists: window.ravenroot.documents().some(document_ => document_.id === closedId),
    overlayExists: Boolean(document.querySelector(`.graph-node-actions-overlay[data-document-id="${closedId}"]`)),
  }), ownership.activeId)).toEqual({ documentExists: false, overlayExists: false });

  await page.evaluate(() => {
    window.ravenroot.replaceActiveDocumentFromText(JSON.stringify({
      nodes: [{ id: 'graphify-node', label: 'Graphify node', type: 'file' }], edges: [],
    }), 'catalog.json');
    window.cy.getElementById('graphify-node').position({ x: 400, y: 220 });
    window.cy.fit(undefined, 100);
  });
  const graphify = await nodePoint(page, 'graphify-node');
  await page.mouse.move(graphify.x, graphify.y);
  const activePane = page.locator('.doc-pane--active');
  await expect(activePane.locator('.graph-node-action[data-node-action="trace"]')).toBeVisible();
  await expect(activePane.locator('.graph-node-action[data-node-action="delete"]')).toBeHidden();

  await page.reload();
  start = await nodePoint(page, 'start');
  await page.mouse.move(start.x, start.y);
  await expect(page.getByRole('button', { name: 'Trace full path from Start' })).toBeVisible();
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await expect(page.locator('.doc-pane--active .graph-node-actions-overlay')).toHaveCount(0);
});
