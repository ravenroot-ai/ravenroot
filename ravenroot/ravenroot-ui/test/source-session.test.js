import { describe, expect, it } from 'vitest';

import {
  captureSourceSessionToken,
  effectiveSourceCount,
  recoverSourceSessionState,
  sourceSessionIsActive,
  sourceSessionTokenIsCurrent,
} from '../src/source-session.js';

const catalog = [
  {
    behavior: 'queue.consume', defaultNature: 'SOURCE', allowedNatures: ['SOURCE'],
    natureProperty: 'runtime.nature',
  },
  {
    behavior: 'selectable', defaultNature: 'WORKER', allowedNatures: ['WORKER', 'SOURCE'],
    natureProperty: 'runtime.nature',
  },
];

describe('source-session editor routing', () => {
  it('counts inherited and explicitly selected SOURCE natures from the runtime catalog', () => {
    const graph = { nodes: [
      { id: 'inherited', kind: 'BEHAVIOR', behavior: 'queue.consume', properties: {} },
      { id: 'selected', kind: 'BEHAVIOR', behavior: 'selectable', properties: { 'runtime.nature': 'SOURCE' } },
      { id: 'worker', kind: 'BEHAVIOR', behavior: 'selectable', properties: {} },
    ] };

    expect(effectiveSourceCount(graph, catalog)).toBe(2);
  });

  it('fails closed for unknown behavior, non-behavior nodes, and a stale disallowed SOURCE choice', () => {
    const graph = { nodes: [
      { id: 'unknown', kind: 'BEHAVIOR', behavior: 'missing', properties: { 'runtime.nature': 'SOURCE' } },
      { id: 'start', kind: 'START', behavior: 'queue.consume', properties: {} },
      { id: 'stale', kind: 'BEHAVIOR', behavior: 'worker-only', properties: { 'runtime.nature': 'SOURCE' } },
    ] };
    const workerOnly = {
      behavior: 'worker-only', defaultNature: 'WORKER', allowedNatures: ['WORKER'],
      natureProperty: 'runtime.nature',
    };

    expect(effectiveSourceCount(graph, [...catalog, workerOnly])).toBe(0);
  });

  it('keeps active lifecycle states distinct from terminal failed and stopped states', () => {
    for (const state of ['STARTING', 'LISTENING', 'DEGRADED', 'STOPPING', 'UNKNOWN']) {
      expect(sourceSessionIsActive({ sessionId: 'session-1', state })).toBe(true);
    }
    expect(sourceSessionIsActive({ sessionId: 'session-1', state: 'FAILED' })).toBe(false);
    expect(sourceSessionIsActive({ sessionId: 'session-1', state: 'STOPPED' })).toBe(false);
  });

  it('fences an older asynchronous completion from a replacement generation', () => {
    const firstClient = {};
    const session = { client: firstClient, sessionId: 'same-id', generation: 1 };
    const older = captureSourceSessionToken(session);

    session.generation = 2;
    expect(sourceSessionTokenIsCurrent(older)).toBe(false);

    const replacement = captureSourceSessionToken(session);
    expect(sourceSessionTokenIsCurrent(replacement)).toBe(true);
    session.client = {};
    expect(sourceSessionTokenIsCurrent(replacement)).toBe(false);
  });

  it('replaces unproven transient claims with UNKNOWN but retains last observed server state', () => {
    expect(recoverSourceSessionState('STARTING')).toBe('UNKNOWN');
    expect(recoverSourceSessionState('STOPPING')).toBe('UNKNOWN');
    expect(recoverSourceSessionState('LISTENING')).toBe('LISTENING');
    expect(recoverSourceSessionState('DEGRADED')).toBe('DEGRADED');
  });
});
