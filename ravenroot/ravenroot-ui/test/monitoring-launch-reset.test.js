import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// ── THE LAUNCH-RESET CONTRACT, ASSERTED RATHER THAN ARGUED ─────────────────────────────────────
//
// Launching a Test or a Run clears the previous run's painting from the Monitoring graph. The rule
// this file enforces (#494):
//
// THE LAUNCH-RESET CLEARS THE MONITORING PAINT IN PLACE; IT NEVER REMOUNTS THE ELASTIC RENDERER.
//
// The bug was not a missing clear -- it was HOW the clear was done. `resetRuntimeState` cleared the
// Cytoscape model and then called `startD3Elastic`, which tears the renderer down and mounts a new
// one. A mounting renderer builds a fresh force simulation at full alpha, so a settled graph
// re-laid-out, and a new SVG/host lost the viewport: the graph visibly jumped on every launch. The
// fix routes the elastic reset through an in-place renderer method instead, so node coordinates and
// zoom/pan survive, and the simulation the #469 convergence rule governs is never restarted.
//
// The behavioural half of this proof is the mount-level test in `viewer-elastic-renderer.test.js`,
// which drives a real renderer and watches it clear its paint without reheating. This half is the
// part a unit renderer cannot show: that the launch path no longer reaches the remount/restart
// entry points anywhere in `app.js`, while the genuine mount/reheat callers keep calling them.
//
// The scan runs against the real file text, and asserts positive controls so that an empty string or
// a mangled one cannot masquerade as a clean result.

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');
const RENDERER_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)),
  '../src/viewer-elastic-renderer.js');

function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    // The `[^:]` guard keeps `https://` in a string literal from swallowing the rest of its line.
    .replace(/(^|[^:])\/\/[^\n]*/gm, '$1');
}

// Returns the body of a named function declaration by brace matching. Throws when it is absent, so
// a rename breaks this test loudly instead of quietly emptying what it checks. The parameter list is
// skipped first: some entry points take destructured/default parameters (`{ skipDraftGuard } = {}`),
// and matching the first `{` naively would return that parameter object as the "body".
function functionBody(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must exist in the source for this contract to mean anything`)
    .toBeGreaterThan(-1);
  let index = source.indexOf('(', start);
  let parens = 0;
  for (; index < source.length; index += 1) {
    if (source[index] === '(') parens += 1;
    else if (source[index] === ')') {
      parens -= 1;
      if (parens === 0) break;
    }
  }
  index = source.indexOf('{', index);
  const open = index;
  let depth = 0;
  for (; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) break;
    }
  }
  return source.slice(open + 1, index);
}

const APP_SOURCE = stripComments(readFileSync(APP_SOURCE_PATH, 'utf8'));
const RENDERER_SOURCE = stripComments(readFileSync(RENDERER_SOURCE_PATH, 'utf8'));

describe('the launch reset clears Monitoring paint without remounting the renderer', () => {
  const launchReset = () => functionBody(APP_SOURCE, 'resetRuntimeState');

  it('never reaches the elastic mount/remount entry points from the launch path', () => {
    const body = launchReset();

    for (const forbidden of [
      'startD3Elastic(',
      'destroyDocumentRenderer(',
      'registerElasticRenderer(',
    ]) {
      expect(body, `resetRuntimeState must not call ${forbidden}`).not.toContain(forbidden);
    }
    // A remount is the only thing that reheats on the launch path; it must also not restart directly.
    expect(body).not.toContain('.restart()');
    expect(body).not.toMatch(/alpha\(\s*[\d.]/);
  });

  it('clears the run paint through an in-place renderer reset instead', () => {
    expect(launchReset()).toContain('resetElasticRuntimePaint(');

    const helper = functionBody(APP_SOURCE, 'resetElasticRuntimePaint');
    expect(helper).toContain('elasticRendererFor(');
    expect(helper).toContain('.resetRuntime(');
    // Idle labels and their human-task attention classes are a document projection and are
    // re-projected from the now-idle model, exactly as a mount would have done.
    expect(helper).toContain('applyHumanTaskProjection(');
  });

  it('keeps the genuine mount, mode-switch and arrangement entry points on startD3Elastic', () => {
    // Positive control: a rename or an over-broad change would break these loudly.
    expect(APP_SOURCE).toContain('function startD3Elastic(');
    for (const name of ['setRenderMode', 'renderActiveMode', 'reconcileActiveRenderModeRenderer']) {
      expect(functionBody(APP_SOURCE, name), `${name} still mounts/reheats in place`).toContain('startD3Elastic(');
    }
  });
});

describe('the renderer in-place reset never reheats or stops the simulation', () => {
  it('has a resetRuntime method that does not restart, stop or raise alpha', () => {
    const start = RENDERER_SOURCE.indexOf('resetRuntime(');
    expect(start, 'resetRuntime must exist on the mount API').toBeGreaterThan(-1);
    // Bound the slice at the next sibling method so neighbouring code cannot satisfy or defeat the
    // assertion by accident.
    const end = RENDERER_SOURCE.indexOf('updateEdgeFlow(', start);
    expect(end).toBeGreaterThan(start);
    const body = RENDERER_SOURCE.slice(start, end);

    expect(body).toContain('paintGeometry()');
    for (const forbidden of ['.restart(', '.stop(', 'alphaTarget(', 'simulation.alpha(']) {
      expect(body, `the in-place reset must not call ${forbidden}`).not.toContain(forbidden);
    }
  });
});

// ── The scan reached real source ─────────────────────────────────────────────────────────────────

describe('the scan proves it read what it claims to have read', () => {
  it('reads a substantial file rather than an empty one', () => {
    expect(APP_SOURCE.length).toBeGreaterThan(50_000);
    expect(RENDERER_SOURCE.length).toBeGreaterThan(5_000);
  });

  it('extracts a body that is a fragment of the file and not the whole of it', () => {
    const body = functionBody(APP_SOURCE, 'resetRuntimeState');

    expect(body.length).toBeGreaterThan(80);
    expect(body.length).toBeLessThan(APP_SOURCE.length / 4);
  });

  it('extracts the right function when two names share a prefix', () => {
    const fixture = `
      function resetRuntime(id) { return 'wrong'; }
      function resetRuntimeState(id) { const inner = { a: 1 }; return 'right'; }
    `;

    expect(functionBody(fixture, 'resetRuntimeState')).toContain("'right'");
    expect(functionBody(fixture, 'resetRuntimeState')).not.toContain("'wrong'");
  });

  it('skips a destructured parameter list rather than returning it as the body', () => {
    const fixture = `
      function setRenderMode(name, { skipDraftGuard = false } = {}) {
        const inner = { a: 1 };
        return 'body';
      }
    `;

    const body = functionBody(fixture, 'setRenderMode');

    expect(body).toContain("'body'");
    expect(body).toContain('const inner = { a: 1 };');
    expect(body).not.toBe(' skipDraftGuard = false ');
  });
});
