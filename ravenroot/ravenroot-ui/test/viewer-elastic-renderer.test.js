import { readFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';

import { describe, expect, it } from 'vitest';

import { mountD3ElasticRenderer } from '../src/viewer-elastic-renderer.js';

describe('shared D3 Elastic renderer', () => {
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

  it('is the one Elastic implementation imported by editor and embed entry', () => {
    const root = resolvePath(import.meta.dirname, '..', 'src');
    for (const file of ['app.js', 'embed-viewer-entry.js']) {
      const source = readFileSync(resolvePath(root, file), 'utf8');
      expect(source).toContain("from './viewer-elastic-renderer.js'");
      expect(source).toContain('mountD3ElasticRenderer({');
    }
  });
});
