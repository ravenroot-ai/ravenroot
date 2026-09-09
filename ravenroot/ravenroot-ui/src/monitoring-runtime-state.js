import { stableEdgeIdViolation } from './stable-edge-id.js';

export const FLOW_PULSE_MS = 1_400;
export const FLOW_BASE_WIDTH = 1.8;
export const FLOW_MAX_WIDTH = 7;
const MAX_EVENT_STREAMS = 8;
// A deployment-scoped projection sees one process instance per admitted message rather than one per
// run, so the traversal-scoped cap would evict a live stream on every arrival and turn replay
// suppression off. This is the bound on retained per-deployment history: enough to dedup the frames
// a reconnect can replay, small enough that a source listening for a week cannot grow it without end.
const MAX_DEPLOYMENT_EVENT_STREAMS = 256;
// Node outcomes a traversal can settle an arrival with. NODE_STARTED and NODE_RETRY_SCHEDULED are
// deliberately absent: they open an arrival rather than settling one.
const SETTLED_NODE_STATES = Object.freeze({
  NODE_COMPLETED: 'completed',
  NODE_FAILED: 'failed',
  NODE_DEFAULTED: 'fallback',
  NODE_BYPASSED: 'bypassed',
});
const OPENING_NODE_EVENTS = Object.freeze(new Set(['NODE_STARTED', 'NODE_RETRY_SCHEDULED']));
// How specific each settled outcome is. A standing outcome survives a less specific one until the
// node is entered again; see `observeNodeActivity` for why recency is the wrong rule here.
const OUTCOME_PRECEDENCE = Object.freeze({
  completed: 1, bypassed: 2, fallback: 3, failed: 4,
});

function finiteTime(value, fallback) {
  const time = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(time) ? time : fallback;
}

export function createMonitoringRuntimeState() {
  return {
    executionId: null,
    // Set only for a view bound to a long-lived deployment. While it holds a value the projection
    // accumulates ACROSS traversals instead of restarting at each new one -- which is the whole
    // difference between watching a run and watching a source.
    deploymentId: null,
    generation: 0,
    pending: false,
    binding: null,
    seen: new Map(),
    edges: new Map(),
    nodes: new Map(),
  };
}

export function resetMonitoringRuntimeState(state, executionId = null) {
  state.executionId = executionId;
  state.deploymentId = null;
  state.generation += 1;
  state.pending = false;
  state.binding = null;
  state.seen.clear();
  state.edges.clear();
  state.nodes.clear();
  return state;
}

/**
 * Binds the projection to one long-lived deployment instead of one traversal.
 *
 * A source session emits traversals without limit and the client is told none of their ids in
 * advance, so a per-traversal binding can only ever be replaced -- which is what erased the edge
 * flow on every admitted message. The deployment is the identity that outlives them, and it is the
 * one the events already carry.
 */
export function bindMonitoringRuntimeStateToDeployment(state, deploymentId) {
  const identity = typeof deploymentId === 'string' && deploymentId ? deploymentId : null;
  if (state.deploymentId === identity) return { preserved: true, reason: 'matching-deployment' };
  resetMonitoringRuntimeState(state, null);
  state.deploymentId = identity;
  return { preserved: false, reason: identity ? 'new-deployment' : 'unbound' };
}

function eventBinding(value) {
  const executionId = typeof value?.executionId === 'string' ? value.executionId : null;
  const processInstanceId = typeof value?.processInstanceId === 'string' ? value.processInstanceId : null;
  const graphVersion = typeof value?.graphVersion === 'string' ? value.graphVersion : null;
  return executionId && processInstanceId && graphVersion
    ? { executionId, processInstanceId, graphVersion } : null;
}

function sameBinding(left, right) {
  return Boolean(left && right && left.executionId === right.executionId
    && left.processInstanceId === right.processInstanceId && left.graphVersion === right.graphVersion);
}

export function bindMonitoringRuntimeState(state, value, { pending = false } = {}) {
  if (pending) {
    resetMonitoringRuntimeState(state, null);
    state.pending = true;
    return { preserved: false, reason: 'pending' };
  }
  const binding = eventBinding(value);
  if (binding && sameBinding(state.binding, binding)) {
    state.executionId = binding.executionId;
    state.pending = false;
    return { preserved: true, reason: 'matching-observation' };
  }
  resetMonitoringRuntimeState(state, binding?.executionId ?? null);
  state.binding = binding;
  return { preserved: false, reason: binding ? 'new-binding' : 'unbound' };
}

export function runtimeEventIdentity(event) {
  const process = String(event?.processInstanceId || 'process');
  const sequence = event?.streamSequence ?? event?.sequence ?? event?.journalOffset;
  return Number.isFinite(Number(sequence)) ? `${process}:${Number(sequence)}` : null;
}

function runtimeEventCursor(event) {
  const sequence = Number(event?.streamSequence ?? event?.sequence ?? event?.journalOffset);
  if (!Number.isFinite(sequence)) return null;
  return { process: String(event?.processInstanceId || 'process'), sequence };
}

export function observeEdgeTraversal(state, event, { now = Date.now(), knownEdgeIds = null } = {}) {
  if (event?.type !== 'EDGE_TRAVERSED') return { changed: false, reason: 'not-traversal' };
  const edgeId = event?.edgeId;
  // Stable edge identity is exact. Whitespace may be significant and the server/UI contract rejects
  // invalid values rather than trimming or normalizing them, so lookup and storage use the same raw
  // string that arrived on the authoritative event.
  if (stableEdgeIdViolation(edgeId) || (knownEdgeIds && !knownEdgeIds.has(edgeId))) {
    return { changed: false, reason: 'unknown-edge' };
  }
  const binding = eventBinding(event);
  if (!binding) return { changed: false, reason: 'unbound-event' };
  if (state.deploymentId) {
    // Deployment scope: the traversal changes with every message and must NOT reset the projection.
    // The fence is the deployment the event names, which no other document's session can share.
    if (event.deploymentId !== state.deploymentId) return { changed: false, reason: 'other-deployment' };
    state.binding = binding;
    state.executionId = binding.executionId;
  } else {
    if (state.binding && !sameBinding(state.binding, binding)) resetMonitoringRuntimeState(state, null);
    state.binding = binding;
    state.executionId = binding.executionId;
  }
  const cursor = runtimeEventCursor(event);
  const identity = runtimeEventIdentity(event);
  if (!cursor || !identity || cursor.sequence <= (state.seen.get(cursor.process) ?? -Infinity)) {
    return { changed: false, reason: 'duplicate' };
  }
  state.seen.set(cursor.process, cursor.sequence);
  const streamBound = state.deploymentId ? MAX_DEPLOYMENT_EVENT_STREAMS : MAX_EVENT_STREAMS;
  while (state.seen.size > streamBound) state.seen.delete(state.seen.keys().next().value);
  const occurredAt = finiteTime(event.occurredAt, now);
  // A replay reveals a historical traversal; it must update the cumulative/last-event facts without
  // pretending the old traversal is current traffic. Clamp future producer clocks to one local
  // window so clock skew cannot leave an edge active indefinitely.
  const expiresAt = Math.min(now + FLOW_PULSE_MS, occurredAt + FLOW_PULSE_MS);
  const current = state.edges.get(edgeId) || {
    count: 0, pulses: [], lastEvent: null, lastOccurredAt: null,
  };
  current.count += 1;
  current.pulses = current.pulses.filter(expiry => expiry > now);
  if (expiresAt > now) current.pulses.push(expiresAt);
  current.lastEvent = identity;
  current.lastOccurredAt = Number.isFinite(occurredAt) ? new Date(occurredAt).toISOString() : null;
  state.edges.set(edgeId, current);
  return { changed: true, edgeId, expiresAt };
}

export function edgeFlowSnapshot(state, edgeId, now = Date.now()) {
  const current = state?.edges?.get(edgeId);
  if (!current) return { recent: 0, count: 0, lastEvent: null, lastOccurredAt: null, expiresAt: null };
  const pulses = current.pulses.filter(expiry => expiry > now);
  if (pulses.length !== current.pulses.length) current.pulses = pulses;
  return {
    recent: pulses.length,
    count: current.count,
    lastEvent: current.lastEvent,
    lastOccurredAt: current.lastOccurredAt,
    expiresAt: pulses.length ? Math.max(...pulses) : null,
  };
}

/**
 * What a node's colour means when several traversals occupy the graph at once.
 *
 * <h4>The question</h4>
 * A single run has one traversal, so "the last event about this node" and "what this node is doing"
 * are the same sentence and the editor never had to choose between them. A listening source breaks
 * that: three messages can be inside one node at the same instant, their events interleave in
 * arrival order, and "last event wins" makes the colour a report on whichever traversal happened to
 * emit most recently. It flickers, and worse, it is not false in a way anyone can see -- a node
 * being painted green a millisecond after it failed for a different message reads as health.
 *
 * <h4>The rule</h4>
 * The colour is a statement about THE NODE, aggregated over every traversal in the view, in this
 * precedence:
 *
 * <ol>
 *   <li><b>failed</b>, once an arrival has failed here. A failure is not erased by a sibling
 *   traversal that finishes just after it -- no settlement clears it, only a new arrival ENTERING
 *   the node does, and that arrival's own outcome then takes over. On a healthy busy source the
 *   next message clears it within one arrival, and a node that only ever fails stays red.</li>
 *   <li><b>active</b>, while the node is occupied. Occupancy is {@code inFlightArrivals}, which the
 *   runtime already counts per node across every traversal in the process -- so this is measured,
 *   not inferred from the ordering of two events.</li>
 *   <li>otherwise the last settled outcome, or idle if none has settled.</li>
 * </ol>
 *
 * <p>The same precedence resolves the other outcomes: a plain completion never replaces a standing
 * {@code fallback} or {@code bypassed} either. Those two arrive as their own event AND a completion
 * for the same arrival, so letting the completion win would erase the one fact worth showing -- the
 * per-event rule this replaced was careful about exactly that, and this keeps it. The order is
 * therefore failed, then defaulted or bypassed, then completed: a more specific standing outcome
 * survives a less specific one until the node is entered again.</p>
 *
 * <p>The colour is therefore a CURRENT statement and can only ever be one. The cumulative one is
 * {@code failures}, which counts every failed arrival at this node for the life of the binding and
 * is never decremented -- so a red flash the user missed is still on the record after the node has
 * gone green again. Two questions, two numbers, instead of one colour asked to answer both.</p>
 */
export function observeNodeActivity(state, event, { knownNodeIds = null } = {}) {
  const nodeId = typeof event?.nodeId === 'string' && event.nodeId ? event.nodeId : null;
  if (!nodeId) return { changed: false, reason: 'not-node-scoped' };
  // The map is keyed by graph node id and the caller passes the graph's own ids, so it is bounded by
  // the document rather than by how long the source has been listening.
  if (knownNodeIds && !knownNodeIds.has(nodeId)) return { changed: false, reason: 'unknown-node' };
  const type = String(event.type || '');
  const settled = SETTLED_NODE_STATES[type];
  if (!settled && !OPENING_NODE_EVENTS.has(type)) return { changed: false, reason: 'not-node-visit' };
  const entry = state.nodes.get(nodeId) || {
    instances: 0, arrivals: 0, settled: null, opening: false, failures: 0,
    lastEventType: null, lastOccurredAt: null, processingDuration: null, fallback: false,
  };
  entry.instances = Number(event.activeInstances) || 0;
  entry.arrivals = Number(event.inFlightArrivals) || 0;
  if (settled) {
    // Precedence, not recency. A failure outranks everything until the node is entered again, which
    // is the whole point of the rule: a sibling traversal completing a millisecond later must not
    // repaint the node green. Below it, a plain completion never replaces a standing defaulted or
    // bypassed outcome, because those publish their own event AND a completion for the SAME arrival
    // -- "it completed" is true of a defaulted node too, and saying only that loses the one fact
    // worth showing. `opening` above is what clears the whole standing outcome.
    const standing = OUTCOME_PRECEDENCE[entry.settled] || 0;
    if (OUTCOME_PRECEDENCE[settled] > standing) entry.settled = settled;
    entry.opening = false;
    if (settled === 'failed') entry.failures += 1;
  } else {
    // A retry keeps the visit open: the attempt has not settled and the next NODE_STARTED is already
    // on its way, so painting a terminal state here would flash something the traversal never reached.
    entry.settled = null;
    entry.opening = true;
  }
  entry.lastEventType = type;
  entry.lastOccurredAt = event.occurredAt || null;
  entry.processingDuration = event.processingDuration ?? null;
  entry.fallback = Boolean(event.fallback);
  state.nodes.set(nodeId, entry);
  return { changed: true, nodeId, view: nodeActivitySnapshot(state, nodeId) };
}

/** The node's aggregated view, or the idle view when nothing has been observed there. */
export function nodeActivitySnapshot(state, nodeId) {
  const entry = state?.nodes?.get(nodeId);
  if (!entry) {
    return {
      state: 'idle', instances: 0, arrivals: 0, failures: 0, observed: false,
      lastEventType: null, lastOccurredAt: null, processingDuration: null, fallback: false,
    };
  }
  return {
    state: entry.settled === 'failed' ? 'failed'
      : (entry.opening || entry.arrivals > 0) ? 'active'
        : entry.settled || 'idle',
    instances: entry.instances,
    arrivals: entry.arrivals,
    failures: entry.failures,
    observed: true,
    lastEventType: entry.lastEventType,
    lastOccurredAt: entry.lastOccurredAt,
    processingDuration: entry.processingDuration,
    fallback: entry.fallback,
  };
}

export function edgeFlowWidth(recent) {
  if (!(recent > 0)) return FLOW_BASE_WIDTH;
  return Math.min(FLOW_MAX_WIDTH, FLOW_BASE_WIDTH + Math.sqrt(recent) * 2.2);
}

export function formatRuntimeTime(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleTimeString([], { hour12: false });
}
