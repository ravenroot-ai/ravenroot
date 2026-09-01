import { resolveRendererEdgeRoutes } from './renderer-edge-route.js';

// The detailed obstacle router compares graph elements while finding detours.
// Keep that presentation for ordinary diagrams, but never let projection size
// turn viewer readiness into an unbounded main-thread operation.
export const VIEWER_DETAILED_ROUTE_WORK_BUDGET = 50_000;
export const VIEWER_DETAILED_ROUTE_PAIR_BUDGET = 40_000;
export const VIEWER_ELASTIC_NODE_LIMIT = 500;
export const VIEWER_ELASTIC_EDGE_LIMIT = 1_000;

function validCount(value, name) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new TypeError(`Invalid viewer route ${name}.`);
  }
  return value;
}

export function viewerRouteStrategy(nodeCount, edgeCount) {
  const nodes = validCount(nodeCount, 'node count');
  const edges = validCount(edgeCount, 'edge count');
  // The router also compares prior routes and crossings, so N*E alone is not
  // sufficient for low-node, high-edge multigraphs. Quotients avoid overflow.
  const withinNodeEdgeBudget = edges === 0
    || nodes <= Math.floor(VIEWER_DETAILED_ROUTE_WORK_BUDGET / edges);
  const withinEdgePairBudget = edges === 0
    || edges <= Math.floor(VIEWER_DETAILED_ROUTE_PAIR_BUDGET / edges);
  if (withinNodeEdgeBudget && withinEdgePairBudget) {
    return 'detailed';
  }
  return 'simple';
}

export function viewerSupportsElastic(nodeCount, edgeCount) {
  const nodes = validCount(nodeCount, 'node count');
  const edges = validCount(edgeCount, 'edge count');
  return nodes <= VIEWER_ELASTIC_NODE_LIMIT && edges <= VIEWER_ELASTIC_EDGE_LIMIT;
}

/**
 * Production routing boundary used by the embed viewer. The expensive router
 * is never invoked when its conservative work estimate exceeds the budget.
 */
export function resolveViewerRoutesWithinBudget({ nodes, edges }) {
  if (!Array.isArray(nodes) || !Array.isArray(edges)) {
    throw new TypeError('Viewer route elements must be arrays.');
  }
  const strategy = viewerRouteStrategy(nodes.length, edges.length);
  return Object.freeze({
    strategy,
    routes: strategy === 'detailed'
      ? resolveRendererEdgeRoutes({ mode: 'cyto', nodes, edges })
      : null,
  });
}
