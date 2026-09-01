// Command model over the canonical graph document (UI-01).
//
// Every edit is a reversible command applied to the document that parseGraphML produced and
// serializeGraphML consumes. The renderer is rebuilt from the document afterwards; it never owns
// a mutation. That inversion is the point of this module: an undo step restores document state,
// not Cytoscape state.
//
// Two invariants make GraphML round-trip invariance hold:
// 1. `remove-*` captures the element OBJECTS and their array indices, so its inverse restores the
// same references at the same positions. Parser-owned fields (`_sourceCanonical`,
// `_syntheticId`, `_sourceEdgeIndex`, `_positionIsCenter`) survive a delete/undo untouched,
// and serializeGraphML still recognises the element as unchanged.
// 2. `update-*` records the previous value of exactly the keys it patches, so undo restores the
// element field-for-field instead of reconstructing it.
// A command model that rebuilt objects instead would perturb key order and canonical-source
// tracking, and the round trip would drift.

export const COMMAND_HISTORY_LIMIT = 100;

// A save point that has been trimmed off the bottom of a bounded stack can never be reached again
// by undoing, so the document stays dirty until the next save. A sentinel expresses that without a
// second flag: no history entry is ever equal to it.
const SAVE_POINT_UNREACHABLE = Symbol('ravenroot.save-point-unreachable');

// ═══════════════════════════════════════════════════════════════
// COMMAND CONSTRUCTORS
// ═══════════════════════════════════════════════════════════════

export function insertNodesCommand(entries, label = 'Insert nodes') {
  return { type: 'insert-nodes', label, entries: normalizeEntries(entries, 'node') };
}

export function removeNodesCommand(nodeIds, label = 'Remove nodes') {
  return { type: 'remove-nodes', label, nodeIds: [...nodeIds] };
}

export function insertEdgesCommand(entries, label = 'Insert edges') {
  return { type: 'insert-edges', label, entries: normalizeEntries(entries, 'edge') };
}

export function removeEdgesCommand(edgeIds, label = 'Remove edges') {
  return { type: 'remove-edges', label, edgeIds: [...edgeIds] };
}

export function updateNodeCommand(id, patch, label = `Edit node ${id}`, unset = []) {
  return { type: 'update-node', label, id, patch: { ...patch }, unset: [...unset] };
}

// Graph-level properties (currently only `join.semantics`, the marker) get their own
// command type rather than being folded into `update-node`, because they patch `graph.graphProperties`
// itself, not one element inside `graph.nodes`/`graph.edges`. Routing the migration action through
// this -- instead of assigning into `graphProperties` directly -- is what makes stamping the marker an
// undo step like every other edit, and what makes the document report itself dirty for it.
export function updateGraphPropertiesCommand(patch, label = 'Edit graph properties', unset = []) {
  return { type: 'update-graph-properties', label, patch: { ...patch }, unset: [...unset] };
}

export function updateEdgeCommand(id, patch, label = `Edit edge ${id}`, unset = []) {
  return { type: 'update-edge', label, id, patch: { ...patch }, unset: [...unset] };
}

export function batchUpdateNodePropertiesCommand(entries, label = 'Edit selected node properties') {
  return {
    type: 'batch-update-node-properties',
    label,
    entries: entries.map(entry => ({
      id: entry.id,
      properties: normalizeMapPatch(entry.properties),
      propertyTypes: normalizeMapPatch(entry.propertyTypes),
    })),
  };
}

export function moveNodesCommand(entries, label = 'Move nodes') {
  return {
    type: 'move-nodes',
    label,
    entries: entries.map(entry => {
      const moved = { id: entry.id, ox: Number(entry.ox), oy: Number(entry.oy) };
      if (Object.hasOwn(entry, 'positionIsCenter')) moved.positionIsCenter = entry.positionIsCenter;
      return moved;
    }),
  };
}

// One user gesture, one undo step. Multi-node drag, delete-with-incident-edges and paste-like
// batches are all a composite: applying it collects each inverse and returns them reversed, so the
// whole batch undoes atomically and can never be unwound in pieces.
export function compositeCommand(commands, label = 'Composite edit') {
  return { type: 'composite', label, commands: [...commands] };
}

function normalizeEntries(entries, key) {
  return entries.map(entry => ({ [key]: entry[key], index: Number(entry.index) }));
}

function normalizeMapPatch(patch = {}) {
  return { set: { ...(patch.set || {}) }, unset: [...(patch.unset || [])] };
}

// ═══════════════════════════════════════════════════════════════
// APPLY / INVERT
// ═══════════════════════════════════════════════════════════════

// Applies `command` to `graph` and returns the command that undoes it. The inverse is computed
// DURING apply, from the state the command is about to overwrite: computing it beforehand would
// miss cascades, and computing it afterwards would have nothing left to read.
export function applyCommand(graph, command) {
  if (!graph || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) {
    throw new Error('A graph command needs a canonical document with nodes and edges');
  }
  const handler = HANDLERS[command?.type];
  if (!handler) throw new Error(`Unknown graph command type '${command?.type}'`);
  return handler(graph, command);
}

const HANDLERS = {
  'insert-nodes': (graph, command) => {
    const entries = [...command.entries].sort((left, right) => left.index - right.index);
    const used = new Set(graph.nodes.map(node => node.id));
    // Preflight the whole batch: a duplicate in a later entry must not leave the valid prefix
    // inserted when this command is used on its own or as one composite child.
    for (const entry of entries) {
      if (used.has(entry.node.id)) throw new Error(`Node '${entry.node.id}' already exists`);
      used.add(entry.node.id);
    }
    for (const entry of entries) {
      graph.nodes.splice(clampIndex(entry.index, graph.nodes.length), 0, entry.node);
    }
    reindexNodes(graph);
    return removeNodesCommand(entries.map(entry => entry.node.id), command.label);
  },

  'remove-nodes': (graph, command) => {
    const wanted = new Set(command.nodeIds);
    for (const id of wanted) {
      if (!graph.nodes.some(node => node.id === id)) {
        throw new Error(`Cannot remove unknown node '${id}'`);
      }
    }
    const entries = [];
    graph.nodes.forEach((node, index) => {
      if (wanted.has(node.id)) entries.push({ node, index });
    });
    graph.nodes = graph.nodes.filter(node => !wanted.has(node.id));
    reindexNodes(graph);
    // Ascending capture order matters: re-inserting removed indices in ascending order into the
    // reduced array reproduces the original array exactly.
    return insertNodesCommand(entries, command.label);
  },

  'insert-edges': (graph, command) => {
    const entries = [...command.entries].sort((left, right) => left.index - right.index);
    const used = new Set(graph.edges.map(edge => edge.id));
    for (const entry of entries) {
      if (used.has(entry.edge.id)) throw new Error(`Edge '${entry.edge.id}' already exists`);
      used.add(entry.edge.id);
    }
    for (const entry of entries) {
      graph.edges.splice(clampIndex(entry.index, graph.edges.length), 0, entry.edge);
    }
    return removeEdgesCommand(entries.map(entry => entry.edge.id), command.label);
  },

  'remove-edges': (graph, command) => {
    const wanted = new Set(command.edgeIds);
    for (const id of wanted) {
      if (!graph.edges.some(edge => edge.id === id)) {
        throw new Error(`Cannot remove unknown edge '${id}'`);
      }
    }
    const entries = [];
    graph.edges.forEach((edge, index) => {
      if (wanted.has(edge.id)) entries.push({ edge, index });
    });
    graph.edges = graph.edges.filter(edge => !wanted.has(edge.id));
    return insertEdgesCommand(entries, command.label);
  },

  'update-node': (graph, command) => {
    const node = graph.nodeMap?.[command.id] || graph.nodes.find(candidate => candidate.id === command.id);
    if (!node) throw new Error(`Cannot update unknown node '${command.id}'`);
    // The id is the nodeMap key, the Cytoscape element id and the GraphML <node id>. Renaming it
    // through a patch would desynchronize all three, so an id change is a delete plus an insert.
    if (Object.hasOwn(command.patch, 'id')) throw new Error('A node id cannot be patched');
    const inverse = invertPatch(node, command);
    applyPatch(node, command);
    reindexNodes(graph);
    return updateNodeCommand(command.id, inverse.patch, command.label, inverse.unset);
  },

  'update-graph-properties': (graph, command) => {
    graph.graphProperties = graph.graphProperties || {};
    const inverse = invertPatch(graph.graphProperties, command);
    applyPatch(graph.graphProperties, command);
    return updateGraphPropertiesCommand(inverse.patch, command.label, inverse.unset);
  },

  'update-edge': (graph, command) => {
    const edge = graph.edges.find(candidate => candidate.id === command.id);
    if (!edge) throw new Error(`Cannot update unknown edge '${command.id}'`);
    if (Object.hasOwn(command.patch, 'id')) throw new Error('An edge id cannot be patched');
    const inverse = invertPatch(edge, command);
    applyPatch(edge, command);
    return updateEdgeCommand(command.id, inverse.patch, command.label, inverse.unset);
  },

  'batch-update-node-properties': (graph, command) => {
    const planned = preflightNodePropertyBatch(graph, command);
    const inverseEntries = planned.map(({ node, entry }) => ({
      id: node.id,
      properties: invertMapPatch(node.properties, entry.properties),
      propertyTypes: invertMapPatch(node.propertyTypes, entry.propertyTypes),
    }));
    // No document mutation occurs until every id, map and key in the batch has passed preflight.
    // A late invalid entry therefore cannot leave a partially edited graph or a history entry whose
    // inverse only covers the prefix that happened to run.
    for (const { node, entry } of planned) {
      node.properties = applyMapPatch(node.properties, entry.properties);
      node.propertyTypes = applyMapPatch(node.propertyTypes, entry.propertyTypes);
    }
    return batchUpdateNodePropertiesCommand(inverseEntries, command.label);
  },

  'move-nodes': (graph, command) => {
    const planned = command.entries.map(entry => {
      const node = graph.nodeMap?.[entry.id] || graph.nodes.find(candidate => candidate.id === entry.id);
      if (!node) throw new Error(`Cannot move unknown node '${entry.id}'`);
      return { node, entry };
    });
    const restored = planned.map(({ node }) => ({
      id: node.id, ox: node.ox, oy: node.oy, positionIsCenter: node._positionIsCenter,
    }));
    for (const { node, entry } of planned) {
      node.ox = entry.ox;
      node.oy = entry.oy;
      node._positionIsCenter = Object.hasOwn(entry, 'positionIsCenter') ? entry.positionIsCenter : true;
    }
    return moveNodesCommand(restored, command.label);
  },

  composite: (graph, command) => {
    const inverses = [];
    try {
      for (const child of command.commands) inverses.push(applyCommand(graph, child));
    } catch (error) {
      // A composite is the editor's atomic unit. Preflight normally makes this path unreachable,
      // but an unexpected late failure must still restore the exact prefix it already applied.
      for (const inverse of inverses.reverse()) applyCommand(graph, inverse);
      throw error;
    }
    return compositeCommand(inverses.reverse(), command.label);
  },
};

function applyPatch(element, command) {
  Object.assign(element, command.patch);
  for (const key of command.unset || []) delete element[key];
}

function invertPatch(element, command) {
  const patch = {};
  const unset = [];
  for (const key of Object.keys(command.patch)) {
    if (Object.hasOwn(element, key)) patch[key] = element[key];
    else unset.push(key);
  }
  for (const key of command.unset || []) {
    if (Object.hasOwn(element, key)) patch[key] = element[key];
  }
  return { patch, unset };
}

function preflightNodePropertyBatch(graph, command) {
  if (!Array.isArray(command.entries) || command.entries.length === 0) {
    throw new Error('A node property batch needs at least one entry');
  }
  const seen = new Set();
  return command.entries.map(entry => {
    if (!entry || typeof entry.id !== 'string' || entry.id === '') {
      throw new Error('A node property batch entry needs a node id');
    }
    if (seen.has(entry.id)) throw new Error(`Node '${entry.id}' occurs twice in the property batch`);
    seen.add(entry.id);
    const node = graph.nodeMap?.[entry.id] || graph.nodes.find(candidate => candidate.id === entry.id);
    if (!node) throw new Error(`Cannot update unknown node '${entry.id}'`);
    validateMapPatch(entry.properties, 'properties', entry.id);
    validateMapPatch(entry.propertyTypes, 'propertyTypes', entry.id);
    if (!isPlainMap(node.properties) || !isPlainMap(node.propertyTypes)) {
      throw new Error(`Node '${entry.id}' has invalid property maps`);
    }
    return { node, entry };
  });
}

function validateMapPatch(patch, mapName, id) {
  if (!patch || !isPlainMap(patch.set) || !Array.isArray(patch.unset)) {
    throw new Error(`Node '${id}' has an invalid ${mapName} patch`);
  }
  const setNames = new Set(Object.keys(patch.set));
  for (const name of [...setNames, ...patch.unset]) {
    if (!name || typeof name !== 'string') throw new Error(`Node '${id}' has an invalid property name`);
  }
  if (new Set(patch.unset).size !== patch.unset.length) {
    throw new Error(`Node '${id}' has duplicate ${mapName} removals`);
  }
  for (const name of patch.unset) {
    if (setNames.has(name)) throw new Error(`Node '${id}' both sets and removes '${name}'`);
  }
  for (const value of Object.values(patch.set)) {
    if (typeof value !== 'string') throw new Error(`Node '${id}' ${mapName} values must be strings`);
  }
}

function isPlainMap(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function invertMapPatch(map, patch) {
  const set = {};
  const unset = [];
  for (const name of Object.keys(patch.set)) {
    if (Object.hasOwn(map, name)) set[name] = map[name];
    else unset.push(name);
  }
  for (const name of patch.unset) {
    if (Object.hasOwn(map, name)) set[name] = map[name];
  }
  return { set, unset };
}

function applyMapPatch(map, patch) {
  const next = { ...map, ...patch.set };
  for (const name of patch.unset) delete next[name];
  return next;
}

function clampIndex(index, length) {
  if (!Number.isFinite(index) || index < 0) return length;
  return Math.min(index, length);
}

function reindexNodes(graph) {
  graph.nodeMap = Object.fromEntries(graph.nodes.map(node => [node.id, node]));
}

// The elements a command touched, so the caller can restore selection after undo or redo.
export function commandTargets(command) {
  const nodeIds = new Set();
  const edgeIds = new Set();
  collectTargets(command, nodeIds, edgeIds);
  return { nodeIds: [...nodeIds], edgeIds: [...edgeIds] };
}

function collectTargets(command, nodeIds, edgeIds) {
  if (!command) return;
  switch (command.type) {
    case 'insert-nodes': command.entries.forEach(entry => nodeIds.add(entry.node.id)); break;
    case 'remove-nodes': command.nodeIds.forEach(id => nodeIds.add(id)); break;
    case 'insert-edges': command.entries.forEach(entry => edgeIds.add(entry.edge.id)); break;
    case 'remove-edges': command.edgeIds.forEach(id => edgeIds.add(id)); break;
    case 'update-node': nodeIds.add(command.id); break;
    case 'batch-update-node-properties': command.entries.forEach(entry => nodeIds.add(entry.id)); break;
    case 'update-edge': edgeIds.add(command.id); break;
    case 'move-nodes': command.entries.forEach(entry => nodeIds.add(entry.id)); break;
    case 'composite': command.commands.forEach(child => collectTargets(child, nodeIds, edgeIds)); break;
    default: break;
  }
}

// ═══════════════════════════════════════════════════════════════
// HISTORY AND DIRTY STATE
// ═══════════════════════════════════════════════════════════════

export function createCommandHistory(options = {}) {
  const limit = Number.isFinite(options.limit) && options.limit > 0
    ? Math.floor(options.limit) : COMMAND_HISTORY_LIMIT;
  let past = [];
  let future = [];
  // The entry on top of `past` at the last save, or null when the document was saved with an empty
  // stack. Dirty is therefore a comparison against a position in the stack, not a boolean that a
  // later undo would have to guess how to clear: undoing back to the save point is clean again.
  let savePoint = null;
  // Monotonic state identity. Stack depth is not a revision: edit -> undo returns to the same
  // depth while an outstanding Assistant proposal must still become stale.
  let revision = 0;

  function trim() {
    while (past.length > limit) {
      const dropped = past.shift();
      if (savePoint === null || savePoint === dropped) savePoint = SAVE_POINT_UNREACHABLE;
    }
  }

  const history = {
    execute(graph, command, options = {}) {
      const coalesceKey = options.coalesceKey || null;
      const metadata = options.metadata || null;
      const previous = past.at(-1);
      if (coalesceKey && previous?.coalesceKey === coalesceKey && previous !== savePoint) {
        applyCommand(graph, previous.inverse);
        try {
          previous.inverse = applyCommand(graph, command);
          previous.command = command;
          previous.metadata = metadata;
        } catch (error) {
          previous.inverse = applyCommand(graph, previous.command);
          throw error;
        }
        future = [];
        revision += 1;
        return command;
      }
      const inverse = applyCommand(graph, command);
      past.push({ command, inverse, coalesceKey, metadata });
      future = [];
      trim();
      revision += 1;
      return command;
    },

    // Undo and redo move the SAME entry object between the two stacks. Identity is what the save
    // point is compared against, so minting a replacement entry on redo would make a document that
    // is byte-for-byte the saved one report itself dirty.
    undo(graph) {
      const entry = past.at(-1);
      if (!entry) return null;
      applyCommand(graph, entry.inverse);
      past.pop();
      future.push(entry);
      revision += 1;
      return entry.inverse;
    },

    redo(graph) {
      const entry = future.at(-1);
      if (!entry) return null;
      // Re-applying recomputes the inverse against the state it actually found, so a second undo
      // reverses this redo rather than a stale snapshot.
      entry.inverse = applyCommand(graph, entry.command);
      future.pop();
      past.push(entry);
      trim();
      revision += 1;
      return entry.command;
    },

    canUndo: () => past.length > 0,
    canRedo: () => future.length > 0,
    undoLabel: () => past.at(-1)?.command.label || '',
    redoLabel: () => future.at(-1)?.command.label || '',
    depth: () => past.length,
    revision: () => revision,
    limit: () => limit,

    markSaved() {
      savePoint = past.at(-1) ?? null;
    },

    isDirty: () => (past.at(-1) ?? null) !== savePoint,

    reset() {
      past = [];
      future = [];
      savePoint = null;
      revision += 1;
    },

    state: () => ({
      canUndo: past.length > 0,
      canRedo: future.length > 0,
      dirty: (past.at(-1) ?? null) !== savePoint,
      undoLabel: past.at(-1)?.command.label || '',
      redoLabel: future.at(-1)?.command.label || '',
      depth: past.length,
      revision,
      lastMetadata: past.at(-1)?.metadata || null,
    }),
  };
  return history;
}

// ═══════════════════════════════════════════════════════════════
// LOSING UNSAVED WORK
// ═══════════════════════════════════════════════════════════════

export function discardChangesMessage(documentName) {
  return `${documentName || 'This workflow'} has unsaved changes.\n\n`
    + 'OK discards them. Cancel keeps the current workflow so you can save it as GraphML first.';
}

// Browsers ignore any custom text on beforeunload and only honour the fact that a handler asked to
// warn, so the predicate is all there is to decide and all there is to test.
export function shouldWarnBeforeUnload(dirty) {
  return Boolean(dirty);
}
