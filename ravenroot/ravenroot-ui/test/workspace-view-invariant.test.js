import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// ── The capture-before-read invariant, enforced instead of merely documented ───
//
// `app.js` keeps the active document's state in module-level variables. Those variables are a
// WORKING VIEW; the document record is the home of the state. `captureActiveDocument` writes the
// view back into the record being left and `applyActiveDocument` loads the record being entered, so
// the two are the only places where the record and the view are legitimately both in play.
//
// The rule this file enforces:
//
// No READ of a document-record field through `workspace.active` outside those two functions.
//
// Permitted everywhere, because none of these reads state that the working view mirrors:
// - `workspace.activeId` — an identifier, not document state
// - `Boolean(workspace.active)` / `if (!x)` — an existence test
// - `owner === workspace.active` — an identity comparison
// - `documentContainer(workspace.active)` — passing the whole record
// - `workspace.active.cy = cy` — a WRITE
// - `workspace.active.fontSize = px` — a WRITE from the active slider
//
// Writes are deliberately allowed and reads are not, because the hazard is asymmetric. A read
// through `workspace.active` can silently return state that the working view has not been given —
// two answers to one question, with nothing on screen to say which one is stale. A write through it
// can only make the record more current, never less. Enforcing the symmetric rule would have meant
// rewriting a correct line to satisfy a guard, which is how guards acquire a reputation for noise.
//
// The scan runs against the real file text. To prove it is not scanning an empty string or a
// mangled one, it asserts a POSITIVE CONTROL — the one write it must still find — and the detector
// itself is exercised against fixtures that must and must not trip it.

// `fileURLToPath` is given a string, not a URL object: the jsdom environment replaces the global
// `URL`, and Node refuses a jsdom URL instance. This mirrors `edge-fixture.test.js`, which reads a
// file from the same suite for the same reason.
const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');
const AUTHORISED_FUNCTIONS = ['captureActiveDocument', 'applyActiveDocument'];

// `workspace.active` (or `workspace.active?.`) followed by at least one property step.
// `workspace.activeId` does not match: there is no `.` after `active`.
const FIELD_ACCESS = /workspace\.active\??(?:\.[A-Za-z_$][\w$]*)+/g;
// An assignment, but not `==`, `===` or `=>`.
const ASSIGNMENT_AHEAD = /^\s*=(?![=>])/;

function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    // The `[^:]` guard keeps `https://` in a string literal from swallowing the rest of its line.
    .replace(/(^|[^:])\/\/[^\n]*/gm, '$1');
}

// Removes a whole function declaration by brace matching, so its body is excluded from the scan.
// Throws when the function is absent: a renamed or deleted sync function must break this test
// loudly rather than quietly widen what the rest of the file is allowed to do.
function removeFunctionDeclaration(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must exist in app.js for the invariant to mean anything`).toBeGreaterThan(-1);

  let index = source.indexOf('{', start);
  let depth = 0;
  for (; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) { index += 1; break; }
    }
  }
  return source.slice(0, start) + source.slice(index);
}

function findActiveFieldAccesses(source) {
  return [...source.matchAll(FIELD_ACCESS)].map(match => ({
    text: match[0],
    isWrite: ASSIGNMENT_AHEAD.test(source.slice(match.index + match[0].length)),
  }));
}

function scanAppSource() {
  const stripped = stripComments(readFileSync(APP_SOURCE_PATH, 'utf8'));
  const outsideSync = AUTHORISED_FUNCTIONS.reduce(removeFunctionDeclaration, stripped);
  return findActiveFieldAccesses(outsideSync);
}

describe('the document record stays authoritative and the module variables stay a view', () => {
  it('reads no document-record field through workspace.active outside the two sync functions', () => {
    const reads = scanAppSource().filter(access => !access.isWrite);

    expect(reads.map(access => access.text)).toEqual([]);
  });

  // Positive control. Without it, a scan that silently produced an empty string — a moved file, a
  // comment stripper that ate the source, a brace matcher that removed everything after the first
  // function — would report a clean invariant and prove nothing at all.
  it('still finds the one permitted write, proving the scan reached real source', () => {
    const writes = scanAppSource().filter(access => access.isWrite);

    expect(writes.map(access => access.text)).toEqual([
      'workspace.active.cy',
      'workspace.active.fontSize',
    ]);
  });

  it('reads a substantial file rather than an empty one', () => {
    expect(readFileSync(APP_SOURCE_PATH, 'utf8').length).toBeGreaterThan(50_000);
  });
});

// ── The detector has teeth ────────────────────────────────────────────────────────────────────────
//
// A guard is only worth its runtime if it can fail. These exercise the same detector the assertions
// above use, against source it must reject and source it must accept.

describe('the invariant detector catches what it claims to catch', () => {
  it('flags a field read through workspace.active', () => {
    const offending = `
      function paintSomething() {
        const running = Boolean(workspace.active.execution.executionId);
        return running;
      }
    `;

    const reads = findActiveFieldAccesses(offending).filter(access => !access.isWrite);

    expect(reads.map(access => access.text)).toEqual(['workspace.active.execution.executionId']);
  });

  it('flags an optional-chained field read, which is the form that slipped through before', () => {
    const offending = 'const execution = workspace.active?.execution;';

    const reads = findActiveFieldAccesses(offending).filter(access => !access.isWrite);

    expect(reads.map(access => access.text)).toEqual(['workspace.active?.execution']);
  });

  it('accepts identity comparisons, existence tests, whole-record passing and the id', () => {
    const allowed = `
      function lifecycle(id) {
        if (!workspace.active) return null;
        const hasDocument = Boolean(workspace.active);
        const owner = workspace.active;
        if (owner === workspace.active) return workspace.activeId;
        if (workspace.activeId === id) return owner;
        return documentContainer(workspace.active);
      }
    `;

    expect(findActiveFieldAccesses(allowed)).toEqual([]);
  });

  it('classifies a write as a write and not as a read', () => {
    const write = 'if (workspace.active) workspace.active.cy = cy;';

    const accesses = findActiveFieldAccesses(write);

    expect(accesses).toEqual([{ text: 'workspace.active.cy', isWrite: true }]);
  });

  it('does not mistake a comparison for an assignment', () => {
    const comparison = 'if (workspace.active.id === wanted) return true;';

    const accesses = findActiveFieldAccesses(comparison);

    expect(accesses).toEqual([{ text: 'workspace.active.id', isWrite: false }]);
  });

  it('ignores the rule when it is only being discussed in a comment', () => {
    const commented = `
      // Never write workspace.active.execution.executionId from here.
      const safe = 1;
    `;

    expect(findActiveFieldAccesses(stripComments(commented))).toEqual([]);
  });
});
