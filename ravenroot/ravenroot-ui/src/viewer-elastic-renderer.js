import * as d3 from 'd3';
import { edgeFlowWidth, formatRuntimeTime } from './monitoring-runtime-state.js';

function requiredElement(value, name) {
  if (!(value instanceof Element)) throw new TypeError(`${name} is required.`);
  return value;
}

function finite(value, fallback) {
  return Number.isFinite(Number(value)) ? Number(value) : fallback;
}

function markerId(key, color) {
  return `arr-${String(key).replace(/[^a-zA-Z0-9_-]/g, '-')}-${String(color).replace('#', '')}`;
}

/**
 * Mount the shared, view-only D3 Elastic renderer into an existing SVG.
 *
 * Callers own graph/session policy and provide renderer-local node/link copies. The renderer never
 * receives the canonical graph, editor history, runtime client, or browser transport credentials.
 */
export function mountD3ElasticRenderer({
  svg,
  tooltip = null,
  nodes,
  links,
  width,
  height,
  palette,
  markerKey = 'viewer',
  fontSize = 14,
  attraction = .3,
  repulsion = 320,
  isLive = () => true,
  onViewportChange = () => {},
  initialTransform = null,
}) {
  requiredElement(svg, 'Elastic SVG');
  if (tooltip !== null) requiredElement(tooltip, 'Elastic tooltip');
  if (!Array.isArray(nodes) || !Array.isArray(links)) {
    throw new TypeError('Elastic nodes and links are required.');
  }
  if (typeof isLive !== 'function' || typeof onViewportChange !== 'function') {
    throw new TypeError('Elastic lifecycle callbacks are required.');
  }

  const viewportWidth = Math.max(1, finite(width, 800));
  const viewportHeight = Math.max(1, finite(height, 600));
  const nodeText = palette?.nodeText ?? '#e6edf3';
  const edgeLabel = palette?.edgeLabel ?? '#b1bac4';
  let destroyed = false;
  let hovered = null;
  let refreshTooltip = () => {};
  const pulseTimers = new Map();

  const root = d3.select(svg)
    .attr('width', viewportWidth)
    .attr('height', viewportHeight)
    .attr('viewBox', `0 0 ${viewportWidth} ${viewportHeight}`);
  root.selectAll('*').remove();

  const zoomGroup = root.append('g').attr('class', 'd3-zoom-group');
  const edgeGroup = zoomGroup.append('g').attr('class', 'd3-edges');
  const edgeLabelGroup = zoomGroup.append('g').attr('class', 'd3-edge-labels');
  const nodeGroup = zoomGroup.append('g').attr('class', 'd3-nodes');
  const nodeLabelGroup = zoomGroup.append('g').attr('class', 'd3-node-labels');

  const zoom = d3.zoom().scaleExtent([0.05, 10]).on('zoom', event => {
    if (destroyed || !isLive()) return;
    zoomGroup.attr('transform', event.transform);
    onViewportChange();
  });
  root.call(zoom);
  if (initialTransform && Number.isFinite(initialTransform.k)
      && Number.isFinite(initialTransform.x) && Number.isFinite(initialTransform.y)) {
    root.call(zoom.transform, d3.zoomIdentity
      .translate(initialTransform.x, initialTransform.y).scale(initialTransform.k));
  }

  const defs = root.insert('defs', ':first-child');
  [...new Set(links.map(link => link.color))].forEach(color => {
    defs.append('marker')
      .attr('id', markerId(markerKey, color))
      .attr('viewBox', '0 -5 10 10')
      .attr('refX', 10)
      .attr('refY', 0)
      .attr('markerWidth', 6)
      .attr('markerHeight', 6)
      .attr('markerUnits', 'userSpaceOnUse')
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M0,-5L10,0L0,5')
      .attr('fill', color);
  });

  const edgeSelection = edgeGroup.selectAll('path')
    .data(links, link => link.id)
    .enter().append('path')
    .attr('fill', 'none')
    .attr('stroke', link => link.color)
    .attr('stroke-width', link => link.baseWidth)
    .attr('marker-end', link => `url(#${markerId(markerKey, link.color)})`);
  const edgeLabelSelection = edgeLabelGroup.selectAll('text')
    .data(links, link => link.id)
    .enter().append('text')
    .attr('fill', edgeLabel)
    .attr('font-size', `${Math.max(8, Math.min(11, Math.round(fontSize * .55)))}px`)
    .attr('text-anchor', 'middle')
    .attr('dominant-baseline', 'auto')
    .text(link => link.label ?? '');
  const nodeSelection = nodeGroup.selectAll('circle')
    .data(nodes, node => node.id)
    .enter().append('circle')
    .attr('r', node => node.r)
    .attr('fill', node => node.color)
    .attr('stroke', node => node.stroke ?? '#8c959f')
    .attr('stroke-width', node => node.strokeWidth ?? 1.5);
  const nodeLabelSelection = nodeLabelGroup.selectAll('text')
    .data(nodes, node => node.id)
    .enter().append('text')
    .attr('fill', nodeText)
    .attr('font-size', `${Math.max(10, Math.min(fontSize, 14))}px`)
    .attr('text-anchor', 'middle')
    .attr('dominant-baseline', 'hanging')
    .text(node => node.label);

  if (tooltip !== null) {
    const tip = d3.select(tooltip);
    const known = (label, value) => value == null || value === '' ? `${label}: unknown` : `${label}: ${value}`;
    const nodeText = node => {
      const state = node.runtimeObserved ? node.runtimeState : null;
      return [node.label, known('State', state), known('Active instances', node.runtimeObserved ? node.instances : null),
        known('In-flight arrivals', node.runtimeObserved ? node.arrivals : null),
        known('Last event', node.lastEventType), known('Last event time', formatRuntimeTime(node.lastOccurredAt)),
        known('Processing duration', node.processingDuration == null ? null : `${node.processingDuration}s`),
        known('Fallback', node.runtimeObserved ? (node.fallback ? 'yes' : 'no') : null),
        known('Bypassed', node.runtimeObserved ? (node.runtimeState === 'bypassed' ? 'yes' : 'no') : null),
      ].join('\n');
    };
    const edgeText = link => [link.label || link.id,
      `Recent activity: ${link.flow?.recent ?? 0}`,
      `Traversals: ${link.flow?.count ?? 0}`,
      known('Last traversal', link.flow?.lastEvent),
      known('Last traversal time', formatRuntimeTime(link.flow?.lastOccurredAt)),
      known('Configured weight', link.configuredWeight),
    ].join('\n');
    const show = (event, text) => tip.text(text)
      .style('left', `${event.offsetX + 14}px`)
      .style('top', `${event.offsetY - 10}px`)
      .style('display', 'block');
    nodeSelection
      .on('mouseover.tip', (event, node) => { hovered = { kind: 'node', datum: node }; show(event, nodeText(node)); })
      .on('mousemove.tip', event => tip
        .style('left', `${event.offsetX + 14}px`).style('top', `${event.offsetY - 10}px`))
      .on('mouseout.tip', () => { hovered = null; tip.style('display', 'none'); });
    edgeSelection
      .on('mouseover.tip', (event, link) => { hovered = { kind: 'edge', datum: link }; show(event, edgeText(link)); })
      .on('mousemove.tip', event => tip
        .style('left', `${event.offsetX + 14}px`).style('top', `${event.offsetY - 10}px`))
      .on('mouseout.tip', () => { hovered = null; tip.style('display', 'none'); });

    refreshTooltip = () => {
      if (!hovered || tooltip.style.display === 'none') return;
      tip.text(hovered.kind === 'node' ? nodeText(hovered.datum) : edgeText(hovered.datum));
    };
  }

  const arcPath = link => {
    const dx = link.target.x - link.source.x;
    const dy = link.target.y - link.source.y;
    const distance = Math.hypot(dx, dy);
    const targetRadius = finite(link.target.r, 20) + 2;
    const ratio = distance > 0 ? (distance - targetRadius) / distance : 1;
    const endX = link.source.x + dx * ratio;
    const endY = link.source.y + dy * ratio;
    return `M${link.source.x},${link.source.y}A${distance},${distance} 0 0,1 ${endX},${endY}`;
  };

  const initialCentroid = {
    x: d3.mean(nodes, node => finite(node.x, 0)) ?? viewportWidth / 2,
    y: d3.mean(nodes, node => finite(node.y, 0)) ?? viewportHeight / 2,
  };

  const paintGeometry = () => {
    edgeSelection.attr('d', arcPath)
      .attr('stroke-width', link => edgeFlowWidth(link.flow?.recent || 0))
      .attr('opacity', link => {
        const distance = Math.hypot(link.target.x - link.source.x, link.target.y - link.source.y);
        const ratio = distance / (link.restLen || 1);
        return ratio > 1.7 ? Math.max(.15, 1.7 / ratio) : 1;
      });
    edgeLabelSelection
      .attr('x', link => (link.source.x + link.target.x) / 2)
      .attr('y', link => (link.source.y + link.target.y) / 2 - 8);
    nodeSelection.attr('cx', node => node.x).attr('cy', node => node.y);
    nodeLabelSelection.attr('x', node => node.x).attr('y', node => node.y + node.r + 5);
  };

  const simulation = d3.forceSimulation(nodes)
    .force('link', d3.forceLink(links).id(node => node.id)
      .distance(link => link.restLen).strength(finite(attraction, .3)))
    .force('charge', d3.forceManyBody().strength(-Math.abs(finite(repulsion, 320))))
    // The view transform already maps Design model coordinates into the Monitoring viewport.
    // Centering in raw SVG coordinates would translate the whole model before the first useful
    // frame. Preserve the transferred model centroid; forces may rearrange shape, never its origin.
    .force('center', d3.forceCenter(initialCentroid.x, initialCentroid.y).strength(.04))
    .force('collision', d3.forceCollide().radius(node => node.r + 8))
    .alphaDecay(.012)
    .velocityDecay(.42)
    .on('tick', () => {
      if (destroyed || !isLive()) {
        simulation.stop();
        return;
      }
      paintGeometry();
      onViewportChange();
    });

  // D3's timer owns later frames, but the first frame is ours: never leave circles at SVG defaults
  // or paths without geometry while the simulation waits for its first asynchronous tick.
  paintGeometry();

  nodeSelection.call(d3.drag()
    .on('start', (event, node) => {
      if (destroyed || !isLive()) return;
      if (!event.active) simulation.alphaTarget(.3).restart();
      node.fx = node.x;
      node.fy = node.y;
    })
    .on('drag', (event, node) => {
      if (destroyed || !isLive()) return;
      node.fx = event.x;
      node.fy = event.y;
    })
    .on('end', (event, node) => {
      if (destroyed || !isLive()) return;
      if (!event.active) simulation.alphaTarget(0);
      node.fx = null;
      node.fy = null;
    }));

  return {
    nodes,
    links,
    simulation,
    zoom,
    zoomGroup,
    nodeSelection,
    edgeSelection,
    nodeLabelSelection,
    edgeLabelSelection,
    paint: paintGeometry,
    updateNode(nodeId, changes) {
      const datum = nodes.find(node => node.id === nodeId);
      if (!datum || destroyed) return;
      Object.assign(datum, changes);
      refreshTooltip();
    },
    updateEdgeFlow(edgeId, flow, { reducedMotion = false, decayMs = 1_400, onDecay = null } = {}) {
      const link = links.find(candidate => candidate.id === edgeId);
      if (!link || destroyed) return;
      link.flow = flow;
      const selection = edgeSelection.filter(candidate => candidate.id === edgeId);
      selection.interrupt('flow').attr('stroke-width', edgeFlowWidth(flow.recent))
        .attr('opacity', flow.recent > 0 ? 1 : .82)
        .classed('d3-edge--active', flow.recent > 0);
      if (!reducedMotion && flow.recent > 0) {
        selection.attr('stroke-dasharray', '7 5').attr('stroke-dashoffset', 12)
          .transition('flow').duration(Math.min(decayMs, 900)).ease(d3.easeLinear)
          .attr('stroke-dashoffset', 0);
      } else selection.attr('stroke-dasharray', null).attr('stroke-dashoffset', null);
      clearTimeout(pulseTimers.get(edgeId));
      if (flow.recent > 0 && typeof onDecay === 'function') {
        pulseTimers.set(edgeId, setTimeout(onDecay, Math.max(0, decayMs)));
      }
      refreshTooltip();
    },
    fit(padding = 40) {
      if (destroyed || nodes.length === 0) return;
      const x1 = Math.min(...nodes.map(node => node.x - node.r));
      const x2 = Math.max(...nodes.map(node => node.x + node.r));
      const y1 = Math.min(...nodes.map(node => node.y - node.r));
      const y2 = Math.max(...nodes.map(node => node.y + node.r));
      const scale = Math.max(.05, Math.min(10,
        Math.min((viewportWidth - padding * 2) / Math.max(1, x2 - x1),
          (viewportHeight - padding * 2) / Math.max(1, y2 - y1))));
      const transform = d3.zoomIdentity
        .translate(viewportWidth / 2 - ((x1 + x2) / 2) * scale,
          viewportHeight / 2 - ((y1 + y2) / 2) * scale)
        .scale(scale);
      root.call(zoom.transform, transform);
    },
    zoomBy(factor) {
      if (!destroyed) root.call(zoom.scaleBy, finite(factor, 1), [viewportWidth / 2, viewportHeight / 2]);
    },
    panBy(delta) {
      if (!destroyed) root.call(zoom.translateBy,
        finite(delta?.x, 0) / d3.zoomTransform(svg).k,
        finite(delta?.y, 0) / d3.zoomTransform(svg).k);
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      simulation.stop();
      pulseTimers.forEach(clearTimeout);
      pulseTimers.clear();
      root.on('.zoom', null).interrupt();
      root.selectAll('*').interrupt().remove();
      if (tooltip !== null) tooltip.style.display = 'none';
    },
  };
}
