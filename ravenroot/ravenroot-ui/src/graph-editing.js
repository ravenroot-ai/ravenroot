import {
  applyCommand,
  batchUpdateNodePropertiesCommand,
  compositeCommand,
  insertEdgesCommand,
  insertNodesCommand,
  moveNodesCommand,
  removeEdgesCommand,
  removeNodesCommand,
  updateEdgeCommand,
  updateGraphPropertiesCommand,
  updateNodeCommand,
} from './graph-commands.js';
import {
  JOIN_SEMANTICS_DECLARED,
  JOIN_SEMANTICS_PROPERTY,
  createEdge,
  createNode,
  planJoinSemanticsMigration,
} from './graph-document.js';
import { validateEdgeConnection, validateEdgeId } from './edge-gestures.js';
import { resolveDescriptorNodeType } from './catalog-node-icon.js';
import { catalogPropertyHasDeclaredDefault } from './adapter-binding.js';

export function canModifyGraph(graph, layoutMode = '') {
  return Boolean(graph && graph.format === 'graphml' && layoutMode !== 'elastic');
}

export function nextModifyState(current, graph, layoutMode = '') {
  return canModifyGraph(graph, layoutMode) ? !current : false;
}

export function uniqueElementId(prefix, elements = []) {
  const used = new Set(elements.map(element => element.id));
  let index = 1;
  while (used.has(`${prefix}-${index}`)) index += 1;
  return `${prefix}-${index}`;
}

export const DUPLICATE_NODE_OFFSET = 32;
const DUPLICABLE_NODE_KINDS = new Set(['PASSTHROUGH', 'BEHAVIOR']);

export function canDuplicateNode(graph, nodeId, layoutMode = '') {
  const node = graph?.nodeMap?.[nodeId];
  return Boolean(canModifyGraph(graph, layoutMode) && node && DUPLICABLE_NODE_KINDS.has(node.kind));
}

// Every editing entry point below routes through the command model (UI-01). `history` is
// optional so the document can still be edited without an undo stack (tests, headless callers);
// when it is supplied the same command becomes one undo step.
function run(graph, command, history, options = {}) {
  if (history) {
    history.execute(graph, command, options);
    return command;
  }
  applyCommand(graph, command);
  return command;
}

export function addNodeAt(graph, position, history = null, options = {}) {
  if (!canModifyGraph(graph)) return null;
  const node = createNodeForInsertion(graph, position, options);
  run(graph, insertNodesCommand([{ node, index: graph.nodes.length }], `Add node ${node.id}`), history);
  return node;
}

// Duplication is an authored document operation, not a renderer clone. Reconstructing the node
// explicitly keeps parser provenance (`_sourceCanonical`, `_legacyKind`) and runtime counters out
// of the new element, while preserving every field this editor can write back to GraphML. START,
// END and ERROR are excluded because copying any of them knowingly violates the graph's terminal
// cardinality. Edges are intentionally outside the command: one duplicate gesture inserts one node
// and therefore becomes exactly one undo/redo step.
export function duplicateNode(graph, sourceId, history = null, options = {}) {
  const layoutMode = options.layoutMode || '';
  if (!canDuplicateNode(graph, sourceId, layoutMode)) return null;
  const source = graph.nodeMap[sourceId];
  const offset = Number.isFinite(Number(options.offset))
    ? Number(options.offset) : DUPLICATE_NODE_OFFSET;
  const id = uniqueElementId(`${source.id}-copy`, graph.nodes);
  const node = createNode(id, source.name, source.kind, {
    x: Number(source.ox) + offset,
    y: Number(source.oy) + offset,
  });
  Object.assign(node, {
    behavior: source.kind === 'BEHAVIOR' ? String(source.behavior || '') : '',
    nodeType: String(source.nodeType || node.nodeType),
    classname: String(source.classname || ''),
    description: String(source.description || ''),
    ow: Number(source.ow) || node.ow,
    oh: Number(source.oh) || node.oh,
    properties: { ...(source.properties || {}) },
    propertyTypes: { ...(source.propertyTypes || {}) },
    _positionIsCenter: true,
  });
  run(graph, insertNodesCommand([{ node, index: graph.nodes.length }],
    `Duplicate ${source.id} as ${node.id}`), history);
  return node;
}

function createNodeForInsertion(graph, position, options = {}) {
  const descriptor = options.descriptor || null;
  const prefix = descriptor?.behavior?.replace(/[^a-zA-Z0-9_-]/g, '-') || 'node';
  const id = uniqueElementId(prefix, graph.nodes);
  const node = createNode(id, descriptor?.displayName || 'New node', descriptor ? 'BEHAVIOR' : 'PASSTHROUGH', position);
  if (descriptor) {
    node.behavior = String(descriptor.behavior || '');
    node.nodeType = resolveDescriptorNodeType(descriptor);
    // Unlike the property-editor form, there is no render step between this and the document —
    // the value goes straight into `node.properties` and from there into the GraphML this graph
    // serialises to. A whitespace-only defaultValue must therefore be judged "not declared" HERE, by
    // the same `catalogPropertyHasDeclaredDefault` the core validator and the editor's own render
    // agree on, not by a private `!== ''` that would silently seed the document with a value every
    // other reader of this catalog already treats as absent. This file must import the shared
    // predicate instead of keeping a copy that only happens to agree with it.
    node.properties = Object.fromEntries((descriptor.properties || [])
      .filter(catalogPropertyHasDeclaredDefault)
      .map(property => [property.name, property.defaultValue]));
    node.propertyTypes = Object.fromEntries((descriptor.properties || [])
      .filter(catalogPropertyHasDeclaredDefault)
      .map(property => [property.name, graphMlType(property.type)]));
  }
  return node;
}

// A toolbox drop on a source is one authoring gesture, so the node and its edge are planned and
// validated against a temporary view of the document before either is inserted. The composite is
// then one history entry: an invalid source cannot strand a node, while undo/redo always moves the
// valid pair together.
export function addConnectedNodeAt(graph, position, source, history = null, options = {}) {
  if (!canModifyGraph(graph)) return { node: null, edge: null, reason: 'This graph cannot be edited' };
  const node = createNodeForInsertion(graph, position, options);
  const proposal = {
    ...graph,
    nodes: [...graph.nodes, node],
    nodeMap: { ...graph.nodeMap, [node.id]: node },
  };
  const connection = validateEdgeConnection(proposal, { source, target: node.id });
  if (!connection.ok) return { node: null, edge: null, reason: connection.reason };

  const edgeId = options.edgeId || uniqueElementId('edge', graph.edges);
  const edgeIdResult = validateEdgeId(graph, edgeId);
  if (!edgeIdResult.ok) return { node: null, edge: null, reason: edgeIdResult.reason };
  const edge = createEdge(edgeId, source, node.id, options.outcome);
  const command = compositeCommand([
    insertNodesCommand([{ node, index: graph.nodes.length }]),
    insertEdgesCommand([{ edge, index: graph.edges.length }]),
  ], `Add node ${node.id} and connect ${source} → ${node.id}`);
  run(graph, command, history);
  return { node, edge, reason: '' };
}

function graphMlType(type) {
  const normalized = String(type || '').toUpperCase();
  if (normalized === 'INTEGER') return 'long';
  if (normalized === 'NUMBER' || normalized === 'DECIMAL') return 'double';
  if (normalized === 'BOOLEAN') return 'boolean';
  return 'string';
}

// Returns the new edge, or null when the connection is refused. Callers that need the reason for
// the refusal — every interactive one — should ask validateEdgeConnection first and show it;
// returning null keeps the old contract for callers that only care whether it happened.
export function connectNodes(graph, source, target, history = null, options = {}) {
  if (!canModifyGraph(graph)) return null;
  if (!validateEdgeConnection(graph, { source, target }).ok) return null;
  const id = options.id || uniqueElementId('edge', graph.edges);
  if (!validateEdgeId(graph, id).ok) return null;
  const edge = createEdge(id, source, target, options.outcome);
  run(graph, insertEdgesCommand([{ edge, index: graph.edges.length }], `Connect ${source} → ${target}`), history);
  return edge;
}

// Moving one end of an existing edge. This is an update, not a delete plus an insert: the edge keeps
// its id, its outcome and every additional property it carried, and the single update-edge command
// means one undo puts the endpoint back where it was.
export function reconnectEdge(graph, edgeId, endpoint, nodeId, history = null) {
  if (!canModifyGraph(graph)) return null;
  if (endpoint !== 'source' && endpoint !== 'target') return null;
  const edge = graph.edges.find(candidate => candidate.id === edgeId);
  if (!edge) return null;
  const proposal = endpoint === 'source'
    ? { source: nodeId, target: edge.target }
    : { source: edge.source, target: nodeId };
  if (!validateEdgeConnection(graph, { ...proposal, edgeId }).ok) return null;
  if (edge[endpoint] === nodeId) return null;
  return run(graph, updateEdgeCommand(edgeId, { [endpoint]: nodeId },
    `Reconnect ${edgeId} ${endpoint} to ${nodeId}`), history);
}

export function insertNodeElement(graph, node, history = null) {
  if (!canModifyGraph(graph) || !node || graph.nodeMap[node.id]) return null;
  run(graph, insertNodesCommand([{ node, index: graph.nodes.length }], `Add node ${node.id}`), history);
  return node;
}

export function insertEdgeElement(graph, edge, history = null) {
  if (!canModifyGraph(graph) || !edge) return null;
  if (!validateEdgeId(graph, edge.id).ok) return null;
  if (!validateEdgeConnection(graph, { source: edge.source, target: edge.target }).ok) return null;
  run(graph, insertEdgesCommand([{ edge, index: graph.edges.length }], `Add edge ${edge.id}`), history);
  return edge;
}

export function updateNodeFields(graph, id, patch, history = null, options = {}) {
  if (!canModifyGraph(graph) || !graph.nodeMap[id]) return null;
  return run(graph, updateNodeCommand(id, patch, `Edit node ${id}`), history, options);
}

// An explicit authored action, never something save or open does on the reader's behalf (see
// JoinSemantics.migrate's javadoc): one composite command that materialises the plan's node patches
// and stamps the marker, so the whole migration is one undo step. Returns null when the document is
// not modifiable OR already declares join.semantics=declared -- in the latter case there is nothing to
// do and running it again must change nothing, same as the Java-side migrate() being idempotent.
export function migrateJoinSemantics(graph, history = null) {
  if (!canModifyGraph(graph)) return null;
  const plan = planJoinSemanticsMigration(graph);
  if (plan.alreadyDeclared) return null;
  const steps = plan.changes.map(change => updateNodeCommand(change.nodeId,
    { properties: { ...graph.nodeMap[change.nodeId].properties, [change.property]: change.value } },
    `Set ${change.property}=${change.value} on ${change.nodeId}`));
  steps.push(updateGraphPropertiesCommand(
    { [JOIN_SEMANTICS_PROPERTY]: JOIN_SEMANTICS_DECLARED }, 'Declare join semantics'));
  return run(graph, compositeCommand(steps, 'Migrate join semantics'), history);
}

export function updateNodePropertiesBatch(graph, entries, history = null) {
  if (!canModifyGraph(graph) || !Array.isArray(entries) || entries.length === 0) return null;
  return run(graph, batchUpdateNodePropertiesCommand(entries,
    `Edit properties on ${entries.length} selected nodes`), history);
}

export function updateEdgeFields(graph, id, patch, history = null) {
  if (!canModifyGraph(graph)) return null;
  const edge = graph.edges.find(candidate => candidate.id === id);
  if (!edge) return null;
  // A patch that moves an endpoint is a reconnection however it arrived, so it faces the same
  // topological rules as one drawn with the pointer. Patches that leave the ends alone skip the
  // check entirely and stay as cheap as they were.
  if (Object.hasOwn(patch, 'source') || Object.hasOwn(patch, 'target')) {
    const proposal = {
      source: Object.hasOwn(patch, 'source') ? patch.source : edge.source,
      target: Object.hasOwn(patch, 'target') ? patch.target : edge.target,
      edgeId: id,
    };
    if (!validateEdgeConnection(graph, proposal).ok) return null;
  }
  return run(graph, updateEdgeCommand(id, patch, `Edit edge ${id}`), history);
}

// One drag gesture is one undo step, however many nodes moved. Entries whose coordinates did not
// change are dropped so a click that merely grabs a node does not push an empty step.
export function moveNodesTo(graph, positions = [], history = null, commandLabel = null) {
  if (!canModifyGraph(graph)) return null;
  const moved = positions.filter(entry => {
    const node = graph.nodeMap[entry.id];
    return node && (node.ox !== Number(entry.ox) || node.oy !== Number(entry.oy)
      || node._positionIsCenter !== true);
  });
  if (!moved.length) return null;
  const label = commandLabel
    || (moved.length === 1 ? `Move ${moved[0].id}` : `Move ${moved.length} nodes`);
  return run(graph, moveNodesCommand(moved, label), history);
}

// Deleting a node cascades to its incident edges. The cascade is a composite, so undo restores the
// node AND every edge it carried away — with their properties, their ids and their original
// positions in graph.edges — as a single step.
export function deleteElements(graph, selectedNodeIds = [], selectedEdgeIds = [], history = null) {
  if (!canModifyGraph(graph)) return { nodeIds: [], edgeIds: [] };
  const nodeIds = [...new Set(selectedNodeIds)].filter(id => Boolean(graph.nodeMap[id]));
  const nodeIdSet = new Set(nodeIds);
  const requestedEdgeIds = new Set(selectedEdgeIds);
  const edgeIds = graph.edges
    .filter(edge => requestedEdgeIds.has(edge.id) || nodeIdSet.has(edge.source) || nodeIdSet.has(edge.target))
    .map(edge => edge.id);
  if (!nodeIds.length && !edgeIds.length) return { nodeIds: [], edgeIds: [] };

  const steps = [];
  if (edgeIds.length) steps.push(removeEdgesCommand(edgeIds, 'Delete edges'));
  if (nodeIds.length) steps.push(removeNodesCommand(nodeIds, 'Delete nodes'));
  run(graph, compositeCommand(steps, deleteLabel(nodeIds.length, edgeIds.length)), history);

  return { nodeIds, edgeIds };
}

function deleteLabel(nodeCount, edgeCount) {
  if (nodeCount && edgeCount) return `Delete ${nodeCount} node(s) and ${edgeCount} edge(s)`;
  if (nodeCount) return `Delete ${nodeCount} node(s)`;
  return `Delete ${edgeCount} edge(s)`;
}

export function isEditableTarget(target) {
  if (!target) return false;
  if (target.isContentEditable || target.contentEditable === 'true'
    || target.getAttribute?.('contenteditable') === '') return true;
  const tagName = String(target.tagName || '').toUpperCase();
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(tagName)
    || Boolean(target.closest?.('[contenteditable="true"], [contenteditable=""]'));
}

export function isDeleteShortcut(event) {
  return ['Delete', 'Backspace', 'Canc'].includes(event.key);
}

export function shouldDeleteSelection(event, modifyEnabled) {
  return Boolean(modifyEnabled && isDeleteShortcut(event) && !isEditableTarget(event.target));
}

export function shouldCreateNodeFromStage(modifyEnabled, gestureStarted, gestureMoved) {
  return Boolean(modifyEnabled && gestureStarted && !gestureMoved);
}

// Undo is Ctrl/⌘+Z, redo is Ctrl/⌘+Shift+Z or Ctrl+Y, and neither fires while the caret is inside
// an editor field, where the browser's own text undo must keep working.
export function historyShortcut(event) {
  if (!event || !(event.ctrlKey || event.metaKey) || event.altKey) return null;
  if (isEditableTarget(event.target)) return null;
  const key = String(event.key || '').toLowerCase();
  if (key === 'z') return event.shiftKey ? 'redo' : 'undo';
  if (key === 'y' && !event.shiftKey) return 'redo';
  if (key === 's' && !event.shiftKey) return 'save';
  return null;
}
