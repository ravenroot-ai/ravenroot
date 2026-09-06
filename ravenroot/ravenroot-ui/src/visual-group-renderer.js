import { projectVisualGroups } from './visual-group-projection.js';
import { createVisualGroupTransition } from './visual-group-transition.js';

const HIDDEN = 'rr-visual-hidden';
const MEMBER = 'rr-visual-member';

/** Incremental Cytoscape presentation, with canonical elements stationary behind ghost glyphs. */
export function createVisualGroupRenderer({ cy, isCurrent = () => true,
  onSelection = () => {}, onUpdate = () => {}, transitionOptions = {} } = {}) {
  let projection = null;
  let input = null;
  let positions = new Map();
  let ownedIds = new Set();
  let destroyed = false;
  let suspended = false;
  let selected = [];
  let focused = null;
  const live = () => !destroyed && !cy.destroyed() && isCurrent();
  const real = id => cy.getElementById(id);
  const transition = createVisualGroupTransition({ ...transitionOptions, isCurrent: live, paint });

  function styles() {
    cy.style().selector(`.${HIDDEN}`).style({ display: 'none', events: 'no' })
      .selector(`node.${MEMBER}`).style({ 'border-style': 'double' })
      .update();
  }

  function upsert(elements) {
    const keep = new Set(elements.map(element => element.data.id));
    for (const id of ownedIds) if (!keep.has(id)) real(id).remove();
    // Node insertion precedes edges; no frame contains an edge without both representatives.
    for (const definition of elements) {
      let element = real(definition.data.id);
      if (element.empty()) {
        const { style: _style, ...record } = definition;
        element = cy.add(record);
      }
      else {
        if (element.isEdge() && (element.data('source') !== definition.data.source
          || element.data('target') !== definition.data.target)) {
          element.move({ source: definition.data.source, target: definition.data.target });
        }
        element.data(definition.data);
        if (definition.position) element.position(definition.position);
      }
      element.style(definition.style || {});
      if (definition.grabbable === false) element.ungrabify();
      if (definition.selectable === false) element.unselectify();
    }
    ownedIds = keep;
  }

  function groupElement(group, role, amount) {
    const header = role === 'header';
    const id = header ? group.headerId : group.summaryId;
    const fontSize = Number.parseFloat(real(group.anchorNodeId).style('font-size')) || 20;
    const characters = [...group.name];
    const displayName = characters.length > 28 ? `${characters.slice(0, 27).join('')}…` : group.name;
    const wrapAt = displayName.length <= 14 ? -1 : Math.max(displayName.lastIndexOf(' ', 14), 0) || 14;
    const firstLine = wrapAt < 0 ? displayName : displayName.slice(0, wrapAt).trim();
    const remaining = wrapAt < 0 ? '' : displayName.slice(wrapAt).trim();
    const secondLine = [...remaining].length > 14 ? `${[...remaining].slice(0, 13).join('')}…` : remaining;
    const wrappedName = [firstLine, secondLine].filter(Boolean).join('\n');
    const label = header ? `▦ ${displayName} · ${group.memberNodeIds.length}`
      : `▦\n${wrappedName}\n${group.memberNodeIds.length} members`;
    return {
      data: { id, name: group.name, label, nodeType: 'visual-group', rrVisualGroup: group.id,
        rrVisualRole: role, memberCount: group.memberNodeIds.length, nw: header ? 190 : 100,
        nh: header ? 24 : 80 },
      position: { x: group.anchorPosition.x,
        y: group.anchorPosition.y - (header ? group.anchorPosition.height / 2 + 30 : 0) },
      grabbable: !header,
      style: { width: header ? 190 : 100, height: header ? 24 : 80, padding: header ? 3 : 8,
        shape: 'roundrectangle', label, 'text-wrap': header ? 'ellipsis' : 'wrap', 'text-max-width': header ? 184 : 96,
        'text-overflow-wrap': 'whitespace', 'font-size': Math.min(fontSize, header ? 12 : 16),
        'text-valign': 'center', 'text-halign': 'center', 'font-weight': 600,
        'background-color': real(group.anchorNodeId).style('background-color') || '#edf4ff',
        'background-image': 'none', 'border-color': '#526780', 'border-width': header ? 1 : 3,
        'border-style': 'double', color: real(group.anchorNodeId).style('color') || '#24354b',
        opacity: header ? 1 - amount : amount, 'transition-property': 'none',
        events: (header ? amount === 0 : amount === 1) ? 'yes' : 'no',
      },
    };
  }

  function paint(values, { final }) {
    if (!live() || suspended || !projection || !input) return;
    const groupMap = new Map(projection.groups.map(group => [group.id, group]));
    const elements = [];
    const represented = new Map();
    const hiddenNodes = new Set();
    const hiddenEdges = new Set();
    // Expose exact provenance for transient ghosts as well as settled representatives.
    for (const group of projection.groups) {
      const amount = values.get(group.id) ?? Number(group.collapsed);
      if (amount > 0) elements.push(groupElement(group, 'summary', amount));
      if (amount < 1) elements.push(groupElement(group, 'header', amount));
      for (const id of group.memberNodeIds) {
        if (amount === 0) { represented.set(id, id); continue; }
        hiddenNodes.add(id);
        if (amount === 1) { represented.set(id, group.summaryId); continue; }
        const original = real(id);
        const geometry = positions.get(id);
        if (!geometry || original.empty()) continue;
        const ghostId = projection.identities.get('ghost', id);
        represented.set(id, ghostId);
        projection.syntheticIds.add(ghostId);
        projection.realNodeIdsByVisibleId.set(ghostId, [id]);
        const inherited = {};
        for (const property of ['shape', 'background-color', 'background-image', 'background-fit',
          'border-color', 'border-width', 'border-style', 'label', 'color', 'font-size', 'font-family',
          'text-valign', 'text-halign', 'text-margin-x', 'text-margin-y', 'text-wrap', 'text-max-width',
          'padding']) inherited[property] = original.style(property);
        elements.push({ data: { ...original.data(), id: ghostId, rrVisualGroup: group.id,
          rrVisualRole: 'ghost', rrOriginalNodeId: id },
        position: { x: geometry.x + (group.anchorPosition.x - geometry.x) * amount,
          y: geometry.y + (group.anchorPosition.y - geometry.y) * amount },
        selectable: false, grabbable: false,
        style: { ...inherited, width: geometry.width, height: geometry.height, opacity: 1 - amount,
          events: 'no', 'transition-property': 'none' } });
      }
    }
    for (const edge of input.graph.edges) {
      const source = represented.get(edge.source) ?? edge.source;
      const target = represented.get(edge.target) ?? edge.target;
      if (source === edge.source && target === edge.target) continue;
      hiddenEdges.add(edge.id);
      if (source === target) continue;
      const original = real(edge.id);
      if (original.empty()) continue;
      const id = projection.identities.get('edge', edge.id);
      projection.syntheticIds.add(id);
      projection.originalEdgeIdsByVisibleId.set(id, [edge.id]);
      const sourceGroup = projection.groupByNodeId.get(edge.source);
      const sameGroup = sourceGroup && sourceGroup === projection.groupByNodeId.get(edge.target);
      const inherited = {};
      for (const property of ['line-color', 'line-style', 'line-dash-pattern', 'width',
        'target-arrow-color', 'target-arrow-shape', 'source-arrow-color', 'source-arrow-shape',
        'label', 'color', 'font-size', 'text-rotation']) inherited[property] = original.style(property);
      elements.push({ data: { ...original.data(), id, source, target, rrVisualRole: 'edge',
        rrOriginalEdgeId: edge.id, originalEdgeIds: [edge.id] },
      grabbable: false,
      style: { ...inherited, 'curve-style': 'bezier', 'source-endpoint': 'outside-to-node',
        'target-endpoint': 'outside-to-node', 'control-point-step-size': 40,
        opacity: sameGroup ? 1 - (values.get(sourceGroup) ?? Number(groupMap.get(sourceGroup).collapsed)) : 1,
        'transition-property': 'none' } });
    }
    // Cytoscape's bundled bezier spacing grows with degree. Keep parallel projected lanes
    // inside a bounded corridor, so a 24-edge boundary does not create a canvas-sized fan.
    const lanes = new Map();
    for (const element of elements) if (element.data.rrVisualRole === 'edge') {
      const key = JSON.stringify([element.data.source, element.data.target]);
      if (!lanes.has(key)) lanes.set(key, []);
      lanes.get(key).push(element);
    }
    for (const edges of lanes.values()) edges.forEach((element, index) => {
      element.style['curve-style'] = 'unbundled-bezier';
      element.style['control-point-distances'] = edges.length === 1 ? 20 : -32 + 64*index/(edges.length-1);
      element.style['control-point-weights'] = .5;
    });
    cy.batch(() => {
      cy.elements().removeClass(HIDDEN).removeClass(MEMBER);
      for (const group of projection.groups) for (const id of group.memberNodeIds) real(id).addClass(MEMBER);
      for (const id of hiddenNodes) real(id).unselect().addClass(HIDDEN);
      for (const id of hiddenEdges) real(id).unselect().addClass(HIDDEN);
      upsert(elements);
    });
    // Resolve deferred class styles before synchronous selection restoration. Cytoscape's
    // derived visible() cache can otherwise retain the previous hidden state until its paint.
    if (final) cy.nodes().forEach(node => node.style('display'));
    if (final) restoreSelection();
    onUpdate(projection, { final });
  }

  function visibleSelection(id) {
    if (projection.representativeByNodeId.has(id)) return projection.representativeByNodeId.get(id);
    const group = projection.groups.find(item => item.summaryId === id || item.headerId === id);
    if (group) {
      if (group.collapsed) return group.summaryId;
      const remembered = input.state?.[group.id]?.lastSelectedNodeIds?.filter(member => group.memberNodeIds.includes(member));
      return remembered?.length ? remembered : group.headerId;
    }
    const edge = projection.edges.find(item => item.originalEdgeIds.includes(id));
    return edge?.id ?? id;
  }
  function restoreSelection() {
    const ids = [...new Set(selected.flatMap(visibleSelection))].filter(id => real(id).nonempty() && real(id).visible());
    cy.batch(() => { cy.$(':selected').unselect(); ids.forEach(id => real(id).select()); });
    const focus = focused ? [visibleSelection(focused)].flat()[0] : ids[0] ?? null;
    onSelection(ids, { focus });
  }

  function removePresentation() {
    if (cy.destroyed()) return;
    cy.batch(() => {
      for (const id of ownedIds) real(id).remove();
      ownedIds = new Set();
      cy.elements().removeClass(HIDDEN).removeClass(MEMBER);
    });
  }

  return {
    setGroups(next) {
      if (!live()) return;
      suspended = false;
      input = next;
      selected = [...(next.selection ?? cy.$(':selected').map(element => element.id()))];
      focused = next.focus ?? null;
      positions = new Map(next.graph.nodes.map(node => {
        const rendered = real(node.id);
        return [node.id, rendered.nonempty()
          ? { ...rendered.position(), width: rendered.width(), height: rendered.height() }
          : { x: node.ox, y: node.oy, width: node.ow, height: node.oh }];
      }));
      projection = projectVisualGroups({ ...next, positions });
      styles();
      const previous = transition.values;
      // Creating a collapsed group emerges from its currently expanded real members.
      for (const group of projection.groups) if (!previous.has(group.id)) previous.set(group.id, 0);
      transition.seed(previous);
      transition.request(projection.groups.map(group => [group.id, Number(group.collapsed)]), { animate: next.animate === true });
      return projection;
    },
    finish() { transition.finish(); },
    suspend() { transition.cancel(); suspended = true; removePresentation(); },
    destroy() { if (destroyed) return; transition.destroy(); removePresentation(); destroyed = true; },
    get projection() { return projection; },
    get isAnimating() { return transition.active; },
  };
}
