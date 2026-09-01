// Ports the outcome declaration to the Inspector. Read `ai.ravenroot.api.catalog.
// NodeOutcomeDescriptor` and `NodeTypeDescriptor#resolveOutcomes` (ravenroot-application-api) first --
// this module implements exactly what those two already decided and adds no policy of its own.
//
// `/v1/catalog` (`RavenrootServer#nodeTypeJson`) ships each descriptor's `outcomes` as an ordered
// array of `{ name, fromProperty, description }`, where exactly one of `name`/`fromProperty` is
// non-empty. It ships the DECLARATION rather than a resolved set on purpose: `cel-decision` and
// `http-request` read their outcome names out of node properties, so only a consumer holding the node
// -- this one -- can resolve them. There is deliberately no per-node inspection route, for the same
// reason `node-nature.js` states: the answer is computed client-side from data the catalog publishes.
//
// The resolution order below is a port, not a choice, and the blank case is the one that matters. On
// the Java side `NodeProperties.string` substitutes a behavior's default for an ABSENT property only,
// never for one present and empty; `NodeResult`'s compact constructor then coerces the empty outcome
// to 'continue'. So a node whose `trueOutcome` is set to '' genuinely emits 'continue' at run time,
// and falling back to the declared default there would suggest an outcome the node cannot produce.

/** Mirrors `NodeOutcomeDescriptor.BLANK_OUTCOME`, itself mirroring `NodeResult`'s coercion. */
export const BLANK_OUTCOME = 'continue';

/**
 * The outcome one declaration resolves to for one configured node.
 *
 * @param outcome a `/v1/catalog` outcome entry (`{ name, fromProperty }`)
 * @param configuredValue the raw value the node carries under `outcome.fromProperty`, or
 * `null`/`undefined` when it carries no such property — the distinction is load-bearing, see header
 * @param declaredDefault the property descriptor's `defaultValue`
 */
export function resolveOutcome(outcome, configuredValue, declaredDefault) {
  if (!outcome?.fromProperty) return outcome?.name || '';
  const raw = configuredValue == null ? declaredDefault : configuredValue;
  const text = raw == null ? '' : String(raw);
  return text.trim() === '' ? BLANK_OUTCOME : text;
}

/**
 * Every outcome a node can produce, in declaration order, deduplicated.
 *
 * Mirrors `NodeTypeDescriptor#resolveOutcomes`, including its deduplication: two properties set to the
 * same name are one outcome, because one outcome is all `nextEdges` can distinguish.
 *
 * **Not the set of edges that can fire.** A runner that finds no edge matching the produced outcome
 * retries the lookup with 'continue' (`GraphRunner`), so an edge wired to 'continue' may fire for an
 * outcome absent from this list. This list is what the source node can emit; turning it into a verdict
 * about one edge is `unreachableOutcome`'s job below, and that function exists precisely because the
 * step is not the identity — it has three exemptions this list knows nothing about.
 *
 * @param descriptor a `/v1/catalog` entry, or `null` for a node whose behavior the catalog lacks
 * @param nodeProperties the node's properties object, keyed by property name
 * @returns array of `{ outcome, description, parameterized, property }`, empty when the descriptor
 * declares nothing — a catalog entry predating outcome metadata, or a node type that is not a behavior
 */
export function resolveOutcomes(descriptor, nodeProperties) {
  const declared = descriptor?.outcomes;
  if (!Array.isArray(declared) || declared.length === 0) return [];
  const properties = nodeProperties || {};
  const byName = new Map();
  for (const outcome of declared) {
    const property = outcome?.fromProperty || '';
    const declaredDefault = property
      ? (descriptor.properties || []).find(candidate => candidate?.name === property)?.defaultValue
      : undefined;
    const resolved = resolveOutcome(outcome, property ? properties[property] : undefined, declaredDefault);
    if (!resolved || byName.has(resolved)) continue;
    byName.set(resolved, {
      outcome: resolved,
      description: outcome?.description || '',
      parameterized: Boolean(property),
      property,
    });
  }
  return [...byName.values()];
}

/**
 * The outcome `GraphRunner` retries with when the produced one selects no edge.
 *
 * Numerically the same string as `BLANK_OUTCOME`, and deliberately a second constant: that one is
 * `NodeResult` coercing a blank outcome before a runner sees it, this one is `nextEdges` being asked a
 * second question after the first found nothing. Two mechanisms, two reasons to change independently.
 */
export const FALLBACK_OUTCOME = 'continue';

/** `NodeProperties.render`'s token. Its PRESENCE is the signal here; it is never expanded. */
const TEMPLATE_TOKEN = /\{\{.*?\}\}/;

/**
 * Whether an edge's outcome is one its source node cannot emit.
 *
 * **Silence is the default, and every uncertain case takes it.** A false positive here is worse than
 * no signal: an author shown a wrong error learns to ignore the right ones too. So this answers `true`
 * only where the set is exact and the outcome is provably outside it, and `false` everywhere else —
 * including cases where the edge may well be wrong and this function cannot establish it.
 *
 * Three exemptions, each a measured property of this repository rather than a precaution:
 *
 * - **An edge wired to `continue`, whatever the set says.** When `nextEdges` matches no edge for the
 * produced outcome, `GraphRunner` asks again with `continue` (`GraphRunner.java`, the
 * `next.isEmpty() && !"continue".equals(...)` retry). A `continue` edge is therefore reachable from
 * outcomes nothing declares — it is the fallback target by construction — and flagging it would be
 * wrong on every graph that has one, which is most of them.
 * - **An empty set.** It means the descriptor declares nothing, which is *unknown*, never *emits
 * nothing*. This is the exemption that matters most by volume: read the other way it would flag
 * every edge leaving every node whose behaviour has no declaration. Today that is seven of the nine
 * extension modules — only `ravenroot-jdbc` and `ravenroot-spel` declare — plus every node package
 * published outside this repository, which is the larger population and the one that keeps growing.
 * - **A parameterized outcome that still holds a `{{…}}` token.** Every behaviour in this repository
 * reads its outcome properties verbatim: `NodeProperties.render` is applied to `url`, `body`,
 * `source`, `template`, `prompt` and `objective`, and to no outcome property in core or in
 * extensions. A third-party behaviour is free to render its own, and for that one the resolved name
 * is the template text rather than the outcome emitted. The token is the only trace of that case
 * visible from here, so a node carrying one is treated as unknowable. This costs a true positive on
 * a core node whose outcome property genuinely contains braces — accepted, because the trade runs
 * the safe way: a missed warning, not a wrong one.
 *
 * @param resolved the array `resolveOutcomes` returned for the SOURCE node
 * @param edgeOutcome the edge's outcome field, raw — compared as `renderEdgeForm` will save it
 * @returns `true` only when the warning is certainly correct
 */
export function unreachableOutcome(resolved, edgeOutcome) {
  if (!Array.isArray(resolved) || resolved.length === 0) return false;
  // Compared exactly as the submit handler will persist it: trimmed, and blank standing for the
  // default. Warning on the untrimmed field would fire on 'approved ' while the author is mid-word
  // and then vanish on save, which is the field correcting itself and looks like a flapping error.
  const outcome = String(edgeOutcome ?? '').trim() || FALLBACK_OUTCOME;
  if (outcome === FALLBACK_OUTCOME) return false;
  if (resolved.some(entry => entry?.parameterized && TEMPLATE_TOKEN.test(String(entry?.outcome ?? '')))) {
    return false;
  }
  // `equals`, like `nextEdges`. Case and inner spacing are load-bearing at run time, so they are here.
  return !resolved.some(entry => entry?.outcome === outcome);
}
