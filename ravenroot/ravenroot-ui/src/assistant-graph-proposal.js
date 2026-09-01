import { catalogPropertyHasDeclaredDefault } from './adapter-binding.js';
import { resolveDescriptorNodeType } from './catalog-node-icon.js';
import {
  applyCommand,
  compositeCommand,
  insertEdgesCommand,
  insertNodesCommand,
  moveNodesCommand,
  removeEdgesCommand,
  removeNodesCommand,
  updateEdgeCommand,
  updateNodeCommand,
} from './graph-commands.js';
import { createEdge, createNode } from './graph-document.js';
import { validateEdgeConnection, validateEdgeId } from './edge-gestures.js';
import { validateMultiPropertyValue } from './multi-selection-inspector.js';
import { resolveOutcomes, unreachableOutcome } from './node-outcomes.js';
import { isPropertyRequiredNow, isPropertyVisible } from './property-condition.js';
import { stableEdgeIdViolation } from './stable-edge-id.js';

export const ASSISTANT_PROPOSAL_VERSION = 1;
export const ASSISTANT_PROPOSAL_OPERATION_LIMIT = 64;
export const ASSISTANT_PROPOSAL_BYTE_LIMIT = 128 * 1024;

const SENSITIVE_NAME = /(?:secret|password|passwd|token|credential|api[-_.]?key)/i;
const IDENTIFIER_LIMIT = 256;
const SUMMARY_LIMIT = 500;
const OP_TYPES = new Set([
  'create-node', 'update-node', 'delete-node',
  'create-edge', 'update-edge', 'delete-edge',
]);

/**
 * Strictly validates and plans an inert Assistant proposal against one exact editor revision.
 * Nothing in this module touches the live graph. Its output is one normal composite command.
 */
export function planAssistantGraphProposal(proposal, {
  documentIncarnation, revision, catalogDigest, graph, catalog,
} = {}) {
  try {
    const normalized = normalizeProposal(proposal);
    if (!graph || graph.format !== 'graphml') fail('This document is not an editable GraphML graph.');
    if (normalized.document.incarnation !== String(documentIncarnation)) {
      fail('This proposal belongs to a different open document.');
    }
    if (normalized.document.revision !== revision) {
      fail('This proposal is stale because the document changed after it was requested.');
    }
    if (normalized.document.catalogDigest !== catalogDigest
        || catalogProposalDigest(catalog) !== catalogDigest) {
      fail('This proposal is stale because the node catalog changed after it was requested.');
    }
    const working = cloneGraph(graph);
    const catalogByBehavior = new Map((catalog || []).map(entry => [entry?.behavior, entry]));
    const refs = new Map();
    const mutated = new Set();
    const directlyChangedEdges = new Set();
    const commands = [];
    const changes = [];

    normalized.operations.forEach((operation, index) => {
      try {
        planOperation(operation, {
          working, catalogByBehavior, refs, mutated, directlyChangedEdges, commands, changes, index,
        });
      } catch (error) {
        fail(`Operation ${index + 1}: ${error.message}`);
      }
    });

    if (!commands.length) fail('The proposal does not contain an edit.');
    return {
      ok: true,
      proposal: normalized,
      command: compositeCommand(commands, 'Apply Assistant proposal'),
      preview: Object.freeze({ summary: normalized.summary, changes: Object.freeze(changes) }),
    };
  } catch (error) {
    return { ok: false, errors: [String(error?.message || error)] };
  }
}

/** Revalidates at click time, then enters the ordinary history exactly once. */
export function applyAssistantGraphProposal(proposal, context = {}) {
  const plan = planAssistantGraphProposal(proposal, context);
  if (!plan.ok) return plan;
  context.history.execute(context.graph, plan.command, {
    metadata: Object.freeze({
      origin: 'assistant', proposalId: plan.proposal.id,
      schemaVersion: plan.proposal.version, opCount: plan.proposal.operations.length,
      userConfirmed: true,
    }),
  });
  return { ...plan, applied: true };
}

export function rejectAssistantGraphProposal(proposal) {
  return { id: proposal?.id || null, rejected: true };
}

function planOperation(operation, state) {
  const {
    working, catalogByBehavior, refs, mutated, directlyChangedEdges, commands, changes,
  } = state;
  switch (operation.op) {
    case 'create-node': {
      exactKeys(operation, ['op', 'ref', 'id', 'behavior'], ['name', 'position', 'properties']);
      const ref = newRef(operation.ref, refs);
      const descriptor = catalogByBehavior.get(operation.behavior);
      if (!descriptor) fail(`Unknown catalog behavior '${operation.behavior}'.`);
      const id = explicitElementId(operation.id, working);
      const position = normalizePosition(operation.position, working.nodes.length);
      const node = createNode(id, optionalName(operation.name) || descriptor.displayName || id,
        'BEHAVIOR', position);
      node.behavior = descriptor.behavior;
      node.nodeType = resolveDescriptorNodeType(descriptor);
      node.properties = Object.fromEntries((descriptor.properties || [])
        .filter(catalogPropertyHasDeclaredDefault)
        .filter(property => !secretProperty(property))
        .map(property => [property.name, String(property.defaultValue)]));
      node.propertyTypes = Object.fromEntries((descriptor.properties || [])
        .filter(catalogPropertyHasDeclaredDefault)
        .filter(property => !secretProperty(property))
        .map(property => [property.name, graphMlType(property.type)]));
      applyValidatedProperties(node, descriptor, operation.properties || [], []);
      validateRequiredProperties(node, descriptor);
      const command = insertNodesCommand([{ node, index: working.nodes.length }], `Add ${id}`);
      applyCommand(working, command);
      commands.push(command);
      refs.set(ref, { kind: 'node', id, created: true });
      changes.push(`Add node ${node.name} (${id}) from catalog behavior ${descriptor.behavior}.`);
      break;
    }
    case 'update-node': {
      exactKeys(operation, ['op', 'target'], ['name', 'position', 'properties', 'removeProperties']);
      const selected = resolveSelector(operation.target, refs, working, 'node');
      claimMutation(mutated, selected, 'node');
      const node = working.nodeMap[selected.id];
      const descriptor = catalogByBehavior.get(node.behavior);
      if (!descriptor) fail(`Node '${selected.id}' has no trusted catalog descriptor.`);
      const patch = {};
      if (Object.hasOwn(operation, 'name')) patch.name = requiredText(operation.name, 'node name', 500);
      const next = {
        ...node,
        properties: { ...(node.properties || {}) },
        propertyTypes: { ...(node.propertyTypes || {}) },
      };
      applyValidatedProperties(next, descriptor, operation.properties || [], operation.removeProperties || []);
      validateRequiredProperties(next, descriptor);
      if (Object.hasOwn(operation, 'properties') || Object.hasOwn(operation, 'removeProperties')) {
        patch.properties = next.properties;
        patch.propertyTypes = next.propertyTypes;
      }
      const operationCommands = [];
      if (Object.keys(patch).length > 0) {
        operationCommands.push(updateNodeCommand(selected.id, patch, `Edit ${selected.id}`));
      }
      if (Object.hasOwn(operation, 'position')) {
        const position = normalizePosition(operation.position, 0);
        operationCommands.push(moveNodesCommand([{
          id: selected.id, ox: position.x, oy: position.y, positionIsCenter: true,
        }], `Move ${selected.id}`));
      }
      if (!operationCommands.length) fail('An update-node operation needs a change.');
      for (const command of operationCommands) {
        applyCommand(working, command);
        commands.push(command);
      }
      changes.push(`Update node ${selected.id}${describeNodePatch(operation)}.`);
      break;
    }
    case 'delete-node': {
      exactKeys(operation, ['op', 'target']);
      const selected = resolveSelector(operation.target, refs, working, 'node');
      claimMutation(mutated, selected, 'node');
      if (selected.created) fail('A proposal cannot delete a node it just created.');
      const incident = working.edges
        .filter(edge => edge.source === selected.id || edge.target === selected.id)
        .map(edge => edge.id);
      const contradictory = incident.filter(edgeId => directlyChangedEdges.has(edgeId));
      if (contradictory.length) {
        fail(`Deleting node '${selected.id}' would also delete an edge changed by this proposal.`);
      }
      if (incident.length) {
        const removeIncident = removeEdgesCommand(incident, `Delete edges incident to ${selected.id}`);
        applyCommand(working, removeIncident);
        commands.push(removeIncident);
      }
      const command = removeNodesCommand([selected.id], `Delete ${selected.id}`);
      applyCommand(working, command);
      commands.push(command);
      changes.push(`Delete node ${selected.id}${incident.length ? ` and ${incident.length} incident edge(s)` : ''}.`);
      break;
    }
    case 'create-edge': {
      exactKeys(operation, ['op', 'ref', 'id', 'source', 'destination'], ['outcome']);
      const ref = newRef(operation.ref, refs);
      const source = resolveSelector(operation.source, refs, working, 'node').id;
      const target = resolveSelector(operation.destination, refs, working, 'node').id;
      const id = explicitEdgeId(operation.id, working);
      const outcome = optionalOutcome(operation.outcome);
      validateEdge(working, { id, source, target, outcome }, catalogByBehavior);
      const edge = createEdge(id, source, target, outcome);
      const command = insertEdgesCommand([{ edge, index: working.edges.length }], `Connect ${source} → ${target}`);
      applyCommand(working, command);
      commands.push(command);
      refs.set(ref, { kind: 'edge', id, created: true });
      directlyChangedEdges.add(id);
      changes.push(`Add edge ${id}: ${source} → ${target}, outcome ${edge.outcome}.`);
      break;
    }
    case 'update-edge': {
      exactKeys(operation, ['op', 'target'], ['source', 'destination', 'outcome']);
      const selected = resolveSelector(operation.target, refs, working, 'edge');
      claimMutation(mutated, selected, 'edge');
      const edge = working.edges.find(candidate => candidate.id === selected.id);
      const patch = {};
      if (Object.hasOwn(operation, 'source')) {
        patch.source = resolveSelector(operation.source, refs, working, 'node').id;
      }
      if (Object.hasOwn(operation, 'destination')) {
        patch.target = resolveSelector(operation.destination, refs, working, 'node').id;
      }
      if (Object.hasOwn(operation, 'outcome')) patch.outcome = optionalOutcome(operation.outcome);
      if (Object.keys(patch).length === 0) fail('An update-edge operation needs a change.');
      validateEdge(working, { ...edge, ...patch }, catalogByBehavior, selected.id);
      const command = updateEdgeCommand(selected.id, patch, `Edit ${selected.id}`);
      applyCommand(working, command);
      commands.push(command);
      directlyChangedEdges.add(selected.id);
      changes.push(`Update edge ${selected.id}${describeEdgePatch(patch)}.`);
      break;
    }
    case 'delete-edge': {
      exactKeys(operation, ['op', 'target']);
      const selected = resolveSelector(operation.target, refs, working, 'edge');
      claimMutation(mutated, selected, 'edge');
      if (selected.created) fail('A proposal cannot delete an edge it just created.');
      const command = removeEdgesCommand([selected.id], `Delete ${selected.id}`);
      applyCommand(working, command);
      commands.push(command);
      changes.push(`Delete edge ${selected.id}.`);
      break;
    }
    default:
      fail(`Unknown operation '${operation.op}'.`);
  }
}

function normalizeProposal(value) {
  if (!plainObject(value)) fail('The assistant proposal must be an object.');
  if (new TextEncoder().encode(JSON.stringify(value)).length > ASSISTANT_PROPOSAL_BYTE_LIMIT) {
    fail('The assistant proposal is too large.');
  }
  exactKeys(value, ['version', 'id', 'document', 'summary', 'operations']);
  if (value.version !== ASSISTANT_PROPOSAL_VERSION) fail('Unknown assistant proposal version.');
  const id = requiredText(value.id, 'proposal id', IDENTIFIER_LIMIT);
  const summary = requiredText(value.summary, 'proposal summary', SUMMARY_LIMIT);
  if (!plainObject(value.document)) fail('The proposal document binding must be an object.');
  exactKeys(value.document, ['incarnation', 'revision', 'catalogDigest']);
  const incarnation = requiredText(value.document.incarnation, 'document incarnation', IDENTIFIER_LIMIT);
  if (!Number.isSafeInteger(value.document.revision) || value.document.revision < 0) {
    fail('The proposal document revision is invalid.');
  }
  if (!Array.isArray(value.operations) || value.operations.length < 1
      || value.operations.length > ASSISTANT_PROPOSAL_OPERATION_LIMIT) {
    fail(`A proposal needs between 1 and ${ASSISTANT_PROPOSAL_OPERATION_LIMIT} operations.`);
  }
  for (const operation of value.operations) {
    if (!plainObject(operation) || !OP_TYPES.has(operation.op)) fail('The proposal contains an unknown operation.');
  }
  return Object.freeze({
    version: value.version,
    id,
    document: Object.freeze({
      incarnation,
      revision: value.document.revision,
      catalogDigest: requiredText(value.document.catalogDigest, 'catalog digest', IDENTIFIER_LIMIT),
    }),
    summary,
    operations: value.operations.map(operation => Object.freeze({ ...operation })),
  });
}

function applyValidatedProperties(node, descriptor, set, remove) {
  if (!Array.isArray(set)) fail('Properties must be an array.');
  if (!Array.isArray(remove) || remove.some(name => typeof name !== 'string')) {
    fail('removeProperties must be a string list.');
  }
  if (new Set(remove).size !== remove.length) fail('removeProperties contains duplicates.');
  const declarations = new Map((descriptor.properties || []).map(property => [property.name, property]));
  const names = new Set();
  for (const entry of set) {
    exactKeys(entry, ['name', 'value']);
    const name = requiredText(entry.name, 'property name', IDENTIFIER_LIMIT);
    const raw = entry.value;
    if (names.has(name)) fail(`Property '${name}' occurs twice.`);
    names.add(name);
    if (remove.includes(name)) fail(`Property '${name}' is both set and removed.`);
    const property = declarations.get(name);
    if (!property) fail(`Property '${name}' is not declared by catalog behavior '${descriptor.behavior}'.`);
    if (secretProperty(property) || SENSITIVE_NAME.test(name)) {
      fail(`Property '${name}' is secret-class and cannot appear in an Assistant proposal.`);
    }
    if (typeof raw !== 'string') fail(`Property '${name}' must be a string.`);
    const normalized = { ...property, type: String(property.type || '').toUpperCase() };
    const invalid = validateMultiPropertyValue(normalized, raw);
    if (invalid) fail(invalid);
    node.properties[name] = raw;
    node.propertyTypes[name] = graphMlType(normalized.type);
  }
  for (const name of remove) {
    const property = declarations.get(name);
    if (!property) fail(`Property '${name}' is not declared by catalog behavior '${descriptor.behavior}'.`);
    if (secretProperty(property) || SENSITIVE_NAME.test(name)) {
      fail(`Property '${name}' is secret-class and cannot appear in an Assistant proposal.`);
    }
    delete node.properties[name];
    delete node.propertyTypes[name];
  }
}

function validateRequiredProperties(node, descriptor) {
  const values = Object.fromEntries((descriptor.properties || []).map(property => [
    property.name,
    Object.hasOwn(node.properties || {}, property.name)
      ? node.properties[property.name]
      : (catalogPropertyHasDeclaredDefault(property) ? String(property.defaultValue) : ''),
  ]));
  for (const property of descriptor.properties || []) {
    if (secretProperty(property)) continue;
    if (!isPropertyVisible(property, values)) continue;
    if (isPropertyRequiredNow(property, values)
        && !String(node.properties?.[property.name] ?? '').trim()) {
      fail(`Required property '${property.name}' is missing.`);
    }
  }
}

function validateEdge(graph, edge, catalogByBehavior, existingId = null) {
  const idVerdict = validateEdgeId(graph, edge.id, { existingId });
  if (!idVerdict.ok) fail(idVerdict.reason);
  const connection = validateEdgeConnection(graph, {
    source: edge.source, target: edge.target, edgeId: existingId,
  });
  if (!connection.ok) fail(connection.reason);
  const source = graph.nodeMap[edge.source];
  const descriptor = catalogByBehavior.get(source?.behavior);
  if (unreachableOutcome(resolveOutcomes(descriptor, source?.properties), edge.outcome)) {
    fail(`Outcome '${edge.outcome}' is not declared by source node '${edge.source}'.`);
  }
}

function resolveSelector(selector, refs, graph, kind) {
  if (!plainObject(selector)) fail('An element selector must be an object.');
  const keys = Object.keys(selector);
  if (!(keys.length === 1 && (keys[0] === 'existing' || keys[0] === 'created'))) {
    fail('An element selector needs exactly one existing or created tag.');
  }
  const token = keys[0] === 'existing' && kind === 'edge'
    ? exactEdgeIdentity(selector[keys[0]], 'element selector')
    : requiredText(selector[keys[0]], 'element selector', IDENTIFIER_LIMIT);
  if (keys[0] === 'created') {
    const resolved = refs.get(token);
    if (!resolved || resolved.kind !== kind) fail(`Unknown ${kind} ref '${token}'.`);
    return resolved;
  }
  const exists = kind === 'node'
    ? Boolean(graph.nodeMap[token])
    : graph.edges.some(edge => edge.id === token);
  if (!exists) fail(`Unknown ${kind} '${token}'.`);
  return { kind, id: token, created: false };
}

function newRef(value, refs) {
  const ref = requiredText(value, 'proposal ref', IDENTIFIER_LIMIT);
  if (refs.has(ref)) fail(`Proposal ref '${ref}' is duplicated.`);
  return ref;
}

function cloneGraph(graph) {
  const nodes = graph.nodes.map(node => ({
    ...node,
    properties: { ...(node.properties || {}) },
    propertyTypes: { ...(node.propertyTypes || {}) },
  }));
  const edges = graph.edges.map(edge => ({
    ...edge,
    properties: { ...(edge.properties || {}) },
    propertyTypes: { ...(edge.propertyTypes || {}) },
  }));
  return { ...graph, nodes, edges, nodeMap: Object.fromEntries(nodes.map(node => [node.id, node])) };
}

/** Stable digest over only proposal-relevant catalog schema, never property values or credentials. */
export function catalogProposalDigest(catalog = []) {
  const normalized = (catalog || []).map(descriptor => ({
    behavior: String(descriptor?.behavior || ''),
    nodeType: String(descriptor?.nodeType || ''),
    nature: String(descriptor?.nature || ''),
    properties: (descriptor?.properties || []).map(property => ({
      name: String(property?.name || ''), type: String(property?.type || '').toUpperCase(),
      required: property?.required === true,
      defaultValue: catalogPropertyHasDeclaredDefault(property) ? String(property.defaultValue) : null,
      allowedValues: [...(property?.allowedValues || [])].map(String),
      visibleWhen: property?.visibleWhen || null,
      requiredWhen: property?.requiredWhen || null,
    })),
    outcomes: descriptor?.outcomes || [],
  })).sort((left, right) => left.behavior.localeCompare(right.behavior));
  return `proposal-catalog-v1-sha256-${sha256(JSON.stringify(normalized))}`;
}

const SHA256_K = Object.freeze([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

function sha256(text) {
  const source = new TextEncoder().encode(text);
  const paddedLength = Math.ceil((source.length + 9) / 64) * 64;
  const bytes = new Uint8Array(paddedLength);
  bytes.set(source);
  bytes[source.length] = 0x80;
  const view = new DataView(bytes.buffer);
  const bitLength = BigInt(source.length) * 8n;
  view.setUint32(paddedLength - 8, Number((bitLength >> 32n) & 0xffffffffn));
  view.setUint32(paddedLength - 4, Number(bitLength & 0xffffffffn));
  const state = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ];
  const words = new Uint32Array(64);
  for (let offset = 0; offset < bytes.length; offset += 64) {
    for (let index = 0; index < 16; index += 1) words[index] = view.getUint32(offset + index * 4);
    for (let index = 16; index < 64; index += 1) {
      const x = words[index - 15];
      const y = words[index - 2];
      const s0 = rotateRight(x, 7) ^ rotateRight(x, 18) ^ (x >>> 3);
      const s1 = rotateRight(y, 17) ^ rotateRight(y, 19) ^ (y >>> 10);
      words[index] = (words[index - 16] + s0 + words[index - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = state;
    for (let index = 0; index < 64; index += 1) {
      const s1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25);
      const choose = (e & f) ^ (~e & g);
      const t1 = (h + s1 + choose + SHA256_K[index] + words[index]) >>> 0;
      const s0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22);
      const majority = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (s0 + majority) >>> 0;
      [a, b, c, d, e, f, g, h] = [(t1 + t2) >>> 0, a, b, c, (d + t1) >>> 0, e, f, g];
    }
    [a, b, c, d, e, f, g, h].forEach((word, index) => { state[index] = (state[index] + word) >>> 0; });
  }
  return state.map(word => word.toString(16).padStart(8, '0')).join('');
}

function rotateRight(value, bits) {
  return (value >>> bits) | (value << (32 - bits));
}

function explicitElementId(value, graph) {
  const id = requiredText(value, 'final graph id', IDENTIFIER_LIMIT);
  if (/\s/u.test(id)) fail('A final graph id cannot contain whitespace.');
  if (graph.nodeMap[id] || graph.edges.some(edge => edge.id === id)) {
    fail(`Final graph id '${id}' already exists.`);
  }
  return id;
}

function exactEdgeIdentity(value, label = 'edge id') {
  const violation = stableEdgeIdViolation(value);
  if (violation || /[\u0000-\u001f\u007f]/u.test(value)) fail(`The ${label} is invalid.`);
  return value;
}

function explicitEdgeId(value, graph) {
  const id = exactEdgeIdentity(value, 'final graph id');
  if (graph.nodeMap[id] || graph.edges.some(edge => edge.id === id)) {
    fail(`Final graph id '${id}' already exists.`);
  }
  return id;
}

function claimMutation(mutated, selected, kind) {
  const key = `${kind}:${selected.id}`;
  if (mutated.has(key)) fail(`${kind} '${selected.id}' is changed more than once.`);
  mutated.add(key);
}

function exactKeys(object, required, optional = []) {
  if (!plainObject(object)) fail('Proposal fields must be objects.');
  const keys = Object.keys(object);
  const allowed = new Set([...required, ...optional]);
  if (!required.every(key => Object.hasOwn(object, key)) || keys.some(key => !allowed.has(key))) {
    fail('The proposal contains unknown or missing fields.');
  }
}

function plainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
}

function requiredText(value, label, limit) {
  if (typeof value !== 'string' || !value.trim() || value !== value.trim()
      || value.length > limit || /[\u0000-\u001f\u007f]/u.test(value)) {
    fail(`The ${label} is invalid.`);
  }
  return value;
}

function optionalName(value) {
  return value == null ? '' : requiredText(value, 'node name', 500);
}

function optionalOutcome(value) {
  if (value == null) return 'continue';
  return requiredText(value, 'edge outcome', 256);
}

function normalizePosition(value, index) {
  if (value == null) return { x: 180 + (index % 4) * 180, y: 180 + Math.floor(index / 4) * 130 };
  exactKeys(value, ['x', 'y']);
  if (![value.x, value.y].every(Number.isFinite)) fail('Node coordinates must be finite numbers.');
  return { x: value.x, y: value.y };
}

function secretProperty(property) {
  return String(property?.type || '').toUpperCase().includes('SECRET');
}

function graphMlType(type) {
  const normalized = String(type || '').toUpperCase();
  if (normalized === 'INTEGER') return 'long';
  if (normalized === 'DECIMAL' || normalized === 'NUMBER') return 'double';
  if (normalized === 'BOOLEAN') return 'boolean';
  return 'string';
}

function describeNodePatch(operation) {
  const parts = [];
  if (Object.hasOwn(operation, 'name')) parts.push('name');
  if (Object.hasOwn(operation, 'position')) {
    parts.push(`position ${operation.position.x}, ${operation.position.y}`);
  }
  const set = (operation.properties || []).map(property => property.name);
  if (set.length) parts.push(`set ${set.join(', ')}`);
  if (operation.removeProperties?.length) parts.push(`remove ${operation.removeProperties.join(', ')}`);
  return parts.length ? ` (${parts.join('; ')})` : '';
}

function describeEdgePatch(patch) {
  const parts = [];
  if (patch.source) parts.push(`source ${patch.source}`);
  if (patch.target) parts.push(`target ${patch.target}`);
  if (patch.outcome) parts.push(`outcome ${patch.outcome}`);
  return parts.length ? ` (${parts.join('; ')})` : '';
}

function fail(message) {
  throw new Error(message);
}
