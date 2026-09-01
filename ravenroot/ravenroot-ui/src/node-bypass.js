// The editor half of the per-node execution bypass. Read
// `ai.ravenroot.api.catalog.NodeBypassProperty` and `ai.ravenroot.core.runtime.NodeBypassValidator`
// first, and `docs/architecture/per-node-execution-bypass.md` for the boundary between this flag and
// the two older mechanisms it resembles — this module implements exactly what those already decided
// and adds no policy of its own. The runtime is the enforcement boundary; this module's job is
// narrower and is the same rule `node-nature.js` states for its own property: never offer the
// control where the platform would refuse it, and never say less about the consequence than the
// runtime will impose.
//
// Same shape as `runtime.nature`, deliberately: a platform-owned node property with a name published
// per node type by `GET /v1/catalog` (`bypassProperty`, from `RavenrootServer#nodeTypeJson`) so the
// editor DERIVES the key it writes instead of hardcoding it and drifting from the server.
//
// One thing is NOT like `runtime.nature`, and it is the reason `bypassPropertyName` takes the whole
// catalog rather than one descriptor: the flag is legal on an UNCATALOGUED behavior. That is the case
// in which the behavior cannot be provisioned, so an author must be able
// to switch off a node the catalog has no descriptor for, and the name still has to come from the
// catalog rather than from this file. Every descriptor publishes the same platform-fixed name (there
// is deliberately no per-type `allowedBypassValues`, for the same reason), so any entry answers it.

/** Mirrors `NodeBypassProperty.NAME`. Used only when no descriptor published a name — a catalog that
 * predates the server change, an unreachable catalog, or a test fixture that does not care. */
export const DEFAULT_BYPASS_PROPERTY = 'execution.bypass';

/** Mirrors `NodeBypassProperty.allowedValues()`. The values are fixed by the platform for every node
 * type, so this is a constant here too and not something a descriptor may narrow. */
export const BYPASS_ALLOWED_VALUES = Object.freeze(['true', 'false']);

/** What the editor writes for a switched-off node. `NodeBypassProperty.parse` accepts the string and
 * the typed boolean alike; the string is what GraphML serialization already does with every other
 * property this editor writes, so writing it keeps one spelling in the document. */
export const BYPASS_TRUE = 'true';

/**
 * The property name to read and write, derived from the catalog.
 *
 * Preference order, and the reason for it: the node's own descriptor when it has one; then any
 * descriptor in the catalog, because the name is platform-fixed and identical on all of them and a
 * node with an uncatalogued behavior still has to be switchable off; then the mirrored constant, for
 * a catalog that is empty, unreachable or predates the `bypassProperty` field.
 *
 * @param descriptor the node's `/v1/catalog` entry, or null/undefined for an uncatalogued behavior
 * @param catalog the whole `/v1/catalog` list, or null/undefined when it never loaded
 */
export function bypassPropertyName(descriptor, catalog) {
  const declared = typeof descriptor?.bypassProperty === 'string' && descriptor.bypassProperty.trim();
  if (declared) return descriptor.bypassProperty.trim();
  const published = (Array.isArray(catalog) ? catalog : [])
    .map(entry => (typeof entry?.bypassProperty === 'string' ? entry.bypassProperty.trim() : ''))
    .find(Boolean);
  return published || DEFAULT_BYPASS_PROPERTY;
}

/**
 * Mirrors `NodeBypassProperty.parse`: `true`/`false` (either spelling, case-insensitive, trimmed) or
 * `null` for anything else — never a repaired `false`.
 *
 * The `null` matters and is not defensive noise. `NodeBypassValidator` refuses the WHOLE GRAPH for an
 * unparseable value rather than reading it as "not switched off", so an editor that quietly showed an
 * unchecked box for `execution.bypass=yes` would show a document the runtime will not load as if it
 * were fine. The Inspector renders that state as its own readout instead.
 */
export function parseBypassValue(raw) {
  if (typeof raw === 'boolean') return raw;
  if (typeof raw === 'string') {
    const normalized = raw.trim().toLowerCase();
    if (normalized === 'true') return true;
    if (normalized === 'false') return false;
  }
  return null;
}

/**
 * Whether the platform accepts the key on this node at all.
 *
 * `NodeBypassValidator` refuses it on every node that is not `BEHAVIOR` — `START`, `END`, `ERROR` and
 * the structural passthrough — and refuses it there **for `false` as well as `true`**, because there
 * is no behaviour to skip and the statement has no referent. So the control must not merely be
 * disabled on those nodes: it must not exist, or flipping it off again would be the refusal.
 */
export function nodeAcceptsBypass(kind) {
  return String(kind || '') === 'BEHAVIOR';
}

/**
 * The declared state of one node, as three cases the Inspector renders differently.
 *
 * `state` is `'off'` (absent, or an explicit `false`), `'on'`, or `'unreadable'` — the value the
 * document carries is neither, and the runtime will refuse to load the graph.
 */
export function declaredBypass(properties, propertyName) {
  const name = propertyName || DEFAULT_BYPASS_PROPERTY;
  const values = properties || {};
  if (!Object.hasOwn(values, name)) return { state: 'off', declared: false, raw: '' };
  const raw = values[name];
  const parsed = parseBypassValue(raw);
  if (parsed === null) return { state: 'unreadable', declared: true, raw: raw == null ? '' : String(raw) };
  return { state: parsed ? 'on' : 'off', declared: true, raw: raw == null ? '' : String(raw) };
}

/**
 * Whether the canvas should draw this node as switched off. Mirrors `NodeBypassProperty.isBypassed`:
 * only an explicit, well-formed `true` counts, and an unreadable value draws as executing because
 * that is what the runtime would do if the validator ever let it through.
 */
export function isNodeBypassed(properties, propertyName) {
  return declaredBypass(properties, propertyName).state === 'on';
}

/**
 * The outcomes on this node's outgoing edges that a switched-off node will NOT take.
 *
 * A bypassed node always emits `GraphEdge.DEFAULT_OUTCOME` (`continue`), whatever it would normally
 * have chosen, so every outgoing edge wired to some other outcome is dead for as long as the flag is
 * on. This is the consequence the contract requires to be legible in the Inspector rather than only in the
 * documentation: it changes which path the graph takes, and the author has to see it while switching
 * the node off, not afterwards.
 *
 * Deduplicated and stable-ordered so the sentence reads the same on every render. `continue` is
 * excluded because it is exactly the branch that IS taken.
 */
export function untakenBypassOutcomes(edges, nodeId, defaultOutcome = 'continue') {
  const seen = new Set();
  for (const edge of Array.isArray(edges) ? edges : []) {
    if (!edge || edge.source !== nodeId) continue;
    const outcome = String(edge.outcome || edge.label || defaultOutcome).trim() || defaultOutcome;
    if (outcome === defaultOutcome) continue;
    seen.add(outcome);
  }
  return [...seen];
}

/**
 * The sentence stating what switching this node off does to the routing, or `''` when there is
 * nothing node-specific to say beyond the general hint.
 *
 * Two different sentences on purpose. A node whose only outgoing edges are `continue` loses nothing,
 * and warning it anyway would train an author to skim the box that matters. A node with named
 * branches loses them by name, so the names are in the sentence — an author who cannot see WHICH
 * branch stops firing has to go and count edges to find out.
 */
export function bypassRoutingConsequence(untakenOutcomes, defaultOutcome = 'continue') {
  const outcomes = Array.isArray(untakenOutcomes) ? untakenOutcomes.filter(Boolean) : [];
  if (!outcomes.length) return '';
  const quoted = outcomes.map(outcome => `“${outcome}”`).join(', ');
  const branch = outcomes.length === 1 ? 'branch is' : 'branches are';
  return `A switched-off node does not choose an outcome — it always takes the ${defaultOutcome} `
    + `branch. This node's ${quoted} ${branch} therefore not taken while the flag is on. If those `
    + 'branches converge on a join downstream, the join can stop being satisfiable.';
}
