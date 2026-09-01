import { describe, expect, it } from 'vitest';
import {
  rendererEdgePath,
  rendererEdgeRouteToRendered,
  resolveRendererEdgeRoute,
  resolveRendererEdgeRoutes,
} from '../src/renderer-edge-route.js';

const SOURCE = { id: 'source', x: 100, y: 100, width: 80, height: 40 };
const FORWARD = { id: 'target', x: 320, y: 160, width: 100, height: 60 };
const BACKWARD = { id: 'target', x: -120, y: 40, width: 60, height: 50 };

describe('renderer edge route resolver', () => {
  it.each([
    ['n8n2', FORWARD, 'round-taxi', 'rightward'],
    ['n8n2', BACKWARD, 'round-taxi', 'leftward'],
    ['n8n3', FORWARD, 'round-segments', 'rightward'],
    ['n8n3', BACKWARD, 'round-segments', 'leftward'],
    ['n8n4', FORWARD, 'unbundled-bezier', 'rightward'],
    ['n8n4', BACKWARD, 'round-segments', 'leftward'],
  ])('classifies %s routes from model node geometry', (mode, target, family, direction) => {
    const route = resolveRendererEdgeRoute({ mode, source: SOURCE, target });
    expect(route).toMatchObject({ coordinateSpace: 'model', family, direction });
    expect(route.start.x).toBe(direction === 'rightward' ? 140 : 60);
    expect(route.end.x).toBe(direction === 'rightward' ? 270 : -90);
  });

  it('uses the 20 model-pixel port gap for N8N4 independent of projection', () => {
    const source = { ...SOURCE, x: 0, width: 80 };
    const below = { ...FORWARD, x: 99, y: 100, width: 80 };
    const boundary = { ...below, x: 100 };
    const belowRoute = resolveRendererEdgeRoute({ mode: 'n8n4', source, target: below });
    const boundaryRoute = resolveRendererEdgeRoute({ mode: 'n8n4', source, target: boundary });
    expect(belowRoute.end.x - belowRoute.start.x).toBe(19);
    expect(belowRoute.family).toBe('round-segments');
    expect(boundaryRoute.end.x - boundaryRoute.start.x).toBe(20);
    expect(boundaryRoute.family).toBe('unbundled-bezier');
    for (const zoom of [0.5, 1, 2]) {
      const projected = rendererEdgeRouteToRendered(boundaryRoute, { zoom, pan: { x: 17, y: -9 } });
      expect(projected).toMatchObject({ family: 'unbundled-bezier', turn: boundaryRoute.turn * zoom });
      expect(projected.end.x - projected.start.x).toBe(20 * zoom);
    }
  });

  it('allocates a prospective N8N3 edge with the exact post-insert sibling routes', () => {
    const nodes = [SOURCE, FORWARD];
    const one = resolveRendererEdgeRoutes({
      mode: 'n8n3', nodes, edges: [{ id: 'edge-a', source: 'source', target: 'target' }],
    });
    const prospective = resolveRendererEdgeRoutes({
      mode: 'n8n3', nodes,
      edges: [
        { id: 'edge-a', source: 'source', target: 'target' },
        { id: 'edge-b', source: 'source', target: 'target' },
      ],
    });
    expect(prospective.get('edge-a')).not.toEqual(one.get('edge-a'));
    expect(prospective.get('edge-a')).toMatchObject({
      laneOffset: -18, sourcePortOffset: -9, targetPortOffset: -9,
    });
    expect(prospective.get('edge-b')).toMatchObject({
      laneOffset: 18, sourcePortOffset: 9, targetPortOffset: 9,
    });
    expect(resolveRendererEdgeRoutes({
      mode: 'n8n3', nodes,
      edges: [
        { id: 'edge-a', source: 'source', target: 'target' },
        { id: 'edge-b', source: 'source', target: 'target' },
      ],
    })).toEqual(prospective);
  });

  it('uses the same prospective allocation for backward N8N4 segment siblings', () => {
    const nodes = [SOURCE, BACKWARD];
    const routes = resolveRendererEdgeRoutes({
      mode: 'n8n4', nodes,
      edges: [
        { id: 'edge-a', source: 'source', target: 'target' },
        { id: 'edge-b', source: 'source', target: 'target' },
      ],
    });
    expect([...routes.values()].map(route => route.family)).toEqual(['round-segments', 'round-segments']);
    expect(routes.get('edge-a').sourcePortOffset).toBe(-9);
    expect(routes.get('edge-b').sourcePortOffset).toBe(9);
  });

  it('keeps Hierarchical routes on deterministic east-output and west-input ports', () => {
    const nodes = [SOURCE, FORWARD];
    const edges = [
      { id: 'edge-b', source: 'source', target: 'target' },
      { id: 'edge-a', source: 'source', target: 'target' },
      { id: 'edge-c', source: 'source', target: 'target' },
    ];
    const routes = resolveRendererEdgeRoutes({ mode: 'hierarchical', nodes, edges });
    expect(['edge-a', 'edge-b', 'edge-c'].map(id => routes.get(id)).map(route => ({
      family: route.family,
      sourceSide: route.sourceSide,
      targetSide: route.targetSide,
      sourcePortOffset: route.sourcePortOffset,
      targetPortOffset: route.targetPortOffset,
    }))).toEqual([
      { family: 'round-segments', sourceSide: 'right', targetSide: 'left', sourcePortOffset: -18, targetPortOffset: -18 },
      { family: 'round-segments', sourceSide: 'right', targetSide: 'left', sourcePortOffset: 0, targetPortOffset: 0 },
      { family: 'round-segments', sourceSide: 'right', targetSide: 'left', sourcePortOffset: 18, targetPortOffset: 18 },
    ]);
    const reversed = resolveRendererEdgeRoutes({ mode: 'hierarchical', nodes, edges: [...edges].reverse() });
    for (const id of ['edge-a', 'edge-b', 'edge-c']) expect(reversed.get(id)).toEqual(routes.get(id));
  });

  it('transforms the completed descriptor rather than resolving in rendered space', () => {
    const model = resolveRendererEdgeRoute({
      mode: 'n8n3', source: SOURCE, target: FORWARD,
      laneOffset: 18, sourcePortOffset: 12, targetPortOffset: -40,
    });
    const rendered = rendererEdgeRouteToRendered(model, { zoom: 2, pan: { x: 7, y: 11 } });
    expect(model).toMatchObject({
      turn: 64, radius: 36, start: { x: 140, y: 112 }, end: { x: 270, y: 130 },
      sourceEndpoint: '40px 12px', targetEndpoint: '-50px -30px',
    });
    expect(rendered).toMatchObject({
      coordinateSpace: 'rendered', turn: 128, radius: 72,
      start: { x: 287, y: 235 }, end: { x: 547, y: 271 },
      sourceEndpoint: model.sourceEndpoint, targetEndpoint: model.targetEndpoint,
    });
  });

  it('serializes rounded and unbundled descriptors without recalculating geometry', () => {
    const rounded = resolveRendererEdgeRoute({ mode: 'n8n2', source: SOURCE, target: FORWARD });
    const bezier = resolveRendererEdgeRoute({ mode: 'n8n4', source: SOURCE, target: FORWARD });
    expect(rendererEdgePath(rounded)).toMatch(/^M 140 100 L .* Q .* L 270 160$/);
    expect(rendererEdgePath(bezier)).toBe(
      `M ${bezier.start.x} ${bezier.start.y} Q ${bezier.points[0].x} ${bezier.points[0].y} `
      + `${bezier.midpoint.x} ${bezier.midpoint.y} Q ${bezier.points[1].x} ${bezier.points[1].y} `
      + `${bezier.end.x} ${bezier.end.y}`,
    );
  });
});
