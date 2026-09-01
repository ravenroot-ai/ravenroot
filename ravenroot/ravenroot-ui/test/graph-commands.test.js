import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  applyCommand,
  batchUpdateNodePropertiesCommand,
  commandTargets,
  compositeCommand,
  createCommandHistory,
  discardChangesMessage,
  insertNodesCommand,
  moveNodesCommand,
  removeEdgesCommand,
  removeNodesCommand,
  shouldWarnBeforeUnload,
  updateEdgeCommand,
  updateGraphPropertiesCommand,
  updateNodeCommand,
} from '../src/graph-commands.js';
import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import {
  addConnectedNodeAt,
  addNodeAt,
  connectNodes,
  deleteElements,
  duplicateNode,
  historyShortcut,
  moveNodesTo,
  updateEdgeFields,
  updateNodeFields,
  updateNodePropertiesBatch,
} from '../src/graph-editing.js';
import { parseGraphML } from '../src/graph-parsers.js';
import { xmlSemantics } from './xml-semantics.js';

const CORPUS = resolve('../ravenroot-core/src/test/resources/graphml-corpus/accepted');

function corpus(name) {
  return readFileSync(resolve(CORPUS, name), 'utf8');
}

describe('graph command primitives', () => {
  it('returns an inverse that removes exactly what an insert added', () => {
    const graph = createWorkflowDocument();
    const node = { id: 'worker', name: 'Worker', kind: 'PASSTHROUGH', properties: {}, propertyTypes: {} };

    const inverse = applyCommand(graph, insertNodesCommand([{ node, index: 1 }], 'Add worker'));

    expect(graph.nodes.map(candidate => candidate.id))
      .toEqual(['start', 'worker', 'dosomething', 'error', 'end']);
    expect(graph.nodeMap.worker).toBe(node);
    applyCommand(graph, inverse);
    expect(graph.nodes.map(candidate => candidate.id)).toEqual(['start', 'dosomething', 'error', 'end']);
    expect(graph.nodeMap.worker).toBeUndefined();
  });

  it('restores removed elements at their original array positions', () => {
    const graph = parseGraphML(corpus('topology.graphml'));
    const before = graph.nodes.map(node => node.id);
    const middle = before.slice(1, -1);

    const inverse = applyCommand(graph, removeNodesCommand(middle, 'Delete middles'));
    expect(graph.nodes.map(node => node.id)).toEqual(before.filter(id => !middle.includes(id)));

    applyCommand(graph, inverse);
    expect(graph.nodes.map(node => node.id)).toEqual(before);
  });

  it('restores the very object it removed, keeping parser-owned fields intact', () => {
    const graph = parseGraphML(corpus('complex-extensions.graphml'));
    const original = graph.nodes.find(node => node.id === 'end');
    const canonical = original._sourceCanonical;

    const inverse = applyCommand(graph, removeNodesCommand(['end'], 'Delete end'));
    applyCommand(graph, inverse);

    const restored = graph.nodes.find(node => node.id === 'end');
    expect(restored).toBe(original);
    expect(restored._sourceCanonical).toBe(canonical);
  });

  it('inverts a property patch field by field and unsets keys the element did not have', () => {
    const graph = createWorkflowDocument();
    const inverse = applyCommand(graph, updateNodeCommand('start', {
      name: 'Kickoff',
      properties: { owner: 'ui-team' },
      reviewer: 'reviewer-a',
    }));

    expect(graph.nodeMap.start).toMatchObject({ name: 'Kickoff', reviewer: 'reviewer-a' });
    expect(graph.nodeMap.start.properties).toEqual({ owner: 'ui-team' });
    expect(inverse.unset).toEqual(['reviewer']);

    applyCommand(graph, inverse);
    expect(graph.nodeMap.start.name).toBe('Start');
    expect(graph.nodeMap.start.properties).toEqual({});
    expect(Object.hasOwn(graph.nodeMap.start, 'reviewer')).toBe(false);
  });

  // `join.semantics` and any other graph-level property go through this command, not a direct
  // assignment into `graph.graphProperties` -- otherwise stamping the marker would not be an undo
  // step and the document would not report itself dirty for it (see graph-editing.js's
  // migrateJoinSemantics).
  it('patches graph-level properties field by field and inverts to unset a key that was absent', () => {
    const graph = createWorkflowDocument();
    delete graph.graphProperties['join.semantics'];

    const inverse = applyCommand(graph, updateGraphPropertiesCommand({ 'join.semantics': 'declared' }));
    expect(graph.graphProperties['join.semantics']).toBe('declared');
    expect(inverse.unset).toEqual(['join.semantics']);

    applyCommand(graph, inverse);
    expect(Object.hasOwn(graph.graphProperties, 'join.semantics')).toBe(false);
  });

  it('restores the previous coordinates and center flag of a move', () => {
    const graph = createWorkflowDocument();
    const before = { ox: graph.nodeMap.start.ox, oy: graph.nodeMap.start.oy };

    const inverse = applyCommand(graph, moveNodesCommand([{ id: 'start', ox: 777, oy: 888 }], 'Move'));
    expect(graph.nodeMap.start).toMatchObject({ ox: 777, oy: 888, _positionIsCenter: true });

    applyCommand(graph, inverse);
    expect(graph.nodeMap.start).toMatchObject(before);
    expect(graph.nodeMap.start._positionIsCenter).toBeUndefined();
  });

  it('undoes a composite in reverse order as one operation', () => {
    const graph = createWorkflowDocument();
    const inverse = applyCommand(graph, compositeCommand([
      removeEdgesCommand(['edge-start-dosomething'], 'Delete edge'),
      removeNodesCommand(['start'], 'Delete node'),
    ], 'Delete start with its edges'));

    expect(graph.nodes.map(node => node.id)).toEqual(['dosomething', 'error', 'end']);
    expect(graph.edges.map(edge => edge.id)).toEqual(['edge-dosomething-end', 'edge-dosomething-error']);
    expect(inverse.type).toBe('composite');
    expect(inverse.commands.map(command => command.type)).toEqual(['insert-nodes', 'insert-edges']);

    applyCommand(graph, inverse);
    expect(graph.nodes.map(node => node.id)).toEqual(['start', 'dosomething', 'error', 'end']);
    expect(graph.edges.map(edge => edge.id))
      .toEqual(['edge-start-dosomething', 'edge-dosomething-end', 'edge-dosomething-error']);
  });

  it('refuses commands that address elements the document does not contain', () => {
    const graph = createWorkflowDocument();
    expect(() => applyCommand(graph, removeNodesCommand(['ghost']))).toThrow("unknown node 'ghost'");
    expect(() => applyCommand(graph, removeEdgesCommand(['ghost']))).toThrow("unknown edge 'ghost'");
    expect(() => applyCommand(graph, updateEdgeCommand('ghost', { outcome: 'x' }))).toThrow("unknown edge 'ghost'");
    expect(() => applyCommand(graph, insertNodesCommand([{ node: { id: 'start' }, index: 0 }])))
      .toThrow("Node 'start' already exists");
    expect(() => applyCommand(graph, updateNodeCommand('start', { id: 'renamed' })))
      .toThrow('A node id cannot be patched');
    expect(() => applyCommand(graph, { type: 'teleport' })).toThrow("Unknown graph command type 'teleport'");
  });

  it('reports the elements a command touched, recursing into composites', () => {
    expect(commandTargets(compositeCommand([
      removeEdgesCommand(['e1', 'e2']),
      removeNodesCommand(['n1']),
      moveNodesCommand([{ id: 'n2', ox: 1, oy: 2 }]),
    ]))).toEqual({ nodeIds: ['n1', 'n2'], edgeIds: ['e1', 'e2'] });
  });

  it('preflights a property batch before mutation and leaves every map unchanged on failure', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    graph.nodeMap.start.properties = { owner: 'before', private: 'keep' };
    graph.nodeMap.start.propertyTypes = { owner: 'string', private: 'string' };
    const before = serializeGraphML(graph);
    const command = batchUpdateNodePropertiesCommand([
      {
        id: 'start',
        properties: { set: { owner: 'after' }, unset: [] },
        propertyTypes: { set: { owner: 'string' }, unset: [] },
      },
      {
        id: 'ghost',
        properties: { set: { owner: 'after' }, unset: [] },
        propertyTypes: { set: { owner: 'string' }, unset: [] },
      },
    ]);

    expect(() => history.execute(graph, command)).toThrow("unknown node 'ghost'");
    expect(history.depth()).toBe(0);
    expect(history.isDirty()).toBe(false);
    expect(graph.nodeMap.start.properties).toEqual({ owner: 'before', private: 'keep' });
    expect(graph.nodeMap.start.propertyTypes).toEqual({ owner: 'string', private: 'string' });
    expect(serializeGraphML(graph)).toBe(before);
  });

  it('rolls back a composite when a later command fails and records no history', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = serializeGraphML(graph);
    const revision = history.revision();
    const command = compositeCommand([
      updateNodeCommand('start', { name: 'Changed' }),
      updateNodeCommand('ghost', { name: 'Never' }),
    ], 'Late failure');

    expect(() => history.execute(graph, command)).toThrow("unknown node 'ghost'");
    expect(serializeGraphML(graph)).toBe(before);
    expect(history.depth()).toBe(0);
    expect(history.revision()).toBe(revision);
  });

  it('increments revision after each successful execute, undo, redo, and reset', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    expect(history.revision()).toBe(0);
    history.execute(graph, updateNodeCommand('start', { name: 'Changed' }));
    expect(history.revision()).toBe(1);
    history.undo(graph);
    expect(history.revision()).toBe(2);
    history.redo(graph);
    expect(history.revision()).toBe(3);
    history.reset();
    expect(history.revision()).toBe(4);
  });

  it('restores exact property and type-map presence through a batch inverse', () => {
    const graph = createWorkflowDocument();
    graph.nodeMap.start.properties = { owner: 'a', remove: 'yes', private: 'keep' };
    graph.nodeMap.start.propertyTypes = { owner: 'string', orphanType: 'long', private: 'string' };
    graph.nodeMap.end.properties = { owner: 'b', private: 'also-keep' };
    graph.nodeMap.end.propertyTypes = { owner: 'string', remove: 'boolean' };
    const before = structuredClone({
      start: [graph.nodeMap.start.properties, graph.nodeMap.start.propertyTypes],
      end: [graph.nodeMap.end.properties, graph.nodeMap.end.propertyTypes],
    });
    const command = batchUpdateNodePropertiesCommand(['start', 'end'].map(id => ({
      id,
      properties: { set: { owner: 'team' }, unset: ['remove'] },
      propertyTypes: { set: { owner: 'string' }, unset: ['remove'] },
    })));

    const inverse = applyCommand(graph, command);
    expect(graph.nodeMap.start.properties).toEqual({ owner: 'team', private: 'keep' });
    expect(graph.nodeMap.end.properties).toEqual({ owner: 'team', private: 'also-keep' });
    expect(commandTargets(command)).toEqual({ nodeIds: ['start', 'end'], edgeIds: [] });
    applyCommand(graph, inverse);
    expect(graph.nodeMap.start.properties).toEqual(before.start[0]);
    expect(graph.nodeMap.start.propertyTypes).toEqual(before.start[1]);
    expect(graph.nodeMap.end.properties).toEqual(before.end[0]);
    expect(graph.nodeMap.end.propertyTypes).toEqual(before.end[1]);
  });
});

describe('command history', () => {
  it('coalesces one edit session into one undo while preserving the pre-session value', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = graph.nodeMap.dosomething.name;
    history.execute(graph, updateNodeCommand('dosomething', { name: 'A' }), { coalesceKey: 'name-focus-1' });
    history.execute(graph, updateNodeCommand('dosomething', { name: 'AB' }), { coalesceKey: 'name-focus-1' });

    expect(history.depth()).toBe(1);
    expect(graph.nodeMap.dosomething.name).toBe('AB');
    history.undo(graph);
    expect(graph.nodeMap.dosomething.name).toBe(before);
    history.redo(graph);
    expect(graph.nodeMap.dosomething.name).toBe('AB');
  });

  it('does not rewrite an entry that is the current save point', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    history.execute(graph, updateNodeCommand('dosomething', { name: 'A' }), { coalesceKey: 'session' });
    history.markSaved();
    history.execute(graph, updateNodeCommand('dosomething', { name: 'AB' }), { coalesceKey: 'session' });

    expect(history.depth()).toBe(2);
    expect(history.isDirty()).toBe(true);
    history.undo(graph);
    expect(graph.nodeMap.dosomething.name).toBe('A');
    expect(history.isDirty()).toBe(false);
  });

  it('restores the previous edit when a coalesced replacement fails', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = graph.nodeMap.dosomething.name;
    history.execute(graph, updateNodeCommand('dosomething', { name: 'A' }), { coalesceKey: 'session' });

    expect(() => history.execute(
      graph,
      updateNodeCommand('missing-node', { name: 'Invalid' }),
      { coalesceKey: 'session' },
    )).toThrow();

    expect(history.depth()).toBe(1);
    expect(graph.nodeMap.dosomething.name).toBe('A');
    history.undo(graph);
    expect(graph.nodeMap.dosomething.name).toBe(before);
  });

  it('duplicates a node as one GraphML-stable undo/redo step without copying edges', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    graph.nodeMap.dosomething.properties = { owner: 'ui-team' };
    graph.nodeMap.dosomething.propertyTypes = { owner: 'string' };
    const edges = structuredClone(graph.edges);
    const before = xmlSemantics(serializeGraphML(graph));

    const duplicate = duplicateNode(graph, 'dosomething', history);
    const edited = xmlSemantics(serializeGraphML(graph));

    expect(history.state()).toMatchObject({
      depth: 1, undoLabel: 'Duplicate dosomething as dosomething-copy-1', dirty: true,
    });
    expect(graph.edges).toEqual(edges);
    expect(graph.nodeMap[duplicate.id].properties).toEqual({ owner: 'ui-team' });
    history.undo(graph);
    expect(xmlSemantics(serializeGraphML(graph))).toBe(before);
    expect(graph.edges).toEqual(edges);
    history.redo(graph);
    expect(xmlSemantics(serializeGraphML(graph))).toBe(edited);
    expect(graph.edges).toEqual(edges);
  });

  it('applies a selected-node property batch as exactly one undo/redo step', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    graph.nodeMap.start.properties = { owner: 'a', untouched: 'first' };
    graph.nodeMap.start.propertyTypes = { owner: 'string', untouched: 'string' };
    graph.nodeMap.end.properties = { owner: 'b', untouched: 'second' };
    graph.nodeMap.end.propertyTypes = { owner: 'string', untouched: 'string' };
    const before = serializeGraphML(graph);
    const entries = ['start', 'end'].map(id => ({
      id,
      properties: { set: { owner: 'team' }, unset: [] },
      propertyTypes: { set: { owner: 'string' }, unset: [] },
    }));

    updateNodePropertiesBatch(graph, entries, history);

    const edited = serializeGraphML(graph);
    expect(history.depth()).toBe(1);
    expect(graph.nodeMap.start.properties).toEqual({ owner: 'team', untouched: 'first' });
    expect(graph.nodeMap.end.properties).toEqual({ owner: 'team', untouched: 'second' });
    history.undo(graph);
    expect(serializeGraphML(graph)).toBe(before);
    history.redo(graph);
    expect(serializeGraphML(graph)).toBe(edited);
  });

  it('adds a typed toolbox node and its edge as one GraphML-stable undo/redo step', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const baseline = xmlSemantics(serializeGraphML(graph));
    const descriptor = {
      behavior: 'template', displayName: 'Template', visualType: 'flow',
      properties: [
        { name: 'retries', type: 'integer', defaultValue: '3' },
        { name: 'enabled', type: 'boolean', defaultValue: 'true' },
      ],
    };

    const { node, edge, reason } = addConnectedNodeAt(
      graph, { x: 321.5, y: 222.25 }, 'start', history, { descriptor },
    );
    node._positionIsCenter = true;
    const edited = xmlSemantics(serializeGraphML(graph));

    expect(reason).toBe('');
    expect(history.depth()).toBe(1);
    expect(node).toMatchObject({
      id: 'template-1', behavior: 'template', ox: 321.5, oy: 222.25,
      properties: { retries: '3', enabled: 'true' },
      propertyTypes: { retries: 'long', enabled: 'boolean' },
    });
    expect(edge).toMatchObject({ source: 'start', target: 'template-1' });

    history.undo(graph);
    expect(xmlSemantics(serializeGraphML(graph))).toBe(baseline);
    expect(graph.nodeMap['template-1']).toBeUndefined();
    expect(graph.edges.some(candidate => candidate.id === edge.id)).toBe(false);

    history.redo(graph);
    expect(xmlSemantics(serializeGraphML(graph))).toBe(edited);
    const roundTripped = parseGraphML(serializeGraphML(graph));
    expect(roundTripped.nodeMap['template-1']).toMatchObject({
      behavior: 'template', ox: 321.5, oy: 222.25,
      properties: { retries: '3', enabled: 'true' },
      propertyTypes: { retries: 'long', enabled: 'boolean' },
    });
    expect(roundTripped.edges.find(candidate => candidate.id === edge.id))
      .toMatchObject({ source: 'start', target: 'template-1' });
  });

  it('validates a toolbox connection before mutation and refuses an END source', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    const before = serializeGraphML(graph);

    const result = addConnectedNodeAt(graph, { x: 90, y: 80 }, 'end', history, {
      descriptor: { behavior: 'template', displayName: 'Template', properties: [] },
    });

    expect(result).toMatchObject({ node: null, edge: null });
    expect(result.reason).toMatch(/END node.*no outgoing edge/);
    expect(history.depth()).toBe(0);
    expect(serializeGraphML(graph)).toBe(before);
  });

  it('undoes and redoes nodes, edges, properties and moves', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();

    const node = addNodeAt(graph, { x: 10, y: 20 }, history);
    const edge = connectNodes(graph, 'start', node.id, history);
    updateNodeFields(graph, node.id, { name: 'Renamed' }, history);
    updateEdgeFields(graph, edge.id, { outcome: 'failed' }, history);
    moveNodesTo(graph, [{ id: node.id, ox: 500, oy: 600 }], history);

    expect(history.depth()).toBe(5);
    for (let step = 0; step < 5; step += 1) history.undo(graph);
    expect(history.canUndo()).toBe(false);
    expect(graph.nodes.map(candidate => candidate.id)).toEqual(['start', 'dosomething', 'error', 'end']);
    expect(graph.edges.map(candidate => candidate.id))
      .toEqual(['edge-start-dosomething', 'edge-dosomething-end', 'edge-dosomething-error']);

    for (let step = 0; step < 5; step += 1) history.redo(graph);
    expect(graph.nodeMap[node.id]).toMatchObject({ name: 'Renamed', ox: 500, oy: 600 });
    expect(graph.edges.find(candidate => candidate.id === edge.id).outcome).toBe('failed');
  });

  it('undoes a node deletion and its incident edges as a single step', () => {
    const graph = parseGraphML(corpus('topology.graphml'));
    const history = createCommandHistory();
    const before = serializeGraphML(graph);
    const nodesBefore = graph.nodes.map(node => node.id);
    const edgesBefore = graph.edges.map(edge => edge.id);
    // worker carries two parallel edges, a self-loop and the finish edge.
    expect(edgesBefore).toEqual(['parallel-a', 'parallel-b', 'self-loop', 'finish']);

    const removed = deleteElements(graph, ['worker'], [], history);
    expect(removed.edgeIds).toEqual(edgesBefore);
    expect(graph.edges).toHaveLength(0);
    // Four edges and one node vanished, but the user performed one gesture, so it is one step.
    expect(history.depth()).toBe(1);

    history.undo(graph);

    expect(history.canUndo()).toBe(false);
    expect(graph.nodes.map(node => node.id)).toEqual(nodesBefore);
    expect(graph.edges.map(edge => edge.id)).toEqual(edgesBefore);
    expect(graph.edges.map(edge => edge.outcome)).toEqual(['a', 'b', 'retry', 'continue']);
    expect(xmlSemantics(serializeGraphML(graph))).toBe(xmlSemantics(before));
  });

  it('undoes a multi-node drag as a single step', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();

    moveNodesTo(graph, [
      { id: 'start', ox: 11, oy: 12 },
      { id: 'end', ox: 21, oy: 22 },
    ], history);

    expect(history.depth()).toBe(1);
    expect(history.undoLabel()).toBe('Move 2 nodes');
    history.undo(graph);
    expect(graph.nodeMap.start).toMatchObject({ ox: 120, oy: 220 });
    expect(graph.nodeMap.end).toMatchObject({ ox: 480, oy: 220 });
    expect(history.canUndo()).toBe(false);
  });

  it('lets a layout transaction describe the arrangement instead of a pointer move', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();

    moveNodesTo(graph, [
      { id: 'start', ox: 30, oy: 40 },
      { id: 'end', ox: 230, oy: 40 },
    ], history, 'Arrange Flow');

    expect(history.depth()).toBe(1);
    expect(history.undoLabel()).toBe('Arrange Flow');
    history.undo(graph);
    expect(graph.nodeMap.start).toMatchObject({ ox: 120, oy: 220 });
    expect(graph.nodeMap.end).toMatchObject({ ox: 480, oy: 220 });
  });

  it('drops the redo branch once a new command is executed', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    addNodeAt(graph, { x: 1, y: 1 }, history);
    history.undo(graph);
    expect(history.canRedo()).toBe(true);

    addNodeAt(graph, { x: 2, y: 2 }, history);
    expect(history.canRedo()).toBe(false);
  });

  it('bounds the stack and drops the oldest step at the limit', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory({ limit: 3 });
    for (let step = 0; step < 5; step += 1) addNodeAt(graph, { x: step, y: step }, history);

    expect(history.depth()).toBe(3);
    while (history.canUndo()) history.undo(graph);
    // The two trimmed insertions are no longer reversible: their nodes stay.
    expect(graph.nodes.map(node => node.id))
      .toEqual(['start', 'dosomething', 'error', 'end', 'node-1', 'node-2']);
  });
});

describe('dirty state', () => {
  it('is clean at the start, dirty after an edit and clean again after saving', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    expect(history.isDirty()).toBe(false);

    addNodeAt(graph, { x: 1, y: 1 }, history);
    expect(history.isDirty()).toBe(true);

    history.markSaved();
    expect(history.isDirty()).toBe(false);
  });

  it('is clean again after undoing back to the last save point, and dirty on either side of it', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    addNodeAt(graph, { x: 1, y: 1 }, history);
    history.markSaved();
    addNodeAt(graph, { x: 2, y: 2 }, history);
    addNodeAt(graph, { x: 3, y: 3 }, history);
    expect(history.isDirty()).toBe(true);

    history.undo(graph);
    expect(history.isDirty()).toBe(true);
    history.undo(graph);
    expect(history.isDirty()).toBe(false);

    // Undoing past the save point is unsaved work again, and so is redoing forward from it.
    history.undo(graph);
    expect(history.isDirty()).toBe(true);
    history.redo(graph);
    expect(history.isDirty()).toBe(false);
    history.redo(graph);
    expect(history.isDirty()).toBe(true);
  });

  it('stays dirty when the save point is trimmed off a bounded stack', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory({ limit: 2 });
    history.markSaved();
    expect(history.isDirty()).toBe(false);

    addNodeAt(graph, { x: 1, y: 1 }, history);
    addNodeAt(graph, { x: 2, y: 2 }, history);
    addNodeAt(graph, { x: 3, y: 3 }, history);

    while (history.canUndo()) history.undo(graph);
    // The empty-stack save point was pushed out of the window, so it can never be reached again.
    expect(history.isDirty()).toBe(true);
  });

  it('resets to clean for a new document', () => {
    const graph = createWorkflowDocument();
    const history = createCommandHistory();
    addNodeAt(graph, { x: 1, y: 1 }, history);
    history.reset();

    expect(history.isDirty()).toBe(false);
    expect(history.canUndo()).toBe(false);
    expect(history.canRedo()).toBe(false);
  });

  it('names the document in the discard prompt and warns on unload only when dirty', () => {
    expect(discardChangesMessage('flow.graphml')).toContain('flow.graphml');
    expect(discardChangesMessage('')).toContain('This workflow');
    expect(shouldWarnBeforeUnload(true)).toBe(true);
    expect(shouldWarnBeforeUnload(false)).toBe(false);
  });
});

describe('history keyboard shortcuts', () => {
  it('maps undo, redo and save without stealing text editing shortcuts', () => {
    const field = document.createElement('input');
    expect(historyShortcut({ ctrlKey: true, key: 'z', target: document.body })).toBe('undo');
    expect(historyShortcut({ metaKey: true, key: 'Z', target: document.body })).toBe('undo');
    expect(historyShortcut({ metaKey: true, shiftKey: true, key: 'z', target: document.body })).toBe('redo');
    expect(historyShortcut({ ctrlKey: true, key: 'y', target: document.body })).toBe('redo');
    expect(historyShortcut({ metaKey: true, key: 's', target: document.body })).toBe('save');
    expect(historyShortcut({ ctrlKey: true, key: 'z', target: field })).toBeNull();
    expect(historyShortcut({ key: 'z', target: document.body })).toBeNull();
    expect(historyShortcut({ ctrlKey: true, altKey: true, key: 'z', target: document.body })).toBeNull();
  });
});

// A GraphML round trip must be unchanged after undo/redo sequences. "Unchanged" is the
// definition the GraphML corpus suite already uses — same expanded-name tree, same attributes, same
// comments, same text inside <data> — not byte equality, which neither the DOM nor XMLSerializer
// promises. A random command sequence fully undone must land back on it.
describe('GraphML round-trip invariance under random undo/redo sequences', () => {
  const documents = ['canonical-minimal.graphml', 'scalar-types.graphml', 'complex-extensions.graphml',
    'defaults-and-scopes.graphml', 'topology.graphml', 'optional-edge-ids.graphml'];

  for (const name of documents) {
    it(`restores ${name} after a random command sequence is fully undone`, () => {
      for (let seed = 1; seed <= 12; seed += 1) {
        const graph = parseGraphML(corpus(name));
        const history = createCommandHistory();
        const baseline = xmlSemantics(serializeGraphML(graph));

        const applied = randomEditSequence(graph, history, seed, 8);
        expect(applied).toBeGreaterThan(0);

        while (history.canUndo()) history.undo(graph);
        expect(xmlSemantics(serializeGraphML(graph))).toBe(baseline);
      }
    });
  }

  it('returns to the same document after undoing everything and redoing everything twice', () => {
    const graph = parseGraphML(corpus('topology.graphml'));
    const history = createCommandHistory();
    const baseline = xmlSemantics(serializeGraphML(graph));

    randomEditSequence(graph, history, 99, 10);
    const edited = xmlSemantics(serializeGraphML(graph));
    expect(edited).not.toBe(baseline);

    for (let round = 0; round < 2; round += 1) {
      while (history.canUndo()) history.undo(graph);
      expect(xmlSemantics(serializeGraphML(graph))).toBe(baseline);
      while (history.canRedo()) history.redo(graph);
      expect(xmlSemantics(serializeGraphML(graph))).toBe(edited);
    }
  });
});

// A small deterministic PRNG keeps the sequence reproducible: a failure names the seed that found it.
function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = Math.imul(state ^ (state >>> 15), 1 | state);
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value;
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function randomEditSequence(graph, history, seed, steps) {
  const random = mulberry32(seed);
  const pick = list => list[Math.floor(random() * list.length)];
  let applied = 0;

  for (let step = 0; step < steps; step += 1) {
    const operation = Math.floor(random() * 6);
    if (operation === 0) {
      applied += addNodeAt(graph, { x: random() * 900, y: random() * 900 }, history) ? 1 : 0;
    } else if (operation === 1 && graph.nodes.length >= 2) {
      applied += connectNodes(graph, pick(graph.nodes).id, pick(graph.nodes).id, history) ? 1 : 0;
    } else if (operation === 2 && graph.nodes.length > 1) {
      applied += deleteElements(graph, [pick(graph.nodes).id], [], history).nodeIds.length ? 1 : 0;
    } else if (operation === 3 && graph.edges.length) {
      applied += deleteElements(graph, [], [pick(graph.edges).id], history).edgeIds.length ? 1 : 0;
    } else if (operation === 4 && graph.nodes.length) {
      const node = pick(graph.nodes);
      applied += updateNodeFields(graph, node.id, {
        name: `renamed-${step}`,
        description: `edited at step ${step}`,
        properties: { ...node.properties, [`probe${step}`]: `value-${step}` },
        propertyTypes: { ...node.propertyTypes, [`probe${step}`]: 'string' },
      }, history) ? 1 : 0;
    } else if (graph.nodes.length) {
      applied += moveNodesTo(graph, graph.nodes
        .filter(() => random() > 0.4)
        .map(node => ({ id: node.id, ox: random() * 500, oy: random() * 500 })), history) ? 1 : 0;
    }
  }
  return applied;
}
