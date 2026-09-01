// Ports the ADR 0024 §2 / runtime-nature contract to the Inspector. Read
// `ai.ravenroot.api.catalog.NodeRuntimeNature`, `NodeRuntimeNatureProperty` and `NodeTypeDescriptor`
// (ravenroot-application-api) first — this module implements exactly what those classes already
// decided and adds no policy of its own. The server, through `NodeRuntimeNatureValidator`, is the
// enforcement boundary; this module's job is narrower: it projects the runtime-nature contract into the Inspector.
// never OFFER a nature the catalog withheld, and never render "declared" and "inherited" identically.
//
// `/v1/catalog` (`RavenrootServer#nodeTypeJson`) already ships the three fields this module reads off
// a descriptor: `defaultNature` and `allowedNatures` are the EFFECTIVE values (never the raw
// possibly-absent declaration — that distinction is server-side only, per the same method's comment),
// and `natureProperty` is the well-known graph property name (`NodeRuntimeNatureProperty.NAME`,
// `"runtime.nature"` today) published so the editor and the server can never drift on what key an
// author's choice is written under. There is deliberately no per-node inspection route: the effective
// value is computed here, client-side, from data the catalog already publishes.

/** Mirrors `NodeRuntimeNatureProperty.NAME`. Used only when a descriptor omits `natureProperty` — a
 * catalog that predates the server change, or a test fixture that does not care about the name. */
export const DEFAULT_NATURE_PROPERTY = 'runtime.nature';

/** Mirrors `NodeRuntimeNature.DEFAULT` (`WORKER`). What every node that cannot declare a nature at
 * all — a non-behavior node, or a behavior absent from the catalog — unconditionally is. */
export const DEFAULT_NATURE = 'WORKER';

/** Fallback name only; an authoring control is rendered only when the catalog publishes its bound. */
export const DEFAULT_MAX_CONCURRENCY_PROPERTY = 'runtime.maxConcurrency';

/**
 * Catalog-driven effective admission value. An invalid/stale graph value falls back for rendering;
 * the server remains the validation boundary and refuses it rather than accepting this fallback.
 */
export function effectiveMaxConcurrency(descriptor, declaredValue) {
  const fallback = Number(descriptor?.defaultMaxConcurrency);
  const ceiling = Number(descriptor?.maxConcurrencyCeiling);
  if (!Number.isSafeInteger(fallback) || fallback < 1
      || !Number.isSafeInteger(ceiling) || ceiling < fallback) return null;
  if (declaredValue == null || declaredValue === '') {
    return { value: fallback, ceiling, declared: false, valid: true };
  }
  const text = String(declaredValue);
  if (!/^\d+$/.test(text)) return { value: fallback, ceiling, declared: true, valid: false };
  const parsed = Number(text);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > ceiling) {
    return { value: fallback, ceiling, declared: true, valid: false };
  }
  return { value: parsed, ceiling, declared: true, valid: true };
}

/**
 * The stable serialized identifier as its own author-facing text.
 *
 * ADR 0024 gives "Authoritative manager" for `AUTHORITY` as an example, not a normative label map.
 * Code therefore preserves the stable identifier. This function is the seam for any future public
 * label map, without requiring call-site changes.
 */
export function natureLabel(identifier) {
  return identifier;
}

/**
 * The effective nature for one node usage: what it declared, or the descriptor's default — mirroring
 * `NodeRuntimeNatureProperty#effectiveNature`'s fallback exactly, including its fail-safe reading of a
 * value the CURRENT descriptor no longer permits.
 *
 * `declared` is `true` only when `declaredValue` names one of `descriptor.allowedNatures` — the set
 * `/v1/catalog` already publishes as the fail-closed EFFECTIVE allowlist (never "anything", see the
 * server-side comment this module's header quotes). A `declaredValue` naming something outside that
 * set — stale data from a graph imported before the catalog tightened its allowlist, or a value this
 * catalog never declared — resolves to the descriptor's default with `declared: false`, exactly like
 * an absent declaration: this function is a rendering fallback, not a second validator, and a graph
 * that is actually invalid is refused server-side, never silently accepted here. This mirrors the
 * existing precedent for an ordinary catalog property's stale `allowedValues` selection
 * (`catalogPropertyFieldsHtml` in app.js), not a new failure mode invented for nature.
 *
 * @param descriptor a `/v1/catalog` entry (`{ defaultNature, allowedNatures, natureProperty }`), or
 * `null`/`undefined` for a node that cannot declare a nature at all (see `DEFAULT_NATURE`'s doc)
 * @param declaredValue the raw string the node's properties carry under the nature property name, or
 * `null`/`undefined`/`''` for "declares nothing"
 */
export function effectiveNature(descriptor, declaredValue) {
  const fallback = descriptor?.defaultNature || DEFAULT_NATURE;
  if (!descriptor || declaredValue == null || declaredValue === '') {
    return { value: fallback, declared: false };
  }
  const allowed = Array.isArray(descriptor.allowedNatures) && descriptor.allowedNatures.length
    ? descriptor.allowedNatures : [fallback];
  if (allowed.includes(declaredValue)) {
    return { value: declaredValue, declared: true };
  }
  return { value: fallback, declared: false };
}

/**
 * Plain-language deploy-refusal help text for `AUTHORITY`/`KEYED`, or `''` for a nature deployable
 * today. Mirrors `NodeRuntimeNature#requiresUnimplementedResidency` — this is the one place the
 * Inspector states the refusal in direct product language rather than an external citation, so an author
 * who picks one of these learns *before* deploying that the platform will refuse it and why.
 */
export function natureRiskText(identifier) {
  if (identifier === 'AUTHORITY') {
    return 'AUTHORITY needs one durable, fenced owner for its whole cluster. Ravenroot does not '
      + 'implement that ownership yet, so a graph that resolves to AUTHORITY is refused when you '
      + 'run or deploy it — before any node starts, not partway through.';
  }
  if (identifier === 'KEYED') {
    return 'KEYED needs one owner per key you declare. Ravenroot does not implement that per-key '
      + 'ownership yet, so a graph that resolves to KEYED is refused when you run or deploy it — '
      + 'before any node starts, not partway through.';
  }
  return '';
}
