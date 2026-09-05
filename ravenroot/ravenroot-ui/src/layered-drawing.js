/**
 * Layered graph drawing for the design editor's additive `Hierarchical (new)` and
 * `Layered (top-down)` arrangements.
 *
 * <p>The existing arrangements hand node positions to Cytoscape and then re-route every edge from
 * its endpoints alone, one edge at a time. This module treats placement and routing as one
 * problem: it asks ELK's layered algorithm for both the node coordinates and the edge sections,
 * with node labels declared as outside labels so the spacing reserves room for them, and then
 * converts the sections into absolute polylines Cytoscape can draw. Backward edges — those whose
 * target sits on the same or an earlier layer than their source — are not drawn through the
 * channels between layers; they are routed in dedicated tracks beyond the band of node rows, so
 * they never cut across the main flow.</p>
 *
 * <p>The two arrangements differ in the axis they flow along, so every formula below is written
 * once against a `main` axis — the one layers advance along — and a `cross` axis — the one a
 * single layer spreads over. The label side follows the axis rather than being a taste: a label
 * under the node card sits in the channel a top-down drawing routes through, so the top-down
 * arrangement carries its labels beside the card instead. The caller paints them there.</p>
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
const LAYER_TOLERANCE = 2;

/**
 * The two drawing axes. `main` is the direction of flow — layers advance along it — and `cross` is
 * the axis one layer spreads over. `bodyMain`/`bodyCross` read a node's own size along each, and
 * `at` builds a point from a pair of axis coordinates, so the geometry below never names `x` or
 * `y` directly and reads identically in either direction.
 */
const AXES = Object.freeze({
  RIGHT: Object.freeze({
    main: 'x', cross: 'y',
    mainStart: 'left', mainEnd: 'right', crossStart: 'top', crossEnd: 'bottom',
    bodyMain: box => box.width, bodyCross: box => box.height,
    at: (main, cross) => ({ x: main, y: cross }),
  }),
  DOWN: Object.freeze({
    main: 'y', cross: 'x',
    mainStart: 'top', mainEnd: 'bottom', crossStart: 'left', crossEnd: 'right',
    bodyMain: box => box.height, bodyCross: box => box.width,
    at: (main, cross) => ({ x: cross, y: main }),
  }),
});

// Where the caller paints a node's label, and therefore where this module must reserve room for
// it. `bottom` is the design editor's own placement; `right` is what a top-down drawing needs,
// because a label under the card would sit in the channel the drawing routes through.
const LABEL_PLACEMENTS = Object.freeze({
  bottom: '[H_CENTER, V_BOTTOM, OUTSIDE]',
  right: '[H_RIGHT, V_CENTER, OUTSIDE]',
});

export const LAYERED_MODES = Object.freeze({
  'hierarchical-new': Object.freeze({
    direction: 'RIGHT', labelSide: 'bottom',
    routing: 'ORTHOGONAL', family: 'round-segments', radius: 12,
    nodeNode: 44, betweenLayers: 96, edgeEdge: 14, edgeNode: 26,
  }),
  'layered-down': Object.freeze({
    direction: 'DOWN', labelSide: 'right',
    // Layers are rows here, so `nodeNode` separates a node from its neighbour's label along the
    // row and has to clear the widest name; `betweenLayers` is the vertical channel, which no
    // longer carries labels and is therefore tighter than the left-to-right drawing's.
    routing: 'ORTHOGONAL', family: 'round-segments', radius: 12,
    nodeNode: 56, betweenLayers: 72, edgeEdge: 14, edgeNode: 26,
  }),
});

export const LAYERED_MODE_NAMES = Object.freeze(Object.keys(LAYERED_MODES));

export function isLayeredMode(mode) {
  return Object.prototype.hasOwnProperty.call(LAYERED_MODES, String(mode));
}

/** The side of the node card the caller must paint labels on for this mode: `bottom` or `right`. */
export function layeredLabelSide(mode) {
  return LAYERED_MODES[mode]?.labelSide || 'bottom';
}

function axisOf(spec) {
  return AXES[spec.direction];
}

export function layeredElkOptions(mode) {
  const spec = LAYERED_MODES[mode];
  if (!spec) throw new TypeError(`Unknown layered drawing mode: ${mode}`);
  return {
    'elk.algorithm': 'layered',
    'elk.direction': spec.direction,
    'elk.edgeRouting': spec.routing,
    // Depth-first cycle breaking from the sources reverses the edges that actually run backwards
    // in a workflow (retry, rework, no-issue) instead of whichever edges a greedy pass finds
    // first, which is what left a START node in a middle layer.
    'elk.layered.cycleBreaking.strategy': 'DEPTH_FIRST',
    'elk.layered.layering.strategy': 'NETWORK_SIMPLEX',
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.layered.thoroughness': '30',
    // Brandes-Koepf for both modes: network-simplex placement drew the same crossings on the
    // test bench and cost six times the engine time on a 200-node, 400-edge graph.
    'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
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

/** The ELK input graph for one drawing mode: node bodies with outside labels on the mode's side. */
export function buildLayeredElkGraph(inputs, mode) {
  const spec = LAYERED_MODES[mode];
  if (!spec) throw new TypeError(`Unknown layered drawing mode: ${mode}`);
  const children = (inputs?.nodes || []).map(node => {
    const label = labelOf(node);
    const layoutOptions = {
      // Every node of a layer shares one centre line, so the drawing reads as discrete levels
      // even when node bodies differ in size.
      'elk.alignment': 'CENTER',
      // Declared as an outside label, the label becomes a node margin: spacing and routing keep
      // clear of it while edges still attach to the node body.
      'elk.nodeLabels.placement': LABEL_PLACEMENTS[spec.labelSide],
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

function clusterLayers(coordinates) {
  const layers = [];
  for (const at of [...coordinates].sort((a, b) => a - b)) {
    const last = layers[layers.length - 1];
    if (last != null && Math.abs(at - last) <= LAYER_TOLERANCE) continue;
    layers.push(at);
  }
  return layers;
}

/**
 * Backward edges leave the source's outgoing side from a port of their own, step out of the band
 * of node rows into a track of their own, run back outside everything, and return into a port of
 * their own on the target's incoming side. Shorter spans take the tracks nearest the band so
 * nested back edges never cross each other; the little stubs that carry an edge from its port to
 * its track are ordered so two back edges sharing a node do not cross either. The stubs sit
 * inside the channel next to the layer's extent, closer to the layer than anything ELK draws
 * there, so they cross no label and overlap no ELK segment.
 */
const PORT_STEP = 6;
const PORT_INSET = 8;
const PORT_CLEARANCE = 4;

// The next port slot on one node side, counted inward from the far end of the body, that no
// forward edge already uses there; back edges of the same node take successive slots.
function freePort(box, axis, used, taken) {
  const centre = box[axis.cross];
  const half = axis.bodyCross(box) / 2;
  for (let slot = 0; slot < 64; slot++) {
    const at = centre + half - PORT_INSET - slot * PORT_STEP;
    if (at < centre - half + PORT_INSET) break;
    if ([...used, ...taken].some(other => Math.abs(other - at) < PORT_CLEARANCE)) continue;
    taken.push(at);
    return at;
  }
  taken.push(centre);
  return centre;
}

function routeBackEdges(backEdges, boxes, layerOf, layerExtents, bandEnd, forwardRoutes, spec) {
  const axis = axisOf(spec);
  // The stubs of one node must all fit inside its own half of the channel; with many back edges
  // on one node they close up rather than reach into the neighbouring layer's extent.
  const maxStub = Math.max(BACK_STUB_BASE, Math.floor(spec.betweenLayers / 2) - 6);
  const outgoingPorts = new Map();
  const incomingPorts = new Map();
  for (const route of forwardRoutes) {
    if (!outgoingPorts.has(route.source)) outgoingPorts.set(route.source, []);
    outgoingPorts.get(route.source).push(route.start[axis.cross]);
    if (!incomingPorts.has(route.target)) incomingPorts.set(route.target, []);
    incomingPorts.get(route.target).push(route.end[axis.cross]);
  }
  const takenOutgoing = new Map();
  const takenIncoming = new Map();
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
    const step = Math.min(BACK_STUB_STEP, (maxStub - BACK_STUB_BASE) / Math.max(1, list.length - 1));
    return BACK_STUB_BASE + position * step;
  };
  return ordered.map(edge => {
    const trackAt = bandEnd + BACK_TRACK_OFFSET + track.get(edge.id) * BACK_TRACK_GAP;
    const sourceBox = boxes.get(edge.source);
    const targetBox = boxes.get(edge.target);
    const sourceExtent = layerExtents[layerOf.get(edge.source)];
    const targetExtent = layerExtents[layerOf.get(edge.target)];
    if (!takenOutgoing.has(edge.source)) takenOutgoing.set(edge.source, []);
    if (!takenIncoming.has(edge.target)) takenIncoming.set(edge.target, []);
    const startCross = freePort(sourceBox, axis, outgoingPorts.get(edge.source) || [], takenOutgoing.get(edge.source));
    const endCross = freePort(targetBox, axis, incomingPorts.get(edge.target) || [], takenIncoming.get(edge.target));
    const start = axis.at(sourceBox[axis.main] + axis.bodyMain(sourceBox) / 2, startCross);
    const end = axis.at(targetBox[axis.main] - axis.bodyMain(targetBox) / 2, endCross);
    const outward = sourceExtent.end + stubFor(leaving.get(edge.source), edge.id, false);
    const inward = targetExtent.start - stubFor(entering.get(edge.target), edge.id, true);
    const polyline = dedupe([
      start,
      axis.at(outward, startCross),
      axis.at(outward, trackAt),
      axis.at(inward, trackAt),
      axis.at(inward, endCross),
      end,
    ]);
    return makeRoute(edge.id, edge.source, edge.target, polyline, boxes, 'round-segments', BACK_EDGE_RADIUS, 'back');
  });
}

// A node's drawn extent: the body, plus the label on the side this mode paints it. The label of a
// left-to-right drawing hangs under the card and widens the node; the label of a top-down drawing
// sits beside it and lengthens the node along the row.
function boxOfChild(child, label, labelSide) {
  const width = number(child.width);
  const height = number(child.height);
  const x = number(child.x) + width / 2;
  const y = number(child.y) + height / 2;
  const labelWidth = label ? label.width : 0;
  const labelHeight = label ? label.height : 0;
  const beside = labelSide === 'right';
  return Object.freeze({
    id: child.id, x, y, width, height, labelWidth, labelHeight, labelSide,
    left: beside ? x - width / 2 : Math.min(x - width / 2, x - labelWidth / 2),
    right: beside ? x + width / 2 + labelWidth : Math.max(x + width / 2, x + labelWidth / 2),
    top: beside ? Math.min(y - height / 2, y - labelHeight / 2) : y - height / 2,
    bottom: beside ? Math.max(y + height / 2, y + labelHeight / 2) : y + height / 2 + labelHeight,
  });
}

/**
 * Turn an ELK result into node centres, per-layer coordinates, and one route per edge.
 *
 * <p>`boxes` carry each node's body and its label extent so both the routing of back edges and
 * the metrics used to judge the drawing see the same geometry the editor will paint.</p>
 */
export function layeredDrawingFromResult(result, inputs, mode) {
  const spec = LAYERED_MODES[mode];
  if (!spec) throw new TypeError(`Unknown layered drawing mode: ${mode}`);
  const axis = axisOf(spec);
  const inputById = new Map((inputs?.nodes || []).map(node => [String(node.id), node]));
  const boxes = new Map();
  for (const child of result?.children || []) {
    boxes.set(child.id, boxOfChild(child, labelOf(inputById.get(child.id)), spec.labelSide));
  }
  const columns = clusterLayers([...boxes.values()].map(box => box[axis.main]));
  const layerOf = new Map();
  boxes.forEach(box => layerOf.set(box.id,
    columns.findIndex(column => Math.abs(column - box[axis.main]) <= LAYER_TOLERANCE)));
  // The extent of a layer along the axis of flow: where a back edge's stub may sit without
  // touching the layer it is leaving or the one it is returning to.
  const layerExtents = columns.map((at, index) => {
    let start = Infinity;
    let end = -Infinity;
    boxes.forEach(box => {
      if (layerOf.get(box.id) !== index) return;
      start = Math.min(start, box[axis.mainStart]);
      end = Math.max(end, box[axis.mainEnd]);
    });
    return { at, start, end };
  });
  // The band is the extent of the whole drawing across the flow. Back-edge tracks are laid beyond
  // its far side, which is what keeps them out of the drawing.
  let bandStart = Infinity;
  let bandEnd = -Infinity;
  boxes.forEach(box => {
    bandStart = Math.min(bandStart, box[axis.crossStart]);
    bandEnd = Math.max(bandEnd, box[axis.crossEnd]);
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
  for (const route of routeBackEdges(backEdges, boxes, layerOf, layerExtents, bandEnd, forwardRoutes, spec)) {
    routes.set(route.id, route);
  }

  return {
    mode,
    direction: spec.direction,
    labelSide: spec.labelSide,
    positions: [...boxes.values()].map(box => ({ id: box.id, x: box.x, y: box.y })),
    boxes,
    routes,
    layers: layerOf,
    columns,
    backEdges: backEdges.map(edge => edge.id),
    // Across the flow: `y` for a left-to-right drawing, `x` for a top-down one.
    band: { axis: axis.cross, start: bandStart, end: bandEnd },
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
