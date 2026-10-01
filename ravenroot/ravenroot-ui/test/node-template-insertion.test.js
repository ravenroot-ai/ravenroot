import { describe, expect, it } from 'vitest';
import { createCommandHistory } from '../src/graph-commands.js';
import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { addTemplateNodeAt } from '../src/graph-editing.js';
import { parseGraphML } from '../src/graph-parsers.js';

describe('saved node insertion', () => {
  it('creates a fresh disconnected clone in one undo entry without changing the template', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const template = Object.freeze({ id: 'saved-1', name: 'Configured probe', node: Object.freeze({
      name: 'Probe', kind: 'BEHAVIOR', behavior: 'probe', nodeType: 'actor',
      description: 'Reusable', width: 140, height: 70,
      properties: Object.freeze({ target: 'x' }), propertyTypes: Object.freeze({ target: 'string' }),
      workspaceReferences: Object.freeze([]),
    }) });
    const edges = graph.edges.map(edge => edge.id);

    const result = addTemplateNodeAt(graph, { x: 200, y: 300 }, template, history);

    expect(result.node).toMatchObject({ name: 'Probe', behavior: 'probe', ox: 200, oy: 300,
      properties: { target: 'x' } });
    expect(result.node.id).not.toBe(template.id);
    expect(graph.edges.map(edge => edge.id)).toEqual(edges);
    expect(history.state()).toMatchObject({ depth: 1, undoLabel: 'Insert saved node Configured probe' });
    history.undo(graph);
    expect(graph.nodeMap[result.node.id]).toBeUndefined();
    expect(template.node.properties).toEqual({ target: 'x' });
  });

  it('preserves the inserted authored fields and typed properties through GraphML round trip', () => {
    const graph = createWorkflowDocument();
    const inserted = addTemplateNodeAt(graph, { x: 33, y: 44 }, { name: 'Counter', node: {
      name: 'Counter', kind: 'BEHAVIOR', behavior: 'counter', nodeType: 'agent', classname: 'demo.Counter',
      description: 'Counts safely', width: 180, height: 90,
      properties: { maximum: '7', enabled: 'true' },
      propertyTypes: { maximum: 'long', enabled: 'boolean' }, workspaceReferences: [],
    } }).node;

    const reloaded = parseGraphML(serializeGraphML(graph)).nodeMap[inserted.id];
    expect(reloaded).toMatchObject({
      name: 'Counter', kind: 'BEHAVIOR', behavior: 'counter', nodeType: 'agent', classname: 'demo.Counter',
      description: 'Counts safely', ow: 180, oh: 90,
      properties: { maximum: '7', enabled: 'true' },
      propertyTypes: { maximum: 'long', enabled: 'boolean' },
    });
  });

  it('refuses duplicate terminals and workspace references missing from the destination graph', () => {
    const graph = createWorkflowDocument();
    expect(addTemplateNodeAt(graph, { x: 1, y: 2 }, { name: 'End', node: { kind: 'END' } }).reason)
      .toContain('already has');
    expect(addTemplateNodeAt(graph, { x: 1, y: 2 }, { name: 'Worker', node: {
      kind: 'BEHAVIOR', behavior: 'workspace', properties: { workspace: 'missing' },
      workspaceReferences: ['workspace'],
    } }).reason).toContain('outside this workflow');
  });

  it.each(['START', 'PASSTHROUGH', 'BEHAVIOR', 'END', 'ERROR'])(
    'inserts a saved %s node with the editor classification preserved', kind => {
      const graph = createWorkflowDocument();
      if (['START', 'END', 'ERROR'].includes(kind)) {
        const removed = graph.nodes.find(node => node.kind === kind);
        graph.nodes = graph.nodes.filter(node => node !== removed);
        delete graph.nodeMap[removed.id];
        graph.edges = graph.edges.filter(edge => edge.source !== removed.id && edge.target !== removed.id);
      }
      const result = addTemplateNodeAt(graph, { x: 10, y: 20 }, { name: kind, node: {
        kind, behavior: kind === 'BEHAVIOR' ? 'probe' : '', nodeType: kind === 'BEHAVIOR' ? 'actor' : 'flow',
      } });
      expect(result.reason).toBe('');
      expect(result.node).toMatchObject({ kind, behavior: kind === 'BEHAVIOR' ? 'probe' : '' });
      expect(graph.nodeMap[result.node.id]).toBe(result.node);
    },
  );
});
