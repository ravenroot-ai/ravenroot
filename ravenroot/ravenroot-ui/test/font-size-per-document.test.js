import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// ── Font size is a document property, asserted rather than argued (UI-12) ───────────────────
//
// `fontSize` belongs on the document record beside `layoutMode`, `filterActive`, `traceActive`,
// `n8nActive` and `cursorId`, all captured from and applied to the active document by
// `captureActiveDocument`/`applyActiveDocument`. Keeping font size only in the `#font-slider` DOM
// element makes `initCy` reset every opened document to a constant because no per-document value is
// available.
//
// THIS FILE IS A GUARD, NOT THE PROOF. A static scan of source text can pass for the wrong reason —
// rewrite the reset as `onFontSize(DEFAULT_FONT_SIZE, instance)` with `DEFAULT_FONT_SIZE` a bare
// constant, and a scan that only checked "the literal 20 is gone" would go green while the document
// is still being reset to a constant on every open, which is the actual defect. So the assertion
// below is POSITIVE: it requires the value passed at `layoutstop` to be an identifier that itself
// traces back to the `fontSize` working-view variable `captureActiveDocument`/`applyActiveDocument`
// read and write — not merely that no numeral appears at the call site.
//
// The load-bearing test is `e2e/font-size-per-document.spec.js`: it drives a real browser, sets one
// document's font, opens a second, and asserts the first document's value survives switching back to
// it. That is what actually proves the defect is fixed. This file only proves the record has
// somewhere to keep the value and that `initCy` is wired to read it — do not delete the e2e spec on
// the strength of this one passing; a passing static scan here has never shown the bug is gone.

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');

function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    // The `[^:]` guard keeps `https://` in a string literal from swallowing the rest of its line.
    .replace(/(^|[^:])\/\/[^\n]*/gm, '$1');
}

// Returns the body of a named function declaration by brace matching. Throws when it is absent, so a
// rename breaks this test loudly instead of quietly emptying what it checks.
//
// The parameter list is skipped by matching PARENS first, not by jumping straight to the first `{`:
// `initCy(elements, gd, options = {})` has a `{}` default value inside its own parameter list, and a
// naive `indexOf('{', start)` finds that one instead of the body's opening brace — returning the
// empty string between them rather than throwing, which is a silent false negative, not a loud one.
function functionBody(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must exist in app.js for this contract to mean anything`).toBeGreaterThan(-1);
  const parenOpen = source.indexOf('(', start);
  let parenIndex = parenOpen;
  let parenDepth = 0;
  for (; parenIndex < source.length; parenIndex += 1) {
    if (source[parenIndex] === '(') parenDepth += 1;
    else if (source[parenIndex] === ')') {
      parenDepth -= 1;
      if (parenDepth === 0) break;
    }
  }
  let index = source.indexOf('{', parenIndex);
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

describe('font size is captured out of and applied into the active document, like its siblings', () => {
  it('captures the working-view font size into the record being left', () => {
    const body = functionBody(APP_SOURCE, 'captureActiveDocument');
    expect(body).toContain('document_.fontSize = fontSize;');
  });

  it('applies the record being entered into the working-view font size', () => {
    const body = functionBody(APP_SOURCE, 'applyActiveDocument');
    expect(body).toMatch(/fontSize\s*=\s*document_\?\.fontSize\s*\?\?/);
  });
});

describe('a document opening does not apply a constant divorced from the working view', () => {
  // The positive control: prove the extraction itself finds something, so a regexp that silently
  // matches nothing cannot masquerade as "the defect is fixed".
  it('sizes the renderer synchronously because loading no longer runs a layout', () => {
    const body = functionBody(APP_SOURCE, 'initCy');
    expect(body).toMatch(/onFontSize\([^,]+,\s*instance\);/);
    expect(body).not.toMatch(/cy\.one\('layoutstop'/);
  });

  it('passes an identifier captured from `fontSize`, not a numeric literal', () => {
    const body = functionBody(APP_SOURCE, 'initCy');
    const registration = body.match(/onFontSize\(([^,]+),\s*instance\);/);
    const argument = registration[1].trim();

    // Rules out `onFontSize(20, instance)`, the original defect.
    expect(argument, 'the deferred call must not apply a bare numeral').not.toMatch(/^\d+$/);

    // Rules out renaming the literal to a same-valued constant (e.g.
    // `onFontSize(DEFAULT_FONT_SIZE, instance)`) is still a constant, not a per-document value. The
    // argument must be an identifier that is ITSELF assigned from the working-view `fontSize` inside
    // this same function call — the same variable `captureActiveDocument`/`applyActiveDocument`
    // thread through the record, not a second, disconnected source of truth.
    const assignment = new RegExp(`const\\s+${argument}\\s*=\\s*fontSize;`);
    expect(body, `${argument} must be assigned from the working-view \`fontSize\`, not a standalone constant`)
      .toMatch(assignment);
  });

  it('resets the working-view font size only when the document actually changed', () => {
    const body = functionBody(APP_SOURCE, 'initCy');
    const guardedBlock = body.slice(body.indexOf('if (documentChanged)'), body.indexOf('if (documentChanged)') + 400);
    expect(guardedBlock).toContain('fontSize = DEFAULT_FONT_SIZE;');
  });
});

describe('the font control is shared chrome that follows the active document, like the layout buttons', () => {
  it('resyncs the font slider and its readout whenever the active document is repainted', () => {
    const body = functionBody(APP_SOURCE, 'syncActiveDocumentChrome');
    expect(body).toMatch(/syncFontChrome\(/);
  });
});
