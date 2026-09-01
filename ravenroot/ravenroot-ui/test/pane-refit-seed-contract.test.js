import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');
const APP_SOURCE = readFileSync(APP_SOURCE_PATH, 'utf8');

// Same brace-matching slice the sibling contract tests use, so an invariant can be asserted against
// one function instead of the whole DOM-bound module.
function functionBody(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(`Signature not found: ${signature}`);
  let depth = 0;
  for (let index = start + signature.length - 1; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, index + 1);
    }
  }
  throw new Error(`Unbalanced braces after: ${signature}`);
}

// WHY THIS IS A SOURCE CONTRACT AND NOT AN END-TO-END TEST.
//
// The defect this pins is a boot-ordering race: `syncPaneRenderer` treats the first pane size it
// ever records as the one the document's framing was computed against, and at boot the pane is
// created and synced in the same tick, while its box is still 0x0, so nothing is recorded at all.
// The first resize the user performs is then read as this document's first measurement and never
// refits — the graph stays framed for a box it no longer occupies.
//
// Two e2e specs detect that (`workspace-layouts.spec.js` › "clamps automatic refits…" and
// `workspace-panes.spec.js` › "refits a halved pane…"), and they are the tests that own the
// behaviour. But they detect it only 8-9 times in 10 against a reverted fix, measured twice
// independently: the boot ordering that HIDES the defect is real and no test can force it. A
// green run after reverting the seeding disproves nothing, which is a bad property for the only
// regression guard.
//
// So this file pins the STRUCTURE instead of the behaviour: it fails 10 times in 10 the moment any
// of the four pieces is removed, and it makes no claim the e2e specs already make. It makes the same
// trade as `elastic-renderer-lifecycle-contract.test.js`: an invariant that would need
// the whole module to unit-test, asserted at the source — and it is a supplement to those specs,
// never a substitute: a passing contract here says the mechanism is still wired, not that it works.
describe('Pane refit seeding source contract', () => {
  it('seeds the pane size bookkeeping from a ResizeObserver, not from a guessed number of frames', () => {
    expect(APP_SOURCE).toMatch(/const paneSeedObserver = new ResizeObserver\(/);
    // Waiting a fixed delay before seeding would reintroduce the race at a different load level.
    const body = functionBody(APP_SOURCE, 'const paneSeedObserver = new ResizeObserver(');
    expect(body).not.toMatch(/setTimeout|requestAnimationFrame/);
  });

  it('ignores a zero-sized delivery and unobserves on the first real one', () => {
    const body = functionBody(APP_SOURCE, 'const paneSeedObserver = new ResizeObserver(');
    // A 0x0 box is exactly the case `syncPaneRenderer` cannot use; recording it would seed the map
    // with a size no framing was ever computed against, which is the defect wearing a new hat.
    expect(body).toMatch(/if \(!element\.clientWidth \|\| !element\.clientHeight\) return;/);
    // Seeding is the whole job. Staying observed would leave a callback that writes on every later
    // resize, racing `syncPaneRenderer` for ownership of the same map.
    expect(body).toContain('paneSeedObserver.unobserve(element);');
  });

  it('never overwrites a size syncPaneRenderer has already recorded', () => {
    const body = functionBody(APP_SOURCE, 'const paneSeedObserver = new ResizeObserver(');
    expect(body).toMatch(/if \(!id \|\| paneRenderedSize\.has\(id\)\) return;/);
    expect(body).toContain('paneRenderedSize.set(id,');
  });

  it('observes the canvas where it is created and releases it where the document is closed', () => {
    const created = functionBody(APP_SOURCE, 'function documentContainer(document_) {');
    expect(created).toContain('paneSeedObserver.observe(element);');

    // Without this the observer holds a strong reference to every canvas ever closed, and the map
    // entry and the observation would be released at two different moments.
    const closed = functionBody(APP_SOURCE, 'function closeDocument(id) {');
    expect(closed).toContain('paneSeedObserver.unobserve(target.container);');
    expect(closed.indexOf('paneSeedObserver.unobserve(target.container);'))
      .toBeLessThan(closed.indexOf('target.container = null;'));
  });

  it('leaves syncPaneRenderer own the refit decision, so seeding cannot silently start refitting', () => {
    // The seed writes a size and nothing else. A `fit()` here would refit on every document's first
    // paint, which differs from refitting only through `syncPaneRenderer`.
    const body = functionBody(APP_SOURCE, 'const paneSeedObserver = new ResizeObserver(');
    expect(body).not.toMatch(/\.fit\(|\.resize\(|clampAutomaticFitZoom/);
  });
});
