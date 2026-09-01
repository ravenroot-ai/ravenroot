const DIRECTION_THRESHOLD = 1.15;
const MIN_TURN = 34;
const BASE_TURN = 46;
const DEFAULT_RADIUS = 36;
const MAX_PORT_OFFSET = 30;
const SLOT_STEP = 16;
const PORT_STEP = 11;
const CYTO_SIDES = ['right', 'left', 'bottom', 'top'];
const CYTO_NORMAL = Object.freeze({
  right: { x: 1, y: 0 }, left: { x: -1, y: 0 },
  bottom: { x: 0, y: 1 }, top: { x: 0, y: -1 },
});
const CYTO_OBSTACLE_MARGIN = 8;
const CYTO_CURVE_STEPS = 20;
const CYTO_ROOT_EPSILON = Number.EPSILON * 64;

const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const number = value => Number.isFinite(Number(value)) ? Number(value) : 0;

function nodeGeometry(node) {
  return {
    id: String(node?.id ?? ''),
    x: number(node?.x),
    y: number(node?.y),
    width: Math.max(0, number(node?.w ?? node?.width)),
    height: Math.max(0, number(node?.h ?? node?.height)),
  };
}

function directionFor(source, target) {
  const dx = target.x - source.x;
  const dy = target.y - source.y;
  return Math.abs(dy) > Math.abs(dx) * DIRECTION_THRESHOLD
    ? (dy >= 0 ? 'downward' : 'upward')
    : (dx >= 0 ? 'rightward' : 'leftward');
}

function sidesFor(direction, mode) {
  // Cyto's reference renderer deliberately keeps the classic right-output/left-input language
  // for forward, backward and vertical links. Its old final helper already used these ports; the
  // canonical descriptor now lets the preview use them too.
  if (mode === 'cyto' || mode === 'hierarchical') return { sourceSide: 'right', targetSide: 'left' };
  if (direction === 'leftward') return { sourceSide: 'left', targetSide: 'right' };
  if (direction === 'downward') return { sourceSide: 'bottom', targetSide: 'top' };
  if (direction === 'upward') return { sourceSide: 'top', targetSide: 'bottom' };
  return { sourceSide: 'right', targetSide: 'left' };
}

function endpointFor(node, side, offsetPx) {
  const offset = clamp(number(offsetPx), -MAX_PORT_OFFSET, MAX_PORT_OFFSET);
  const halfW = node.width / 2;
  const halfH = node.height / 2;
  if (side === 'right') return {
    point: { x: node.x + halfW, y: node.y + offset }, css: `${halfW}px ${offset}px`,
  };
  if (side === 'left') return {
    point: { x: node.x - halfW, y: node.y + offset }, css: `${-halfW}px ${offset}px`,
  };
  if (side === 'top') return {
    point: { x: node.x + offset, y: node.y - halfH }, css: `${offset}px ${-halfH}px`,
  };
  return {
    point: { x: node.x + offset, y: node.y + halfH }, css: `${offset}px ${halfH}px`,
  };
}

function orthogonalPoints(start, end, direction, turn) {
  if (direction === 'rightward') {
    const x = start.x + turn;
    return [{ x, y: start.y }, { x, y: end.y }];
  }
  if (direction === 'leftward') {
    const x = start.x - turn;
    return [{ x, y: start.y }, { x, y: end.y }];
  }
  if (direction === 'downward') {
    const y = start.y + turn;
    return [{ x: start.x, y }, { x: end.x, y }];
  }
  const y = start.y - turn;
  return [{ x: start.x, y }, { x: end.x, y }];
}

function bendDirection(routeKey, dy) {
  if (Math.abs(dy) > 1) return Math.sign(dy);
  const hash = [...String(routeKey || '')].reduce((sum, char) => sum + char.charCodeAt(0), 0);
  return hash % 2 ? 1 : -1;
}

function n8nBezierPoints(start, end, routeKey) {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  const handle = Math.max(48, Math.min(220, Math.abs(dx) * 0.42));
  const bow = bendDirection(routeKey, dy) * Math.max(6, Math.min(24, Math.abs(dy) * 0.10));
  return [
    { x: start.x + handle, y: start.y + bow },
    { x: end.x - handle, y: end.y + bow },
  ];
}

function cytoBezierPoints(start, end) {
  const handle = Math.max(48, Math.min(220, Math.abs(end.x - start.x) * 0.42));
  return [
    { x: start.x + handle, y: start.y },
    { x: end.x - handle, y: end.y },
  ];
}

function routeMidpoint(route) {
  if (route.family === 'bezier' || route.family === 'unbundled-bezier') {
    return {
      x: (route.points[0].x + route.points[1].x) / 2,
      y: (route.points[0].y + route.points[1].y) / 2,
    };
  }
  return {
    x: (route.points[0].x + route.points[1].x) / 2,
    y: (route.points[0].y + route.points[1].y) / 2,
  };
}

const compareId = (left, right) => left < right ? -1 : left > right ? 1 : 0;

function cytoPreferredSides(direction) {
  if (direction === 'leftward') return ['left', 'right'];
  if (direction === 'downward') return ['bottom', 'top'];
  if (direction === 'upward') return ['top', 'bottom'];
  return ['right', 'left'];
}

function cytoPinnedSide(edge, endpoint) {
  const direct = edge?.[`${endpoint}Anchor`];
  const nested = edge?.anchors?.[endpoint];
  const side = direct?.pinned === false ? null : (direct?.side ?? nested?.side);
  return CYTO_SIDES.includes(side) ? side : null;
}

function cytoControlPoints(start, end, sourceSide, targetSide, laneOffset) {
  const sourceNormal = CYTO_NORMAL[sourceSide];
  const targetNormal = CYTO_NORMAL[targetSide];
  const span = Math.hypot(end.x - start.x, end.y - start.y);
  const towardTarget = (end.x - start.x) * sourceNormal.x + (end.y - start.y) * sourceNormal.y;
  const towardSource = (start.x - end.x) * targetNormal.x + (start.y - end.y) * targetNormal.y;
  const openSpan = towardTarget > 0 && towardSource > 0 ? Math.min(towardTarget, towardSource) : Infinity;
  const clearance = Math.min(
    clamp(span * 0.28, BASE_TURN, 180) + Math.abs(number(laneOffset)) * 0.35,
    Math.max(4, openSpan * 0.4),
  );
  const sourceTangent = { x: -sourceNormal.y, y: sourceNormal.x };
  const targetTangent = { x: -targetNormal.y, y: targetNormal.x };
  return [
    {
      x: start.x + sourceNormal.x * clearance + sourceTangent.x * number(laneOffset),
      y: start.y + sourceNormal.y * clearance + sourceTangent.y * number(laneOffset),
    },
    {
      x: end.x + targetNormal.x * clearance + targetTangent.x * number(laneOffset),
      y: end.y + targetNormal.y * clearance + targetTangent.y * number(laneOffset),
    },
  ];
}

function cytoQuadraticPoint(start, control, end, t) {
  const inverse = 1 - t;
  return {
    x: inverse * inverse * start.x + 2 * inverse * t * control.x + t * t * end.x,
    y: inverse * inverse * start.y + 2 * inverse * t * control.y + t * t * end.y,
  };
}

function cytoSampleRoute(route) {
  const sampled = [];
  for (let step = 0; step <= CYTO_CURVE_STEPS / 2; step += 1) {
    sampled.push(cytoQuadraticPoint(route.start, route.points[0], route.midpoint, step / (CYTO_CURVE_STEPS / 2)));
  }
  for (let step = 1; step <= CYTO_CURVE_STEPS / 2; step += 1) {
    sampled.push(cytoQuadraticPoint(route.midpoint, route.points[1], route.end, step / (CYTO_CURVE_STEPS / 2)));
  }
  return sampled;
}

function cytoBounds(node, margin = 0) {
  return {
    left: node.x - node.width / 2 - margin, right: node.x + node.width / 2 + margin,
    top: node.y - node.height / 2 - margin, bottom: node.y + node.height / 2 + margin,
  };
}

function cytoBox(box, margin = 0) {
  if (!box) return null;
  if ([box.left, box.right, box.top, box.bottom].every(value => Number.isFinite(Number(value)))) {
    return {
      left: number(box.left) - margin, right: number(box.right) + margin,
      top: number(box.top) - margin, bottom: number(box.bottom) + margin,
    };
  }
  const x = number(box.x);
  const y = number(box.y);
  const width = Math.max(0, number(box.width));
  const height = Math.max(0, number(box.height));
  return {
    left: x - width / 2 - margin, right: x + width / 2 + margin,
    top: y - height / 2 - margin, bottom: y + height / 2 + margin,
  };
}

function cytoInside(point, box) {
  return box && point.x > box.left && point.x < box.right && point.y > box.top && point.y < box.bottom;
}

function cytoInteriorHits(points, box, omitEndpoints = false) {
  let hits = 0;
  const first = omitEndpoints ? 2 : 0;
  const last = omitEndpoints ? points.length - 2 : points.length;
  for (let index = first; index < last; index += 1) if (cytoInside(points[index], box)) hits += 1;
  return hits;
}

function cytoQuadraticBoundaryRoots(start, control, end, boundary) {
  const a = start - 2 * control + end;
  const b = 2 * (control - start);
  const c = start - boundary;
  if (a === 0) {
    if (b === 0) return [];
    return [-c / b];
  }
  const discriminant = b * b - 4 * a * c;
  if (discriminant < 0) return [];
  if (discriminant === 0) return [-b / (2 * a)];
  const root = Math.sqrt(discriminant);
  // The q form avoids subtracting almost-equal values when one root is close to a boundary.
  const q = -0.5 * (b + (b < 0 ? -root : root));
  if (q === 0) return [-b / (2 * a)];
  return [q / a, c / q];
}

function cytoQuadraticEntersBox(start, control, end, box) {
  if (!box || !(box.left < box.right && box.top < box.bottom)) return false;
  // A quadratic Bezier is contained by the convex hull of its three defining points. Rejecting a
  // box disjoint from that hull's AABB is therefore conservative; every possible overlap still
  // reaches the exact boundary-root partition below.
  const curveLeft = Math.min(start.x, control.x, end.x);
  const curveRight = Math.max(start.x, control.x, end.x);
  const curveTop = Math.min(start.y, control.y, end.y);
  const curveBottom = Math.max(start.y, control.y, end.y);
  if (curveRight <= box.left || curveLeft >= box.right || curveBottom <= box.top || curveTop >= box.bottom) return false;
  const roots = [
    ...cytoQuadraticBoundaryRoots(start.x, control.x, end.x, box.left),
    ...cytoQuadraticBoundaryRoots(start.x, control.x, end.x, box.right),
    ...cytoQuadraticBoundaryRoots(start.y, control.y, end.y, box.top),
    ...cytoQuadraticBoundaryRoots(start.y, control.y, end.y, box.bottom),
  ].filter(value => value >= -CYTO_ROOT_EPSILON && value <= 1 + CYTO_ROOT_EPSILON)
    .map(value => clamp(value, 0, 1));
  const partitions = [0, ...roots, 1].sort((left, right) => left - right)
    .filter((value, index, values) => index === 0 || value - values[index - 1] > CYTO_ROOT_EPSILON);
  for (let index = 1; index < partitions.length; index += 1) {
    const left = partitions[index - 1];
    const right = partitions[index];
    if (right <= left) continue;
    if (cytoInside(cytoQuadraticPoint(start, control, end, (left + right) / 2), box)) return true;
  }
  return false;
}

function cytoRouteEntersBox(route, box) {
  return cytoQuadraticEntersBox(route.start, route.points[0], route.midpoint, box)
    || cytoQuadraticEntersBox(route.midpoint, route.points[1], route.end, box);
}

function cytoOrientation(a, b, c) {
  const value = (b.y - a.y) * (c.x - b.x) - (b.x - a.x) * (c.y - b.y);
  return Math.abs(value) < 1e-7 ? 0 : Math.sign(value);
}

function cytoSampleSegments(points) {
  const segments = [];
  segments.left = Infinity;
  segments.right = -Infinity;
  segments.top = Infinity;
  segments.bottom = -Infinity;
  for (let index = 1; index < points.length; index += 1) {
    const start = points[index - 1];
    const end = points[index];
    const segment = {
      start, end,
      left: Math.min(start.x, end.x), right: Math.max(start.x, end.x),
      top: Math.min(start.y, end.y), bottom: Math.max(start.y, end.y),
    };
    segments.push(segment);
    segments.left = Math.min(segments.left, segment.left);
    segments.right = Math.max(segments.right, segment.right);
    segments.top = Math.min(segments.top, segment.top);
    segments.bottom = Math.max(segments.bottom, segment.bottom);
  }
  return segments;
}

function cytoSegmentsCross(left, right) {
  if (left.right < right.left || right.right < left.left
    || left.bottom < right.top || right.bottom < left.top) return false;
  return cytoOrientation(left.start, left.end, right.start)
      !== cytoOrientation(left.start, left.end, right.end)
    && cytoOrientation(right.start, right.end, left.start)
      !== cytoOrientation(right.start, right.end, left.end);
}

function cytoCrossings(points, priorSegments) {
  let crossings = 0;
  const segments = cytoSampleSegments(points);
  for (const other of priorSegments) {
    if (segments.right < other.left || other.right < segments.left
      || segments.bottom < other.top || other.bottom < segments.top) continue;
    for (const left of segments) for (const right of other) {
      if (cytoSegmentsCross(left, right)) crossings += 1;
    }
  }
  return crossings;
}

function cytoPathLength(points) {
  let length = 0;
  for (let index = 1; index < points.length; index += 1) {
    length += Math.hypot(points[index].x - points[index - 1].x, points[index].y - points[index - 1].y);
  }
  return length;
}

function cytoLabelBox(midpoint, label) {
  const text = String(label ?? '');
  if (!text) return null;
  const width = Math.min(240, Math.max(24, text.length * 7 + 12));
  return { left: midpoint.x - width / 2, right: midpoint.x + width / 2, top: midpoint.y - 15, bottom: midpoint.y + 15 };
}

function cytoCandidate({ source, target, edge, sourceSide, targetSide, laneOffset, sourcePortOffset, targetPortOffset }) {
  const sourceEndpoint = endpointFor(source, sourceSide, sourcePortOffset);
  const targetEndpoint = endpointFor(target, targetSide, targetPortOffset);
  const points = cytoControlPoints(sourceEndpoint.point, targetEndpoint.point, sourceSide, targetSide, laneOffset);
  const midpoint = { x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2 };
  return {
    coordinateSpace: 'model', family: 'unbundled-bezier', direction: directionFor(source, target),
    turn: BASE_TURN, radius: 0, sourceSide, targetSide,
    start: sourceEndpoint.point, end: targetEndpoint.point, points, midpoint,
    sourceEndpoint: sourceEndpoint.css, targetEndpoint: targetEndpoint.css,
    laneOffset: number(laneOffset),
    sourcePortOffset: clamp(number(sourcePortOffset), -MAX_PORT_OFFSET, MAX_PORT_OFFSET),
    targetPortOffset: clamp(number(targetPortOffset), -MAX_PORT_OFFSET, MAX_PORT_OFFSET),
    pinned: Boolean(cytoPinnedSide(edge, 'source') || cytoPinnedSide(edge, 'target')),
    labelBox: cytoLabelBox(midpoint, edge?.label),
  };
}

function cytoScore(route, {
  source, target, obstacles, priorRoutes, priorSegments, preferred, obstacleDependencies,
}) {
  const points = cytoSampleRoute(route);
  // Open, unexpanded endpoint boxes make the selected boundary contact legitimate while still
  // rejecting any later interval that returns through the node body.
  const endpointReentry = Number(cytoRouteEntersBox(route, cytoBounds(source)))
    + Number(cytoRouteEntersBox(route, cytoBounds(target)));
  let obstacleHits = 0;
  let labelHits = 0;
  obstacles.forEach(obstacle => {
    const obstacleHit = cytoRouteEntersBox(route, cytoBounds(obstacle, CYTO_OBSTACLE_MARGIN));
    const labelHit = cytoInteriorHits(points, cytoBox(obstacle.labelBounds, 6));
    if (obstacleHit || labelHit) obstacleDependencies.add(obstacle.id);
    obstacleHits += Number(obstacleHit);
    labelHits += labelHit;
  });
  priorRoutes.forEach(prior => { labelHits += cytoInteriorHits(points, cytoBox(prior.labelBox, 6)); });
  const preferredPenalty = route.sourceSide === preferred[0] && route.targetSide === preferred[1] ? 0 : 1;
  return [
    endpointReentry, obstacleHits, cytoCrossings(points, priorSegments), labelHits,
    preferredPenalty, cytoPathLength(points),
    CYTO_SIDES.indexOf(route.sourceSide) * CYTO_SIDES.length + CYTO_SIDES.indexOf(route.targetSide),
  ];
}

function cytoCompareScore(left, right) {
  for (let index = 0; index < left.length; index += 1) {
    if (Math.abs(left[index] - right[index]) > 1e-7) return left[index] - right[index];
  }
  return 0;
}

function resolveCytoEdgeRoute({
  source, target, edge = null, routeKey = '', nodes = [], priorRoutes = [],
  laneOffset = 0, sourcePortOffset = 0, targetPortOffset = 0,
}) {
  const sourceNode = nodeGeometry(source);
  const targetNode = nodeGeometry(target);
  const routeEdge = { id: String(edge?.id ?? routeKey), ...edge };
  const sourcePin = cytoPinnedSide(routeEdge, 'source');
  const targetPin = cytoPinnedSide(routeEdge, 'target');
  const sourceSides = sourcePin ? [sourcePin] : CYTO_SIDES;
  const targetSides = targetPin ? [targetPin] : CYTO_SIDES;
  const obstacles = nodes.map(node => ({ ...nodeGeometry(node), labelBounds: node?.labelBounds }))
    .filter(node => node.id !== sourceNode.id && node.id !== targetNode.id)
    .sort((left, right) => compareId(left.id, right.id));
  const preferred = cytoPreferredSides(directionFor(sourceNode, targetNode));
  const priorSegments = priorRoutes.map(route => cytoSampleSegments(cytoSampleRoute(route)));
  const obstacleDependencies = new Set();
  let best = null;
  let bestScore = null;
  sourceSides.forEach(sourceSide => targetSides.forEach(targetSide => {
    const candidate = cytoCandidate({
      source: sourceNode, target: targetNode, edge: routeEdge, sourceSide, targetSide,
      laneOffset, sourcePortOffset, targetPortOffset,
    });
    const score = cytoScore(candidate, {
      source: sourceNode, target: targetNode, obstacles, priorRoutes, priorSegments, preferred,
      obstacleDependencies,
    });
    if (!best || cytoCompareScore(score, bestScore) < 0) { best = candidate; bestScore = score; }
  }));
  return {
    ...best, score: bestScore, candidateEvaluations: sourceSides.length * targetSides.length,
    obstacleDependencyIds: [...obstacleDependencies].sort(compareId),
  };
}

// Resolve one edge entirely in model space. Callers may supply sibling allocation offsets, but no
// rendered measurement is accepted here: fixed thresholds and radii therefore cannot vary by zoom.
export function resolveRendererEdgeRoute({
  mode = 'dagre', source, target, routeKey = '', laneOffset = 0,
  sourcePortOffset = 0, targetPortOffset = 0, edge = null, nodes = [], priorRoutes = [],
} = {}) {
  if (mode === 'cyto') return resolveCytoEdgeRoute({
    source, target, routeKey, laneOffset, sourcePortOffset, targetPortOffset, edge, nodes, priorRoutes,
  });
  const sourceNode = nodeGeometry(source);
  const targetNode = nodeGeometry(target);
  const direction = directionFor(sourceNode, targetNode);
  const { sourceSide, targetSide } = sidesFor(direction, mode);
  const sourceEndpoint = endpointFor(sourceNode, sourceSide, sourcePortOffset);
  const targetEndpoint = endpointFor(targetNode, targetSide, targetPortOffset);
  const start = sourceEndpoint.point;
  const end = targetEndpoint.point;
  const horizontal = direction === 'rightward' || direction === 'leftward';
  const primarySpan = horizontal ? Math.abs(end.x - start.x) : Math.abs(end.y - start.y);
  const requestedTurn = BASE_TURN + number(laneOffset);
  const turn = Math.max(MIN_TURN, Math.min(primarySpan - 28, requestedTurn));
  const safeTurn = Number.isFinite(turn) ? turn : BASE_TURN;

  let family = 'bezier';
  let radius = 0;
  if (mode === 'n8n' || mode === 'elk') family = 'taxi';
  if (mode === 'hierarchical') { family = 'round-segments'; radius = DEFAULT_RADIUS; }
  if (mode === 'n8n2') { family = 'round-taxi'; radius = DEFAULT_RADIUS; }
  if (mode === 'n8n3') { family = 'round-segments'; radius = DEFAULT_RADIUS; }
  if (mode === 'n8n4') {
    // This is a model-space port gap. It remains 20 at every zoom and is unaffected by target
    // validity classes, borders or underlays.
    family = direction === 'rightward' && end.x - start.x >= 20
      ? 'unbundled-bezier' : 'round-segments';
    radius = family === 'round-segments' ? DEFAULT_RADIUS : 0;
  }
  if (mode === 'cyto') family = 'unbundled-bezier';

  let points;
  if (mode === 'cyto') points = cytoBezierPoints(start, end);
  else if (family === 'unbundled-bezier' || family === 'bezier') {
    points = n8nBezierPoints(start, end, routeKey);
  } else points = orthogonalPoints(start, end, direction, safeTurn);

  const route = {
    coordinateSpace: 'model', family, direction, turn: safeTurn, radius,
    sourceSide, targetSide, start, end, points,
    sourceEndpoint: sourceEndpoint.css, targetEndpoint: targetEndpoint.css,
    laneOffset: number(laneOffset),
    sourcePortOffset: clamp(number(sourcePortOffset), -MAX_PORT_OFFSET, MAX_PORT_OFFSET),
    targetPortOffset: clamp(number(targetPortOffset), -MAX_PORT_OFFSET, MAX_PORT_OFFSET),
  };
  return { ...route, midpoint: routeMidpoint(route) };
}

function slotOffset(group, edge) {
  if (!group || group.length <= 1) return 0;
  return group.indexOf(edge) - (group.length - 1) / 2;
}

function addToGroup(groups, key, edge) {
  const group = groups.get(key) || [];
  group.push(edge);
  groups.set(key, group);
}

function sortGroups(groups, compare) {
  groups.forEach(group => group.sort(compare));
}

// Resolve a whole renderer route set. A prospective edge is just another item in `edges`, so its
// descriptor and every sibling descriptor are calculated from the exact post-commit allocation.
export function resolveRendererEdgeRoutes({ mode = 'dagre', nodes = [], edges = [] } = {}) {
  if (mode === 'cyto') return resolveCytoEdgeRoutes({ nodes, edges });
  const nodesById = new Map(nodes.map(node => {
    const geometry = nodeGeometry(node);
    return [geometry.id, geometry];
  }));
  const routable = edges
    .map(edge => ({ id: String(edge.id), source: String(edge.source), target: String(edge.target) }))
    .filter(edge => edge.source !== edge.target && nodesById.has(edge.source) && nodesById.has(edge.target));
  const baseRoutes = new Map(routable.map(edge => [edge.id, resolveRendererEdgeRoute({
    mode, source: nodesById.get(edge.source), target: nodesById.get(edge.target), routeKey: edge.id,
  })]));

  if (!['hierarchical', 'n8n2', 'n8n3', 'n8n4'].includes(mode)) return baseRoutes;

  const outgoing = new Map();
  const incoming = new Map();
  const pairs = new Map();
  const sourceSides = new Map();
  const targetSides = new Map();
  routable.forEach(edge => {
    const route = baseRoutes.get(edge.id);
    addToGroup(outgoing, edge.source, edge);
    addToGroup(incoming, edge.target, edge);
    addToGroup(pairs, `${edge.source}→${edge.target}`, edge);
    addToGroup(sourceSides, `${edge.source}:${route.sourceSide}`, edge);
    addToGroup(targetSides, `${edge.target}:${route.targetSide}`, edge);
  });
  const byId = (left, right) => left.id.localeCompare(right.id);
  sortGroups(outgoing, (left, right) => {
    const delta = nodesById.get(left.target).y - nodesById.get(right.target).y;
    return delta || byId(left, right);
  });
  sortGroups(incoming, (left, right) => {
    const delta = nodesById.get(left.source).y - nodesById.get(right.source).y;
    return delta || byId(left, right);
  });
  sortGroups(pairs, byId);
  sourceSides.forEach((group, key) => {
    const side = key.split(':').at(-1);
    group.sort((left, right) => {
      const a = nodesById.get(left.target);
      const b = nodesById.get(right.target);
      const delta = side === 'top' || side === 'bottom' ? a.x - b.x : a.y - b.y;
      return delta || byId(left, right);
    });
  });
  targetSides.forEach((group, key) => {
    const side = key.split(':').at(-1);
    group.sort((left, right) => {
      const a = nodesById.get(left.source);
      const b = nodesById.get(right.source);
      const delta = side === 'top' || side === 'bottom' ? a.x - b.x : a.y - b.y;
      return delta || byId(left, right);
    });
  });

  return new Map(routable.map(edge => {
    const base = baseRoutes.get(edge.id);
    const sourceSlot = slotOffset(outgoing.get(edge.source), edge);
    const targetSlot = slotOffset(incoming.get(edge.target), edge);
    const pairSlot = slotOffset(pairs.get(`${edge.source}→${edge.target}`), edge);
    const laneOffset = Math.round((sourceSlot * 0.7 + targetSlot * 0.45 + pairSlot * 1.15) * SLOT_STEP);
    let sourcePortOffset = 0;
    let targetPortOffset = 0;
    if (mode === 'hierarchical' || mode === 'n8n3' || mode === 'n8n4') {
      const sourcePortSlot = slotOffset(sourceSides.get(`${edge.source}:${base.sourceSide}`), edge);
      const targetPortSlot = slotOffset(targetSides.get(`${edge.target}:${base.targetSide}`), edge);
      sourcePortOffset = Math.round((sourcePortSlot * 1.1 + pairSlot * 0.5) * PORT_STEP);
      targetPortOffset = Math.round((targetPortSlot * 1.1 + pairSlot * 0.5) * PORT_STEP);
    }
    return [edge.id, resolveRendererEdgeRoute({
      mode, source: nodesById.get(edge.source), target: nodesById.get(edge.target), routeKey: edge.id,
      laneOffset, sourcePortOffset, targetPortOffset,
    })];
  }));
}

function cytoRouteInput(nodes, edges) {
  const sortedNodes = nodes.map(node => ({ ...nodeGeometry(node), labelBounds: node?.labelBounds }))
    .sort((left, right) => compareId(left.id, right.id));
  const nodesById = new Map(sortedNodes.map(node => [node.id, node]));
  const sortedEdges = edges.map(edge => ({
    ...edge, id: String(edge.id), source: String(edge.source), target: String(edge.target),
  })).filter(edge => edge.source !== edge.target && nodesById.has(edge.source) && nodesById.has(edge.target))
    .sort((left, right) => compareId(left.id, right.id));
  return { sortedNodes, nodesById, sortedEdges };
}

function cytoAllocations(edges) {
  const outgoing = new Map();
  const incoming = new Map();
  const pairs = new Map();
  edges.forEach(edge => {
    addToGroup(outgoing, edge.source, edge);
    addToGroup(incoming, edge.target, edge);
    const endpoints = [edge.source, edge.target].sort(compareId);
    addToGroup(pairs, `${endpoints[0]}↔${endpoints[1]}`, edge);
  });
  [outgoing, incoming, pairs].forEach(groups => groups.forEach(group => group.sort((left, right) => compareId(left.id, right.id))));
  return edge => {
    const endpoints = [edge.source, edge.target].sort(compareId);
    const pairSlot = slotOffset(pairs.get(`${endpoints[0]}↔${endpoints[1]}`), edge);
    const sourceSlot = slotOffset(outgoing.get(edge.source), edge);
    const targetSlot = slotOffset(incoming.get(edge.target), edge);
    return {
      laneOffset: Math.round((sourceSlot * 0.7 + targetSlot * 0.45 + pairSlot * 1.15) * SLOT_STEP),
      sourcePortOffset: Math.round((sourceSlot * 1.1 + pairSlot * 0.5) * PORT_STEP),
      targetPortOffset: Math.round((targetSlot * 1.1 + pairSlot * 0.5) * PORT_STEP),
    };
  };
}

function resolveCytoEdgeRoutes({ nodes = [], edges = [] } = {}) {
  const { sortedNodes, nodesById, sortedEdges } = cytoRouteInput(nodes, edges);
  const allocation = cytoAllocations(sortedEdges);
  const routes = new Map();
  sortedEdges.forEach(edge => routes.set(edge.id, resolveCytoEdgeRoute({
    source: nodesById.get(edge.source), target: nodesById.get(edge.target), edge,
    routeKey: edge.id, nodes: sortedNodes, priorRoutes: [...routes.values()], ...allocation(edge),
  })));
  return routes;
}

export function resolveCytoEdgeRouteUpdate({
  nodes = [], edges = [], previousRoutes = new Map(), dirtyNodeIds = [],
} = {}) {
  const { sortedNodes, nodesById, sortedEdges } = cytoRouteInput(nodes, edges);
  const dirty = new Set([...dirtyNodeIds].map(String));
  const dirtyNodes = sortedNodes.filter(node => dirty.has(node.id));
  let interestEvaluations = 0;
  const selected = sortedEdges.filter(edge => {
    if (dirty.has(edge.source) || dirty.has(edge.target)) return true;
    const previous = previousRoutes.get(edge.id);
    if (!previous || !Array.isArray(previous.obstacleDependencyIds)) {
      if (dirtyNodes.length > 0) interestEvaluations += 1;
      return dirtyNodes.length > 0;
    }
    let sampled = null;
    return dirtyNodes.some(node => {
      interestEvaluations += 1;
      if (previous.obstacleDependencyIds.includes(node.id)
          || cytoRouteEntersBox(previous, cytoBounds(node, CYTO_OBSTACLE_MARGIN))) return true;
      if (!node.labelBounds) return false;
      sampled ??= cytoSampleRoute(previous);
      return cytoInteriorHits(sampled, cytoBox(node.labelBounds, 6)) > 0;
    });
  });
  const edgeIds = new Set(sortedEdges.map(edge => edge.id));
  const routes = new Map([...previousRoutes].filter(([id]) => edgeIds.has(id)));
  const allocation = cytoAllocations(sortedEdges);
  let candidateEvaluations = 0;
  selected.forEach(edge => {
    const priorRoutes = [...routes].filter(([id]) => id !== edge.id).map(([, route]) => route);
    const route = resolveCytoEdgeRoute({
      source: nodesById.get(edge.source), target: nodesById.get(edge.target), edge,
      routeKey: edge.id, nodes: sortedNodes, priorRoutes, ...allocation(edge),
    });
    routes.set(edge.id, route);
    candidateEvaluations += route.candidateEvaluations;
  });
  return {
    routes, routedEdgeIds: selected.map(edge => edge.id), candidateEvaluations, interestEvaluations,
  };
}

const transformPoint = (point, zoom, pan) => ({
  x: point.x * zoom + pan.x,
  y: point.y * zoom + pan.y,
});

// SVG is the projection layer only: the semantic descriptor is complete before zoom and pan are
// applied. This deliberately transforms radius and turn as well as points for inspection parity.
export function rendererEdgeRouteToRendered(route, { zoom = 1, pan = { x: 0, y: 0 } } = {}) {
  const scale = number(zoom) || 1;
  const translation = { x: number(pan?.x), y: number(pan?.y) };
  return {
    ...route,
    coordinateSpace: 'rendered',
    start: transformPoint(route.start, scale, translation),
    end: transformPoint(route.end, scale, translation),
    points: route.points.map(point => transformPoint(point, scale, translation)),
    midpoint: transformPoint(route.midpoint, scale, translation),
    turn: route.turn * scale,
    radius: route.radius * scale,
  };
}

function roundedPolylinePath(points, radius) {
  let path = `M ${points[0].x} ${points[0].y}`;
  for (let index = 1; index < points.length - 1; index += 1) {
    const previous = points[index - 1];
    const corner = points[index];
    const next = points[index + 1];
    const beforeLength = Math.hypot(corner.x - previous.x, corner.y - previous.y);
    const afterLength = Math.hypot(next.x - corner.x, next.y - corner.y);
    const cornerRadius = Math.min(radius, beforeLength / 2, afterLength / 2);
    if (cornerRadius <= 0) {
      path += ` L ${corner.x} ${corner.y}`;
      continue;
    }
    const before = {
      x: corner.x + ((previous.x - corner.x) / beforeLength) * cornerRadius,
      y: corner.y + ((previous.y - corner.y) / beforeLength) * cornerRadius,
    };
    const after = {
      x: corner.x + ((next.x - corner.x) / afterLength) * cornerRadius,
      y: corner.y + ((next.y - corner.y) / afterLength) * cornerRadius,
    };
    path += ` L ${before.x} ${before.y} Q ${corner.x} ${corner.y} ${after.x} ${after.y}`;
  }
  const end = points.at(-1);
  return `${path} L ${end.x} ${end.y}`;
}

export function rendererEdgePath(route) {
  const { start, end, points = [], family, radius = 0 } = route;
  if (family === 'unbundled-bezier') {
    return `M ${start.x} ${start.y} Q ${points[0].x} ${points[0].y} ${route.midpoint.x} ${route.midpoint.y} Q ${points[1].x} ${points[1].y} ${end.x} ${end.y}`;
  }
  if (family === 'bezier') {
    return `M ${start.x} ${start.y} C ${points[0].x} ${points[0].y}, ${points[1].x} ${points[1].y}, ${end.x} ${end.y}`;
  }
  return roundedPolylinePath([start, ...points, end], radius);
}
