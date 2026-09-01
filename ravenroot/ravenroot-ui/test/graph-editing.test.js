import { describe, expect, it } from 'vitest';

import { createEdge, createNode, createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { createCommandHistory } from '../src/graph-commands.js';
import {
  addNodeAt,
  canModifyGraph,
  canDuplicateNode,
  connectNodes,
  deleteElements,
  duplicateNode,
  migrateJoinSemantics,
  nextModifyState,
  shouldCreateNodeFromStage,
  shouldDeleteSelection,
  updateNodePropertiesBatch,
} from '../src/graph-editing.js';
import { parseGraphML } from '../src/graph-parsers.js';

describe('Modify mode model operations', () => {
  it('starts OFF, toggles ON and turns OFF on the second click', () => {
    const graph = createWorkflowDocument();
    let enabled = false;
    enabled = nextModifyState(enabled, graph);
    expect(enabled).toBe(true);
    enabled = nextModifyState(enabled, graph);
    expect(enabled).toBe(false);
  });

  it('keeps Graphify JSON and elastic visualization read-only', () => {
    expect(canModifyGraph({ format: 'graphify' })).toBe(false);
    expect(nextModifyState(false, { format: 'graphify' })).toBe(false);
    expect(canModifyGraph(createWorkflowDocument(), 'elastic')).toBe(false);
  });

  it('creates one unique node at the requested graph coordinates', () => {
    const graph = createWorkflowDocument();
    graph.nodes.push({ ...graph.nodes[0], id: 'node-1' });
    graph.nodeMap['node-1'] = graph.nodes.at(-1);

    const node = addNodeAt(graph, { x: 321, y: 123 });

    expect(node).toMatchObject({ id: 'node-2', ox: 321, oy: 123 });
    expect(graph.nodes.filter(candidate => candidate.id === node.id)).toHaveLength(1);
    expect(graph.nodeMap[node.id]).toBe(node);
  });

  it('creates a catalog-typed node at the stage position with descriptor defaults', () => {
    const graph = createWorkflowDocument();
    const node = addNodeAt(graph, { x: 40, y: 70 }, null, { descriptor: {
      behavior: 'send-mail', displayName: 'Send mail', visualType: 'actor',
      properties: [{ name: 'retries', type: 'integer', defaultValue: '3' }],
    } });

    expect(node).toMatchObject({
      id: 'send-mail-1', name: 'Send mail', kind: 'BEHAVIOR', behavior: 'send-mail',
      ox: 40, oy: 70, properties: { retries: '3' }, propertyTypes: { retries: 'long' },
    });
  });

  it('duplicates one editable node with a deterministic id and offset but no edges or provenance', () => {
    const graph = createWorkflowDocument();
    const source = graph.nodeMap.dosomething;
    source.behavior = 'template';
    source.kind = 'BEHAVIOR';
    source.nodeType = 'actor';
    source.classname = 'example.Template';
    source.description = 'A reusable step';
    source.properties = { template: 'Hello {{name}}' };
    source.propertyTypes = { template: 'string' };
    source._sourceCanonical = { name: source.name };
    source._legacyKind = true;
    source.instances = 17;
    const edgesBefore = graph.edges.map(edge => edge.id);

    const first = duplicateNode(graph, source.id);
    const second = duplicateNode(graph, source.id);

    expect(first).toMatchObject({
      id: 'dosomething-copy-1', name: source.name, kind: 'BEHAVIOR', behavior: 'template',
      nodeType: 'actor', classname: 'example.Template', description: 'A reusable step',
      ox: source.ox + 32, oy: source.oy + 32,
      properties: { template: 'Hello {{name}}' }, propertyTypes: { template: 'string' },
      _positionIsCenter: true, instances: 0,
    });
    expect(second.id).toBe('dosomething-copy-2');
    expect(first.properties).not.toBe(source.properties);
    expect(first.propertyTypes).not.toBe(source.propertyTypes);
    expect(first).not.toHaveProperty('_sourceCanonical');
    expect(first).not.toHaveProperty('_legacyKind');
    expect(graph.edges.map(edge => edge.id)).toEqual(edgesBefore);
  });

  it('fails closed for Graphify, Elastic and terminal nodes', () => {
    const graph = createWorkflowDocument();
    expect(canDuplicateNode(graph, 'dosomething')).toBe(true);
    expect(canDuplicateNode(graph, 'dosomething', 'elastic')).toBe(false);
    expect(canDuplicateNode(graph, 'start')).toBe(false);
    expect(canDuplicateNode(graph, 'end')).toBe(false);
    expect(canDuplicateNode(graph, 'error')).toBe(false);
    expect(duplicateNode(graph, 'start')).toBeNull();
    expect(duplicateNode({ format: 'graphify', nodes: [], edges: [], nodeMap: {} }, 'node')).toBeNull();
  });

  /**
   * Canvas insertion has no form in between to drop a blank value at save time -- whatever this
   * seeds goes straight into the document and the GraphML it serialises to. A pure-whitespace default
   * must be judged "not declared" here the same way `NodeTypeDescriptorValidator.isBlank()` and the
   * property-editor render both judge it, not merely "non-empty" the way a bare `!== ''` would. If
   * `createNodeForInsertion` carries its own `!== ''` copy, this fails:
   * `node.properties` and `node.propertyTypes` both carried the whitespace-only key.
   */
  it('does not seed a property whose descriptor default is pure whitespace', () => {
    const graph = createWorkflowDocument();
    const node = addNodeAt(graph, { x: 40, y: 70 }, null, { descriptor: {
      behavior: 'send-mail', displayName: 'Send mail', visualType: 'actor',
      properties: [
        { name: 'retries', type: 'integer', defaultValue: '3' },
        { name: 'mode', type: 'string', defaultValue: ' ', allowedValues: ['LONG_POLLING', 'WEBHOOK'] },
      ],
    } });

    expect(node.properties).toEqual({ retries: '3' });
    expect(node.propertyTypes).toEqual({ retries: 'long' });
    expect(node.properties).not.toHaveProperty('mode');
    expect(node.propertyTypes).not.toHaveProperty('mode');
  });

  it('creates on an intentional stage tap but not after pan/drag', () => {
    expect(shouldCreateNodeFromStage(true, true, false)).toBe(true);
    expect(shouldCreateNodeFromStage(true, true, true)).toBe(false);
    expect(shouldCreateNodeFromStage(false, true, false)).toBe(false);
  });

  it('connects nodes with unique IDs and synchronizes the graph model', () => {
    const graph = createWorkflowDocument();
    graph.edges.push({ ...graph.edges[0], id: 'edge-1' });

    const edge = connectNodes(graph, 'start', 'end');

    expect(edge).toMatchObject({ id: 'edge-2', source: 'start', target: 'end' });
    expect(graph.edges.at(-1)).toBe(edge);
  });

  it('batch deletes selected elements and incident edges while rebuilding nodeMap', () => {
    const graph = createWorkflowDocument();
    const worker = addNodeAt(graph, { x: 300, y: 200 });
    const incident = connectNodes(graph, 'start', worker.id);
    const result = deleteElements(graph, ['start'], []);

    expect(result.nodeIds).toEqual(['start']);
    expect(result.edgeIds).toEqual(expect.arrayContaining(['edge-start-dosomething', incident.id]));
    // start's only incident edge is the one to dosomething; dosomething's own two outgoing edges
    // (to end and to error) are untouched by deleting start.
    expect(graph.edges).toHaveLength(2);
    expect(graph.nodeMap.start).toBeUndefined();
    expect(graph.nodeMap[worker.id]).toBe(worker);
  });

  it('guards delete shortcuts inside editable controls and outside Modify mode', () => {
    const input = document.createElement('input');
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');

    expect(shouldDeleteSelection({ key: 'Delete', target: document.body }, true)).toBe(true);
    expect(shouldDeleteSelection({ key: 'Backspace', target: input }, true)).toBe(false);
    expect(shouldDeleteSelection({ key: 'Canc', target: editable }, true)).toBe(false);
    expect(shouldDeleteSelection({ key: 'Delete', target: document.body }, false)).toBe(false);
  });

  it('does not create a command or history entry for a rejected empty batch plan', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = serializeGraphML(graph);

    expect(updateNodePropertiesBatch(graph, [], history)).toBeNull();

    expect(serializeGraphML(graph)).toBe(before);
    expect(history.depth()).toBe(0);
    expect(history.isDirty()).toBe(false);
  });

  it('round-trips edited topology, properties and center-based layout through GraphML', () => {
    const graph = createWorkflowDocument();
    const node = addNodeAt(graph, { x: 333.5, y: 177.25 });
    node._positionIsCenter = true;
    node.properties.owner = 'ui-team';
    node.propertyTypes.owner = 'string';
    connectNodes(graph, 'start', node.id);

    const reparsed = parseGraphML(serializeGraphML(graph));

    expect(reparsed.nodes.map(candidate => candidate.id))
      .toEqual(['start', 'dosomething', 'error', 'end', node.id]);
    expect(reparsed.edges.map(edge => [edge.source, edge.target])).toContainEqual(['start', node.id]);
    expect(reparsed.nodeMap[node.id]).toMatchObject({
      ox: 333.5,
      oy: 177.25,
      _positionIsCenter: true,
    });
    expect(reparsed.nodeMap[node.id].properties.owner).toBe('ui-team');
  });
});

// The "Migrate join semantics" action: an explicit, undoable, one-step edit that materialises
// the currently-inferred policy on every existing fan-in and stamps the marker -- it is what makes a
// legacy document (parsed without `join.semantics`, per parseGraphML) reachable at all, since
// JoinSemantics.migrate on the Java side has no caller of its own.
describe('migrateJoinSemantics', () => {
  function legacyFanInDocument() {
    const graph = createWorkflowDocument();
    delete graph.graphProperties['join.semantics'];
    graph.nodes.push(createNode('a', 'a', 'PASSTHROUGH'), createNode('b', 'b', 'PASSTHROUGH'));
    graph.nodes.push(createNode('merge', 'merge', 'PASSTHROUGH'));
    graph.edges.push(
      createEdge('e-start-a', 'start', 'a', 'continue'),
      createEdge('e-start-b', 'start', 'b', 'continue'),
      createEdge('e-a-merge', 'a', 'merge', 'continue'),
      createEdge('e-b-merge', 'b', 'merge', 'continue'),
      createEdge('e-a-error', 'a', 'error', 'failed'),
      createEdge('e-b-error', 'b', 'error', 'failed'),
    );
    graph.nodeMap = Object.fromEntries(graph.nodes.map(node => [node.id, node]));
    return graph;
  }

  it('materialises the inferred policy, stamps the marker, and is one undo step', () => {
    const graph = legacyFanInDocument();
    const history = createCommandHistory();

    const command = migrateJoinSemantics(graph, history);

    expect(command).not.toBeNull();
    expect(graph.graphProperties['join.semantics']).toBe('declared');
    expect(graph.nodeMap.merge.properties.joinPolicy).toBe('all');
    expect(graph.nodeMap.error.properties.joinQuorum).toBe('1');
    expect(history.canUndo()).toBe(true);

    history.undo(graph);
    expect(Object.hasOwn(graph.graphProperties, 'join.semantics')).toBe(false);
    expect(Object.hasOwn(graph.nodeMap.merge.properties, 'joinPolicy')).toBe(false);
    expect(Object.hasOwn(graph.nodeMap.error.properties, 'joinQuorum')).toBe(false);
  });

  it('is a no-op returning null on a document that already declares the marker', () => {
    const graph = legacyFanInDocument();
    graph.graphProperties['join.semantics'] = 'declared';
    const history = createCommandHistory();

    expect(migrateJoinSemantics(graph, history)).toBeNull();
    expect(history.canUndo()).toBe(false);
    expect(Object.hasOwn(graph.nodeMap.merge.properties, 'joinPolicy')).toBe(false);
  });
});
