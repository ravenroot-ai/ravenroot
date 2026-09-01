import { describe, expect, it } from 'vitest';
import {
  GRAPH_LIFECYCLE_NOT_IMPLEMENTED,
  requestGraphLifecycle,
} from '../src/graph-lifecycle.js';

describe('graph lifecycle placeholder boundary', () => {
  it.each(['run', 'pause', 'stop', 'forceStop'])('returns an explicit scoped placeholder for %s', action => {
    const result = requestGraphLifecycle(action, {
      documentId: 'document-a', graphName: 'orders', deploymentId: 'deployment-a',
    });

    expect(result).toMatchObject({
      status: GRAPH_LIFECYCLE_NOT_IMPLEMENTED,
      action,
      documentId: 'document-a',
      graphName: 'orders',
      deploymentId: 'deployment-a',
    });
    expect(result.message).toMatch(/not implemented yet/i);
  });

  it('never silently accepts an unknown or server-wide action', () => {
    expect(() => requestGraphLifecycle('drainActorSystem', { documentId: 'document-a' }))
      .toThrow(/Unknown graph lifecycle action/);
  });
});
