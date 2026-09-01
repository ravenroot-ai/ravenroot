import { describe, expect, it } from 'vitest';

import {
  resolveViewerRoutesWithinBudget,
  VIEWER_DETAILED_ROUTE_PAIR_BUDGET,
  VIEWER_DETAILED_ROUTE_WORK_BUDGET,
  viewerSupportsElastic,
  viewerRouteStrategy,
} from '../src/viewer-route-budget.js';

function largeProjection() {
  const nodes = Array.from({ length: 2_000 }, (_, index) => ({
    id: `n-${index}`,
    x: (index % 50) * 100,
    y: Math.floor(index / 50) * 80,
    width: 64,
    height: 40,
  }));
  const edges = Array.from({ length: 5_000 }, (_, index) => ({
    id: `e-${index}`,
    source: nodes[index % nodes.length].id,
    target: nodes[(index * 17 + 1) % nodes.length].id,
    label: '',
  }));
  return { nodes, edges };
}

function parallelProjection(edgeCount) {
  const nodes = [
    { id: 'source', x: 0, y: 0, width: 64, height: 40 },
    { id: 'target', x: 300, y: 0, width: 64, height: 40 },
  ];
  const edges = Array.from({ length: edgeCount }, (_, index) => ({
    id: `parallel-${index}`, source: 'source', target: 'target', label: '',
  }));
  return { nodes, edges };
}

describe('viewer route work budget', () => {
  it('retains detailed Cyto routing for ordinary graphs', () => {
    expect(viewerRouteStrategy(10, 20)).toBe('detailed');
    const plan = resolveViewerRoutesWithinBudget({
      nodes: [
        { id: 'a', x: 0, y: 0, width: 64, height: 40 },
        { id: 'b', x: 200, y: 0, width: 64, height: 40 },
      ],
      edges: [{ id: 'edge', source: 'a', target: 'b', label: '' }],
    });
    expect(plan.strategy).toBe('detailed');
    expect(plan.routes.get('edge')).toBeDefined();
  });

  it('selects the bounded route without invoking the superlinear router at the maximum fixture', () => {
    const projection = largeProjection();
    const started = performance.now();
    const plan = resolveViewerRoutesWithinBudget(projection);
    const elapsed = performance.now() - started;

    expect(plan).toEqual({ strategy: 'simple', routes: null });
    expect(2_000 * 5_000).toBeGreaterThan(VIEWER_DETAILED_ROUTE_WORK_BUDGET);
    expect(elapsed).toBeLessThan(250);
  });

  it('validates counts without overflowing the work calculation', () => {
    expect(viewerRouteStrategy(Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER)).toBe('simple');
    expect(() => viewerRouteStrategy(-1, 1)).toThrow('Invalid viewer route node count.');
  });

  it.each([
    [200, 'detailed', 2_500],
    [500, 'simple', 250],
    [1_000, 'simple', 250],
    [5_000, 'simple', 250],
  ])('bounds the two-node/%i-parallel-edge production planner', (edgeCount, strategy, maximumMs) => {
    const started = performance.now();
    const plan = resolveViewerRoutesWithinBudget(parallelProjection(edgeCount));
    const elapsed = performance.now() - started;

    expect(plan.strategy).toBe(strategy);
    expect(elapsed).toBeLessThan(maximumMs);
    if (strategy === 'simple') expect(plan.routes).toBeNull();
    else expect(plan.routes.size).toBe(edgeCount);
  });

  it('accounts for edge-pair work independently of node count', () => {
    expect(200 * 200).toBe(VIEWER_DETAILED_ROUTE_PAIR_BUDGET);
    expect(viewerRouteStrategy(2, 200)).toBe('detailed');
    expect(viewerRouteStrategy(2, 201)).toBe('simple');
  });

  it('keeps Elastic available for normal fixtures and disables it at the maximum', () => {
    expect(viewerSupportsElastic(3, 2)).toBe(true);
    expect(viewerSupportsElastic(2_000, 5_000)).toBe(false);
    expect(viewerSupportsElastic(501, 1)).toBe(false);
    expect(viewerSupportsElastic(1, 1_001)).toBe(false);
  });
});
