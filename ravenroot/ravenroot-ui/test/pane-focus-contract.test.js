import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// ── THE PANE FOCUS CONTRACT, ASSERTED RATHER THAN ARGUED ─────────────────────────────────────
//
// The panes are a new way to change which document is active, and a new way in is exactly where a
// private path gets opened by accident. The rule this file enforces:
//
// THE PANE LAYER CHANGES THE ACTIVE DOCUMENT ONLY BY CALLING `activateDocument`, AND
// `activateDocument` IS THE ONLY CALLER OF `workspace.activate`.
//
// That matters beyond tidiness. `activateDocument` is where `stopElasticRendering()` runs BEFORE
// `applyActiveDocument` repoints `cy`, and where `syncActiveDocumentChrome()` — and through it
// `syncLayoutChrome(name)` — brings the shared toolbar to the document now in front of the user. A
// pane that reached `workspace.activate` directly would change the active document and leave the
// elastic renderer running against the wrong graph. "Elastic only in the active document" stays
// ENFORCED rather than true by accident only for
// as long as there is one door.
//
// The behavioural half of this proof is in `e2e/workspace-panes.spec.js`, which drives a real focus
// event and watches the toolbar follow. This half is the part a browser cannot show: that no SECOND
// door exists anywhere in the file.
//
// The scan runs against the real file text, and asserts positive controls so that an empty string or
// a mangled one cannot masquerade as a clean result.

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');

function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    // The `[^:]` guard keeps `https://` in a string literal from swallowing the rest of its line.
    .replace(/(^|[^:])\/\/[^\n]*/gm, '$1');
}

// Returns the body of a named function declaration by brace matching. Throws when it is absent, so
// a rename breaks this test loudly instead of quietly emptying what it checks.
export function functionBody(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must exist in app.js for this contract to mean anything`).toBeGreaterThan(-1);
  let index = source.indexOf('{', start);
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

const occurrences = (source, needle) => source.split(needle).length - 1;

const APP_SOURCE = stripComments(readFileSync(APP_SOURCE_PATH, 'utf8'));

describe('there is exactly one door into the workspace', () => {
  it('calls workspace.activate from one place in the whole file', () => {
    expect(occurrences(APP_SOURCE, 'workspace.activate(')).toBe(1);
  });

  it('puts that one call inside activateDocument', () => {
    expect(functionBody(APP_SOURCE, 'activateDocument')).toContain('workspace.activate(');
  });

  // Renderer lifetime is a property of pane visibility, not keyboard focus. Activation
  // must therefore move only the working view and shared chrome, in this exact order, while every
  // visible document keeps its renderer alive.
  it('repoints the working view without tearing down or restarting a renderer', () => {
    const body = functionBody(APP_SOURCE, 'activateDocument');

    expect(body).not.toContain('stopElasticRendering(');
    expect(body).not.toContain('startD3Elastic(');
    expect(body).toContain('captureActiveDocument();');
    expect(body).toContain('workspace.activate(');
    expect(body).toContain('applyActiveDocument();');
    expect(body).toContain('syncPaneLayout();');
    expect(body).toContain('syncActiveDocumentChrome();');
    expect(body.indexOf('captureActiveDocument();')).toBeLessThan(body.indexOf('workspace.activate('));
    expect(body.indexOf('workspace.activate(')).toBeLessThan(body.indexOf('applyActiveDocument();'));
    expect(body.indexOf('applyActiveDocument();')).toBeLessThan(body.indexOf('syncPaneLayout();'));
    expect(body.indexOf('syncPaneLayout();')).toBeLessThan(body.indexOf('syncActiveDocumentChrome();'));
  });

  it('reaches syncLayoutChrome through syncActiveDocumentChrome rather than around it', () => {
    expect(functionBody(APP_SOURCE, 'syncActiveDocumentChrome')).toContain('syncLayoutChrome(');
  });
});

describe('the pane layer uses that door and no other', () => {
  const paneBody = () => functionBody(APP_SOURCE, 'documentPane');

  it('changes the active document by calling activateDocument', () => {
    expect(paneBody()).toContain('activateDocument(document_.id)');
  });

  it('never touches the workspace or the working view directly', () => {
    const body = paneBody();

    for (const forbidden of [
      'workspace.activate(',
      'applyActiveDocument(',
      'captureActiveDocument(',
      'stopElasticRendering(',
      'startD3Elastic(',
      'syncLayoutChrome(',
    ]) {
      expect(body, `documentPane must not call ${forbidden} itself`).not.toContain(forbidden);
    }
  });

  // The strip is presentation. If it ever becomes a control surface that is a separate decision, and
  // it must still arrive through `activateDocument`.
  it('puts no handler on the header strip', () => {
    const body = paneBody();
    const headerSection = body.slice(body.indexOf('doc-pane-header'), body.indexOf('pane.append(header)'));

    expect(headerSection).not.toContain('addEventListener');
  });
});

describe('the canvas is built inside its pane and never moved', () => {
  it('asks for the pane before creating the canvas', () => {
    const body = functionBody(APP_SOURCE, 'documentContainer');

    expect(body).toContain('documentPane(document_)');
    expect(body.indexOf('documentPane(document_)'))
      .toBeLessThan(body.indexOf("createElement('div')"));
    // Appended to the pane, not to the shared host.
    expect(body).toContain('pane.append(element)');
    expect(body).not.toContain('host.append(element)');
  });

  // Re-parenting an existing `.doc-canvas` stops its Cytoscape instance painting for good, so the
  // pane layer is only ever allowed to append a pane, remove a pane, or toggle a class.
  it('never re-parents a container anywhere in the file', () => {
    for (const forbidden of [
      '.append(document_.container)',
      '.append(target.container)',
      '.appendChild(document_.container)',
      'insertBefore(document_.container',
    ]) {
      expect(APP_SOURCE, `re-parenting a canvas (${forbidden}) stops it painting`).not.toContain(forbidden);
    }
  });
});

// ── The scan reached real source ─────────────────────────────────────────────────────────────────
//
// Without these, a moved file or a brace matcher that swallowed the rest of the file would report a
// perfectly clean contract and prove nothing whatsoever.

describe('the scan proves it read what it claims to have read', () => {
  it('reads a substantial file rather than an empty one', () => {
    expect(APP_SOURCE.length).toBeGreaterThan(50_000);
  });

  it('extracts a body that is a fragment of the file and not the whole of it', () => {
    const body = functionBody(APP_SOURCE, 'activateDocument');

    expect(body.length).toBeGreaterThan(80);
    expect(body.length).toBeLessThan(APP_SOURCE.length / 4);
  });

  it('extracts the right function when two names share a prefix', () => {
    const fixture = `
      function activate(id) { return 'wrong'; }
      function activateDocument(id) { const inner = { a: 1 }; return 'right'; }
    `;

    expect(functionBody(fixture, 'activateDocument')).toContain("'right'");
    expect(functionBody(fixture, 'activateDocument')).not.toContain("'wrong'");
  });

  it('stops at the closing brace rather than running to the end of the source', () => {
    const fixture = `
      function target() { if (true) { return 1; } }
      function after() { return 'outside'; }
    `;

    expect(functionBody(fixture, 'target')).not.toContain('outside');
  });
});
