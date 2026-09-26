import { readFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';

import { describe, expect, it, vi } from 'vitest';

import {
  SETTLE_WINDOW_DISPLACEMENT_PX,
  SETTLE_WINDOW_TICKS,
  mountD3ElasticRenderer,
} from '../src/viewer-elastic-renderer.js';

// A small connected Monitoring graph with distinct radii; used to exercise convergence and the
// distinction between reheating gestures and radius-only runtime updates deterministically.
function monitoringSettleGraph() {
  return {
    nodes: [
      { id: 'n0', label: 'N0', r: 12, color: '#fff', x: -200, y: -60 },
      { id: 'n1', label: 'N1', r: 16, color: '#fff', x: -80, y: 70 },
      { id: 'n2', label: 'N2', r: 20, color: '#fff', x: 60, y: -80 },
      { id: 'n3', label: 'N3', r: 14, color: '#fff', x: 220, y: 30 },
      { id: 'n4', label: 'N4', r: 24, color: '#fff', x: 0, y: 0 },
      { id: 'n5', label: 'N5', r: 10, color: '#fff', x: 140, y: 120 },
    ],
    links: [[0, 1], [1, 2], [2, 3], [3, 4], [4, 5], [0, 5], [1, 4]].map(([a, b], index) => ({
      id: `e${index}`, source: `n${a}`, target: `n${b}`, baseWidth: 1.8, restLen: 130,
      color: '#fff', flow: { recent: 0 },
    })),
  };
}

// Drive an explicit tick until the renderer stops the simulation (or the cap is reached), so a
// convergence decision is observed rather than assumed.
function tickUntilStopped(renderer, stop, cap = 900) {
  let steps = 0;
  while (steps < cap && stop.mock.calls.length === 0) { renderer.simulation.tick(1); steps += 1; }
  return steps;
}

// d3.drag reads `event.view`; jsdom rejects `view` in the MouseEvent init dict, so it is attached
// afterwards. Coordinates resolve through getBoundingClientRect (jsdom returns zeros), i.e. clientX/Y.
function mouseEvent(type, clientX, clientY) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, clientX, clientY });
  Object.defineProperty(event, 'view', { value: window });
  return event;
}

describe('shared D3 Elastic renderer', () => {
  it('projects groups without replacing, reheating or stopping the canonical simulation and restores temporary pins', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    let frame = null;
    vi.stubGlobal('requestAnimationFrame', callback => { frame = callback; return 1; });
    vi.stubGlobal('cancelAnimationFrame', () => { frame = null; });
    const renderer = mountD3ElasticRenderer({ svg: document.querySelector('svg'),
      nodes: [{ id: 'a', label: 'A', r: 10, color: '#fff', x: 10, y: 20, fx: 10 },
        { id: 'b', label: 'B', r: 10, color: '#fff', x: 80, y: 20 },
        { id: 'c', label: 'C', r: 10, color: '#fff', x: 180, y: 80 }],
      links: [{ id: 'e1', source: 'a', target: 'b', baseWidth: 1.8, restLen: 70, color: '#fff' },
        { id: 'e2', source: 'b', target: 'c', baseWidth: 1.8, restLen: 70, color: '#fff' }],
      width: 400, height: 200, palette: {}, initialTransform: { k: 1.3, x: 21, y: -9 },
    });
    const stop = vi.spyOn(renderer.simulation, 'stop');
    const restart = vi.spyOn(renderer.simulation, 'restart');
    const groups = [{ id: 'g', name: 'Section', memberNodeIds: ['a', 'b'], anchorNodeId: 'a', collapsed: false }];
    const before = renderer.nodes.map(node => ({ id: node.id, x: node.x, y: node.y, vx: node.vx, vy: node.vy, fx: node.fx, fy: node.fy }));
    const alpha = renderer.simulation.alpha();
    renderer.setVisualGroups({ groups, state: { g: { collapsed: true } }, animate: true });
    renderer.simulation.tick(4); renderer.paint();
    expect(renderer.nodes.map(node => [node.id, node.x, node.y])).toEqual(before.map(node => [node.id, node.x, node.y]));
    expect(renderer.simulation.alpha()).toBeLessThan(alpha);
    expect(stop).not.toHaveBeenCalled(); expect(restart).not.toHaveBeenCalled();
    renderer.finishVisualGroupTransition();
    expect(renderer.nodes.map(node => ({ id: node.id, x: node.x, y: node.y, vx: node.vx, vy: node.vy, fx: node.fx, fy: node.fy }))).toEqual(before);
    expect(renderer.simulation.nodes()).toBe(renderer.nodes);
    expect(renderer.getVisibleGraph().nodes.some(node => node.id === 'b')).toBe(false);
    expect(document.querySelector('.d3-zoom-group').getAttribute('transform')).toBe('translate(21,-9) scale(1.3)');
    renderer.updateNode('b', { runtimeObserved: true, runtimeState: 'failed' });
    expect(document.querySelector('[data-member-observations]').getAttribute('data-member-observations')).toContain('"failed":1');
    // A settled simulation also remains settled; toggling never calls restart or changes alpha.
    renderer.simulation.stop().alpha(0); stop.mockClear();
    renderer.setVisualGroups({ groups, state: {}, animate: false });
    expect(renderer.simulation.alpha()).toBe(0); expect(stop).not.toHaveBeenCalled();
    expect(restart).not.toHaveBeenCalled();
    renderer.setVisualGroups({ groups, state: { g: { collapsed: true } }, selectedGroupId: 'g', focusGroupId: 'g' });
    const restoredSummary = document.querySelector('[aria-label="Expand visual group Section, 2 members"]');
    expect(restoredSummary.getAttribute('data-selected')).toBe('true');
    expect(document.activeElement).toBe(restoredSummary);
    const headerId = renderer.visualGroupProjection.groups[0].headerId;
    renderer.setVisualGroups({ groups, state: {}, selection: [headerId], focus: headerId });
    const restoredHeader = document.querySelector('[aria-label="Collapse visual group Section, 2 members"]');
    expect(restoredHeader.getAttribute('data-selected')).toBe('true');
    expect(document.activeElement).toBe(restoredHeader);
    renderer.destroy(); vi.unstubAllGlobals();
  });
  it('mounts a real SVG force renderer and tears it down deterministically', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    const svg = document.querySelector('#elastic');
    const renderer = mountD3ElasticRenderer({
      svg,
      nodes: [
        { id: 'a', label: 'A', r: 18, color: '#58a6ff', x: 40, y: 50,
          stroke: '#ff0000', strokeWidth: 4 },
        { id: 'b', label: 'B', r: 18, color: '#3fb950', x: 160, y: 100 },
      ],
      links: [{
        id: 'edge', source: 'a', target: 'b', baseWidth: 2, restLen: 120,
        color: '#8c959f', label: '',
      }],
      width: 320,
      height: 200,
      palette: { nodeText: '#fff', edgeLabel: '#ccc' },
    });

    expect(svg.querySelectorAll('.d3-nodes circle')).toHaveLength(2);
    expect(svg.querySelector('.d3-nodes circle').getAttribute('stroke')).toBe('#ff0000');
    expect(svg.querySelector('.d3-nodes circle').getAttribute('stroke-width')).toBe('4');
    expect(svg.querySelectorAll('.d3-nodes circle')[1].getAttribute('stroke')).toBe('#8c959f');
    expect(svg.querySelectorAll('.d3-nodes circle')[1].getAttribute('stroke-width')).toBe('1.5');
    expect(svg.querySelectorAll('.d3-edges path')).toHaveLength(1);
    expect(svg.querySelector('.d3-edges path').getAttribute('d')).toMatch(/^M/);
    expect(svg.querySelector('.d3-nodes circle').getAttribute('cx')).toBe('40');
    expect(svg.querySelector('.d3-nodes circle').getAttribute('cy')).toBe('50');
    expect(svg.querySelector('marker')).not.toBeNull();
    renderer.zoomBy(1.1);
    renderer.panBy({ x: 8, y: -4 });
    renderer.fit(20);
    renderer.destroy();
    renderer.destroy();
    expect(svg.children).toHaveLength(0);
  });

  it('preserves the transferred model centroid before the first frame and after force settlement', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    const svg = document.querySelector('#elastic');
    const xs = [-300, -100, 100, 300];
    const renderer = mountD3ElasticRenderer({
      svg,
      nodes: xs.map((x, index) => ({ id: `n${index}`, label: `N${index}`, r: 10,
        color: '#fff', x, y: index % 2 ? 40 : -40 })),
      links: xs.slice(1).map((_, index) => ({ id: `e${index}`, source: `n${index}`,
        target: `n${index + 1}`, baseWidth: 1.8, restLen: 120, color: '#fff', flow: { recent: 0 } })),
      width: 900, height: 500, palette: {}, initialTransform: { k: 1.25, x: 550, y: 130 },
    });
    const screenCentroid = () => renderer.nodes.reduce((sum, node) => sum + node.x * 1.25 + 550, 0)
      / renderer.nodes.length;
    expect(screenCentroid()).toBeCloseTo(550, 8);
    expect([...svg.querySelectorAll('circle')].map(circle => Number(circle.getAttribute('cx')))).toEqual(xs);
    renderer.simulation.stop().tick(400);
    renderer.paint();
    expect(screenCentroid()).toBeCloseTo(550, 2);
    renderer.destroy();
  });

  it('keeps arrow geometry independent from flow width and projects the Design viewport immediately', () => {
    document.body.innerHTML = '<svg id="elastic"></svg><div id="tooltip"></div>';
    const svg = document.querySelector('#elastic');
    const renderer = mountD3ElasticRenderer({
      svg, tooltip: document.querySelector('#tooltip'),
      nodes: [{ id: 'a', label: 'A', r: 10, color: '#fff', x: 10, y: 20 },
        { id: 'b', label: 'B', r: 10, color: '#fff', x: 80, y: 20 }],
      links: [{ id: 'e1', source: 'a', target: 'b', baseWidth: 1.8, restLen: 70,
        color: '#fff', label: 'continue', configuredWeight: 4, flow: { recent: 0, count: 0 } }],
      width: 200, height: 100, palette: {}, initialTransform: { k: 1.5, x: 23, y: -7 },
    });
    expect(svg.querySelector('marker').getAttribute('markerUnits')).toBe('userSpaceOnUse');
    expect(svg.querySelector('.d3-zoom-group').getAttribute('transform')).toBe('translate(23,-7) scale(1.5)');
    renderer.updateEdgeFlow('e1', { recent: 9, count: 9, lastEvent: 'p:9', lastOccurredAt: null },
      { reducedMotion: true });
    expect(Number(svg.querySelector('.d3-edges path').getAttribute('stroke-width'))).toBeGreaterThan(1.8);
    expect(svg.querySelector('marker').getAttribute('markerWidth')).toBe('6');
    expect(svg.querySelector('.d3-edges path').getAttribute('stroke-dasharray')).toBeNull();
    renderer.destroy();
  });

  it('updates only the D3 link whose whitespace-significant identity matches exactly', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    const svg = document.querySelector('#elastic');
    const renderer = mountD3ElasticRenderer({
      svg,
      nodes: [{ id: 'a', label: 'A', r: 10, color: '#fff', x: 10, y: 20 },
        { id: 'b', label: 'B', r: 10, color: '#fff', x: 80, y: 20 }],
      links: [{ id: 'edge', source: 'a', target: 'b', baseWidth: 1.8, restLen: 70,
        color: '#fff', flow: { recent: 0, count: 0 } },
      { id: ' edge ', source: 'a', target: 'b', baseWidth: 1.8, restLen: 70,
        color: '#fff', flow: { recent: 0, count: 0 } }],
      width: 200, height: 100, palette: {},
    });
    renderer.updateEdgeFlow(' edge ', { recent: 1, count: 1 }, { reducedMotion: true });
    const paths = [...svg.querySelectorAll('.d3-edges path')];
    expect(paths.map(path => Number(path.getAttribute('stroke-width'))))
      .toEqual([1.8, expect.any(Number)]);
    expect(Number(paths[1].getAttribute('stroke-width'))).toBeGreaterThan(1.8);
    expect(renderer.links.map(link => link.id)).toEqual(['edge', ' edge ']);
    renderer.destroy();
  });

  it('refreshes a visible operational tooltip without exposing arbitrary datum fields', () => {
    document.body.innerHTML = '<svg id="elastic"></svg><div id="tooltip"></div>';
    const svg = document.querySelector('#elastic');
    const tooltip = document.querySelector('#tooltip');
    const renderer = mountD3ElasticRenderer({
      svg, tooltip,
      nodes: [{ id: 'a', label: 'Worker', r: 10, color: '#fff', x: 20, y: 20,
        secret: 'must-not-render', runtimeObserved: false }],
      links: [], width: 100, height: 100, palette: {},
    });
    svg.querySelector('circle').dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
    expect(tooltip.textContent).toContain('State: unknown');
    expect(tooltip.textContent).not.toContain('must-not-render');
    renderer.updateNode('a', {
      runtimeObserved: true, runtimeState: 'active', instances: 2, arrivals: 3,
      lastEventType: 'NODE_STARTED', lastOccurredAt: '2026-08-30T12:00:00Z',
    });
    expect(tooltip.textContent).toContain('State: active');
    expect(tooltip.textContent).toContain('Active instances: 2');
    expect(tooltip.textContent).toContain('In-flight arrivals: 3');
    renderer.destroy();
  });

  it('settles on measured displacement rather than d3\'s alpha tail, then keeps the view fixed', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    const { nodes, links } = monitoringSettleGraph();
    const renderer = mountD3ElasticRenderer({ svg: document.querySelector('#elastic'),
      nodes, links, width: 900, height: 500, palette: {} });
    // Neutralise the timer so every step is explicit and deterministic.
    renderer.simulation.stop();
    const stop = vi.spyOn(renderer.simulation, 'stop');
    const steps = tickUntilStopped(renderer, stop);
    expect(stop).toHaveBeenCalledTimes(1);
    // A window of quiet ticks cannot complete before it has been filled.
    expect(steps).toBeGreaterThanOrEqual(SETTLE_WINDOW_TICKS);
    // The stop happened while alpha was still far above d3's alphaMin: this is the displacement
    // decision, not the geometric alpha tail (which only reaches alphaMin after ~570 ticks).
    expect(renderer.simulation.alpha()).toBeGreaterThan(renderer.simulation.alphaMin());
    expect(steps).toBeLessThan(570);
    // Continuing to step moves the layout by less than the material window, so the settled view is
    // visually fixed rather than merely paused.
    const settled = renderer.nodes.map(node => ({ x: node.x, y: node.y }));
    let drift = 0;
    for (let i = 0; i < SETTLE_WINDOW_TICKS; i += 1) {
      renderer.simulation.tick(1);
      renderer.nodes.forEach((node, index) => {
        drift = Math.max(drift, Math.hypot(node.x - settled[index].x, node.y - settled[index].y));
      });
    }
    expect(drift).toBeLessThan(SETTLE_WINDOW_DISPLACEMENT_PX);
    renderer.destroy();
  });

  it('refreshes collision radii for the next reheat without reheating or moving a settled graph', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    const { nodes, links } = monitoringSettleGraph();
    const renderer = mountD3ElasticRenderer({ svg: document.querySelector('#elastic'),
      nodes, links, width: 900, height: 500, palette: {} });
    renderer.simulation.stop().alpha(0);
    const restart = vi.spyOn(renderer.simulation, 'restart');
    const stop = vi.spyOn(renderer.simulation, 'stop');
    const collisionBefore = renderer.simulation.force('collision');
    const coordinates = renderer.nodes.map(node => ({ id: node.id, x: node.x, y: node.y }));
    const paddedRadius = node => renderer.simulation.force('collision').radius()(node);
    // Mimic a runtime instance-count update: the datum's drawn/visual size grows.
    renderer.nodes[0].r = 25;
    renderer.updateNode('n0', { r: 25, runtimeObserved: true, runtimeState: 'active', instances: 5 });
    renderer.refreshCollisionRadii();
    // The collide force is rebuilt, so its radii cache is recomputed from the new node.r for the
    // next legitimate reheat, and the accessor reports the enlarged radius.
    expect(renderer.simulation.force('collision')).not.toBe(collisionBefore);
    expect(paddedRadius(renderer.nodes[0])).toBe(25 + 8);
    // ... but nothing reheated and no settled coordinate moved.
    expect(restart).not.toHaveBeenCalled();
    expect(stop).not.toHaveBeenCalled();
    expect(renderer.simulation.alpha()).toBe(0);
    expect(renderer.nodes[0].r).toBe(25);
    expect(renderer.nodes.map(node => ({ id: node.id, x: node.x, y: node.y }))).toEqual(coordinates);
    renderer.destroy();
  });

  it('reheats when a node is dragged and released, then settles again', () => {
    document.body.innerHTML = '<svg id="elastic"></svg>';
    const svg = document.querySelector('#elastic');
    const { nodes, links } = monitoringSettleGraph();
    const renderer = mountD3ElasticRenderer({ svg, nodes, links, width: 900, height: 500, palette: {} });
    renderer.simulation.stop();
    const stop = vi.spyOn(renderer.simulation, 'stop');
    const restart = vi.spyOn(renderer.simulation, 'restart');
    expect(tickUntilStopped(renderer, stop)).toBeGreaterThanOrEqual(SETTLE_WINDOW_TICKS);
    expect(stop).toHaveBeenCalledTimes(1);
    stop.mockClear();

    const dragged = renderer.nodes[0];
    const circle = renderer.nodeSelection.nodes()[0];
    const startX = dragged.x;
    const startY = dragged.y;
    circle.dispatchEvent(mouseEvent('mousedown', startX, startY));
    // Dragging reheats the layout so it can adjust around the new position.
    expect(restart).toHaveBeenCalledTimes(1);
    expect(renderer.simulation.alphaTarget()).toBeCloseTo(0.3);
    expect(dragged.fx).toBe(startX);
    window.dispatchEvent(mouseEvent('mousemove', startX + 80, startY + 30));
    expect(dragged.fx).toBe(startX + 80);
    expect(dragged.fy).toBe(startY + 30);
    window.dispatchEvent(mouseEvent('mouseup', startX + 80, startY + 30));
    expect(dragged.fx).toBeNull();
    expect(renderer.simulation.alphaTarget()).toBe(0);

    // Neighbours adjust, then the graph returns to the settled state.
    const afterDrag = tickUntilStopped(renderer, stop);
    expect(stop).toHaveBeenCalledTimes(1);
    expect(afterDrag).toBeLessThan(900);
    renderer.destroy();
  });

  it('never reheats the Monitoring simulation for a runtime radius-only update', () => {
    const source = readFileSync(resolvePath(import.meta.dirname, '..', 'src', 'app.js'), 'utf8');
    const start = source.indexOf('function updateD3RuntimeNode(');
    expect(start).toBeGreaterThan(-1);
    const next = source.indexOf('\nfunction ', start + 1);
    const body = source.slice(start, next === -1 ? undefined : next);
    // The runtime resize refreshes collision radii through the renderer and keeps animating the
    // visible size, but must not call alpha/restart itself.
    expect(body).toContain('refreshCollisionRadii');
    expect(body).toContain(".transition().duration(180)");
    expect(body).not.toContain('.restart()');
    expect(body).not.toMatch(/alpha\(\s*[\d.]/);
  });

  it('is the one Elastic implementation imported by editor and embed entry', () => {
    const root = resolvePath(import.meta.dirname, '..', 'src');
    for (const file of ['app.js', 'embed-viewer-entry.js']) {
      const source = readFileSync(resolvePath(root, file), 'utf8');
      expect(source).toContain("from './viewer-elastic-renderer.js'");
      expect(source).toContain('mountD3ElasticRenderer({');
    }
  });
});
