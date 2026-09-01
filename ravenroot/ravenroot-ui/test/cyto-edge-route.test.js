import { describe, expect, it } from 'vitest';
import {
  rendererEdgePath,
  rendererEdgeRouteToRendered,
  resolveCytoEdgeRouteUpdate,
  resolveRendererEdgeRoute,
  resolveRendererEdgeRoutes,
} from '../src/renderer-edge-route.js';

const SOURCE = { id: 'source', x: 100, y: 100, width: 80, height: 40 };

const plain = routes => [...routes].map(([id, route]) => [id, route]);

const openBox = (node, margin = 0) => ({
  left: node.x - node.width / 2 - margin,
  right: node.x + node.width / 2 + margin,
  top: node.y - node.height / 2 - margin,
  bottom: node.y + node.height / 2 + margin,
});

function independentlyEntersOpenBox(route, box, steps = 20_000) {
  const quadratic = (start, control, end, t) => {
    const inverse = 1 - t;
    return {
      x: inverse * inverse * start.x + 2 * inverse * t * control.x + t * t * end.x,
      y: inverse * inverse * start.y + 2 * inverse * t * control.y + t * t * end.y,
    };
  };
  const inside = point => point.x > box.left && point.x < box.right
    && point.y > box.top && point.y < box.bottom;
  for (let step = 1; step < steps; step += 1) {
    const t = step / steps;
    if (inside(quadratic(route.start, route.points[0], route.midpoint, t))
        || inside(quadratic(route.midpoint, route.points[1], route.end, t))) return true;
  }
  return false;
}

describe('Cyto automatic edge anchors', () => {
  it.each([
    ['rightward', { id: 'target', x: 360, y: 100, width: 80, height: 40 }, 'right', 'left'],
    ['leftward', { id: 'target', x: -160, y: 100, width: 80, height: 40 }, 'left', 'right'],
    ['downward', { id: 'target', x: 100, y: 360, width: 80, height: 40 }, 'bottom', 'top'],
    ['upward', { id: 'target', x: 100, y: -160, width: 80, height: 40 }, 'top', 'bottom'],
  ])('chooses canonical %s anchors with outward controls', (_direction, target, sourceSide, targetSide) => {
    const route = resolveRendererEdgeRoute({ mode: 'cyto', source: SOURCE, target });
    expect(route).toMatchObject({
      coordinateSpace: 'model', family: 'unbundled-bezier', sourceSide, targetSide,
      candidateEvaluations: 16,
    });
    const normals = {
      right: [1, 0], left: [-1, 0], bottom: [0, 1], top: [0, -1],
    };
    expect([
      Math.sign(route.points[0].x - route.start.x),
      Math.sign(route.points[0].y - route.start.y),
    ]).toEqual(normals[sourceSide]);
    expect([
      Math.sign(route.points[1].x - route.end.x),
      Math.sign(route.points[1].y - route.end.y),
    ]).toEqual(normals[targetSide]);
    expect(rendererEdgePath(route)).toMatch(/^M .* Q .* Q .*$/);
  });

  it('avoids an intervening node when another cardinal pair is clear', () => {
    const source = { id: 's', x: 100, y: 100, width: 80, height: 80 };
    const obstacle = { id: 'o', x: 240, y: 100, width: 80, height: 80 };
    const target = { id: 't', x: 380, y: 100, width: 80, height: 80 };
    const route = resolveRendererEdgeRoutes({
      mode: 'cyto', nodes: [target, obstacle, source], edges: [{ id: 'e', source: 's', target: 't' }],
    }).get('e');
    expect(route.score.slice(0, 2)).toEqual([0, 0]);
    expect([route.sourceSide, route.targetSide]).not.toEqual(['right', 'left']);
  });

  it('continuously clears the obstacle fixture independent of its score', () => {
    const source = { id: 'source', x: 0, y: 0, width: 80, height: 40 };
    const target = { id: 'target', x: 400, y: 100, width: 80, height: 40 };
    const obstacle = { id: 'obstacle', x: 296, y: 64, width: 80, height: 80 };
    const edge = { id: 'route', source: 'source', target: 'target' };
    const nodes = [source, target, obstacle];
    const realObstacle = openBox(obstacle);
    const expandedObstacle = openBox(obstacle, 8);

    // The rejected candidate enters the box between its coarse samples. Keeping that control proves
    // this test's evaluator can detect the intersection without reading the route score.
    const rejectedCandidate = resolveRendererEdgeRoute({
      mode: 'cyto', source, target, nodes,
      edge: {
        id: 'rejected', sourceAnchor: { side: 'right', pinned: true },
        targetAnchor: { side: 'bottom', pinned: true },
      },
    });
    expect(independentlyEntersOpenBox(rejectedCandidate, realObstacle)).toBe(true);
    const rejectedOnSecondQuadratic = resolveRendererEdgeRoute({
      mode: 'cyto', source: target, target: source, nodes,
      edge: {
        id: 'rejected-reverse', sourceAnchor: { side: 'bottom', pinned: true },
        targetAnchor: { side: 'right', pinned: true },
      },
    });
    expect(independentlyEntersOpenBox(rejectedOnSecondQuadratic, realObstacle)).toBe(true);

    const route = resolveRendererEdgeRoutes({ mode: 'cyto', nodes, edges: [edge] }).get(edge.id);
    expect(independentlyEntersOpenBox(route, realObstacle)).toBe(false);
    expect(independentlyEntersOpenBox(route, expandedObstacle)).toBe(false);
    expect(independentlyEntersOpenBox(route, openBox(source))).toBe(false);
    expect(independentlyEntersOpenBox(route, openBox(target))).toBe(false);
    const reverseRoute = resolveRendererEdgeRoutes({
      mode: 'cyto', nodes, edges: [{ id: 'reverse', source: 'target', target: 'source' }],
    }).get('reverse');
    expect(independentlyEntersOpenBox(reverseRoute, realObstacle)).toBe(false);
    expect(independentlyEntersOpenBox(reverseRoute, expandedObstacle)).toBe(false);

    const distantNodes = [
      ...nodes,
      { id: 'distant-source', x: -500, y: -500, width: 40, height: 40 },
      { id: 'distant-target', x: -300, y: -500, width: 40, height: 40 },
    ];
    const distantEdge = { id: 'z-distant', source: 'distant-source', target: 'distant-target' };
    const forward = resolveRendererEdgeRoutes({
      mode: 'cyto', nodes: distantNodes, edges: [edge, distantEdge],
    });
    const permuted = resolveRendererEdgeRoutes({
      mode: 'cyto', nodes: [...distantNodes].reverse(), edges: [distantEdge, edge],
    });
    expect(plain(permuted)).toEqual(plain(forward));
    expect(permuted.get(edge.id)).toEqual(forward.get(edge.id));
  });

  it('invalidates a non-incident route when a standalone obstacle enters and leaves its corridor', () => {
    const source = { id: 's', x: 0, y: 0, width: 80, height: 40 };
    const target = { id: 't', x: 400, y: 0, width: 80, height: 40 };
    const obstacleFar = { id: 'o', x: 200, y: 300, width: 80, height: 80 };
    const obstacleBlocking = { ...obstacleFar, y: 0 };
    const otherSource = { id: 'u', x: -700, y: -500, width: 60, height: 40 };
    const otherTarget = { id: 'v', x: -400, y: -500, width: 60, height: 40 };
    const edges = [
      { id: 'route', source: 's', target: 't' },
      { id: 'unrelated', source: 'u', target: 'v' },
    ];
    const farNodes = [source, target, obstacleFar, otherSource, otherTarget];
    const blockingNodes = [source, target, obstacleBlocking, otherSource, otherTarget];
    const initial = resolveRendererEdgeRoutes({ mode: 'cyto', nodes: farNodes, edges });
    const direct = initial.get('route');
    const unrelated = initial.get('unrelated');
    expect(direct).toMatchObject({
      sourceSide: 'right', targetSide: 'left', obstacleDependencyIds: [],
    });
    expect(independentlyEntersOpenBox(direct, openBox(obstacleBlocking, 8))).toBe(true);

    const blocked = resolveCytoEdgeRouteUpdate({
      nodes: blockingNodes, edges, previousRoutes: initial, dirtyNodeIds: ['o'],
    });
    expect(blocked).toMatchObject({
      routedEdgeIds: ['route'], candidateEvaluations: 16, interestEvaluations: 2,
    });
    expect(blocked.routes.get('route')).toMatchObject({
      sourceSide: 'bottom', targetSide: 'bottom', obstacleDependencyIds: ['o'],
    });
    expect(independentlyEntersOpenBox(
      blocked.routes.get('route'), openBox(obstacleBlocking, 8),
    )).toBe(false);
    expect(blocked.routes.get('unrelated')).toBe(unrelated);

    const permuted = resolveCytoEdgeRouteUpdate({
      nodes: [...blockingNodes].reverse(), edges: [...edges].reverse(),
      previousRoutes: initial, dirtyNodeIds: ['o'],
    });
    expect(plain(permuted.routes)).toEqual(plain(blocked.routes));
    expect(permuted.routedEdgeIds).toEqual(blocked.routedEdgeIds);

    const restored = resolveCytoEdgeRouteUpdate({
      nodes: farNodes, edges, previousRoutes: blocked.routes, dirtyNodeIds: ['o'],
    });
    expect(restored).toMatchObject({
      routedEdgeIds: ['route'], candidateEvaluations: 16, interestEvaluations: 2,
    });
    expect(restored.routes.get('route')).toMatchObject({
      sourceSide: 'right', targetSide: 'left', obstacleDependencyIds: [],
    });
    expect(restored.routes.get('route')).toEqual(direct);
    expect(restored.routes.get('unrelated')).toBe(unrelated);

    const legacy = new Map([...initial].map(([id, route]) => {
      const { obstacleDependencyIds: ignored, ...descriptor } = route;
      return [id, descriptor];
    }));
    expect(resolveCytoEdgeRouteUpdate({
      nodes: blockingNodes, edges, previousRoutes: legacy, dirtyNodeIds: ['o'],
    })).toMatchObject({
      routedEdgeIds: ['route', 'unrelated'], candidateEvaluations: 32, interestEvaluations: 2,
    });
  });

  it('tracks a standalone node label as a transient route dependency', () => {
    const source = { id: 's', x: 0, y: 0, width: 80, height: 40 };
    const target = { id: 't', x: 400, y: 0, width: 80, height: 40 };
    const obstacle = {
      id: 'o', x: 200, y: 300, width: 80, height: 80,
      labelBounds: { x: 200, y: 0, width: 100, height: 24 },
    };
    const edge = { id: 'route', source: 's', target: 't' };
    const blocked = resolveRendererEdgeRoutes({ mode: 'cyto', nodes: [source, target, obstacle], edges: [edge] });
    expect(blocked.get('route').obstacleDependencyIds).toEqual(['o']);

    const moved = { ...obstacle, labelBounds: { ...obstacle.labelBounds, y: 300 } };
    const restored = resolveCytoEdgeRouteUpdate({
      nodes: [source, target, moved], edges: [edge], previousRoutes: blocked, dirtyNodeIds: ['o'],
    });
    expect(restored.routedEdgeIds).toEqual(['route']);
    expect(restored.routes.get('route')).toMatchObject({
      sourceSide: 'right', targetSide: 'left', obstacleDependencyIds: [],
    });
  });

  it('separates parallel and reciprocal Cyto edges deterministically', () => {
    const target = { id: 'target', x: 360, y: 100, width: 80, height: 40 };
    const edges = [
      { id: 'a', source: 'source', target: 'target' },
      { id: 'b', source: 'source', target: 'target' },
      { id: 'c', source: 'target', target: 'source' },
      { id: 'd', source: 'source', target: 'target' },
    ];
    const routes = resolveRendererEdgeRoutes({ mode: 'cyto', nodes: [SOURCE, target], edges });
    const allocations = [...routes.values()].map(route =>
      `${route.sourceEndpoint}|${route.targetEndpoint}|${route.laneOffset}`);
    expect(new Set(allocations).size).toBe(4);
    expect(plain(resolveRendererEdgeRoutes({
      mode: 'cyto', nodes: [target, SOURCE], edges: [...edges].reverse(),
    }))).toEqual(plain(routes));
  });

  it('keeps repeated runs and rendered projection deterministic', () => {
    const target = { id: 'target', x: 160, y: 420, width: 80, height: 40 };
    const first = resolveRendererEdgeRoute({ mode: 'cyto', source: SOURCE, target });
    const second = resolveRendererEdgeRoute({ mode: 'cyto', source: SOURCE, target });
    expect(second).toEqual(first);
    for (const zoom of [0.5, 1, 2]) {
      const rendered = rendererEdgeRouteToRendered(first, { zoom, pan: { x: 13, y: -7 } });
      expect(rendered).toMatchObject({
        sourceSide: first.sourceSide, targetSide: first.targetSide,
        sourceEndpoint: first.sourceEndpoint, targetEndpoint: first.targetEndpoint,
      });
    }
  });

  it('honours optional future pins without mutating or serializing automatic anchors', () => {
    const edge = {
      id: 'pinned', sourceAnchor: { side: 'top', pinned: true },
      targetAnchor: { side: 'right', pinned: true },
    };
    const before = JSON.stringify(edge);
    expect(resolveRendererEdgeRoute({
      mode: 'cyto', source: SOURCE,
      target: { id: 'target', x: 360, y: 100, width: 80, height: 40 }, edge,
    })).toMatchObject({ sourceSide: 'top', targetSide: 'right', pinned: true, candidateEvaluations: 1 });
    expect(JSON.stringify(edge)).toBe(before);

    const automatic = { id: 'automatic', source: 'source', target: 'target' };
    resolveRendererEdgeRoutes({
      mode: 'cyto', nodes: [SOURCE, { id: 'target', x: 360, y: 100, width: 80, height: 40 }],
      edges: [automatic],
    });
    expect(automatic).toEqual({ id: 'automatic', source: 'source', target: 'target' });
  });

  it('recalculates geometrically interested routes and preserves unrelated object identity', () => {
    const nodes = [
      { id: 'a', x: 0, y: 0, width: 60, height: 40 },
      { id: 'b', x: 200, y: 0, width: 60, height: 40 },
      { id: 'c', x: 0, y: 200, width: 60, height: 40 },
      { id: 'd', x: 200, y: 200, width: 60, height: 40 },
    ];
    const edges = [{ id: 'ab', source: 'a', target: 'b' }, { id: 'cd', source: 'c', target: 'd' }];
    const previousRoutes = resolveRendererEdgeRoutes({ mode: 'cyto', nodes, edges });
    const unrelated = previousRoutes.get('cd');
    const update = resolveCytoEdgeRouteUpdate({
      nodes: nodes.map(node => node.id === 'a' ? { ...node, y: 70 } : node),
      edges, previousRoutes, dirtyNodeIds: ['a'],
    });
    expect(update).toMatchObject({ routedEdgeIds: ['ab'], candidateEvaluations: 16 });
    expect(update.routes.get('cd')).toBe(unrelated);
    expect(update.routes.get('ab')).not.toEqual(previousRoutes.get('ab'));
  });

  // The timings are reporting evidence, while the discovery and candidate-count assertions are the
  // gates. Parallel full-suite contention can exceed Vitest's inherited 5 s without changing either
  // contract.
  it('keeps interested updates operation-bounded and reports local performance evidence', () => {
    const routedNodes = Array.from({ length: 39 }, (_, index) => ({
      id: `n-${String(index).padStart(2, '0')}`,
      x: (index % 8) * 180, y: Math.floor(index / 8) * 140, width: 100, height: 56,
    }));
    const obstacleFar = { id: 'standalone', x: 10_000, y: 10_000, width: 100, height: 100 };
    const obstacleBlocking = { ...obstacleFar, x: 630, y: 280 };
    const edges = Array.from({ length: 80 }, (_, index) => ({
      id: `e-${String(index).padStart(2, '0')}`,
      source: routedNodes[index % routedNodes.length].id,
      target: routedNodes[(index * 7 + 9) % routedNodes.length].id,
    })).filter(edge => edge.source !== edge.target);
    let nodes = [...routedNodes, obstacleFar];
    let routes = resolveRendererEdgeRoutes({ mode: 'cyto', nodes, edges });
    const incidentSamples = [];
    const obstacleSamples = [];
    let maximumObstacleFanout = 0;
    for (let run = 0; run < 125; run += 1) {
      let started = performance.now();
      const update = resolveCytoEdgeRouteUpdate({ nodes, edges, previousRoutes: routes, dirtyNodeIds: ['n-00'] });
      incidentSamples.push(performance.now() - started);
      expect(update.candidateEvaluations).toBeLessThanOrEqual(update.routedEdgeIds.length * 16);
      expect(update.interestEvaluations).toBeLessThanOrEqual(edges.length);
      routes = update.routes;

      nodes = [...routedNodes, run % 2 === 0 ? obstacleBlocking : obstacleFar];
      started = performance.now();
      const obstacleUpdate = resolveCytoEdgeRouteUpdate({
        nodes, edges, previousRoutes: routes, dirtyNodeIds: ['standalone'],
      });
      obstacleSamples.push(performance.now() - started);
      expect(obstacleUpdate.candidateEvaluations)
        .toBeLessThanOrEqual(obstacleUpdate.routedEdgeIds.length * 16);
      expect(obstacleUpdate.interestEvaluations).toBeLessThanOrEqual(edges.length);
      maximumObstacleFanout = Math.max(maximumObstacleFanout, obstacleUpdate.routedEdgeIds.length);
      routes = obstacleUpdate.routes;
    }
    const percentile = (samples, quantile) => {
      const measured = samples.slice(5).sort((a, b) => a - b);
      return measured[Math.floor(measured.length * quantile)];
    };
    const incidentP50 = percentile(incidentSamples, 0.5);
    const incidentP95 = percentile(incidentSamples, 0.95);
    const obstacleP50 = percentile(obstacleSamples, 0.5);
    const obstacleP95 = percentile(obstacleSamples, 0.95);
    console.info(
      `Cyto interested routing benchmark: incident p50=${incidentP50.toFixed(2)}ms p95=${incidentP95.toFixed(2)}ms; `
      + `standalone obstacle p50=${obstacleP50.toFixed(2)}ms p95=${obstacleP95.toFixed(2)}ms `
      + `fanout=${maximumObstacleFanout}`,
    );
    expect([incidentP50, incidentP95, obstacleP50, obstacleP95].every(Number.isFinite)).toBe(true);
    expect(maximumObstacleFanout).toBeGreaterThan(0);
  }, 15_000);

  it('leaves self-loops to the established custom loop renderer', () => {
    expect(resolveRendererEdgeRoutes({
      mode: 'cyto', nodes: [SOURCE], edges: [{ id: 'loop', source: 'source', target: 'source' }],
    }).has('loop')).toBe(false);
  });
});
