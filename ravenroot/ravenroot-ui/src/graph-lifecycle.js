export const GRAPH_LIFECYCLE_NOT_IMPLEMENTED = 'NOT_YET_IMPLEMENTED';

const descriptions = Object.freeze({
  run: 'Real deployment start and recovery are not implemented yet.',
  pause: 'Safe-boundary pause and durable resume are not implemented yet.',
  stop: 'Cooperative stop for this graph deployment is not implemented yet.',
  forceStop: 'Forced isolation kill for this graph deployment is not implemented yet.',
});

/**
 * Temporary application boundary for lifecycle controls.
 *
 * Keeping the graph identity in the result prevents future clients from accidentally replacing a
 * deployment-scoped command with a server-wide ActorSystem drain. The implementation will move
 * behind the runtime client when the versioned DeploymentManager API is available.
 */
export function requestGraphLifecycle(action, graphContext = {}) {
  if (!Object.hasOwn(descriptions, action)) throw new TypeError(`Unknown graph lifecycle action: ${action}`);
  return Object.freeze({
    status: GRAPH_LIFECYCLE_NOT_IMPLEMENTED,
    action,
    documentId: graphContext.documentId || null,
    graphName: graphContext.graphName || null,
    deploymentId: graphContext.deploymentId || null,
    message: descriptions[action],
  });
}
