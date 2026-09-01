import { describe, expect, it, vi } from 'vitest';

import {
  applyViewerSimpleRoute,
  applyViewerRoundedSegmentRoute,
  applyViewerUnbundledRoute,
  viewerControlPointStyle,
} from '../src/viewer-edge-style.js';

const route = Object.freeze({
  family: 'unbundled-bezier',
  start: { x: 0, y: 0 }, end: { x: 100, y: 0 },
  points: [{ x: 25, y: 10 }, { x: 75, y: -10 }],
  sourceEndpoint: '50px 0px', targetEndpoint: '-50px 0px', radius: 36,
});

describe('shared viewer edge styling', () => {
  it('projects renderer-neutral points deterministically', () => {
    expect(viewerControlPointStyle(route)).toEqual([
      { weight: .25, distance: 10 }, { weight: .75, distance: -10 },
    ]);
  });

  it('applies the same unbundled and rounded route contracts to editor and embed adapters', () => {
    const edge = { style: vi.fn() };
    applyViewerUnbundledRoute(edge, route, { lineCap: 'round' });
    expect(edge.style).toHaveBeenCalledWith(expect.objectContaining({
      'curve-style': 'unbundled-bezier',
      'control-point-weights': [.25, .75],
      'control-point-distances': [10, -10],
      'line-cap': 'round',
    }));

    edge.style.mockClear();
    applyViewerRoundedSegmentRoute(edge, { ...route, family: 'round-segments' });
    expect(edge.style).toHaveBeenCalledWith(expect.objectContaining({
      'curve-style': 'round-segments', 'segment-weights': [.25, .75],
    }));
  });

  it('clears detailed controls before applying the bounded fallback', () => {
    const edge = { removeStyle: vi.fn(), style: vi.fn() };
    applyViewerSimpleRoute(edge);
    expect(edge.removeStyle).toHaveBeenCalledWith(expect.stringContaining('control-point-weights'));
    expect(edge.style).toHaveBeenCalledWith(expect.objectContaining({
      'curve-style': 'bezier',
      'edge-distances': 'intersection',
      'line-cap': 'round',
    }));
  });
});
