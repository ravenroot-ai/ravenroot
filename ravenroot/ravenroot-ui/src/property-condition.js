// Ports `ai.ravenroot.api.catalog.PropertyCondition`/`PropertyConditionOperator`
// (ravenroot-application-api) to the editor. Read
// `docs/getting-started/conditional-node-properties.md` first — this file implements exactly that
// contract and adds nothing to it: no negation, no cross-node references, no expression combinators.
//
// ── The security rule this file exists to carry ─────────────────────────────────────────────────
//
// `PropertyCondition#CONTRACT` is versioned and travels with every serialized condition specifically
// so a consumer that does not recognise it can refuse to guess. A consumer that ignored an unknown
// contract and evaluated the condition anyway would silently turn a guarded field into an
// unconditional one — the failure is invisible, because the field just always appears, exactly as if
// no condition had been declared at all. `conditionHolds` below refuses first, before it ever looks
// at `operator` or `values`, so an unrecognised contract can never reach the switch that would
// otherwise evaluate it.
//
// The same function also carries the frontend's only defence-in-depth obligation: the backend
// validates every descriptor fail-closed at catalog load (bad references, cycles, inadmissible
// values), so a validated catalog can never actually SERVE a malformed condition — the Inspector is
// not a second validator and must not become one. But "cannot happen given a validated catalog" and
// "cannot arrive at this function" are different claims, and this module is written to the second,
// narrower one: any condition shape it does not fully recognise — unknown contract, unknown
// operator — is treated as unsatisfiable, never as holding. Fail-closed, not fail-silent.
//
// ── Why there is nothing here that looks like recursion ─────────────────────────────────────────
//
// A condition names one sibling PROPERTY and reads that sibling's current raw VALUE — never another
// property's computed visibility or required-ness. Evaluating property A's condition therefore never
// evaluates property B's condition, even when B's own condition happens to name A back: each lookup
// is an O(1) read of a value already sitting in the caller's `valuesByName` map. A descriptor that
// declared a genuine cycle (A depends on B depends on A) is rejected at catalog load on the backend
// and can never reach here — but even if a malformed one somehow did, there is no call graph in this
// module for it to be a cycle IN, which is a property of the design, not a check this file performs.

import { adapterIdOf } from './adapter-binding.js';

/** The one contract version this module understands. Mirrors `PropertyCondition.CONTRACT`. */
export const CONTRACT = 'ravenroot.property-condition/1';

/**
 * Whether `condition` holds against `siblingRawValue`, the sibling property's current (possibly
 * unsaved, possibly blank) value as the editor holds it right now.
 *
 * Mirrors `PropertyCondition#holds(String)` exactly, including its blank/whitespace normalisation —
 * reused from `./adapter-binding.js`'s `adapterIdOf` rather than reimplemented, because it is the
 * same question (`String#strip()`-equivalent blankness) `NodePropertyDescriptor#adapterIdOf` already
 * answers on the Java side, and CORE-07/ already establishes that this project keeps exactly one
 * definition of it per language, not one per caller.
 *
 * Returns `false` — never throws, never falls through to "holds" — for anything this module does not
 * fully recognise: an unrecognised `contract`, or an operator outside the four this module knows.
 * `condition` itself is expected non-null; a caller holding a possibly-absent condition decides what
 * absence means (see `isPropertyVisible`/`isPropertyRequiredNow` below) before calling this, but a
 * nullish `condition` reaching this function anyway is also treated as unsatisfiable rather than
 * throwing, for the same fail-closed reason.
 */
export function conditionHolds(condition, siblingRawValue) {
  if (!condition || condition.contract !== CONTRACT) return false;
  const value = adapterIdOf(siblingRawValue);
  switch (condition.operator) {
    case 'EQUALS':
    case 'ONE_OF':
      return Array.isArray(condition.values) && condition.values.includes(value);
    case 'PRESENT':
      return value !== '';
    case 'BLANK':
      return value === '';
    default:
      // The operator set is closed by design (see PropertyConditionOperator's own javadoc for why
      // there is deliberately no fifth case) — this is not a case this module expects to reach
      // against a validated catalog, but an operator it does not recognise gets the same refusal an
      // unrecognised contract gets, not a silent "true".
      return false;
  }
}

/**
 * Whether `property` should be shown, given every sibling's current raw value in `valuesByName`
 * (keyed by property name). `null`/`undefined` `visibleWhen` means no condition was declared, which
 * is unconditionally visible — not the same statement as "a condition that happens to hold" (see the
 * doc's own "null means no condition declared" line).
 */
export function isPropertyVisible(property, valuesByName) {
  const condition = property?.visibleWhen;
  if (!condition) return true;
  return conditionHolds(condition, valuesByName?.[condition.property]);
}

/**
 * Whether `property` is required RIGHT NOW.
 *
 * A declared `requiredWhen` REPLACES the static `required` flag as the answer, it does not gate on
 * top of it — the doc's own worked example (and its HTTP example, `"required":false` carrying a
 * `requiredWhen`) declares the base flag `false` precisely because the property is optional by
 * default and `requiredWhen` is what makes it conditionally mandatory. Requiring the base flag to
 * already be `true` before `requiredWhen` could do anything would make that documented example
 * impossible to express. `null`/absent `requiredWhen` falls back to the static flag unconditionally
 * — the ordinary, unconditional case does not change.
 *
 * Deliberately never consults `visibleWhen` here: the two are load-time-validated so that
 * `requiredWhen` holding implies `visibleWhen` holding, but that implication is a property of the
 * DATA, not something this function re-derives. A caller that also needs visibility calls
 * `isPropertyVisible` separately (`catalogPropertyFieldsHtml` does, and additionally never emits the
 * native `required` attribute while hidden regardless of what this function returns, as defence in
 * depth against a descriptor that violated that invariant).
 */
export function isPropertyRequiredNow(property, valuesByName) {
  const condition = property?.requiredWhen;
  if (condition) {
    return conditionHolds(condition, valuesByName?.[condition.property]);
  }
  return Boolean(property?.required);
}
