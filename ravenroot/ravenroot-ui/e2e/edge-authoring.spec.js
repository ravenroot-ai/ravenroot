import { expect, test } from '@playwright/test';

// Browser coverage for UI-02: creating and reconnecting edges with the POINTER and with the
// KEYBOARD, against the real Cytoscape renderer. The two halves assert the same outcomes through
// different input, because accessible edge authoring cannot be a mouse
// feature with a keyboard afterthought.

async function startEditableWorkflow(page) {
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

// A third node to aim at. The starting document is no longer just start ──> end -- it is
// start -> dosomething -> {end, error} -- so `pinNodesApart` below also has to account for
// dosomething and error, not just the three nodes these tests are actually about.
async function addReviewNode(page) {
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Review');
  await page.locator('#node-editor button[type="submit"]').click();
  await expect.poll(() => nodeIds(page)).toContain('node-1');
  await pinNodesApart(page);
  // Creating a node leaves it selected so the author can continue editing. Pointer-authoring tests
  // need the node body, not the selected-node action minibar that deliberately owns pointer input.
  await page.evaluate(() => { window.cy.$(':selected').unselect(); });
  await expect.poll(() => page.evaluate(() => window.cy.$(':selected').length)).toBe(0);
}

// A freshly added node lands wherever the running layout puts it, which can be a few pixels from
// another node and makes a pointer drag land on whichever one happens to be on top. These tests are
// about edge gestures, not about layout, so the three nodes are pinned to known, well-separated
// coordinates and the assertions become deterministic. Left to right: start, node-1, end — so
// "one step right" from start is node-1 for the keyboard tests too.
// Any node NOT explicitly pinned by these helpers (today: the template's own `dosomething`
// and `error`) is pushed well clear of the pinned set, in a direction none of the pinned nodes ever
// occupy. Two things depend on this: `moveGraphCursor`'s directional keyboard scan
// (`src/app.js:moveGraphCursor`) picks whichever node is nearest in the pressed direction, and
// `blankCanvasPoint` avoids every node's rendered bounding box -- an unpinned node sitting near the
// pinned trio could win a directional scan it has no business winning, or eat into the "empty
// canvas" area these tests need. Derived from whichever nodes ARE pinned rather than naming
// dosomething/error specifically, so the next node the template gains doesn't silently reopen this.
async function clearUnpinnedNodes(page, pinnedIds, anchor = { x: 0, y: 0 }) {
  await page.evaluate(({ pinnedIds: pinned, anchor: origin }) => {
    let index = 0;
    window.cy.nodes().forEach(node => {
      if (pinned.includes(node.id())) return;
      index += 1;
      node.position({ x: origin.x - 900 - (index * 220), y: origin.y - 900 });
    });
  }, { pinnedIds, anchor });
}

// The shared `cy.fit()` of the four sibling setup helpers, and the reason it is a function
// rather than a line repeated in each of them. It is NOT the file's only `cy.fit()` -- the
// reconnection test near the bottom calls it too, inside a block body alongside its own `stop()`
// and repositioning, which is the same rule applied without needing a helper.
//
// Every Cytoscape mutator returns the core (or a collection) so calls can be chained. A CONCISE
// arrow body therefore returns it too, and `page.evaluate(() => window.cy.fit(undefined, 80))`
// asks Playwright to copy the entire live Cytoscape instance back across the wire: every element,
// its style state, the renderer and its canvases, all of it cyclic. Measured on this document, five
// nodes, with `dist/` freshly built: the call itself costs 0-1ms in the page, the round trip costs
// 5.6-9.7s, and the renderer process eventually dies inside it -- which is the `page.evaluate:
// Target crashed` failure. A/B, same page, same instance:
// `() => { window.cy.fit(undefined, 80); }` = 1-5ms; `() => window.cy.fit(undefined, 80)` = 9434ms
// and then a crash; and `() => window.cy` on its own, calling nothing at all, = 6864ms. The cost is
// the RETURN VALUE, not `fit`, not the layout, and not the test that happens to be running.
//
// So the rule for this file, and the reason the guard in `test/e2e-evaluate-return.test.js` exists:
// an evaluated callback that mutates Cytoscape uses a BLOCK body and returns nothing. Anything it
// genuinely needs from the page comes back as a plain value it built itself.
async function fitPinnedGraph(page, pinnedIds) {
  await page.evaluate(ids => {
    const pinned = window.cy.collection(ids.map(id => window.cy.getElementById(id)));
    window.cy.fit(pinned, 80);
  }, pinnedIds);
}

async function waitForLayoutIdle(page) {
  await expect(page.locator('.doc-pane--layout-busy')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => Boolean(window.cy.scratch('_rrLayoutRunning'))))
    .toBe(false);
  await page.evaluate(() => new Promise(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function pinNodesApart(page) {
  await page.locator('#btn-design').click();
  // `pinNodesApart` alone among the four siblings contains this wait-then-poll. Its intended guard is
  // that `cy.stop()` interrupts a layout whose completion the fixed wait only guessed, preventing
  // multi-second `fit()` polls and eventual renderer crashes/timeouts.
  //
  // INSTRUMENTATION SHOWS THAT IS NOT WHAT HAPPENS. Recording `_rrLayoutRunning`, the
  // layoutstart/layoutstop counts and the elapsed idle time at every `stop()` and every `fit()`:
  // pressing '1' starts the dagre layout SYNCHRONOUSLY (running true, one layoutstart, before the
  // keypress call even returns), the layout has genuinely ended 160-330ms before the poll clears,
  // and `cy.stop()` costs 1-9ms with starts == stops -- it interrupts nothing, on any run,
  // including the runs that crash. The poll reads `false` because the layout FINISHED, not because
  // it had not started. The crash was `fit()`'s return value all along -- see `fitPinnedGraph`.
  //
  // The wait and the poll are kept because here they do guard a real layout. In the three
  // visual-style siblings below they guard nothing: '0'/'5'/'6'/'7'/'9' go through
  // `setVisualStyle`, which paints synchronously and never runs a layout, so no layoutstart is ever
  // emitted across those keypresses. Left in place rather than removed, because removing them is a
  // timing change whose consequences are not measured here.
  await waitForLayoutIdle(page);
  await page.evaluate(() => {
    window.cy.stop();
    window.cy.getElementById('start').position({ x: 0, y: 0 });
    // Off the start-to-end line so the edge can be grabbed without hitting this node, but still
    // the nearest node to the right of start, which is what the keyboard tests navigate to.
    window.cy.getElementById('node-1').position({ x: 300, y: 90 });
    window.cy.getElementById('end').position({ x: 600, y: 0 });
  });
  await clearUnpinnedNodes(page, ['start', 'node-1', 'end']);
  await fitPinnedGraph(page, ['start', 'node-1', 'end']);
  await expect.poll(async () => {
    const gap = await page.evaluate(() =>
      window.cy.getElementById('node-1').renderedPosition().x
        - window.cy.getElementById('start').renderedPosition().x);
    return Math.round(gap);
  }).toBeGreaterThan(60);
}

async function useCytoAndPinNodesApart(page) {
  await page.locator('#btn-design').click();
  // This comment used to say that switching layout schedules Cytoscape asynchronously, so the
  // scratch flag reads false until the run starts and polling it alone could pin the test just
  // before a layout overwrote the coordinates. Measurements show that '0' is a VISUAL STYLE, not a
  // layout. It reaches `setVisualStyle`, which paints synchronously inside one `cy.batch` and never
  // constructs a layout, so no `layoutstart` is emitted across this keypress at all -- the
  // instrumented counters do not move. There is no asynchronous run here to wait through and
  // nothing for the poll below to observe.
  //
  // Both are kept anyway, and only because removing them changes the timing of every test that
  // calls this helper, and that timing consequence has not been measured. They are dead weight with
  // a reason, not a guard: do not cite them as one.
  await waitForLayoutIdle(page);
  await page.evaluate(() => {
    window.cy.stop();
    window.cy.getElementById('start').position({ x: 0, y: 0 });
    window.cy.getElementById('node-1').position({ x: 300, y: 90 });
    window.cy.getElementById('end').position({ x: 600, y: 0 });
  });
  await clearUnpinnedNodes(page, ['start', 'node-1', 'end']);
  await fitPinnedGraph(page, ['start', 'node-1', 'end']);
  // Position notifications schedule Cyto edge geometry for the next frame. Wait until both that
  // frame and one paint frame have passed before measuring the invariant positions.
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect.poll(() => page.evaluate(() =>
    window.cy.getElementById('edge-start-dosomething').style('curve-style'))).toBe('unbundled-bezier');
}

async function useDesignAndPinNodesApart(page) {
  await page.locator('#btn-design').click();
  await waitForLayoutIdle(page);
  await page.evaluate(() => {
    window.cy.stop();
    window.cy.getElementById('start').position({ x: 0, y: 0 });
    window.cy.getElementById('node-1').position({ x: 300, y: 90 });
    window.cy.getElementById('end').position({ x: 600, y: 0 });
  });
  await clearUnpinnedNodes(page, ['start', 'node-1', 'end']);
  await fitPinnedGraph(page, ['start', 'node-1', 'end']);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
  await expect.poll(() => page.evaluate(() =>
    window.cy.getElementById('edge-start-dosomething').style('curve-style'))).toBe('unbundled-bezier');
}

async function useRendererAndPinNodes(page, positions) {
  await page.locator('#btn-design').click();
  await waitForLayoutIdle(page);
  await page.evaluate(({ positions: next }) => {
    window.cy.stop();
    Object.entries(next).forEach(([id, position]) => window.cy.getElementById(id).position(position));
  }, { positions });
  await clearUnpinnedNodes(page, Object.keys(positions));
  await fitPinnedGraph(page, Object.keys(positions));
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
}

async function blankCanvasPoint(page, preferred = null) {
  const { canvas, boxes } = await page.evaluate(() => {
    const rect = window.cy.container().getBoundingClientRect();
    return {
      canvas: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
      boxes: window.cy.nodes().map(node => node.renderedBoundingBox()),
    };
  });
  const candidates = [];
  for (let y = 28; y < canvas.height - 28; y += 32) {
    for (let x = 28; x < canvas.width - 28; x += 32) {
      const clear = boxes.every(box => x < box.x1 - 16 || x > box.x2 + 16
        || y < box.y1 - 16 || y > box.y2 + 16);
      if (clear) candidates.push({ x: canvas.x + x, y: canvas.y + y });
    }
  }
  if (preferred && candidates.length) candidates.sort((left, right) =>
    Math.hypot(left.x - preferred.x, left.y - preferred.y)
      - Math.hypot(right.x - preferred.x, right.y - preferred.y));
  if (candidates.length) return candidates[0];
  throw new Error('Cyto canvas has no measured blank point');
}

function nodeIds(page) {
  return page.evaluate(() => window.cy.nodes().map(node => node.id()).sort());
}

function edgeLinks(page) {
  return page.evaluate(() => window.cy.edges()
    .map(edge => `${edge.id()}:${edge.data('source')}>${edge.data('target')}`).sort());
}

function documentEdges(page) {
  return page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort());
}

async function centreOf(page, nodeId) {
  return page.evaluate(id => {
    const box = window.cy.container().getBoundingClientRect();
    const position = window.cy.getElementById(id).renderedPosition();
    return { x: box.x + position.x, y: box.y + position.y };
  }, nodeId);
}

async function previewGeometry(page) {
  return page.locator('#edge-ghost .ghost-edge').evaluate(path => {
    const d = path.getAttribute('d');
    const length = path.getTotalLength();
    const point = ratio => {
      const value = path.getPointAtLength(length * ratio);
      return { x: value.x, y: value.y };
    };
    const commands = [...d.matchAll(/([MLQC])([^MLQC]*)/g)].map(match => ({
      type: match[1],
      values: (match[2].match(/-?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?/gi) || []).map(Number),
    }));
    return { d, source: point(0), midpoint: point(0.5), target: point(1), commands };
  });
}

async function committedGeometry(page, edgeId = 'edge-1') {
  return page.evaluate(id => {
    const edge = window.cy.getElementById(id);
    const safePoints = method => {
      try { return edge[method]().map(point => ({ x: point.x, y: point.y })); } catch { return []; }
    };
    return {
      curve: edge.style('curve-style'),
      source: edge.renderedSourceEndpoint(),
      target: edge.renderedTargetEndpoint(),
      midpoint: edge.renderedMidpoint(),
      controls: safePoints('renderedControlPoints'),
      segments: safePoints('renderedSegmentPoints'),
      sourceEndpoint: edge.style('source-endpoint'),
      targetEndpoint: edge.style('target-endpoint'),
      turn: parseFloat(edge.style('taxi-turn')) || 0,
      radius: edge.style('curve-style') === 'round-taxi'
        ? parseFloat(edge.style('taxi-radius')) || 0
        : edge.style('curve-style') === 'round-segments'
          ? parseFloat(edge.style('segment-radii')) || 0 : 0,
    };
  }, edgeId);
}

function expectPointClose(actual, expected, tolerance, message) {
  expect(Math.hypot(actual.x - expected.x, actual.y - expected.y), message)
    .toBeLessThanOrEqual(tolerance);
}

function previewFamily(geometry) {
  const types = geometry.commands.map(command => command.type);
  if (types.includes('C')) return 'bezier';
  if (types.filter(type => type === 'Q').length === 2 && !types.includes('L')) return 'unbundled-bezier';
  return 'rounded';
}

function previewRoutePoints(geometry) {
  return geometry.commands.filter(command => command.type === 'Q')
    .map(command => ({ x: command.values[0], y: command.values[1] }));
}

async function pinExactRoute(page, {
  sourceId = 'start', targetId = 'node-1', source = { x: 100, y: 120 },
  target = null, portGap = null, zoom = 1,
} = {}) {
  await page.locator('#btn-design').click();
  await waitForLayoutIdle(page);
  return page.evaluate(({ sourceId: sourceKey, targetId: targetKey, source: sourcePosition,
    target: targetPosition, portGap: requestedGap, zoom: nextZoom }) => {
    const sourceNode = window.cy.getElementById(sourceKey);
    const targetNode = window.cy.getElementById(targetKey);
    window.cy.stop();
    sourceNode.position(sourcePosition);
    const resolvedTarget = targetPosition || {
      x: sourcePosition.x + sourceNode.width() / 2 + targetNode.width() / 2 + requestedGap,
      y: sourcePosition.y,
    };
    targetNode.position(resolvedTarget);
    // Every OTHER node in the document (the template's own dosomething/error, or `end` when
    // it isn't the pair under test) is pushed well clear of the pinned pair, generically rather than
    // naming `end` specifically -- that was this function's own version of the same bug the rest of
    // this file has: a hardcoded exception for one node that stopped covering the template once a
    // second and third node joined it.
    let clearIndex = 0;
    window.cy.nodes().forEach(node => {
      if (node.id() === sourceKey || node.id() === targetKey) return;
      clearIndex += 1;
      node.position({ x: sourcePosition.x + 900 + (clearIndex * 220), y: sourcePosition.y + 900 });
    });
    const rect = window.cy.container().getBoundingClientRect();
    window.cy.zoom(nextZoom);
    window.cy.pan({
      x: rect.width * 0.28 - sourcePosition.x * nextZoom,
      y: rect.height * 0.48 - sourcePosition.y * nextZoom,
    });
    return {
      sourceWidth: sourceNode.width(), targetWidth: targetNode.width(),
      source: sourceNode.position(), target: targetNode.position(), zoom: window.cy.zoom(),
    };
  }, { sourceId, targetId, source, target, portGap, zoom });
}

async function authorPreviewedEdge(page, sourceId, targetId) {
  const from = await centreOf(page, sourceId);
  const to = await centreOf(page, targetId);
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 12 });
  await expect(page.locator(`#cy-wrap`)).toHaveAttribute('data-edge-gesture-state', /target-valid|target-self/);
  await expect.poll(() => page.evaluate(id => window.cy.getElementById(id).hasClass('connect-valid'), targetId))
    .toBe(true);
  const preview = await previewGeometry(page);
  await page.mouse.up();
  await expect.poll(() => edgeLinks(page)).toContain(`edge-1:${sourceId}>${targetId}`);
  return { preview, committed: await committedGeometry(page) };
}

function expectPreviewMatchesCommitted(preview, committed, tolerance = 2) {
  expectPointClose(preview.source, committed.source, tolerance, 'source endpoint');
  expectPointClose(preview.target, committed.target, tolerance, 'target endpoint');
  const previewPoints = previewRoutePoints(preview);
  const committedPoints = committed.curve === 'round-segments'
    ? committed.segments : committed.controls;
  expect(committedPoints).toHaveLength(previewPoints.length);
  previewPoints.forEach((point, index) => {
    expectPointClose(point, committedPoints[index], tolerance, `route point ${index + 1}`);
  });
  if (committed.curve === 'unbundled-bezier') {
    expectPointClose(preview.midpoint, committed.midpoint, tolerance, 'route midpoint');
  }
}

// ═══════════════════════════════════════════════════════════════
// POINTER
// ═══════════════════════════════════════════════════════════════

test('Cyto previews continuously, then creates one edge without moving the source', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  await useCytoAndPinNodesApart(page);
  const before = await documentEdges(page);

  const from = await centreOf(page, 'start');
  const to = await centreOf(page, 'node-1');
  const sourceBefore = await page.evaluate(() => window.cy.getElementById('start').position());
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await expect(page.locator('#edge-ghost')).not.toHaveClass(/on/);
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  const blank = await blankCanvasPoint(page, {
    x: (from.x + to.x) / 2,
    y: (from.y + to.y) / 2,
  });
  await page.mouse.move(blank.x, blank.y, { steps: 8 });
  await expect(page.locator('#edge-ghost')).toHaveClass(/on/);
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'dragging');
  const firstPreview = await page.locator('#edge-ghost .ghost-edge').getAttribute('d');
  await page.mouse.move(to.x, to.y, { steps: 8 });
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'target-valid');
  await expect(page.locator('#graph-live')).toContainText('Release to connect');
  const snappedPreview = await page.locator('#edge-ghost .ghost-edge').getAttribute('d');
  expect(snappedPreview).not.toBe(firstPreview);
  await page.mouse.up();

  await expect.poll(() => documentEdges(page)).toHaveLength(before.length + 1);
  await expect.poll(() => edgeLinks(page)).toContain('edge-1:start>node-1');
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('start').position()))
    .toEqual(sourceBefore);
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
  // Routed through the command model, so it undoes like any other edit (UI-01).
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Connect start/);
  await page.locator('#btn-undo').click();
  await expect.poll(() => documentEdges(page)).toEqual(before);
});

test('Design keeps provisional and committed pointer edges in its Cyto routing contract', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  await useDesignAndPinNodesApart(page);
  const beforePositions = await page.evaluate(() => Object.fromEntries(
    window.cy.nodes().map(node => [node.id(), node.position()])));
  const beforeStyle = await page.evaluate(() => {
    const edge = window.cy.getElementById('edge-start-dosomething');
    return {
      curve: edge.style('curve-style'), line: edge.style('line-color'),
      marker: edge.style('target-arrow-shape'), markerColor: edge.style('target-arrow-color'),
    };
  });
  expect(beforeStyle.curve).toBe('unbundled-bezier');

  const from = await centreOf(page, 'start');
  const to = await centreOf(page, 'node-1');
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 12 });
  await expect(page.locator('#edge-ghost .ghost-unbundled-bezier')).toBeVisible();
  const preview = await page.locator('#edge-ghost .ghost-edge').getAttribute('d');
  expect(preview).toContain(' Q ');
  await page.mouse.up();

  await expect.poll(() => edgeLinks(page)).toContain('edge-1:start>node-1');
  // This is observed immediately after insertion: no default-bezier paint is allowed between add
  // and the active renderer contract, and marker/line values remain shared with its sibling edge.
  await expect.poll(() => page.evaluate(() => {
    const edge = window.cy.getElementById('edge-1');
    return {
      curve: edge.style('curve-style'), line: edge.style('line-color'),
      marker: edge.style('target-arrow-shape'), markerColor: edge.style('target-arrow-color'),
    };
  })).toEqual(beforeStyle);
  await expect.poll(() => page.evaluate(() => Object.fromEntries(
    window.cy.nodes().map(node => [node.id(), node.position()])))).toEqual(beforePositions);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Connect start/);
});

const CUSTOM_RENDERER_CASES = [
  { name: 'Design forward', preview: 'unbundled-bezier', curve: 'unbundled-bezier', start: { x: 0, y: 0 }, target: { x: 300, y: 90 } },
  { name: 'Design backward', preview: 'unbundled-bezier', curve: 'unbundled-bezier', start: { x: 300, y: 90 }, target: { x: 0, y: 0 } },
];

for (const scenario of CUSTOM_RENDERER_CASES) {
  test(`custom renderer preview resolves to its committed route family: ${scenario.name}`, async ({ page }) => {
    await page.goto('/');
    await startEditableWorkflow(page);
    await addReviewNode(page);
    // Dosomething must be pinned rightward of every `scenario.start` here (never left to
    // clearUnpinnedNodes' default "push it far away"), because N8N4's edge family is purely
    // geometric -- `renderer-edge-route.js`: rightward and >=20 model px apart is unbundled-bezier,
    // anything else is round-segments (nothing about node degree or a "hybrid" exception). The
    // N8N4-backward case below asserts edge-start-dosomething STAYS unbundled-bezier; that only
    // holds if dosomething is actually positioned rightward of start, the way `end` always was for
    // the same edge in the earlier three-node fixture.
    await useRendererAndPinNodes(page, {
      start: scenario.start, 'node-1': scenario.target, dosomething: { x: 600, y: 0 }, end: { x: 600, y: 180 },
    });
    const before = await page.evaluate(() => Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])));
    const from = await centreOf(page, 'start');
    const to = await centreOf(page, 'node-1');
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    await page.mouse.move(to.x, to.y, { steps: 12 });
    const preview = await previewGeometry(page);
    expect(previewFamily(preview)).toBe(
      scenario.preview === 'unbundled-bezier' ? 'unbundled-bezier' : 'rounded');
    await page.mouse.up();
    await expect.poll(() => edgeLinks(page)).toContain('edge-1:start>node-1');
    await expect.poll(() => page.evaluate(() => window.cy.getElementById('edge-1').style('curve-style')))
      .toBe(scenario.curve);
    const committed = await committedGeometry(page);
    for (const endpoint of ['source', 'target']) {
      expectPointClose(preview[endpoint], committed[endpoint], 2,
        `${scenario.name} ${endpoint} preview should meet its committed port`);
    }
    if (scenario.preview === 'unbundled-bezier') {
      expectPointClose(preview.midpoint, committed.midpoint, 4,
        `${scenario.name} preview should preserve its committed midpoint`);
    }
    await expect.poll(() => page.evaluate(() =>
      window.cy.getElementById('edge-start-dosomething').style('curve-style')))
      .toBe('unbundled-bezier');
    await expect.poll(() => page.evaluate(() => Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()]))))
      .toEqual(before);
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Connect start/);
    await page.locator('#btn-undo').click();
    await expect.poll(() => edgeLinks(page)).not.toContain('edge-1:start>node-1');
  });
}

test('Design keeps preview and committed ports invariant at zoom 0.5, 1 and 2', async ({ page }) => {
  test.setTimeout(90000);
  for (const zoom of [0.5, 1, 2]) {
    await startEditableWorkflow(page);
    await addReviewNode(page);
    const pinned = await pinExactRoute(page, { portGap: 20, zoom });
    expect(pinned.zoom).toBe(zoom);
    const { preview, committed } = await authorPreviewedEdge(page, 'start', 'node-1');
    expect(previewFamily(preview)).toBe('unbundled-bezier');
    expect(committed.curve).toBe('unbundled-bezier');
    expectPreviewMatchesCommitted(preview, committed, 2);
    await page.locator('#btn-undo').click();
    await expect.poll(() => edgeLinks(page)).not.toContain('edge-1:start>node-1');
  }
});

test('parallel Design previews use post-insert slots and re-slot siblings once', async ({ page }) => {
  for (const renderer of [{ name: 'Design', curve: 'unbundled-bezier' }]) {
    await startEditableWorkflow(page);
    // Start no longer has a direct edge to end -- dosomething does (edge-dosomething-end).
    // That is now the pre-existing sibling this test needs: the pair whose second, authored edge
    // should trigger post-insert re-slotting.
    await pinExactRoute(page, {
      sourceId: 'dosomething', targetId: 'end', source: { x: 100, y: 120 }, target: { x: 420, y: 190 },
    });
    const siblingBefore = await committedGeometry(page, 'edge-dosomething-end');
    const { preview, committed } = await authorPreviewedEdge(page, 'dosomething', 'end');
    expect(committed.curve).toBe(renderer.curve);
    expectPreviewMatchesCommitted(preview, committed, 2);

    const siblingAfter = await committedGeometry(page, 'edge-dosomething-end');
    expect(siblingAfter.sourceEndpoint, `${renderer.name} source port should be re-slotted`)
      .not.toBe(siblingBefore.sourceEndpoint);
    expect(siblingAfter.targetEndpoint, `${renderer.name} target port should be re-slotted`)
      .not.toBe(siblingBefore.targetEndpoint);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    expect(await committedGeometry(page, 'edge-dosomething-end')).toEqual(siblingAfter);
  }
});

test('Cyto preview and final share backward and vertical ports, controls and midpoint', async ({ page }) => {
  for (const scenario of [
    {
      name: 'backward', source: { x: 420, y: 160 }, target: { x: 100, y: 90 },
      sourceSide: 'left', targetSide: 'right',
    },
    {
      name: 'vertical', source: { x: 180, y: 80 }, target: { x: 180, y: 380 },
      sourceSide: 'bottom', targetSide: 'top',
    },
  ]) {
    await startEditableWorkflow(page);
    await addReviewNode(page);
    await pinExactRoute(page, { source: scenario.source, target: scenario.target, zoom: 2 });
    const before = await edgeLinks(page);
    const { preview, committed } = await authorPreviewedEdge(page, 'start', 'node-1');
    expect(previewFamily(preview), scenario.name).toBe('unbundled-bezier');
    expect(committed.curve).toBe('unbundled-bezier');
    expect(previewRoutePoints(preview), `${scenario.name} preview controls`).toHaveLength(2);
    expect(committed.controls, `${scenario.name} committed controls`).toHaveLength(2);
    expectPreviewMatchesCommitted(preview, committed, 2);

    const routeState = await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument();
      const edge = owner.cy.getElementById('edge-1');
      const route = owner.cytoEdgeRouteCache.get('edge-1');
      const nodeGeometry = id => {
        const node = owner.cy.getElementById(id);
        return { position: node.position(), width: node.width(), height: node.height() };
      };
      return {
        route: route ? {
          sourceSide: route.sourceSide, targetSide: route.targetSide,
          sourceEndpoint: route.sourceEndpoint, targetEndpoint: route.targetEndpoint,
          start: route.start, end: route.end, points: route.points,
        } : null,
        style: {
          curve: edge.style('curve-style'),
          sourceEndpoint: edge.style('source-endpoint'),
          targetEndpoint: edge.style('target-endpoint'),
          controlPointWeights: edge.style('control-point-weights'),
          controlPointDistances: edge.style('control-point-distances'),
        },
        sourceNode: nodeGeometry('start'), targetNode: nodeGeometry('node-1'),
      };
    });
    expect(routeState.route, `${scenario.name} committed route cache entry`).not.toBeNull();
    expect(routeState.route).toMatchObject({
      sourceSide: scenario.sourceSide, targetSide: scenario.targetSide,
    });
    expect(routeState.style).toMatchObject({
      curve: 'unbundled-bezier',
      sourceEndpoint: routeState.route.sourceEndpoint,
      targetEndpoint: routeState.route.targetEndpoint,
    });
    expect(routeState.style.controlPointWeights).not.toBe('');
    expect(routeState.style.controlPointDistances).not.toBe('');

    const normals = {
      left: { x: -1, y: 0 }, right: { x: 1, y: 0 },
      top: { x: 0, y: -1 }, bottom: { x: 0, y: 1 },
    };
    const tangentDot = (control, endpoint, side) =>
      (control.x - endpoint.x) * normals[side].x
        + (control.y - endpoint.y) * normals[side].y;
    expect(tangentDot(routeState.route.points[0], routeState.route.start, scenario.sourceSide),
      `${scenario.name} source control must leave the selected side`).toBeGreaterThan(0);
    expect(tangentDot(routeState.route.points[1], routeState.route.end, scenario.targetSide),
      `${scenario.name} target control must leave the selected side`).toBeGreaterThan(0);

    const expectEndpointOnSide = (endpoint, node, side, label) => {
      const halfWidth = node.width / 2;
      const halfHeight = node.height / 2;
      if (side === 'left' || side === 'right') {
        expect(endpoint.x, label).toBeCloseTo(
          node.position.x + (side === 'left' ? -halfWidth : halfWidth), 4);
        expect(Math.abs(endpoint.y - node.position.y), label).toBeLessThanOrEqual(halfHeight);
      } else {
        expect(endpoint.y, label).toBeCloseTo(
          node.position.y + (side === 'top' ? -halfHeight : halfHeight), 4);
        expect(Math.abs(endpoint.x - node.position.x), label).toBeLessThanOrEqual(halfWidth);
      }
    };
    expectEndpointOnSide(routeState.route.start, routeState.sourceNode, scenario.sourceSide,
      `${scenario.name} source endpoint`);
    expectEndpointOnSide(routeState.route.end, routeState.targetNode, scenario.targetSide,
      `${scenario.name} target endpoint`);

    await expect.poll(() => edgeLinks(page)).toEqual([...before, 'edge-1:start>node-1'].sort());
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Connect start/);
    await page.locator('#btn-undo').click();
    await expect.poll(() => edgeLinks(page)).toEqual(before);
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.activeDocument().cytoEdgeRouteCache.has('edge-1'))).toBe(false);
  }
});

test('Cyto authors a readable self-loop atomically and preserves it through undo, redo and GraphML reload', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  await useCytoAndPinNodesApart(page);
  const before = await edgeLinks(page);
  const positionsBefore = await page.evaluate(() => Object.fromEntries(
    window.cy.nodes().map(node => [node.id(), node.position()])));

  const source = await centreOf(page, 'node-1');
  await page.mouse.move(source.x, source.y);
  await page.mouse.down();
  await expect(page.locator('#cy-wrap')).not.toHaveAttribute('data-edge-gesture-source', 'node-1');
  await page.mouse.move(source.x + 55, source.y - 55, { steps: 8 });
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-source', 'node-1');
  // Return inside the source rather than to the mathematically identical down coordinate. This
  // proves a real re-entry event (some browser stacks coalesce a round-trip's identical endpoint)
  // while remaining well inside the visible node and its explicit snap corridor.
  await page.mouse.move(source.x + 10, source.y + 10, { steps: 8 });

  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'target-self');
  await expect(page.locator('#edge-ghost')).toHaveAttribute('data-preview-kind', 'self-loop');
  await expect(page.locator('#edge-ghost .ghost-loop')).toBeVisible();
  await expect(page.locator('#graph-live')).toContainText('self-loop');
  const previewBox = await page.locator('#edge-ghost .ghost-loop').boundingBox();
  const nodeBox = await page.evaluate(() => window.cy.getElementById('node-1').renderedBoundingBox());
  expect(previewBox.x + previewBox.width).toBeGreaterThan(nodeBox.x2 + 20);
  await page.mouse.up();

  await expect.poll(() => edgeLinks(page)).toContain('edge-1:node-1>node-1');
  await expect.poll(() => page.evaluate(() => Object.fromEntries(
    window.cy.nodes().map(node => [node.id(), node.position()])))).toEqual(positionsBefore);
  await expect.poll(() => page.evaluate(() => {
    const loop = window.cy.getElementById('edge-1');
    const node = loop.source().renderedBoundingBox();
    const route = loop.renderedBoundingBox();
    return {
      curve: loop.style('curve-style'), direction: loop.style('loop-direction'),
      sweep: loop.style('loop-sweep'), step: Number.parseFloat(loop.style('control-point-step-size')),
      rotation: loop.style('text-rotation'),
      clearanceRatio: (node.y1 - route.y1) / node.h,
    };
  })).toMatchObject({
    curve: 'bezier', direction: '0deg', sweep: '-80deg', rotation: 'none',
    step: expect.any(Number), clearanceRatio: expect.any(Number),
  });
  const loopGeometry = await page.evaluate(() => {
    const loop = window.cy.getElementById('edge-1');
    const node = loop.source().renderedBoundingBox();
    const route = loop.renderedBoundingBox();
    return {
      step: Number.parseFloat(loop.style('control-point-step-size')),
      clearanceRatio: (node.y1 - route.y1) / node.h,
    };
  });
  expect(loopGeometry.step).toBeGreaterThanOrEqual(88);
  expect(loopGeometry.clearanceRatio).toBeGreaterThan(0.5);

  await page.locator('#btn-undo').click();
  await expect.poll(() => edgeLinks(page)).toEqual(before);
  await page.locator('#btn-redo').click();
  await expect.poll(() => edgeLinks(page)).toContain('edge-1:node-1>node-1');

  // Block body, no implicit return. `emit()` returns the collection it was called on,
  // which holds the whole Cytoscape core -- the same defect `fitPinnedGraph` documents, and the
  // reason this test has been seen dying at THIS line rather than inside a setup helper.
  await page.evaluate(() => { window.cy.getElementById('edge-1').emit('tap'); });
  await page.locator('#edge-editor input[name="outcome"]').fill('retry');
  await page.locator('#edge-editor input[name="edgeName"]').fill('Review again');
  await page.locator('#edge-editor [data-add-property]').click();
  await page.locator('#edge-properties .property-row input[data-property-name]').last().fill('maxAttempts');
  await page.locator('#edge-properties .property-row input[data-property-value]').last().fill('3');
  await page.locator('#edge-editor button[type="submit"]').click();

  const download = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#btn-export').click(),
  ]).then(([event]) => event);
  const stream = await download.createReadStream();
  const xml = await new Promise(resolve => {
    let text = '';
    stream.on('data', chunk => { text += chunk; });
    stream.on('end', () => resolve(text));
  });
  await page.locator('#file-inp').setInputFiles({
    name: 'self-loop.graphml', mimeType: 'application/graphml+xml', buffer: Buffer.from(xml),
  });

  await expect.poll(() => edgeLinks(page)).toContain('edge-1:node-1>node-1');
  await expect.poll(() => page.evaluate(() => {
    const loop = window.cy.getElementById('edge-1');
    return {
      id: loop.id(), source: loop.data('source'), target: loop.data('target'),
      outcome: loop.data('outcome'), edgeName: loop.data('edgeName'),
      maxAttempts: loop.data('properties').maxAttempts,
    };
  })).toEqual({
    id: 'edge-1', source: 'node-1', target: 'node-1', outcome: 'retry',
    edgeName: 'Review again', maxAttempts: '3',
  });
  // Import builds a fresh Cytoscape instance. The loop must regain its renderer contract, not just
  // its data, so a round-trip never reloads as an unreadable generic edge.
  await expect.poll(() => page.evaluate(() => {
    const loop = window.cy.getElementById('edge-1');
    return {
      curve: loop.style('curve-style'), direction: loop.style('loop-direction'),
      sweep: loop.style('loop-sweep'),
      hasComputedLine: Boolean(loop.style('line-color')),
      marker: loop.style('target-arrow-shape'),
    };
  })).toEqual({
    curve: 'bezier', direction: '0deg', sweep: '-80deg',
    hasComputedLine: true, marker: 'triangle',
  });
});

test('reconnects an edge by dragging the end nearest the pointer onto another node', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  // This test needs start, the edge's other end (now dosomething, not end) and the drop
  // target (node-1) all in the visible pinned area. `pinNodesApart` parks dosomething out of the
  // way instead, since most of this file's tests don't care where it sits -- swap it back in just
  // for this one: dosomething takes the visual slot `end` used to occupy, and `end` (which this
  // test never touches) is pushed clear instead.
  await page.evaluate(() => {
    window.cy.stop();
    window.cy.getElementById('dosomething').position({ x: 600, y: 0 });
    window.cy.getElementById('end').position({ x: -1200, y: -900 });
    const pinned = window.cy.collection([
      window.cy.getElementById('start'),
      window.cy.getElementById('dosomething'),
      window.cy.getElementById('node-1'),
    ]);
    window.cy.fit(pinned, 80);
  });
  await page.evaluate(() => new Promise(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(resolve))));
  // The document also carries dosomething's own two edges (to end and to error), untouched by this
  // test, so the check is "this edge exists" (toContain), not "this is the only edge in the
  // document" (toEqual) -- true for a fresh document once, never again.
  await expect.poll(() => edgeLinks(page)).toContain('edge-start-dosomething:start>dosomething');

  // Grab the existing start ──> dosomething edge close to its target end, and drop it on the new
  // node. Computed from the edge's own rendered geometry (source/midpoint/target), not a straight
  // line between the two node centers: unlike `end` in the old template, dosomething has three
  // incident edges (one in, two out), so Cytoscape's per-node curve routing is not guaranteed to
  // draw this one as the straight segment a two-point interpolation would assume.
  const container = await page.evaluate(() => {
    const box = window.cy.container().getBoundingClientRect();
    return { x: box.x, y: box.y };
  });
  const edgeGeometry = await committedGeometry(page, 'edge-start-dosomething');
  // Cyto can select a non-horizontal anchor pair, so target→midpoint linear interpolation is
  // no longer guaranteed to lie on the rendered quadratic. Pick a real point on its final segment
  // from Cytoscape's own rendered control point. The fallback keeps this helper valid for a
  // renderer with no exposed controls.
  const control = edgeGeometry.controls.at(-1);
  const junction = edgeGeometry.controls.length === 2 ? {
    x: (edgeGeometry.controls[0].x + edgeGeometry.controls[1].x) / 2,
    y: (edgeGeometry.controls[0].y + edgeGeometry.controls[1].y) / 2,
  } : edgeGeometry.midpoint;
  const quadraticPoint = t => {
    const inverse = 1 - t;
    return {
      x: inverse * inverse * junction.x
        + 2 * inverse * t * control.x + t * t * edgeGeometry.target.x,
      y: inverse * inverse * junction.y
        + 2 * inverse * t * control.y + t * t * edgeGeometry.target.y,
    };
  };
  const curvePoint = control ? quadraticPoint(0.72) : {
    x: edgeGeometry.target.x + ((edgeGeometry.midpoint.x - edgeGeometry.target.x) * 0.15),
    y: edgeGeometry.target.y + ((edgeGeometry.midpoint.y - edgeGeometry.target.y) * 0.15),
  };
  const hitCandidates = control ? [0.92, 0.84, 0.76, 0.68].map(tValue =>
    quadraticPoint(tValue)) : [curvePoint];
  const review = await centreOf(page, 'node-1');
  const beforeReconnect = await edgeLinks(page);

  let hitRenderedCurve = false;
  for (const candidate of hitCandidates) {
    await page.mouse.move(container.x + candidate.x, container.y + candidate.y);
    await page.mouse.down();
    await page.mouse.move(review.x, review.y, { steps: 15 });
    hitRenderedCurve = await page.evaluate(() =>
      window.cy.edges('.edge-reconnecting').map(edge => edge.id()).includes('edge-start-dosomething'));
    if (hitRenderedCurve) break;
    await page.keyboard.press('Escape');
    await page.mouse.up();
  }
  // This proves one of the mathematically sampled points hit the rendered curve itself before the
  // drop is released. A miss would start node authoring or remain on the canvas.
  expect(hitRenderedCurve).toBe(true);
  await page.mouse.up();

  // The edge KEPT ITS ID: this is a reconnection, not a delete plus an insert.
  await expect.poll(() => edgeLinks(page)).toContain('edge-start-dosomething:start>node-1');
  await expect.poll(() => edgeLinks(page)).not.toContain('edge-start-dosomething:start>dosomething');
  await expect.poll(() => edgeLinks(page)).toHaveLength(beforeReconnect.length);
  await expect.poll(() => edgeLinks(page).then(edges => edges.filter(edge => edge.startsWith('edge-1:'))))
    .toEqual([]);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Reconnect edge-start-dosomething/);

  await page.locator('#btn-undo').click();
  await expect.poll(() => edgeLinks(page)).toEqual(beforeReconnect);
  await page.locator('#btn-redo').click();
  await expect.poll(() => edgeLinks(page)).toContain('edge-start-dosomething:start>node-1');
  await expect.poll(() => edgeLinks(page)).toHaveLength(beforeReconnect.length);
});

test('an invalid pointer drop cancels cleanly without document, history or message residue', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  const before = await edgeLinks(page);

  // START takes no incoming edge.
  const from = await centreOf(page, 'node-1');
  const to = await centreOf(page, 'start');
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 15 });
  await page.mouse.up();

  await expect(page.locator('#graph-live')).toBeEmpty();
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  await expect(page.locator('#edge-ghost')).not.toHaveClass(/on/);
  await expect.poll(() => edgeLinks(page)).toEqual(before);
  // Nothing was committed: the last undoable step is still the node that was added in setup, so the
  // refused drop pushed no command onto the history.
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Add node/);
});

test('a Cyto click selects and its subsequent selected-node drag moves without latent edge state', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  await useCytoAndPinNodesApart(page);
  const before = await edgeLinks(page);
  const source = await centreOf(page, 'node-1');
  const canvasDrop = await blankCanvasPoint(page);

  // Pressing is still a selection candidate: authoring must remain entirely invisible until the
  // pointer crosses the drag-intent threshold.
  await page.mouse.move(source.x, source.y);
  await page.mouse.down();
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  await expect(page.locator('#edge-ghost')).not.toHaveClass(/on/);
  await expect(page.locator('#graph-live')).not.toContainText('Connecting');
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('node-1').hasClass('connect-source')))
    .toBe(false);
  await page.mouse.up();
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  await expect(page.locator('#graph-live')).not.toContainText('Connecting');
  await expect.poll(() => edgeLinks(page)).toEqual(before);

  const positionBefore = await page.evaluate(() => window.cy.getElementById('node-1').position());
  await page.mouse.move(source.x, source.y);
  await page.mouse.down();
  await page.mouse.move(canvasDrop.x, canvasDrop.y, { steps: 10 });
  await page.mouse.up();

  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  await expect(page.locator('#graph-live')).toBeEmpty();
  await expect(page.locator('#edge-ghost')).not.toHaveClass(/on/);
  await expect.poll(() => edgeLinks(page)).toEqual(before);
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('node-1').position()))
    .not.toEqual(positionBefore);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move node-1/);
});

// The three renderer flags a pointer gesture suspends. They are read directly rather than through a
// symptom because they are the contract: whatever suspends them owes an exact restoration, and a
// cancel path that forgets even one of them leaves the canvas frozen.
function rendererInteraction(page) {
  return page.evaluate(() => ({
    autoungrabify: window.cy.autoungrabify(),
    boxSelection: window.cy.boxSelectionEnabled(),
    userPanning: window.cy.userPanningEnabled(),
  }));
}

test('Escape during a pointer drag cancels the edge and gives the canvas back', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  const before = await edgeLinks(page);
  const idle = await rendererInteraction(page);
  expect(idle).toEqual({ autoungrabify: false, boxSelection: true, userPanning: false });

  // Open a pointer gesture and hold it: the button stays down, so the drag is still in progress
  // when Escape arrives. The existing Escape coverage is keyboard-only, which is exactly why this
  // path was never exercised.
  const from = await centreOf(page, 'start');
  const over = await centreOf(page, 'node-1');
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(over.x, over.y, { steps: 10 });

  // Mid-drag the renderer is deliberately suspended.
  expect(await rendererInteraction(page)).toEqual({
    autoungrabify: true, boxSelection: false, userPanning: false,
  });

  await page.keyboard.press('Escape');
  await expect(page.locator('#graph-live')).toContainText('cancelled');
  await page.mouse.up();

  // Escape is the documented cancel, advertised in the aria-label and the shortcut panel, so it
  // owes the same restoration a completed drag performs.
  expect(await rendererInteraction(page)).toEqual(idle);
  await expect.poll(() => edgeLinks(page)).toEqual(before);

  // The consequence the user would actually meet: Editing remains armed for another edge drag.
  expect(await rendererInteraction(page)).toEqual(idle);
});

// ═══════════════════════════════════════════════════════════════
// KEYBOARD
// ═══════════════════════════════════════════════════════════════

test('creates an edge with the keyboard alone', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(String(error)));
  await startEditableWorkflow(page);
  await addReviewNode(page);
  const before = await documentEdges(page);

  // The graph is a single focusable widget; from here on nothing but keys is used.
  await page.locator('#cy-wrap').focus();
  await expect(page.locator('#cy-wrap')).toBeFocused();
  // Focusing puts the cursor on the first node and says where it is.
  await expect(page.locator('#graph-live')).toContainText('start');

  // A gesture can be abandoned without leaving anything behind.
  await page.locator('#cy-wrap').press('e');
  await expect(page.locator('#graph-live')).toContainText('Connecting from');
  await page.locator('#cy-wrap').press('Escape');
  await expect(page.locator('#graph-live')).toContainText('cancelled');
  expect(await documentEdges(page)).toEqual(before);

  // Create for real: E opens the gesture, the arrow key chooses the target, Enter commits.
  await page.locator('#cy-wrap').press('e');
  await page.locator('#cy-wrap').press('ArrowRight');
  await expect(page.locator('#graph-live')).toContainText('Press Enter');
  await page.locator('#cy-wrap').press('Enter');

  await expect.poll(() => documentEdges(page)).toHaveLength(before.length + 1);
  await expect.poll(() => edgeLinks(page)).toContain('edge-1:start>node-1');
  await expect(page.locator('#graph-live')).toContainText('Connected');
  expect(pageErrors).toEqual([]);
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
  // The keyboard edit is an ordinary command: it undoes like every other one.
  await page.locator('#btn-undo').click();
  await expect.poll(() => documentEdges(page)).toEqual(before);
});

test('reconnects an edge with the keyboard alone and undoes it', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  // Start has exactly one incident edge (to dosomething), same as before -- only its target
  // changed. toContain rather than toEqual: the document also carries dosomething's own two edges
  // to end and to error, which this test never touches.
  await expect.poll(() => edgeLinks(page)).toContain('edge-start-dosomething:start>dosomething');

  await page.locator('#cy-wrap').focus();
  // The cursor starts on the first node; Shift with an arrow steps onto one of its edges. `start`
  // still has exactly one incident edge, so this selects it regardless of where its target sits.
  await page.locator('#cy-wrap').press('Shift+ArrowRight');
  await expect(page.locator('#graph-live')).toContainText('Edge edge-start-dosomething');
  await expect(page.locator('#graph-live')).toContainText('outcome continue');

  await page.locator('#cy-wrap').press('r');
  await expect(page.locator('#graph-live')).toContainText('Reconnecting the target');
  // The end that is NOT moving is named, so the user knows what the edge will become.
  await expect(page.locator('#graph-live')).toContainText('source stays');

  // ArrowRight scans from the cursor (still on `start`) for the nearest node in that direction.
  // dosomething/error are parked off to the far corner by `pinNodesApart`, so node-1 -- pinned at
  // (300, 90) -- is still the nearest node to the right.
  await page.locator('#cy-wrap').press('ArrowRight');
  await page.locator('#cy-wrap').press('Enter');

  await expect.poll(() => edgeLinks(page)).toContain('edge-start-dosomething:start>node-1');
  await expect(page.locator('#graph-live')).toContainText('Reconnected');

  await page.locator('#btn-undo').click();
  await expect.poll(() => edgeLinks(page)).toContain('edge-start-dosomething:start>dosomething');
});

test('announces the reason a keyboard target is refused and creates nothing', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);
  const before = await edgeLinks(page);

  await page.locator('#cy-wrap').focus();
  // Cursor onto node-1, open a gesture, then aim back at START, which takes no incoming edge.
  await page.locator('#cy-wrap').press('ArrowRight');
  await page.locator('#cy-wrap').press('e');
  await page.locator('#cy-wrap').press('ArrowLeft');
  await page.locator('#cy-wrap').press('Enter');

  await expect(page.locator('#graph-live')).toContainText('START');
  await expect.poll(() => edgeLinks(page)).toEqual(before);
});

// ═══════════════════════════════════════════════════════════════
// VALIDATION AND SERIALIZATION
// ═══════════════════════════════════════════════════════════════

test('validates the edge form while it is being filled in, before anything is submitted', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);

  await page.locator('#btn-add-edge').click();
  const validation = page.locator('#edge-validation');
  const submit = page.locator('#edge-editor button[type="submit"]');

  await page.locator('#edge-editor input[name="id"]').fill('edge-start-dosomething');
  await expect(validation).toContainText('already exists');
  await expect(submit).toBeDisabled();

  await page.locator('#edge-editor input[name="id"]').fill('has space');
  await expect(validation).toContainText('From start to dosomething');
  await expect(submit).toBeEnabled();

  await page.locator('#edge-editor input[name="id"]').fill(' edge-review ');
  await page.locator('#edge-editor select[name="source"]').selectOption('node-1');
  await page.locator('#edge-editor select[name="target"]').selectOption('start');
  await expect(validation).toContainText('START');
  await expect(submit).toBeDisabled();

  await page.locator('#edge-editor select[name="target"]').selectOption('end');
  await expect(validation).toContainText('From node-1 to end');
  await expect(submit).toBeEnabled();
  await submit.click();

  await expect.poll(() => edgeLinks(page)).toContain(' edge-review :node-1>end');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.edges
    .some(edge => edge.id === 'edge-review'))).toBe(false);
});

test('exports an authored graph as GraphML that still carries the edge and its properties', async ({ page }) => {
  await startEditableWorkflow(page);
  await addReviewNode(page);

  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor input[name="id"]').fill('edge-approved');
  await page.locator('#edge-editor select[name="source"]').selectOption('start');
  await page.locator('#edge-editor select[name="target"]').selectOption('node-1');
  await page.locator('#edge-editor input[name="outcome"]').fill('approved');
  await page.locator('#edge-editor input[name="command"]').fill('PROCESS');
  await page.locator('#edge-editor [data-add-property]').click();
  await page.locator('#edge-properties .property-row input[data-property-name]').last().fill('slaMinutes');
  await page.locator('#edge-properties .property-row input[data-property-value]').last().fill('30');
  await page.locator('#edge-editor button[type="submit"]').click();
  await expect.poll(() => edgeLinks(page)).toContain('edge-approved:start>node-1');

  const download = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#btn-export').click(),
  ]).then(([event]) => event);
  const stream = await download.createReadStream();
  const xml = await new Promise(resolve => {
    let text = '';
    stream.on('data', chunk => { text += chunk; });
    stream.on('end', () => resolve(text));
  });

  expect(xml).toContain('id="edge-approved"');
  expect(xml).toContain('source="start"');
  expect(xml).toContain('approved');
  expect(xml).toContain('attr.name="command"');
  expect(xml).toContain('>process</data>');
  expect(xml).toContain('slaMinutes');
  expect(xml).toContain('30');
});
