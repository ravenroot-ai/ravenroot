# Intergraph calls

Intergraph calls start a fresh child process from an immutable version already present in the deployment registry. They never install a bundle, publish a missing graph, or select the latest version. Ordinary top-level execution and imported GraphML keep their existing behavior.

## Graph contract

A target is callable when the graph-level `callable` property is absent or `true`. Set `callable=false` to refuse intergraph admission while leaving every other authorized ingress unchanged. A malformed value is refused.

Targets may declare all three members of either optional envelope contract:

| Property group | Required members when declared |
|---|---|
| Input | `call.input.schema`, `call.input.schemaVersion`, `call.input.kind` |
| Output | `call.output.schema`, `call.output.schemaVersion`, `call.output.kind` |

`kind` is `SCALAR`, `LIST`, or `MAP`. These properties validate the envelope's schema identity, version, and top-level payload kind; they do not evaluate a JSON Schema document. Partial declarations and mismatched envelopes are refused. With no declaration, the normal bounded payload model still applies.

The packaged server baseline authorizes a target only when its immutable `GraphVersion.createdBy` identity exactly matches the calling graph's qualified identity and the tenant matches. This is intentionally narrower than tenant membership. Embedded applications may compose another trusted `FlowTargetAuthorizer`; the running graph receives no roles or scopes with which to recreate an ingress authorization decision.

## Nodes

| Node | Properties | Success behavior |
|---|---|---|
| `call-flow` | required `deploymentId`, required positive `version`, optional `deadlineMs` (default 60000, maximum 7 days) | Starts and durably waits; returns the child output on `completed` |
| `start-flow` | the same target properties | Returns `{ "flowHandle": "…" }` on `started` |
| `await-flow` | none | Accepts that opaque handle as the payload or its `flowHandle` member and waits |

The terminal outcomes are `completed`, `failed`, `cancelled`, `deadline_exceeded`, `orphaned`, and `ambiguous`. A handle is runtime-issued, tenant-scoped, and bound to its original caller process. A second distinct continuation claimant is refused.

```xml
<node id="call">
  <data key="kind">BEHAVIOR</data>
  <data key="behavior">call-flow</data>
  <data key="deploymentId">01JEXAMPLEDEPLOYMENT</data>
  <data key="version">7</data>
  <data key="deadlineMs">30000</data>
</node>
```

For detached work, replace `call-flow` with `start-flow`, route `started` to an `await-flow` node, and pass the returned payload unchanged. The two forms use the same durable invocation capability.

## Settlement and recovery

The runtime commits the invocation intent, exact target version and digest, fresh child identities, canonical input, caller identity, deadline, and retention deadline before launch. A retry of the same caller invocation reuses the first intent. Current tenant quota and graph admission limits apply to the fresh child.

`await-flow` atomically parks the original caller invocation with its pinned graph, execution budget, and pending parallel paths. No worker thread is retained. The internal continuation transport is excluded from the Human Task inbox, confirmation workbench, administration, interaction providers, and all user settlement APIs. Its reserved response schema cannot be authored by `human-task`.

Recovery continuously reconciles bounded retained relation pages. An intent with an already accepted child is correlated without creating a second identity; unfinished children are checked for durable terminal state; terminal relations settle the caller checkpoint; expired terminal relations are purged explicitly. When completion committed before `await-flow`, the node atomically claims the single continuation and returns the durable result without parking. When completion races the park, the relation is reread after the caller checkpoint commits.

Cancellation asks the child traversal to stop. A confirmed stop settles `cancelled`; an unconfirmed stop settles `orphaned` and requires operator reconciliation. A deadline settles `deadline_exceeded`. Child failure settles `failed`. These outcomes do not require an END arrival. Cancellation propagation is bounded to the correlated child; the runtime does not promise exactly-once external effects performed by either graph.

A called graph completes only after the whole traversal settles. One END payload remains scalar. Multiple END arrivals, including repeated arrivals at one END node or parallel branches, are canonicalized, sorted, and returned as one bounded list. Ordinary executions retain their established terminal-payload behavior.

## Observability and UI

The internal `FlowInvocationCapability.observe` projection exposes the durable caller and child identities, target version and digest, status, wait claimant, failure classification, timestamps, and retention without exposing input or output payloads. The lookup is tenant-scoped and a cross-tenant handle is indistinguishable from a missing one. Execution events continue to describe both traversals and their flow nodes. Internal wait transport does not appear as user work.

The existing descriptor-driven authoring UI can render all three nodes and their properties without a custom editor. The packaged UI does not yet render the relation projection; a dedicated caller/child view can consume it later without changing the durable contract.
