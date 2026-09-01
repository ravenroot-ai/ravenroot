import cytoscape from 'cytoscape';
import { describe, expect, it } from 'vitest';

import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { addNodeAt, connectNodes } from '../src/graph-editing.js';
import { parseGraphML } from '../src/graph-parsers.js';
import {
  DEFAULT_RENDER_MODE,
  DEFAULT_VISUAL_STYLE,
  DESIGN_RENDER_MODE,
  documentPresentationState,
  graphLayoutPlan,
  initialLayoutForGraph,
  isRendererKindLayout,
  loadedGraphLayoutPlan,
  MONITORING_RENDER_MODE,
  normalizeRenderMode,
  normalizeVisualStyle,
  renderGraphStatistics,
  renderModePresentation,
  retainableLayout,
  syncGraphPositionsFromCy,
  visualStyleFromLegacyLayout,
} from '../src/graph-view-state.js';

function elementsFromGraph(graph) {
  return [
    ...graph.nodes.map(node => ({
      data: { id: node.id, nw: node.ow, nh: node.oh },
      position: { x: node.ox, y: node.oy },
    })),
    ...graph.edges.map(edge => ({
      data: { id: edge.id, source: edge.source, target: edge.target },
    })),
  ];
}

function createHeadlessView(graph) {
  return cytoscape({
    headless: true,
    elements: elementsFromGraph(graph),
    layout: { name: 'preset' },
  });
}

describe('Cytoscape layout lifecycle', () => {
  it('normalizes every legacy split or combined preference into two semantic modes', () => {
    for (const legacy of ['dagre', 'cose', 'elk', 'n8n', 'n8n2', 'n8n3', 'n8n4', 'cyto', 'preset']) {
      expect(documentPresentationState({ layoutMode: legacy })).toEqual({
        renderMode: 'design', layoutMode: 'cyto', visualStyle: 'cyto',
      });
    }
    expect(documentPresentationState({ layoutMode: 'elastic', visualStyle: 'n8n4' })).toEqual({
      renderMode: 'monitoring', layoutMode: 'elastic', visualStyle: 'cyto',
    });
    expect(documentPresentationState({ renderMode: 'monitoring', layoutMode: 'dagre' })).toEqual({
      renderMode: 'monitoring', layoutMode: 'elastic', visualStyle: 'cyto',
    });
    expect(visualStyleFromLegacyLayout('dagre')).toBe(DEFAULT_VISUAL_STYLE);
  });

  it('defaults invalid injected state to Design and projects only canonical internal pairs', () => {
    expect(DEFAULT_RENDER_MODE).toBe(DESIGN_RENDER_MODE);
    expect(normalizeRenderMode(undefined)).toBe(DESIGN_RENDER_MODE);
    expect(normalizeRenderMode('n8n4')).toBe(DESIGN_RENDER_MODE);
    expect(normalizeRenderMode('elastic')).toBe(MONITORING_RENDER_MODE);
    expect(renderModePresentation('monitoring')).toEqual({
      renderMode: 'monitoring', layoutMode: 'elastic', visualStyle: 'cyto',
    });
    expect(DEFAULT_VISUAL_STYLE).toBe('cyto');
    expect(normalizeVisualStyle(undefined)).toBe('cyto');
    expect(normalizeVisualStyle('not-a-style')).toBe('cyto');
    expect(documentPresentationState({ renderMode: 'invalid', layoutMode: 'elastic' })).toEqual({
      renderMode: 'design', layoutMode: 'cyto', visualStyle: 'cyto',
    });
  });
  it('keeps the established position-planning default independent from the visual style', () => {
    expect(initialLayoutForGraph(createWorkflowDocument())).toBe('n8n');
    expect(initialLayoutForGraph({ format: 'graphify', nodes: [{ id: 'view' }] })).toBe('n8n');
  });

  it('preserves parse → Cytoscape rebuild → export topology and coordinates', () => {
    const parsed = parseGraphML(serializeGraphML(createWorkflowDocument()));
    expect(initialLayoutForGraph(parsed)).toBe('preset');
    expect(graphLayoutPlan(parsed)).toEqual({ name: 'preset', preservePositions: false });

    const view = createHeadlessView(parsed);
    view.getElementById('start').position({ x: 401.5, y: 202.25 });
    syncGraphPositionsFromCy(parsed, view);

    const worker = addNodeAt(parsed, { x: 610, y: 205 });
    worker._positionIsCenter = true;
    connectNodes(parsed, 'start', worker.id);

    // Mirrors rebuildGraph(): synchronize the live view, rebuild nodeMap and
    // initialize a new Cytoscape instance from the retained model positions.
    parsed.nodeMap = Object.fromEntries(parsed.nodes.map(node => [node.id, node]));
    expect(graphLayoutPlan(parsed, { layoutName: 'n8n', preservePositions: true }))
      .toEqual({ name: 'n8n', preservePositions: true });
    const rebuilt = createHeadlessView(parsed);
    expect(rebuilt.getElementById('start').position()).toEqual({ x: 401.5, y: 202.25 });
    expect(rebuilt.getElementById(worker.id).position()).toEqual({ x: 610, y: 205 });

    syncGraphPositionsFromCy(parsed, rebuilt);
    const reparsed = parseGraphML(serializeGraphML(parsed));
    expect(initialLayoutForGraph(reparsed)).toBe('preset');
    expect(reparsed.nodeMap.start).toMatchObject({ ox: 401.5, oy: 202.25 });
    expect(reparsed.nodeMap[worker.id]).toMatchObject({ ox: 610, oy: 205 });
    expect(reparsed.edges.map(edge => [edge.source, edge.target]))
      .toContainEqual(['start', worker.id]);

    view.destroy();
    rebuilt.destroy();
  });

  it('loads persisted positions using the current visual renderer without relayout', () => {
    const parsed = parseGraphML(serializeGraphML(createWorkflowDocument()));

    expect(loadedGraphLayoutPlan(parsed, 'n8n4'))
      .toEqual({ name: 'n8n4', preservePositions: true });
    expect(loadedGraphLayoutPlan(parsed, 'cyto'))
      .toEqual({ name: 'cyto', preservePositions: true });
  });

  it('uses the current renderer normally when a loaded graph has no persisted positions', () => {
    const graph = createWorkflowDocument();
    graph.nodes.forEach(node => { node._positionIsCenter = false; });

    expect(loadedGraphLayoutPlan(graph, 'n8n4'))
      .toEqual({ name: 'n8n4', preservePositions: false });
  });

  // `elastic` is the one `layoutMode` value that is not a Cytoscape layout algorithm: it
  // selects a separate renderer kind with its own DOM host and D3 simulation. Retaining it across a
  // load rebuilt that renderer for the incoming graph, defeating the teardown a replace had just
  // performed. Every genuine layout algorithm must keep travelling, so this pins both directions.
  it('never retains a renderer-kind selection across a load, unlike a layout algorithm', () => {
    const positioned = parseGraphML(serializeGraphML(createWorkflowDocument()));
    const unpositioned = createWorkflowDocument();
    unpositioned.nodes.forEach(node => { node._positionIsCenter = false; });

    expect(isRendererKindLayout('elastic')).toBe(true);
    expect(retainableLayout('elastic')).toBeNull();

    expect(loadedGraphLayoutPlan(positioned, 'elastic'))
      .toEqual({ name: 'preset', preservePositions: true });
    expect(loadedGraphLayoutPlan(unpositioned, 'elastic'))
      .toEqual({ name: 'n8n', preservePositions: false });
  });

  it('treats every Cytoscape layout algorithm as retainable', () => {
    for (const name of ['dagre', 'cose', 'elk', 'n8n', 'n8n2', 'n8n3', 'n8n4', 'cyto', 'preset']) {
      expect(isRendererKindLayout(name)).toBe(false);
      expect(retainableLayout(name)).toBe(name);
    }
    // An absent selection is not a renderer kind; it simply has nothing to retain.
    expect(retainableLayout(null)).toBeNull();
    expect(retainableLayout(undefined)).toBeNull();
    expect(retainableLayout('')).toBeNull();
  });
});

describe('Graph statistics rendering', () => {
  it('renders untrusted graph types as text without creating markup', () => {
    const container = document.createElement('div');
    const maliciousType = '<img src=x onerror="globalThis.compromised=true">';

    renderGraphStatistics(container, 1, 1, { [maliciousType]: 1 }, { '<script>bad()</script>': 1 });

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(container.textContent).toContain(maliciousType);
    expect(container.textContent).toContain('<script>bad()</script>');
    expect(globalThis.compromised).toBeUndefined();
  });
});
