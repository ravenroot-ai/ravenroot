import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { isPropertyRequiredNow, isPropertyVisible } from '../src/property-condition.js';

// ── A CREDENTIAL REFERENCE IS CHOSEN, NEVER TYPED ────────────────────────────────────────────
//
// `SECRET_REFERENCE` used to fall through `catalogPropertyFieldsHtml`'s if/else chain to the final
// `else` and render `<input type="text">`. Two defects follow from that control and neither is
// visible in the model:
//
// 1. It asks an author to reproduce an opaque identifier by hand, and it accepts every string —
// so a typo saves cleanly and fails at execution, where the reference is resolved.
// 2. It is a text box next to the word "secret", which invites an actual leak: an author without
// the reference to hand pastes the VALUE, because the box takes it.
//
// A test over the descriptor, or over the markup string, would see neither. So every test below goes
// the real path — descriptor -> real `catalogPropertyFieldsHtml` -> real DOM -> real
// `readCatalogPropertyEditor` — and asserts on the COLLECTED MAP wherever the question is "what
// would a save write", exactly as `catalog-property-undeclared-choice.test.js` does.
//
// Both functions are extracted from the live `src/app.js` by brace-matching rather than
// reimplemented, the technique this codebase already uses for its non-exported functions. A
// reimplementation would have reproduced the reasoning under test and proved nothing.
//
// `credentialReferenceChoices` is INJECTED rather than extracted, for the reason
// `catalog-property-undeclared-choice.test.js` gives about `catalogPropertyHasDeclaredDefault`: it
// reads a module-level variable that only exists inside a running app.js, and its whole purpose is
// to be the one substitutable seam between the editor and the service. Injecting it is what lets
// this file render every list state — held, empty, and never fetched — without a browser or a
// network.

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

async function loadEditor(held) {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  const parts = ['escapeHtml', 'escapeAttribute', 'contextualHelpButtonHtml', 'secretReferenceOptionsHtml',
    'catalogPropertyFieldsHtml', 'readCatalogPropertyEditor', 'catalogTypeToGraphMl',
    'readCurrentCatalogPropertyValues']
    .map(name => extractFunctionSource(source, name)).join('\n');

  const adapterModule = existsSync(ADAPTER_BINDING_PATH)
    ? await import(pathToFileURL(ADAPTER_BINDING_PATH).href)
    : {};

  // eslint-disable-next-line no-new-func
  const factory = new Function('adapterIdOf', 'catalogPropertyHasDeclaredDefault', 'isPropertyVisible',
    'isPropertyRequiredNow', 'credentialReferenceChoices', `
    ${parts}
    return { catalogPropertyFieldsHtml, readCatalogPropertyEditor, readCurrentCatalogPropertyValues };
  `);
  return factory(adapterModule.adapterIdOf, adapterModule.catalogPropertyHasDeclaredDefault,
    isPropertyVisible, isPropertyRequiredNow, () => held);
}

function renderForm(catalogPropertyFieldsHtml, descriptor, values) {
  const form = document.createElement('form');
  form.innerHTML = catalogPropertyFieldsHtml(descriptor, values);
  document.body.appendChild(form);
  return form;
}

afterEach(() => { document.body.innerHTML = ''; });

const SECRET = {
  name: 'credentialRef', displayName: 'Credential', type: 'SECRET_REFERENCE', required: false,
  description: 'Which stored credential this node uses.', defaultValue: '',
  allowedValues: [], adapterBinding: false, visibleWhen: null, requiredWhen: null,
};

const WEATHER = { reference: 'rrc_0123456789abcdef0123456789abcdef', label: 'Weather API' };
const REGISTRY = { reference: 'rrc_fedcba9876543210fedcba9876543210', label: 'Package registry' };

const HELD = { loaded: true, credentials: [WEATHER, REGISTRY] };
const NONE_HELD = { loaded: true, credentials: [] };
const NOT_FETCHED = { loaded: false, credentials: [] };

const descriptorWith = (...properties) => ({ displayName: 'Example node', properties });
const control = (form, name = SECRET.name) => form.querySelector(`[data-catalog-property="${name}"]`);

describe('the SECRET_REFERENCE control is a choice, not a text field', () => {
  it('renders a select — there is no text input for this property in any list state', async () => {
    for (const held of [HELD, NONE_HELD, NOT_FETCHED]) {
      const { catalogPropertyFieldsHtml } = await loadEditor(held);
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET), {});

      const field = control(form);
      expect(field.tagName, `list state ${JSON.stringify(held.loaded)}`).toBe('SELECT');
      // No free-text fallback exists to degrade into, because the
      // state an author is most likely to reach for the secret in is exactly the degraded one.
      expect(form.querySelector('input[data-catalog-property]')).toBeNull();
      document.body.innerHTML = '';
    }
  });

  it('shows the LABEL and carries the REFERENCE, so the reference never has to be read or retyped',
    async () => {
      const { catalogPropertyFieldsHtml } = await loadEditor(HELD);
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET), {});

      const options = [...control(form).options];
      expect(options.map(option => option.textContent.trim()))
        .toEqual(['Not selected', 'Weather API', 'Package registry']);
      expect(options.map(option => option.value))
        .toEqual(['', WEATHER.reference, REGISTRY.reference]);
    });

  it('offers "Not selected" first, with an empty value, and starts there when nothing is declared',
    async () => {
      const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(HELD);
      const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET), {});

      const field = control(form);
      // First and `value=""` is not cosmetic: that combination is what makes it HTML's placeholder
      // label option, so a native-`required` select reports itself missing while it is selected.
      expect(field.selectedIndex).toBe(0);
      expect(field.options[0].value).toBe('');
      expect(field.value).toBe('');
      // And the guarantee holds unchanged: merely opening a node and saving declares nothing.
      expect(Object.keys(readCatalogPropertyEditor(form).properties)).not.toContain(SECRET.name);
    });

  it('keeps emitting the two data attributes `readCatalogPropertyEditor` collects by', async () => {
    const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(HELD);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET),
      { [SECRET.name]: WEATHER.reference });

    expect(control(form).dataset.catalogType).toBe('SECRET_REFERENCE');
    const { properties, propertyTypes } = readCatalogPropertyEditor(form);
    expect(properties[SECRET.name]).toBe(WEATHER.reference);
    // SECRET_REFERENCE has no GraphML type of its own; it is a string, and it was before this change.
    expect(propertyTypes[SECRET.name]).toBe('string');
  });

  it('collects the reference the author picks, not the label they read', async () => {
    const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(HELD);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET), {});

    // The one place in this file where the control is acted on, standing in for the author.
    control(form).value = REGISTRY.reference;

    const { properties } = readCatalogPropertyEditor(form);
    expect(properties[SECRET.name]).toBe(REGISTRY.reference);
    expect(properties[SECRET.name]).not.toBe(REGISTRY.label);
  });

  it('lets an author go back to not selected, and the save then drops the key', async () => {
    const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(HELD);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET),
      { [SECRET.name]: WEATHER.reference });

    control(form).value = '';

    expect(Object.keys(readCatalogPropertyEditor(form).properties)).not.toContain(SECRET.name);
  });
});

// ── THE TWO PRESERVING STATES, which are what stand in for a free-text fallback ───────────────────
describe('a SECRET_REFERENCE this build does not recognise survives untouched', () => {
  const FOREIGN = 'rrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

  it('an imported graph\'s reference round-trips through a render and a read, unchanged', async () => {
    const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(HELD);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET),
      { [SECRET.name]: FOREIGN });

    const field = control(form);
    expect(field.value).toBe(FOREIGN);
    expect([...field.options].map(option => option.value))
      .toEqual(['', FOREIGN, WEATHER.reference, REGISTRY.reference]);
    // Saving a form the author did not touch must not silently drop somebody else's declaration.
    expect(readCatalogPropertyEditor(form).properties[SECRET.name]).toBe(FOREIGN);
  });

  it('says it is not one of theirs when the list HAS been read', async () => {
    const { catalogPropertyFieldsHtml } = await loadEditor(HELD);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET),
      { [SECRET.name]: FOREIGN });

    expect(control(form).selectedOptions[0].textContent)
      .toContain('not one of your credentials');
  });

  it('says the opposite when the list has NOT been read — the two are different facts', async () => {
    // Telling an author "not one of yours" while nobody has asked the service would invite them to
    // delete a perfectly good declaration.
    const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(NOT_FETCHED);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET),
      { [SECRET.name]: WEATHER.reference });

    const field = control(form);
    expect(field.selectedOptions[0].textContent).toContain('connect to your service');
    expect(field.selectedOptions[0].textContent).not.toContain('not one of your credentials');
    // PRESERVED, not emptied, and not turned back into an input.
    expect(field.tagName).toBe('SELECT');
    expect(readCatalogPropertyEditor(form).properties[SECRET.name]).toBe(WEATHER.reference);
  });

  it('adds no preserving option when there is nothing to preserve', async () => {
    const { catalogPropertyFieldsHtml } = await loadEditor(NOT_FETCHED);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET), {});

    expect([...control(form).options].map(option => option.textContent.trim())).toEqual(['Not selected']);
  });

  it('escapes a label and a reference rather than building markup from them', async () => {
    const hostile = {
      loaded: true,
      credentials: [{ reference: 'rrc_"><img src=x>', label: '<script>alert(1)</script>' }],
    };
    const { catalogPropertyFieldsHtml } = await loadEditor(hostile);
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(SECRET), {});

    expect(form.querySelector('img')).toBeNull();
    expect(form.querySelector('script')).toBeNull();
    expect([...control(form).options][1].value).toBe('rrc_"><img src=x>');
  });
});

// ── The refresh loop, which every control in this container has to survive ───────────────────
describe('the SECRET_REFERENCE control survives a render -> read -> render cycle', () => {
  it('a chosen reference is still chosen, and an undeclared one is still Not selected', async () => {
    const { catalogPropertyFieldsHtml, readCurrentCatalogPropertyValues } = await loadEditor(HELD);
    const descriptor = descriptorWith(SECRET);
    const form = renderForm(catalogPropertyFieldsHtml, descriptor, {});

    expect(control(form).value).toBe('');
    control(form).value = WEATHER.reference;

    // The exact `refreshConditionalCatalogProperties` path: read every control's current DOM value
    // (always a string), then rebuild the container from that map.
    const domValues = readCurrentCatalogPropertyValues(form);
    expect(domValues[SECRET.name]).toBe(WEATHER.reference);
    form.innerHTML = catalogPropertyFieldsHtml(descriptor, domValues);
    expect(control(form).value).toBe(WEATHER.reference);

    const undeclared = renderForm(catalogPropertyFieldsHtml, descriptor, {});
    undeclared.innerHTML = catalogPropertyFieldsHtml(descriptor,
      readCurrentCatalogPropertyValues(undeclared));
    expect(control(undeclared).selectedIndex).toBe(0);
    expect(control(undeclared).options[0].value).toBe('');
  });
});

// ── PRESERVATION CONTROLS: UNRELATED PROPERTY TYPES REMAIN UNCHANGED ─────────────────────────────
describe('the other property types are untouched', () => {
  it('a plain STRING property is still a text input', async () => {
    const { catalogPropertyFieldsHtml } = await loadEditor(HELD);
    const property = {
      name: 'endpoint', displayName: 'Endpoint', type: 'STRING', required: false, description: '',
      defaultValue: '', allowedValues: [], adapterBinding: false, visibleWhen: null, requiredWhen: null,
    };
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(property), {});

    expect(control(form, 'endpoint').tagName).toBe('INPUT');
    expect(control(form, 'endpoint').type).toBe('text');
  });

  it('a closed choice with a default still pre-selects it, with no extra option', async () => {
    const { catalogPropertyFieldsHtml, readCatalogPropertyEditor } = await loadEditor(HELD);
    const property = {
      name: 'closed.defaulted', displayName: 'Closed', type: 'STRING', required: false,
      description: '', defaultValue: 'declared-default',
      allowedValues: ['declared-default', 'other-value'], adapterBinding: false,
      visibleWhen: null, requiredWhen: null,
    };
    const form = renderForm(catalogPropertyFieldsHtml, descriptorWith(property), {});

    expect(control(form, 'closed.defaulted').options.length).toBe(2);
    expect(readCatalogPropertyEditor(form).properties['closed.defaulted']).toBe('declared-default');
  });
});
