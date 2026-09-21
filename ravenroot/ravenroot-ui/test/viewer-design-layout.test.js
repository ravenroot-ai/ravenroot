import { describe, expect, it, vi } from 'vitest';

import {
  applyViewerDesignLabelSide,
  applyViewerDesignRoutes,
  viewerDesignLayoutOptions,
} from '../src/viewer-design-layout.js';

function graphFixture({ drawing = null } = {}) {
  const nodes = [
    { id: () => 'a', position: axis => axis ? ({ x: 100, y: 100 })[axis] : ({ x: 100, y: 100 }),
      width: () => 80, height: () => 80, outerWidth: () => 80, outerHeight: () => 80 },
    { id: () => 'b', position: axis => axis ? ({ x: 300, y: 180 })[axis] : ({ x: 300, y: 180 }),
      width: () => 80, height: () => 80, outerWidth: () => 80, outerHeight: () => 80 },
  ];
  const edge = {
    id: () => 'route', source: () => nodes[0], target: () => nodes[1],
    data: key => key === 'label' ? 'continue' : null,
    style: vi.fn(), removeStyle: vi.fn(),
  };
  const nodeCollection = [...nodes];
  nodeCollection.removeStyle = vi.fn(() => nodeCollection);
  nodeCollection.style = vi.fn(() => nodeCollection);
  const edgeCollection = [edge];
  edgeCollection.forEach = Array.prototype.forEach.bind(edgeCollection);
  return {
    nodes: () => nodeCollection, edges: () => edgeCollection,
    scratch: () => drawing,
    nodeCollection, edge,
  };
}

describe('embedded Design Render arrangement', () => {
  it.each([
    ['keep', 'preset'],
    ['flow', 'dagre'],
    ['organic', 'cose'],
    ['hierarchical', 'elk'],
    ['hierarchical-new', 'rr-layered'],
    ['layered-down', 'rr-layered'],
  ])('maps %s to the native Design engine %s without fitting', (arrangement, engine) => {
    expect(viewerDesignLayoutOptions(arrangement)).toMatchObject({ name: engine, fit: false });
  });

  it('fails closed for renderer and unknown algorithm names', () => {
    expect(() => viewerDesignLayoutOptions('elastic')).toThrow(/Unsupported Design arrangement/);
    expect(() => viewerDesignLayoutOptions('cose')).toThrow(/Unsupported Design arrangement/);
  });

  it('uses native hierarchical ports and rounded segments after Render', () => {
    const graph = graphFixture();
    applyViewerDesignRoutes(graph, 'hierarchical');
    expect(graph.edge.style).toHaveBeenCalledWith(expect.objectContaining({
      'curve-style': 'round-segments',
      'source-endpoint': expect.any(String), 'target-endpoint': expect.any(String),
    }));
  });

  it('preserves a published layered route and its arrangement-specific label side', () => {
    const route = {
      family: 'round-segments', source: 'a', target: 'b',
      start: { x: 140, y: 100 }, end: { x: 260, y: 180 },
      points: [{ x: 190, y: 100 }, { x: 190, y: 180 }], radius: 18,
      sourceEndpoint: '40px 0px', targetEndpoint: '-40px 0px',
    };
    const graph = graphFixture({ drawing: {
      routes: new Map([['route', route]]),
      boxes: new Map([['a', { x: 100, y: 100 }], ['b', { x: 300, y: 180 }]]),
    } });

    applyViewerDesignRoutes(graph, 'layered-down');

    expect(graph.nodeCollection.style).toHaveBeenCalledWith(expect.objectContaining({
      'text-valign': 'center', 'text-halign': 'right', 'text-margin-x': 10,
    }));
    expect(graph.edge.style).toHaveBeenCalledWith(expect.objectContaining({
      'curve-style': 'round-segments', 'segment-radii': [18],
      'source-endpoint': '40px 0px', 'target-endpoint': '-40px 0px',
    }));
  });

  it('passes label preparation into the layered layout before measurement', () => {
    const graph = graphFixture();
    applyViewerDesignLabelSide(graph, 'bottom');
    const prepareLabels = vi.fn();
    expect(viewerDesignLayoutOptions('layered-down', { prepareLabels }))
      .toMatchObject({ name: 'rr-layered', mode: 'layered-down', prepareLabels });
    expect(graph.nodeCollection.style).toHaveBeenCalledWith(expect.objectContaining({
      'text-valign': 'bottom', 'text-margin-y': 10,
    }));
  });
});
