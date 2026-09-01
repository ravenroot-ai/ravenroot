export function graphHasPersistedLayout(graph) {
  return Boolean(graph?.nodes?.length
    && graph.nodes.every(node => node._positionIsCenter
      && Number.isFinite(Number(node.ox))
      && Number.isFinite(Number(node.oy))));
}

export function initialLayoutForGraph(graph) {
  return graphHasPersistedLayout(graph) ? 'preset' : 'n8n';
}

export const VISUAL_STYLES = Object.freeze(new Set(['standard', 'n8n', 'n8n2', 'n8n3', 'n8n4', 'cyto']));
export const DEFAULT_VISUAL_STYLE = 'cyto';
export const DESIGN_RENDER_MODE = 'design';
export const MONITORING_RENDER_MODE = 'monitoring';
export const DEFAULT_RENDER_MODE = DESIGN_RENDER_MODE;
export const RENDER_MODES = Object.freeze(new Set([DESIGN_RENDER_MODE, MONITORING_RENDER_MODE]));

// Render mode is the product contract. Algorithm names remain internal implementation details and
// every historical finite layout/style value converges on Design; the old separate renderer value
// is the sole legacy spelling of Monitoring.
export function normalizeRenderMode(renderMode) {
  return renderMode === MONITORING_RENDER_MODE || renderMode === 'elastic'
    ? MONITORING_RENDER_MODE : DESIGN_RENDER_MODE;
}

export function renderModePresentation(renderMode) {
  const normalized = normalizeRenderMode(renderMode);
  return normalized === MONITORING_RENDER_MODE
    ? { renderMode: normalized, layoutMode: 'elastic', visualStyle: DEFAULT_VISUAL_STYLE }
    : { renderMode: normalized, layoutMode: 'cyto', visualStyle: DEFAULT_VISUAL_STYLE };
}

export function normalizeVisualStyle(visualStyle) {
  return VISUAL_STYLES.has(visualStyle) ? visualStyle : DEFAULT_VISUAL_STYLE;
}

// Before the single `layoutMode` field carried the complete render choice. The N8N/Cyto values
// all ran ELK and then painted a different renderer style. Keep accepting those records through the
// compatibility split even though exposes one complete render-mode choice again.
export function visualStyleFromLegacyLayout(layoutMode) {
  return normalizeVisualStyle(layoutMode);
}

export function layoutFromLegacyMode(layoutMode) {
  if (['n8n', 'n8n2', 'n8n3', 'n8n4', 'cyto'].includes(layoutMode)) return 'elk';
  if (layoutMode === 'elastic') return 'preset';
  return layoutMode || 'preset';
}

export function documentPresentationState(document_) {
  const storedMode = document_ && Object.hasOwn(document_, 'renderMode')
    ? document_.renderMode : document_?.layoutMode;
  return renderModePresentation(storedMode);
}

export function graphLayoutPlan(graph, options = {}) {
  return {
    name: options.layoutName || initialLayoutForGraph(graph),
    preservePositions: Boolean(options.preservePositions),
  };
}

// Not every value `layoutMode` can hold is a Cytoscape layout algorithm. `elastic` selects a
// DIFFERENT RENDERER KIND — its own DOM host, D3 simulation and generation-scoped lifecycle
// (`renderer-session.js`) — rather than a way to position nodes inside the Cytoscape renderer.
// The distinction matters exactly once: when a layout is carried from one document view to
// another. A layout algorithm is a preference and travels; a renderer kind is a property of the
// view that was torn down and must not be re-instantiated for a graph that never asked for it.
export const RENDERER_KIND_LAYOUT_MODES = Object.freeze(new Set(['elastic']));

export function isRendererKindLayout(layoutName) {
  return RENDERER_KIND_LAYOUT_MODES.has(layoutName);
}

// The layout a freshly-loaded view may inherit from the previous one, or `null` when the previous
// selection was a renderer switch rather than a layout choice.
export function retainableLayout(currentLayout) {
  return isRendererKindLayout(currentLayout) ? null : (currentLayout || null);
}

// Loading is different from asking for a layout. Persisted coordinates belong to the document;
// the currently selected LAYOUT belongs to the user's view. Combine them without running the
// renderer's positioning engine, so opening a file changes paint but not placement.
//
// A renderer-kind selection is deliberately NOT retained. Carrying `elastic` through here
// made `initLoadedGraph` run `setLayout('elastic')` against the brand-new post-replace `cy`, which
// built a live Elastic renderer for the replacement and defeated the teardown the replace had just
// performed. Such a view falls back to the graph's own persisted/default layout.
export function loadedGraphLayoutPlan(graph, currentLayout) {
  return graphLayoutPlan(graph, {
    layoutName: retainableLayout(currentLayout) || initialLayoutForGraph(graph),
    preservePositions: graphHasPersistedLayout(graph),
  });
}

export function syncGraphPositionsFromCy(graph, cy) {
  if (!graph || graph.format === 'graphify' || !cy) return;
  cy.nodes().forEach(node => {
    const model = graph.nodeMap[node.id()];
    if (!model) return;
    model.ox = node.position('x');
    model.oy = node.position('y');
    model.ow = Number(node.data('nw')) || model.ow;
    model.oh = Number(node.data('nh')) || model.oh;
    model._positionIsCenter = true;
  });
}

export function renderGraphStatistics(container, totalNodes, totalEdges, nodeCounts = {}, edgeCounts = {}) {
  container.replaceChildren();
  appendStatistic(container, 'Nodes', totalNodes);
  appendStatistic(container, 'Edges', totalEdges);
  Object.entries(nodeCounts).forEach(([type, count]) => appendStatistic(container, type, count));
  const separator = document.createElement('div');
  separator.className = 'graph-stat-separator';
  separator.textContent = '──';
  container.append(separator);
  Object.entries(edgeCounts).forEach(([type, count]) => appendStatistic(container, type, count));
}

function appendStatistic(container, label, value) {
  const row = document.createElement('div');
  row.className = 'graph-stat-line';
  const name = document.createElement('span');
  name.textContent = `${String(label)}:`;
  const count = document.createElement('span');
  count.textContent = String(value);
  row.append(name, count);
  container.append(row);
}
