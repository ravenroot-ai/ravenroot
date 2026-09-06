/** A presentation projection. None of these objects belong in an executable graph. */
export function createVisualGroupIdentities(graph, groups = []) {
  const occupied = new Set([...(graph.nodes || []), ...(graph.edges || [])].map(item => String(item.id)));
  const identities = new Map();
  const allocate = (kind, id) => {
    const key = JSON.stringify([kind, String(id)]);
    if (identities.has(key)) return identities.get(key);
    // JSON is injective over UTF-16 strings, including lone surrogates, and every base ends in
    // ']'. A collision suffix cannot therefore equal another generated base. Allocation order
    // is immaterial and no sorting/full reindex is required for each presentation toggle.
    const base = `rr-visual:${key}`;
    let candidate = base;
    let suffix = 0;
    while (occupied.has(candidate)) candidate = `${base}~${++suffix}`;
    occupied.add(candidate);
    identities.set(key, candidate);
    return candidate;
  };
  // Allocate independently of presentation state and input ordering.
  groups.forEach(group => {
    allocate('summary', group.id);
    allocate('header', group.id);
  });
  (graph.nodes || []).forEach(node => allocate('ghost', node.id));
  (graph.edges || []).forEach(edge => allocate('edge', edge.id));
  return { get: allocate, ids: new Set(identities.values()) };
}

const finite = (value, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback;

export function visualGroupGeometry(node, positions = new Map()) {
  const position = positions instanceof Map ? positions.get(node.id) : positions?.[node.id];
  return {
    x: finite(position?.x, finite(node.ox)), y: finite(position?.y, finite(node.oy)),
    width: finite(position?.width ?? position?.w, finite(node.ow, 80)),
    height: finite(position?.height ?? position?.h, finite(node.oh, 80)),
  };
}

export function projectVisualGroups({ graph, groups = [], state = {}, positions = new Map() }) {
  const canonicalNodes = graph.nodes || [];
  const canonicalEdges = graph.edges || [];
  const nodeMap = new Map(canonicalNodes.map(node => [node.id, node]));
  const groupByNodeId = new Map();
  const seenGroups = new Set();
  // Fail open as a whole: ambiguity must never hide part of an executable graph.
  const valid = groups.every(group => {
    if (!group || typeof group.id !== 'string' || seenGroups.has(group.id)
      || !Array.isArray(group.memberNodeIds) || group.memberNodeIds.length < 2
      || !group.memberNodeIds.includes(group.anchorNodeId)) return false;
    seenGroups.add(group.id);
    return group.memberNodeIds.every(id => {
      if (!nodeMap.has(id) || groupByNodeId.has(id)) return false;
      groupByNodeId.set(id, group.id);
      return true;
    });
  });
  if (!valid) { groups = []; groupByNodeId.clear(); }
  const identities = createVisualGroupIdentities(graph, groups);
  const representativeByNodeId = new Map(canonicalNodes.map(node => [node.id, node.id]));
  const realNodeIdsByVisibleId = new Map();
  const originalEdgeIdsByVisibleId = new Map();
  const hiddenNodeIds = new Set();
  const hiddenEdgeIds = new Set();
  const syntheticIds = new Set();
  const projectedGroups = groups.map(definition => {
    const preference = Object.hasOwn(state, definition.id) ? state[definition.id] : null;
    const collapsed = preference?.collapsed ?? definition.collapsed ?? false;
    const anchorNodeId = definition.memberNodeIds.includes(preference?.anchorNodeId)
      ? preference.anchorNodeId : definition.anchorNodeId;
    const anchorPosition = visualGroupGeometry(nodeMap.get(anchorNodeId), positions);
    const summaryId = identities.get('summary', definition.id);
    const headerId = identities.get('header', definition.id);
    realNodeIdsByVisibleId.set(summaryId, [...definition.memberNodeIds]);
    realNodeIdsByVisibleId.set(headerId, [...definition.memberNodeIds]);
    syntheticIds.add(summaryId);
    syntheticIds.add(headerId);
    if (collapsed) definition.memberNodeIds.forEach(id => {
      representativeByNodeId.set(id, summaryId);
      hiddenNodeIds.add(id);
    });
    return { ...definition, collapsed, anchorNodeId, anchorPosition, summaryId, headerId };
  });
  const nodes = canonicalNodes.filter(node => !hiddenNodeIds.has(node.id)).map(node => {
    realNodeIdsByVisibleId.set(node.id, [node.id]);
    return { ...node, ...visualGroupGeometry(node, positions), realNodeId: node.id, synthetic: false };
  });
  projectedGroups.forEach(group => {
    nodes.push({ id: group.collapsed ? group.summaryId : group.headerId, name: group.name,
      groupId: group.id, memberNodeIds: [...group.memberNodeIds], synthetic: true,
      role: group.collapsed ? 'summary' : 'header',
      x: group.anchorPosition.x,
      y: group.anchorPosition.y - (group.collapsed ? 0 : group.anchorPosition.height / 2 + 30),
      width: group.collapsed ? 100 : 160, height: group.collapsed ? 80 : 24,
    });
  });
  const edges = [];
  canonicalEdges.forEach(edge => {
    const source = representativeByNodeId.get(edge.source);
    const target = representativeByNodeId.get(edge.target);
    if (source == null || target == null) return;
    const projected = source !== edge.source || target !== edge.target;
    if (projected) hiddenEdgeIds.add(edge.id);
    if (projected && source === target) return;
    const id = projected ? identities.get('edge', edge.id) : edge.id;
    if (projected) syntheticIds.add(id);
    originalEdgeIdsByVisibleId.set(id, [edge.id]);
    // No bundling: even unknown style-affecting properties retain their separate provenance.
    edges.push({ ...edge, id, source, target, originalEdgeIds: [edge.id], synthetic: projected });
  });
  return { nodes, edges, groups: projectedGroups, representativeByNodeId, realNodeIdsByVisibleId,
    originalEdgeIdsByVisibleId, syntheticIds, hiddenNodeIds, hiddenEdgeIds, groupByNodeId,
    identities, valid };
}
