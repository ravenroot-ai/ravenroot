import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { resolveOutcomes, unreachableOutcome } from '../src/node-outcomes.js';

// ── THE EDGE INSPECTOR WARNS WHEN THE SOURCE CANNOT EMIT THE TYPED OUTCOME ─────────────────────
//
// `node-outcomes.test.js` proves the predicate. This file proves the WIRING: the inspector reads the
// SOURCE node, the warning appears and disappears as the field changes, and the warning remains
// advisory rather than blocking the save.
//
// `refreshOutcomeWarning` is extracted from the live `src/app.js` by brace-matching rather than
// reimplemented — the same technique `edge-outcome-suggestions.test.js` uses for suggestion wiring,
// and for the same reason: a reimplementation would restate the logic under test and prove nothing.
// `resolveOutcomes` and `unreachableOutcome` are injected as the REAL imports, so this stops proving
// anything the moment app.js's call site drifts from the module.

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

/**
 * The real `refreshOutcomeWarning`, closed over a real form and a real warning element.
 *
 * `outcomeWarning` is injected because app.js resolves it OUTSIDE the function, next to the comment
 * that explains why the element is separate from `#edge-validation`.
 */
function mountInspector(graphData, catalog, { source: selectedSource, outcome, readOnly = false }) {
  const appSource = readFileSync(APP_SOURCE_PATH, 'utf8');
  // Both halves, because `refreshOutcomeWarning` delegates every write to `setOutcomeWarning` — the
  // assign-only-if-changed guard that keeps the live region from being rewritten per keystroke.
  // Extracting only the caller would fail with a ReferenceError, which is how this line came to exist,
  // and reimplementing the guard here would stop proving that app.js still routes writes through it.
  const body = `${extractFunctionSource(appSource, 'setOutcomeWarning')}
    ${extractFunctionSource(appSource, 'refreshOutcomeWarning')}`;

  const form = document.createElement('form');
  form.innerHTML = `
    <select name="source">${graphData.nodes.map(node =>
    `<option value="${node.id}">${node.id}</option>`).join('')}</select>
    <input name="outcome" list="edge-outcome-options" value="">
    <p id="edge-outcome-warning" hidden></p>`;
  document.body.appendChild(form);
  form.elements.source.value = selectedSource;
  form.elements.outcome.value = outcome;
  form.elements.outcome.readOnly = readOnly;

  const outcomeWarning = document.getElementById('edge-outcome-warning');
  const catalogDescriptor = behavior => catalog.find(type => type.behavior === behavior) || null;

  // eslint-disable-next-line no-new-func
  const factory = new Function('graphData', 'form', 'outcomeWarning', 'catalogDescriptor',
    'resolveOutcomes', 'unreachableOutcome', `
    ${body}
    return refreshOutcomeWarning;
  `);
  const refresh = factory(graphData, form, outcomeWarning, catalogDescriptor,
    resolveOutcomes, unreachableOutcome);
  return { form, outcomeWarning, refresh };
}

const CATALOG = [
  {
    behavior: 'delay',
    properties: [{ name: 'durationMs', defaultValue: '1000' }],
    outcomes: [{ name: 'continue', fromProperty: '', description: 'The wait elapsed.' }],
  },
  {
    behavior: 'cel-decision',
    properties: [
      { name: 'trueOutcome', defaultValue: 'true' },
      { name: 'falseOutcome', defaultValue: 'false' },
    ],
    outcomes: [
      { name: '', fromProperty: 'trueOutcome', description: 'The expression evaluated to true.' },
      { name: '', fromProperty: 'falseOutcome', description: 'The expression evaluated otherwise.' },
    ],
  },
  {
    behavior: 'http-request',
    properties: [
      { name: 'successOutcome', defaultValue: 'continue' },
      { name: 'failureOutcome', defaultValue: 'failed' },
    ],
    outcomes: [
      { name: '', fromProperty: 'successOutcome', description: 'The response was a 2xx.' },
      { name: '', fromProperty: 'failureOutcome', description: 'The response was not a 2xx.' },
    ],
  },
  // An extension as they are shipped today: no `outcomes` at all.
  { behavior: 'mail-send', properties: [], outcomes: [] },
];

const GRAPH = {
  nodes: [
    { id: 'wait-1', name: 'Wait', behavior: 'delay', properties: { durationMs: '5' } },
    {
      id: 'decide-1',
      name: 'Approve?',
      behavior: 'cel-decision',
      properties: { trueOutcome: 'approved', falseOutcome: 'rejected' },
    },
    {
      id: 'call-1',
      name: 'Fetch',
      behavior: 'http-request',
      properties: { successOutcome: 'ok', failureOutcome: 'broken' },
    },
    { id: 'mail-1', name: 'Notify', behavior: 'mail-send', properties: {} },
    { id: 'mystery-1', name: 'Unknown', behavior: 'not-in-catalog', properties: {} },
  ],
};

afterEach(() => { document.body.innerHTML = ''; });

describe('edge inspector unreachable-outcome warning', () => {
  it('warns when the outcome is one the source node cannot emit', () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'decide-1', outcome: 'escalated' });
    refresh();
    expect(outcomeWarning.hidden).toBe(false);
    // Names the SOURCE, so the author knows which node the verdict is about, and states the
    // consequence rather than re-listing the outcomes `#edge-outcome-hint` has just listed.
    expect(outcomeWarning.textContent).toContain('Approve?');
    expect(outcomeWarning.textContent).toContain('never be taken');
  });

  it('stays silent for an outcome the source node does emit', () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'decide-1', outcome: 'approved' });
    refresh();
    expect(outcomeWarning.hidden).toBe(true);
    expect(outcomeWarning.textContent).toBe('');
  });

  // At the wiring level, use the author's own names on both parameterized built-ins.
  it("reads http-request's author-named outcomes rather than the declared defaults", () => {
    const reachable = mountInspector(GRAPH, CATALOG, { source: 'call-1', outcome: 'ok' });
    reachable.refresh();
    expect(reachable.outcomeWarning.hidden).toBe(true);
    document.body.innerHTML = '';

    // 'failed' is http-request's DECLARED default and this node renamed it, so it is now unreachable.
    const renamed = mountInspector(GRAPH, CATALOG, { source: 'call-1', outcome: 'failed' });
    renamed.refresh();
    expect(renamed.outcomeWarning.hidden).toBe(false);
    expect(renamed.outcomeWarning.textContent).toContain('Fetch');
  });

  /**
   * The `continue` exemption, reached through the real inspector rather than the predicate alone.
   * `delay` declares 'continue' and nothing else, so 'timed-out' leaving it is genuinely unreachable
   * while 'continue' must stay silent no matter what.
   */
  it("never warns on an edge wired to 'continue'", () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'decide-1', outcome: 'continue' });
    refresh();
    expect(outcomeWarning.hidden).toBe(true);
  });

  it('warns on a wrong outcome leaving a delay node', () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'wait-1', outcome: 'timed-out' });
    refresh();
    expect(outcomeWarning.hidden).toBe(false);
    expect(outcomeWarning.textContent).toContain('Wait');
  });

  /**
   * An extension that declares no outcomes has an unknown outcome set, not an empty one. Reading it
   * as 'emits nothing' would warn on every edge leaving every mail, telegram, kafka and amqp node.
   */
  it('says nothing at all about a source whose behavior declares no outcomes', () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'mail-1', outcome: 'delivered' });
    refresh();
    expect(outcomeWarning.hidden).toBe(true);
    expect(outcomeWarning.textContent).toBe('');
  });

  it('says nothing about a source whose behavior the catalog does not carry', () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'mystery-1', outcome: 'whatever' });
    refresh();
    expect(outcomeWarning.hidden).toBe(true);
  });

  /**
   * While the edge is a declared failure route the Outcome field is readonly and parked at
   * the default. It carries the node's FAILURE, not one of its outcomes, so there is nothing here to
   * be unreachable — and a warning would contradict the panel directly above it.
   */
  it('says nothing while the edge is a declared failure route', () => {
    const { outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'decide-1', outcome: 'escalated', readOnly: true });
    refresh();
    expect(outcomeWarning.hidden).toBe(true);
    expect(outcomeWarning.textContent).toBe('');
  });

  // The warning is a function of the SOURCE, not of the target or of the edge alone.
  it('clears itself when the source changes to one that can emit the outcome', () => {
    const { form, outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'decide-1', outcome: 'continue' });
    refresh();
    expect(outcomeWarning.hidden).toBe(true);

    form.elements.outcome.value = 'escalated';
    refresh();
    expect(outcomeWarning.hidden).toBe(false);

    form.elements.source.value = 'mail-1';
    refresh();
    expect(outcomeWarning.hidden).toBe(true);
  });

  /**
   * The value is deliberately not quoted back, so the sentence stays byte-identical while the author
   * keeps typing an unknown outcome and the `aria-live` region announces once rather than per
   * keystroke. Asserted because it is a property a well-meaning edit would remove.
   */
  it('does not quote the typed value, so the live region does not re-announce per keystroke', () => {
    const { form, outcomeWarning, refresh } = mountInspector(GRAPH, CATALOG,
      { source: 'decide-1', outcome: 'esc' });
    refresh();
    const first = outcomeWarning.textContent;
    form.elements.outcome.value = 'escal';
    refresh();
    expect(outcomeWarning.textContent).toBe(first);
    expect(outcomeWarning.textContent).not.toContain('escal');
  });

  /**
   * Advisory, not a verdict. the constraint is that a false positive costs more than silence, and
   * the corollary is that this must never be able to stop a save: an author renaming a source node's
   * `trueOutcome` after drawing the edge passes through exactly this state on the way to a correct
   * graph. Asserted over app.js's source because the coupling would be introduced elsewhere — in
   * `revalidateEdgeForm`, which owns `submit.disabled` — and not inside the function above.
   */
  it('never disables Save and adds no workflow violation', () => {
    const appSource = readFileSync(APP_SOURCE_PATH, 'utf8');
    const body = extractFunctionSource(appSource, 'refreshOutcomeWarning');
    expect(body).not.toContain('submit');
    expect(body).not.toContain('disabled');

    const validate = readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), '../src/graph-document.js'), 'utf8');
    expect(validate).not.toContain('unreachableOutcome');
  });

  /**
   * The warning element is only reachable because `renderEdgeForm` emits it with this id, hidden, and
   * as a live region. Asserting over the real markup means renaming one half without the other fails
   * here rather than shipping a warning nobody ever sees.
   */
  it('the inspector renders the element this fills, hidden and announced', () => {
    const appSource = readFileSync(APP_SOURCE_PATH, 'utf8');
    expect(appSource).toContain('id="edge-outcome-warning"');
    expect(appSource).toContain('<p class="edge-outcome-warning" id="edge-outcome-warning"'
      + ' role="status" aria-live="polite" hidden>');
  });

  /**
   * Amber and not red, and a different box from `#edge-validation`, which is the one that gates the
   * save. If the two ever look alike, an advisory reads as a refusal and refusals start being
   * dismissed — the failure mode exists to avoid.
   */
  it('is styled as a warning rather than as the save-blocking error', () => {
    const styles = readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), '../src/styles.css'), 'utf8');
    const rule = styles.slice(styles.indexOf('.edge-outcome-warning {'));
    expect(rule.slice(0, rule.indexOf('}'))).toContain('var(--amber)');
    expect(rule.slice(0, rule.indexOf('}'))).not.toContain('var(--err)');
    expect(styles).toContain('.edge-outcome-warning[hidden] { display: none; }');
  });
});
