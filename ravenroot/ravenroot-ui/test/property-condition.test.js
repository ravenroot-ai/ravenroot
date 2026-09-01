import { describe, expect, it } from 'vitest';

import { CONTRACT, conditionHolds, isPropertyRequiredNow, isPropertyVisible } from '../src/property-condition.js';

// CATALOG-UI Inspector client-side evaluator for `visibleWhen`/`requiredWhen`.
// Nothing in this file names a behavior or an adapter — the whole point of the generic contract is
// that this evaluator never needs to know one exists.

const equalTo = (property, value) => ({ contract: CONTRACT, property, operator: 'EQUALS', values: [value] });
const oneOf = (property, ...values) => ({ contract: CONTRACT, property, operator: 'ONE_OF', values });
const present = property => ({ contract: CONTRACT, property, operator: 'PRESENT', values: [] });
const blank = property => ({ contract: CONTRACT, property, operator: 'BLANK', values: [] });

describe('conditionHolds — the four operators', () => {
  it('EQUALS holds only for the exact named value', () => {
    const condition = equalTo('mode', 'WEBHOOK');
    expect(conditionHolds(condition, 'WEBHOOK')).toBe(true);
    expect(conditionHolds(condition, 'LONG_POLLING')).toBe(false);
    expect(conditionHolds(condition, '')).toBe(false);
  });

  it('ONE_OF holds for any named value', () => {
    const condition = oneOf('mode', 'WEBHOOK', 'HYBRID');
    expect(conditionHolds(condition, 'WEBHOOK')).toBe(true);
    expect(conditionHolds(condition, 'HYBRID')).toBe(true);
    expect(conditionHolds(condition, 'LONG_POLLING')).toBe(false);
  });

  it('PRESENT holds for any non-blank value and BLANK for the opposite', () => {
    expect(conditionHolds(present('mode'), 'anything')).toBe(true);
    expect(conditionHolds(present('mode'), '')).toBe(false);
    expect(conditionHolds(present('mode'), null)).toBe(false);
    expect(conditionHolds(blank('mode'), '')).toBe(true);
    expect(conditionHolds(blank('mode'), null)).toBe(true);
    expect(conditionHolds(blank('mode'), 'anything')).toBe(false);
  });

  it('null and undefined sibling values are treated as blank, matching PropertyCondition#holds', () => {
    expect(conditionHolds(equalTo('mode', ''), null)).toBe(true);
    expect(conditionHolds(equalTo('mode', ''), undefined)).toBe(true);
  });

  it('normalises whitespace the same way NodePropertyDescriptor#adapterIdOf does, not with a second implementation', () => {
    // U+2007 FIGURE SPACE: ECMAScript's own trim() strips it, Java's Character.isWhitespace does not
    // (see adapter-binding.js's own enumeration). A value of only that character is therefore NOT
    // blank on either side of the language boundary, and this must agree with the server.
    expect(conditionHolds(blank('mode'), ' ')).toBe(false);
    expect(conditionHolds(present('mode'), ' ')).toBe(true);
    // Ordinary ASCII whitespace strips on both sides, so " WEBHOOK " still equals "WEBHOOK".
    expect(conditionHolds(equalTo('mode', 'WEBHOOK'), '  WEBHOOK  ')).toBe(true);
  });
});

describe('conditionHolds — fail-closed on anything not understood (security rule)', () => {
  it('an unrecognised contract is unsatisfiable even when the operator would otherwise match', () => {
    // Exactly the scenario the security rule names: an EQUALS that WOULD hold for this value under
    // the known contract, carried under a contract string this evaluator has never seen.
    const condition = { contract: 'ravenroot.property-condition/99-does-not-exist', property: 'mode', operator: 'EQUALS', values: ['WEBHOOK'] };
    expect(conditionHolds(condition, 'WEBHOOK')).toBe(false);
  });

  it('a missing contract field is unsatisfiable, not treated as the current one', () => {
    const condition = { property: 'mode', operator: 'EQUALS', values: ['WEBHOOK'] };
    expect(conditionHolds(condition, 'WEBHOOK')).toBe(false);
  });

  it('an unrecognised operator is unsatisfiable rather than silently true', () => {
    const condition = { contract: CONTRACT, property: 'mode', operator: 'NOT_EQUALS', values: ['LONG_POLLING'] };
    expect(conditionHolds(condition, 'WEBHOOK')).toBe(false);
  });

  it('a null condition reaching the evaluator directly is unsatisfiable, not an exception', () => {
    expect(conditionHolds(null, 'anything')).toBe(false);
    expect(conditionHolds(undefined, 'anything')).toBe(false);
  });

  it('a self-referential or mutually-referential condition pair evaluates without recursion or hanging', () => {
    // What a "cycle" would look like if a malformed descriptor ever reached the client despite the
    // backend's fail-closed load-time rejection: two conditions naming each other. Each one only
    // reads the OTHER property's raw current value — never the other's computed visibility — so
    // there is no call graph here for a cycle to occur IN. This is a robustness assertion (it
    // completes, synchronously, with an ordinary answer), not a claim that the shape is valid.
    const aWhenB = equalTo('b', 'X');
    const bWhenA = equalTo('a', 'Y');
    expect(conditionHolds(aWhenB, 'X')).toBe(true);
    expect(conditionHolds(bWhenA, 'Z')).toBe(false);
  });
});

describe('isPropertyVisible', () => {
  it('is unconditionally visible when visibleWhen is null — absence is not a condition that holds', () => {
    expect(isPropertyVisible({ name: 'callbackUrl', visibleWhen: null }, { mode: 'LONG_POLLING' })).toBe(true);
  });

  it('follows visibleWhen against the current sibling value', () => {
    const property = { name: 'callbackUrl', visibleWhen: equalTo('mode', 'WEBHOOK') };
    expect(isPropertyVisible(property, { mode: 'WEBHOOK' })).toBe(true);
    expect(isPropertyVisible(property, { mode: 'LONG_POLLING' })).toBe(false);
  });

  it('hides rather than shows when the condition names an unrecognised contract', () => {
    const property = {
      name: 'callbackUrl',
      visibleWhen: { contract: 'unknown/1', property: 'mode', operator: 'EQUALS', values: ['WEBHOOK'] },
    };
    // The sibling value WOULD satisfy EQUALS under the known contract — proving this hides, not that
    // an unsatisfiable condition trivially hides an already-unmatched value.
    expect(isPropertyVisible(property, { mode: 'WEBHOOK' })).toBe(false);
  });
});

describe('isPropertyRequiredNow', () => {
  it('a declared requiredWhen REPLACES the static flag, matching the doc\'s own worked example (required:false + requiredWhen = conditionally required)', () => {
    const property = { name: 'callbackUrl', required: false, requiredWhen: equalTo('mode', 'WEBHOOK') };
    expect(isPropertyRequiredNow(property, { mode: 'WEBHOOK' })).toBe(true);
    expect(isPropertyRequiredNow(property, { mode: 'LONG_POLLING' })).toBe(false);
  });

  it('a required property with no requiredWhen is unconditionally required', () => {
    const property = { name: 'mode', required: true, requiredWhen: null };
    expect(isPropertyRequiredNow(property, {})).toBe(true);
  });

  it('an optional property with no requiredWhen is never required', () => {
    const property = { name: 'classname', required: false, requiredWhen: null };
    expect(isPropertyRequiredNow(property, {})).toBe(false);
  });

  it('requiredWhen still governs even when the static flag also happens to be true', () => {
    const property = { name: 'callbackUrl', required: true, requiredWhen: equalTo('mode', 'WEBHOOK') };
    expect(isPropertyRequiredNow(property, { mode: 'WEBHOOK' })).toBe(true);
    expect(isPropertyRequiredNow(property, { mode: 'LONG_POLLING' })).toBe(false);
  });

  it('an unrecognised contract on requiredWhen fails closed to not-required', () => {
    const property = {
      name: 'callbackUrl',
      required: false,
      requiredWhen: { contract: 'unknown/1', property: 'mode', operator: 'EQUALS', values: ['WEBHOOK'] },
    };
    expect(isPropertyRequiredNow(property, { mode: 'WEBHOOK' })).toBe(false);
  });
});
