/**
 * Geometry checks for judging a graph drawing.
 *
 * <p>These are the acceptance criteria of the layered arrangements written as code: edge
 * crossings, edges running through nodes or labels, labels colliding, edges piling on the same
 * line, nodes off their layer column, and back edges cutting through the band of node rows. They
 * work on plain geometry — polylines and boxes in model coordinates — so the same functions judge
 * a drawing computed in a unit test and one sampled from the rendered canvas.</p>
 */

const EPSILON = 1e-6;

const cross = (ax, ay, bx, by) => ax * by - ay * bx;

/** Whether two closed segments intersect at a point interior to both (touching ends do not count). */
export function segmentsCross(p1, p2, q1, q2) {
  const rx = p2.x - p1.x;
  const ry = p2.y - p1.y;
  const sx = q2.x - q1.x;
  const sy = q2.y - q1.y;
  const denominator = cross(rx, ry, sx, sy);
  if (Math.abs(denominator) < EPSILON) return false;
  const qx = q1.x - p1.x;
  const qy = q1.y - p1.y;
  const t = cross(qx, qy, sx, sy) / denominator;
  const u = cross(qx, qy, rx, ry) / denominator;
  return t > EPSILON && t < 1 - EPSILON && u > EPSILON && u < 1 - EPSILON;
}

/** The consecutive segments of a polyline `[{x, y}, ...]`. */
export function segmentsOf(points) {
  const segments = [];
  for (let index = 1; index < points.length; index++) segments.push([points[index - 1], points[index]]);
  return segments;
}

/**
 * Proper crossings between the polylines of distinct edges. Each polyline is
 * `{ id, source, target, points }`; the count is over unordered pairs of edges, each pair counted
 * once per crossing segment pair.
 */
export function countCrossings(polylines) {
  const items = polylines.map(line => ({ ...line, segments: segmentsOf(line.points) }));
  let crossings = 0;
  for (let i = 0; i < items.length; i++) {
    for (let j = i + 1; j < items.length; j++) {
      for (const [p1, p2] of items[i].segments) {
        for (const [q1, q2] of items[j].segments) {
          if (segmentsCross(p1, p2, q1, q2)) crossings++;
        }
      }
    }
  }
  return crossings;
}

function boxesIntersect(a, b, shrink = 0) {
  return a.left + shrink < b.right - shrink && b.left + shrink < a.right - shrink
    && a.top + shrink < b.bottom - shrink && b.top + shrink < a.bottom - shrink;
}

/** Pairs of nodes whose label boxes intersect. Nodes are `{ id, label: { left, top, right, bottom } }`. */
export function labelOverlaps(nodes) {
  const labelled = nodes.filter(node => node.label);
  const overlaps = [];
  for (let i = 0; i < labelled.length; i++) {
    for (let j = i + 1; j < labelled.length; j++) {
      if (boxesIntersect(labelled[i].label, labelled[j].label)) overlaps.push([labelled[i].id, labelled[j].id]);
    }
  }
  return overlaps;
}

/** Liang–Barsky: whether a closed segment has a point strictly inside a box. */
export function segmentIntersectsBox(p, q, box, shrink = 0) {
  const left = box.left + shrink;
  const right = box.right - shrink;
  const top = box.top + shrink;
  const bottom = box.bottom - shrink;
  if (left >= right || top >= bottom) return false;
  let t0 = 0;
  let t1 = 1;
  const dx = q.x - p.x;
  const dy = q.y - p.y;
  const clip = (denominator, numerator) => {
    if (Math.abs(denominator) < EPSILON) return numerator >= 0;
    const t = numerator / denominator;
    if (denominator < 0) {
      if (t > t1) return false;
      if (t > t0) t0 = t;
    } else {
      if (t < t0) return false;
      if (t < t1) t1 = t;
    }
    return true;
  };
  if (!clip(-dx, p.x - left)) return false;
  if (!clip(dx, right - p.x)) return false;
  if (!clip(-dy, p.y - top)) return false;
  if (!clip(dy, bottom - p.y)) return false;
  return t1 - t0 > EPSILON;
}

/**
 * Edges whose path enters a box of a node that is not one of their endpoints. `boxOf` picks the
 * box to test (body, label, or their union) from a node `{ id, body, label }`.
 */
export function edgesThroughBoxes(polylines, nodes, boxOf, { shrink = 0.5 } = {}) {
  const hits = [];
  for (const line of polylines) {
    const segments = segmentsOf(line.points);
    for (const node of nodes) {
      if (node.id === line.source || node.id === line.target) continue;
      const box = boxOf(node);
      if (!box) continue;
      if (segments.some(([p, q]) => segmentIntersectsBox(p, q, box, shrink))) hits.push({ edge: line.id, node: node.id });
    }
  }
  return hits;
}

export const bodyOf = node => node.body || null;
export const labelBoxOf = node => node.label || null;
export const extentOf = node => (node.body && node.label ? {
  left: Math.min(node.body.left, node.label.left), right: Math.max(node.body.right, node.label.right),
  top: Math.min(node.body.top, node.label.top), bottom: Math.max(node.body.bottom, node.label.bottom),
} : node.body || node.label || null);

function collinearOverlap(p1, p2, q1, q2, tolerance) {
  const rx = p2.x - p1.x;
  const ry = p2.y - p1.y;
  const length = Math.hypot(rx, ry);
  if (length < EPSILON) return 0;
  const ux = rx / length;
  const uy = ry / length;
  const offset = point => Math.abs(cross(ux, uy, point.x - p1.x, point.y - p1.y));
  if (offset(q1) > tolerance || offset(q2) > tolerance) return 0;
  const project = point => (point.x - p1.x) * ux + (point.y - p1.y) * uy;
  const a0 = 0;
  const a1 = length;
  const b0 = Math.min(project(q1), project(q2));
  const b1 = Math.max(project(q1), project(q2));
  return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
}

/**
 * Pairs of distinct edges that share a collinear run longer than `minLength`: two edges drawn on
 * top of each other. Segments within `tolerance` pixels of the same line count as collinear.
 */
export function sharedRuns(polylines, { minLength, tolerance = 1 }) {
  const items = polylines.map(line => ({ ...line, segments: segmentsOf(line.points) }));
  const shared = [];
  for (let i = 0; i < items.length; i++) {
    for (let j = i + 1; j < items.length; j++) {
      let longest = 0;
      for (const [p1, p2] of items[i].segments) {
        for (const [q1, q2] of items[j].segments) {
          longest = Math.max(longest, collinearOverlap(p1, p2, q1, q2, tolerance));
        }
      }
      if (longest > minLength) shared.push({ edges: [items[i].id, items[j].id], length: longest });
    }
  }
  return shared;
}

/**
 * Cluster node centres into layer columns. Returns the columns and the nodes whose centre is
 * farther than `tolerance` from every column.
 */
export function layerColumns(nodes, { tolerance = 3 } = {}) {
  const xs = nodes.map(node => node.x).sort((a, b) => a - b);
  const columns = [];
  for (const x of xs) {
    const last = columns[columns.length - 1];
    if (last && Math.abs(x - last.x) <= tolerance) {
      last.sum += x;
      last.count += 1;
      last.x = last.sum / last.count;
      continue;
    }
    columns.push({ x, sum: x, count: 1 });
  }
  const centres = columns.map(column => column.x);
  const layerOf = new Map();
  const offGrid = [];
  for (const node of nodes) {
    let best = -1;
    let distance = Infinity;
    centres.forEach((centre, index) => {
      const candidate = Math.abs(centre - node.x);
      if (candidate < distance) {
        distance = candidate;
        best = index;
      }
    });
    if (distance > tolerance) offGrid.push(node.id);
    layerOf.set(node.id, best);
  }
  return { columns: centres, layerOf, offGrid };
}

function samplePolyline(points, step) {
  const samples = [];
  for (const [p, q] of segmentsOf(points)) {
    const length = Math.hypot(q.x - p.x, q.y - p.y);
    const count = Math.max(1, Math.ceil(length / step));
    for (let index = 0; index <= count; index++) {
      const t = index / count;
      samples.push({ x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t });
    }
  }
  return samples;
}

/**
 * Back edges — target layer at or before the source layer — that enter the vertical extent of a
 * column strictly between their endpoints' columns. Nodes are `{ id, x, body, label }` with the
 * union of body and label giving the column's band; `layerOf` maps node ids to column indices.
 */
export function backEdgesInsideBand(polylines, nodes, layerOf, { step = 4 } = {}) {
  const columns = new Map();
  for (const node of nodes) {
    const extent = extentOf(node);
    const index = layerOf.get(node.id);
    if (!extent || index == null) continue;
    const column = columns.get(index) || { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity };
    column.left = Math.min(column.left, extent.left);
    column.right = Math.max(column.right, extent.right);
    column.top = Math.min(column.top, extent.top);
    column.bottom = Math.max(column.bottom, extent.bottom);
    columns.set(index, column);
  }
  const violations = [];
  for (const line of polylines) {
    const from = layerOf.get(line.source);
    const to = layerOf.get(line.target);
    if (from == null || to == null || to > from) continue;
    const samples = samplePolyline(line.points, step);
    for (let index = to + 1; index < from; index++) {
      const column = columns.get(index);
      if (!column) continue;
      const inside = samples.some(sample => sample.x > column.left && sample.x < column.right
        && sample.y > column.top && sample.y < column.bottom);
      if (inside) violations.push({ edge: line.id, column: index });
    }
  }
  return violations;
}

/** Whether every edge is a back edge or forward edge according to `layerOf`; used to classify. */
export function isBackEdge(line, layerOf) {
  const from = layerOf.get(line.source);
  const to = layerOf.get(line.target);
  return from != null && to != null && to <= from;
}
