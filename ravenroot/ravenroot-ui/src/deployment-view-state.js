import { productError } from './product-error.js';
const LIFECYCLE = new Set([
  'REGISTERED', 'STARTING', 'READY', 'DEGRADED', 'STOPPING', 'STOPPED', 'FAILED', 'UNDEPLOYED',
]);

export function createDeploymentViewState(binding) {
  if (!binding?.deploymentId || !binding?.graphVersion || !binding?.incarnationId) {
    throw productError("workspace.error.deployment.view.state.deployment.viewer.requires.an.immutable.deployment.binding", new TypeError('Deployment viewer requires an immutable deployment binding.'));
  }
  return {
    binding: Object.freeze({
      deploymentId: binding.deploymentId,
      graphVersion: binding.graphVersion,
      incarnationId: binding.incarnationId,
      processInstanceId: binding.processInstanceId || null,
      traversalId: binding.traversalId || null,
    }),
    lifecycle: LIFECYCLE.has(binding.lifecycle) ? binding.lifecycle : 'REGISTERED',
    continuity: 'CONNECTING', cursor: null, gap: null,
    generation: 0, nodeStates: new Map(), edgeStates: new Map(), seen: new Set(),
    runtime: createMonitoringRuntimeState(),
  };
}

export function resetDeploymentViewRuntime(state, continuity, gap = null) {
  state.continuity = continuity;
  state.gap = gap;
  state.nodeStates.clear();
  state.edgeStates.clear();
  state.seen.clear();
  state.nodeHistoryAvailability = null; state.edgeHistoryAvailability = null;
  state.nodeCoverageComplete = false; state.edgeCoverageComplete = false;
  resetMonitoringRuntimeState(state.runtime);
}

function sameBinding(state, frame) {
  return frame?.deploymentId === state.binding.deploymentId
    && frame?.graphVersion === state.binding.graphVersion
    && frame?.incarnationId === state.binding.incarnationId
    && (!state.binding.processInstanceId
      || frame?.processInstanceId === state.binding.processInstanceId);
}

export function applyDeploymentViewFrame(state, frame) {
  if (!frame || typeof frame !== 'object') return { accepted: false, reason: 'invalid' };
  if (!sameBinding(state, frame)) {
    resetDeploymentViewRuntime(state, 'VERSION_MISMATCH', 'binding-mismatch');
    return { accepted: false, reason: 'binding-mismatch', terminal: true };
  }
  if (frame.type === 'reset') {
    resetDeploymentViewRuntime(state, 'LIVE', 'AUTHORITATIVE_REPLAY');
    return { accepted: true, reason: 'authoritative-reset' };
  }
  if (frame.type === 'gap') {
    resetDeploymentViewRuntime(state, 'GAP', frame.reason || 'cursor-unavailable');
    state.cursor = frame.cursor || null;
    return { accepted: true, reason: 'gap', terminal: true };
  }
  if (frame.type === 'invalidated') {
    const replaced = frame.reason === 'SOURCE_CHANGED' || frame.reason === 'VERSION_MISMATCH';
    const undeployed = frame.reason === 'UNDEPLOYED';
    resetDeploymentViewRuntime(state, replaced ? 'VERSION_MISMATCH' : 'DETACHED', frame.reason || null);
    if (undeployed) state.lifecycle = 'UNDEPLOYED';
    state.cursor = frame.cursor || null;
    return { accepted: true,
      reason: replaced ? 'binding-mismatch' : undeployed ? 'undeployed' : 'authority-changed',
      terminal: true };
  }
  if (frame.type === 'lifecycle') {
    if (!LIFECYCLE.has(frame.lifecycle)) return { accepted: false, reason: 'invalid-lifecycle' };
    state.lifecycle = frame.lifecycle;
    state.continuity = frame.lifecycle === 'UNDEPLOYED' ? 'DETACHED'
      : ['READY', 'DEGRADED'].includes(frame.lifecycle) ? 'LIVE'
        : frame.lifecycle === 'STOPPED' ? 'STOPPED' : 'UNAVAILABLE';
    if (state.continuity !== 'LIVE') resetDeploymentViewRuntime(state, state.continuity);
    return { accepted: true, reason: 'lifecycle', terminal: frame.lifecycle === 'UNDEPLOYED' };
  }
  if (frame.type !== 'execution' || !frame.event) return { accepted: false, reason: 'invalid-type' };
  const event = frame.event;
  if (state.binding.traversalId && (event.traversalId || event.executionId) !== state.binding.traversalId)
    return { accepted: false, reason: 'traversal-selection' };
  const identity = String(frame.cursor || event.sequence || '');
  if (!identity || state.seen.has(identity)) return { accepted: false, reason: 'duplicate' };
  state.seen.add(identity);
  if (state.seen.size > 2048) state.seen.delete(state.seen.values().next().value);
  state.cursor = frame.cursor || identity;
  state.continuity = 'LIVE';
  const runtimeDeployment = `${state.binding.deploymentId}:${state.binding.processInstanceId || '*'}`;
  bindMonitoringRuntimeStateToDeployment(state.runtime, runtimeDeployment);
  const runtimeEvent = { ...event, deploymentId: runtimeDeployment,
    processInstanceId: frame.processInstanceId || state.binding.processInstanceId || 'deployment',
    graphVersion: frame.graphVersion, streamSequence: event.sequence || state.seen.size };
  observeNodeActivity(state.runtime, runtimeEvent);
  observeEdgeTraversal(state.runtime, runtimeEvent);
  if (event.nodeId) {
    const snapshot = nodeActivitySnapshot(state.runtime, event.nodeId);
    if (snapshot.observed) state.nodeStates.set(event.nodeId, {
      ...snapshot, runtimeObserved: true, runtimeState: snapshot.state,
      activeInstances: snapshot.instances,
    });
  }
  if (event.type === 'EDGE_TRAVERSED' && event.edgeId) {
    state.edgeStates.set(event.edgeId, edgeFlowSnapshot(state.runtime, event.edgeId));
  }
  return { accepted: true, reason: 'execution', nodeId: event.nodeId, edgeId: event.edgeId };
}

/** Publishes bounded scalar coverage without fabricating start times, durations or journal events. */
export function applyTraversalSummaries(state, { nodes, edges, nodeAvailability = 'AVAILABLE', edgeAvailability = 'AVAILABLE', nodeCoverageComplete = true, edgeCoverageComplete = true }) {
  if (!Array.isArray(nodes) || nodes.length > 5000 || !Array.isArray(edges) || edges.length > 10000)
    throw new RangeError('Traversal summary render budget exceeded');
  const nextNodes = new Map(), nextEdges = new Map();
  for (const row of nodes) {
    if (typeof row.nodeId !== 'string' || !row.nodeId || nextNodes.has(row.nodeId)
        || !['visitCount', 'activeVisits', 'completedVisits', 'failedVisits', 'attemptCount'].every(name => Number.isSafeInteger(row[name]) && row[name] >= 0)
        || row.activeVisits + row.completedVisits + row.failedVisits !== row.visitCount)
      throw new TypeError('Invalid traversal node summary');
    nextNodes.set(row.nodeId, { historySummary: true, runtimeObserved: true, runtimeState: row.activeVisits ? 'active' : row.failedVisits ? 'failed' : row.completedVisits ? 'completed' : 'idle',
      activeInstances: row.activeVisits, instances: row.activeVisits, arrivals: row.visitCount, visitCount: row.visitCount,
      completedVisits: row.completedVisits, failedVisits: row.failedVisits, attemptCount: row.attemptCount,
      processingDuration: null, lastOccurredAt: null, lastEventType: null, fallback: false });
  }
  for (const row of edges) {
    if (typeof row.edgeId !== 'string' || !row.edgeId || nextEdges.has(row.edgeId) || !Number.isSafeInteger(row.traversalCount) || row.traversalCount < 1)
      throw new TypeError('Invalid traversal edge summary');
    nextEdges.set(row.edgeId, { count: row.traversalCount, recent: 0 });
  }
  state.nodeStates = nodeAvailability === 'AVAILABLE' ? nextNodes : new Map();
  state.edgeStates = edgeAvailability === 'AVAILABLE' ? nextEdges : new Map();
  state.nodeHistoryAvailability = nodeAvailability; state.edgeHistoryAvailability = edgeAvailability;
  state.nodeCoverageComplete = nodeAvailability === 'AVAILABLE' && nodeCoverageComplete;
  state.edgeCoverageComplete = edgeAvailability === 'AVAILABLE' && edgeCoverageComplete;
}

export function applyDeploymentViewStateToRenderer(instance, state) {
  if (!instance || !state) return;
  instance.nodes().forEach(node => {
    const runtime = state.nodeStates.get(node.id());
    node.data('runtimeObserved', Boolean(runtime));
    node.data('runtimeState', runtime?.runtimeState || (state.nodeHistoryAvailability && !state.nodeCoverageComplete ? 'unknown' : 'idle'));
    node.data('historyNotLoaded', Boolean(state.nodeHistoryAvailability && !runtime && !state.nodeCoverageComplete));
    node.data('activeInstances', runtime?.activeInstances || 0);
    node.data('fallback', Boolean(runtime?.fallback));
  });
  instance.edges().forEach(edge => {
    const identity = edge.data('runtimeIdentity') || edge.id();
    const runtime = state.edgeStates.get(identity);
    edge.data('historyNotLoaded', Boolean(state.edgeHistoryAvailability && !runtime && !state.edgeCoverageComplete));
    edge.data('runtimeActive', Boolean(runtime?.recent));
    edge.data('runtimeRecent', runtime?.recent || 0);
    edge.data('runtimeCount', runtime?.count || 0);
  });
}
import {
  bindMonitoringRuntimeStateToDeployment,
  createMonitoringRuntimeState,
  edgeFlowSnapshot,
  nodeActivitySnapshot,
  observeEdgeTraversal,
  observeNodeActivity,
  resetMonitoringRuntimeState,
} from './monitoring-runtime-state.js';
