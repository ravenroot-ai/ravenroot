import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

const CATALOG = JSON.stringify([{
  behavior: 'template', displayName: 'Template', category: 'Core', origin: 'CORE',
  description: 'Renders a template', visualType: 'flow', agentic: false,
  properties: [
    { name: 'template', type: 'string', defaultValue: 'Hello' },
    { name: 'retries', type: 'integer', defaultValue: '3' },
    { name: 'enabled', type: 'boolean', defaultValue: 'true' },
  ],
}]);

// The document a fresh page load carries is start -> dosomething -> {end, error}, not just
// start -> end (`createWorkflowDocument`). start/end are still pinned to a known, fixed layout here
// -- most of this file's tests read that layout back to compute drag deltas and click targets, so
// it has to stay a controlled baseline, not a moving target. dosomething/error are deliberately left
// wherever the initial layout put them: no test here needs them anywhere specific, and the two tests
// that need a guaranteed-empty click point find one dynamically (`blankModelPoint`) instead of
// assuming a literal is clear of whatever the template happens to contain.
async function open(page, { catalog = false } = {}) {
  if (catalog) {
    await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: CATALOG }));
    await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
  }
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => window.cy?.nodes().length)).toBe(4);
  await page.waitForFunction(() => window.cy && !window.cy.scratch('_rrLayoutRunning'));
  await page.evaluate(() => {
    window.cy.stop(true);
    window.cy.getElementById('start').position({ x: 150, y: 180 });
    window.cy.getElementById('end').position({ x: 500, y: 180 });
    // Fit to exactly {start, end}, not every element -- fitting to the whole document would
    // let dosomething/error's position (wherever the initial layout put them) change the resulting
    // zoom level, which several tests' pixel-space click/drop math is sensitive to at sub-pixel
    // precision. Scoping the fit keeps that zoom identical to what it was before the template grew.
    window.cy.fit(window.cy.collection([
      window.cy.getElementById('start'), window.cy.getElementById('end'),
    ]), 100);
  });
  await page.waitForTimeout(100);
}

async function canvasPoint(page, modelPosition) {
  const box = await page.locator('.doc-canvas').boundingBox();
  const rendered = await page.evaluate(position => ({
    x: position.x * window.cy.zoom() + window.cy.pan().x,
    y: position.y * window.cy.zoom() + window.cy.pan().y,
  }), modelPosition);
  return { x: box.x + rendered.x, y: box.y + rendered.y };
}

// A click that only needs to land on EMPTY canvas -- not test geometry itself -- must find
// one derived from the live document, not a literal that happened to be clear of whatever the
// template contained on the day it was written. Scans a grid in MODEL space (rather than rendered/
// page space, like edge-authoring.spec.js's analogous blankCanvasPoint) because callers here
// (canvasPoint, dropCatalog) both take model coordinates. Picks whichever clear candidate is
// closest to `preferred`, so the click still lands near where the test originally wanted it.
async function blankModelPoint(page, preferred) {
  return page.evaluate(pref => {
    const container = window.cy.container().getBoundingClientRect();
    const zoom = window.cy.zoom();
    const pan = window.cy.pan();
    // The search has to stay inside what the CURRENT viewport actually shows -- a model point can
    // be clear of every node and still fall outside the visible canvas (off the bottom or side),
    // in which case the resulting page click lands on whatever DOM sits behind the canvas instead
    // (here: a click that opened the Inspector narrowed the canvas, which this recomputes for).
    const edgeMargin = 20;
    const minX = (edgeMargin - pan.x) / zoom;
    const maxX = (container.width - edgeMargin - pan.x) / zoom;
    const minY = (edgeMargin - pan.y) / zoom;
    const maxY = (container.height - edgeMargin - pan.y) / zoom;
    const boxes = window.cy.nodes().map(node => {
      const position = node.position();
      const halfWidth = node.width() / 2 + 30;
      const halfHeight = node.height() / 2 + 30;
      return {
        x1: position.x - halfWidth, x2: position.x + halfWidth,
        y1: position.y - halfHeight, y2: position.y + halfHeight,
      };
    });
    const candidates = [];
    const step = Math.max(10, (maxX - minX) / 24);
    for (let y = minY; y <= maxY; y += step) {
      for (let x = minX; x <= maxX; x += step) {
        const clear = boxes.every(box => x < box.x1 || x > box.x2 || y < box.y1 || y > box.y2);
        if (clear) candidates.push({ x, y });
      }
    }
    candidates.sort((left, right) =>
      Math.hypot(left.x - pref.x, left.y - pref.y) - Math.hypot(right.x - pref.x, right.y - pref.y));
    if (!candidates.length) throw new Error('No blank model point found within the visible canvas');
    return candidates[0];
  }, preferred);
}

async function nodePoint(page, id) {
  return page.evaluate(nodeId => {
    const box = window.cy.container().getBoundingClientRect();
    const rendered = window.cy.getElementById(nodeId).renderedPosition();
    return { x: box.left + rendered.x, y: box.top + rendered.y };
  }, id);
}

async function dropCatalog(page, { position = { x: 0, y: 0 }, targetId = null } = {}) {
  return page.evaluate(({ position, targetId }) => {
    const item = document.querySelector('[data-catalog-add="template"]');
    const container = window.cy.container();
    const rendered = targetId
      ? window.cy.getElementById(targetId).renderedPosition()
      : { x: position.x * window.cy.zoom() + window.cy.pan().x, y: position.y * window.cy.zoom() + window.cy.pan().y };
    const bounds = container.getBoundingClientRect();
    const dataTransfer = new DataTransfer();
    item.dispatchEvent(new DragEvent('dragstart', { bubbles: true, dataTransfer }));
    document.getElementById('cy-wrap').dispatchEvent(new DragEvent('drop', {
      bubbles: true, cancelable: true, dataTransfer,
      clientX: bounds.left + rendered.x, clientY: bounds.top + rendered.y,
    }));
  }, { position, targetId });
}

test('selection is stable, replaceable and additive with Ctrl/Cmd-click', async ({ page }) => {
  await open(page);
  const start = await nodePoint(page, 'start');
  const end = await nodePoint(page, 'end');

  await page.mouse.click(start.x, start.y);
  await expect.poll(() => page.evaluate(() => window.cy.$(':selected').map(item => item.id()))).toEqual(['start']);
  await page.mouse.click(start.x, start.y);
  await expect.poll(() => page.evaluate(() => window.cy.$(':selected').map(item => item.id()))).toEqual(['start']);
  await page.mouse.click(end.x, end.y);
  await expect.poll(() => page.evaluate(() => window.cy.$(':selected').map(item => item.id()))).toEqual(['end']);
  await page.keyboard.down('Control');
  await page.mouse.click(start.x, start.y);
  await page.keyboard.up('Control');
  await expect.poll(() => page.evaluate(() => window.cy.nodes(':selected').map(item => item.id()).sort()))
    .toEqual(['end', 'start']);
});

test('empty-stage drag box-selects nodes and their edge; Navigation and H alone pan', async ({ page }) => {
  await open(page);
  expect(await page.evaluate(() => ({
    box: window.cy.boxSelectionEnabled(), pan: window.cy.userPanningEnabled(),
  }))).toEqual({ box: true, pan: false });

  // Start's edge now goes to dosomething, not directly to end -- so a box selection around
  // "a node, its neighbor and the edge between them" targets {start, dosomething,
  // edge-start-dosomething}, not {start, end}. Moved next to start (open() otherwise leaves it
  // wherever the initial layout put it, since no other test here cares) so the drag box stays a
  // reasonably sized rectangle. `end` and `error` -- irrelevant to this test -- are pushed well
  // clear too: dosomething's other two edges start at the same point as the one under test, so
  // leaving their far ends anywhere near the selection rectangle (end already sits exactly where
  // dosomething is being moved to) lets Cytoscape's box selection sweep them in as well. The
  // selection bounds are still computed from exactly the intended trio, not "every element", so
  // this is belt and braces rather than the only thing keeping the selection narrow.
  await page.evaluate(() => {
    window.cy.stop();
    window.cy.getElementById('dosomething').position({ x: 500, y: 180 });
    window.cy.getElementById('end').position({ x: -1200, y: -900 });
    window.cy.getElementById('error').position({ x: -1500, y: -900 });
    // Fit to exactly {start, dosomething}, not every element: `end` and `error` were just pushed
    // far away specifically to stay out of the selection, and fitting to the whole document would
    // zoom out to include them, shrinking the trio this test actually drags a box around.
    window.cy.fit(window.cy.collection([
      window.cy.getElementById('start'), window.cy.getElementById('dosomething'),
    ]), 100);
  });
  const selectionBounds = await page.evaluate(() => {
    const box = window.cy.collection([
      window.cy.getElementById('start'),
      window.cy.getElementById('dosomething'),
      window.cy.getElementById('edge-start-dosomething'),
    ]).renderedBoundingBox();
    const canvas = window.cy.container().getBoundingClientRect();
    return {
      topLeft: { x: canvas.left + box.x1 - 30, y: canvas.top + box.y1 - 30 },
      bottomRight: { x: canvas.left + box.x2 + 30, y: canvas.top + box.y2 + 30 },
    };
  });
  const { topLeft, bottomRight } = selectionBounds;
  await page.mouse.move(topLeft.x, topLeft.y);
  await page.mouse.down();
  await page.mouse.move(bottomRight.x, bottomRight.y, { steps: 12 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.cy.$(':selected').map(item => item.id()).sort()))
    .toEqual(['dosomething', 'edge-start-dosomething', 'start']);

  await page.locator('#btn-navigation').click();
  await expect(page.locator('#btn-navigation')).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => ({
    box: window.cy.boxSelectionEnabled(), pan: window.cy.userPanningEnabled(),
  }))).toEqual({ box: false, pan: true });
  await expect(page.locator('#cy')).toHaveCSS('cursor', 'grab');
  const before = await page.evaluate(() => window.cy.pan());
  await page.mouse.move(topLeft.x, topLeft.y);
  await page.mouse.down();
  await page.mouse.move(topLeft.x + 90, topLeft.y + 50, { steps: 10 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.cy.pan())).not.toEqual(before);

  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('h');
  await expect(page.locator('#btn-navigation')).toHaveAttribute('aria-pressed', 'false');
  expect(await page.evaluate(() => window.cy.userPanningEnabled())).toBe(false);
});

test('Viewer node drag persists layout while Editing direct drag of an unselected node creates an edge', async ({ page }) => {
  await open(page);
  const start = await nodePoint(page, 'start');
  const before = await page.evaluate(() => window.cy.getElementById('start').position());
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.mouse.move(start.x, start.y + 90, { steps: 12 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('start').position().y))
    .toBeGreaterThan(before.y);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move start/);

  await page.locator('#btn-modify').click();
  await expect(page.locator('#graph-mode-label')).toHaveText('Editing');
  await page.evaluate(() => { window.cy.elements().unselect(); });
  const sourceBefore = await page.evaluate(() => window.cy.getElementById('start').position());
  const from = await nodePoint(page, 'start');
  const to = await nodePoint(page, 'end');
  const edgeCount = await page.evaluate(() => window.cy.edges().length);
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 14 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.cy.edges().length)).toBe(edgeCount + 1);
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('start').position())).toEqual(sourceBefore);
});

test('Editing click selects without authoring, then dragging that selected node moves it only', async ({ page }) => {
  await open(page);
  await page.locator('#btn-modify').click();
  const from = await nodePoint(page, 'start');
  const sourceBefore = await page.evaluate(() => window.cy.getElementById('start').position());

  // The base document now carries 3 edges (start->dosomething, dosomething->end,
  // dosomething->error), not 1 -- captured dynamically so this test's actual concern (a click
  // authors no edge, a drag authors exactly one more) doesn't re-encode the template's edge count.
  const edgeCountBeforeClick = await page.evaluate(() => window.cy.edges().length);
  await page.mouse.click(from.x, from.y);
  await expect.poll(() => page.evaluate(() => window.cy.edges().length)).toBe(edgeCountBeforeClick);
  await expect.poll(() => page.evaluate(() => window.cy.nodes(':selected').map(node => node.id())))
    .toEqual(['start']);
  await expect(page.locator('#graph-live')).not.toContainText('Cannot connect');

  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(from.x + 90, from.y + 60, { steps: 14 });
  await page.mouse.up();

  await expect.poll(() => page.evaluate(() => window.cy.edges().length)).toBe(edgeCountBeforeClick);
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('start').position()))
    .not.toEqual(sourceBefore);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move start/);
});

for (const renderer of [{ name: 'Design', selector: '#btn-design' }]) {
  test(`${renderer.name} Editing arbitrates selected movement, group history and unselected connection`, async ({ page }) => {
    await open(page);
    await page.locator(renderer.selector).click();
    await page.locator('#btn-modify').click();
    await page.evaluate(() => {
      window.cy.stop(true);
      const positions = {
        start: { x: 120, y: 120 },
        dosomething: { x: 390, y: 120 },
        error: { x: 390, y: 330 },
        end: { x: 680, y: 225 },
      };
      Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
      window.cy.fit(undefined, 90);
    });

    const snapshot = () => page.evaluate(() => ({
      positions: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])),
      selected: window.cy.nodes(':selected').map(node => node.id()).sort(),
      edges: window.cy.edges().length,
      depth: window.ravenroot.activeDocument().history.depth(),
    }));

    // Click A, then drag A: selection is the move affordance and never an edge source.
    let point = await nodePoint(page, 'start');
    await page.mouse.click(point.x, point.y);
    await expect.poll(async () => (await snapshot()).selected).toEqual(['start']);
    const beforeSingle = await snapshot();
    await page.mouse.move(point.x, point.y);
    await page.mouse.down();
    await page.mouse.move(point.x + 70, point.y + 45, { steps: 12 });
    await page.mouse.up();
    const afterSingle = await snapshot();
    expect(afterSingle.positions.start).not.toEqual(beforeSingle.positions.start);
    for (const id of ['dosomething', 'error', 'end']) {
      expect(afterSingle.positions[id]).toEqual(beforeSingle.positions[id]);
    }
    expect(afterSingle.edges).toBe(beforeSingle.edges);
    expect(afterSingle.depth).toBe(beforeSingle.depth + 1);
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move start/);

    await page.locator('#btn-undo').click();
    await expect.poll(async () => (await snapshot()).positions).toEqual(beforeSingle.positions);
    await page.locator('#btn-redo').click();
    await expect.poll(async () => (await snapshot()).positions.start).toEqual(afterSingle.positions.start);

    // Click B transfers selection; Ctrl/Cmd-click A makes A+B one atomic move command.
    point = await nodePoint(page, 'dosomething');
    await page.mouse.click(point.x, point.y);
    await expect.poll(async () => (await snapshot()).selected).toEqual(['dosomething']);
    const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
    const startPoint = await nodePoint(page, 'start');
    await page.keyboard.down(modifier);
    await page.mouse.click(startPoint.x, startPoint.y);
    await page.keyboard.up(modifier);
    await expect.poll(async () => (await snapshot()).selected).toEqual(['dosomething', 'start']);

    const beforeGroup = await snapshot();
    point = await nodePoint(page, 'dosomething');
    await page.mouse.move(point.x, point.y);
    await page.mouse.down();
    await page.mouse.move(point.x + 80, point.y + 35, { steps: 12 });
    await page.mouse.up();
    const afterGroup = await snapshot();
    expect(afterGroup.positions.start).not.toEqual(beforeGroup.positions.start);
    expect(afterGroup.positions.dosomething).not.toEqual(beforeGroup.positions.dosomething);
    expect(afterGroup.positions.error).toEqual(beforeGroup.positions.error);
    expect(afterGroup.positions.end).toEqual(beforeGroup.positions.end);
    expect(afterGroup.edges).toBe(beforeGroup.edges);
    expect(afterGroup.depth).toBe(beforeGroup.depth + 1);
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move 2 nodes/);
    await page.locator('#btn-undo').click();
    await expect.poll(async () => (await snapshot()).positions).toEqual(beforeGroup.positions);
    await page.locator('#btn-redo').click();
    await expect.poll(async () => (await snapshot()).positions.start).toEqual(afterGroup.positions.start);

    // Escape while moving restores both selected nodes and writes no command.
    const beforeEscape = await snapshot();
    point = await nodePoint(page, 'start');
    await page.mouse.move(point.x, point.y);
    await page.mouse.down();
    await page.mouse.move(point.x + 55, point.y + 30, { steps: 8 });
    await page.keyboard.press('Escape');
    await page.mouse.up();
    await expect.poll(async () => (await snapshot()).positions).toEqual(beforeEscape.positions);
    expect((await snapshot()).depth).toBe(beforeEscape.depth);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('.edge-ghost.on')).toHaveCount(0);

    // C remains unselected and therefore connectable by direct drag.
    const beforeConnect = await snapshot();
    const from = await nodePoint(page, 'error');
    const to = await nodePoint(page, 'end');
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    await page.mouse.move(to.x, to.y, { steps: 14 });
    await page.mouse.up();
    await expect.poll(async () => (await snapshot()).edges).toBe(beforeConnect.edges + 1);
    const afterConnect = await snapshot();
    expect(afterConnect.positions.error).toEqual(beforeConnect.positions.error);
    expect(afterConnect.depth).toBe(beforeConnect.depth + 1);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('.edge-ghost.on')).toHaveCount(0);
  });
}

for (const renderer of [{ name: 'Design', selector: '#btn-design' }]) {
  test(`${renderer.name} plain-click transfer is authoritative for the immediate next drag`, async ({ page }) => {
    await open(page);
    await page.locator(renderer.selector).click();
    await page.locator('#btn-modify').click();
    await page.evaluate(() => {
      window.cy.stop(true);
      const positions = {
        start: { x: 120, y: 120 },
        dosomething: { x: 390, y: 120 },
        error: { x: 390, y: 330 },
        end: { x: 680, y: 120 },
      };
      Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
      window.cy.fit(undefined, 90);
    });
    const state = () => page.evaluate(() => ({
      positions: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])),
      selected: window.cy.nodes(':selected').map(node => node.id()).sort(),
      grabbable: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.grabbable()])),
      edges: window.cy.edges().length,
      depth: window.ravenroot.activeDocument().history.depth(),
    }));

    const a = await nodePoint(page, 'start');
    await page.mouse.click(a.x, a.y);
    await expect.poll(async () => (await state()).selected).toEqual(['start']);
    const before = await state();

    const b = await nodePoint(page, 'dosomething');
    await page.mouse.click(b.x, b.y);
    // Deliberately no poll, timeout or debounce wait between B's click and the next drag. The
    // synchronous snapshot proves the old A selection is already unavailable to `grab`.
    expect(await state()).toMatchObject({
      selected: ['dosomething'],
      grabbable: { start: false, dosomething: true },
    });
    await page.mouse.move(b.x, b.y);
    await page.mouse.down();
    await page.mouse.move(b.x + 75, b.y + 40, { steps: 10 });
    await page.mouse.up();

    const afterMove = await state();
    expect(afterMove.positions.start).toEqual(before.positions.start);
    expect(afterMove.positions.dosomething).not.toEqual(before.positions.dosomething);
    expect(afterMove.positions.error).toEqual(before.positions.error);
    expect(afterMove.positions.end).toEqual(before.positions.end);
    expect(afterMove).toMatchObject({ selected: ['dosomething'], edges: before.edges, depth: before.depth + 1 });
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move dosomething/);

    // A is already unselected and therefore immediately returns to the pointer edge-authoring
    // route. Its position remains fixed while the edge and exactly one history step are added.
    const source = await nodePoint(page, 'start');
    const target = await nodePoint(page, 'end');
    await page.mouse.move(source.x, source.y);
    await page.mouse.down();
    await page.mouse.move(target.x, target.y, { steps: 14 });
    await page.mouse.up();
    await expect.poll(async () => (await state()).edges).toBe(before.edges + 1);
    const afterEdge = await state();
    expect(afterEdge.positions.start).toEqual(before.positions.start);
    expect(afterEdge.positions.dosomething).toEqual(afterMove.positions.dosomething);
    expect(afterEdge).toMatchObject({ selected: ['dosomething'], depth: before.depth + 2 });

    const debounce = await page.evaluate(() => window.cy.multiClickDebounceTime());
    await page.waitForTimeout(debounce + 80);
    expect(await state()).toMatchObject({
      selected: ['dosomething'],
      grabbable: { start: false, dosomething: true },
      edges: before.edges + 1,
      depth: before.depth + 2,
    });
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('.edge-ghost.on')).toHaveCount(0);
  });

  test(`${renderer.name} keyboard E and armed Connect reject selected sources only`, async ({ page }) => {
    await open(page);
    await page.locator(renderer.selector).click();
    await page.locator('#btn-modify').click();
    await page.evaluate(() => {
      window.cy.stop(true);
      const positions = {
        start: { x: 120, y: 120 },
        dosomething: { x: 390, y: 120 },
        error: { x: 390, y: 330 },
        end: { x: 680, y: 120 },
      };
      Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
      window.cy.fit(undefined, 90);
    });
    const state = () => page.evaluate(() => ({
      selected: window.cy.nodes(':selected').map(node => node.id()).sort(),
      cursor: window.cy.nodes('.graph-cursor').map(node => node.id()),
      edges: window.cy.edges().length,
      depth: window.ravenroot.activeDocument().history.depth(),
    }));

    const start = await nodePoint(page, 'start');
    await page.mouse.click(start.x, start.y);
    await expect.poll(async () => (await state()).selected).toEqual(['start']);
    const before = await state();
    const canvas = page.locator('#cy-wrap');
    await canvas.focus();

    await canvas.press('e');
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('#graph-live')).toContainText(/selected.*unselected node/i);
    await expect(page.locator('.edge-ghost.on')).toHaveCount(0);
    expect(await state()).toMatchObject({ edges: before.edges, depth: before.depth });

    // Moving the keyboard cursor does not move selection, so E remains accessible from an
    // unselected source and Escape can cancel it without history or visual residue.
    await canvas.press('ArrowRight');
    await expect.poll(async () => (await state()).cursor).toEqual(['dosomething']);
    await canvas.press('e');
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-source', 'dosomething');
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'composing');
    await canvas.press('Escape');
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('.edge-ghost.on')).toHaveCount(0);
    expect(await state()).toMatchObject({ edges: before.edges, depth: before.depth, selected: ['start'] });

    // Armed Connect follows the same rule: the selected source is refused, while an unselected
    // source can open and commit through the established two-click route.
    await page.locator('#btn-connect').click();
    await page.mouse.click(start.x, start.y);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('#graph-live')).toContainText(/selected.*unselected node/i);
    await expect.poll(async () => (await state()).selected).toEqual(['start']);
    expect(await state()).toMatchObject({ edges: before.edges, depth: before.depth, selected: ['start'] });

    const source = await nodePoint(page, 'dosomething');
    const target = await nodePoint(page, 'end');
    await page.mouse.click(source.x, source.y);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-source', 'dosomething');
    await page.mouse.click(target.x, target.y);
    await expect.poll(async () => (await state()).edges).toBe(before.edges + 1);
    expect((await state()).depth).toBe(before.depth + 1);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    await expect(page.locator('.edge-ghost.on')).toHaveCount(0);
  });
}

test('Editing stage click creates a generic inspector-configurable node or the persistent clicked type', async ({ page }) => {
  await open(page, { catalog: true });
  await expect(page.locator('[data-catalog-add="template"]')).toBeVisible();
  await page.locator('#btn-modify').click();
  const nodeCountBefore = await page.evaluate(() => window.cy.nodes().length);
  // (330, 360) was picked when the document was only start/end; it is not a load-bearing
  // coordinate, just "somewhere empty" -- derived here so it can't silently land on dosomething or
  // error today, or on whatever the template adds next time.
  const empty = await canvasPoint(page, await blankModelPoint(page, { x: 330, y: 360 }));
  await page.mouse.click(empty.x, empty.y);
  await expect.poll(() => page.evaluate(() => window.cy.nodes().length)).toBe(nodeCountBefore + 1);
  await expect(page.locator('#node-editor input[name="name"]')).toHaveValue('New node');

  await page.locator('[data-catalog-add="template"]').click();
  await expect(page.locator('[data-catalog-add="template"]')).toHaveAttribute('aria-pressed', 'true');
  // The first click opened the Inspector panel, narrowing the canvas. cy's own zoom/pan is not
  // automatically recalculated against the new container size, so blankModelPoint's model-space
  // scan and canvasPoint's model-to-page conversion would otherwise agree with each other but
  // disagree with where the canvas actually is on screen now.
  // Block body, no implicit return -- every Cytoscape mutator returns the core or the
  // collection, and Playwright would copy that whole cyclic object graph back. See
  // `e2e/edge-authoring.spec.js:fitPinnedGraph` for the measurement and
  // `test/e2e-evaluate-return.test.js` for the guard.
  await page.evaluate(() => { window.cy.resize(); });
  const second = await canvasPoint(page, await blankModelPoint(page, { x: 430, y: 360 }));
  await page.mouse.click(second.x, second.y);
  await expect.poll(() => page.evaluate(() => window.cy.nodes().length)).toBe(nodeCountBefore + 2);
  await expect.poll(() => page.evaluate(() => window.cy.nodes().filter('[behavior="template"]').length)).toBe(1);
  await expect(page.locator('[data-catalog-add="template"]')).toHaveAttribute('aria-pressed', 'true');
});

for (const renderer of [{ name: 'Design', selector: '#btn-design' }]) {
  test(`${renderer.name} Editing stage clearing precedes insertion and restores the selected source`, async ({ page }) => {
    await open(page);
    await page.locator(renderer.selector).click();
    await page.locator('#btn-modify').click();

    const start = await nodePoint(page, 'start');
    await page.mouse.click(start.x, start.y);
    await expect.poll(() => page.evaluate(() => window.cy.$(':selected').map(item => item.id())))
      .toEqual(['start']);
    const before = await page.evaluate(() => ({
      nodes: window.cy.nodes().length,
      edges: window.cy.edges().length,
      history: window.ravenroot.activeDocument().history.depth(),
      position: window.cy.getElementById('start').position(),
    }));

    const clearPoint = await canvasPoint(page, await blankModelPoint(page, { x: 320, y: 360 }));
    await page.mouse.click(clearPoint.x, clearPoint.y);
    await expect.poll(() => page.evaluate(() => window.cy.$(':selected').length)).toBe(0);
    expect(await page.evaluate(() => ({
      nodes: window.cy.nodes().length,
      history: window.ravenroot.activeDocument().history.depth(),
    }))).toEqual({ nodes: before.nodes, history: before.history });

    // Before the empty-stage clear, start is selected and moves; afterwards it is immediately a
    // direct-edge source. This pins the precedence without depending on Cytoscape listener order.
    const source = await nodePoint(page, 'start');
    const target = await nodePoint(page, 'end');
    await page.mouse.move(source.x, source.y);
    await page.mouse.down();
    await page.mouse.move(target.x, target.y, { steps: 12 });
    await page.mouse.up();
    await expect.poll(() => page.evaluate(() => window.cy.edges().length)).toBe(before.edges + 1);
    expect(await page.evaluate(() => window.cy.getElementById('start').position())).toEqual(before.position);

    const insertModelPosition = await blankModelPoint(page, { x: 430, y: 350 });
    const insertPoint = await canvasPoint(page, insertModelPosition);
    const nodesBeforeInsert = await page.evaluate(() => window.cy.nodes().length);
    await page.mouse.click(insertPoint.x, insertPoint.y);
    await expect.poll(() => page.evaluate(() => window.cy.nodes().length)).toBe(nodesBeforeInsert + 1);
    const insertedPosition = await page.evaluate(() => window.cy.nodes().last().position());
    expect(Math.abs(insertedPosition.x - insertModelPosition.x)).toBeLessThan(2);
    expect(Math.abs(insertedPosition.y - insertModelPosition.y)).toBeLessThan(2);
  });
}

test('toolbox DnD creates on empty stage and creates plus connects when dropped on a node', async ({ page }) => {
  await open(page, { catalog: true });
  await page.locator('#btn-modify').click();
  await expect(page.locator('[data-catalog-add="template"]')).toBeVisible();

  // (320, 350) is likewise just "somewhere empty", derived for the same reason as above.
  await dropCatalog(page, { position: await blankModelPoint(page, { x: 320, y: 350 }) });
  await expect.poll(() => page.evaluate(() => window.cy.nodes().filter('[behavior="template"]').length)).toBe(1);
  const edgesBefore = await page.evaluate(() => window.cy.edges().length);
  await dropCatalog(page, { targetId: 'start' });
  await expect.poll(() => page.evaluate(() => window.cy.nodes().filter('[behavior="template"]').length)).toBe(2);
  await expect.poll(() => page.evaluate(() => window.cy.edges().length)).toBe(edgesBefore + 1);
  await expect.poll(() => page.evaluate(() => window.cy.edges().some(edge =>
    edge.data('source') === 'start' && String(edge.data('target')).startsWith('template-')))).toBe(true);
});

test('toolbox source drop is atomic across undo, redo and GraphML round-trip', async ({ page }) => {
  await open(page, { catalog: true });
  await page.locator('#btn-modify').click();
  const sourcePosition = await page.evaluate(() => window.cy.getElementById('start').position());
  // Captured dynamically rather than re-encoding the template's base counts (4 nodes, 3
  // edges) -- this test's concern is "one drop adds exactly one node and one edge", not the
  // template's shape.
  const baseCounts = await page.evaluate(() => ({ nodes: window.cy.nodes().length, edges: window.cy.edges().length }));
  const droppedCounts = { nodes: baseCounts.nodes + 1, edges: baseCounts.edges + 1 };

  await dropCatalog(page, { targetId: 'start' });
  await expect.poll(() => page.evaluate(() => ({ nodes: window.cy.nodes().length, edges: window.cy.edges().length })))
    .toEqual(droppedCounts);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Add node .* and connect start/);

  await page.locator('#btn-undo').click();
  await expect.poll(() => page.evaluate(() => ({ nodes: window.cy.nodes().length, edges: window.cy.edges().length })))
    .toEqual(baseCounts);
  await page.locator('#btn-redo').click();
  await expect.poll(() => page.evaluate(() => ({ nodes: window.cy.nodes().length, edges: window.cy.edges().length })))
    .toEqual(droppedCounts);
  const authoredPosition = await page.evaluate(() => window.cy.nodes('[behavior="template"]').first().position());

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#btn-export').click(),
  ]);
  const xml = await readFile(await download.path(), 'utf8');
  const roundTrip = await page.evaluate(source => {
    const document_ = new DOMParser().parseFromString(source, 'application/xml');
    const keys = Object.fromEntries(Array.from(document_.querySelectorAll('key')).map(key => [
      key.getAttribute('id'),
      { name: key.getAttribute('attr.name'), type: key.getAttribute('attr.type') || 'string' },
    ]));
    const values = element => Object.fromEntries(Array.from(element.querySelectorAll(':scope > data')).map(data => [
      keys[data.getAttribute('key')]?.name,
      { value: data.textContent, type: keys[data.getAttribute('key')]?.type },
    ]));
    const node = Array.from(document_.querySelectorAll('node'))
      .map(element => ({ id: element.id, values: values(element) }))
      .find(candidate => candidate.values.behavior?.value === 'template');
    const edgeElement = Array.from(document_.querySelectorAll('edge'))
      .find(element => element.getAttribute('source') === 'start' && element.getAttribute('target') === node?.id);
    return {
      node,
      edge: edgeElement && {
        id: edgeElement.id,
        source: edgeElement.getAttribute('source'),
        target: edgeElement.getAttribute('target'),
      },
    };
  }, xml);
  expect(roundTrip.node.values).toMatchObject({
    behavior: { value: 'template', type: 'string' },
    layoutX: { type: 'double' },
    layoutY: { type: 'double' },
    template: { value: 'Hello', type: 'string' },
    retries: { value: '3', type: 'long' },
    enabled: { value: 'true', type: 'boolean' },
  });
  expect(Number(roundTrip.node.values.layoutX.value)).toBeCloseTo(authoredPosition.x, 6);
  expect(Number(roundTrip.node.values.layoutY.value)).toBeCloseTo(authoredPosition.y, 6);
  expect(authoredPosition.x).toBeCloseTo(sourcePosition.x, 0);
  expect(authoredPosition.y).toBeCloseTo(sourcePosition.y, 0);
  expect(roundTrip.edge).toMatchObject({ source: 'start', target: roundTrip.node.id });
});

test('toolbox drop on END announces refusal and mutates neither nodes nor edges', async ({ page }) => {
  await open(page, { catalog: true });
  await page.locator('#btn-modify').click();
  const baseCounts = await page.evaluate(() => ({ nodes: window.cy.nodes().length, edges: window.cy.edges().length }));

  await dropCatalog(page, { targetId: 'end' });

  await expect.poll(() => page.evaluate(() => ({ nodes: window.cy.nodes().length, edges: window.cy.edges().length })))
    .toEqual(baseCounts);
  await expect(page.locator('#graph-live')).toContainText(/Cannot add and connect node:.*END node.*no outgoing edge/);
  await expect(page.locator('#btn-undo')).toBeDisabled();
});
