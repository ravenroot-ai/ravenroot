import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

import { isPropertyRequiredNow, isPropertyVisible } from '../src/property-condition.js';
import { PROVIDER_CONFIG_POINTER, invokesModelProvider } from '../src/generative-capability.js';

// ── THE EDITOR'S "UNCONFIGURED" HINT MUST CLASSIFY BLANK THE WAY THE SERVER DOES ─────────────
//
// `catalogPropertyFieldsHtml` in `src/app.js` decides whether an adapter-bound property is
// "unconfigured" (amber hint, native `required` suppressed) or holds a real, named adapter. The
// server (`NodePropertyDescriptor#adapterIdOf`, `BehaviorPropertySchema#namesNoAdapter`) makes the
// same decision when the graph actually runs, and the two must agree — a value the editor calls
// unconfigured but the server calls a named (and then refused) adapter is a silent contradiction the
// user cannot see coming.
//
// This file exercises the REAL `catalogPropertyFieldsHtml` from `src/app.js` — extracted from the
// live source by brace-matching, the same technique `workspace-view-invariant.test.js` and
// `pane-focus-contract.test.js` use for this codebase's non-exported functions — rather than a
// reimplementation of its logic. A `String(value).trim() === ''` implementation disagrees with
// the server, so these tests fail against that implementation without themselves changing.
//
// `adapter-binding.js` is imported dynamically and tolerantly so an extracted function body that
// uses `.trim()` inline can still be evaluated. An unused, undefined helper causes no failure of its
// own — the RED failures below come from the classification being wrong, not from a missing import.
//
// `property-condition.js` has no such alternate implementation to simulate — it is imported
// statically like any other module — but the extracted `catalogPropertyFieldsHtml` now calls
// `isPropertyVisible`/`isPropertyRequiredNow` too, so the `new Function` factory below must inject
// them the same way it already injects `adapterIdOf`, or every extraction throws a plain
// `ReferenceError` before a single assertion runs.

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

async function loadCatalogPropertyFieldsHtml() {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  const escapeHtmlSrc = extractFunctionSource(source, 'escapeHtml');
  const escapeAttributeSrc = extractFunctionSource(source, 'escapeAttribute');
  const contextualHelpButtonSrc = extractFunctionSource(source, 'contextualHelpButtonHtml');
  const catalogSrc = extractFunctionSource(source, 'catalogPropertyFieldsHtml');

  // Checked with `existsSync` and imported via a runtime-built `file://` URL (not a string-literal
  // specifier) so Vite's import-analysis plugin never sees a static import to resolve eagerly — the
  // adapter-binding module is optional to this extraction harness, and its absence must not be a
  // build error that kills the suite before a single assertion runs.
  const adapterModule = existsSync(ADAPTER_BINDING_PATH)
    ? await import(pathToFileURL(ADAPTER_BINDING_PATH).href)
    : {};

  // The extracted body has two more free names (`invokesModelProvider` and
  // `PROVIDER_CONFIG_POINTER`), for the sentence that tells an author where a model provider is
  // configured. Both live in `generative-capability.js`; they are injected here for exactly the
  // reason the header gives for
  // `isPropertyVisible`: an uninjected free name is a `ReferenceError` thrown before any assertion
  // runs, which would turn every test in this file red for a reason that has nothing to do with what
  // it is measuring.
  // eslint-disable-next-line no-new-func
  const factory = new Function('adapterIdOf', 'isPropertyVisible', 'isPropertyRequiredNow',
    'invokesModelProvider', 'PROVIDER_CONFIG_POINTER', `
    ${escapeHtmlSrc}
    ${escapeAttributeSrc}
    ${contextualHelpButtonSrc}
    ${catalogSrc}
    return catalogPropertyFieldsHtml;
  `);
  return factory(adapterModule.adapterIdOf, isPropertyVisible, isPropertyRequiredNow,
    invokesModelProvider, PROVIDER_CONFIG_POINTER);
}

const ADAPTER_PROPERTY = {
  name: 'provider',
  displayName: 'Provider',
  type: 'STRING',
  required: true,
  description: 'Adapter to use.',
  defaultValue: '',
  allowedValues: [],
  adapterBinding: true,
};

function descriptorWith(property) {
  return { displayName: 'LLM prompt', properties: [property] };
}

function isUnconfigured(html) {
  return html.includes('catalog-property--unconfigured');
}

// Built from String.fromCodePoint rather than typed as literal characters, so nothing invisible sits
// in this file's bytes.
const cp = (codePoint) => String.fromCodePoint(codePoint);
const NBSP = cp(0x00a0);
const FIGURE_SPACE = cp(0x2007);
const NARROW_NBSP = cp(0x202f);
const BOM = cp(0xfeff);
const FILE_SEPARATOR = cp(0x001c);
const GROUP_SEPARATOR = cp(0x001d);
const RECORD_SEPARATOR = cp(0x001e);
const UNIT_SEPARATOR = cp(0x001f);

describe('catalogPropertyFieldsHtml classifies "blank" the same way the server does', () => {
  it('RED CONTROL: a value that is only U+00A0 (NBSP) must NOT be unconfigured — the server names and refuses it as an adapter', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: NBSP });

    expect(isUnconfigured(html)).toBe(false);
  });

  it('a value that is only U+001F (an information separator) MUST be unconfigured, matching the server', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: UNIT_SEPARATOR });

    expect(isUnconfigured(html)).toBe(true);
  });

  // ── Full enumerated divergence set — explicitly warns not to assume it is only U+00A0 ──────

  it.each([
    { label: 'U+00A0 NO-BREAK SPACE', value: NBSP },
    { label: 'U+2007 FIGURE SPACE', value: FIGURE_SPACE },
    { label: 'U+202F NARROW NO-BREAK SPACE', value: NARROW_NBSP },
    { label: 'U+FEFF ZERO WIDTH NO-BREAK SPACE / BOM', value: BOM },
  ])('$label: NOT unconfigured (Java\'s Character.isWhitespace does not call it whitespace)', ({ value }) => {
    return loadCatalogPropertyFieldsHtml().then((catalogPropertyFieldsHtml) => {
      const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: value });
      expect(isUnconfigured(html)).toBe(false);
    });
  });

  it.each([
    { label: 'U+001C FILE SEPARATOR', value: FILE_SEPARATOR },
    { label: 'U+001D GROUP SEPARATOR', value: GROUP_SEPARATOR },
    { label: 'U+001E RECORD SEPARATOR', value: RECORD_SEPARATOR },
    { label: 'U+001F UNIT SEPARATOR', value: UNIT_SEPARATOR },
  ])('$label: unconfigured (Java\'s Character.isWhitespace calls it whitespace)', ({ value }) => {
    return loadCatalogPropertyFieldsHtml().then((catalogPropertyFieldsHtml) => {
      const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: value });
      expect(isUnconfigured(html)).toBe(true);
    });
  });

  // ── UNMUTATED CONTROLS: UNRELATED PROPERTY BEHAVIOR REMAINS UNCHANGED ─────────────────────────

  it('UNMUTATED CONTROL: an ordinary empty value is unconfigured in both worlds', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: '' });

    expect(isUnconfigured(html)).toBe(true);
  });

  it('UNMUTATED CONTROL: a real adapter id is configured in both worlds', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: 'openai' });

    expect(isUnconfigured(html)).toBe(false);
  });

  it('UNMUTATED CONTROL: an ordinary ASCII space is unconfigured in both worlds (both sides agree on U+0020)', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: '   ' });

    expect(isUnconfigured(html)).toBe(true);
  });

  it('UNMUTATED CONTROL: a property with no adapter binding is never marked unconfigured, regardless of value', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const property = { ...ADAPTER_PROPERTY, adapterBinding: false, required: false };
    const html = catalogPropertyFieldsHtml(descriptorWith(property), { provider: NBSP });

    expect(isUnconfigured(html)).toBe(false);
  });

  it('proves the extraction reached real source: the function is found and produces non-empty markup', async () => {
    const catalogPropertyFieldsHtml = await loadCatalogPropertyFieldsHtml();
    const html = catalogPropertyFieldsHtml(descriptorWith(ADAPTER_PROPERTY), { provider: 'openai' });

    expect(html).toContain('data-catalog-property="provider"');
    expect(html.length).toBeGreaterThan(50);
  });
});
