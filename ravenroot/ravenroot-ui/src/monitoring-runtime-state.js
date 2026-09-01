import { stableEdgeIdViolation } from './stable-edge-id.js';

export const FLOW_PULSE_MS = 1_400;
export const FLOW_BASE_WIDTH = 1.8;
export const FLOW_MAX_WIDTH = 7;
const MAX_EVENT_STREAMS = 8;

function finiteTime(value, fallback) {
  const time = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(time) ? time : fallback;
}

export function createMonitoringRuntimeState() {
  return { executionId: null, generation: 0, pending: false, binding: null, seen: new Map(), edges: new Map() };
}

export function resetMonitoringRuntimeState(state, executionId = null) {
  state.executionId = executionId;
  state.generation += 1;
  state.pending = false;
  state.binding = null;
  state.seen.clear();
  state.edges.clear();
  return state;
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
  if (state.binding && !sameBinding(state.binding, binding)) resetMonitoringRuntimeState(state, null);
  state.binding = binding;
  state.executionId = binding.executionId;
  const cursor = runtimeEventCursor(event);
  const identity = runtimeEventIdentity(event);
  if (!cursor || !identity || cursor.sequence <= (state.seen.get(cursor.process) ?? -Infinity)) {
    return { changed: false, reason: 'duplicate' };
  }
  state.seen.set(cursor.process, cursor.sequence);
  if (state.seen.size > MAX_EVENT_STREAMS) state.seen.delete(state.seen.keys().next().value);
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

export function edgeFlowWidth(recent) {
  if (!(recent > 0)) return FLOW_BASE_WIDTH;
  return Math.min(FLOW_MAX_WIDTH, FLOW_BASE_WIDTH + Math.sqrt(recent) * 2.2);
}

export function formatRuntimeTime(value) {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleTimeString([], { hour12: false });
}
