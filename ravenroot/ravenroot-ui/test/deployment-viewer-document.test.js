import { describe, expect, it } from 'vitest';

import { deploymentProjectionDocument } from '../src/deployment-viewer-document.js';
import { canModifyGraph } from '../src/graph-editing.js';

function envelope(overrides = {}) {
  return { viewerSourceVersion: '1', source: { kind: 'deployment', deploymentId: 'orders',
    graphVersion: 'v1', incarnationId: 'inc-1' }, lifecycle: 'READY', projection: {
    viewerContractVersion: '1.0', graphId: 'orders', graphVersionId: 'v1', canonicalDigest: 'd',
    nodes: [{ id: 'start', kind: 'START', visualType: 'start', label: 'Start',
      layout: { x: 10, y: 20, width: 80, height: 80 } },
    { id: 'worker', kind: 'BEHAVIOR', visualType: 'agent', label: 'Worker', layout: null }],
    edges: [{ id: 'e1', source: 'start', target: 'worker', label: 'continue',
      visualType: 'continue' }],
  }, ...overrides };
}

describe('deployment projection document', () => {
  it('opens without GraphML as a structurally read-only editor document', () => {
    const graph = deploymentProjectionDocument(envelope());
    expect(graph.format).toBe('deployment');
    expect(canModifyGraph(graph)).toBe(false);
    expect(graph.nodeMap.worker.nodeType).toBe('agent');
    expect(graph.edgeMap.e1.source).toBe('start');
    expect(JSON.stringify(graph)).not.toContain('graphml');
  });

  it('rejects topology outside the allowlisted projection', () => {
    const invalid = envelope();
    invalid.projection.edges[0].target = 'missing';
    expect(() => deploymentProjectionDocument(invalid)).toThrow('topology');
  });
});
