import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { resolveOutcomes } from '../src/node-outcomes.js';

// ── THE EDGE INSPECTOR PROPOSES OUTCOMES THE SOURCE NODE CAN PRODUCE ───────────────────────────
//
// `node-outcomes.test.js` proves the resolution. This file proves the separate WIRING invariant: the
// inspector reads the source node rather than the target, rebuilds when Source changes, and puts the
// result where the Outcome field's `list` attribute points. A test over `resolveOutcomes` alone would
// pass with the datalist never populated.
//
// `refreshOutcomeOptions` is extracted from the live `src/app.js` by brace-matching rather than
// reimplemented — the technique `catalog-property-undeclared-choice.test.js`,
// `catalog-property-blank-editor.test.js` and `workspace-view-invariant.test.js` already use for this
// codebase's non-exported functions. A reimplementation would restate the logic under test and prove
// nothing. `resolveOutcomes` is injected as the REAL import for the same reason that file injects
// `adapterIdOf` as one: it lives in its own module, so extracting a copy would stop proving anything
// the moment app.js's call site changed.

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
 * The real `refreshOutcomeOptions`, closed over a real form, a real datalist and a real hint element.
 *
 * The markup mirrors what `renderEdgeForm` emits for the two controls this function touches: an
 * Outcome input whose `list` names the datalist, and the datalist itself. If those ever drift apart in
 * app.js, `theOutcomeFieldPointsAtTheDatalistThisFills` below is what notices.
 */
function mountInspector(graphData, catalog, selectedSource) {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  const body = extractFunctionSource(source, 'refreshOutcomeOptions');

  const form = document.createElement('form');
  form.innerHTML = `
    <select name="source">${graphData.nodes.map(node =>
    `<option value="${node.id}">${node.id}</option>`).join('')}</select>
    <input name="outcome" list="edge-outcome-options" value="continue">
    <small id="edge-outcome-hint"></small>
    <datalist id="edge-outcome-options"></datalist>`;
  document.body.appendChild(form);
  form.elements.source.value = selectedSource;

  const outcomeList = document.getElementById('edge-outcome-options');
  const outcomeHint = document.getElementById('edge-outcome-hint');
  const catalogDescriptor = behavior => catalog.find(type => type.behavior === behavior) || null;
  const escapeAttribute = value => String(value).replace(/"/g, '&quot;');
  const escapeHtml = value => String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  // eslint-disable-next-line no-new-func
  const factory = new Function('graphData', 'form', 'outcomeList', 'outcomeHint', 'catalogDescriptor',
    'resolveOutcomes', 'escapeAttribute', 'escapeHtml', `
    ${body}
    return refreshOutcomeOptions;
  `);
  const refresh = factory(graphData, form, outcomeList, outcomeHint, catalogDescriptor,
    resolveOutcomes, escapeAttribute, escapeHtml);
  return { form, outcomeList, outcomeHint, refresh };
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
    { id: 'decide-default', name: 'Plain decision', behavior: 'cel-decision', properties: {} },
    { id: 'mystery-1', name: 'Unknown', behavior: 'not-in-catalog', properties: {} },
  ],
};

function optionValues(outcomeList) {
  return [...outcomeList.querySelectorAll('option')].map(option => option.value);
}

afterEach(() => { document.body.innerHTML = ''; });

describe('edge inspector outcome suggestions', () => {
  it('suggests the fixed outcome of a source node that declares one', () => {
    const { outcomeList, refresh } = mountInspector(GRAPH, CATALOG, 'wait-1');
    refresh();
    expect(optionValues(outcomeList)).toEqual(['continue']);
  });

  // The point of the whole feature: the author's own names, read off the source node.
  it("suggests the source node's own outcome names, not the behavior's defaults", () => {
    const { outcomeList, refresh } = mountInspector(GRAPH, CATALOG, 'decide-1');
    refresh();
    expect(optionValues(outcomeList)).toEqual(['approved', 'rejected']);
  });

  it('falls back to the declared defaults for a source node that configured neither', () => {
    const { outcomeList, refresh } = mountInspector(GRAPH, CATALOG, 'decide-default');
    refresh();
    expect(optionValues(outcomeList)).toEqual(['true', 'false']);
  });

  // Suggestions belong to the source because the outcome is what the source PRODUCES. Reading the
  // target instead would be a plausible-looking mistake that every other assertion here would survive.
  it('rebuilds from the new source when Source changes', () => {
    const { form, outcomeList, refresh } = mountInspector(GRAPH, CATALOG, 'wait-1');
    refresh();
    expect(optionValues(outcomeList)).toEqual(['continue']);

    form.elements.source.value = 'decide-1';
    refresh();
    expect(optionValues(outcomeList)).toEqual(['approved', 'rejected']);
  });

  it('names the property a parameterized suggestion came from, so the author knows what to edit', () => {
    const { outcomeHint, refresh } = mountInspector(GRAPH, CATALOG, 'decide-1');
    refresh();
    expect(outcomeHint.textContent).toContain('approved, rejected');
    expect(outcomeHint.textContent).toContain('trueOutcome');
    expect(outcomeHint.textContent).toContain('falseOutcome');
  });

  // An empty list must not read as "this node produces nothing".
  it('says so plainly when the source node declares no outcomes', () => {
    const { outcomeList, outcomeHint, refresh } = mountInspector(GRAPH, CATALOG, 'mystery-1');
    refresh();
    expect(optionValues(outcomeList)).toEqual([]);
    expect(outcomeHint.textContent).toContain('declares no outcomes');
  });

  it('carries each outcome description as the option label', () => {
    const { outcomeList, refresh } = mountInspector(GRAPH, CATALOG, 'decide-1');
    refresh();
    expect([...outcomeList.querySelectorAll('option')].map(option => option.textContent))
      .toEqual(['The expression evaluated to true.', 'The expression evaluated otherwise.']);
  });

  /**
   * The datalist is only reachable because the Outcome input's `list` attribute names it. This asserts
   * over the real app.js markup, so renaming one half without the other fails here rather than shipping
   * a suggester the browser never consults.
   */
  it('the outcome field points at the datalist this fills', () => {
    const source = readFileSync(APP_SOURCE_PATH, 'utf8');
    expect(source).toContain('<input name="outcome" list="edge-outcome-options"');
    expect(source).toContain('<datalist id="edge-outcome-options">');
  });

  /**
   * At the wiring level, a behavior that newly declares an outcome
   * reaches the inspector through the catalog alone. Nothing in app.js names an outcome.
   */
  it('surfaces an outcome a behavior newly declares, with no change to the editor', () => {
    const catalog = [...CATALOG, {
      behavior: 'invented',
      properties: [{ name: 'timeoutOutcome', defaultValue: 'timed-out' }],
      outcomes: [
        { name: 'continue', fromProperty: '', description: 'Finished.' },
        { name: '', fromProperty: 'timeoutOutcome', description: 'Ran out of time.' },
      ],
    }];
    const graph = { nodes: [{ id: 'new-1', name: 'New', behavior: 'invented', properties: {} }] };
    const { outcomeList, refresh } = mountInspector(graph, catalog, 'new-1');
    refresh();
    expect(optionValues(outcomeList)).toEqual(['continue', 'timed-out']);
  });
});
