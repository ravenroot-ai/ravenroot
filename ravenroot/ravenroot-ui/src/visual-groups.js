import { uiMessage, uiErrorMessage, renderUiMessage, withUiMessage, uiMessageOf } from './ui-message.js';
import { uiText } from './ui-text.js';
import { productError } from './product-error.js';
// Visual definitions describe membership only. Real graph elements and renderer coordinates remain
// owned by the canonical graph and the document presentation respectively.
export const VISUAL_GROUPS_PROPERTY = 'ravenroot.ui.visualGroups';
export const VISUAL_GROUP_LIMITS = Object.freeze({ bytes: 1024 * 1024, groups: 2000, name: 160, id: 512 });

export function validateVisualGroups(value, graph) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw productError("workspace.error.visual.groups.expected.a.visual.group.object", new Error('Expected a visual group object'));
  if (value.version !== 1) throw productError("workspace.error.visual.groups.unsupported.visual.group.version", new Error('Unsupported visual group version'));
  if (!Array.isArray(value.groups) || value.groups.length > VISUAL_GROUP_LIMITS.groups) throw productError("workspace.error.visual.groups.too.many.visual.groups.or.missing.groups", new Error('Too many visual groups or missing groups'));
  const real = new Set((graph?.nodes || []).map(node => node.id));
  const ids = new Set();
  const members = new Set();
  return value.groups.map(group => {
    if (!group || typeof group.id !== 'string' || !group.id.trim() || group.id.length > VISUAL_GROUP_LIMITS.id
        || ids.has(group.id)) throw productError("workspace.error.visual.groups.visual.group.ids.must.be.unique.bounded.strings", new Error('Visual group IDs must be unique, bounded strings'));
    ids.add(group.id);
    if (typeof group.name !== 'string' || !group.name.trim() || group.name.length > VISUAL_GROUP_LIMITS.name) throw productError("workspace.error.visual.groups.a.visual.group.needs.a.name.of.1.160.characters", new Error('A visual group needs a name of 1–160 characters'));
    if (!Array.isArray(group.memberNodeIds) || group.memberNodeIds.length < 2
        || group.memberNodeIds.length > real.size) throw productError("workspace.error.visual.groups.select.at.least.two.real.nodes.for.a.visual.group", new Error('Select at least two real nodes for a visual group'));
    for (const id of group.memberNodeIds) {
      if (typeof id !== 'string' || !real.has(id)) throw productError("workspace.error.visual.groups.a.visual.group.refers.to.an.unknown.node", new Error('A visual group refers to an unknown node'));
      if (members.has(id)) throw productError("workspace.error.visual.groups.visual.groups.cannot.overlap.or.repeat.members", new Error('Visual groups cannot overlap or repeat members'));
      members.add(id);
    }
    if (!group.memberNodeIds.includes(group.anchorNodeId)) throw productError("workspace.error.visual.groups.the.visual.group.anchor.must.be.a.member", new Error('The visual group anchor must be a member'));
    if (typeof group.collapsed !== 'boolean') throw productError("workspace.error.visual.groups.the.visual.group.collapsed.value.must.be.boolean", new Error('The visual group collapsed value must be boolean'));
    return { id: group.id, name: group.name.trim(), memberNodeIds: [...group.memberNodeIds],
      anchorNodeId: group.anchorNodeId, collapsed: group.collapsed };
  });
}

export function readVisualGroups(graph) {
  const raw = graph?.graphProperties?.[VISUAL_GROUPS_PROPERTY];
  const issue = graph?._visualGroupsMetadata;
  const warning = (status, reason) => {
    const message = uiMessage('workspace.visualGroups.warning', { message: reason });
    return withUiMessage({ status, groups: [], warning: renderUiMessage(message, uiText) }, message);
  };
  if (issue?.invalid && issue.raw === raw) return warning('invalid', uiMessageOf(issue, issue.invalid));
  if (raw == null) return { status: 'none', groups: [], warning: '' };
  try {
    if (typeof raw !== 'string' || new TextEncoder().encode(raw).byteLength > VISUAL_GROUP_LIMITS.bytes) throw productError("workspace.error.visual.groups.visual.group.metadata.exceeds.the.supported.size", new Error('Visual group metadata exceeds the supported size'));
    const value = JSON.parse(raw);
    if (Number.isInteger(value?.version) && value.version > 1) {
      return warning('future', uiMessage('workspace.visualGroups.newerVersion'));
    }
    return { status: 'valid', groups: validateVisualGroups(value, graph), warning: '' };
  } catch (error) {
    return warning('invalid', uiErrorMessage(error));
  }
}

export function visualGroupsValue(groups, graph) {
  return JSON.stringify({ version: 1, groups: validateVisualGroups({ version: 1, groups }, graph) });
}

export function reconcileVisualGroupState(groups, previous = {}) {
  return Object.fromEntries(groups.map(group => {
    const saved = Object.hasOwn(previous || {}, group.id) ? previous[group.id] : null;
    const members = new Set(group.memberNodeIds);
    return [group.id, {
      collapsed: typeof saved?.collapsed === 'boolean' ? saved.collapsed : group.collapsed,
      anchorNodeId: members.has(saved?.anchorNodeId) ? saved.anchorNodeId : group.anchorNodeId,
      lastSelectedNodeIds: Array.isArray(saved?.lastSelectedNodeIds)
        ? saved.lastSelectedNodeIds.filter(id => members.has(id)) : [],
    }];
  }));
}

export function groupsAfterNodeRemoval(groups, removedIds) {
  const removed = new Set(removedIds);
  return groups.flatMap(group => {
    const memberNodeIds = group.memberNodeIds.filter(id => !removed.has(id));
    if (memberNodeIds.length < 2) return [];
    return [{ ...group, memberNodeIds, anchorNodeId: memberNodeIds.includes(group.anchorNodeId)
      ? group.anchorNodeId : [...memberNodeIds].sort()[0] }];
  });
}

export function graphWithVisualGroupPresentation(graph, state) {
  const metadata = readVisualGroups(graph);
  if (metadata.status !== 'valid') return graph;
  const current = reconcileVisualGroupState(metadata.groups, state);
  const groups = metadata.groups.map(group => ({ ...group, collapsed: current[group.id].collapsed,
    anchorNodeId: current[group.id].anchorNodeId }));
  return { ...graph, graphProperties: { ...graph.graphProperties,
    [VISUAL_GROUPS_PROPERTY]: visualGroupsValue(groups, graph) } };
}

// Read the owned property separately so malformed grouping never suppresses executable nodes.
// Other GraphML properties still pass the parser's normal strict ambiguity checks.
export function readVisualGroupMetadataElement(graphElement, keyDefinitions, namespace) {
  const definitions = Object.values(keyDefinitions).filter(key => key.name === VISUAL_GROUPS_PROPERTY
    && ['graph', 'all'].includes(key.scope));
  if (!definitions.length) return null;
  const keys = new Set(definitions.map(key => key.id));
  const values = Array.from(graphElement.children).filter(child => child.namespaceURI === namespace
    && child.localName === 'data' && keys.has(child.getAttribute('key')));
  const defaults = definitions.filter(key => key.defaultValue !== null);
  if (!values.length && !defaults.length && !definitions.some(key => key.defaultComplex)) return null;
  const raw = values[0]?.textContent ?? defaults[0]?.defaultValue ?? '';
  const key = definitions.length !== 1 || values.length > 1 ? 'workspace.visualGroups.ambiguous'
    : definitions[0].scope !== 'graph' || definitions[0].type !== 'string' ? 'workspace.visualGroups.graphString'
      : values.some(value => value.children.length) || definitions.some(key => key.defaultComplex)
        ? 'workspace.visualGroups.scalar' : null;
  // Keep the parser's original diagnostic field and attach presentation outside the wire shape.
  const invalid = key === 'workspace.visualGroups.ambiguous' ? 'Ambiguous visual group metadata'
    : key === 'workspace.visualGroups.graphString' ? 'Visual group metadata needs a graph string key'
      : key ? 'Visual group metadata must be scalar text' : '';
  return withUiMessage({ raw, invalid }, key ? uiMessage(key) : null);
}
