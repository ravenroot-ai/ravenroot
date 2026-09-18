import { applyCommand, compositeCommand, insertEdgesCommand, insertNodesCommand } from './graph-commands.js';
import { createEdge, createNode } from './graph-document.js';

export const REGISTER_MACHINE_PRESETS = Object.freeze([
  preset('register-set', 'Set register', 'Set one register from an exact decimal string.'),
  preset('register-increment', 'Increment register', 'Add one to one initialized register.'),
  preset('register-zero-test', 'Zero test', 'Compare one initialized register with zero.'),
  preset('register-decjz', 'DECJZ expansion', 'Insert zero test, decision, and guarded decrement as editable ordinary nodes.'),
]);

function preset(presetId, displayName, description) {
  return Object.freeze({ presetId, displayName, description, category: 'Register machine',
    origin: 'CORE', visualType: 'flow', behavior: 'bigint-op' });
}

/** Inserts a preset as one undoable gesture. The saved graph contains only ordinary nodes and edges. */
export function insertRegisterMachinePreset(graph, presetId, configuration, position = {}, history = null) {
  if (!graph || graph.format !== 'graphml') throw new Error('Register-machine presets require editable GraphML');
  const config = normalizedConfiguration(presetId, configuration, graph);
  const x = Number(position.x) || 0;
  const y = Number(position.y) || 0;
  const ids = new Set(graph.nodes.map(node => node.id));
  const edgeIds = new Set(graph.edges.map(edge => edge.id));
  const nodeEntries = [];
  const edgeEntries = [];
  const addNode = (prefix, name, behavior, properties, offset = 0) => {
    const id = unique(prefix, ids); ids.add(id);
    const node = createNode(id, name, 'BEHAVIOR', { x: x + offset, y });
    node.behavior = behavior;
    node.nodeType = 'flow';
    node.properties = { ...properties };
    node.propertyTypes = Object.fromEntries(Object.keys(properties).map(key => [key, 'string']));
    nodeEntries.push({ node, index: graph.nodes.length + nodeEntries.length });
    return node;
  };
  const addEdge = (source, target, outcome = 'continue') => {
    const id = unique('register-edge', edgeIds); edgeIds.add(id);
    edgeEntries.push({ edge: createEdge(id, source, target, outcome), index: graph.edges.length + edgeEntries.length });
  };

  let primary;
  if (presetId === 'register-set') {
    primary = addNode('set-register', `Set ${config.register}`, 'bigint-op', {
      operation: 'copy', left: `literal:${config.decimal}`, target: config.register,
    });
  } else if (presetId === 'register-increment') {
    primary = addNode('increment-register', `Increment ${config.register}`, 'bigint-op', {
      operation: 'add', left: `field:${config.register}`, right: 'literal:1', target: config.register,
    });
  } else if (presetId === 'register-zero-test') {
    primary = addNode('zero-test', `Test ${config.register} = 0`, 'bigint-op', {
      operation: 'equal', left: `field:${config.register}`, right: 'literal:0', target: config.boolean,
    });
  } else if (presetId === 'register-decjz') {
    const test = addNode('decjz-test', `Test ${config.register} = 0`, 'bigint-op', {
      operation: 'equal', left: `field:${config.register}`, right: 'literal:0', target: config.boolean,
    });
    const decision = addNode('decjz-branch', `Branch on ${config.boolean}`, 'cel-decision', {
      expression: `payload.${config.boolean}`, trueOutcome: 'zero', falseOutcome: 'nonzero',
    }, 180);
    const decrement = addNode('decjz-decrement', `Decrement ${config.register}`, 'bigint-op', {
      operation: 'subtract', left: `field:${config.register}`, right: 'literal:1', target: config.register,
    }, 360);
    addEdge(test.id, decision.id);
    addEdge(decision.id, config.zeroTarget, 'zero');
    addEdge(decision.id, decrement.id, 'nonzero');
    addEdge(decrement.id, config.nonzeroTarget);
    primary = test;
  } else {
    throw new Error(`Unknown register-machine preset '${presetId}'`);
  }
  const commands = [insertNodesCommand(nodeEntries)];
  if (edgeEntries.length) commands.push(insertEdgesCommand(edgeEntries));
  const command = compositeCommand(commands, `Insert ${presetId}`);
  if (history) history.execute(graph, command);
  else applyCommand(graph, command);
  return { primary, nodes: nodeEntries.map(entry => entry.node), edges: edgeEntries.map(entry => entry.edge), command };
}

function normalizedConfiguration(presetId, configuration = {}, graph) {
  const register = requiredName(configuration.register, 'register');
  if (presetId === 'register-set') {
    const decimal = String(configuration.decimal ?? '');
    if (!/^(0|-?[1-9][0-9]*)$/.test(decimal)) throw new Error('Value must be a canonical decimal string');
    if (decimal.replace('-', '').length > 4096) throw new Error('Value exceeds the 4096-digit ceiling');
    return { register, decimal };
  }
  if (presetId === 'register-increment') return { register };
  const boolean = requiredName(configuration.boolean || `${register}IsZero`, 'boolean field');
  if (presetId !== 'register-decjz') return { register, boolean };
  const known = new Set(graph.nodes.map(node => node.id));
  const zeroTarget = String(configuration.zeroTarget || '');
  const nonzeroTarget = String(configuration.nonzeroTarget || '');
  if (!known.has(zeroTarget) || !known.has(nonzeroTarget)) throw new Error('DECJZ destinations must name existing nodes');
  return { register, boolean, zeroTarget, nonzeroTarget };
}

function requiredName(value, label) {
  const name = String(value || '');
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(name)) throw new Error(`${label} must be a portable top-level field name`);
  return name;
}

function unique(prefix, ids) {
  let index = 1;
  while (ids.has(`${prefix}-${index}`)) index += 1;
  return `${prefix}-${index}`;
}
