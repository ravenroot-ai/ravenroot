import { expect, it } from 'vitest';
import { createVisualGroupTransition } from '../src/visual-group-transition.js';

function harness() {
  const callbacks = new Map(); let clock = 0; let next = 0; let live = true;
  const paints = [];
  const owner = createVisualGroupTransition({
    paint: (values, state) => paints.push({ values, ...state }), isCurrent: () => live,
    now: () => clock, reducedMotion: () => false,
    requestFrame: fn => { callbacks.set(++next, fn); return next; },
    cancelFrame: id => callbacks.delete(id),
  });
  return { owner, paints, callbacks, invalidate: () => { live = false; },
    tick(ms) { clock += ms; const pending = [...callbacks.values()]; callbacks.clear(); pending.forEach(fn => fn(clock)); } };
}
it('reverses from the current frame with one scheduled owner and finishes synchronously', () => {
  const h = harness(); h.owner.seed([['a', 0]]); h.owner.request([['a', 1]]);
  h.tick(120); expect(h.owner.values.get('a')).toBe(.5);
  h.owner.request([['a', 0]]); expect(h.owner.values.get('a')).toBe(.5);
  expect(h.callbacks.size).toBe(1);
  h.owner.finish(); expect(h.owner.values.get('a')).toBe(0);
  expect(h.paints.at(-1).final).toBe(true); expect(h.callbacks.size).toBe(0);
});
it('invalidates stale document callbacks and bounds twenty cycles without drift', () => {
  const h = harness(); h.owner.seed([['a', 0]]);
  for (let i = 0; i < 20; i += 1) {
    h.owner.request([['a', 1]]); h.tick(240); expect(h.owner.values.get('a')).toBe(1);
    h.owner.request([['a', 0]]); h.tick(240); expect(h.owner.values.get('a')).toBe(0);
  }
  h.owner.request([['a', 1]]); const count = h.paints.length; h.invalidate(); h.tick(240);
  expect(h.paints).toHaveLength(count); expect(h.owner.active).toBe(false);
});
it('uses final geometry immediately for reduced motion', () => {
  const frames = [];
  const owner = createVisualGroupTransition({ paint: (values, state) => frames.push([values, state]), reducedMotion: () => true });
  owner.seed([['a', 0]]); owner.request([['a', 1]]);
  expect(owner.active).toBe(false); expect(frames.at(-1)[0].get('a')).toBe(1);
});
it('an already queued retired callback cannot clear the replacement owner or its frame', () => {
  const h = harness(); h.owner.seed([['a', 0]]); h.owner.request([['a', 1]]);
  const retired = [...h.callbacks.values()][0];
  h.tick(80); h.owner.request([['a', 0]]);
  retired(120);
  expect(h.owner.active).toBe(true); expect(h.callbacks.size).toBe(1);
  h.owner.finish(); expect(h.callbacks.size).toBe(0);
  expect(h.owner.values.get('a')).toBe(0);
});
