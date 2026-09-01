import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');
const APP_SOURCE = readFileSync(APP_SOURCE_PATH, 'utf8');

// Slices one function body out of the source by brace matching, so a contract can be asserted
// against a single function instead of the whole 7000-line module.
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

describe('Elastic renderer lifecycle source contract', () => {
  it('has no detached stress RAF or Cytoscape restart path', () => {
    expect(APP_SOURCE).not.toMatch(/\brunElasticPhysics\b/);
    expect(APP_SOURCE).not.toMatch(/\b_runElasticStressFrame\b/);
    expect(APP_SOURCE).not.toMatch(/\b(?:physicsGeneration|stressRaf|stressActive)\b/);
    expect(APP_SOURCE).not.toContain('free.elasticRestart');
  });

  // `layoutSessions.request` claims the ELK serialization slot; only `layoutSessions.complete`
  // returns it. Every path that ends a layout job must therefore go through `completeOwnedLayout`,
  // which owns that release and the document-busy transition. A bare delete ends the job while leaving
  // the slot claimed, which is exactly how a preserved-position ELK load stranded Elastic behind a
  // layout that had already finished — and it did so silently, with no error and no failed paint.
  // Unit-testing `runOwnedLayout` directly would need the whole DOM-bound module, so the invariant
  // is pinned at the source, in the same shape as the contract above.
  it('routes every layout-job ending in runOwnedLayout through the ELK slot release', () => {
    // `completeOwnedLayout` is the one place allowed to end a job, because it also releases the slot.
    expect(APP_SOURCE).toMatch(
      /function completeOwnedLayout\(job\) \{[\s\S]*?layoutJobs\.delete\(token\.generation\);/);
    expect(APP_SOURCE).toMatch(
      /const released = token\.kind === 'elk' \? layoutSessions\.complete\(token\)\.start : null;/);

    // `runOwnedLayout` has several paths that finish without a native layout, so none of them may
    // end the job by hand. `finishOwnedLayout` is deliberately not covered here: it runs FROM a
    // `layoutstop` and completes the token itself before publishing.
    const body = functionBody(APP_SOURCE, 'function runOwnedLayout(token) {');
    expect(body).not.toContain('layoutJobs.delete');
    expect(body).toContain('settleOwnedLayout(token);');
  });

  it('replaces a Monitoring view with canonical Design state without moving loaded coordinates', () => {
    const body = functionBody(APP_SOURCE, 'function completeReplaceActiveDocument(');
    const install = functionBody(APP_SOURCE, 'function installActiveRenderModePresentation(');
    expect(install).toContain('const presentation = renderModePresentation(mode);');
    expect(install).toContain('Object.assign(owner, presentation);');
    expect(install).toContain('renderMode = presentation.renderMode;');
    expect(install).toContain('layoutMode = presentation.layoutMode;');
    expect(install).toContain('visualStyle = presentation.visualStyle;');
    expect(body).toContain('installActiveRenderModePresentation(target, DEFAULT_RENDER_MODE);');
    expect(body.indexOf('installActiveRenderModePresentation(target, DEFAULT_RENDER_MODE);'))
      .toBeLessThan(body.indexOf('initLoadedGraph(graph, visualStyle);'));
    expect(body).not.toContain('setLayout(');
  });
});
