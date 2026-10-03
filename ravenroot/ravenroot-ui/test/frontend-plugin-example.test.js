import { describe, expect, it } from 'vitest';

import textbookAutomata from '../public/examples/frontend-plugins/textbook-automata/plugin.js';

describe('textbook automata example geometry', () => {
  it('separates parallel transitions and loops in a graph with more than two states', () => {
    const state = id => ({ id, label: id, nodeId: `node-${id}`, active: false });
    const transition = (id, source, target) => ({ id, source, target, label: id, edgePath: [id], active: false });
    const snapshot = {
      schema: 'ravenroot.drawing-model-snapshot/v1',
      states: [state('a'), state('b'), state('c')],
      transitions: [
        transition('loop-a-1', 'a', 'a'), transition('loop-a-2', 'a', 'a'),
        transition('a-b-1', 'a', 'b'), transition('a-b-2', 'a', 'b'),
        transition('b-a', 'b', 'a'), transition('b-c', 'b', 'c'),
      ],
      positions: { a: { x: 120, y: 240 }, b: { x: 380, y: 240 }, c: { x: 640, y: 240 } },
      evidence: { activeNodeIds: [], activeEdgeIds: [], description: '' },
    };
    const scene = textbookAutomata.render({ snapshot, layout: { schema: 'ravenroot.layout-result/v1',
      positions: snapshot.positions } });
    const paths = Object.fromEntries(scene.elements.filter(item => item.type === 'path')
      .map(item => [item.id, item.d]));
    expect(paths['loop-a-1']).not.toBe(paths['loop-a-2']);
    expect(paths['a-b-1']).not.toBe(paths['a-b-2']);
    expect(paths['a-b-1']).not.toBe(paths['b-a']);
    expect(new Set(Object.values(paths))).toHaveLength(6);
  });

  it('targets the mapped state from a distinct initial-marker scene element', () => {
    const snapshot = {
      schema: 'ravenroot.drawing-model-snapshot/v1',
      states: [{ id: 'qEven', label: 'qEven', nodeId: 'node-even', initial: true,
        accepting: true, active: false }],
      transitions: [], positions: { qEven: { x: 220, y: 250 } },
      evidence: { activeNodeIds: [], activeEdgeIds: [], description: '' },
    };
    const scene = textbookAutomata.render({ snapshot, layout: {
      schema: 'ravenroot.layout-result/v1', positions: snapshot.positions,
    } });
    expect(scene.elements.find(item => item.id === 'initial-qEven'))
      .toMatchObject({ role: 'state', targetId: 'qEven' });
  });
});
