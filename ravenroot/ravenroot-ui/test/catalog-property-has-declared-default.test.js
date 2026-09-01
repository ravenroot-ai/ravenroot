import { catalogPropertyHasDeclaredDefault } from '../src/adapter-binding.js';
import { describe, expect, it } from 'vitest';

// ── ONE ANSWER TO "HAS THIS PROPERTY GOT A DECLARED DEFAULT?" ─────────────────────────────────
//
// The property-editor render (`catalogPropertyFieldsHtml` in app.js), "Add <behavior> node" seeding
// (`showAddCatalogNodeForm`, also app.js), canvas insertion (`createNodeForInsertion` in
// graph-editing.js), and the core validator (`NodeTypeDescriptorValidator`) must agree on blankness.
// A pure-whitespace `defaultValue` is non-empty under `=== ''`/`!== ''` but blank under Java's
// `String#isBlank()`, so independent predicates disagree on exactly that shape.
//
// A private helper in app.js cannot serve graph-editing.js, forcing canvas insertion to keep an
// independent predicate on the path where the answer reaches the document without an intervening
// editor render. A shared formula prevents divergence only when every caller imports it.
//
// `catalogPropertyHasDeclaredDefault` lives in adapter-binding.js next to `adapterIdOf`, whose Java
// `String#isBlank()` semantics are verified code-point-by-code-point against
// `Character.isWhitespace`. Every caller imports the predicate by name — app.js at two call sites and
// graph-editing.js at two call sites — rather than extracting or reimplementing it. This file tests
// that shared export directly.

describe('catalogPropertyHasDeclaredDefault is the one predicate every call site shares', () => {
  it('a genuinely empty defaultValue has no declared default', () => {
    expect(catalogPropertyHasDeclaredDefault({ defaultValue: '' })).toBe(false);
  });

  it('a pure-whitespace defaultValue also has no declared default -- this is the mutation', () => {
    expect(catalogPropertyHasDeclaredDefault({ defaultValue: ' ' })).toBe(false);
    expect(catalogPropertyHasDeclaredDefault({ defaultValue: '\t\n' })).toBe(false);
  });

  it('an ordinary declared default is recognised as declared', () => {
    expect(catalogPropertyHasDeclaredDefault({ defaultValue: 'GET' })).toBe(true);
  });

  it('null or undefined defaultValue (e.g. a hand-built model) has no declared default', () => {
    expect(catalogPropertyHasDeclaredDefault({ defaultValue: null })).toBe(false);
    expect(catalogPropertyHasDeclaredDefault({ defaultValue: undefined })).toBe(false);
  });
});
