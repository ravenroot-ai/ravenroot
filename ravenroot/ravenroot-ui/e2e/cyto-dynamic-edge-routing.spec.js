import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

const ROUTING_GRAPHML = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="name" for="node" attr.name="name" attr.type="string"/>
  <key id="layout-x" for="node" attr.name="layoutX" attr.type="double"/>
  <key id="layout-y" for="node" attr.name="layoutY" attr.type="double"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <graph id="issue-466-routing" edgedefault="directed">
    <node id="source"><data key="kind">START</data><data key="name">Source</data><data key="layout-x">300</data><data key="layout-y">300</data></node>
    <node id="target"><data key="kind">PASSTHROUGH</data><data key="name">Target</data><data key="layout-x">600</data><data key="layout-y">300</data></node>
    <node id="obstacle"><data key="kind">ERROR</data><data key="name">Obstacle</data><data key="layout-x">-500</data><data key="layout-y">-500</data></node>
    <node id="end"><data key="kind">END</data><data key="name">End</data><data key="layout-x">800</data><data key="layout-y">540</data></node>
    <edge id="route" source="source" target="target"><data key="outcome">continue</data></edge>
  </graph>
</graphml>`;

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

const settleRouting = page => page.evaluate(() => new Promise(resolve =>
  requestAnimationFrame(() => requestAnimationFrame(resolve))));

async function openRoutingGraph(page) {
  await page.goto('/');
  await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'routing.graphml'),
    ROUTING_GRAPHML);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument()?.graph?.nodes
    ?.map(node => node.id).sort()))
    .toEqual(['end', 'obstacle', 'source', 'target']);
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.stop();
    owner.cy.fit(undefined, 100);
  });
  await settleRouting(page);
}

async function dragNodeTo(page, nodeId, position, { release = true } = {}) {
  const points = await page.evaluate(({ id, target }) => {
    const owner = window.ravenroot.activeDocument();
    const node = owner.cy.getElementById(id);
    const container = owner.cy.container().getBoundingClientRect();
    const current = node.renderedPosition();
    const zoom = owner.cy.zoom();
    const pan = owner.cy.pan();
    return {
      from: { x: container.x + current.x, y: container.y + current.y },
      to: { x: container.x + target.x * zoom + pan.x, y: container.y + target.y * zoom + pan.y },
    };
  }, { id: nodeId, target: position });
  await page.mouse.move(points.from.x, points.from.y);
  await page.mouse.down();
  await page.mouse.move(points.to.x, points.to.y, { steps: 18 });
  if (release) await page.mouse.up();
  await settleRouting(page);
}

const routeState = (page, edgeId = 'route') => page.evaluate(id => {
  const owner = window.ravenroot.activeDocument();
  const route = owner.cytoEdgeRouteCache.get(id);
  const edge = owner.cy.getElementById(id);
  const normal = {
    right: { x: 1, y: 0 }, left: { x: -1, y: 0 },
    bottom: { x: 0, y: 1 }, top: { x: 0, y: -1 },
  };
  const dot = (control, endpoint, side) =>
    (control.x - endpoint.x) * normal[side].x + (control.y - endpoint.y) * normal[side].y;
  return {
    sourceSide: route.sourceSide,
    targetSide: route.targetSide,
    sourceTangent: dot(route.points[0], route.start, route.sourceSide),
    targetTangent: dot(route.points[1], route.end, route.targetSide),
    route: {
      start: route.start, end: route.end, midpoint: route.midpoint, points: route.points,
      sourceEndpoint: route.sourceEndpoint, targetEndpoint: route.targetEndpoint,
    },
    committed: {
      curve: edge.style('curve-style'),
      sourceEndpoint: edge.style('source-endpoint'),
      targetEndpoint: edge.style('target-endpoint'),
      controlCount: edge.controlPoints().length,
    },
  };
}, edgeId);

const obstacleEvidence = (page, { edgeId = 'route', nodeId = 'obstacle', margin = 0 } = {}) =>
  page.evaluate(({ routeId, obstacleId, expansion }) => {
    const owner = window.ravenroot.activeDocument();
    const edge = owner.cy.getElementById(routeId);
    const obstacle = owner.cy.getElementById(obstacleId);
    const controls = edge.controlPoints();
    const start = edge.sourceEndpoint();
    const end = edge.targetEndpoint();
    const midpoint = {
      x: (controls[0].x + controls[1].x) / 2,
      y: (controls[0].y + controls[1].y) / 2,
    };
    const center = obstacle.position();
    const box = {
      left: center.x - obstacle.width() / 2 - expansion,
      right: center.x + obstacle.width() / 2 + expansion,
      top: center.y - obstacle.height() / 2 - expansion,
      bottom: center.y + obstacle.height() / 2 + expansion,
    };
    const quadratic = (from, control, to, t) => {
      const inverse = 1 - t;
      return {
        x: inverse * inverse * from.x + 2 * inverse * t * control.x + t * t * to.x,
        y: inverse * inverse * from.y + 2 * inverse * t * control.y + t * t * to.y,
      };
    };
    let penetrationCount = 0;
    for (let step = 1; step < 4_000; step += 1) {
      const t = step / 4_000;
      for (const point of [
        quadratic(start, controls[0], midpoint, t),
        quadratic(midpoint, controls[1], end, t),
      ]) if (point.x > box.left && point.x < box.right
        && point.y > box.top && point.y < box.bottom) penetrationCount += 1;
    }
    const route = owner.cytoEdgeRouteCache.get(routeId);
    return {
      box, start, end, midpoint, controls, penetrationCount,
      curve: edge.style('curve-style'),
      routeSides: [route.sourceSide, route.targetSide],
    };
  }, { routeId: edgeId, obstacleId: nodeId, expansion: margin });

test.beforeEach(async ({ page }) => {
  await stubService(page);
});

test('Cyto vertical edges use bottom-to-top anchors and update only interested routes on drag', async ({ page }) => {
  await page.goto('/');
  await page.locator('#btn-design').click();
  // Cyto is now a complete render mode. Pinning is an authored follow-up operation, so it begins
  // only after the mode's atomic positioning-and-routing transaction releases the canvas.
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.stop();
    owner.cy.getElementById('start').position({ x: 260, y: 120 });
    owner.cy.getElementById('dosomething').position({ x: 260, y: 420 });
    owner.cy.getElementById('end').position({ x: 650, y: 320 });
    owner.cy.getElementById('error').position({ x: 650, y: 520 });
    owner.cy.fit(undefined, 90);
  });
  await settleRouting(page);

  const baseline = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const movedId = 'start';
    const incident = owner.cy.getElementById(movedId).connectedEdges().map(edge => edge.id()).sort();
    const unrelated = owner.cy.edges().map(edge => edge.id()).filter(id => !incident.includes(id)).sort();
    window.__cytoRoutingReferences = Object.fromEntries(owner.cytoEdgeRouteCache);
    const vertical = owner.cytoEdgeRouteCache.get('edge-start-dosomething');
    return {
      graph: JSON.stringify(owner.graph),
      modelPositions: Object.fromEntries(owner.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])),
      renderedPositions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
      incident, unrelated,
      vertical: { sourceSide: vertical.sourceSide, targetSide: vertical.targetSide },
      curve: owner.cy.getElementById('edge-start-dosomething').style('curve-style'),
    };
  });
  expect(baseline).toMatchObject({
    vertical: { sourceSide: 'bottom', targetSide: 'top' },
    curve: 'unbundled-bezier',
  });
  expect(baseline.incident.length).toBeGreaterThan(0);
  expect(baseline.unrelated.length).toBeGreaterThan(0);
  await page.screenshot({ path: 'test-results/issue-466-cyto-vertical.png', fullPage: true });

  await page.evaluate(() => {
    const node = window.ravenroot.activeDocument().cy.getElementById('start');
    node.position({ x: node.position('x') + 75, y: node.position('y') + 120 });
  });
  await settleRouting(page);

  const after = await page.evaluate(({ incident, unrelated }) => {
    const owner = window.ravenroot.activeDocument();
    const changed = id => owner.cytoEdgeRouteCache.get(id) !== window.__cytoRoutingReferences[id];
    return {
      graph: JSON.stringify(owner.graph),
      modelPositions: Object.fromEntries(owner.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])),
      renderedPositions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
      changedIncident: incident.filter(changed),
      changedUnrelated: unrelated.filter(changed),
      dirtyCount: owner.cytoEdgeRouteDirtyNodes.size,
      framePending: owner.cytoEdgeGeometryRaf !== null,
    };
  }, { incident: baseline.incident, unrelated: baseline.unrelated });
  expect(after.changedIncident).toEqual(baseline.incident);
  expect(after.changedUnrelated).toEqual([]);
  expect(after).toMatchObject({
    graph: baseline.graph, modelPositions: baseline.modelPositions, dirtyCount: 0, framePending: false,
  });
  Object.entries(baseline.renderedPositions).forEach(([id, position]) => {
    if (id !== 'start') expect(after.renderedPositions[id]).toEqual(position);
  });
  await expect(page.locator('#cy-wrap')).toHaveAttribute('aria-label', /graph/i);
  await page.screenshot({ path: 'test-results/issue-466-cyto-routing.png', fullPage: true });
});

test('Cyto committed curve continuously clears a real obstacle node box', async ({ page }) => {
  await openRoutingGraph(page);
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.getElementById('source').position({ x: 0, y: 0 });
    owner.cy.getElementById('target').position({ x: 400, y: 100 });
    owner.cy.getElementById('obstacle').position({ x: 296, y: 64 });
    owner.cy.getElementById('end').position({ x: -400, y: -300 });
    owner.cy.fit(undefined, 100);
  });
  await settleRouting(page);

  const evidence = await obstacleEvidence(page);
  const expanded = await obstacleEvidence(page, { margin: 8 });
  expect(evidence.controls).toHaveLength(2);
  expect(evidence.curve).toBe('unbundled-bezier');
  expect(evidence.penetrationCount).toBe(0);
  expect(expanded.penetrationCount).toBe(0);
  expect(evidence.routeSides).not.toEqual(['right', 'bottom']);
  await page.screenshot({ path: 'test-results/issue-466-cyto-obstacle.png', fullPage: true });
});

test('a standalone obstacle reroutes Cyto while entering and leaving during real drag', async ({ page }) => {
  await openRoutingGraph(page);
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.getElementById('source').position({ x: 0, y: 0 });
    owner.cy.getElementById('target').position({ x: 400, y: 0 });
    owner.cy.getElementById('obstacle').position({ x: 200, y: 300 }).data('name', '');
    owner.cy.getElementById('end').position({ x: 200, y: -120 });
    owner.cy.fit(undefined, 100);
  });
  await settleRouting(page);

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'untouched-obstacle.graphml' }));
  await settleRouting(page);
  await page.evaluate(({ firstId, secondId }) => {
    window.ravenroot.activateDocument(firstId);
    const sibling = window.ravenroot.workspace.find(secondId);
    window.__issue466ObstacleSiblingCache = sibling.cytoEdgeRouteCache;
    window.__issue466ObstacleSiblingState = JSON.stringify({
      graph: sibling.graph,
      positions: Object.fromEntries(sibling.cy.nodes().map(node => [node.id(), node.position()])),
      routes: Object.fromEntries(sibling.cytoEdgeRouteCache),
      history: sibling.history.state(),
    });
  }, { firstId: first, secondId: second });
  await settleRouting(page);

  const baseline = await routeState(page);
  expect(baseline).toMatchObject({ sourceSide: 'right', targetSide: 'left' });
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    window.__issue466ObstaclePositionEvents = 0;
    owner.cy.on('position', '#obstacle', () => { window.__issue466ObstaclePositionEvents += 1; });
    window.__issue466ObstacleFrames = [];
    window.__issue466ObstacleFramesRunning = true;
    const sample = () => {
      const current = window.ravenroot.activeDocument();
      const edge = current.cy.getElementById('route');
      const route = current.cytoEdgeRouteCache.get('route');
      window.__issue466ObstacleFrames.push({
        curve: edge.style('curve-style'), controls: edge.controlPoints().length,
        sides: route ? [route.sourceSide, route.targetSide] : null,
      });
      if (window.__issue466ObstacleFramesRunning) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });

  await dragNodeTo(page, 'obstacle', { x: 200, y: 0 }, { release: false });
  await expect.poll(() => page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return {
      grabbed: owner.cy.getElementById('obstacle').grabbed(),
      positionEvents: window.__issue466ObstaclePositionEvents,
      framePending: owner.cytoEdgeGeometryRaf !== null,
      dirtyCount: owner.cytoEdgeRouteDirtyNodes.size,
    };
  })).toMatchObject({ grabbed: true, framePending: false, dirtyCount: 0 });
  expect(await page.evaluate(() => window.__issue466ObstaclePositionEvents)).toBeGreaterThan(0);
  const blocked = await obstacleEvidence(page);
  const blockedExpanded = await obstacleEvidence(page, { margin: 8 });
  expect(blocked).toMatchObject({
    curve: 'unbundled-bezier', penetrationCount: 0, routeSides: ['bottom', 'bottom'],
  });
  expect(blockedExpanded.penetrationCount).toBe(0);
  expect(await page.evaluate(() => window.__issue466ObstacleFrames
    .some(frame => frame.sides?.[0] === 'bottom' && frame.sides?.[1] === 'bottom'))).toBe(true);
  await page.screenshot({ path: 'test-results/issue-466-cyto-obstacle-during-drag.png', fullPage: true });
  await page.mouse.up();
  await settleRouting(page);

  const eventsBeforeLeaving = await page.evaluate(() => window.__issue466ObstaclePositionEvents);
  await dragNodeTo(page, 'obstacle', { x: 200, y: 300 }, { release: false });
  await expect.poll(() => page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const route = owner.cytoEdgeRouteCache.get('route');
    return {
      grabbed: owner.cy.getElementById('obstacle').grabbed(),
      positionEvents: window.__issue466ObstaclePositionEvents,
      framePending: owner.cytoEdgeGeometryRaf !== null,
      dirtyCount: owner.cytoEdgeRouteDirtyNodes.size,
      sides: [route.sourceSide, route.targetSide],
    };
  })).toMatchObject({
    grabbed: true, framePending: false, dirtyCount: 0, sides: ['right', 'left'],
  });
  expect(await page.evaluate(() => window.__issue466ObstaclePositionEvents)).toBeGreaterThan(eventsBeforeLeaving);
  const restored = await routeState(page);
  expect(restored.route).toEqual(baseline.route);
  await page.mouse.up();
  await settleRouting(page);

  const frameEvidence = await page.evaluate(() => {
    window.__issue466ObstacleFramesRunning = false;
    return window.__issue466ObstacleFrames;
  });
  expect(frameEvidence.length).toBeGreaterThan(2);
  expect(frameEvidence.every(frame => frame.curve === 'unbundled-bezier' && frame.controls === 2)).toBe(true);
  expect(frameEvidence.some(frame => frame.sides?.[0] === 'right' && frame.sides?.[1] === 'left')).toBe(true);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().history.state())).toMatchObject({
    depth: 2, undoLabel: 'Move obstacle', canUndo: true,
  });
  expect(await page.evaluate(secondId => {
    const sibling = window.ravenroot.workspace.find(secondId);
    return {
      sameCache: sibling.cytoEdgeRouteCache === window.__issue466ObstacleSiblingCache,
      sameState: JSON.stringify({
        graph: sibling.graph,
        positions: Object.fromEntries(sibling.cy.nodes().map(node => [node.id(), node.position()])),
        routes: Object.fromEntries(sibling.cytoEdgeRouteCache),
        history: sibling.history.state(),
      }) === window.__issue466ObstacleSiblingState,
    };
  }, second)).toEqual({ sameCache: true, sameState: true });
});

test('actual node drag changes Cyto quadrants without persisting anchors or mutating another document', async ({ page }) => {
  await openRoutingGraph(page);
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.getElementById('source').position({ x: 300, y: 300 });
    owner.cy.getElementById('target').position({ x: 600, y: 300 });
    owner.cy.getElementById('obstacle').position({ x: -500, y: -500 });
    owner.cy.getElementById('end').position({ x: 800, y: 540 });
    owner.cy.fit(undefined, 120);
  });
  await settleRouting(page);

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const baseline = await routeState(page);
  expect(baseline).toMatchObject({
    sourceSide: 'right', targetSide: 'left',
    committed: { curve: 'unbundled-bezier', controlCount: 2 },
  });
  expect(baseline.sourceTangent).toBeGreaterThan(0);
  expect(baseline.targetTangent).toBeGreaterThan(0);

  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'untouched.graphml' }));
  await settleRouting(page);
  await page.evaluate(({ firstId, secondId }) => {
    // Activation first captures the second document's renderer into its own record. Snapshot after
    // that established hand-off so later differences can only come from the first canvas's drag.
    window.ravenroot.activateDocument(firstId);
    const secondOwner = window.ravenroot.workspace.find(secondId);
    window.__issue466SecondCache = secondOwner.cytoEdgeRouteCache;
    window.__issue466SecondSnapshot = JSON.stringify({
      graph: secondOwner.graph,
      positions: Object.fromEntries(secondOwner.cy.nodes().map(node => [node.id(), node.position()])),
      routes: Object.fromEntries(secondOwner.cytoEdgeRouteCache),
      history: secondOwner.history.state(),
    });
  }, { firstId: first, secondId: second });
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(first);

  const unchangedBefore = await page.evaluate(() => Object.fromEntries(
    window.ravenroot.activeDocument().cy.nodes().filter(node => node.id() !== 'target')
      .map(node => [node.id(), node.position()])));
  for (const scenario of [
    { name: 'vertical', position: { x: 300, y: 650 }, sides: ['bottom', 'top'] },
    { name: 'horizontal-left', position: { x: 0, y: 300 }, sides: ['left', 'right'] },
    { name: 'diagonal', position: { x: 540, y: 540 }, sides: ['right', 'left'] },
  ]) {
    await dragNodeTo(page, 'target', scenario.position);
    const state = await routeState(page);
    expect([state.sourceSide, state.targetSide], scenario.name).toEqual(scenario.sides);
    expect(state.sourceTangent, `${scenario.name} source tangent`).toBeGreaterThan(0);
    expect(state.targetTangent, `${scenario.name} target tangent`).toBeGreaterThan(0);
    expect(state.committed).toMatchObject({
      curve: 'unbundled-bezier',
      sourceEndpoint: state.route.sourceEndpoint,
      targetEndpoint: state.route.targetEndpoint,
      controlCount: 2,
    });
  }

  expect(await page.evaluate(() => Object.fromEntries(
    window.ravenroot.activeDocument().cy.nodes().filter(node => node.id() !== 'target')
      .map(node => [node.id(), node.position()])))).toEqual(unchangedBefore);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().history.state())).toMatchObject({
    depth: 3, undoLabel: 'Move target', canUndo: true,
  });

  await page.locator('#btn-undo').click();
  await settleRouting(page);
  expect(await routeState(page)).toMatchObject({ sourceSide: 'left', targetSide: 'right' });
  await page.locator('#btn-redo').click();
  await settleRouting(page);
  expect(await routeState(page)).toMatchObject({ sourceSide: 'right', targetSide: 'left' });

  expect(await page.evaluate(secondId => {
    const owner = window.ravenroot.workspace.find(secondId);
    return {
      sameCache: owner.cytoEdgeRouteCache === window.__issue466SecondCache,
      sameState: JSON.stringify({
        graph: owner.graph,
        positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
        routes: Object.fromEntries(owner.cytoEdgeRouteCache),
        history: owner.history.state(),
      }) === window.__issue466SecondSnapshot,
    };
  }, second)).toEqual({ sameCache: true, sameState: true });

  await page.locator('#btn-modify').click();
  // The history step intentionally leaves its moved target selected. Selected nodes move in
  // Editing, so edge authoring must begin from an explicitly unselected source.
  await page.evaluate(() => { window.ravenroot.activeDocument().cy.elements().unselect(); });
  const authoringPoints = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const container = owner.cy.container().getBoundingClientRect();
    const point = id => {
      const rendered = owner.cy.getElementById(id).renderedPosition();
      return { x: container.x + rendered.x, y: container.y + rendered.y };
    };
    return { source: point('target'), target: point('end') };
  });
  await page.mouse.move(authoringPoints.source.x, authoringPoints.source.y);
  await page.mouse.down();
  await page.mouse.move(authoringPoints.target.x, authoringPoints.target.y, { steps: 18 });
  await expect(page.locator('#edge-ghost')).toHaveClass(/on/);
  const preview = await page.locator('#edge-ghost .ghost-edge').evaluate(path => {
    const length = path.getTotalLength();
    const at = ratio => {
      const point = path.getPointAtLength(length * ratio);
      return { x: point.x, y: point.y };
    };
    return { source: at(0), midpoint: at(0.5), target: at(1) };
  });
  await page.mouse.up();
  const committed = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const model = owner.graph.edges.find(edge => edge.source === 'target' && edge.target === 'end');
    const edge = owner.cy.getElementById(model.id);
    return {
      id: model.id,
      source: edge.renderedSourceEndpoint(),
      midpoint: edge.renderedMidpoint(),
      target: edge.renderedTargetEndpoint(),
      curve: edge.style('curve-style'),
      cached: owner.cytoEdgeRouteCache.has(model.id),
    };
  });
  const distance = (left, right) => Math.hypot(left.x - right.x, left.y - right.y);
  expect(committed).toMatchObject({ curve: 'unbundled-bezier', cached: true });
  expect(distance(preview.source, committed.source)).toBeLessThanOrEqual(2);
  expect(distance(preview.midpoint, committed.midpoint)).toBeLessThanOrEqual(2);
  expect(distance(preview.target, committed.target)).toBeLessThanOrEqual(2);

  const downloadPromise = page.waitForEvent('download');
  await page.locator('#btn-export').click();
  const exported = await readFile(await (await downloadPromise).path(), 'utf8');
  expect(exported).not.toMatch(/(?:source|target)?anchors?/i);
  expect(exported).not.toMatch(/obstacleDependency/i);
});

test('Cyto route cache and pending frame remain isolated per document ownership', async ({ page }) => {
  await page.goto('/');
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await settleRouting(page);

  await page.evaluate(({ first: firstId, second: secondId }) => {
    const firstOwner = window.ravenroot.workspace.find(firstId);
    const secondOwner = window.ravenroot.workspace.find(secondId);
    window.__secondCytoCache = secondOwner.cytoEdgeRouteCache;
    window.__secondCytoEntries = JSON.stringify(Object.fromEntries(secondOwner.cytoEdgeRouteCache));
    firstOwner.cy.getElementById('start').position({ x: 160, y: 280 });
  }, { first, second });
  await settleRouting(page);

  expect(await page.evaluate(({ first: firstId, second: secondId }) => {
    const firstOwner = window.ravenroot.workspace.find(firstId);
    const secondOwner = window.ravenroot.workspace.find(secondId);
    return {
      active: window.ravenroot.workspace.activeId,
      distinctCaches: firstOwner.cytoEdgeRouteCache !== secondOwner.cytoEdgeRouteCache,
      secondIdentityStable: secondOwner.cytoEdgeRouteCache === window.__secondCytoCache,
      secondEntriesStable: JSON.stringify(Object.fromEntries(secondOwner.cytoEdgeRouteCache))
        === window.__secondCytoEntries,
      firstSettled: firstOwner.cytoEdgeGeometryRaf === null && firstOwner.cytoEdgeRouteDirtyNodes.size === 0,
      secondSettled: secondOwner.cytoEdgeGeometryRaf === null && secondOwner.cytoEdgeRouteDirtyNodes.size === 0,
    };
  }, { first, second })).toEqual({
    active: second, distinctCaches: true, secondIdentityStable: true,
    secondEntriesStable: true, firstSettled: true, secondSettled: true,
  });
});
