import { describe, expect, it } from 'vitest';
import { projectVisualGroups } from '../src/visual-group-projection.js';
import { makeVisualGroupFixture } from './fixtures/visual-groups.js';

describe('visual grouping projection', () => {
  it('preserves the canonical graph and maps every boundary edge exactly once without semantic merging', () => {
    const { graph, groups } = makeVisualGroupFixture();
    const before = structuredClone(graph);
    const projection = projectVisualGroups({ graph, groups,
      state: { 'section-a': { collapsed: true }, 'section-b': { collapsed: true } } });
    const expected = graph.edges.filter(edge => {
      const source = projection.representativeByNodeId.get(edge.source);
      const target = projection.representativeByNodeId.get(edge.target);
      return source !== target || source === edge.source;
    });
    expect(projection.edges.flatMap(edge => edge.originalEdgeIds).sort())
      .toEqual(expected.map(edge => edge.id).sort());
    for (const edge of projection.edges) {
      const original = graph.edges.find(item => item.id === edge.originalEdgeIds[0]);
      expect(edge.parallel).toBe(original.parallel);
      expect(edge.outcome).toBe(original.outcome);
      expect(edge.command).toBe(original.command);
      expect(edge.status).toBe(original.status);
    }
    expect(graph).toEqual(before);
    expect(projection.edges.filter(edge => edge.source === edge.target)).toHaveLength(0);
    expect(projection.edges.filter(edge => edge.source === 'node-0').length).toBeGreaterThanOrEqual(24);
  });
  it('allocates stable collision-free identities over all canonical nodes and edges across toggles and reordering', () => {
    const { graph, groups } = makeVisualGroupFixture();
    const expanded = projectVisualGroups({ graph, groups });
    const collapsed = projectVisualGroups({ graph: { ...graph, nodes: [...graph.nodes].reverse(), edges: [...graph.edges].reverse() },
      groups: [...groups].reverse(), state: { 'section-a': { collapsed: true } } });
    const realIds = new Set([...graph.nodes, ...graph.edges].map(item => item.id));
    for (const id of collapsed.identities.ids) expect(realIds.has(id)).toBe(false);
    for (const group of expanded.groups) {
      expect(collapsed.groups.find(item => item.id === group.id).summaryId).toBe(group.summaryId);
    }
    expect(expanded.groups[0].summaryId).not.toBe('rr-visual:["summary","section-a"]');
    expect(expanded.edges.filter(edge => edge.source === edge.target)).toHaveLength(2);
  });
  it('handles valid JSON identifiers containing lone UTF-16 surrogates without throwing', () => {
    const { graph, groups } = makeVisualGroupFixture();
    const projection = projectVisualGroups({ graph, groups: [{ ...groups[0], id: '\ud800', collapsed: true }] });
    expect(projection.valid).toBe(true);
    expect(projection.groups[0].summaryId).toContain('\\ud800');
  });
  it('fails open for malformed membership and preserves independent expanded groups', () => {
    const { graph, groups } = makeVisualGroupFixture();
    const invalid = projectVisualGroups({ graph, groups: [groups[0], { ...groups[1], memberNodeIds: groups[0].memberNodeIds }] });
    expect(invalid.valid).toBe(false);
    expect(invalid.nodes).toHaveLength(graph.nodes.length);
    const valid = projectVisualGroups({ graph, groups, state: { 'section-a': { collapsed: true } } });
    expect(valid.representativeByNodeId.get('node-25')).toBe('node-25');
    expect(valid.representativeByNodeId.get('node-1')).toBe(valid.groups[0].summaryId);
  });
});
