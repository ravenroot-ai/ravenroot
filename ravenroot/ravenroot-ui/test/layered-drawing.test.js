import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { parseGraphML } from '../src/graph-parsers.js';
import {
  LAYERED_MODE_NAMES, buildLayeredElkGraph, computeLayeredDrawing, isLayeredMode, layeredElkOptions,
  sectionPolyline,
} from '../src/layered-drawing.js';
import {
  backEdgesInsideBand, bodyOf, countCrossings, edgesThroughBoxes, geometryColumns, labelBoxOf, labelOverlaps,
  layerDiscreteness, sharedRuns,
} from '../src/layered-metrics.js';

const FONT_PX = 20;
const NODE_SIZE = 80;

// The design editor paints 80px cards with a single-line label beneath them. The label width is
// estimated the same way the editor's own route resolver estimates it; the browser suite measures
// the real thing.
function inputsFromGraph(graph) {
  return {
    nodes: graph.nodes.map(node => {
      const text = String(node.name || node.label || node.id);
      return {
        id: node.id, width: NODE_SIZE, height: NODE_SIZE, kind: node.kind,
        label: { text, width: text.length * FONT_PX * 0.56, height: 10 + FONT_PX * 1.35 },
      };
    }),
    edges: graph.edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target })),
  };
}

function judged(drawing, inputs) {
  const polylines = [...drawing.routes.values()].map(route => ({
    id: route.id, source: route.source, target: route.target, points: [route.start, ...route.points, route.end],
  }));
  const kinds = new Map(inputs.nodes.map(node => [node.id, node.kind]));
  const nodes = [...drawing.boxes.values()].map(box => ({
    id: box.id, x: box.x, kind: kinds.get(box.id),
    body: { left: box.x - box.width / 2, right: box.x + box.width / 2, top: box.y - box.height / 2, bottom: box.y + box.height / 2 },
    label: box.labelWidth ? {
      left: box.x - box.labelWidth / 2, right: box.x + box.labelWidth / 2,
      top: box.y + box.height / 2, bottom: box.y + box.height / 2 + box.labelHeight,
    } : null,
  }));
  const { columnOf } = geometryColumns(nodes);
  // Piled edges are judged at the stroke width; a fan from one node's adjacent ports is the one
  // exception the criteria allow, and it is reported separately rather than absorbed.
  const runs = sharedRuns(polylines, { minLength: NODE_SIZE, tolerance: 3, ignoreSharedEndpoints: true });
  return {
    crossings: countCrossings(polylines),
    labelOverlaps: labelOverlaps(nodes),
    throughBodies: edgesThroughBoxes(polylines, nodes, bodyOf),
    throughLabels: edgesThroughBoxes(polylines, nodes, labelBoxOf),
    piles: [...runs],
    fans: runs.fans,
    layering: layerDiscreteness(nodes, inputs.edges),
    backInsideBand: backEdgesInsideBand(polylines, nodes, columnOf),
    polylines, nodes,
  };
}

const PEERS = ['product-architecture', 'core-runtime', 'integrations', 'frontend', 'graph-rendering', 'qa',
  'platform', 'ai-agents', 'open-source', 'legal', 'security', 'docs'];

// jsdom swaps the global `URL`, which Node's fs refuses; resolve the fixture from the path string.

// A workflow-shaped synthetic graph: blocks of fan-out and fan-in along a spine, extra edges
// between near neighbours, and a few retries running backwards.
export function syntheticWorkflow(nodeCount, edgeCount) {
  const nodes = Array.from({ length: nodeCount }, (_, index) => ({
    id: `n${index}`, width: 80, height: 80,
    kind: index === 0 ? 'START' : index === nodeCount - 1 ? 'END' : 'PASSTHROUGH',
    label: { text: `Node ${index}`, width: 90, height: 37 },
  }));
  const edges = [];
  const seen = new Set();
  const add = (from, to) => {
    const key = `${from}>${to}`;
    if (from === to || from < 0 || to < 0 || from >= nodeCount || to >= nodeCount || seen.has(key)) return;
    seen.add(key);
    edges.push({ id: `e${edges.length}`, source: `n${from}`, target: `n${to}` });
  };
  for (let index = 1; index < nodeCount; index++) add(Math.floor((index - 1) / 3), index);
  let seed = 7;
  const random = () => { seed = (seed * 48271) % 2147483647; return seed / 2147483647; };
  while (edges.length < edgeCount) {
    const from = Math.floor(random() * nodeCount);
    const span = 1 + Math.floor(random() * 12);
    add(from, random() < 0.08 ? from - span : from + span);
  }
  return { nodes, edges };
}

const testBench = parseGraphML(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'layout-test-bench.graphml'), 'utf8'));

describe('layered drawing', () => {
  it('exposes exactly the two additive modes and their ELK options', () => {
    expect(LAYERED_MODE_NAMES).toEqual(['hierarchical-new', 'flow-new']);
    expect(isLayeredMode('hierarchical')).toBe(false);
    expect(isLayeredMode('flow-new')).toBe(true);
    expect(layeredElkOptions('hierarchical-new')['elk.edgeRouting']).toBe('ORTHOGONAL');
    expect(layeredElkOptions('flow-new')['elk.edgeRouting']).toBe('POLYLINE');
    expect(layeredElkOptions('flow-new')['elk.direction']).toBe('RIGHT');
    expect(() => layeredElkOptions('organic')).toThrow(/Unknown layered drawing mode/);
  });

  it('builds an ELK graph with outside labels, centred layers, pinned terminals and no self-loops', () => {
    const graph = buildLayeredElkGraph({
      nodes: [
        { id: 'a', width: 80, height: 80, kind: 'START', label: { text: 'Start', width: 50, height: 30 } },
        { id: 'b', width: 80, height: 80, kind: 'PASSTHROUGH' },
        { id: 'c', width: 80, height: 80, kind: 'END', label: { text: 'End', width: 0, height: 30 } },
      ],
      edges: [
        { id: 'e1', source: 'a', target: 'b' }, { id: 'loop', source: 'b', target: 'b' },
        { id: 'e2', source: 'b', target: 'c' }, { id: 'dangling', source: 'b', target: 'zz' },
      ],
    }, 'hierarchical-new');
    expect(graph.children.map(child => child.id)).toEqual(['a', 'b', 'c']);
    expect(graph.children[0].labels).toEqual([{ text: 'Start', width: 50, height: 30 }]);
    expect(graph.children[0].layoutOptions['elk.layered.layering.layerConstraint']).toBe('FIRST');
    expect(graph.children[1].layoutOptions['elk.layered.layering.layerConstraint']).toBeUndefined();
    expect(graph.children[2].layoutOptions['elk.layered.layering.layerConstraint']).toBe('LAST');
    expect(graph.children[2].labels).toEqual([]);
    expect(graph.children.every(child => child.layoutOptions['elk.alignment'] === 'CENTER')).toBe(true);
    expect(graph.edges.map(edge => edge.id)).toEqual(['e1', 'e2']);
  });

  it('turns an ELK section into an absolute polyline without duplicate points', () => {
    expect(sectionPolyline({
      startPoint: { x: 0, y: 0 }, bendPoints: [{ x: 10, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 20 }], endPoint: { x: 30, y: 20 },
    })).toEqual([{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 20 }, { x: 30, y: 20 }]);
  });

  describe.each(LAYERED_MODE_NAMES)('%s on the test bench', mode => {
    it('meets the drawing criteria: no collisions, discrete layers, back edges outside the band', async () => {
      const inputs = inputsFromGraph(testBench);
      const drawing = await computeLayeredDrawing(inputs, mode);
      expect(drawing.routes.size).toBe(testBench.edges.length);
      expect(drawing.backEdges.length).toBeGreaterThan(0);
      const verdict = judged(drawing, inputs);
      expect(verdict.labelOverlaps).toEqual([]);
      expect(verdict.throughBodies).toEqual([]);
      expect(verdict.throughLabels).toEqual([]);
      expect(verdict.piles).toEqual([]);
      expect(verdict.backInsideBand).toEqual([]);
      // Layer discreteness is judged against a layering computed from the graph alone: one
      // column per structural layer, in order, with every forward edge crossing columns forwards.
      expect(verdict.layering.layerCount).toBe(15);
      expect(verdict.layering.columns).toHaveLength(15);
      expect(verdict.layering.splitLayers).toEqual([]);
      expect(verdict.layering.nonMonotone).toEqual([]);
      expect(verdict.layering.ok).toBe(true);
      expect(new Set(PEERS.map(id => verdict.layering.columnOf.get(id))).size).toBe(1);
      // Measured 187 (orthogonal) and 191 (polyline) when the drawing was accepted; the guard
      // catches a regression of the engine options, not a specific number.
      expect(verdict.crossings).toBeLessThanOrEqual(220);
      // Every failure route reaches the error node through its west side at its own port.
      const intoError = [...drawing.routes.values()].filter(route => route.target === 'error');
      expect(intoError.length).toBeGreaterThan(10);
      const errorBox = drawing.boxes.get('error');
      expect(intoError.every(route => Math.abs(route.end.x - (errorBox.x - errorBox.width / 2)) < 0.01)).toBe(true);
      expect(new Set(intoError.map(route => route.targetEndpoint)).size).toBe(intoError.length);
    });

    it('keeps START first and END last, and routes back edges east-out, west-in, under the band', async () => {
      const drawing = await computeLayeredDrawing(inputsFromGraph(testBench), mode);
      expect(drawing.layers.get('start')).toBe(0);
      expect(drawing.layers.get('end')).toBe(drawing.columns.length - 1);
      expect(drawing.backEdges).toEqual(expect.arrayContaining(['handler__poll', 'eligibility__poll', 'gate__leader', 'review__leader', 'recovery__poll']));
      for (const id of drawing.backEdges) {
        const route = drawing.routes.get(id);
        expect(route.kind).toBe('back');
        expect(route.family).toBe('round-segments');
        const source = drawing.boxes.get(route.source);
        const target = drawing.boxes.get(route.target);
        expect(route.start.x).toBeCloseTo(source.x + source.width / 2, 5);
        expect(route.end.x).toBeCloseTo(target.x - target.width / 2, 5);
        expect(Math.max(...route.points.map(point => point.y))).toBeGreaterThan(drawing.band.bottom);
      }
    });

    it('is judged by a layer check that fails on a scatter of the same nodes', async () => {
      const inputs = inputsFromGraph(testBench);
      const drawing = await computeLayeredDrawing(inputs, mode);
      let seed = 11;
      const random = () => { seed = (seed * 48271) % 2147483647; return seed / 2147483647; };
      const scattered = [...drawing.boxes.values()].map(box => ({
        id: box.id, kind: inputs.nodes.find(node => node.id === box.id).kind, x: random() * 3000, y: random() * 2000,
      }));
      const verdict = layerDiscreteness(scattered, inputs.edges);
      expect(verdict.ok).toBe(false);
      expect(verdict.columns.length).toBeGreaterThan(verdict.layerCount);
      expect(verdict.nonMonotone.length).toBeGreaterThan(0);
    });

    it('is deterministic', async () => {
      const first = await computeLayeredDrawing(inputsFromGraph(testBench), mode);
      const second = await computeLayeredDrawing(inputsFromGraph(testBench), mode);
      expect(second.positions).toEqual(first.positions);
      expect([...second.routes.values()].map(route => [route.id, route.start, route.points, route.end]))
        .toEqual([...first.routes.values()].map(route => [route.id, route.start, route.points, route.end]));
    });
  });

  it('draws a 200-node, 400-edge workflow-shaped graph completely', async () => {
    // The time budget of the issue is measured in the browser suite, which runs alone; here the
    // file shares the machine with every other unit file, so only completeness and a sanity bound
    // are asserted.
    const inputs = syntheticWorkflow(200, 400);
    for (const mode of LAYERED_MODE_NAMES) {
      const drawing = await computeLayeredDrawing(inputs, mode);
      expect(drawing.routes.size).toBe(400);
      expect(drawing.positions).toHaveLength(200);
      const verdict = judged(drawing, inputs);
      expect(verdict.labelOverlaps).toEqual([]);
      // ELK's depth-first cycle break and the structural one may reverse different edges of the
      // same cycle, so an edge running against the structural order is acceptable only when the
      // drawing itself routed it as a back edge, outside the band.
      expect(verdict.layering.nonMonotone.filter(id => !drawing.backEdges.includes(id))).toEqual([]);
      // A graph with slack lets the engine place a node later than longest-path layering does,
      // so the one-to-one mapping asserted on the test bench is not a universal property; the
      // column bound is.
      expect(verdict.layering.columns.length).toBeLessThanOrEqual(verdict.layering.layerCount);
      expect(verdict.backInsideBand).toEqual([]);
      expect(drawing.elapsedMs).toBeLessThan(20_000);
    }
  }, 40_000);
});
