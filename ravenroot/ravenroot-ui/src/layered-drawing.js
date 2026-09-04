/**
 * Layered graph drawing for the design editor's additive `Hierarchical (new)` and `Flow (new)`
 * arrangements.
 *
 * <p>The existing arrangements hand node positions to Cytoscape and then re-route every edge from
 * its endpoints alone, one edge at a time. This module treats placement and routing as one
 * problem: it asks ELK's layered algorithm for both the node coordinates and the edge sections,
 * with node labels declared as outside labels so the spacing reserves room for them, and then
 * converts the sections into absolute polylines Cytoscape can draw. Backward edges — those whose
 * target sits on the same or an earlier layer than their source — are not drawn through the
 * channels between layers; they are routed in dedicated tracks below the band of node rows, so
 * they never cut across the main flow.</p>
 *
 * <p>This module is pure: no DOM, no Cytoscape. The caller measures nodes and labels and applies
 * the result. Keeping it pure is what lets the acceptance criteria be checked in unit tests with
 * the same code the editor runs.</p>
 */
import ELK from 'elkjs/lib/elk.bundled.js';

const number = value => Number.isFinite(Number(value)) ? Number(value) : 0;

const BACK_EDGE_RADIUS = 14;
const BACK_TRACK_OFFSET = 28;
const BACK_TRACK_GAP = 14;
const BACK_STUB_BASE = 8;
const BACK_STUB_STEP = 6;
const COLUMN_TOLERANCE = 2;

export const LAYERED_MODES = Object.freeze({
  'hierarchical-new': Object.freeze({
    routing: 'ORTHOGONAL', family: 'round-segments', radius: 12, placement: 'BRANDES_KOEPF',
    nodeNode: 44, betweenLayers: 96, edgeEdge: 14, edgeNode: 26,
  }),
  'flow-new': Object.freeze({
    routing: 'POLYLINE', family: 'round-segments', radius: 22, placement: 'NETWORK_SIMPLEX',
    nodeNode: 30, betweenLayers: 72, edgeEdge: 10, edgeNode: 22,
  }),
});

export const LAYERED_MODE_NAMES = Object.freeze(Object.keys(LAYERED_MODES));

export function isLayeredMode(mode) {
  return Object.prototype.hasOwnProperty.call(LAYERED_MODES, String(mode));
}

export function layeredElkOptions(mode) {
  const spec = LAYERED_MODES[mode];
  if (!spec) throw new TypeError(`Unknown layered drawing mode: ${mode}`);
  return {
    'elk.algorithm': 'layered',
    'elk.direction': 'RIGHT',
    'elk.edgeRouting': spec.routing,
    // Depth-first cycle breaking from the sources reverses the edges that actually run backwards
    // in a workflow (retry, rework, no-issue) instead of whichever edges a greedy pass finds
    // first, which is what left a START node in a middle layer.
    'elk.layered.cycleBreaking.strategy': 'DEPTH_FIRST',
    'elk.layered.layering.strategy': 'NETWORK_SIMPLEX',
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.layered.thoroughness': '30',
    'elk.layered.nodePlacement.strategy': spec.placement,
    'elk.layered.nodePlacement.bk.fixedAlignment': 'BALANCED',
    'elk.layered.unnecessaryBendpoints': 'true',
    // Backward edges are routed by this module, outside the band; ELK only has to layer them.
    'elk.layered.feedbackEdges': 'false',
    'elk.spacing.nodeNode': String(spec.nodeNode),
    'elk.layered.spacing.nodeNodeBetweenLayers': String(spec.betweenLayers),
    'elk.spacing.edgeEdge': String(spec.edgeEdge),
    'elk.layered.spacing.edgeEdgeBetweenLayers': String(spec.edgeEdge),
    'elk.spacing.edgeNode': String(spec.edgeNode),
    'elk.layered.spacing.edgeNodeBetweenLayers': String(spec.edgeNode),
    'elk.spacing.labelNode': '0',
    'elk.padding': '[top=20,left=20,bottom=20,right=20]',
  };
}

function labelOf(node) {
  const label = node?.label;
  if (!label) return null;
  const width = number(label.width);
  const height = number(label.height);
  return width > 0 && height > 0 ? { text: String(label.text ?? ''), width, height } : null;
}

/** The ELK input graph for one drawing mode: node bodies with outside bottom labels. */
export function buildLayeredElkGraph(inputs, mode) {
  const children = (inputs?.nodes || []).map(node => {
    const label = labelOf(node);
    const layoutOptions = {
      // Every node of a layer shares one centre column, so the drawing reads as discrete levels
      // even when node bodies differ in width.
      'elk.alignment': 'CENTER',
      // Declared as an outside label, the label becomes a node margin: spacing and routing keep
      // clear of it while edges still attach to the node body.
      'elk.nodeLabels.placement': '[H_CENTER, V_BOTTOM, OUTSIDE]',
    };
    if (node.kind === 'START') layoutOptions['elk.layered.layering.layerConstraint'] = 'FIRST';
    else if (node.kind === 'END') layoutOptions['elk.layered.layering.layerConstraint'] = 'LAST';
    return {
      id: String(node.id),
      width: Math.max(1, number(node.width)),
      height: Math.max(1, number(node.height)),
      layoutOptions,
      labels: label ? [label] : [],
    };
  });
  const ids = new Set(children.map(child => child.id));
  const edges = (inputs?.edges || [])
    .map(edge => ({ id: String(edge.id), source: String(edge.source), target: String(edge.target) }))
    // Self-loops keep the editor's own loop rendering; ELK never sees them.
    .filter(edge => ids.has(edge.source) && ids.has(edge.target) && edge.source !== edge.target)
    .map(edge => ({ id: edge.id, sources: [edge.source], targets: [edge.target] }));
  return { id: 'root', layoutOptions: layeredElkOptions(mode), children, edges };
}

function point(value) {
  return { x: number(value?.x), y: number(value?.y) };
}

/** The absolute polyline of one ELK edge section: its start, bend points and end, de-duplicated. */
export function sectionPolyline(section) {
  const start = point(section?.startPoint);
  const end = point(section?.endPoint);
  const bends = (section?.bendPoints || []).map(point);
  return dedupe([start, ...bends, end]);
}

function dedupe(points) {
  const out = [];
  for (const candidate of points) {
    const last = out[out.length - 1];
    if (last && Math.abs(last.x - candidate.x) < 0.01 && Math.abs(last.y - candidate.y) < 0.01) continue;
    out.push({ x: candidate.x, y: candidate.y });
  }
  return out;
}

function endpointCss(box, at) {
  return `${(at.x - box.x).toFixed(2)}px ${(at.y - box.y).toFixed(2)}px`;
}

function makeRoute(id, source, target, polyline, boxes, family, radius, kind) {
  const start = polyline[0];
  const end = polyline[polyline.length - 1];
  const points = polyline.slice(1, -1);
  return Object.freeze({
    id, source, target, kind, start, end, points,
    family: points.length ? family : 'straight',
    radius,
    coordinateSpace: 'model',
    sourceEndpoint: endpointCss(boxes.get(source), start),
    targetEndpoint: endpointCss(boxes.get(target), end),
  });
}

function clusterColumns(xs) {
  const columns = [];
  for (const x of [...xs].sort((a, b) => a - b)) {
    const last = columns[columns.length - 1];
    if (last && Math.abs(x - last) <= COLUMN_TOLERANCE) continue;
    columns.push(x);
  }
  return columns;
}

/**
 * Backward edges leave the source's east side from a port of their own, drop below the band of
 * node rows into a track of their own, run back under everything, and rise into a port of their
 * own on the target's west side. Shorter spans take
 * the tracks nearest the band so nested back edges never cross each other; the little stubs that
 * carry an edge from its port to its vertical are ordered so two back edges sharing a node do not
 * cross either. The verticals sit inside the channel next to the layer's label extent, closer to
 * the layer than any vertical ELK draws there, so they cross no label and overlap no ELK segment.
 */
const PORT_STEP = 6;
const PORT_INSET = 8;
const PORT_CLEARANCE = 4;

// The next port slot on one node side, counted up from the bottom, that no forward edge already
// uses there; back edges of the same node take successive slots.
function freePort(box, usedYs, taken) {
  for (let slot = 0; slot < 64; slot++) {
    const y = box.y + box.height / 2 - PORT_INSET - slot * PORT_STEP;
    if (y < box.y - box.height / 2 + PORT_INSET) break;
    if ([...usedYs, ...taken].some(used => Math.abs(used - y) < PORT_CLEARANCE)) continue;
    taken.push(y);
    return y;
  }
  const y = box.y;
  taken.push(y);
  return y;
}

function routeBackEdges(backEdges, boxes, layerOf, layerExtents, bandBottom, forwardRoutes) {
  const eastPorts = new Map();
  const westPorts = new Map();
  for (const route of forwardRoutes) {
    if (!eastPorts.has(route.source)) eastPorts.set(route.source, []);
    eastPorts.get(route.source).push(route.start.y);
    if (!westPorts.has(route.target)) westPorts.set(route.target, []);
    westPorts.get(route.target).push(route.end.y);
  }
  const takenEast = new Map();
  const takenWest = new Map();
  const span = edge => Math.abs(layerOf.get(edge.source) - layerOf.get(edge.target));
  const ordered = [...backEdges].sort((a, b) => span(a) - span(b) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const track = new Map(ordered.map((edge, index) => [edge.id, index]));
  const leaving = new Map();
  const entering = new Map();
  for (const edge of ordered) {
    if (!leaving.has(edge.source)) leaving.set(edge.source, []);
    leaving.get(edge.source).push(edge.id);
    if (!entering.has(edge.target)) entering.set(edge.target, []);
    entering.get(edge.target).push(edge.id);
  }
  const stubFor = (list, id, reversed) => {
    const rank = list.indexOf(id);
    const position = reversed ? list.length - 1 - rank : rank;
    return BACK_STUB_BASE + position * BACK_STUB_STEP;
  };
  return ordered.map(edge => {
    const trackY = bandBottom + BACK_TRACK_OFFSET + track.get(edge.id) * BACK_TRACK_GAP;
    const sourceBox = boxes.get(edge.source);
    const targetBox = boxes.get(edge.target);
    const sourceExtent = layerExtents[layerOf.get(edge.source)];
    const targetExtent = layerExtents[layerOf.get(edge.target)];
    if (!takenEast.has(edge.source)) takenEast.set(edge.source, []);
    if (!takenWest.has(edge.target)) takenWest.set(edge.target, []);
    const start = { x: sourceBox.x + sourceBox.width / 2, y: freePort(sourceBox, eastPorts.get(edge.source) || [], takenEast.get(edge.source)) };
    const end = { x: targetBox.x - targetBox.width / 2, y: freePort(targetBox, westPorts.get(edge.target) || [], takenWest.get(edge.target)) };
    const x1 = sourceExtent.right + stubFor(leaving.get(edge.source), edge.id, false);
    const x2 = targetExtent.left - stubFor(entering.get(edge.target), edge.id, true);
    const polyline = dedupe([
      start,
      { x: x1, y: start.y },
      { x: x1, y: trackY },
      { x: x2, y: trackY },
      { x: x2, y: end.y },
      end,
    ]);
    return makeRoute(edge.id, edge.source, edge.target, polyline, boxes, 'round-segments', BACK_EDGE_RADIUS, 'back');
  });
}

/**
 * Turn an ELK result into node centres, per-layer columns, and one route per edge.
 *
 * <p>`boxes` carry each node's body and its label extent so both the routing of back edges and
 * the metrics used to judge the drawing see the same geometry the editor will paint.</p>
 */
export function layeredDrawingFromResult(result, inputs, mode) {
  const spec = LAYERED_MODES[mode];
  if (!spec) throw new TypeError(`Unknown layered drawing mode: ${mode}`);
  const inputById = new Map((inputs?.nodes || []).map(node => [String(node.id), node]));
  const boxes = new Map();
  for (const child of result?.children || []) {
    const label = labelOf(inputById.get(child.id));
    const width = number(child.width);
    const height = number(child.height);
    const x = number(child.x) + width / 2;
    const y = number(child.y) + height / 2;
    const labelWidth = label ? label.width : 0;
    const labelHeight = label ? label.height : 0;
    boxes.set(child.id, Object.freeze({
      id: child.id, x, y, width, height, labelWidth, labelHeight,
      left: Math.min(x - width / 2, x - labelWidth / 2),
      right: Math.max(x + width / 2, x + labelWidth / 2),
      top: y - height / 2,
      bottom: y + height / 2 + labelHeight,
    }));
  }
  const columns = clusterColumns([...boxes.values()].map(box => box.x));
  const layerOf = new Map();
  boxes.forEach(box => layerOf.set(box.id, columns.findIndex(column => Math.abs(column - box.x) <= COLUMN_TOLERANCE)));
  const layerExtents = columns.map((x, index) => {
    let left = Infinity;
    let right = -Infinity;
    boxes.forEach(box => {
      if (layerOf.get(box.id) !== index) return;
      left = Math.min(left, box.left);
      right = Math.max(right, box.right);
    });
    return { x, left, right };
  });
  let bandTop = Infinity;
  let bandBottom = -Infinity;
  boxes.forEach(box => {
    bandTop = Math.min(bandTop, box.top);
    bandBottom = Math.max(bandBottom, box.bottom);
  });

  const routes = new Map();
  const backEdges = [];
  for (const edge of result?.edges || []) {
    const source = edge.sources?.[0];
    const target = edge.targets?.[0];
    const section = edge.sections?.[0];
    if (!section || !boxes.has(source) || !boxes.has(target)) continue;
    if (layerOf.get(target) <= layerOf.get(source)) {
      backEdges.push({ id: edge.id, source, target });
      continue;
    }
    const polyline = sectionPolyline(section);
    routes.set(edge.id, makeRoute(edge.id, source, target, polyline, boxes, spec.family, spec.radius, 'forward'));
  }
  const forwardRoutes = [...routes.values()];
  for (const route of routeBackEdges(backEdges, boxes, layerOf, layerExtents, bandBottom, forwardRoutes)) {
    routes.set(route.id, route);
  }

  return {
    mode,
    positions: [...boxes.values()].map(box => ({ id: box.id, x: box.x, y: box.y })),
    boxes,
    routes,
    layers: layerOf,
    columns,
    backEdges: backEdges.map(edge => edge.id),
    band: { top: bandTop, bottom: bandBottom },
  };
}

let sharedEngine = null;

/** One ELK instance per page; the engine is stateless between `layout` calls. */
export function layeredEngine() {
  if (!sharedEngine) sharedEngine = new ELK();
  return sharedEngine;
}

/**
 * Compute one drawing. `inputs.nodes` are `{ id, width, height, kind, label: { width, height } }`
 * with sizes in model pixels; `inputs.edges` are `{ id, source, target }`.
 */
export async function computeLayeredDrawing(inputs, mode, { elk = layeredEngine(), now = defaultNow } = {}) {
  const started = now();
  const result = await elk.layout(buildLayeredElkGraph(inputs, mode));
  const drawing = layeredDrawingFromResult(result, inputs, mode);
  drawing.elapsedMs = now() - started;
  return drawing;
}

function defaultNow() {
  return typeof performance !== 'undefined' && typeof performance.now === 'function' ? performance.now() : Date.now();
}
