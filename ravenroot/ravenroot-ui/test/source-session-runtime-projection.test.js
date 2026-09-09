import { describe, expect, it } from 'vitest';

import {
  bindMonitoringRuntimeStateToDeployment,
  createMonitoringRuntimeState,
  edgeFlowSnapshot,
  nodeActivitySnapshot,
  observeEdgeTraversal,
  observeNodeActivity,
  resetMonitoringRuntimeState,
} from '../src/monitoring-runtime-state.js';
import { createDocumentRecord, documentForRuntimeEvent, createWorkspace, bindExecution, PENDING_EXECUTION }
  from '../src/workspace.js';
import { validateSourceSessionStatus } from '../src/runtime-client.js';

// A source produces one traversal per admitted message, so every fixture here changes traversalId and
// processInstanceId while keeping the deployment: that is the shape the editor could not attribute.
let traversalCounter = 0;
const admission = (overrides = {}) => {
  traversalCounter += 1;
  return {
    executionId: `traversal-${traversalCounter}`,
    processInstanceId: `process-${traversalCounter}`,
    graphVersion: 'graph-1',
    deploymentId: 'session-a',
    occurredAt: '2026-09-09T10:00:00Z',
    sequence: traversalCounter,
    ...overrides,
  };
};

const document_ = (id, sourceSession = {}) => {
  const record = createDocumentRecord({ id });
  Object.assign(record.sourceSession, sourceSession);
  return record;
};

const workspaceWith = (...documents) => {
  const workspace = createWorkspace();
  documents.forEach(record => workspace.add(record));
  return workspace;
};

describe('runtime events are attributed to a listening source graph', () => {
  it('routes a source traversal by deployment, which is the only identity the document can hold', () => {
    const listening = document_('doc-source', { sessionId: 'session-a', deploymentId: 'session-a', state: 'LISTENING' });
    const workspace = workspaceWith(listening);

    // Three separate admissions, three traversal ids the document was never told.
    for (let index = 0; index < 3; index += 1) {
      expect(documentForRuntimeEvent(workspace, admission())).toBe(listening);
    }
  });

  it('drops a source event for a deployment no open document is watching', () => {
    const listening = document_('doc-source', { sessionId: 'session-a', deploymentId: 'session-a' });
    const workspace = workspaceWith(listening);

    expect(documentForRuntimeEvent(workspace, admission({ deploymentId: 'session-b' }))).toBeNull();
    expect(documentForRuntimeEvent(workspace, admission({ deploymentId: null }))).toBeNull();
  });

  it('keeps the execution binding first, so Test and Run are unchanged by the new rule', () => {
    const run = document_('doc-run');
    const listening = document_('doc-source', { sessionId: 'session-a', deploymentId: 'session-a' });
    const workspace = workspaceWith(run, listening);
    bindExecution(run, 'traversal-run', 'graph-1');

    const ownRun = { ...admission(), executionId: 'traversal-run', deploymentId: 'session-a' };
    expect(documentForRuntimeEvent(workspace, ownRun)).toBe(run);
  });

  it('does not let a document waiting for its own execution id adopt a source session s traffic', () => {
    const submitting = document_('doc-submitting');
    const listening = document_('doc-source', { sessionId: 'session-a', deploymentId: 'session-a' });
    const workspace = workspaceWith(submitting, listening);
    bindExecution(submitting, PENDING_EXECUTION);

    expect(documentForRuntimeEvent(workspace, admission())).toBe(listening);
  });

  it('accepts a status without a deployment identity rather than refusing the session outright', () => {
    const withIdentity = validateSourceSessionStatus({
      sessionId: 'session-a', deploymentId: 'session-a', state: 'LISTENING', sourceCount: 1,
      scope: 'LOCAL_PROCESS', diagnostic: null,
    }, 'session-a');
    expect(withIdentity.deploymentId).toBe('session-a');

    expect(validateSourceSessionStatus({
      sessionId: 'session-a', state: 'LISTENING', sourceCount: 1, scope: 'LOCAL_PROCESS', diagnostic: null,
    }, 'session-a').deploymentId).toBeUndefined();

    expect(() => validateSourceSessionStatus({
      sessionId: 'session-a', deploymentId: '', state: 'LISTENING', sourceCount: 1,
      scope: 'LOCAL_PROCESS', diagnostic: null,
    }, 'session-a')).toThrow(/not a valid process-local status/);
  });
});

describe('the monitoring projection outlives one traversal when it is bound to a deployment', () => {
  it('accumulates edge flow across admissions instead of resetting on every new traversal', () => {
    const state = createMonitoringRuntimeState();
    bindMonitoringRuntimeStateToDeployment(state, 'session-a');
    const knownEdgeIds = new Set(['e1']);

    for (let index = 0; index < 5; index += 1) {
      expect(observeEdgeTraversal(state, { ...admission(), type: 'EDGE_TRAVERSED', edgeId: 'e1' },
        { now: 1_000 + index, knownEdgeIds }).changed).toBe(true);
    }

    expect(edgeFlowSnapshot(state, 'e1', 1_010).count).toBe(5);
  });

  it('still resets per traversal for a run, which is what a one-shot execution should do', () => {
    const state = createMonitoringRuntimeState();
    const knownEdgeIds = new Set(['e1']);

    observeEdgeTraversal(state, { ...admission(), type: 'EDGE_TRAVERSED', edgeId: 'e1' },
      { now: 1_000, knownEdgeIds });
    observeEdgeTraversal(state, { ...admission(), type: 'EDGE_TRAVERSED', edgeId: 'e1' },
      { now: 1_001, knownEdgeIds });

    expect(edgeFlowSnapshot(state, 'e1', 1_010).count).toBe(1);
  });

  it('ignores another deployment s traffic while bound', () => {
    const state = createMonitoringRuntimeState();
    bindMonitoringRuntimeStateToDeployment(state, 'session-a');
    const knownEdgeIds = new Set(['e1']);

    expect(observeEdgeTraversal(state, {
      ...admission({ deploymentId: 'session-b' }), type: 'EDGE_TRAVERSED', edgeId: 'e1',
    }, { now: 1_000, knownEdgeIds })).toMatchObject({ changed: false, reason: 'other-deployment' });
  });

  it('drops the deployment binding when the view is reset for a new run', () => {
    const state = createMonitoringRuntimeState();
    bindMonitoringRuntimeStateToDeployment(state, 'session-a');
    resetMonitoringRuntimeState(state, null);
    expect(state.deploymentId).toBeNull();
  });
});

describe('what a node colour means when several traversals occupy the graph', () => {
  const nodeEvent = (type, { arrivals = 0, instances = 0 } = {}) => ({
    ...admission(), type, nodeId: 'log', inFlightArrivals: arrivals, activeInstances: instances,
  });

  it('reports the node as active while it is occupied, however many traversals are inside it', () => {
    const state = createMonitoringRuntimeState();
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1, instances: 1 }));
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 2, instances: 2 }));
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 3, instances: 3 }));

    expect(nodeActivitySnapshot(state, 'log')).toMatchObject({ state: 'active', arrivals: 3, instances: 3 });

    // Two of the three settle; the node is still occupied, so it is still active.
    observeNodeActivity(state, nodeEvent('NODE_COMPLETED', { arrivals: 2, instances: 2 }));
    observeNodeActivity(state, nodeEvent('NODE_COMPLETED', { arrivals: 1, instances: 1 }));
    expect(nodeActivitySnapshot(state, 'log').state).toBe('active');

    observeNodeActivity(state, nodeEvent('NODE_COMPLETED', { arrivals: 0, instances: 0 }));
    expect(nodeActivitySnapshot(state, 'log').state).toBe('completed');
  });

  it('does not let a sibling traversal s occupancy hide a failure that has already settled', () => {
    const state = createMonitoringRuntimeState();
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 2, instances: 2 }));
    observeNodeActivity(state, nodeEvent('NODE_FAILED', { arrivals: 1, instances: 1 }));

    expect(nodeActivitySnapshot(state, 'log')).toMatchObject({ state: 'failed', failures: 1 });
  });

  it('does not let a sibling traversal settling a moment later repaint the failure green', () => {
    const state = createMonitoringRuntimeState();
    // Two messages inside one node -- the ordinary case under a source, not a corner. A fails, B
    // completes, and nothing has entered the node in between.
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1, instances: 1 }));
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 2, instances: 2 }));
    observeNodeActivity(state, nodeEvent('NODE_FAILED', { arrivals: 1, instances: 1 }));
    expect(nodeActivitySnapshot(state, 'log').state).toBe('failed');

    observeNodeActivity(state, nodeEvent('NODE_COMPLETED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(state, 'log')).toMatchObject({ state: 'failed', failures: 1 });

    // A defaulted sibling does not clear it either; only entering the node again does.
    observeNodeActivity(state, nodeEvent('NODE_DEFAULTED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(state, 'log').state).toBe('failed');
  });

  it('clears the failure when a new arrival enters, and never clears the cumulative count', () => {
    const state = createMonitoringRuntimeState();
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1, instances: 1 }));
    observeNodeActivity(state, nodeEvent('NODE_FAILED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(state, 'log').state).toBe('failed');

    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1, instances: 1 }));
    expect(nodeActivitySnapshot(state, 'log').state).toBe('active');
    observeNodeActivity(state, nodeEvent('NODE_COMPLETED', { arrivals: 0 }));

    // Green again, because that is what the node is doing now -- and the failure is still on record.
    expect(nodeActivitySnapshot(state, 'log')).toMatchObject({ state: 'completed', failures: 1 });

    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1 }));
    observeNodeActivity(state, nodeEvent('NODE_FAILED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(state, 'log').failures).toBe(2);
  });

  it('keeps a retried attempt active rather than flashing a terminal state it never reached', () => {
    const state = createMonitoringRuntimeState();
    observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1 }));
    observeNodeActivity(state, nodeEvent('NODE_RETRY_SCHEDULED', { arrivals: 0 }));

    expect(nodeActivitySnapshot(state, 'log').state).toBe('active');
  });

  it('keeps a defaulted or bypassed outcome when its own completion follows', () => {
    const defaulted = createMonitoringRuntimeState();
    observeNodeActivity(defaulted, nodeEvent('NODE_STARTED', { arrivals: 1 }));
    observeNodeActivity(defaulted, nodeEvent('NODE_DEFAULTED', { arrivals: 1 }));
    observeNodeActivity(defaulted, nodeEvent('NODE_COMPLETED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(defaulted, 'log').state).toBe('fallback');

    const bypassed = createMonitoringRuntimeState();
    observeNodeActivity(bypassed, nodeEvent('NODE_BYPASSED', { arrivals: 1 }));
    observeNodeActivity(bypassed, nodeEvent('NODE_COMPLETED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(bypassed, 'log').state).toBe('bypassed');

    // A later arrival starts the node's story again, so the next plain completion is plain.
    observeNodeActivity(bypassed, nodeEvent('NODE_STARTED', { arrivals: 1 }));
    observeNodeActivity(bypassed, nodeEvent('NODE_COMPLETED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(bypassed, 'log').state).toBe('completed');
  });

  it('carries bypass and fallback outcomes through unchanged', () => {
    const bypassed = createMonitoringRuntimeState();
    observeNodeActivity(bypassed, nodeEvent('NODE_BYPASSED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(bypassed, 'log').state).toBe('bypassed');

    const defaulted = createMonitoringRuntimeState();
    observeNodeActivity(defaulted, nodeEvent('NODE_DEFAULTED', { arrivals: 0 }));
    expect(nodeActivitySnapshot(defaulted, 'log').state).toBe('fallback');
  });

  it('observes only node visits, and only nodes the document actually has', () => {
    const state = createMonitoringRuntimeState();
    expect(observeNodeActivity(state, { ...admission(), type: 'EXECUTION_COMPLETED' }).changed).toBe(false);
    expect(observeNodeActivity(state, nodeEvent('EDGE_TRAVERSED')).changed).toBe(false);
    expect(observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1 }),
      { knownNodeIds: new Set(['other']) })).toMatchObject({ changed: false, reason: 'unknown-node' });
    expect(nodeActivitySnapshot(state, 'log')).toMatchObject({ state: 'idle', observed: false });
  });

  it('is bounded by the document rather than by how long the source has listened', () => {
    const state = createMonitoringRuntimeState();
    const knownNodeIds = new Set(['log']);
    for (let index = 0; index < 5_000; index += 1) {
      observeNodeActivity(state, nodeEvent('NODE_STARTED', { arrivals: 1 }), { knownNodeIds });
      observeNodeActivity(state, nodeEvent('NODE_COMPLETED', { arrivals: 0 }), { knownNodeIds });
    }
    expect(state.nodes.size).toBe(1);
  });
});
