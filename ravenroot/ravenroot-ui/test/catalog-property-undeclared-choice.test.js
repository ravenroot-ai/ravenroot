import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { isPropertyRequiredNow, isPropertyVisible } from '../src/property-condition.js';

// ── SAVING MUST NOT DECLARE A CLOSED CHOICE THE AUTHOR NEVER MADE ─────────────────────────────
//
// The defect is in the RENDERING, not in the model, and that is the whole reason this file exists in
// this shape. `catalogPropertyFieldsHtml` built a `<select>` from `allowedValues` alone. When the
// descriptor declares no default, no option carried `selected` and none of them was empty — so by
// the HTML specification the FIRST option becomes the selected one, the control reads back the first
// allowed value, and `readCatalogPropertyEditor` (which drops only `''`) collects it. Submit
// whole-replaces `node.properties` from exactly that map. Opening a node to look at it and pressing
// Save wrote a declaration nobody took.
//
// A test that asserted over the descriptor, or over the HTML string, would have seen nothing: the
// markup is correct-looking and contains no `selected` at all. The value only exists once a real
// `<select>` element exists in a real document. So every test below goes down the real path —
// descriptor -> real `catalogPropertyFieldsHtml` -> real DOM -> real `readCatalogPropertyEditor` —
// and asserts on the COLLECTED MAP, which is the thing that reaches `node.properties`. No test here
// interacts with the control before reading it; the whole point is what a save writes when the
// author touched nothing.
//
// Both functions are extracted from the live `src/app.js` by brace-matching rather than
// reimplemented, the technique `catalog-property-blank-editor.test.js`, `pane-focus-contract.test.js`
// and `workspace-view-invariant.test.js` already use for this codebase's non-exported functions. A
// reimplementation would have reproduced the reasoning under test and proved nothing.
//
// Nothing here names `recovery.repeatable`, `deadLetterMode` or any behavior. The trigger is the
// SHAPE — a closed choice with no default — because that shape is not incidental: the core's own
// `NodeTypeDescriptorValidator.requiredWhenHasNoDefault` refuses a default on any conditionally
// required property, so it is minted by rule and the next one arrives the same way.

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
  // `catalogPropertyHasDeclaredDefault` is no longer a private function of app.js -- it moved to
  // adapter-binding.js so graph-editing.js could import the same one (see that module's own comment).
  // It is therefore injected below as a REAL import, exactly like `adapterIdOf`, rather than extracted
  // by brace-matching: extracting it would silently stop proving anything the moment app.js's own copy
  // was deleted, because `source.indexOf('function catalogPropertyHasDeclaredDefault(')` would simply
  // not match and the test would need to be looking at the real call site to notice.
  const parts = ['escapeHtml', 'escapeAttribute', 'contextualHelpButtonHtml', 'catalogPropertyFieldsHtml',
    'readCatalogPropertyEditor', 'catalogTypeToGraphMl', 'readCurrentCatalogPropertyValues']
    .map(name => extractFunctionSource(source, name)).join('\n');

  const adapterModule = existsSync(ADAPTER_BINDING_PATH)
    ? await import(pathToFileURL(ADAPTER_BINDING_PATH).href)
    : {};

  // eslint-disable-next-line no-new-func
  const factory = new Function('adapterIdOf', 'catalogPropertyHasDeclaredDefault', 'isPropertyVisible',
    'isPropertyRequiredNow', `
    ${parts}
    return { catalogPropertyFieldsHtml, readCatalogPropertyEditor, readCurrentCatalogPropertyValues };
  `);
  return factory(adapterModule.adapterIdOf, adapterModule.catalogPropertyHasDeclaredDefault,
    isPropertyVisible, isPropertyRequiredNow);
}

/**
 * Renders a descriptor into a REAL `<form>` in a real document, exactly as the node editor does, and
 * returns the form. Nothing is clicked, typed or selected afterwards — the values the controls hold
 * at this point are the ones the browser itself decided, which is the state under test.
 */
function renderForm(catalogPropertyFieldsHtml, descriptor, values) {
  const form = document.createElement('form');
  form.innerHTML = catalogPropertyFieldsHtml(descriptor, values);
  document.body.appendChild(form);
  return form;
}

afterEach(() => { document.body.innerHTML = ''; });

const CONTRACT = 'ravenroot.property-condition/1';

/** A closed choice with NO default. Deliberately anonymous. */
const CLOSED_NO_DEFAULT = {
  name: 'closed.undefaulted', displayName: 'Closed choice, no default', type: 'STRING',
  required: false, description: 'Two mutually exclusive declarations.', defaultValue: '',
  allowedValues: ['first-allowed-value', 'second-allowed-value'], adapterBinding: false,
  visibleWhen: null, requiredWhen: null,
};

/** The same shape, but conditionally visible — the form the core's validator forces into existence. */
const CLOSED_NO_DEFAULT_CONDITIONAL = {
  ...CLOSED_NO_DEFAULT,
  name: 'closed.conditional', displayName: 'Closed choice, conditional',
  allowedValues: ['only-allowed-value'],
  visibleWhen: { contract: CONTRACT, property: 'driver', operator: 'EQUALS', values: ['reveal'] },
  requiredWhen: { contract: CONTRACT, property: 'driver', operator: 'EQUALS', values: ['reveal'] },
};

/** A closed choice WITH a default: its established behavior must remain unchanged. */
const CLOSED_WITH_DEFAULT = {
  name: 'closed.defaulted', displayName: 'Closed choice, defaulted', type: 'STRING',
  required: false, description: 'Has a catalog default.', defaultValue: 'declared-default',
  allowedValues: ['declared-default', 'other-value'], adapterBinding: false,
  visibleWhen: null, requiredWhen: null,
};

/**
 * A defaultValue of pure whitespace. `NodeTypeDescriptorValidator.requiredWhenHasNoDefault` and
 * the new `defaultValueIsAdmissible` both decide "has a default" via Java's `String#isBlank()`, so the
 * core treats this exactly like `CLOSED_NO_DEFAULT` above — not declared. An editor predicate based
 * on `=== ''` reaches the opposite answer for whitespace-only values, so the editor
 * rendered "has a default" (no `Not declared` option) while the validator said "no default": the
 * gap this predicate reopens for any descriptor whose default is only whitespace.
 */
const CLOSED_WHITESPACE_DEFAULT = {
  ...CLOSED_NO_DEFAULT,
  name: 'closed.whitespace-default', displayName: 'Closed choice, whitespace default',
  defaultValue: ' ',
};

const DRIVER = {
  name: 'driver', displayName: 'Driver', type: 'STRING', required: false,
  description: 'Drives the conditional property.', defaultValue: 'hide',
  allowedValues: ['hide', 'reveal'], adapterBinding: false, visibleWhen: null, requiredWhen: null,
};

const descriptorWith = (...properties) => ({ displayName: 'Example node', properties });

describe('a closed choice with no default must not be declared by merely opening and saving', () => {
  it('RED: a node that never declared the property still does not declare it after a save with no interaction',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      // The node as it exists today: the key is simply absent from `properties`.
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_NO_DEFAULT), {});

      const { properties, propertyTypes } = readCatalogPropertyEditor(form);

      expect(Object.keys(properties)).not.toContain(CLOSED_NO_DEFAULT.name);
      expect(Object.keys(propertyTypes)).not.toContain(CLOSED_NO_DEFAULT.name);
    });

  it('RED: the control itself holds no value after rendering — the browser did not pick one for the author',
    async () => {
      const { catalogPropertyFieldsHtml } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_NO_DEFAULT), {});

      const select = form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`);
      // Read from the live element, not from the markup: the markup contains no `selected` either
      // way, and that is exactly why reading it proves nothing.
      expect(select.value).toBe('');
    });

  it('RED: a property that is not even visible does not get declared either — a hidden control is still collected',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      // `driver` sits at its default `hide`, so the conditional property is rendered `hidden`.
      // keeps hidden controls in the DOM on purpose, and `readCatalogPropertyEditor` collects every
      // control regardless of `hidden` — so a hidden select with an auto-selected first option wrote
      // a declaration for a field the author could not even see.
      const form = renderForm(catalogPropertyFieldsHtml,
        descriptorWith(DRIVER, CLOSED_NO_DEFAULT_CONDITIONAL), {});

      const wrapper = form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT_CONDITIONAL.name}"]`)
        .closest('.catalog-property');
      expect(wrapper.hidden, 'the control must still be rendered and hidden, not omitted').toBe(true);

      const { properties } = readCatalogPropertyEditor(form);
      expect(Object.keys(properties)).not.toContain(CLOSED_NO_DEFAULT_CONDITIONAL.name);
    });

  it('the absence is REPRESENTED in the control, not merely in the model: a first, empty, selected "Not declared" option',
    async () => {
      const { catalogPropertyFieldsHtml } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_NO_DEFAULT), {});

      const select = form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`);
      const first = select.options[0];
      // First and `value=""` is not cosmetic: that combination is what makes it HTML's "placeholder
      // label option", so a native-`required` select reports itself missing while it is selected.
      expect(first.value).toBe('');
      expect(first.textContent.trim()).toBe('Not declared');
      expect(select.selectedIndex).toBe(0);
      expect(select.options.length).toBe(CLOSED_NO_DEFAULT.allowedValues.length + 1);
      // Someone reading the screen is told what the state means, and told it as a state rather than
      // as an empty value.
      // `getElementById`, not a `#id` selector: the id embeds the property name, which contains a
      // dot in every real catalog example of this shape, and a `#a.b` selector reads that as a class.
      const help = select.closest('.catalog-property').querySelector('[data-contextual-help]');
      expect(help.dataset.contextualHelp).toContain('Not declared is a state of its own');
      expect(help.getAttribute('aria-controls')).toBe('contextual-help-popover');
    });

  it('an existing EXPLICIT value survives a save untouched, and does not gain an extra selection',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_NO_DEFAULT),
        { [CLOSED_NO_DEFAULT.name]: 'second-allowed-value' });

      const select = form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`);
      expect(select.value).toBe('second-allowed-value');

      const { properties, propertyTypes } = readCatalogPropertyEditor(form);
      expect(properties[CLOSED_NO_DEFAULT.name]).toBe('second-allowed-value');
      expect(propertyTypes[CLOSED_NO_DEFAULT.name]).toBe('string');
    });

  it('choosing a value still declares it — the empty option adds a state, it does not remove one',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_NO_DEFAULT), {});

      // The one place in this file where the control is acted on, standing in for the author.
      form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`).value = 'first-allowed-value';

      const { properties } = readCatalogPropertyEditor(form);
      expect(properties[CLOSED_NO_DEFAULT.name]).toBe('first-allowed-value');
    });

  it('an author can go back to not declared, and the save then drops the key',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_NO_DEFAULT),
        { [CLOSED_NO_DEFAULT.name]: 'first-allowed-value' });

      form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`).value = '';

      const { properties } = readCatalogPropertyEditor(form);
      expect(Object.keys(properties)).not.toContain(CLOSED_NO_DEFAULT.name);
    });

  // ── UNMUTATED CONTROLS: UNRELATED PROPERTY BEHAVIOR REMAINS UNCHANGED ─────────────────────────

  it('UNMUTATED CONTROL: a closed choice WITH a default keeps its exact previous behaviour — default pre-selected and saved',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_WITH_DEFAULT), {});

      const select = form.querySelector(`[data-catalog-property="${CLOSED_WITH_DEFAULT.name}"]`);
      expect(select.options.length, 'no empty option is added where the catalog nominates a value')
        .toBe(CLOSED_WITH_DEFAULT.allowedValues.length);
      expect(select.value).toBe('declared-default');

      const { properties } = readCatalogPropertyEditor(form);
      expect(properties[CLOSED_WITH_DEFAULT.name]).toBe('declared-default');
    });

  it('UNMUTATED CONTROL: a BOOLEAN control is untouched — it has always had two exhaustive options and a real default',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const property = {
        name: 'flag', displayName: 'Flag', type: 'BOOLEAN', required: false, description: '',
        defaultValue: '', allowedValues: [], adapterBinding: false, visibleWhen: null, requiredWhen: null,
      };
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(property), {});

      const select = form.querySelector('[data-catalog-property="flag"]');
      expect(select.options.length).toBe(2);
      expect(readCatalogPropertyEditor(form).properties.flag).toBe('false');
    });

  it('a whitespace-only defaultValue reads as "not declared", the same way the core validator reads it',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(CLOSED_WHITESPACE_DEFAULT), {});

      const select = form.querySelector(`[data-catalog-property="${CLOSED_WHITESPACE_DEFAULT.name}"]`);
      const first = select.options[0];
      expect(first.value).toBe('');
      expect(first.textContent.trim()).toBe('Not declared');
      expect(select.selectedIndex).toBe(0);
      expect(select.options.length).toBe(CLOSED_WHITESPACE_DEFAULT.allowedValues.length + 1);

      const { properties } = readCatalogPropertyEditor(form);
      expect(Object.keys(properties)).not.toContain(CLOSED_WHITESPACE_DEFAULT.name);
    });

  it('proves the extraction reached real source: real markup, real ids, real collection',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor();
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(DRIVER), {});

      expect(form.innerHTML).toContain('data-catalog-property="driver"');
      expect(readCatalogPropertyEditor(form).properties.driver).toBe('hide');
    });
});

// ── THE SAME SHAPE, ONE RE-RENDER LATER ──────────────────────────────────────────────────────
//
// Every test above renders ONCE and reads the result, leaving this gap:
// `catalogPropertyFieldsHtml`'s own `present` is `values[property.name] != null`, but the VALUES this
// function receives on a first render (straight from the document/model) are not the values it
// receives on every later render. `refreshConditionalCatalogProperties` rebuilds this same
// container from `readCurrentCatalogPropertyValues(container)`, which reads every `[data-catalog-
// property]` control's CURRENT DOM VALUE — and a `<select>`'s `.value` is always a string, never
// `undefined`, whether or not the document ever declared the property. So a property that was
// genuinely undeclared on the FIRST render is `present === true` on every render after the first
// refresh, indistinguishable — by `present` alone — from one the author actually filled in.
//
// The describe block below goes around that exact loop for real: render -> read the container back
// with the real `readCurrentCatalogPropertyValues` -> render again with what that read produced —
// then asserts on the SECOND render.
describe('an undeclared closed choice survives a full render -> read -> render cycle', () => {
  it('SECOND render after the round trip still shows "Not declared" first and selected, with no extra option',
    async () => {
      const { catalogPropertyFieldsHtml, readCurrentCatalogPropertyValues } = await loadEditor();
      const descriptor = descriptorWith(CLOSED_NO_DEFAULT);
      const form = renderForm(catalogPropertyFieldsHtml, descriptor, {});

      // Sanity on the FIRST render, matching the rest of this file: nothing was ever declared.
      const firstSelect = form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`);
      expect(firstSelect.selectedIndex).toBe(0);
      expect(firstSelect.value).toBe('');

      // The exact refresh path: read every control's current DOM value (always a string), then
      // rebuild the container's markup from that map — precisely what
      // `refreshConditionalCatalogProperties` does on any visibility/required-ness change, and
      // exactly the step none of the tests above this one ever took.
      const domValues = readCurrentCatalogPropertyValues(form);
      expect(domValues[CLOSED_NO_DEFAULT.name]).toBe('');
      form.innerHTML = catalogPropertyFieldsHtml(descriptor, domValues);

      const select = form.querySelector(`[data-catalog-property="${CLOSED_NO_DEFAULT.name}"]`);
      expect(select.selectedIndex).toBe(0);
      expect(select.options[0].value).toBe('');
      expect(select.options[0].textContent.trim()).toBe('Not declared');
      expect(select.options.length).toBe(CLOSED_NO_DEFAULT.allowedValues.length + 1);
      // The placeholder label option survived: still first, still `value=""`, still selected — so a
      // native `required` on this control (asserted directly against the real http-request shape in
      // the next describe block) still reports `valueMissing`.
    });
});

// ── THE SAME FAILURE ON THE REAL `http-request` SHAPE ────────────────────────────────────────
//
// `recovery.repeatable` (PERS-04 / ADR 0022) is exactly this shape: a closed choice with NO default,
// conditionally required via `requiredWhen` ONE_OF on the sibling `method`, and `method` itself has no
// `requiredWhen` of its own (it is unconditionally required, the ordinary case). No node-specific
// name is asserted on directly — the fixture below is anonymous the same way `CLOSED_NO_DEFAULT`
// above is, but its SHAPE (a ONE_OF requiredWhen against a sibling that changes) is the one
// `NodeTypeDescriptorValidator.requiredWhenHasNoDefault` guarantees every conditionally-required
// closed choice is born in, on any node package, not only this core one.
const HTTP_METHOD = {
  name: 'method', displayName: 'Method', type: 'STRING', required: true,
  description: 'The HTTP method this request issues.', defaultValue: 'GET',
  allowedValues: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], adapterBinding: false,
  visibleWhen: null, requiredWhen: null,
};

const RECOVERY_REPEATABLE = {
  name: 'recovery.repeatable', displayName: 'Recovery repeatable', type: 'STRING', required: false,
  description: 'Whether a failed attempt of this request may safely be repeated.', defaultValue: '',
  allowedValues: ['true', 'false'], adapterBinding: false, visibleWhen: null,
  requiredWhen: { contract: CONTRACT, property: 'method', operator: 'ONE_OF',
    values: ['POST', 'PUT', 'PATCH', 'DELETE'] },
};

describe('the real http-request shape (recovery.repeatable) keeps its fail-closed gate across a refresh', () => {
  it('switching method to POST re-renders recovery.repeatable ' +
    'as still-undeclared and native-required, so the browser refuses the save',
    async () => {
      const { catalogPropertyFieldsHtml, readCurrentCatalogPropertyValues } = await loadEditor();
      const descriptor = descriptorWith(HTTP_METHOD, RECOVERY_REPEATABLE);
      // The author opens a node with method=GET (recovery.repeatable therefore optional and
      // undeclared) — the ordinary, unremarkable starting point for this condition.
      const form = renderForm(catalogPropertyFieldsHtml, descriptor, { method: 'GET' });

      const repeatableBefore = form.querySelector('[data-catalog-property="recovery.repeatable"]');
      expect(repeatableBefore.hasAttribute('required')).toBe(false);
      expect(repeatableBefore.value).toBe('');

      // The author changes Method to POST — a plain value change, standing in for a real `input`
      // event on the select. `refreshConditionalCatalogProperties` reacts to exactly this: it reads
      // EVERY control's current DOM value (recovery.repeatable's included, still `''`, still
      // genuinely undeclared) and rebuilds the container from that map.
      form.querySelector('[data-catalog-property="method"]').value = 'POST';
      const domValues = readCurrentCatalogPropertyValues(form);
      expect(domValues.method).toBe('POST');
      expect(domValues['recovery.repeatable']).toBe('');
      form.innerHTML = catalogPropertyFieldsHtml(descriptor, domValues);

      const select = form.querySelector('[data-catalog-property="recovery.repeatable"]');
      // requiredNow now holds (method is ONE_OF POST/PUT/PATCH/DELETE), so the control must be
      // native-required, AND the placeholder label option ("Not declared", value="", first) must
      // still be the one selected -- not a second, empty-labelled "mismatch" option that would push
      // the true placeholder out of first position and silence `valueMissing`.
      expect(select.hasAttribute('required')).toBe(true);
      expect(select.selectedIndex).toBe(0);
      expect(select.options[0].value).toBe('');
      expect(select.options[0].textContent.trim()).toBe('Not declared');
      expect(select.validity.valueMissing).toBe(true);
      expect(form.checkValidity()).toBe(false);
    });
});
