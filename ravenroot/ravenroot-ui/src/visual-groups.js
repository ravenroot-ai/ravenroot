// Visual definitions describe membership only. Real graph elements and renderer coordinates remain
// owned by the canonical graph and the document presentation respectively.
export const VISUAL_GROUPS_PROPERTY = 'ravenroot.ui.visualGroups';
export const VISUAL_GROUP_LIMITS = Object.freeze({ bytes: 1024 * 1024, groups: 2000, name: 160, id: 512 });

export function validateVisualGroups(value, graph) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected a visual group object');
  if (value.version !== 1) throw new Error('Unsupported visual group version');
  if (!Array.isArray(value.groups) || value.groups.length > VISUAL_GROUP_LIMITS.groups) throw new Error('Too many visual groups or missing groups');
  const real = new Set((graph?.nodes || []).map(node => node.id));
  const ids = new Set();
  const members = new Set();
  return value.groups.map(group => {
    if (!group || typeof group.id !== 'string' || !group.id.trim() || group.id.length > VISUAL_GROUP_LIMITS.id
        || ids.has(group.id)) throw new Error('Visual group IDs must be unique, bounded strings');
    ids.add(group.id);
    if (typeof group.name !== 'string' || !group.name.trim() || group.name.length > VISUAL_GROUP_LIMITS.name) throw new Error('A visual group needs a name of 1–160 characters');
    if (!Array.isArray(group.memberNodeIds) || group.memberNodeIds.length < 2
        || group.memberNodeIds.length > real.size) throw new Error('Select at least two real nodes for a visual group');
    for (const id of group.memberNodeIds) {
      if (typeof id !== 'string' || !real.has(id)) throw new Error('A visual group refers to an unknown node');
      if (members.has(id)) throw new Error('Visual groups cannot overlap or repeat members');
      members.add(id);
    }
    if (!group.memberNodeIds.includes(group.anchorNodeId)) throw new Error('The visual group anchor must be a member');
    if (typeof group.collapsed !== 'boolean') throw new Error('The visual group collapsed value must be boolean');
    return { id: group.id, name: group.name.trim(), memberNodeIds: [...group.memberNodeIds],
      anchorNodeId: group.anchorNodeId, collapsed: group.collapsed };
  });
}

export function readVisualGroups(graph) {
  const raw = graph?.graphProperties?.[VISUAL_GROUPS_PROPERTY];
  const issue = graph?._visualGroupsMetadata;
  const warning = message => `${message}. The complete graph is shown. Remove visual group metadata to repair this document.`;
  if (issue?.invalid && issue.raw === raw) return { status: 'invalid', groups: [], warning: warning(issue.invalid) };
  if (raw == null) return { status: 'none', groups: [], warning: '' };
  try {
    if (typeof raw !== 'string' || new TextEncoder().encode(raw).byteLength > VISUAL_GROUP_LIMITS.bytes) throw new Error('Visual group metadata exceeds the supported size');
    const value = JSON.parse(raw);
    if (Number.isInteger(value?.version) && value.version > 1) {
      return { status: 'future', groups: [], warning: warning('This visual group version is newer than this editor supports') };
    }
    return { status: 'valid', groups: validateVisualGroups(value, graph), warning: '' };
  } catch (error) {
    return { status: 'invalid', groups: [], warning: warning(error.message) };
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
  const invalid = definitions.length !== 1 || values.length > 1 ? 'Ambiguous visual group metadata'
    : definitions[0].scope !== 'graph' || definitions[0].type !== 'string' ? 'Visual group metadata needs a graph string key'
      : values.some(value => value.children.length) || definitions.some(key => key.defaultComplex)
        ? 'Visual group metadata must be scalar text' : '';
  return { raw, invalid };
}
