import { createReadOnlyRendererAdapter } from './viewer-renderer-adapter.js';
import { viewerProjectionElements } from './viewer-presentation.js';

const CONTRACT_VERSION = '1.0';
export const VIEWER_BUDGET = Object.freeze({ nodes: 5_000, edges: 10_000, renderMilliseconds: 5_000 });
const NODE_KINDS = new Set(['START', 'PASSTHROUGH', 'BEHAVIOR', 'END', 'ERROR']);
function requireText(value, field) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`Invalid viewer projection: ${field}.`);
  }
  return value;
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null;
}

function projectionNode(node) {
  const id = requireText(node?.id, 'node.id');
  const kind = requireText(node?.kind, 'node.kind');
  if (!NODE_KINDS.has(kind)) throw new TypeError('Invalid viewer projection: node.kind.');
  const layout = node?.layout == null ? null : Object.freeze({
    x: finiteOrNull(node.layout.x),
    y: finiteOrNull(node.layout.y),
    width: finiteOrNull(node.layout.width),
    height: finiteOrNull(node.layout.height),
  });
  if (layout && Object.values(layout).some(value => value == null)) {
    throw new TypeError('Invalid viewer projection: node.layout.');
  }
  const visualType = typeof node?.visualType === 'string' && node.visualType
    ? node.visualType : kind.toLowerCase();
  const label = typeof node?.label === 'string' && node.label ? node.label : id;
  return Object.freeze({ id, kind, visualType, label, bypassed: Boolean(node?.bypassed), layout });
}

function projectionEdge(edge) {
  return Object.freeze({
    id: typeof edge?.id === 'string' && edge.id ? edge.id : null,
    source: requireText(edge?.source, 'edge.source'),
    target: requireText(edge?.target, 'edge.target'),
    label: typeof edge?.label === 'string' ? edge.label : '',
    visualType: typeof edge?.visualType === 'string' && edge.visualType
      ? edge.visualType : 'continue',
  });
}

/**
 * Copy only the projection allowlist. Unknown fields never enter the
 * renderer snapshot, DOM, accessibility tree, or parent messaging surface.
 */
export function createViewerSnapshot(projection, budget = VIEWER_BUDGET) {
  if (projection?.viewerSourceVersion === '1' && projection.projection) {
    projection = projection.projection;
  }
  if (projection?.viewerContractVersion !== CONTRACT_VERSION) {
    throw new TypeError('Incompatible viewer projection.');
  }
  const nodes = Array.isArray(projection.nodes) ? projection.nodes.map(projectionNode) : null;
  const edges = Array.isArray(projection.edges) ? projection.edges.map(projectionEdge) : null;
  if (nodes == null || edges == null) throw new TypeError('Invalid viewer projection.');
  if (nodes.length > budget.nodes || edges.length > budget.edges) {
    throw new RangeError('Viewer projection exceeds the rendering budget.');
  }

  const nodeIds = new Set(nodes.map(node => node.id));
  if (nodeIds.size !== nodes.length
      || edges.some(edge => !nodeIds.has(edge.source) || !nodeIds.has(edge.target))) {
    throw new TypeError('Invalid viewer projection topology.');
  }

  const elements = viewerProjectionElements(nodes, edges).map(element => Object.freeze({
    data: Object.freeze(element.data),
    ...(element.position ? { position: Object.freeze(element.position) } : {}),
  }));

  return Object.freeze({
    viewerContractVersion: CONTRACT_VERSION,
    graphId: requireText(projection.graphId, 'graphId'),
    graphVersionId: requireText(projection.graphVersionId, 'graphVersionId'),
    canonicalDigest: requireText(projection.canonicalDigest, 'canonicalDigest'),
    nodes: Object.freeze(nodes),
    edges: Object.freeze(edges),
    elements: Object.freeze(elements),
  });
}

export function createReadOnlyViewerCore(renderer, budget = VIEWER_BUDGET) {
  const adapter = createReadOnlyRendererAdapter(renderer);
  let state = 'idle';
  let destroyed = false;
  let generation = 0;

  function available() {
    if (destroyed) throw new Error('Viewer is destroyed.');
  }

  return Object.freeze({
    get state() { return state; },
    async mount(projection) {
      available();
      const operation = ++generation;
      state = 'loading';
      try {
        const snapshot = createViewerSnapshot(projection, budget);
        let timeout;
        await Promise.race([
          adapter.render(snapshot),
          new Promise((resolve, reject) => {
            timeout = setTimeout(() => reject(new Error('Viewer render timed out.')),
              budget.renderMilliseconds);
          }),
        ]).finally(() => clearTimeout(timeout));
        if (destroyed || operation !== generation) throw new Error('Viewer render superseded.');
        state = snapshot.nodes.length === 0 ? 'empty' : 'ready';
        return snapshot;
      } catch (failure) {
        // A late renderer settlement belongs to the operation that was destroyed or superseded.
        // It must not overwrite the terminal state, nor the state owned by a newer mount.
        if (destroyed || operation !== generation) throw failure;
        state = failure instanceof TypeError && failure.message === 'Incompatible viewer projection.'
          ? 'incompatible'
          : 'error';
        throw failure;
      }
    },
    fit(padding = 60) { available(); return adapter.fit(padding); },
    zoomBy(factor) {
      available();
      if (!Number.isFinite(factor) || factor <= 0) throw new TypeError('Zoom factor must be positive.');
      return adapter.zoomBy(factor);
    },
    panBy(delta) { available(); return adapter.panBy(delta); },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      generation += 1;
      state = 'destroyed';
      adapter.destroy();
    },
  });
}
