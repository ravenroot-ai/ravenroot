import { describe, expect, it } from 'vitest';

import {
  FRONTEND_PLUGIN_API_VERSION,
  drawingModelSnapshot,
  providerCompatibility,
  validateFrontendPluginManifest,
  validateLayoutResult,
  validatePresentationMapping,
  validateScene,
} from '../src/frontend-plugin-api.js';

const graph = {
  nodes: [{ id: 'even' }, { id: 'read' }, { id: 'odd' }],
  edges: [
    { id: 'e1', source: 'even', target: 'read' },
    { id: 'e2', source: 'read', target: 'odd' },
    { id: 'e3', source: 'odd', target: 'read' },
    { id: 'e4', source: 'read', target: 'even' },
  ],
};
const mapping = {
  schema: 'ravenroot.presentation-mapping/v1',
  states: [
    { id: 'qEven', label: 'qEven', nodeId: 'even', initial: true, accepting: true },
    { id: 'qOdd', label: 'qOdd', nodeId: 'odd' },
  ],
  transitions: [
    { id: 'one', source: 'qEven', target: 'qOdd', label: '1', edgePath: ['e1', 'e2'] },
    { id: 'back', source: 'qOdd', target: 'qEven', label: '1', edgePath: ['e3', 'e4'] },
  ],
  positions: { qEven: { x: 100, y: 200 }, qOdd: { x: 400, y: 200 } },
};

describe('frontend plugin v1 contract', () => {
  it('validates separate layout and renderer providers and their composition', () => {
    const manifest = validateFrontendPluginManifest({
      schema: 'ravenroot.frontend-plugin/v1', id: 'example.automata', name: 'Automata', version: '1.2.3',
      apiVersion: FRONTEND_PLUGIN_API_VERSION, permissions: [],
      layouts: [{ id: 'layout', name: 'Layout', entry: 'plugin.js', capabilities: ['self-loops'] }],
      renderers: [{ id: 'renderer', name: 'Renderer', entry: 'plugin.js', capabilities: ['selection'] }],
      drawingModels: [{ id: 'model', name: 'Model', layout: 'layout', renderer: 'renderer' }],
    });
    expect(manifest.drawingModels[0]).toMatchObject({ layout: 'layout', renderer: 'renderer' });
  });

  it('refuses incompatible API versions, permissions and undeclared providers', () => {
    const base = { schema: 'ravenroot.frontend-plugin/v1', id: 'example.bad', name: 'Bad', version: '1.0.0',
      apiVersion: '2.0', permissions: [], layouts: [], renderers: [] };
    expect(() => validateFrontendPluginManifest(base)).toThrow(/incompatible/);
    expect(() => validateFrontendPluginManifest({ ...base, apiVersion: '1.0', permissions: ['network'],
      layouts: [{ id: 'l', name: 'L', entry: 'p.js' }] })).toThrow(/empty permissions/);
    expect(() => validateFrontendPluginManifest({ ...base, apiVersion: '1.0',
      layouts: [{ id: 'l', name: 'L', entry: 'p.js' }], renderers: [],
      drawingModels: [{ id: 'm', name: 'M', layout: 'l', renderer: 'missing' }] })).toThrow(/unknown renderer/);
  });

  it('composes layout-only and renderer-only packages only when capabilities satisfy requirements', () => {
    expect(providerCompatibility({ capabilities: ['self-loops'] }, { requires: ['self-loops'] }))
      .toMatchObject({ compatible: true, missingForRenderer: [] });
    expect(providerCompatibility({ capabilities: [] }, { requires: ['self-loops'] }))
      .toMatchObject({ compatible: false, missingForRenderer: ['self-loops'] });
  });
});

describe('explicit presentation mapping', () => {
  it('requires real, contiguous workflow paths and preserves their order and repetitions', () => {
    const cyclicGraph = { ...graph, edges: [...graph.edges, { id: 'e5', source: 'even', target: 'even' }] };
    const cyclic = structuredClone(mapping);
    cyclic.transitions.push({ id: 'loop', source: 'qEven', target: 'qEven', label: '0', edgePath: ['e5', 'e5'] });
    const validated = validatePresentationMapping(cyclic, cyclicGraph);
    expect(validated.transitions[2].edgePath).toEqual(['e5', 'e5']);
    expect(() => validatePresentationMapping({ ...mapping,
      transitions: [{ id: 'broken', source: 'qEven', target: 'qOdd', label: '1', edgePath: ['e2'] }] }, graph))
      .toThrow(/endpoints/);
  });

  it('maps operational evidence without declaring formal acceptance', () => {
    const snapshot = drawingModelSnapshot(graph, mapping, { activeNodeIds: ['even'], activeEdgeIds: ['e2'] });
    expect(snapshot.states[0].active).toBe(true);
    expect(snapshot.transitions[0].active).toBe(true);
    expect(snapshot.evidence.description).toMatch(/do not assert formal automaton acceptance/);
  });
});

describe('declarative renderer scene', () => {
  it('requires one finite layout position for every projected state', () => {
    const snapshot = drawingModelSnapshot(graph, mapping);
    expect(validateLayoutResult({ schema: 'ravenroot.layout-result/v1', positions: {
      qEven: { x: 1, y: 2 }, qOdd: { x: 3, y: 4 },
    } }, snapshot).positions.qOdd).toEqual({ x: 3, y: 4 });
    expect(() => validateLayoutResult({ schema: 'ravenroot.layout-result/v1', positions: {
      qEven: { x: 1, y: 2 },
    } }, snapshot)).toThrow(/qOdd/);
  });

  it('preserves finite coordinates while rejecting markup, hrefs and unsupported path data', () => {
    const scene = validateScene({ schema: 'ravenroot.scene/v1', width: 800, height: 500, elements: [
      { type: 'circle', id: 'qEven', role: 'state', x: 220, y: 250, r: 46, className: 'state active' },
      { type: 'path', d: 'M 1 2 Q 3 4 5 6', className: 'edge' },
    ] });
    expect(scene.elements[0]).toMatchObject({ x: 220, y: 250, r: 46 });
    expect(() => validateScene({ schema: 'ravenroot.scene/v1', elements: [
      { type: 'circle', x: 1, y: 2, r: 3, href: 'https://example.test' },
    ] })).toThrow(/unknown field 'href'/);
    expect(() => validateScene({ schema: 'ravenroot.scene/v1', elements: [
      { type: 'path', d: '<script>' },
    ] })).toThrow(/unsupported data/);
  });
});
