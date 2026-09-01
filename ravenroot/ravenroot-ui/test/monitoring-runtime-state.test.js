import { describe, expect, it } from 'vitest';

import {
  FLOW_BASE_WIDTH,
  FLOW_MAX_WIDTH,
  bindMonitoringRuntimeState,
  createMonitoringRuntimeState,
  edgeFlowSnapshot,
  edgeFlowWidth,
  observeEdgeTraversal,
  resetMonitoringRuntimeState,
} from '../src/monitoring-runtime-state.js';

const traversal = (sequence, edgeId = 'e1', extra = {}) => ({
  type: 'EDGE_TRAVERSED', sequence, edgeId, processInstanceId: 'process-1',
  executionId: 'execution-1', graphVersion: 'graph-1', occurredAt: '2026-08-30T12:00:00Z', ...extra,
});

describe('authoritative Monitoring edge flow', () => {
  it('counts only known EDGE_TRAVERSED events and deduplicates live/replay sequence identity', () => {
    const state = createMonitoringRuntimeState();
    const knownEdgeIds = new Set(['e1']);
    expect(observeEdgeTraversal(state, { ...traversal(1), type: 'NODE_COMPLETED' }, { knownEdgeIds }).changed).toBe(false);
    expect(observeEdgeTraversal(state, traversal(1), { now: 100, knownEdgeIds }).changed).toBe(true);
    expect(observeEdgeTraversal(state, traversal(1, 'e1', { streamSequence: 1 }), { now: 110, knownEdgeIds }).changed).toBe(false);
    expect(observeEdgeTraversal(state, traversal(2, 'missing'), { now: 120, knownEdgeIds }).reason).toBe('unknown-edge');
    expect(edgeFlowSnapshot(state, 'e1', 200)).toMatchObject({ recent: 1, count: 1 });
  });

  it('attributes whitespace-significant edge identities exactly without replay duplication', () => {
    const state = createMonitoringRuntimeState();
    const knownEdgeIds = new Set(['edge', ' edge ']);
    expect(observeEdgeTraversal(state, traversal(1, ' edge '), { now: 100, knownEdgeIds }))
      .toMatchObject({ changed: true, edgeId: ' edge ' });
    expect(edgeFlowSnapshot(state, ' edge ', 101).count).toBe(1);
    expect(edgeFlowSnapshot(state, 'edge', 101).count).toBe(0);
    expect(observeEdgeTraversal(state, traversal(1, ' edge ', { streamSequence: 1 }), {
      now: 110, knownEdgeIds,
    }).reason).toBe('duplicate');

    expect(observeEdgeTraversal(state, traversal(2, 'edge'), { now: 120, knownEdgeIds }))
      .toMatchObject({ changed: true, edgeId: 'edge' });
    expect(edgeFlowSnapshot(state, ' edge ', 121).count).toBe(1);
    expect(edgeFlowSnapshot(state, 'edge', 121).count).toBe(1);

    const spacedOnly = createMonitoringRuntimeState();
    expect(observeEdgeTraversal(spacedOnly, traversal(1, ' edge '), {
      now: 100, knownEdgeIds: new Set([' edge ']),
    }).changed).toBe(true);
    expect(edgeFlowSnapshot(spacedOnly, ' edge ', 101).count).toBe(1);
    expect(edgeFlowSnapshot(spacedOnly, 'edge', 101).count).toBe(0);
  });

  it('pulses from zero to activity and decays without changing the cumulative count', () => {
    const state = createMonitoringRuntimeState();
    expect(edgeFlowSnapshot(state, 'e1', 0)).toEqual({
      recent: 0, count: 0, lastEvent: null, lastOccurredAt: null, expiresAt: null,
    });
    observeEdgeTraversal(state, traversal(1), { now: 1_000 });
    observeEdgeTraversal(state, traversal(2), { now: 1_100 });
    expect(edgeFlowSnapshot(state, 'e1', 1_200)).toMatchObject({ recent: 2, count: 2 });
    expect(edgeFlowSnapshot(state, 'e1', 2_501)).toMatchObject({ recent: 0, count: 2 });
  });

  it('reconciles an old replay into the count without presenting it as recent traffic', () => {
    const state = createMonitoringRuntimeState();
    observeEdgeTraversal(state, traversal(1, 'e1', { occurredAt: '2026-08-30T12:00:00Z' }), {
      now: Date.parse('2026-08-30T12:00:10Z'),
    });
    expect(edgeFlowSnapshot(state, 'e1', Date.parse('2026-08-30T12:00:10Z')))
      .toMatchObject({ recent: 0, count: 1 });
  });

  it('resets execution observations atomically and isolates edge maps', () => {
    const state = createMonitoringRuntimeState();
    observeEdgeTraversal(state, traversal(1), { now: 1 });
    resetMonitoringRuntimeState(state, 'execution-2');
    expect(state.executionId).toBe('execution-2');
    expect(edgeFlowSnapshot(state, 'e1', 2).count).toBe(0);
    expect(observeEdgeTraversal(state, traversal(1, 'e2'), { now: 2 }).changed).toBe(true);
  });

  it('preserves a provisional observation only when the accepted binding is identical', () => {
    const state = createMonitoringRuntimeState();
    bindMonitoringRuntimeState(state, { executionId: 'pending' }, { pending: true });
    observeEdgeTraversal(state, traversal(1), { now: 1 });
    expect(bindMonitoringRuntimeState(state, {
      executionId: 'execution-1', processInstanceId: 'process-1', graphVersion: 'graph-1',
    }).preserved).toBe(true);
    expect(edgeFlowSnapshot(state, 'e1', 2).count).toBe(1);

    expect(bindMonitoringRuntimeState(state, {
      executionId: 'execution-2', processInstanceId: 'process-1', graphVersion: 'graph-1',
    }).preserved).toBe(false);
    expect(edgeFlowSnapshot(state, 'e1', 2).count).toBe(0);
  });

  it('uses an equal base width at idle and clamps observed activity independently of configured weight', () => {
    expect(edgeFlowWidth(0)).toBe(FLOW_BASE_WIDTH);
    expect(edgeFlowWidth(null)).toBe(FLOW_BASE_WIDTH);
    expect(edgeFlowWidth(1)).toBeGreaterThan(FLOW_BASE_WIDTH);
    expect(edgeFlowWidth(10_000)).toBe(FLOW_MAX_WIDTH);
  });
});
