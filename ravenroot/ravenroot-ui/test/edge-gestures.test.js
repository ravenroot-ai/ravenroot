import { describe, expect, it } from 'vitest';

import { createCommandHistory } from '../src/graph-commands.js';
import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { connectNodes, reconnectEdge } from '../src/graph-editing.js';
import {
  beginConnectGesture,
  beginPointerEdgeGesture,
  beginReconnectGesture,
  createEdgeGestureSession,
  describeEdgeGesture,
  EDGE_POINTER_DRAG_DISTANCE,
  EDGE_POINTER_HOLD_DISTANCE,
  EDGE_POINTER_HOLD_MS,
  edgeGestureCandidate,
  edgeGestureSessionOwns,
  finishPointerEdgeGesture,
  idleEdgeGesture,
  nearestEndpoint,
  updatePointerEdgeGesture,
  validateEdgeConnection,
  validateEdgeId,
} from '../src/edge-gestures.js';
import { parseGraphML } from '../src/graph-parsers.js';

function workflow() {
  const graph = createWorkflowDocument();
  for (const [id, name] of [['review', 'Review'], ['archive', 'Archive']]) {
    const node = {
      ...graph.nodes[0], id, name, kind: 'PASSTHROUGH', nodeType: 'flow',
      isStart: false, isEnd: false, properties: {}, propertyTypes: {},
    };
    graph.nodes.splice(graph.nodes.length - 1, 0, node);
    graph.nodeMap[id] = node;
  }
  return graph;
}

describe('an edge gesture session captures one document root', () => {
  it('keeps renderer, model, history, ghost, and view state on the opening document', () => {
    const graph = workflow();
    const history = createCommandHistory();
    const owner = { id: 'doc-2' };
    const cy = { name: 'cy-2' };
    const renderer = { owner, cy, token: { documentId: 'doc-2' } };
    const ghost = { dataset: { documentId: 'doc-2' } };
    const gesture = beginConnectGesture(graph, 'review');
    const session = createEdgeGestureSession({
      documentId: owner.id, owner, renderer, cy, graph, history, ghost, gesture,
      visualStyle: 'n8n', layoutMode: 'dagre', fontSize: 20,
    });

    expect(session).toMatchObject({
      documentId: 'doc-2', owner, renderer, cy, graph, history, ghost, gesture,
      visualStyle: 'n8n', layoutMode: 'dagre', fontSize: 20,
      pointer: null, lastPointerPosition: null,
    });
    expect(edgeGestureSessionOwns(session, {
      documentId: owner.id, owner, renderer, cy, graph, history,
    })).toBe(true);
  });

  it('rejects a same element id from a different document or renderer', () => {
    const graph = workflow();
    const history = createCommandHistory();
    const owner = { id: 'doc-2' };
    const cy = { name: 'cy-2' };
    const renderer = { owner, cy };
    const session = createEdgeGestureSession({
      documentId: owner.id, owner, renderer, cy, graph, history,
      ghost: {}, gesture: beginConnectGesture(graph, 'review'),
    });

    expect(edgeGestureSessionOwns(session, {
      documentId: 'doc-1', owner, renderer, cy, graph, history,
    })).toBe(false);
    expect(edgeGestureSessionOwns(session, {
      documentId: owner.id, owner, renderer: { owner, cy }, cy, graph, history,
    })).toBe(false);
    expect(edgeGestureSessionOwns(session, {
      documentId: owner.id, owner, renderer, cy: { name: 'cy-1' }, graph, history,
    })).toBe(false);
  });

  it('refuses incomplete or idle roots instead of falling back to ambient state', () => {
    expect(() => createEdgeGestureSession()).toThrow(/documentId/);
    expect(() => createEdgeGestureSession({ documentId: 'doc-1' })).toThrow(/requires its owner/);
    expect(() => createEdgeGestureSession({
      documentId: 'doc-1', owner: {}, renderer: {}, cy: {}, graph: {}, history: {}, ghost: {},
      gesture: idleEdgeGesture(),
    })).toThrow(/non-idle/);
  });
});

describe('reconnecting an edge preserves its identity and undoes as one step', () => {
  it('moves the target, keeps the id and every property, and restores them on undo', () => {
    const graph = workflow();
    const history = createCommandHistory();
    const edge = connectNodes(graph, 'start', 'review', history);
    edge.outcome = 'approved';
    edge.edgeName = 'Approval branch';
    edge.properties.slaMinutes = '30';
    edge.propertyTypes.slaMinutes = 'int';

    reconnectEdge(graph, edge.id, 'target', 'archive', history);

    const moved = graph.edges.find(candidate => candidate.id === edge.id);
    expect(moved).toMatchObject({
      id: edge.id,
      source: 'start',
      target: 'archive',
      outcome: 'approved',
      edgeName: 'Approval branch',
    });
    expect(moved.properties.slaMinutes).toBe('30');
    expect(graph.edges.filter(candidate => candidate.id === edge.id)).toHaveLength(1);

    history.undo(graph);

    const restored = graph.edges.find(candidate => candidate.id === edge.id);
    expect(restored.target).toBe('review');
    expect(restored.properties.slaMinutes).toBe('30');
  });

  it('moves the source end while the target stays pinned', () => {
    const graph = workflow();
    const history = createCommandHistory();
    const edge = connectNodes(graph, 'start', 'review', history);

    reconnectEdge(graph, edge.id, 'source', 'archive', history);

    expect(graph.edges.find(candidate => candidate.id === edge.id))
      .toMatchObject({ source: 'archive', target: 'review' });
  });

  it('is one undo step, labelled so the toolbar can name it', () => {
    const graph = workflow();
    const history = createCommandHistory();
    const edge = connectNodes(graph, 'start', 'review', history);
    const depthBefore = history.depth();

    reconnectEdge(graph, edge.id, 'target', 'archive', history);

    expect(history.depth()).toBe(depthBefore + 1);
    expect(history.undoLabel()).toContain('Reconnect');
    history.undo(graph);
    expect(graph.edges.find(candidate => candidate.id === edge.id).target).toBe('review');
    expect(history.depth()).toBe(depthBefore);
  });

  it('refuses a reconnection that would violate the same rules as a new edge', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');

    expect(reconnectEdge(graph, edge.id, 'target', 'start')).toBeNull();
    expect(reconnectEdge(graph, edge.id, 'target', 'nowhere')).toBeNull();
    expect(reconnectEdge(graph, edge.id, 'source', 'end')).toBeNull();
    expect(reconnectEdge(graph, edge.id, 'target', 'start')).toBeNull();
    expect(reconnectEdge(graph, 'missing-edge', 'target', 'archive')).toBeNull();
    expect(graph.edges.find(candidate => candidate.id === edge.id))
      .toMatchObject({ source: 'start', target: 'review' });
  });

  it('allows reconnecting an end onto the pinned end as a self-loop', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');

    expect(reconnectEdge(graph, edge.id, 'source', 'review')).not.toBeNull();
    expect(graph.edges.find(candidate => candidate.id === edge.id))
      .toMatchObject({ source: 'review', target: 'review' });
  });

  it('survives the GraphML round trip after being reconnected', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');
    edge.properties.owner = 'ui-team';
    edge.propertyTypes.owner = 'string';
    reconnectEdge(graph, edge.id, 'target', 'archive');

    const reparsed = parseGraphML(serializeGraphML(graph));

    const roundTripped = reparsed.edges.find(candidate => candidate.id === edge.id);
    expect(roundTripped).toMatchObject({ source: 'start', target: 'archive' });
    expect(roundTripped.properties.owner).toBe('ui-team');
  });
});

describe('the gesture state machine is the same for the pointer and the keyboard', () => {
  it('opens a connect gesture from a node and refuses to open one from END', () => {
    const graph = workflow();

    expect(beginConnectGesture(graph, 'review')).toMatchObject({ mode: 'connect', sourceId: 'review' });
    expect(beginConnectGesture(graph, 'end').mode).toBe('idle');
    expect(beginConnectGesture(graph, 'nowhere').mode).toBe('idle');
  });

  it('opens a reconnect gesture only for a real edge and a real endpoint', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');

    expect(beginReconnectGesture(graph, edge.id, 'source'))
      .toMatchObject({ mode: 'reconnect', edgeId: edge.id, endpoint: 'source' });
    expect(beginReconnectGesture(graph, edge.id, 'middle').mode).toBe('idle');
    expect(beginReconnectGesture(graph, 'missing', 'source').mode).toBe('idle');
  });

  it('judges each candidate target and explains every refusal', () => {
    const graph = workflow();
    const gesture = beginConnectGesture(graph, 'review');

    expect(edgeGestureCandidate(gesture, graph, 'archive')).toMatchObject({ ok: true, source: 'review', target: 'archive' });
    expect(edgeGestureCandidate(gesture, graph, 'review'))
      .toMatchObject({ ok: true, source: 'review', target: 'review' });
    expect(edgeGestureCandidate(gesture, graph, 'start').reason).toMatch(/START/);
    expect(edgeGestureCandidate(idleEdgeGesture(), graph, 'archive').ok).toBe(false);
  });

  it('reports a reconnection onto the end the edge already has, rather than silently accepting it', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');
    const gesture = beginReconnectGesture(graph, edge.id, 'target');

    expect(edgeGestureCandidate(gesture, graph, 'review')).toMatchObject({ ok: false });
    expect(edgeGestureCandidate(gesture, graph, 'review').reason).toMatch(/[Aa]lready/);
    expect(edgeGestureCandidate(gesture, graph, 'archive').ok).toBe(true);
  });

  it('picks the endpoint the pointer grabbed nearest', () => {
    expect(nearestEndpoint({ x: 10, y: 0 }, { x: 0, y: 0 }, { x: 100, y: 0 })).toBe('source');
    expect(nearestEndpoint({ x: 90, y: 0 }, { x: 0, y: 0 }, { x: 100, y: 0 })).toBe('target');
  });

  it('speaks direction, the pinned end and the reason a target is refused', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');

    const connecting = describeEdgeGesture(beginConnectGesture(graph, 'review'), graph);
    expect(connecting).toContain('Connecting from');
    expect(connecting).toMatch(/Enter/);
    expect(connecting).toMatch(/Escape/);

    const refused = describeEdgeGesture(beginConnectGesture(graph, 'review'), graph, 'start');
    expect(refused).toMatch(/cannot be the target/);
    expect(refused).toMatch(/START/);

    const reconnecting = describeEdgeGesture(beginReconnectGesture(graph, edge.id, 'target'), graph, 'archive');
    expect(reconnecting).toContain(`edge ${edge.id}`);
    // The end that is NOT moving is named, so a keyboard user knows what the edge will look like.
    expect(reconnecting).toMatch(/source stays/);
    expect(reconnecting).toMatch(/Press Enter/);

    expect(describeEdgeGesture(idleEdgeGesture(), graph)).toBe('');
  });
});

describe('pointer edge intent is explicit and continuously classified', () => {
  function move(state, x, y, timestamp, candidateId = null, candidateValid = false) {
    return updatePointerEdgeGesture(state, {
      position: { x, y }, timestamp, candidateId, candidateValid, sourceId: 'review',
    });
  }

  it('starts on press but treats a click inside the movement threshold as selection', () => {
    let state = beginPointerEdgeGesture({ x: 100, y: 80 }, 1_000);
    expect(state.phase).toBe('pressed');

    state = move(state, 100 + EDGE_POINTER_HOLD_DISTANCE - 0.1, 80, 1_000 + EDGE_POINTER_HOLD_MS + 50,
      'review', true);

    expect(state.phase).toBe('pressed');
    expect(finishPointerEdgeGesture(state)).toEqual({ outcome: 'select', candidateId: null });
  });

  it('crosses the normal movement threshold and tracks canvas, valid, self and invalid targets', () => {
    let state = beginPointerEdgeGesture({ x: 10, y: 20 }, 500);
    state = move(state, 10 + EDGE_POINTER_DRAG_DISTANCE, 20, 520);
    expect(state.phase).toBe('dragging');
    expect(finishPointerEdgeGesture(state).outcome).toBe('cancel');

    state = move(state, 40, 20, 530, 'archive', true);
    expect(state.phase).toBe('target-valid');
    expect(finishPointerEdgeGesture(state)).toEqual({ outcome: 'commit', candidateId: 'archive' });

    state = move(state, 10, 20, 540, 'review', true);
    expect(state.phase).toBe('target-self');
    expect(finishPointerEdgeGesture(state)).toEqual({ outcome: 'commit', candidateId: 'review' });

    state = move(state, 60, 20, 550, 'start', false);
    expect(state.phase).toBe('target-invalid');
    expect(finishPointerEdgeGesture(state)).toEqual({ outcome: 'cancel', candidateId: null });
  });

  it('offers a slower low-distance threshold without turning a stationary hold into a drag', () => {
    const pressed = beginPointerEdgeGesture({ x: 0, y: 0 }, 100);
    const stationary = move(pressed, 0, 0, 100 + EDGE_POINTER_HOLD_MS + 1, 'review', true);
    expect(stationary.phase).toBe('pressed');

    const deliberate = move(pressed, EDGE_POINTER_HOLD_DISTANCE, 0,
      100 + EDGE_POINTER_HOLD_MS, 'review', true);
    expect(deliberate.phase).toBe('target-self');
  });
});

describe('edge ids and references are validated before anything is committed', () => {
  it('rejects blank and duplicate ids while preserving whitespace-significant stable ids', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');

    expect(validateEdgeId(graph, '').ok).toBe(false);
    expect(validateEdgeId(graph, '  ').reason).toMatch(/nonblank/);
    expect(validateEdgeId(graph, ' padded ').ok).toBe(true);
    expect(validateEdgeId(graph, 'has space').ok).toBe(true);
    expect(validateEdgeId(graph, edge.id).reason).toMatch(/already exists/);
    expect(validateEdgeId(graph, edge.id, { existingId: edge.id }).ok).toBe(true);
    expect(validateEdgeId(graph, 'edge-99').ok).toBe(true);
  });

  it('names which end is at fault instead of refusing without a reason', () => {
    const graph = workflow();

    expect(validateEdgeConnection(graph, { source: '', target: 'review' }).reason).toMatch(/source/);
    expect(validateEdgeConnection(graph, { source: 'start', target: '' }).reason).toMatch(/target/);
    expect(validateEdgeConnection(graph, { source: 'ghost', target: 'review' }).reason).toMatch(/Unknown source/);
    expect(validateEdgeConnection(graph, { source: 'start', target: 'ghost' }).reason).toMatch(/Unknown target/);
    expect(validateEdgeConnection(graph, { source: 'start', target: 'review' }).ok).toBe(true);
  });
});
