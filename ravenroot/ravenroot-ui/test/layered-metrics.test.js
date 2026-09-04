import { describe, expect, it } from 'vitest';

import {
  backEdgesInsideBand, bodyOf, countCrossings, edgesThroughBoxes, extentOf, geometryColumns, labelBoxOf,
  labelOverlaps, layerDiscreteness, segmentIntersectsBox, segmentsCross, sharedRuns, structuralLayering,
} from '../src/layered-metrics.js';

const line = (id, source, target, ...points) => ({ id, source, target, points });

describe('layered metrics', () => {
  it('counts only proper crossings, never touching ends or collinear runs', () => {
    expect(segmentsCross({ x: 0, y: 0 }, { x: 10, y: 10 }, { x: 0, y: 10 }, { x: 10, y: 0 })).toBe(true);
    expect(segmentsCross({ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 })).toBe(false);
    expect(segmentsCross({ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 5, y: 0 }, { x: 15, y: 0 })).toBe(false);
    expect(countCrossings([
      line('a', 's', 't', { x: 0, y: 0 }, { x: 10, y: 10 }),
      line('b', 'u', 'v', { x: 0, y: 10 }, { x: 10, y: 0 }),
      line('c', 'w', 'z', { x: 0, y: 20 }, { x: 10, y: 20 }),
    ])).toBe(1);
  });

  it('finds overlapping labels and leaves separated ones alone', () => {
    expect(labelOverlaps([
      { id: 'a', label: { left: 0, top: 0, right: 100, bottom: 20 } },
      { id: 'b', label: { left: 90, top: 10, right: 190, bottom: 30 } },
      { id: 'c', label: { left: 100, top: 0, right: 200, bottom: 20 } },
      { id: 'd', label: null },
    ])).toEqual([['a', 'b'], ['b', 'c']]);
  });

  it('detects an edge entering a box but not one grazing its border', () => {
    const box = { left: 10, top: 10, right: 30, bottom: 30 };
    expect(segmentIntersectsBox({ x: 0, y: 20 }, { x: 40, y: 20 }, box)).toBe(true);
    expect(segmentIntersectsBox({ x: 0, y: 10 }, { x: 40, y: 10 }, box, 0.5)).toBe(false);
    expect(segmentIntersectsBox({ x: 0, y: 0 }, { x: 5, y: 5 }, box)).toBe(false);
    const nodes = [
      { id: 'n', body: box, label: { left: 5, top: 30, right: 35, bottom: 45 } },
      { id: 's', body: { left: -50, top: 10, right: -30, bottom: 30 }, label: null },
    ];
    expect(edgesThroughBoxes([line('through', 's', 'x', { x: -30, y: 20 }, { x: 60, y: 20 })], nodes, bodyOf))
      .toEqual([{ edge: 'through', node: 'n' }]);
    expect(edgesThroughBoxes([line('under', 's', 'x', { x: -30, y: 40 }, { x: 60, y: 40 })], nodes, bodyOf)).toEqual([]);
    expect(edgesThroughBoxes([line('under', 's', 'x', { x: -30, y: 40 }, { x: 60, y: 40 })], nodes, labelBoxOf))
      .toEqual([{ edge: 'under', node: 'n' }]);
    expect(edgesThroughBoxes([line('own', 's', 'n', { x: -30, y: 20 }, { x: 10, y: 20 })], nodes, bodyOf)).toEqual([]);
    expect(extentOf(nodes[0])).toEqual({ left: 5, top: 10, right: 35, bottom: 45 });
  });

  it('reports edges drawn on top of each other beyond the tolerated length', () => {
    const shared = sharedRuns([
      line('a', 's', 't', { x: 0, y: 0 }, { x: 200, y: 0 }),
      line('b', 'u', 'v', { x: 50, y: 0.5 }, { x: 150, y: 0.5 }),
      line('c', 'w', 'z', { x: 0, y: 40 }, { x: 200, y: 40 }),
    ], { minLength: 80, tolerance: 1 });
    expect(shared).toEqual([{ edges: ['a', 'b'], length: 100 }]);
    expect(sharedRuns([
      line('a', 's', 't', { x: 0, y: 0 }, { x: 200, y: 0 }),
      line('b', 'u', 'v', { x: 50, y: 0.5 }, { x: 100, y: 0.5 }),
    ], { minLength: 80 })).toEqual([]);
    // The tolerance is the stroke width: 2.5px apart is still on top of each other at 3px.
    expect(sharedRuns([
      line('a', 's', 't', { x: 0, y: 0 }, { x: 200, y: 0 }),
      line('b', 'u', 'v', { x: 0, y: 2.5 }, { x: 200, y: 2.5 }),
    ], { minLength: 80, tolerance: 3 })).toHaveLength(1);
  });

  it('keeps a fan from one node apart from a pile of unrelated edges, and says so', () => {
    const lines = [
      line('a', 'hub', 't', { x: 0, y: 0 }, { x: 400, y: 4 }),
      line('b', 'hub', 'u', { x: 0, y: 2 }, { x: 400, y: 6 }),
      line('c', 'w', 'z', { x: 0, y: 3 }, { x: 400, y: 3 }),
      line('d', 'p', 'sink', { x: 0, y: 30 }, { x: 400, y: 30 }),
      line('e', 'q', 'sink', { x: 0, y: 32 }, { x: 400, y: 32 }),
    ];
    const judged = sharedRuns(lines, { minLength: 80, tolerance: 3, ignoreSharedEndpoints: true });
    expect(judged.fans.map(entry => entry.edges)).toEqual([['a', 'b'], ['d', 'e']]);
    expect(judged.map(entry => entry.edges)).toEqual([['a', 'c'], ['b', 'c']]);
    expect(sharedRuns(lines, { minLength: 80, tolerance: 3 })).toHaveLength(4);
  });

  it('treats two edges chained head to tail on one line as a pile, never a fan', () => {
    const lines = [
      line('ab', 'a', 'b', { x: 0, y: 0 }, { x: 300, y: 0 }),
      line('bc', 'b', 'c', { x: 100, y: 1 }, { x: 500, y: 1 }),
    ];
    const judged = sharedRuns(lines, { minLength: 80, tolerance: 3, ignoreSharedEndpoints: true });
    expect(judged.fans).toEqual([]);
    expect(judged.map(entry => entry.edges)).toEqual([['ab', 'bc']]);
    expect(judged[0].length).toBeCloseTo(200, 5);
  });

  it('clusters centres into the columns the geometry shows', () => {
    const result = geometryColumns([
      { id: 'a', x: 100 }, { id: 'b', x: 101 }, { id: 'c', x: 300 }, { id: 'd', x: 330 },
    ], { tolerance: 3 });
    expect(result.columns).toEqual([100.5, 300, 330]);
    expect(result.columnOf.get('b')).toBe(0);
    expect(result.columnOf.get('d')).toBe(2);
  });

  const workflow = {
    nodes: [
      { id: 'start', kind: 'START' }, { id: 'a', kind: 'PASSTHROUGH' }, { id: 'b', kind: 'PASSTHROUGH' },
      { id: 'c', kind: 'PASSTHROUGH' }, { id: 'join', kind: 'PASSTHROUGH' }, { id: 'end', kind: 'END' },
    ],
    edges: [
      { id: 'sa', source: 'start', target: 'a' }, { id: 'ab', source: 'a', target: 'b' },
      { id: 'ac', source: 'a', target: 'c' }, { id: 'bj', source: 'b', target: 'join' },
      { id: 'cj', source: 'c', target: 'join' }, { id: 'je', source: 'join', target: 'end' },
      { id: 'retry', source: 'join', target: 'a' }, { id: 'loop', source: 'c', target: 'c' },
    ],
  };

  it('layers a graph from its structure alone, breaking cycles from START and pinning END last', () => {
    const { layerOf, layerCount, backEdges } = structuralLayering(workflow.nodes, workflow.edges);
    expect([...backEdges]).toEqual(['retry']);
    expect(layerCount).toBe(5);
    expect([...layerOf]).toEqual([['start', 0], ['a', 1], ['b', 2], ['c', 2], ['join', 3], ['end', 4]]);
  });

  it('rejects a drawing whose columns run backwards even when the ids are numbers', () => {
    const nodes = [{ id: 1, kind: 'START', x: 200 }, { id: 2, kind: 'PASSTHROUGH', x: 100 }, { id: 3, kind: 'END', x: 0 }];
    const edges = [{ id: 10, source: 1, target: 2 }, { id: 11, source: 2, target: 3 }];
    const verdict = layerDiscreteness(nodes, edges);
    expect(verdict.ok).toBe(false);
    expect(verdict.nonMonotone).toEqual([10, 11]);
    expect(verdict.columns).toHaveLength(3);
    expect(verdict.layerCount).toBe(3);
    const forwards = layerDiscreteness(nodes.map(node => ({ ...node, x: 200 - node.x })), edges);
    expect(forwards.ok).toBe(true);
  });

  it('accepts a layered placement and rejects a scatter of the same nodes', () => {
    const layered = [
      { id: 'start', x: 0 }, { id: 'a', x: 200 }, { id: 'b', x: 400 }, { id: 'c', x: 400.5 },
      { id: 'join', x: 600 }, { id: 'end', x: 800 },
    ].map(node => ({ ...node, kind: workflow.nodes.find(entry => entry.id === node.id).kind }));
    const good = layerDiscreteness(layered, workflow.edges);
    expect(good.ok).toBe(true);
    expect(good.columns).toHaveLength(5);
    expect(good.extraColumns).toBe(0);
    const scattered = layered.map((node, index) => ({ ...node, x: [300, 50, 700, 90, 10, 400][index] }));
    const bad = layerDiscreteness(scattered, workflow.edges);
    expect(bad.ok).toBe(false);
    expect(bad.extraColumns).toBe(1);
    expect(bad.splitLayers).toEqual(['c']);
    // start(300) -> a(50), b(700) -> join(10) and c(90) -> join(10) run backwards; a -> b, a -> c
    // and join -> end happen to run forwards in this scatter and are correctly not reported.
    expect(bad.nonMonotone).toEqual(['sa', 'bj', 'cj']);
  });

  it('flags a back edge that runs through an intermediate column band', () => {
    const nodes = [
      { id: 'a', x: 0, body: { left: -40, top: -40, right: 40, bottom: 40 }, label: null },
      { id: 'b', x: 200, body: { left: 160, top: -40, right: 240, bottom: 40 }, label: null },
      { id: 'c', x: 400, body: { left: 360, top: -40, right: 440, bottom: 40 }, label: null },
    ];
    const layerOf = new Map([['a', 0], ['b', 1], ['c', 2]]);
    const through = line('back', 'c', 'a', { x: 360, y: 0 }, { x: 40, y: 0 });
    const around = line('back', 'c', 'a', { x: 440, y: 0 }, { x: 460, y: 0 }, { x: 460, y: 80 }, { x: -60, y: 80 }, { x: -60, y: 0 }, { x: -40, y: 0 });
    expect(backEdgesInsideBand([through], nodes, layerOf)).toEqual([{ edge: 'back', column: 1 }]);
    expect(backEdgesInsideBand([around], nodes, layerOf)).toEqual([]);
    expect(backEdgesInsideBand([line('fwd', 'a', 'c', { x: 40, y: 0 }, { x: 360, y: 0 })], nodes, layerOf)).toEqual([]);
  });
});
