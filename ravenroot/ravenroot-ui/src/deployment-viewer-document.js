import { viewerNodeType } from './viewer-presentation.js';

const KINDS = new Set(['START', 'PASSTHROUGH', 'BEHAVIOR', 'END', 'ERROR']);

function required(value, field) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`Invalid deployment viewer projection: ${field}`);
  }
  return value;
}

/**
 * Builds an editor-compatible but structurally non-editable document from the same safe projection
 * consumed by the embed viewer. No GraphML, property bag, payload, or secret enters this record.
 */
export function deploymentProjectionDocument(envelope) {
  if (envelope?.viewerSourceVersion !== '1' || envelope?.source?.kind !== 'deployment') {
    throw new TypeError('Invalid deployment viewer source.');
  }
  const projection = envelope.projection;
  if (projection?.viewerContractVersion !== '1.0'
      || !Array.isArray(projection.nodes) || !Array.isArray(projection.edges)) {
    throw new TypeError('Invalid deployment viewer projection.');
  }
  const nodeIds = new Set();
  const nodes = projection.nodes.map(node => {
    const id = required(node?.id, 'node.id');
    const kind = required(node?.kind, 'node.kind');
    if (!KINDS.has(kind) || nodeIds.has(id)) throw new TypeError('Invalid deployment viewer node.');
    nodeIds.add(id);
    const layout = node.layout || {};
    return {
      id, name: typeof node.label === 'string' && node.label ? node.label : id,
      kind, nodeType: viewerNodeType(node), behavior: '',
      classname: '', description: '', fillColor: '', instances: null,
      ox: Number.isFinite(layout.x) ? layout.x : 0,
      oy: Number.isFinite(layout.y) ? layout.y : 0,
      ow: Number.isFinite(layout.width) ? layout.width : 100,
      oh: Number.isFinite(layout.height) ? layout.height : 56,
      _positionIsCenter: true,
      properties: node.bypassed ? { 'execution.bypass': 'true' } : {}, propertyTypes: {},
      isStart: kind === 'START', isEnd: kind === 'END', bypassed: Boolean(node.bypassed),
    };
  });
  const edges = projection.edges.map((edge, index) => {
    const source = required(edge?.source, 'edge.source');
    const target = required(edge?.target, 'edge.target');
    if (!nodeIds.has(source) || !nodeIds.has(target)) {
      throw new TypeError('Invalid deployment viewer topology.');
    }
    const id = typeof edge.id === 'string' && edge.id ? edge.id : `viewer-edge-${index}`;
    const label = typeof edge.label === 'string' ? edge.label : '';
    return {
      id, source, target, label, outcome: label || 'continue',
      edgeType: edge.visualType || 'continue', status: '', parallel: 0,
      edgeName: '', color: '', lineWidth: null, trafficWeight: null,
      description: '', failureRouteKind: '', properties: {}, propertyTypes: {},
    };
  });
  return {
    format: 'deployment',
    viewerBinding: Object.freeze({
      deploymentId: required(envelope.source.deploymentId, 'source.deploymentId'),
      graphVersion: required(envelope.source.graphVersion, 'source.graphVersion'),
      incarnationId: required(envelope.source.incarnationId, 'source.incarnationId'),
    }),
    lifecycle: envelope.lifecycle,
    nodes, edges,
    nodeMap: Object.fromEntries(nodes.map(node => [node.id, node])),
    edgeMap: Object.fromEntries(edges.map(edge => [edge.id, edge])),
    graphProperties: {}, keyDefinitions: [],
  };
}
