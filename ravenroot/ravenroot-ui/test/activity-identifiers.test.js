import { dirname, resolve } from 'node:path';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// Process, traversal and invocation are not synonyms. A process is the
// long-lived instance; a traversal is one crossing of the graph (a process can have several —
// exists so a new traversal can resume the same process after a wait); an invocation is one node
// executing inside a traversal, carrying attempts beneath it. Before this, the activity log's own
// rendering (`appendActivityEvent` in src/app.js) read only `event.executionId` — the legacy
// traversalId alias — and showed nothing else, even though `processInstanceId`, `invocationId` and
// `attemptId` were already sitting on every event unread (verified directly against
// ExecutionEvent.java and RavenrootServer#writeEvent: the SSE wire carries all four; only the
// renderer dropped three of them).
//
// This file exercises the REAL `activityIdentifiersHtml` from `src/app.js` — extracted from the live
// source by brace-matching, the same technique `catalog-property-blank-editor.test.js` and
// `workspace-view-invariant.test.js` use for this codebase's non-exported functions — rather than a
// reimplementation. `shortId`/`escapeHtml` are extracted alongside it because the function under test
// calls them directly.

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');

function extractFunctionSource(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must exist in app.js for this control to mean anything`).toBeGreaterThan(-1);
  let index = source.indexOf('{', start);
  let depth = 0;
  for (; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) { index += 1; break; }
    }
  }
  return source.slice(start, index);
}

function loadActivityIdentifiersHtml() {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  const shortIdSrc = extractFunctionSource(source, 'shortId');
  const escapeHtmlSrc = extractFunctionSource(source, 'escapeHtml');
  const targetSrc = extractFunctionSource(source, 'activityIdentifiersHtml');
  // eslint-disable-next-line no-new-func
  const factory = new Function(`
    ${shortIdSrc}
    ${escapeHtmlSrc}
    ${targetSrc}
    return activityIdentifiersHtml;
  `);
  return factory();
}

// Real-shaped UUIDs (distinct per field, distinct between the two traversals) so a test failure
// names an actual mismatched value rather than two fixtures that happen to collide by using the
// same short placeholder string for everything.
const PROCESS_A = '11111111-aaaa-4aaa-8aaa-111111111111';
const TRAVERSAL_1 = '22222222-bbbb-4bbb-8bbb-222222222222';
const TRAVERSAL_2 = '33333333-cccc-4ccc-8ccc-333333333333';
const INVOCATION_1 = '44444444-dddd-4ddd-8ddd-444444444444';
const INVOCATION_2 = '55555555-eeee-4eee-8eee-555555555555';
const ATTEMPT_1 = '66666666-ffff-4fff-8fff-666666666666';
const HANDLER_1 = '77777777-9999-4999-8999-777777777777';

function eventWith(overrides) {
  return {
    processInstanceId: PROCESS_A,
    traversalId: TRAVERSAL_1,
    invocationId: INVOCATION_1,
    attemptId: ATTEMPT_1,
    ...overrides,
  };
}

describe('activityIdentifiersHtml renders process/traversal/invocation/attempt as distinct, labelled values', () => {
  it('renders all four identifiers, each truncated the same way shortId already truncates ids elsewhere', () => {
    const activityIdentifiersHtml = loadActivityIdentifiersHtml();
    const html = activityIdentifiersHtml(eventWith({}));

    expect(html).toContain(`data-id-kind="process"`);
    expect(html).toContain(PROCESS_A.slice(0, 8));
    expect(html).toContain(`data-id-kind="traversal"`);
    expect(html).toContain(TRAVERSAL_1.slice(0, 8));
    expect(html).toContain(`data-id-kind="invocation"`);
    expect(html).toContain(INVOCATION_1.slice(0, 8));
    expect(html).toContain(`data-id-kind="attempt"`);
    expect(html).toContain(ATTEMPT_1.slice(0, 8));
  });

  it('an invocation-less event (execution-level, per ExecutionEvent\'s own javadoc) shows the placeholder, not a blank or a borrowed id', () => {
    const activityIdentifiersHtml = loadActivityIdentifiersHtml();
    const html = activityIdentifiersHtml(eventWith({ invocationId: null, attemptId: null }));

    // shortId's own established placeholder ('—'), reused rather than a second convention.
    const invocationSpan = html.match(/data-id-kind="invocation">[^<]*<code>([^<]*)<\/code>/)[1];
    const attemptSpan = html.match(/data-id-kind="attempt">[^<]*<code>([^<]*)<\/code>/)[1];
    expect(invocationSpan).toBe('—');
    expect(attemptSpan).toBe('—');
  });

  describe('two traversals of the same process retain their shared process identity', () => {
    it('same processInstanceId, different traversalId: the process label matches and the traversal label does not', () => {
      const activityIdentifiersHtml = loadActivityIdentifiersHtml();
      const rowOne = activityIdentifiersHtml(eventWith({ traversalId: TRAVERSAL_1, invocationId: INVOCATION_1 }));
      const rowTwo = activityIdentifiersHtml(eventWith({ traversalId: TRAVERSAL_2, invocationId: INVOCATION_2 }));

      const processOf = html => html.match(/data-id-kind="process">[^<]*<code>([^<]*)<\/code>/)[1];
      const traversalOf = html => html.match(/data-id-kind="traversal">[^<]*<code>([^<]*)<\/code>/)[1];

      expect(processOf(rowOne)).toBe(processOf(rowTwo));
      expect(traversalOf(rowOne)).not.toBe(traversalOf(rowTwo));
      expect(traversalOf(rowOne)).toBe(TRAVERSAL_1.slice(0, 8));
      expect(traversalOf(rowTwo)).toBe(TRAVERSAL_2.slice(0, 8));
    });
  });

  // ── The mutation criterion, applied deliberately ────────────────────────────────────────────────
  //
  // A test rendering an identifier is not evidence that it renders the RIGHT one: one assertion
  // per field, each pinned to the field it actually
  // reads, so that swapping which event property feeds which labelled span reds EXACTLY one of the
  // four assertions below — never zero (the substitution would be invisible) and never all four
  // (a single swap should not cascade).
  describe('mutation criterion: substituting one identifier for another reds exactly that one assertion', () => {
    const event = eventWith({});
    const html = loadActivityIdentifiersHtml()(event);
    const valueFor = kind => html.match(new RegExp(`data-id-kind="${kind}">[^<]*<code>([^<]*)</code>`))[1];

    it('the "process" span carries processInstanceId, not any other field', () => {
      expect(valueFor('process')).toBe(event.processInstanceId.slice(0, 8));
    });
    it('the "traversal" span carries traversalId, not any other field', () => {
      expect(valueFor('traversal')).toBe(event.traversalId.slice(0, 8));
    });
    it('the "invocation" span carries invocationId, not any other field', () => {
      expect(valueFor('invocation')).toBe(event.invocationId.slice(0, 8));
    });
    it('the "attempt" span carries attemptId, not any other field', () => {
      expect(valueFor('attempt')).toBe(event.attemptId.slice(0, 8));
    });
  });

  // ── The fifth row, and the only conditional one ─────────────────────────────────────────────────
  //
  // The four above are the execution hierarchy every event sits somewhere in, so an em dash for an
  // absent one reads as "above that level". A handler is not part of that hierarchy: it is the
  // durable wait a process is parked on, and only handler-lifecycle events carry one. Rendering an
  // empty `handler —` on every node event would assert that every event has a handler slot, which is
  // the opposite of the distinction the row exists to make — so absence here means the span is gone,
  // not that it holds a placeholder, and both halves are asserted.
  describe('a handler-lifecycle event names its handler as a fifth, conditional identifier', () => {
    it('renders the handler span when the event carries a handlerId, without displacing the other four', () => {
      const activityIdentifiersHtml = loadActivityIdentifiersHtml();
      const html = activityIdentifiersHtml(eventWith({ handlerId: HANDLER_1 }));

      expect(html).toContain(`data-id-kind="handler"`);
      expect(html).toContain(HANDLER_1.slice(0, 8));
      for (const kind of ['process', 'traversal', 'invocation', 'attempt']) {
        expect(html).toContain(`data-id-kind="${kind}"`);
      }
    });

    it('omits the handler span entirely on an event with no handler, rather than showing an empty slot', () => {
      const activityIdentifiersHtml = loadActivityIdentifiersHtml();
      const html = activityIdentifiersHtml(eventWith({}));

      expect(html).not.toContain(`data-id-kind="handler"`);
      expect(html).toContain(`data-id-kind="invocation"`);
    });

    it('the "handler" span carries handlerId and not the traversal it resumed', () => {
      const activityIdentifiersHtml = loadActivityIdentifiersHtml();
      const html = activityIdentifiersHtml(eventWith({ handlerId: HANDLER_1 }));
      const valueFor = kind => html.match(new RegExp(`data-id-kind="${kind}">[^<]*<code>([^<]*)</code>`))[1];

      expect(valueFor('handler')).toBe(HANDLER_1.slice(0, 8));
      expect(valueFor('handler')).not.toBe(TRAVERSAL_1.slice(0, 8));
    });
  });

  it('proves the extraction reached real source: the function is found and produces non-empty markup', () => {
    const activityIdentifiersHtml = loadActivityIdentifiersHtml();
    const html = activityIdentifiersHtml(eventWith({}));
    expect(html.length).toBeGreaterThan(50);
  });
});
