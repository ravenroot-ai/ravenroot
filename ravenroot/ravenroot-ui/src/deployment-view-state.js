const LIFECYCLE = new Set([
  'REGISTERED', 'STARTING', 'READY', 'DEGRADED', 'STOPPING', 'STOPPED', 'FAILED', 'UNDEPLOYED',
]);

export function createDeploymentViewState(binding) {
  if (!binding?.deploymentId || !binding?.graphVersion || !binding?.incarnationId) {
    throw new TypeError('Deployment viewer requires an immutable deployment binding.');
  }
  return {
    binding: Object.freeze({
      deploymentId: binding.deploymentId,
      graphVersion: binding.graphVersion,
      incarnationId: binding.incarnationId,
    }),
    lifecycle: LIFECYCLE.has(binding.lifecycle) ? binding.lifecycle : 'REGISTERED',
    continuity: 'CONNECTING', cursor: null, gap: null,
    nodeStates: new Map(), edgeStates: new Map(), seen: new Set(),
  };
}

export function resetDeploymentViewRuntime(state, continuity, gap = null) {
  state.continuity = continuity;
  state.gap = gap;
  state.nodeStates.clear();
  state.edgeStates.clear();
  state.seen.clear();
}

function sameBinding(state, frame) {
  return frame?.deploymentId === state.binding.deploymentId
    && frame?.graphVersion === state.binding.graphVersion
    && frame?.incarnationId === state.binding.incarnationId;
}

export function applyDeploymentViewFrame(state, frame) {
  if (!frame || typeof frame !== 'object') return { accepted: false, reason: 'invalid' };
  if (!sameBinding(state, frame)) {
    resetDeploymentViewRuntime(state, 'VERSION_MISMATCH', 'binding-mismatch');
    return { accepted: false, reason: 'binding-mismatch', terminal: true };
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
  const identity = String(frame.cursor || event.sequence || '');
  if (!identity || state.seen.has(identity)) return { accepted: false, reason: 'duplicate' };
  state.seen.add(identity);
  if (state.seen.size > 2048) state.seen.delete(state.seen.values().next().value);
  state.cursor = frame.cursor || identity;
  state.continuity = 'LIVE';
  if (event.nodeId) {
    const runtimeState = event.fallback || event.type === 'NODE_DEFAULTED' ? 'fallback'
      : event.type === 'NODE_STARTED' ? 'active'
      : event.type === 'NODE_FAILED' ? 'failed'
        : event.type === 'NODE_BYPASSED' ? 'bypassed'
          : event.type === 'NODE_COMPLETED' ? 'completed' : null;
    if (runtimeState) state.nodeStates.set(event.nodeId, {
      runtimeState, activeInstances: Number(event.activeInstances) || 0,
      fallback: Boolean(event.fallback), occurredAt: event.occurredAt || null,
    });
  }
  if (event.type === 'EDGE_TRAVERSED' && event.edgeId) {
    const previous = state.edgeStates.get(event.edgeId) || { count: 0, recent: 0 };
    state.edgeStates.set(event.edgeId, { count: previous.count + 1,
      recent: Math.min(8, previous.recent + 1), occurredAt: event.occurredAt || null });
  }
  return { accepted: true, reason: 'execution', nodeId: event.nodeId, edgeId: event.edgeId };
}

export function applyDeploymentViewStateToRenderer(instance, state) {
  if (!instance || !state) return;
  instance.nodes().forEach(node => {
    const runtime = state.nodeStates.get(node.id());
    node.data('runtimeObserved', Boolean(runtime));
    node.data('runtimeState', runtime?.runtimeState || 'idle');
    node.data('activeInstances', runtime?.activeInstances || 0);
    node.data('fallback', Boolean(runtime?.fallback));
  });
  instance.edges().forEach(edge => {
    const runtime = state.edgeStates.get(edge.id());
    edge.data('runtimeActive', Boolean(runtime?.recent));
    edge.data('runtimeRecent', runtime?.recent || 0);
    edge.data('runtimeCount', runtime?.count || 0);
  });
}
