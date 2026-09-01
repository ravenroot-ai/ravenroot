import { expect, test } from '@playwright/test';

async function open(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await page.evaluate(() => window.ravenroot.setApplicationTheme('light'));
}

async function selectDesign(page) {
  await page.locator('#btn-design').click();
  await expect(page.locator('.doc-pane--active')).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 10_000,
  });
}

const selectedNodeIds = page => page.evaluate(() =>
  window.cy.nodes(':selected').map(node => node.id()).sort());

async function pinSelectionFixture(page) {
  await page.evaluate(() => {
    window.cy.stop(true);
    const positions = {
      start: { x: 140, y: 150 }, dosomething: { x: 420, y: 150 },
      error: { x: 420, y: 390 }, end: { x: 720, y: 150 },
    };
    Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
    window.cy.fit(undefined, 90);
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function nodePoint(page, id) {
  return page.evaluate(nodeId => {
    const point = window.cy.getElementById(nodeId).renderedPosition();
    const box = window.cy.container().getBoundingClientRect();
    return { x: box.left + point.x, y: box.top + point.y };
  }, id);
}

const overlayState = (page, documentId = null) => page.evaluate(id => {
  const owner = id ? window.ravenroot.workspace.find(id) : window.ravenroot.activeDocument();
  const canvas = owner.cy.container();
  const root = [...canvas.children].find(child =>
    child.classList?.contains('graph-selection-overlay') && child.dataset.documentId === owner.id);
  const computed = element => {
    const style = getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    return {
      rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
      pointerEvents: style.pointerEvents, position: style.position, overflow: style.overflow,
      zIndex: style.zIndex, borderWidth: style.borderWidth, borderStyle: style.borderStyle,
      borderColor: style.borderColor, backgroundColor: style.backgroundColor,
    };
  };
  return {
    id: owner.id,
    root: root && { ready: root.dataset.ready, ariaHidden: root.getAttribute('aria-hidden'), ...computed(root) },
    boxes: root ? [...root.querySelectorAll(':scope > .graph-selection-box')].map(box => ({
      nodeId: box.dataset.nodeId,
      ...computed(box),
      handles: [...box.querySelectorAll(':scope > .graph-selection-handle')].map(handle => ({
        corner: handle.dataset.corner, ...computed(handle), ariaHidden: handle.getAttribute('aria-hidden'),
      })),
    })).sort((left, right) => left.nodeId.localeCompare(right.nodeId)) : [],
  };
}, documentId);

const selectionSnapshot = (page, nodeId, documentId = null) => page.evaluate(({ nodeId, documentId }) => {
  const owner = documentId ? window.ravenroot.workspace.find(documentId) : window.ravenroot.activeDocument();
  const node = owner.cy.getElementById(nodeId);
  const canvas = owner.cy.container().getBoundingClientRect();
  const body = node.renderedBoundingBox({
    includeLabels: false, includeOverlays: false, includeUnderlays: false, includeOutlines: false,
  });
  return {
    selected: node.selected(),
    body: { x: canvas.left + body.x1, y: canvas.top + body.y1, width: body.w, height: body.h },
    native: {
      width: node.style('border-width'), color: node.style('border-color'),
      style: node.style('border-style'), background: node.style('background-color'),
    },
  };
}, { nodeId, documentId });

// Cytoscape changes its rendered bbox synchronously, while the visual-only layer deliberately
// follows on its own animation frame.  Sample both sides in one browser task and poll their
// relation, not merely the box's existence: an older box is valid DOM but stale geometry.
const selectionBoxTracksRenderedBody = (page, nodeId, documentId = null) => page.evaluate(({ nodeId, documentId }) => {
  const owner = documentId ? window.ravenroot.workspace.find(documentId) : window.ravenroot.activeDocument();
  const canvas = owner.cy.container();
  const root = [...canvas.children].find(child =>
    child.classList?.contains('graph-selection-overlay') && child.dataset.documentId === owner.id);
  const box = root && [...root.querySelectorAll(':scope > .graph-selection-box')]
    .find(candidate => candidate.dataset.nodeId === nodeId);
  const node = owner.cy.getElementById(nodeId);
  if (!box || !node.selected()) return false;
  const canvasRect = canvas.getBoundingClientRect();
  const body = node.renderedBoundingBox({
    includeLabels: false, includeOverlays: false, includeUnderlays: false, includeOutlines: false,
  });
  const rect = box.getBoundingClientRect();
  const close = (actual, expected) => Math.abs(actual - expected) <= 1.1;
  return close(rect.x, canvasRect.left + body.x1 - 8)
    && close(rect.y, canvasRect.top + body.y1 - 8)
    && close(rect.width, body.w + 16)
    && close(rect.height, body.h + 16);
}, { nodeId, documentId });

function expectNear(actual, expected, label) {
  expect(Math.abs(actual - expected), label).toBeLessThanOrEqual(1.1);
}

async function expectSelectionBox(page, nodeId, documentId = null) {
  await expect.poll(() => overlayState(page, documentId)).toMatchObject({
    root: {
      ready: 'true', ariaHidden: 'true', position: 'absolute', overflow: 'visible',
      pointerEvents: 'none', zIndex: '4',
    },
  });
  await expect.poll(() => selectionBoxTracksRenderedBody(page, nodeId, documentId)).toBe(true);
  const [state, selection] = await Promise.all([
    overlayState(page, documentId), selectionSnapshot(page, nodeId, documentId),
  ]);
  expect(selection.selected).toBe(true);
  const box = state.boxes.find(candidate => candidate.nodeId === nodeId);
  expect(box, `selection box for ${nodeId}`).toBeTruthy();
  // The renderer supplies a body bbox; the 8px projection is a screen-space visual affordance.
  expectNear(box.rect.x, selection.body.x - 8, `${nodeId} box x`);
  expectNear(box.rect.y, selection.body.y - 8, `${nodeId} box y`);
  expectNear(box.rect.width, selection.body.width + 16, `${nodeId} box width`);
  expectNear(box.rect.height, selection.body.height + 16, `${nodeId} box height`);
  expect(box).toMatchObject({
    pointerEvents: 'none', borderWidth: '2px', borderStyle: 'solid',
    borderColor: 'rgb(130, 80, 223)',
  });
  expect(box.handles.map(handle => handle.corner).sort()).toEqual(['ne', 'nw', 'se', 'sw']);
  for (const handle of box.handles) {
    expect(handle).toMatchObject({
      pointerEvents: 'none', borderWidth: '2px', borderStyle: 'solid',
      borderColor: 'rgb(130, 80, 223)', backgroundColor: 'rgb(246, 248, 250)', ariaHidden: null,
    });
    expectNear(handle.rect.width, 6, `${nodeId} ${handle.corner} handle width`);
    expectNear(handle.rect.height, 6, `${nodeId} ${handle.corner} handle height`);
  }
  return selection.native;
}

async function expectNoSelectionBox(page, ids, documentId = null) {
  await expect.poll(() => overlayState(page, documentId).then(state => state.boxes.map(box => box.nodeId)))
    .not.toEqual(expect.arrayContaining(ids));
  for (const id of ids) expect((await selectionSnapshot(page, id, documentId)).selected).toBe(false);
}

const documentOverlayRoots = (page, documentIds) => page.evaluate(ids => ids.map(id => {
  const owner = window.ravenroot.workspace.find(id);
  const roots = [...owner.cy.container().children]
    .filter(child => child.classList?.contains('graph-selection-overlay'));
  return {
    id,
    roots: roots.map(root => ({
      documentId: root.dataset.documentId,
      boxes: [...root.querySelectorAll(':scope > .graph-selection-box')].map(box => box.dataset.nodeId).sort(),
      handles: [...root.querySelectorAll('.graph-selection-handle')].length,
      hidden: root.hidden,
    })),
  };
}), documentIds);

async function expectOneDocumentOverlay(page, documentId, nodeIds) {
  await expect.poll(() => documentOverlayRoots(page, [documentId])).toEqual([{
    id: documentId,
    roots: [{ documentId, boxes: [...nodeIds].sort(), handles: nodeIds.length * 4, hidden: nodeIds.length === 0 }],
  }]);
}

for (const renderer of [{ name: 'Design', selector: '#btn-design' }]) {
  test(`${renderer.name} renders external, equal selection boxes for pointer, box and keyboard mode paths`, async ({ page }) => {
    await open(page);
    await selectDesign(page);
    await pinSelectionFixture(page);
    const nativeBefore = (await selectionSnapshot(page, 'start')).native;

    await page.evaluate(() => {
      window.cy.getElementById('start').select();
      window.cy.getElementById('dosomething').select();
    });
    await expectSelectionBox(page, 'start');
    await expectSelectionBox(page, 'dosomething');
    expect((await selectionSnapshot(page, 'start')).native).toEqual(nativeBefore);

    await page.evaluate(() => { window.cy.getElementById('start').unselect(); });
    await expectNoSelectionBox(page, ['start']);
    await expectSelectionBox(page, 'dosomething');
    await page.evaluate(() => { window.cy.$(':selected').unselect(); });
    await expectNoSelectionBox(page, ['start', 'dosomething']);

    const start = await nodePoint(page, 'start');
    const end = await nodePoint(page, 'end');
    await page.mouse.click(start.x, start.y);
    await page.keyboard.down('Control');
    await page.mouse.click(end.x, end.y);
    await page.keyboard.up('Control');
    await expect.poll(() => selectedNodeIds(page)).toEqual(['end', 'start']);
    await expectSelectionBox(page, 'start');
    await expectSelectionBox(page, 'end');
    const debounce = await page.evaluate(() => window.cy.multiClickDebounceTime());
    await page.waitForTimeout(debounce + 20);
    await page.evaluate(() => { window.cy.$(':selected').unselect(); });

    const bounds = await page.evaluate(() => {
      const nodes = window.cy.collection([window.cy.getElementById('start'), window.cy.getElementById('dosomething')])
        .renderedBoundingBox({ includeLabels: false, includeOutlines: false });
      const canvas = window.cy.container().getBoundingClientRect();
      return {
        from: { x: canvas.left + nodes.x1 - 25, y: canvas.top + nodes.y1 - 25 },
        to: { x: canvas.left + nodes.x2 + 25, y: canvas.top + nodes.y2 + 25 },
      };
    });
    await page.mouse.move(bounds.from.x, bounds.from.y);
    await page.mouse.down();
    await page.mouse.move(bounds.to.x, bounds.to.y, { steps: 12 });
    await page.mouse.up();
    await expect.poll(() => selectedNodeIds(page)).toEqual(['dosomething', 'start']);
    await expectSelectionBox(page, 'start');
    await expectSelectionBox(page, 'dosomething');

    // These keyboard-triggered renderer and mode changes must neither replace the native state nor
    // turn the visual-only overlay into an interactive graph control.
    await page.locator('#btn-modify').click();
    await expectSelectionBox(page, 'start');
    await page.locator('#btn-navigation').click();
    await expectSelectionBox(page, 'dosomething');
    await selectDesign(page);
    await expectSelectionBox(page, 'start');
  });
}

test('Design keeps the external selection synchronized through dense incident edges, a self-loop, pan, zoom and node movement', async ({ page }) => {
  await open(page);
  await selectDesign(page);
  await pinSelectionFixture(page);
  await page.evaluate(() => {
    window.cy.add([
      { group: 'edges', data: { id: 'selection-self-loop', source: 'start', target: 'start', label: 'loop' } },
      { group: 'edges', data: { id: 'selection-incident-1', source: 'dosomething', target: 'start', label: 'in' } },
      { group: 'edges', data: { id: 'selection-incident-2', source: 'error', target: 'start', label: 'in' } },
    ]);
    window.cy.getElementById('start').select();
  });
  const before = await selectionSnapshot(page, 'start');
  await expectSelectionBox(page, 'start');
  expect((await selectionSnapshot(page, 'start')).native).toEqual(before.native);

  await page.evaluate(() => {
    window.cy.panBy({ x: 33, y: -21 });
    window.cy.zoom({ level: window.cy.zoom() * 1.08, renderedPosition: { x: 360, y: 220 } });
    window.cy.getElementById('start').position({ x: 180, y: 205 });
  });
  await expectSelectionBox(page, 'start');
  await expect.poll(() => page.evaluate(() => ({
    loop: window.cy.getElementById('selection-self-loop').nonempty(),
    incident: window.cy.getElementById('selection-incident-2').nonempty(),
    position: window.cy.getElementById('start').position(),
  }))).toMatchObject({ loop: true, incident: true, position: { x: 180, y: 205 } });

  await page.evaluate(() => { window.cy.elements().unselect(); });
  await expectNoSelectionBox(page, ['start']);
});

test('Monitoring restores exactly one current Design overlay without crossing document ownership', async ({ page }) => {
  await open(page);
  await selectDesign(page);
  const primary = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await page.evaluate(() => { window.cy.getElementById('start').select(); });
  await expectSelectionBox(page, 'start', primary);
  await expectOneDocumentOverlay(page, primary, ['start']);

  const sibling = await page.evaluate(() => window.ravenroot.openDocument({ name: 'selection-sibling.graphml' }));
  await selectDesign(page);
  await page.evaluate(() => { window.cy.getElementById('end').select(); });
  await expectSelectionBox(page, 'end', sibling);
  await expectOneDocumentOverlay(page, sibling, ['end']);
  await page.evaluate(id => window.ravenroot.activateDocument(id), primary);

  for (let cycle = 0; cycle < 3; cycle += 1) {
    await page.locator('#btn-monitoring').click();
    await expect.poll(() => page.evaluate(documentId => {
      const owner = window.ravenroot.workspace.find(documentId);
      return owner?.renderer?.kind;
    }, primary)).toBe('elastic');
    // The retiring Cytoscape renderer removes its root. Its selected model node must gain one
    // fresh layer on restore, never a duplicate listener/root retained from an earlier cycle.
    await expect.poll(() => documentOverlayRoots(page, [primary])).toEqual([{ id: primary, roots: [] }]);

    await selectDesign(page);
    await expect.poll(() => page.evaluate(documentId => {
      const owner = window.ravenroot.workspace.find(documentId);
      return { kind: owner?.renderer?.kind, style: owner?.visualStyle, layout: owner?.layoutMode };
    }, primary)).toMatchObject({ kind: 'cytoscape', style: 'cyto', layout: 'cyto' });
    await page.evaluate(() => { window.cy.getElementById('start').select(); });
    await expectSelectionBox(page, 'start', primary);
    await expectOneDocumentOverlay(page, primary, ['start']);
    await expectOneDocumentOverlay(page, sibling, ['end']);
  }

  await page.evaluate(() => { window.cy.elements().unselect(); });
  await expectNoSelectionBox(page, ['start'], primary);
  await expectOneDocumentOverlay(page, primary, []);
  await expectOneDocumentOverlay(page, sibling, ['end']);

  await page.evaluate(id => window.ravenroot.activateDocument(id), sibling);
  await page.evaluate(() => { window.cy.getElementById('end').select(); });
  await expectSelectionBox(page, 'end', sibling);
  await page.evaluate(() => { window.cy.elements().unselect(); });
  await expectNoSelectionBox(page, ['end'], sibling);
  await expectOneDocumentOverlay(page, sibling, []);
  await expectOneDocumentOverlay(page, primary, []);
});

test('selection overlays remain document-owned across Design documents and full deselection', async ({ page }) => {
  await open(page);
  await selectDesign(page);
  const cytoDocument = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await page.evaluate(() => {
    window.cy.getElementById('start').select();
    window.cy.getElementById('dosomething').select();
  });
  await expectSelectionBox(page, 'start', cytoDocument);
  await expectSelectionBox(page, 'dosomething', cytoDocument);

  const designDocument = await page.evaluate(() => window.ravenroot.openDocument({ name: 'design.graphml' }));
  await selectDesign(page);
  await page.evaluate(() => { window.cy.getElementById('end').select(); });
  await expectSelectionBox(page, 'end', designDocument);

  await page.evaluate(id => window.ravenroot.activateDocument(id), cytoDocument);
  await expect.poll(() => selectedNodeIds(page)).toEqual([]);
  await expectNoSelectionBox(page, ['start', 'dosomething'], cytoDocument);
  await page.evaluate(id => window.ravenroot.activateDocument(id), designDocument);
  await expect.poll(() => selectedNodeIds(page)).toEqual([]);
  await expectNoSelectionBox(page, ['end'], designDocument);
});
