import { describe, expect, it } from 'vitest';

import { TRACE_STOP_TYPES, traceDownstreamIds } from '../src/graph-trace.js';

// TraceDownstream() used to stop at any node whose visual type was in TERMINAL_TYPES,
// which was Set(['end', 'error']). `Error` is a routing point, so stopping the downstream trace
// there made the tool hide edges that ARE
// walked at runtime. Only `End` is an actual dead end.

function adjacencyFrom(edges, nodeTypes) {
  const adjacency = new Map();
  for (const nodeId of Object.keys(nodeTypes)) {
    adjacency.set(nodeId, { nodeType: nodeTypes[nodeId], outEdges: [] });
  }
  for (const [edgeId, source, target] of edges) {
    adjacency.get(source).outEdges.push({ edgeId, targetId: target });
  }
  return adjacency;
}

describe('traceDownstreamIds — downstream trace does not treat Error as a dead end', () => {
  it('a graph with Error -> Log includes Log in the trace from an upstream node', () => {
    // start -> error -> log
    const adjacency = adjacencyFrom(
      [
        ['e1', 'start', 'error'],
        ['e2', 'error', 'log'],
      ],
      { start: 'task', error: 'error', log: 'log' },
    );

    const { nodeIds, edgeIds } = traceDownstreamIds(adjacency, 'start');

    expect(nodeIds.has('log')).toBe(true);
    expect(edgeIds.has('e2')).toBe(true);
    expect(nodeIds).toEqual(new Set(['start', 'error', 'log']));
  });

  it('continues past Error through a full remediation chain and back into the flow', () => {
    // dosomething -> error -> retry -> dosomething (cycle back in)
    // \-> end
    const adjacency = adjacencyFrom(
      [
        ['e1', 'dosomething', 'error'],
        ['e2', 'dosomething', 'end'],
        ['e3', 'error', 'retry'],
        ['e4', 'retry', 'dosomething'],
      ],
      { dosomething: 'task', error: 'error', retry: 'task', end: 'end' },
    );

    const { nodeIds } = traceDownstreamIds(adjacency, 'dosomething');

    expect(nodeIds).toEqual(new Set(['dosomething', 'error', 'retry', 'end']));
  });

  it('End remains a true terminal: the trace reaches it but does not expand past it', () => {
    const adjacency = adjacencyFrom(
      [
        ['e1', 'start', 'end'],
        // If the BFS incorrectly expanded past 'end', this edge would surface 'ghost' in the trace.
        ['e2', 'end', 'ghost'],
      ],
      { start: 'task', end: 'end', ghost: 'task' },
    );

    const { nodeIds, edgeIds } = traceDownstreamIds(adjacency, 'start');

    expect(nodeIds).toEqual(new Set(['start', 'end']));
    expect(edgeIds).toEqual(new Set(['e1']));
    expect(nodeIds.has('ghost')).toBe(false);
  });

  it('tracing from Error itself still shows what follows it — trivially true here, since "error" is not in TRACE_STOP_TYPES at all; this does NOT exercise the start-node exemption (see the "starts at End" test below for that)', () => {
    const adjacency = adjacencyFrom([['e1', 'error', 'log']], { error: 'error', log: 'log' });

    const { nodeIds } = traceDownstreamIds(adjacency, 'error');

    expect(nodeIds).toEqual(new Set(['error', 'log']));
  });

  it('tracing that STARTS at End still shows what follows it: the "id !== startId" exemption applies to the start node regardless of its own type, including a real stop-type', () => {
    // end -> ghost. 'end' IS in TRACE_STOP_TYPES, so this only reaches 'ghost' because the start
    // node is exempt from stopping its own expansion. Without the `&& id !== startId` clause,
    // 'ghost' would never be reached from an End start node.
    const adjacency = adjacencyFrom([['e1', 'end', 'ghost']], { end: 'end', ghost: 'task' });

    const { nodeIds, edgeIds } = traceDownstreamIds(adjacency, 'end');

    expect(nodeIds).toEqual(new Set(['end', 'ghost']));
    expect(edgeIds).toEqual(new Set(['e1']));
  });

  it('a target id absent from the adjacency map is still reached (treated as a leaf), not dropped or thrown on', () => {
    // 'missing' is referenced as an edge target but has no entry in the adjacency map at all —
    // e.g. a dangling edge, or a target whose own node wasn't included in the snapshot.
    const adjacency = new Map([['start', { nodeType: 'task', outEdges: [{ edgeId: 'e1', targetId: 'missing' }] }]]);

    const { nodeIds, edgeIds } = traceDownstreamIds(adjacency, 'start');

    expect(nodeIds).toEqual(new Set(['start', 'missing']));
    expect(edgeIds).toEqual(new Set(['e1']));
  });

  it('TRACE_STOP_TYPES holds only "end" — the single behavior this module is responsible for', () => {
    expect(TRACE_STOP_TYPES).toEqual(new Set(['end']));
  });
});
