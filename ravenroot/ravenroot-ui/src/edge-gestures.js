// Edge authoring gestures and their validation (UI-02).
//
// One state machine serves all three input routes — pointer drag, keyboard, and the inspector form
// — so "create an edge" and "reconnect an edge" mean exactly the same thing however the user got
// there. Only the input differs; the rules, the refusals and the wording do not. That is what makes
// the keyboard route a first-class path rather than a reduced imitation of the mouse one.
//
// The module is pure: it decides and describes, it never touches the document or the renderer.
// Committing a gesture is the caller's job, and the caller routes it through the command model so
// edges undo exactly like nodes do.

import { NODE_KINDS } from './graph-document.js';
import { stableEdgeIdViolation } from './stable-edge-id.js';

export const EDGE_ENDPOINTS = ['source', 'target'];

// A pointer edge begins on press so the preview can appear without latency, but it does not become
// an authoring intent until the pointer travels a visible distance. The slower alternative keeps
// the gesture usable for people who cannot make an eight-pixel motion reliably: after a deliberate
// hold, two pixels are enough. Both thresholds are exported so the state machine and browser tests
// assert the contract instead of duplicating magic numbers.
export const EDGE_POINTER_DRAG_DISTANCE = 8;
export const EDGE_POINTER_HOLD_DISTANCE = 2;
export const EDGE_POINTER_HOLD_MS = 300;

// ═══════════════════════════════════════════════════════════════
// VALIDATION
// ═══════════════════════════════════════════════════════════════

// Edge identity follows the same exact, non-normalizing contract as GraphML ingestion and runtime
// observation. Leading, trailing and internal whitespace are significant; only a blank or otherwise
// invalid StableEdgeId is refused.
export function validateEdgeId(graph, candidate, { existingId = null } = {}) {
  const id = candidate;
  const violation = stableEdgeIdViolation(id);
  if (violation) return refuse(`Edge ID ${violation}`);
  const clash = (graph?.edges || []).some(edge => edge.id === id && edge.id !== existingId);
  if (clash) return refuse(`Edge ID '${id}' already exists`);
  return { ok: true, reason: '' };
}

// The topology rules the editor refuses to author. They are deliberately narrower than
// validateWorkflow(): this runs on every candidate target while the user is still dragging, so it
// only rejects what is wrong no matter what the user does next.
//
// Parallel edges between the same pair are NOT rejected. Ravenroot models them on purpose — edges
// carry a `parallel` flag, and the N8N2/N8N3 layouts exist to draw several edges between the same
// two nodes on distinct ports. Refusing them here would forbid a supported modelling construct.
export function validateEdgeConnection(graph, { source, target, edgeId = null } = {}) {
  const sourceNode = lookupNode(graph, source);
  const targetNode = lookupNode(graph, target);
  if (!source) return refuse('Choose a source node');
  if (!target) return refuse('Choose a target node');
  if (!sourceNode) return refuse(`Unknown source node '${source}'`);
  if (!targetNode) return refuse(`Unknown target node '${target}'`);
  if (targetNode.kind === 'START') return refuse(`${nodeLabel(targetNode)} is the START node and takes no incoming edge`);
  if (sourceNode.kind === 'END') return refuse(`${nodeLabel(sourceNode)} is the END node and takes no outgoing edge`);
  if (edgeId !== null && !(graph?.edges || []).some(edge => edge.id === edgeId)) {
    return refuse(`Unknown edge '${edgeId}'`);
  }
  return { ok: true, reason: '' };
}

// Known node kinds are the other half of "immediate validation of references": an edge that points
// at a node whose kind the runtime does not know is a document the server will reject later.
export function unknownNodeKinds(graph) {
  return (graph?.nodes || []).filter(node => !NODE_KINDS.includes(node.kind)).map(node => node.id);
}

function lookupNode(graph, id) {
  if (!id) return null;
  return graph?.nodeMap?.[id] || (graph?.nodes || []).find(node => node.id === id) || null;
}

function nodeLabel(node) {
  return node?.name ? `${node.name} (${node.id})` : `'${node?.id}'`;
}

function refuse(reason) {
  return { ok: false, reason };
}

// ═══════════════════════════════════════════════════════════════
// GESTURE STATE MACHINE
// ═══════════════════════════════════════════════════════════════

export function idleEdgeGesture() {
  return { mode: 'idle', sourceId: null, edgeId: null, endpoint: null };
}

// A gesture belongs to the document and renderer that received its opening press/key, not to the
// document that happens to be active when a later pointermove, tapend, or Enter is delivered. Keep
// every mutable collaborator in one captured value so callers cannot accidentally assemble a
// commit from the active graph and a preview from the previous renderer.
export function createEdgeGestureSession({
  documentId,
  owner,
  renderer,
  cy,
  graph,
  history,
  ghost,
  gesture,
  visualStyle,
  layoutMode,
  fontSize,
} = {}) {
  if (documentId == null) throw new TypeError('An edge gesture session requires a documentId.');
  if (!owner || !renderer || !cy || !graph || !history || !ghost) {
    throw new TypeError('An edge gesture session requires its owner, renderer, graph, history, Cytoscape instance, and ghost.');
  }
  if (!gesture || gesture.mode === 'idle') {
    throw new TypeError('An edge gesture session requires a non-idle gesture.');
  }
  return {
    documentId: String(documentId),
    owner,
    renderer,
    cy,
    graph,
    history,
    ghost,
    gesture,
    visualStyle,
    layoutMode,
    fontSize,
    pointer: null,
    lastPointerPosition: null,
  };
}

export function edgeGestureSessionOwns(session, { documentId, owner, renderer, cy, graph, history } = {}) {
  if (!session) return false;
  return session.documentId === String(documentId)
    && session.owner === owner
    && session.renderer === renderer
    && session.cy === cy
    && session.graph === graph
    && session.history === history;
}

// ═══════════════════════════════════════════════════════════════
// POINTER INTENT STATE MACHINE
// ═══════════════════════════════════════════════════════════════

export function beginPointerEdgeGesture(position, startedAt = 0) {
  const point = pointerPoint(position);
  return {
    phase: 'pressed',
    origin: point,
    latest: point,
    startedAt: finiteNumber(startedAt),
    elapsedMs: 0,
    maxDistance: 0,
    candidateId: null,
  };
}

// `candidate` is deliberately renderer-neutral. The app obtains it from Cytoscape, while unit
// tests can drive the exact same transitions without a canvas or synthetic DOM events.
export function updatePointerEdgeGesture(state, {
  position,
  timestamp = state?.startedAt ?? 0,
  candidateId = null,
  candidateValid = false,
  sourceId = null,
} = {}) {
  if (!state || state.phase === 'finished') return state;
  const latest = pointerPoint(position || state.latest);
  const distance = Math.sqrt(squaredDistance(latest, state.origin));
  const maxDistance = Math.max(state.maxDistance || 0, distance);
  const elapsedMs = Math.max(0, finiteNumber(timestamp) - finiteNumber(state.startedAt));
  const intentional = maxDistance >= EDGE_POINTER_DRAG_DISTANCE
    || (maxDistance >= EDGE_POINTER_HOLD_DISTANCE && elapsedMs >= EDGE_POINTER_HOLD_MS);

  let phase = 'pressed';
  if (intentional) {
    if (!candidateId) phase = 'dragging';
    else if (!candidateValid) phase = 'target-invalid';
    else if (candidateId === sourceId) phase = 'target-self';
    else phase = 'target-valid';
  }
  return { ...state, phase, latest, elapsedMs, maxDistance, candidateId: intentional ? candidateId : null };
}

export function finishPointerEdgeGesture(state) {
  if (!state || state.phase === 'pressed') return { outcome: 'select', candidateId: null };
  if (state.phase === 'target-valid' || state.phase === 'target-self') {
    return { outcome: 'commit', candidateId: state.candidateId };
  }
  return { outcome: 'cancel', candidateId: null };
}

function pointerPoint(position = {}) {
  return { x: finiteNumber(position.x), y: finiteNumber(position.y) };
}

function finiteNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

export function beginConnectGesture(graph, sourceId) {
  const node = lookupNode(graph, sourceId);
  if (!node) return idleEdgeGesture();
  if (node.kind === 'END') return idleEdgeGesture();
  return { mode: 'connect', sourceId, edgeId: null, endpoint: null };
}

// `endpoint` names which end travels. The other end stays pinned, which is what distinguishes a
// reconnect from deleting the edge and drawing a new one: the id, the outcome and every additional
// property survive the gesture.
export function beginReconnectGesture(graph, edgeId, endpoint) {
  const edge = (graph?.edges || []).find(candidate => candidate.id === edgeId);
  if (!edge || !EDGE_ENDPOINTS.includes(endpoint)) return idleEdgeGesture();
  return { mode: 'reconnect', sourceId: null, edgeId, endpoint };
}

// What the gesture would produce if it were completed on `candidateId`, and why it may not be.
// The renderer asks this on every hover and every cursor move, so it must stay side-effect free.
export function edgeGestureCandidate(state, graph, candidateId) {
  if (!state || state.mode === 'idle') return { ok: false, reason: 'No edge gesture is in progress' };
  if (state.mode === 'connect') {
    const proposal = { source: state.sourceId, target: candidateId };
    return { ...validateEdgeConnection(graph, proposal), ...proposal };
  }
  const edge = (graph?.edges || []).find(candidate => candidate.id === state.edgeId);
  if (!edge) return { ok: false, reason: `Unknown edge '${state.edgeId}'` };
  const proposal = state.endpoint === 'source'
    ? { source: candidateId, target: edge.target }
    : { source: edge.source, target: candidateId };
  if (candidateId === edge[state.endpoint]) {
    return { ...proposal, ok: false, reason: `Already the ${state.endpoint} of ${edge.id}` };
  }
  return { ...validateEdgeConnection(graph, { ...proposal, edgeId: state.edgeId }), ...proposal };
}

// The endpoint a pointer grabbed, decided by which end of the edge it started nearer to. Grabbing
// an edge near its arrow means "move the arrow", which is the same thing the gesture then does.
export function nearestEndpoint(position, sourcePosition, targetPosition) {
  return squaredDistance(position, sourcePosition) <= squaredDistance(position, targetPosition)
    ? 'source' : 'target';
}

function squaredDistance(left = {}, right = {}) {
  const dx = Number(left.x || 0) - Number(right.x || 0);
  const dy = Number(left.y || 0) - Number(right.y || 0);
  return (dx * dx) + (dy * dy);
}

// ═══════════════════════════════════════════════════════════════
// ANNOUNCEMENTS
// ═══════════════════════════════════════════════════════════════

// Everything the pointer conveys by position and colour has to reach a keyboard user as words, so
// each transition has one sentence and the live region reads it. Direction is always spoken as
// "from X to Y" because an arrowhead is not available to a screen reader.
export function describeEdgeGesture(state, graph, candidateId = null) {
  if (!state || state.mode === 'idle') return '';
  if (state.mode === 'connect') {
    const source = lookupNode(graph, state.sourceId);
    const opening = `Connecting from ${nodeLabel(source)}.`;
    return `${opening} ${describeCandidate(state, graph, candidateId, 'target')}`;
  }
  const edge = (graph?.edges || []).find(candidate => candidate.id === state.edgeId);
  if (!edge) return '';
  const pinnedEnd = state.endpoint === 'source' ? 'target' : 'source';
  const pinned = lookupNode(graph, edge[pinnedEnd]);
  const opening = `Reconnecting the ${state.endpoint} of edge ${edge.id}, `
    + `${pinnedEnd} stays ${nodeLabel(pinned)}.`;
  return `${opening} ${describeCandidate(state, graph, candidateId, state.endpoint)}`;
}

function describeCandidate(state, graph, candidateId, role) {
  if (!candidateId) return `Choose a ${role} node, then press Enter. Escape cancels.`;
  const candidate = edgeGestureCandidate(state, graph, candidateId);
  const node = lookupNode(graph, candidateId);
  if (!candidate.ok) return `${nodeLabel(node)} cannot be the ${role}: ${candidate.reason}.`;
  return `Press Enter to make ${nodeLabel(node)} the ${role}.`;
}

export function describeEdge(edge, graph) {
  if (!edge) return '';
  const source = lookupNode(graph, edge.source);
  const target = lookupNode(graph, edge.target);
  const outcome = edge.outcome || 'continue';
  return `Edge ${edge.id}, from ${nodeLabel(source)} to ${nodeLabel(target)}, outcome ${outcome}.`;
}
