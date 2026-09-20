import { describe, expect, it } from 'vitest';

import {
  applyDeploymentViewFrame,
  applyDeploymentViewStateToRenderer,
  createDeploymentViewState,
} from '../src/deployment-view-state.js';

const binding = { deploymentId: 'orders', graphVersion: 'sha256', incarnationId: 'inc-1',
  lifecycle: 'READY' };
const frame = overrides => ({ type: 'execution', ...binding, cursor: 'cursor-1',
  event: { type: 'NODE_STARTED', nodeId: 'worker', executionId: 'run-1', sequence: 1 }, ...overrides });

describe('deployment viewer runtime state', () => {
  it('accepts matching real execution events and projects node/edge state', () => {
    const state = createDeploymentViewState(binding);
    expect(applyDeploymentViewFrame(state, frame()).accepted).toBe(true);
    expect(state.nodeStates.get('worker').runtimeState).toBe('active');
    expect(applyDeploymentViewFrame(state, frame({ cursor: 'cursor-2', event: {
      type: 'EDGE_TRAVERSED', edgeId: 'edge-1', executionId: 'run-1', sequence: 2,
    } })).accepted).toBe(true);
    expect(state.edgeStates.get('edge-1')).toMatchObject({ count: 1, recent: 1 });
  });

  it('preserves fallback provenance through the shared reducer and renderer data state', () => {
    const state = createDeploymentViewState(binding);
    expect(applyDeploymentViewFrame(state, frame({ cursor: 'cursor-fallback', event: {
      type: 'NODE_DEFAULTED', nodeId: 'worker', executionId: 'run-1', sequence: 3,
      activeInstances: 1, fallback: true,
    } }))).toMatchObject({ accepted: true, nodeId: 'worker' });
    expect(state.nodeStates.get('worker')).toMatchObject({
      runtimeState: 'fallback', fallback: true, activeInstances: 1,
    });

    const values = new Map([['id', 'worker']]);
    const node = {
      id: () => 'worker',
      data: (key, value) => value === undefined ? values.get(key) : values.set(key, value),
    };
    applyDeploymentViewStateToRenderer({ nodes: () => [node], edges: () => [] }, state);
    expect(Object.fromEntries(values)).toMatchObject({
      runtimeObserved: true, runtimeState: 'fallback', activeInstances: 1, fallback: true,
    });
  });

  it('rejects another deployment/version/incarnation and makes the view visibly non-live', () => {
    for (const mismatch of [
      { deploymentId: 'foreign' }, { graphVersion: 'other' }, { incarnationId: 'inc-2' },
    ]) {
      const state = createDeploymentViewState(binding);
      const result = applyDeploymentViewFrame(state, frame(mismatch));
      expect(result).toMatchObject({ accepted: false, terminal: true });
      expect(state.continuity).toBe('VERSION_MISMATCH');
    }
  });

  it('treats a gap and undeploy as terminal rather than inventing continuity', () => {
    const state = createDeploymentViewState(binding);
    expect(applyDeploymentViewFrame(state, { type: 'gap', ...binding,
      cursor: 'resume', reason: 'cursor-expired' }).terminal).toBe(true);
    expect(state.continuity).toBe('GAP');
    expect(applyDeploymentViewFrame(state, { type: 'lifecycle', ...binding,
      lifecycle: 'UNDEPLOYED' }).terminal).toBe(true);
    expect(state.continuity).toBe('DETACHED');
  });

  it('never reports cold, transitional, stopped, or failed lifecycle states as live', () => {
    const expected = new Map([
      ['REGISTERED', 'UNAVAILABLE'], ['STARTING', 'UNAVAILABLE'],
      ['STOPPING', 'UNAVAILABLE'], ['STOPPED', 'STOPPED'], ['FAILED', 'UNAVAILABLE'],
      ['READY', 'LIVE'], ['DEGRADED', 'LIVE'], ['UNDEPLOYED', 'DETACHED'],
    ]);
    for (const [lifecycle, continuity] of expected) {
      const state = createDeploymentViewState(binding);
      applyDeploymentViewFrame(state, frame());
      expect(state.nodeStates.size).toBe(1);
      expect(applyDeploymentViewFrame(state, { type: 'lifecycle', ...binding, lifecycle }).accepted)
        .toBe(true);
      expect(state.continuity).toBe(continuity);
      expect(state.nodeStates.size).toBe(continuity === 'LIVE' ? 1 : 0);
    }
  });

  it('distinguishes an incarnation replacement from an undeploy invalidation', () => {
    const replacement = createDeploymentViewState(binding);
    expect(applyDeploymentViewFrame(replacement, {
      type: 'invalidated', ...binding, reason: 'VERSION_MISMATCH', cursor: 'terminal-1',
    })).toMatchObject({ accepted: true, terminal: true, reason: 'binding-mismatch' });
    expect(replacement.continuity).toBe('VERSION_MISMATCH');

    const removed = createDeploymentViewState(binding);
    expect(applyDeploymentViewFrame(removed, {
      type: 'invalidated', ...binding, reason: 'UNDEPLOYED', cursor: 'terminal-2',
    })).toMatchObject({ accepted: true, terminal: true, reason: 'undeployed' });
    expect(removed).toMatchObject({ continuity: 'DETACHED', lifecycle: 'UNDEPLOYED' });

    const revoked = createDeploymentViewState({ ...binding, lifecycle: 'READY' });
    expect(applyDeploymentViewFrame(revoked, {
      type: 'invalidated', ...binding, reason: 'AUTHORITY_CHANGED', cursor: 'terminal-3',
    })).toMatchObject({ accepted: true, reason: 'authority-changed', terminal: true });
    expect(revoked).toMatchObject({ continuity: 'DETACHED', lifecycle: 'READY',
      gap: 'AUTHORITY_CHANGED' });
  });
});
