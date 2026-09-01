/** Convert model-space route points into Cytoscape's renderer-relative coordinates. */
export function viewerControlPointStyle(route) {
  const dx = route.end.x - route.start.x;
  const dy = route.end.y - route.start.y;
  const squared = dx * dx + dy * dy || 1;
  const length = Math.sqrt(squared);
  return route.points.map(point => ({
    weight: ((point.x - route.start.x) * dx + (point.y - route.start.y) * dy) / squared,
    distance: ((point.x - route.start.x) * -dy + (point.y - route.start.y) * dx) / length,
  }));
}

export function applyViewerRoundedSegmentRoute(edge, route) {
  const control = viewerControlPointStyle(route);
  edge.style({
    'curve-style': route.family,
    'segment-weights': control.map(point => point.weight),
    'segment-distances': control.map(point => point.distance),
    'segment-radii': [route.radius, route.radius],
    'radius-type': 'arc-radius',
    'edge-distances': 'endpoints',
    'source-endpoint': route.sourceEndpoint,
    'target-endpoint': route.targetEndpoint,
    width: 2.5,
  });
}

export function applyViewerUnbundledRoute(edge, route, { lineCap = 'square', width = 2 } = {}) {
  const control = viewerControlPointStyle(route);
  edge.style({
    'curve-style': route.family,
    'control-point-weights': control.map(point => point.weight),
    'control-point-distances': control.map(point => point.distance),
    'edge-distances': 'endpoints',
    'source-endpoint': route.sourceEndpoint,
    'target-endpoint': route.targetEndpoint,
    'line-cap': lineCap,
    width,
  });
}

/** A bounded fallback which clears any control points from an earlier mode. */
export function applyViewerSimpleRoute(edge) {
  edge.removeStyle('control-point-weights control-point-distances segment-weights segment-distances segment-radii');
  edge.style({
    'curve-style': 'bezier',
    'edge-distances': 'intersection',
    'source-endpoint': 'outside-to-node',
    'target-endpoint': 'outside-to-node',
    'line-cap': 'round',
    width: 2,
  });
}
