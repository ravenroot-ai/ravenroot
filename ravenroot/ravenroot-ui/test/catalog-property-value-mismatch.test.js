import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { isPropertyRequiredNow, isPropertyVisible } from '../src/property-condition.js';
import { parseGraphML } from '../src/graph-parsers.js';
import { serializeGraphML } from '../src/graph-document.js';

// ── Opening a document must not rewrite a value merely because it does not byte-for-byte
// match a declared alternative ──────────────────────────────────────────────────────────────────
//
// `catalogPropertyFieldsHtml` compares the current value against `allowedValues` with exact string
// equality. `graph-parsers.js` preserves generic properties on read because trimming silently eats a
// `program` node's leading/trailing whitespace, whose sha256 is compared against what the author
// actually wrote. A padded or otherwise non-matching value therefore reaches this control. If no
// option matches, the browser pre-selects the FIRST
// option nobody chose, and submit -- which whole-replaces `node.properties`, see
// `catalogPropertyFieldsHtml`'s own comment -- wrote that unrelated first option over a value
// the author never touched. For BOOLEAN the corresponding faulty condition is
// `String(value) !== 'true'`, true for anything that is not the exact string "true", so the value
// degrades to `false` outright.
//
// A test that only inspects the rendered `<select>` proves the control looks right; it does not
// prove the DOCUMENT survives. This file goes all the way around: a real GraphML fixture with a
// padded/unrecognized value -> `parseGraphML` (the exact no-trim reader) -> a real
// `catalogPropertyFieldsHtml` render into a real `<form>` -> `readCatalogPropertyEditor` with NOTHING
// touched, standing in for "the author opened the node and pressed Save" -> the whole-replace
// `node.properties` a real submit would produce -> `serializeGraphML` -> `parseGraphML` again. Only
// the round trip proves the document itself is unchanged; the control could render a perfect-looking
// escape option and still lose the value if `readCatalogPropertyEditor` or the write side disagreed
// with it.
//
// Both functions are extracted from the live `src/app.js` by brace-matching, the same technique
// `catalog-property-undeclared-choice.test.js`, `catalog-property-blank-editor.test.js` and others in
// this suite already use for this codebase's non-exported functions -- a reimplementation would have
// reproduced the reasoning under test and proven nothing.

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');
const ADAPTER_BINDING_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/adapter-binding.js');

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

async function loadEditor() {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  const parts = ['escapeHtml', 'escapeAttribute', 'contextualHelpButtonHtml', 'catalogPropertyFieldsHtml',
    'readCatalogPropertyEditor', 'catalogTypeToGraphMl']
    .map(name => extractFunctionSource(source, name)).join('\n');

  const adapterModule = await import(pathToFileURL(ADAPTER_BINDING_PATH).href);

  // eslint-disable-next-line no-new-func
  const factory = new Function('adapterIdOf', 'catalogPropertyHasDeclaredDefault', 'isPropertyVisible',
    'isPropertyRequiredNow', `
    ${parts}
    return { catalogPropertyFieldsHtml, readCatalogPropertyEditor };
  `);
  return factory(adapterModule.adapterIdOf, adapterModule.catalogPropertyHasDeclaredDefault,
    isPropertyVisible, isPropertyRequiredNow);
}

/** Renders a descriptor into a REAL `<form>` in a real document, exactly as the node editor does. */
function renderForm(catalogPropertyFieldsHtml, descriptor, values) {
  const form = document.createElement('form');
  form.innerHTML = catalogPropertyFieldsHtml(descriptor, values);
  document.body.appendChild(form);
  return form;
}

afterEach(() => { document.body.innerHTML = ''; });

/** A closed choice WITH a declared default -- deliberately the shape the "Not declared" escape
 * does NOT cover, so it proves the value-mismatch path is distinct. `RETRY` is
 * placed SECOND on purpose: if the editor silently falls back to "whichever option is first",
 * that fallback (`SKIP`) differs from the padded document value even after trimming, so a still-buggy
 * implementation cannot pass this test by accident. */
const RETRY_MODE = {
  name: 'retryMode', displayName: 'Retry mode', type: 'STRING', required: false,
  description: 'How a failed attempt is retried.', defaultValue: 'SKIP',
  allowedValues: ['SKIP', 'RETRY'], adapterBinding: false, visibleWhen: null, requiredWhen: null,
};

const ENABLED = {
  name: 'enabled', displayName: 'Enabled', type: 'BOOLEAN', required: false,
  description: '', defaultValue: '', allowedValues: [], adapterBinding: false,
  visibleWhen: null, requiredWhen: null,
};

const descriptorWith = (...properties) => ({ displayName: 'Example node', properties });

/** A minimal, real GraphML document with a `retryMode` and `enabled` property on node `n0`, written
 * as raw XML text so the exact bytes -- including whitespace -- are under this test's control. Passed
 * through the same `parseGraphML` the app uses to open a file, so its no-trim read is exercised
 * for real rather than assumed. */
function graphmlWithNodeProperties(retryModeText, enabledText, enabledKeyType = 'boolean') {
  return '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
    + '<key id="k1" for="node" attr.name="retryMode" attr.type="string"/>'
    + `<key id="k2" for="node" attr.name="enabled" attr.type="${enabledKeyType}"/>`
    + '<graph id="g" edgedefault="directed">'
    + `<node id="n0"><data key="k1">${retryModeText}</data><data key="k2">${enabledText}</data></node>`
    + '</graph></graphml>';
}

/** Simulates exactly the write side of a node-editor submit for the properties under test: the
 * catalog editor's own read, whole-replacing `node.properties`/`propertyTypes` -- the same formula
 * `catalogPropertyFieldsHtml`'s own comment documents (`custom`/`nature`/`join` contribute
 * nothing here, since this node declares neither a join nor a runtime nature). */
function saveUntouched(catalogPropertyFieldsHtml, readCatalogPropertyEditor, descriptor, node) {
  const form = renderForm(catalogPropertyFieldsHtml, descriptor, node.properties);
  const { properties, propertyTypes } = readCatalogPropertyEditor(form);
  node.properties = properties;
  node.propertyTypes = propertyTypes;
  return form;
}

describe('a value that does not match a declared alternative must not be rewritten by an untouched save', () => {
  it('RED (round trip): a closed-choice value padded with whitespace survives an untouched open-save cycle',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const doc = graphmlWithNodeProperties('\n  RETRY\n', 'false');
      const graph = parseGraphML(doc);
      const node = graph.nodeMap.n0;
      // Sanity: this is really exercising the no-trim read, not a value this test invented.
      expect(node.properties.retryMode).toBe('\n  RETRY\n');

      saveUntouched(catalogPropertyFieldsHtml, readCatalogPropertyEditor,
        descriptorWith(RETRY_MODE, ENABLED), node);

      const reparsed = parseGraphML(serializeGraphML(graph));
      expect(reparsed.nodeMap.n0.properties.retryMode).toBe('\n  RETRY\n');
    });

  it('RED (round trip): a closed-choice value that is simply not one of the declared alternatives survives too',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const doc = graphmlWithNodeProperties('bogus-mode', 'false');
      const graph = parseGraphML(doc);
      const node = graph.nodeMap.n0;

      saveUntouched(catalogPropertyFieldsHtml, readCatalogPropertyEditor,
        descriptorWith(RETRY_MODE, ENABLED), node);

      const reparsed = parseGraphML(serializeGraphML(graph));
      expect(reparsed.nodeMap.n0.properties.retryMode).toBe('bogus-mode');
    });

  it('RED (round trip): a BOOLEAN value padded with whitespace survives an untouched open-save cycle -- ' +
    'previously degraded to false silently',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const doc = graphmlWithNodeProperties('RETRY', ' true ');
      const graph = parseGraphML(doc);
      const node = graph.nodeMap.n0;
      expect(node.properties.enabled).toBe(' true ');

      saveUntouched(catalogPropertyFieldsHtml, readCatalogPropertyEditor,
        descriptorWith(RETRY_MODE, ENABLED), node);

      const reparsed = parseGraphML(serializeGraphML(graph));
      expect(reparsed.nodeMap.n0.properties.enabled).toBe(' true ');
    });

  it('RED (round trip): a BOOLEAN value that is not "true"/"false" at all survives too, instead of becoming false',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      // The GraphML key itself is typed `string`, not `boolean` -- a value like "yes" would never
      // pass `<key attr.type="boolean">`'s own parse-time validation (`validateScalar`), so this
      // models the catalog's `property.type === 'BOOLEAN'` disagreeing with whatever type the
      // document's own `<key>` declares (e.g. a hand-edited document, or one written before a catalog
      // property changed type) -- unvalidated at parse time, exactly like the `program` source.
      const doc = graphmlWithNodeProperties('RETRY', 'yes', 'string');
      const graph = parseGraphML(doc);
      const node = graph.nodeMap.n0;

      saveUntouched(catalogPropertyFieldsHtml, readCatalogPropertyEditor,
        descriptorWith(RETRY_MODE, ENABLED), node);

      const reparsed = parseGraphML(serializeGraphML(graph));
      expect(reparsed.nodeMap.n0.properties.enabled).toBe('yes');
    });

  it('the mismatch is REPRESENTED in the control, not merely preserved in the model: a selected option ' +
    'carrying the exact raw value',
    async () => {
      const { catalogPropertyFieldsHtml } = await loadEditor();
      const doc = graphmlWithNodeProperties('\n  RETRY\n', 'false');
      const node = parseGraphML(doc).nodeMap.n0;

      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(RETRY_MODE), node.properties);
      const select = form.querySelector('[data-catalog-property="retryMode"]');
      expect(select.value).toBe('\n  RETRY\n');
      expect(select.selectedOptions[0].textContent).toContain('Current value not among the declared alternatives');
    });

  it('the BOOLEAN mismatch is likewise represented, not left to fall through to "false" unlabeled',
    async () => {
      const { catalogPropertyFieldsHtml } = await loadEditor();
      const doc = graphmlWithNodeProperties('RETRY', ' true ');
      const node = parseGraphML(doc).nodeMap.n0;

      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(ENABLED), node.properties);
      const select = form.querySelector('[data-catalog-property="enabled"]');
      expect(select.value).toBe(' true ');
      expect(select.selectedOptions[0].textContent).toContain('Current value not recognized');
    });

  it('an explicit author choice still overwrites the mismatched value on save -- the escape option adds a ' +
    'state, it does not freeze the field',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const doc = graphmlWithNodeProperties('\n  RETRY\n', 'false');
      const graph = parseGraphML(doc);
      const node = graph.nodeMap.n0;

      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(RETRY_MODE, ENABLED), node.properties);
      form.querySelector('[data-catalog-property="retryMode"]').value = 'SKIP';
      const { properties, propertyTypes } = readCatalogPropertyEditor(form);
      node.properties = properties;
      node.propertyTypes = propertyTypes;

      const reparsed = parseGraphML(serializeGraphML(graph));
      expect(reparsed.nodeMap.n0.properties.retryMode).toBe('SKIP');
    });

  // ── Unmutated controls: a genuinely absent property, or a value that already matches, must keep
  // their exact previous behaviour ──────────────────────────────────────────────────────────────

  it('UNMUTATED CONTROL: a value that already matches a declared alternative renders and saves unchanged',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const doc = graphmlWithNodeProperties('RETRY', 'true');
      const graph = parseGraphML(doc);
      const node = graph.nodeMap.n0;

      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(RETRY_MODE, ENABLED), node.properties);
      expect(form.querySelector('[data-catalog-property="retryMode"]').value).toBe('RETRY');
      expect(form.querySelector('[data-catalog-property="enabled"]').value).toBe('true');
      expect(form.querySelectorAll('[data-catalog-property="retryMode"] option').length)
        .toBe(RETRY_MODE.allowedValues.length);
      expect(form.querySelectorAll('[data-catalog-property="enabled"] option').length).toBe(2);

      node.properties = readCatalogPropertyEditor(form).properties;
      const reparsed = parseGraphML(serializeGraphML(graph));
      expect(reparsed.nodeMap.n0.properties.retryMode).toBe('RETRY');
      expect(reparsed.nodeMap.n0.properties.enabled).toBe('true');
    });

  it('UNMUTATED CONTROL: a genuinely absent property remains not declared and is still dropped',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const noDefault = { ...RETRY_MODE, defaultValue: '' };
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(noDefault), {});

      const select = form.querySelector('[data-catalog-property="retryMode"]');
      expect(select.value).toBe('');
      expect(select.selectedOptions[0].textContent.trim()).toBe('Not declared');

      expect(Object.keys(readCatalogPropertyEditor(form).properties)).not.toContain('retryMode');
    });

  // ── A STORED EMPTY STRING MUST READ AS "NOT DECLARED", NEVER AS A MISMATCH ──────────────────
  //
  // After a re-render, `present` alone cannot tell "the document
  // declares nothing" apart from "the document declares an empty string" (`readCurrentCatalogProperty
  // Values` reports both identically, as `''`). The BOOLEAN branch's own escape hatch
  // ("Current value not recognized: ") must not fire for that empty-string case -- an empty value is
  // exactly what a genuinely undeclared BOOLEAN control reads as, and mislabelling it accuses the
  // author of a value they never entered.
  it('a BOOLEAN property with an explicitly stored empty string renders as the plain, ' +
    'undeclared two-option control -- never a "not recognized" mismatch',
    async () => {
      const { catalogPropertyFieldsHtml } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(ENABLED), { enabled: '' });

      const select = form.querySelector('[data-catalog-property="enabled"]');
      expect(select.options.length).toBe(2);
      expect(Array.from(select.options).some(option => option.textContent.includes('not recognized'))).toBe(false);
      expect(select.value).toBe('false');
    });
});
