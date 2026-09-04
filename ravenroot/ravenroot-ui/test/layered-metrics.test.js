import { describe, expect, it } from 'vitest';

import {
  backEdgesInsideBand, bodyOf, countCrossings, edgesThroughBoxes, extentOf, labelBoxOf, labelOverlaps, layerColumns,
  segmentIntersectsBox, segmentsCross, sharedRuns,
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
    ], { minLength: 80 });
    expect(shared).toEqual([{ edges: ['a', 'b'], length: 100 }]);
    expect(sharedRuns([
      line('a', 's', 't', { x: 0, y: 0 }, { x: 200, y: 0 }),
      line('b', 'u', 'v', { x: 50, y: 0.5 }, { x: 100, y: 0.5 }),
    ], { minLength: 80 })).toEqual([]);
  });

  it('clusters centres into columns and names the nodes off every column', () => {
    const result = layerColumns([
      { id: 'a', x: 100 }, { id: 'b', x: 101 }, { id: 'c', x: 300 }, { id: 'd', x: 330 },
    ], { tolerance: 3 });
    expect(result.columns).toEqual([100.5, 300, 330]);
    expect(result.offGrid).toEqual([]);
    expect(result.layerOf.get('b')).toBe(0);
    expect(result.layerOf.get('d')).toBe(2);
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
