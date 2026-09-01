// Downstream path trace (right-click, `traceDownstream` in app.js) — the BFS extracted here so it
// is testable without a Cytoscape/DOM instance.
//
// TRACE_STOP_TYPES decides which node types stop the BFS from expanding further outgoing edges.
// It intentionally holds only 'end'.
//
// 'error' does NOT belong here. An `Error` node is a routing point: flow continues from it toward
// logging, notification, retry or remediation, possibly rejoining the flow. `Error` is mandatory
// in every graph, so
// every graph has at least one such point. "Error terminal" in that decision names `Error` as
// the single, well-known entry point into error handling, not as a dead end with no outgoing edges
// — those edges exist and are walked at runtime. Stopping the downstream trace there made the
// one tool meant to show where the flow goes lie about a part of the flow that actually runs.
//
// This constant has exactly one consumer in the codebase (this module, from traceDownstream in
// app.js) — verified with:
// grep -rn "TERMINAL_TYPES\|TRACE_STOP_TYPES" --include="*.js" . (excluding node_modules/dist)
// so narrowing it to 'end' changes no other behavior.
export const TRACE_STOP_TYPES = new Set(['end']);

/**
 * Strict forward BFS over outgoing edges, starting at startId.
 *
 * @param {Map<string, { nodeType: string, outEdges: Array<{ edgeId: string, targetId: string }> }>} adjacency
 * Node id -> its visual type and its outgoing edges. Nodes absent from the map are treated as
 * leaves (visited, nothing further to expand).
 * @param {string} startId
 * @returns {{ nodeIds: Set<string>, edgeIds: Set<string> }} everything reached, including startId.
 */
export function traceDownstreamIds(adjacency, startId) {
  const nodeIds = new Set();
  const edgeIds = new Set();
  const queue = [startId];
  const seen = new Set([startId]);

  while (queue.length) {
    const id = queue.shift();
    nodeIds.add(id);

    const entry = adjacency.get(id);
    if (!entry) continue;

    // Stop expanding at a stop-type node, but only once we've moved past the start node itself —
    // tracing *from* an Error (or, degenerately, an End) node should still show what follows it.
    const stopsHere = TRACE_STOP_TYPES.has(entry.nodeType) && id !== startId;
    if (stopsHere) continue;

    for (const { edgeId, targetId } of entry.outEdges || []) {
      edgeIds.add(edgeId);
      if (!seen.has(targetId)) {
        seen.add(targetId);
        queue.push(targetId);
      }
    }
  }

  return { nodeIds, edgeIds };
}
