export const FRONTEND_PLUGIN_SCHEMA = 'ravenroot.frontend-plugin/v1';
export const FRONTEND_PLUGIN_API_VERSION = '1.0';
export const FRONTEND_PRESENTATION_PROPERTY = 'ravenroot.frontendPresentation.v1';
export const FRONTEND_DRAWING_MODEL_PROPERTY = 'ravenroot.frontendDrawingModel.v1';

const PACKAGE_ID = /^[a-z0-9]+(?:[.-][a-z0-9]+)+$/;
const PROVIDER_ID = /^[a-z0-9]+(?:[._-][a-z0-9]+)*$/;
const MAPPING_ID = /^[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*$/;
const VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const SAFE_FILE = /^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$))[A-Za-z0-9._/-]+$/;
const CAPABILITIES = new Set([
  'directed-edges', 'self-loops', 'parallel-edges', 'opposite-edges', 'initial-marker',
  'accepting-marker', 'structured-labels', 'selection', 'operational-overlay', 'authoring',
]);

function plainObject(value) {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}

function exactKeys(value, allowed, label) {
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) throw new TypeError(`${label} contains unknown field '${key}'`);
  }
}

function requiredString(value, label, pattern = null) {
  if (typeof value !== 'string' || !value.trim()) throw new TypeError(`${label} must be a non-empty string`);
  if (pattern && !pattern.test(value)) throw new TypeError(`${label} has an invalid value`);
  return value;
}

function provider(value, kind) {
  if (!plainObject(value)) throw new TypeError(`${kind} provider must be an object`);
  exactKeys(value, new Set(['id', 'name', 'entry', 'capabilities', 'requires']), `${kind} provider`);
  const entry = requiredString(value.entry, `${kind} provider entry`, SAFE_FILE);
  const capabilities = Array.isArray(value.capabilities) ? value.capabilities.map(capability => {
    requiredString(capability, `${kind} capability`);
    if (!CAPABILITIES.has(capability)) throw new TypeError(`Unsupported ${kind} capability '${capability}'`);
    return capability;
  }) : [];
  const requires = Array.isArray(value.requires) ? value.requires.map(capability => {
    requiredString(capability, `${kind} required capability`);
    if (!CAPABILITIES.has(capability)) throw new TypeError(`Unsupported ${kind} required capability '${capability}'`);
    return capability;
  }) : [];
  return Object.freeze({
    id: requiredString(value.id, `${kind} provider id`, PROVIDER_ID),
    name: requiredString(value.name, `${kind} provider name`), entry,
    capabilities: Object.freeze([...new Set(capabilities)]), requires: Object.freeze([...new Set(requires)]),
  });
}

export function providerCompatibility(layout, renderer) {
  const layoutCapabilities = new Set(layout?.capabilities || []);
  const rendererCapabilities = new Set(renderer?.capabilities || []);
  const missingForRenderer = (renderer?.requires || []).filter(capability => !layoutCapabilities.has(capability));
  const missingForLayout = (layout?.requires || []).filter(capability => !rendererCapabilities.has(capability));
  return Object.freeze({ compatible: !missingForRenderer.length && !missingForLayout.length,
    missingForRenderer: Object.freeze(missingForRenderer), missingForLayout: Object.freeze(missingForLayout) });
}

export function validateFrontendPluginManifest(input) {
  if (!plainObject(input)) throw new TypeError('Frontend plugin manifest must be an object');
  exactKeys(input, new Set([
    'schema', 'id', 'name', 'version', 'apiVersion', 'description', 'permissions',
    'layouts', 'renderers', 'drawingModels', 'integrity',
  ]), 'Frontend plugin manifest');
  if (input.schema !== FRONTEND_PLUGIN_SCHEMA) throw new TypeError(`Unsupported frontend plugin schema '${input.schema}'`);
  if (input.apiVersion !== FRONTEND_PLUGIN_API_VERSION) {
    throw new TypeError(`Frontend plugin API ${input.apiVersion || '(missing)'} is incompatible with ${FRONTEND_PLUGIN_API_VERSION}`);
  }
  if (!Array.isArray(input.permissions) || input.permissions.length) {
    throw new TypeError('Frontend plugin v1 accepts only an empty permissions array');
  }
  const layouts = (input.layouts || []).map(item => provider(item, 'layout'));
  const renderers = (input.renderers || []).map(item => provider(item, 'renderer'));
  if (!layouts.length && !renderers.length) throw new TypeError('Frontend plugin must provide a layout or renderer');
  const layoutIds = new Set(layouts.map(item => item.id));
  const rendererIds = new Set(renderers.map(item => item.id));
  const models = (input.drawingModels || []).map(value => {
    if (!plainObject(value)) throw new TypeError('Drawing model must be an object');
    exactKeys(value, new Set(['id', 'name', 'layout', 'renderer']), 'Drawing model');
    const model = {
      id: requiredString(value.id, 'Drawing model id', PROVIDER_ID),
      name: requiredString(value.name, 'Drawing model name'),
      layout: requiredString(value.layout, 'Drawing model layout id', PROVIDER_ID),
      renderer: requiredString(value.renderer, 'Drawing model renderer id', PROVIDER_ID),
    };
    if (!layoutIds.has(model.layout)) throw new TypeError(`Drawing model references unknown layout '${model.layout}'`);
    if (!rendererIds.has(model.renderer)) throw new TypeError(`Drawing model references unknown renderer '${model.renderer}'`);
    return Object.freeze(model);
  });
  const integrity = plainObject(input.integrity) ? Object.fromEntries(Object.entries(input.integrity).map(([path, digest]) => {
    requiredString(path, 'Integrity path', SAFE_FILE);
    if (!/^sha256-[A-Za-z0-9+/]{43}=$/.test(digest)) throw new TypeError(`Integrity digest for '${path}' must be sha256 base64`);
    return [path, digest];
  })) : {};
  const allIds = [...layouts, ...renderers, ...models].map(item => item.id);
  if (new Set(allIds).size !== allIds.length) throw new TypeError('Provider and drawing model ids must be unique within a package');
  return Object.freeze({
    schema: input.schema,
    id: requiredString(input.id, 'Frontend plugin id', PACKAGE_ID),
    name: requiredString(input.name, 'Frontend plugin name'),
    version: requiredString(input.version, 'Frontend plugin version', VERSION),
    apiVersion: input.apiVersion,
    description: typeof input.description === 'string' ? input.description : '',
    permissions: Object.freeze([]), layouts: Object.freeze(layouts), renderers: Object.freeze(renderers),
    drawingModels: Object.freeze(models), integrity: Object.freeze(integrity),
  });
}

function stringArray(value, label) {
  if (!Array.isArray(value) || !value.length || value.some(item => typeof item !== 'string' || !item)) {
    throw new TypeError(`${label} must be a non-empty string array`);
  }
  return [...value];
}

export function validatePresentationMapping(input, graph) {
  if (!plainObject(input) || input.schema !== 'ravenroot.presentation-mapping/v1') {
    throw new TypeError('Presentation mapping must use ravenroot.presentation-mapping/v1');
  }
  exactKeys(input, new Set(['schema', 'states', 'transitions', 'positions']), 'Presentation mapping');
  if (!Array.isArray(input.states) || !Array.isArray(input.transitions)) {
    throw new TypeError('Presentation mapping needs states and transitions arrays');
  }
  const nodeIds = new Set((graph?.nodes || []).map(node => node.id));
  const edgeIds = new Set((graph?.edges || []).map(edge => edge.id));
  const stateIds = new Set();
  const states = input.states.map(value => {
    if (!plainObject(value)) throw new TypeError('Mapped state must be an object');
    exactKeys(value, new Set(['id', 'label', 'nodeId', 'initial', 'accepting']), 'Mapped state');
    const state = {
      id: requiredString(value.id, 'Mapped state id', MAPPING_ID),
      label: requiredString(value.label, 'Mapped state label'),
      nodeId: requiredString(value.nodeId, 'Mapped state nodeId'),
      initial: Boolean(value.initial), accepting: Boolean(value.accepting),
    };
    if (stateIds.has(state.id)) throw new TypeError(`Duplicate mapped state '${state.id}'`);
    if (!nodeIds.has(state.nodeId)) throw new TypeError(`Mapped state '${state.id}' references unknown node '${state.nodeId}'`);
    stateIds.add(state.id);
    return Object.freeze(state);
  });
  if (!states.some(state => state.initial)) throw new TypeError('Presentation mapping needs an initial state');
  const transitionIds = new Set();
  const transitions = input.transitions.map(value => {
    if (!plainObject(value)) throw new TypeError('Mapped transition must be an object');
    exactKeys(value, new Set(['id', 'source', 'target', 'label', 'edgePath']), 'Mapped transition');
    const transition = {
      id: requiredString(value.id, 'Mapped transition id', MAPPING_ID),
      source: requiredString(value.source, 'Mapped transition source', MAPPING_ID),
      target: requiredString(value.target, 'Mapped transition target', MAPPING_ID),
      label: requiredString(value.label, 'Mapped transition label'),
      edgePath: stringArray(value.edgePath, `Mapped transition '${value.id}' edgePath`),
    };
    if (transitionIds.has(transition.id)) throw new TypeError(`Duplicate mapped transition '${transition.id}'`);
    if (!stateIds.has(transition.source) || !stateIds.has(transition.target)) {
      throw new TypeError(`Mapped transition '${transition.id}' references unknown state`);
    }
    for (const edgeId of transition.edgePath) {
      if (!edgeIds.has(edgeId)) throw new TypeError(`Mapped transition '${transition.id}' references unknown edge '${edgeId}'`);
    }
    const first = graph.edges.find(edge => edge.id === transition.edgePath[0]);
    const last = graph.edges.find(edge => edge.id === transition.edgePath.at(-1));
    const sourceNode = states.find(state => state.id === transition.source)?.nodeId;
    const targetNode = states.find(state => state.id === transition.target)?.nodeId;
    if (first?.source !== sourceNode || last?.target !== targetNode) {
      throw new TypeError(`Mapped transition '${transition.id}' path endpoints do not match its states`);
    }
    for (let index = 1; index < transition.edgePath.length; index += 1) {
      const previous = graph.edges.find(edge => edge.id === transition.edgePath[index - 1]);
      const current = graph.edges.find(edge => edge.id === transition.edgePath[index]);
      if (previous?.target !== current?.source) throw new TypeError(`Mapped transition '${transition.id}' edgePath is not contiguous`);
    }
    transitionIds.add(transition.id);
    return Object.freeze(transition);
  });
  const positions = {};
  for (const [id, point] of Object.entries(input.positions || {})) {
    if (!stateIds.has(id) || !Number.isFinite(point?.x) || !Number.isFinite(point?.y)) {
      throw new TypeError(`Position for '${id}' is invalid`);
    }
    positions[id] = Object.freeze({ x: point.x, y: point.y });
  }
  return Object.freeze({ schema: input.schema, states: Object.freeze(states),
    transitions: Object.freeze(transitions), positions: Object.freeze(positions) });
}

export function readPresentationMapping(graph) {
  const value = graph?.graphProperties?.[FRONTEND_PRESENTATION_PROPERTY];
  if (!value) return null;
  return validatePresentationMapping(JSON.parse(value), graph);
}

export function presentationMappingValue(mapping, graph) {
  return JSON.stringify(validatePresentationMapping(mapping, graph));
}

export function drawingModelSnapshot(graph, mapping, operational = {}) {
  const valid = validatePresentationMapping(mapping, graph);
  const activeNodes = new Set(operational.activeNodeIds || []);
  const activeEdges = new Set(operational.activeEdgeIds || []);
  return Object.freeze({
    schema: 'ravenroot.drawing-model-snapshot/v1',
    states: valid.states.map(state => Object.freeze({ ...state, active: activeNodes.has(state.nodeId) })),
    transitions: valid.transitions.map(transition => Object.freeze({ ...transition,
      active: transition.edgePath.some(edgeId => activeEdges.has(edgeId)) })),
    positions: valid.positions,
    evidence: Object.freeze({
      activeNodeIds: Object.freeze([...activeNodes]), activeEdgeIds: Object.freeze([...activeEdges]),
      description: 'Highlights report mapped workflow evidence; they do not assert formal automaton acceptance.',
    }),
  });
}

export function validateLayoutResult(input, snapshot) {
  if (!plainObject(input) || input.schema !== 'ravenroot.layout-result/v1' || !plainObject(input.positions)) {
    throw new TypeError('Layout provider returned an invalid v1 result');
  }
  const positions = {};
  for (const state of snapshot.states || []) {
    const point = input.positions[state.id];
    if (!Number.isFinite(point?.x) || !Number.isFinite(point?.y)) {
      throw new TypeError(`Layout provider omitted a finite position for '${state.id}'`);
    }
    positions[state.id] = Object.freeze({ x: point.x, y: point.y });
  }
  return Object.freeze({ schema: input.schema, positions: Object.freeze(positions) });
}

export function validateScene(input) {
  if (!plainObject(input) || input.schema !== 'ravenroot.scene/v1' || !Array.isArray(input.elements)) {
    throw new TypeError('Renderer returned an invalid v1 scene');
  }
  if (input.elements.length > 5000) throw new TypeError('Renderer scene exceeds the 5000 element limit');
  const allowedTypes = new Set(['circle', 'path', 'text', 'line']);
  const elements = input.elements.map(element => {
    if (!plainObject(element) || !allowedTypes.has(element.type)) throw new TypeError('Renderer scene contains an invalid element');
    const safe = { type: element.type };
    for (const [key, value] of Object.entries(element)) {
      if (key === 'type') continue;
      if (!['id', 'targetId', 'role', 'label', 'text', 'd', 'className',
        'x', 'y', 'x1', 'y1', 'x2', 'y2', 'r'].includes(key)) {
        throw new TypeError(`Renderer scene element contains unknown field '${key}'`);
      }
      if (['x', 'y', 'x1', 'y1', 'x2', 'y2', 'r'].includes(key)) {
        if (!Number.isFinite(value)) throw new TypeError(`Renderer scene field '${key}' must be finite`);
        safe[key] = value;
      } else if (typeof value !== 'string') throw new TypeError(`Renderer scene field '${key}' must be a string`);
      else {
        if ((key === 'id' || key === 'targetId' || key === 'role') && !/^[A-Za-z0-9._-]+$/.test(value)) {
          throw new TypeError(`Renderer scene field '${key}' contains unsafe characters`);
        }
        if (key === 'className' && !/^[A-Za-z0-9 _-]*$/.test(value)) {
          throw new TypeError('Renderer scene className contains unsafe characters');
        }
        if (key === 'd' && !/^[MmLlHhVvCcSsQqTtAaZz0-9., +\-]*$/.test(value)) {
          throw new TypeError('Renderer scene path contains unsupported data');
        }
        safe[key] = value;
      }
    }
    return Object.freeze(safe);
  });
  return Object.freeze({ schema: input.schema,
    width: Number.isFinite(input.width) ? Math.max(1, Math.min(10000, input.width)) : 800,
    height: Number.isFinite(input.height) ? Math.max(1, Math.min(10000, input.height)) : 500,
    elements: Object.freeze(elements) });
}
