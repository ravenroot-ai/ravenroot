// Which node types invoke a model, and the one sentence the Inspector adds when one is left
// unconfigured.
//
// ── WHY THIS FILE EXISTS SEPARATELY ──────────────────────────────────────────────────────────────
//
// The model-provider form, validation and transport are absent from this editor. The two concerns
// here are independent of that UI: `invokesModelProvider` classifies a CATALOG DESCRIPTOR, and the
// Inspector still needs truthful guidance for an unconfigured model-invoking node.
//
// DOM-free and network-free: nothing here reads an element or opens a request.
//
// ── WHAT THE SENTENCE BELOW MAY SAY ─────────────────────────────────────────────────────────────
//
// Guidance may name only a surface the shipped product actually provides. This editor has no Model
// providers panel, `/v1/model-providers` API or included provider bundle. The core catalog also has no
// model-invoking node; any such descriptor visible here comes from an installed bundle or embedding
// application, whose deployment composed the provider. The sentence therefore names THE
// DEPLOYMENT'S OWN COMPOSITION and states plainly that neither this editor nor its API can supply it.

/**
 * The one sentence the Inspector adds to an unconfigured model-invoking node, so an author who
 * reaches a blank `provider` field learns what the value IS instead of being left with an
 * unexplained blank.
 *
 * A SENTENCE ABOUT WHAT THE FIELD NAMES, not a claim that anything is configured — see
 * `invokesModelProvider` for why it is not shown on every adapter-bound property.
 *
 * IT MAY NOT NAME A SURFACE THE SHIPPED PRODUCT DOES NOT PROVIDE: neither a panel in this editor nor
 * a bundle absent from the distribution. `generative-capability.test.js` reads
 * `ravenroot-plugins/` and fails this constant if it names a bundle while none exists, so the
 * prohibition is measured against the repository rather than remembered.
 */
export const PROVIDER_CONFIG_POINTER =
  'This names a model provider the deployment itself composed. It cannot be created from this '
  + 'editor, and there is no Ravenroot API that accepts one: use the id whoever installed this node '
  + 'registered it under.';

/**
 * Ports `ai.ravenroot.api.provenance.SyntheticProvenance#GENERATIVE_CAPABILITIES`. The runtime reads
 * exactly this set to decide whether a node invokes a model, precisely so that neither side keeps a
 * list of behavior names in step with the catalog.
 *
 * It is a CAPABILITY set because `llm-prompt` and `agent` are not in the core catalog, so a list of
 * behavior names in this editor would contain names the editor never receives. A capability is
 * declared by whatever supplies the node, so a bundle that adds a model-invoking type — under any
 * name — is covered by this file without changing it.
 */
export const GENERATIVE_CAPABILITIES = Object.freeze(['ai', 'agentic']);

/**
 * Whether this descriptor's adapter binding names a MODEL provider — the only case in which
 * `PROVIDER_CONFIG_POINTER` is true.
 *
 * `adapterBinding` is a plain boolean on `NodePropertyDescriptor`: it says "this property names a
 * deployment-configured adapter", not WHICH KIND of adapter. An AMQP or Telegram node package can
 * declare one too, and telling its author to go and configure a model provider would be a confident,
 * wrong instruction. So the kind is read from the catalog's declared capabilities, exactly as the
 * server reads them, and never from the behavior name — `e2e/adapter-binding-unconfigured.spec.js`
 * already pins that the editor must use the catalog flag rather than a behavior-name allow-list, and
 * this is the same rule applied to the sentence rather than to the field state. There are no core
 * model-invoking behavior names from which to build such an allow-list.
 */
export function invokesModelProvider(descriptor) {
  const capabilities = descriptor?.capabilities;
  if (!Array.isArray(capabilities)) return false;
  return capabilities.some(capability => GENERATIVE_CAPABILITIES.includes(String(capability)));
}
