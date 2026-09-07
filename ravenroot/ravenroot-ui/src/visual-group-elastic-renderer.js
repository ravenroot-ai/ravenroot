import * as d3 from 'd3';
import { projectVisualGroups } from './visual-group-projection.js';
import { createVisualGroupTransition } from './visual-group-transition.js';

/** A display layer over the original Monitoring simulation, never a replacement force graph. */
export function createElasticVisualGroupRenderer({ zoomGroup, nodes, links, simulation,
  nodeSelection, nodeLabelSelection, edgeSelection, edgeLabelSelection,
  isLive, marker, nodeText, edgeLabel, onViewportChange }) {
  const layer = zoomGroup.append('g').attr('class', 'd3-visual-groups');
  const edgeLayer = layer.append('g').attr('class', 'd3-visual-edges');
  const ghostLayer = layer.append('g').attr('class', 'd3-visual-members');
  const groupLayer = layer.append('g').attr('class', 'd3-visual-headers');
  const canonicalNodes = new Map(nodes.map(node => [node.id, node]));
  let projection = null;
  let input = null;
  let positions = new Map();
  let visible = { nodes, links };
  let destroyed = false;
  let frozen = null;
  let focusedGroup = null;
  let selectedGroup = null;
  const transition = createVisualGroupTransition({ isCurrent: () => !destroyed && isLive(), paint });
  const point = id => transition.active ? positions.get(id) : canonicalNodes.get(id);
  const endpointId = endpoint => typeof endpoint === 'object' ? endpoint.id : endpoint;
  const graph = { nodes, edges: links.map(link => ({ ...link,
    source: endpointId(link.source), target: endpointId(link.target) })) };
  function freezeGeometry() {
    if (frozen) return;
    frozen = nodes.map(node => ({ node, fx: node.fx, fy: node.fy, vx: node.vx, vy: node.vy,
      hasFx: Object.hasOwn(node, 'fx'), hasFy: Object.hasOwn(node, 'fy') }));
    nodes.forEach(node => { node.fx = node.x; node.fy = node.y; });
  }
  function releaseGeometry() {
    if (!frozen) return;
    frozen.forEach(({ node, fx, fy, vx, vy, hasFx, hasFy }) => {
      if (hasFx) node.fx = fx; else delete node.fx;
      if (hasFy) node.fy = fy; else delete node.fy;
      node.vx = vx; node.vy = vy;
    });
    frozen = null;
  }
  const path = link => {
    const source = link.source;
    const target = link.target;
    const distance = Math.hypot(target.x - source.x, target.y - source.y);
    if (distance < .001) {
      const radius = (source.r || 14) + 16;
      return `M${source.x},${source.y}C${source.x-radius*2},${source.y-radius*3} ${source.x+radius*2},${source.y-radius*3} ${source.x+2},${source.y}`;
    }
    const ratio = Math.max(0, distance - (target.r || 14) - 2) / distance;
    return `M${source.x},${source.y}A${distance},${distance} 0 0,1 ${source.x+(target.x-source.x)*ratio},${source.y+(target.y-source.y)*ratio}`;
  };

  function paint(values, { final } = {}) {
    if (destroyed || !isLive() || !projection) return;
    const representatives = new Map(nodes.map(node => [node.id, node]));
    const hidden = new Set();
    const ghosts = [];
    const badges = [];
    for (const group of projection.groups) {
      const amount = values.get(group.id) ?? Number(group.collapsed);
      const anchor = point(group.anchorNodeId);
      if (amount > 0) badges.push({ ...group, groupId: group.id, id: group.summaryId, role: 'summary', x: anchor.x,
        y: anchor.y, r: 24, opacity: amount, action: 'Expand', amount });
      if (amount < 1) badges.push({ ...group, groupId: group.id, id: group.headerId, role: 'header', x: anchor.x,
        y: anchor.y - (anchor.r || 14) - 30, r: 12, opacity: 1-amount, action: 'Collapse', amount });
      for (const id of group.memberNodeIds) {
        if (amount === 0) continue;
        hidden.add(id);
        if (amount === 1) {
          representatives.set(id, { id: group.summaryId, x: anchor.x, y: anchor.y, r: 24 });
        } else {
          const node = canonicalNodes.get(id);
          const position = point(id);
          const ghost = { ...node, id: projection.identities.get('ghost', id),
            x: position.x + (anchor.x-position.x)*amount,
            y: position.y + (anchor.y-position.y)*amount, opacity: 1-amount };
          ghosts.push(ghost);
          representatives.set(id, ghost);
          projection.syntheticIds.add(ghost.id);
          projection.realNodeIdsByVisibleId.set(ghost.id, [id]);
        }
      }
    }
    const projectedEdges = [];
    const hiddenEdges = new Set();
    for (const link of links) {
      const sourceId = endpointId(link.source);
      const targetId = endpointId(link.target);
      if (!hidden.has(sourceId) && !hidden.has(targetId)) continue;
      hiddenEdges.add(link.id);
      const source = representatives.get(sourceId);
      const target = representatives.get(targetId);
      if (source.id === target.id) continue;
      const groupId = projection.groupByNodeId.get(sourceId);
      const internal = groupId && groupId === projection.groupByNodeId.get(targetId);
      const id = projection.identities.get('edge', link.id);
      projection.syntheticIds.add(id);
      projection.originalEdgeIdsByVisibleId.set(id, [link.id]);
      projectedEdges.push({ ...link, id, source, target, originalEdgeIds: [link.id],
        opacity: internal ? 1-(values.get(groupId) || 0) : 1 });
    }
    nodeSelection.attr('display', node => hidden.has(node.id) ? 'none' : null)
      .attr('aria-hidden', node => hidden.has(node.id) ? 'true' : null);
    nodeLabelSelection.attr('display', node => hidden.has(node.id) ? 'none' : null);
    edgeSelection.attr('display', edge => hiddenEdges.has(edge.id) ? 'none' : null);
    edgeLabelSelection.attr('display', edge => hiddenEdges.has(edge.id) ? 'none' : null);
    const paths = edgeLayer.selectAll('path').data(projectedEdges, edge => edge.id).join('path')
      .attr('d', path).attr('fill', 'none').attr('stroke', edge => edge.color)
      .attr('stroke-width', edge => edge.baseWidth || 1.8).attr('opacity', edge => edge.opacity)
      .attr('marker-end', edge => `url(#${marker(edge.color)})`);
    paths.attr('data-original-edge-id', edge => edge.originalEdgeIds[0]);
    paths.attr('stroke-dasharray', edge => edge.lineStyle === 'dashed' ? (edge.dashPattern || '6 4') : null)
      .attr('stroke-width', edge => (edge.baseWidth || 1.8) * (edge.parallel ? 1.6 : 1));
    edgeLayer.selectAll('text').data(projectedEdges, edge => edge.id).join('text')
      .attr('x', edge => (edge.source.x+edge.target.x)/2)
      .attr('y', edge => (edge.source.y+edge.target.y)/2-8)
      .attr('text-anchor', 'middle').attr('font-size', 10).attr('fill', edgeLabel)
      .attr('opacity', edge => edge.opacity).text(edge => edge.label || '');
    ghostLayer.selectAll('circle').data(ghosts, node => node.id).join('circle')
      .attr('cx', node => node.x).attr('cy', node => node.y).attr('r', node => node.r)
      .attr('fill', node => node.color).attr('stroke', node => node.stroke || '#8c959f')
      .attr('stroke-width', node => node.strokeWidth || 1.5).attr('opacity', node => node.opacity)
      .attr('pointer-events', 'none').attr('aria-hidden', 'true');
    ghostLayer.selectAll('text').data(ghosts, node => node.id).join('text')
      .attr('x', node => node.x).attr('y', node => node.y+node.r+5)
      .attr('text-anchor', 'middle').attr('dominant-baseline', 'hanging').attr('font-size', 14)
      .attr('fill', nodeText).attr('opacity', node => node.opacity).text(node => node.label);
    const badge = groupLayer.selectAll('g').data(badges, group => group.id).join(enter => {
      const item = enter.append('g'); item.append('rect'); item.append('text'); item.append('title'); return item;
    }).attr('data-visual-group-id', group => group.id)
      .attr('transform', group => `translate(${group.x},${group.y})`)
      .attr('opacity', group => group.opacity).attr('role', 'button')
      .attr('data-selected', group => String(group.groupId === selectedGroup))
      .attr('aria-expanded', group => String(group.role === 'header'))
      .attr('tabindex', group => final && group.opacity === 1 ? 0 : -1)
      .attr('aria-label', group => `${group.action} visual group ${group.name}, ${group.memberNodeIds.length} members`)
      .style('cursor', 'pointer');
    const activate = group => {
      const collapsed = group.role === 'header';
      focusedGroup = group.groupId;
      selectedGroup = group.groupId;
      input.onSelection?.([group.id], { focus: group.id, groupId: group.groupId });
      input.onToggle?.(group.groupId, collapsed);
    };
    badge.on('click.group', (_, group) => activate(group))
      .on('focus.group', (_, group) => {
        focusedGroup = group.groupId;
        selectedGroup = group.groupId;
        badge.attr('data-selected', item => String(item.groupId === selectedGroup));
        input.onSelection?.([group.id], { focus: group.id, groupId: group.groupId });
      })
      .on('keydown.group', (event, group) => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(group); }
      });
    badge.select('rect').attr('x', group => group.role === 'header' ? -80 : -50)
      .attr('y', group => group.role === 'header' ? -12 : -30)
      .attr('width', group => group.role === 'header' ? 160 : 100)
      .attr('height', group => group.role === 'header' ? 24 : 60)
      .attr('rx', 7).attr('fill', '#edf4ff')
      .attr('stroke', group => group.groupId === selectedGroup ? '#086adb' : '#526780')
      .attr('stroke-width', group => group.groupId === selectedGroup ? 3 : 2);
    badge.select('text').attr('text-anchor', 'middle').attr('dominant-baseline', 'central')
      .attr('fill', '#24354b').attr('font-size', 12)
      .text(group => `▦ ${group.name.length > 19 ? `${group.name.slice(0, 18)}…` : group.name} · ${group.memberNodeIds.length}`);
    badge.select('title').text(group => `${group.name} — ${group.memberNodeIds.map(id => canonicalNodes.get(id)?.label || id).join(', ')}`);
    const observations = group => group.memberNodeIds.reduce((counts, id) => {
      const member = canonicalNodes.get(id);
      const state = member?.runtimeObserved ? member.runtimeState : 'unknown';
      counts[state] = (counts[state] || 0) + 1;
      return counts;
    }, {});
    badge.attr('data-member-observations', group => JSON.stringify(observations(group)));
    badge.filter(group => group.role === 'summary').select('text').each(function(group) {
      const counts = observations(group);
      const activity = ['active', 'failed', 'waiting'].filter(state => counts[state])
        .map(state => `${counts[state]} ${state}`).join(' · ');
      if (!activity) return;
      d3.select(this).attr('transform', 'translate(0,-8)').append('tspan')
        .attr('x', 0).attr('dy', 18).attr('font-size', 10).text(activity);
    });
    visible = {
      nodes: [...nodes.filter(node => !hidden.has(node.id)), ...ghosts, ...badges],
      links: [...links.filter(link => !hiddenEdges.has(link.id)), ...projectedEdges],
    };
    input.onUpdate?.(projection, { final });
    onViewportChange();
    if (final) releaseGeometry();
    if (final && focusedGroup) {
      const target = badge.filter(group => group.groupId === focusedGroup && group.opacity === 1).node();
      if (target && document.activeElement !== target) target.focus({ preventScroll: true });
      focusedGroup = null;
    }
  }
  return {
    setGroups(next) {
      if (destroyed || !isLive()) return;
      input = next;
      // Pin only during the transition. Keep the existing timer, alpha, topology and prior pin
      // ownership: a running simulation keeps running and a settled one is never restarted.
      if (next.animate === true) freezeGeometry();
      positions = new Map(nodes.map(node => [node.id, { x: node.x, y: node.y,
        r: node.r, width: node.r*2, height: node.r*2 }]));
      projection = projectVisualGroups({ graph, groups: next.groups, state: next.state, positions });
      const byIdentity = id => projection.groups.find(group => group.id === id);
      const byHandle = id => projection.groups.find(group => group.summaryId === id || group.headerId === id);
      const restoredSelection = byIdentity(next.selectedGroupId)
        || (next.selection || []).map(byHandle).find(Boolean);
      selectedGroup = restoredSelection?.id ?? null;
      const restoredFocus = byIdentity(next.focusGroupId) || byHandle(next.focus);
      if (restoredFocus) focusedGroup = restoredFocus.id;
      const previous = transition.values;
      projection.groups.forEach(group => { if (!previous.has(group.id)) previous.set(group.id, 0); });
      transition.seed(previous);
      transition.request(projection.groups.map(group => [group.id, Number(group.collapsed)]), { animate: next.animate === true });
      return projection;
    },
    refresh() { if (projection) paint(transition.values, { final: !transition.active }); },
    finish() { transition.finish(); },
    clear() {
      transition.cancel(); releaseGeometry(); projection = null; selectedGroup = null; focusedGroup = null;
      nodeSelection.attr('display', null).attr('aria-hidden', null);
      nodeLabelSelection.attr('display', null);
      edgeSelection.attr('display', null); edgeLabelSelection.attr('display', null);
      edgeLayer.selectAll('*').remove(); ghostLayer.selectAll('*').remove(); groupLayer.selectAll('*').remove();
      visible = { nodes, links };
    },
    destroy() { transition.destroy(); releaseGeometry(); destroyed = true; layer.remove(); },
    get projection() { return projection; },
    get visible() { return visible; },
    get isAnimating() { return transition.active; },
  };
}
