/**
 * The Cytoscape side of the layered drawings: a layout extension that measures the rendered
 * nodes and labels, asks `layered-drawing.js` for positions and routes, animates the positions
 * through Cytoscape's own layout lifecycle, and keeps the routes on the instance so the editor
 * can paint them after `layoutstop` and again whenever it needs to repaint edges.
 *
 * <p>Edge routes are absolute polylines. They are applied as `round-segments` (or `segments`)
 * relative to the two endpoints, so a route stays attached to its nodes; when a node is later
 * moved by hand the route no longer describes the drawing, and the editor is told which edges
 * are stale so it can fall back to its dynamic routing for those alone.</p>
 */
import { computeLayeredDrawing, isLayeredMode } from './layered-drawing.js';
import { viewerControlPointStyle } from './viewer-edge-style.js';

export const LAYERED_LAYOUT_NAME = 'rr-layered';
const DRAWING_SCRATCH = '_rrLayeredDrawing';
const POSITION_TOLERANCE = 0.5;

/** Node body plus rendered label extent, in model pixels, as the drawing needs them. */
export function measureLayeredNode(node) {
  const position = node.position();
  const width = node.outerWidth();
  const height = node.outerHeight();
  const labelled = node.boundingBox({ includeLabels: true, includeOverlays: false, includeEdges: false });
  const labelHeight = Math.max(0, labelled.y2 - (position.y + height / 2));
  const labelWidth = Math.max(0, labelled.w);
  const text = String(node.data('name') || node.data('label') || '');
  return {
    id: node.id(),
    width,
    height,
    kind: node.data('kind'),
    label: text && labelHeight > 0 ? { text, width: labelWidth, height: labelHeight } : null,
  };
}

export function layeredDrawingInputs(nodes, edges) {
  return {
    nodes: nodes.map(measureLayeredNode),
    edges: edges.map(edge => ({ id: edge.id(), source: edge.source().id(), target: edge.target().id() })),
  };
}

// A plain constructor: Cytoscape's extension wrapper invokes the registrant with `call`, which a
// native class would refuse.
function LayeredLayout(options) {
  this.options = options;
  this.cancelled = false;
}

LayeredLayout.prototype.run = function run() {
  const { cy, eles, mode } = this.options;
  if (!isLayeredMode(mode)) throw new TypeError(`Unknown layered drawing mode: ${mode}`);
  const nodes = eles.nodes().filter(node => !node.isParent());
  const edges = eles.edges();
  const stillCurrent = () => !this.cancelled && !cy.destroyed()
    && (typeof this.options.isCurrent !== 'function' || this.options.isCurrent());
  const settleWithoutMoving = () => {
    this.emit('layoutready');
    this.emit('layoutstop');
  };
  computeLayeredDrawing(layeredDrawingInputs(nodes, edges), mode, this.options.engine ? { elk: this.options.engine } : {})
    .then(drawing => {
      if (!stillCurrent()) {
        // A superseded request must still settle so the caller can release its slot, but it has
        // no business moving nodes the newer request is about to place.
        if (!cy.destroyed()) settleWithoutMoving();
        return;
      }
      cy.scratch(DRAWING_SCRATCH, drawing);
      const positions = new Map(drawing.positions.map(entry => [entry.id, entry]));
      nodes.layoutPositions(this, this.options, node => {
        const target = positions.get(node.id());
        return target ? { x: target.x, y: target.y } : node.position();
      });
    })
    .catch(error => {
      if (cy.destroyed()) return;
      cy.removeScratch(DRAWING_SCRATCH);
      if (typeof this.options.onError === 'function') this.options.onError(error);
      settleWithoutMoving();
    });
  return this;
};

LayeredLayout.prototype.stop = function stop() {
  this.cancelled = true;
  return this;
};

export function registerLayeredLayout(cytoscape) {
  cytoscape('layout', LAYERED_LAYOUT_NAME, LayeredLayout);
}

/** The drawing the last successful layered arrangement left on this instance, if any. */
export function layeredDrawingOf(cy) {
  const drawing = cy?.scratch(DRAWING_SCRATCH);
  return drawing && drawing.routes instanceof Map ? drawing : null;
}

export function clearLayeredDrawing(cy) {
  cy?.removeScratch(DRAWING_SCRATCH);
}

function styleForRoute(route, width) {
  if (route.family === 'straight') {
    return {
      'curve-style': 'straight',
      'edge-distances': 'endpoints',
      'source-endpoint': route.sourceEndpoint,
      'target-endpoint': route.targetEndpoint,
      'line-cap': 'round',
      width,
    };
  }
  const control = viewerControlPointStyle(route);
  return {
    'curve-style': route.family,
    'segment-weights': control.map(point => point.weight),
    'segment-distances': control.map(point => point.distance),
    'segment-radii': [route.radius || 0],
    'radius-type': 'arc-radius',
    'edge-distances': 'endpoints',
    'source-endpoint': route.sourceEndpoint,
    'target-endpoint': route.targetEndpoint,
    'line-cap': 'round',
    width,
  };
}

export function applyLayeredRoute(edge, route, { width = 2.5 } = {}) {
  edge.removeStyle('control-point-weights control-point-distances');
  edge.style(styleForRoute(route, width));
}

function nodeAtDrawingPosition(node, drawing) {
  const box = drawing.boxes?.get(node.id());
  if (!box) return false;
  const position = node.position();
  return Math.abs(position.x - box.x) <= POSITION_TOLERANCE && Math.abs(position.y - box.y) <= POSITION_TOLERANCE;
}

/**
 * Paint every edge whose endpoints are still where the drawing put them. Self-loops are handed
 * to `loop`; edges the drawing does not describe — new, or with a moved endpoint — are returned
 * so the caller can route them dynamically.
 */
export function applyLayeredEdgeRoutes(cy, drawing, { loop = null, width = 2.5 } = {}) {
  const stale = [];
  cy.edges().forEach(edge => {
    if (edge.source().id() === edge.target().id()) {
      if (typeof loop === 'function') loop(edge);
      return;
    }
    const route = drawing.routes.get(edge.id());
    const attached = route && route.source === edge.source().id() && route.target === edge.target().id()
      && nodeAtDrawingPosition(edge.source(), drawing) && nodeAtDrawingPosition(edge.target(), drawing);
    if (attached) applyLayeredRoute(edge, route, { width });
    else stale.push(edge);
  });
  return stale;
}
