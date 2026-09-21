import { DESIGN_ARRANGEMENTS } from './design-arrangements.js';
import { isLayeredMode } from './layered-drawing.js';
import { applyLayeredEdgeRoutes, layeredDrawingOf } from './layered-layout.js';
import { resolveRendererEdgeRoutes } from './renderer-edge-route.js';
import {
  applyViewerRoundedSegmentRoute,
  applyViewerSimpleRoute,
  applyViewerUnbundledRoute,
} from './viewer-edge-style.js';
import { resolveViewerRoutesWithinBudget } from './viewer-route-budget.js';

const LABEL_PLACEMENT_PROPERTIES = 'text-valign text-halign text-margin-x text-margin-y';
const BOTTOM_LABEL_STYLE = Object.freeze({
  'text-valign': 'bottom', 'text-halign': 'center', 'text-margin-x': 0, 'text-margin-y': 10,
});
const RIGHT_LABEL_STYLE = Object.freeze({
  'text-valign': 'center', 'text-halign': 'right', 'text-margin-x': 10, 'text-margin-y': 0,
});

function routeInputs(instance) {
  return {
    nodes: instance.nodes().map(node => ({
      id: node.id(), x: node.position('x'), y: node.position('y'),
      width: node.width(), height: node.height(),
    })),
    edges: instance.edges().map(edge => ({
      id: edge.id(), source: edge.source().id(), target: edge.target().id(),
      label: edge.data('label') || '',
    })),
  };
}

function applyLoop(edge) {
  edge.removeStyle('control-point-weights control-point-distances segment-weights segment-distances segment-radii');
  edge.style({
    'curve-style': 'bezier', 'loop-direction': '0deg', 'loop-sweep': '-80deg',
    'control-point-step-size': Math.max(88,
      Math.round(Math.max(edge.source().outerWidth(), edge.source().outerHeight()) * 0.85)),
    'source-endpoint': 'outside-to-node', 'target-endpoint': 'outside-to-node',
    'source-distance-from-node': 8, 'target-distance-from-node': 8, width: 2,
  });
}

function applyDynamicRoutes(instance, edges = instance.edges()) {
  const inputs = routeInputs(instance);
  const plan = resolveViewerRoutesWithinBudget(inputs);
  edges.forEach(edge => {
    if (edge.source().id() === edge.target().id()) return applyLoop(edge);
    if (plan.strategy === 'simple') return applyViewerSimpleRoute(edge);
    const route = plan.routes.get(edge.id());
    if (route) applyViewerUnbundledRoute(edge, route, { lineCap: 'round' });
  });
}

/** Paint label placement before layered measurement and preserve it with the published drawing. */
export function applyViewerDesignLabelSide(instance, side = 'bottom') {
  instance.nodes().removeStyle(LABEL_PLACEMENT_PROPERTIES);
  instance.nodes().style(side === 'right' ? RIGHT_LABEL_STYLE : BOTTOM_LABEL_STYLE);
}

/**
 * Applies the native Design route grammar for the persisted arrangement without moving or fitting.
 */
export function applyViewerDesignRoutes(instance, arrangement) {
  if (arrangement === 'hierarchical') {
    const routes = resolveRendererEdgeRoutes({ mode: 'hierarchical', ...routeInputs(instance) });
    instance.edges().forEach(edge => {
      if (edge.source().id() === edge.target().id()) return applyLoop(edge);
      const route = routes.get(edge.id());
      if (route) applyViewerRoundedSegmentRoute(edge, route);
    });
    return;
  }
  if (isLayeredMode(arrangement)) {
    applyViewerDesignLabelSide(instance, arrangement === 'layered-down' ? 'right' : 'bottom');
    const drawing = layeredDrawingOf(instance);
    if (!drawing) return applyDynamicRoutes(instance);
    const stale = applyLayeredEdgeRoutes(instance, drawing, { loop: applyLoop });
    if (stale.length) applyDynamicRoutes(instance, stale);
    return;
  }
  applyViewerDesignLabelSide(instance, 'bottom');
  applyDynamicRoutes(instance);
}

/** Maps the persisted Design arrangement vocabulary to the editor's layout engines. */
export function viewerDesignLayoutOptions(arrangement, { prepareLabels } = {}) {
  if (arrangement != null && !Object.hasOwn(DESIGN_ARRANGEMENTS, arrangement)) {
    throw new TypeError('Unsupported Design arrangement.');
  }
  if (arrangement === 'keep') return { name: 'preset', fit: false };
  if (arrangement === 'flow') return { name: 'dagre', rankDir: 'LR', fit: false, animate: false };
  if (arrangement === 'organic') return { name: 'cose', fit: false, animate: false };
  if (arrangement === 'hierarchical-new' || arrangement === 'layered-down') {
    return { name: 'rr-layered', mode: arrangement, fit: false, animate: false, prepareLabels };
  }
  return {
    name: 'elk', fit: false, animate: false,
    elk: { algorithm: 'layered', 'elk.direction': 'RIGHT' },
  };
}
