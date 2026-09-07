import cytoscape from 'cytoscape';
import { expect, it } from 'vitest';
import { createVisualGroupRenderer } from '../src/visual-group-renderer.js';
import { makeVisualGroupFixture } from './fixtures/visual-groups.js';

it('keeps canonical positions, dimensions and viewport invariant on every frame, finishing and cancelling safely', () => {
  const { graph, groups } = makeVisualGroupFixture();
  const cy = cytoscape({ headless: true, styleEnabled: true, layout: { name: 'preset' },
    elements: [...graph.nodes.map(node => ({ data: { id: node.id, label: node.name },
      position: { x: node.ox, y: node.oy } })), ...graph.edges.map(edge => ({ data: { ...edge } }))],
    style: [{ selector: 'node', style: { width: 80, height: 80, 'font-size': 20 } }],
  });
  cy.zoom(1.3); cy.pan({ x: 34, y: -19 });
  const before = graph.nodes.map(node => [node.id, { ...cy.getElementById(node.id).position() }]);
  let callback = null; let clock = 0;
  const selected = [];
  const renderer = createVisualGroupRenderer({ cy, onSelection: ids => selected.push(ids),
    transitionOptions: { requestFrame: fn => { callback = fn; return 1; }, cancelFrame: () => { callback = null; },
      now: () => clock, reducedMotion: () => false } });
  renderer.setGroups({ graph, groups, state: {}, selection: ['node-1'] });
  renderer.setGroups({ graph, groups, state: { 'section-a': { collapsed: true } }, selection: ['node-1'], animate: true });
  for (let i = 0; i < 16; i += 1) {
    clock += 16; callback?.(clock);
    expect(graph.nodes.map(node => [node.id, { ...cy.getElementById(node.id).position() }])).toEqual(before);
    expect(cy.zoom()).toBe(1.3); expect(cy.pan()).toEqual({ x: 34, y: -19 });
    graph.nodes.forEach(node => { expect(cy.getElementById(node.id).width()).toBe(80); });
  }
  expect(cy.getElementById('node-1').visible()).toBe(false);
  expect(selected.at(-1)).toEqual([renderer.projection.groups[0].summaryId]);
  // User zoom during an animation is authoritative even when save finishes synchronously.
  renderer.setGroups({ graph, groups, state: {}, animate: true });
  cy.zoom(.7); cy.pan({ x: 99, y: -23 }); renderer.finish();
  expect(cy.zoom()).toBe(.7); expect(cy.pan()).toEqual({ x: 99, y: -23 });
  const member = cy.getElementById('node-1');
  expect(member.visible(), JSON.stringify({ classes: member.classes(), display: member.style('display'),
    opacity: member.style('opacity'), visibility: member.style('visibility'), width: member.width(),
    empty: member.empty() })).toBe(true);
  renderer.suspend(); expect(cy.nodes()).toHaveLength(80); expect(cy.edges()).toHaveLength(180);
  renderer.destroy(); cy.destroy();
});
