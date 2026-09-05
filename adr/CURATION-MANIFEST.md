# Public ADR curation manifest

- Status: Complete for the published collection at 2026-09-05
- Ordering: Chronological, then numerical
- Publication scope: Accepted decisions and historically material superseded decisions

This manifest describes the architecture decision records published in this repository. Each entry
links only to another public file in the same collection. Working proposals and supporting evidence
remain outside the architecture contract until they are approved and prepared for publication.

Two earlier execution-engine decisions are consolidated as ADR 0002 because they describe one
architectural choice at different levels. The consolidation preserves the original decision number
without presenting the same choice as two independent contracts.

| Date | ADR | Publication status | Relationship note |
|---|---|---|---|
| 2026-07-20 | [0001](0001-ravenroot-product-boundaries.md) | Superseded | Retained for the history of Ravenroot's product boundary; its replacement records are linked from the ADR. |
| 2026-07-20 | [0002](0002-execution-engine-abstraction-and-runtime-selection.md) | Accepted | Consolidates execution-engine abstraction and runtime selection into one record. |
| 2026-07-21 | [0003](0003-graph-editor-and-live-execution-events.md) | Accepted | Defines the graph workspace and correlated live-event boundary. |
| 2026-07-21 | [0004](0004-oci-image-and-deployment-modes.md) | Accepted | Defines packaging and deployment shapes. |
| 2026-07-21 | [0005](0005-programmable-and-agentic-nodes.md) | Superseded in part | ADR 0029 replaces the default-core placement of AI and agent nodes. |
| 2026-07-30 | [0007](0007-process-traversal-invocation-attempt-lifecycle.md) | Accepted | Defines execution identity and lifecycle scopes. |
| 2026-08-02 | [0012](0012-engine-supervision-cancellation-and-drain.md) | Accepted | Defines supervised execution-engine lifecycle behavior. |
| 2026-08-02 | [0014](0014-local-execution-store-operational-surface.md) | Accepted | Defines the operational boundary of the local execution store. |
| 2026-08-02 | [0015](0015-structured-payloads-and-versioned-error-contract.md) | Accepted | Defines bounded payload and error contracts. |
| 2026-08-09 | [0017](0017-optional-generative-capability-boundaries.md) | Accepted | Defines the distribution boundary for optional generative capabilities. |
| 2026-08-09 | [0018](0018-credential-boundary-and-secret-bindings.md) | Accepted | Defines credential references and secret-binding authority. |
| 2026-08-10 | [0020](0020-artifact-execution-admission.md) | Accepted | Defines artifact admission and evidence binding. |
| 2026-08-11 | [0021](0021-deployment-runtime-ownership.md) | Accepted, partially superseded | ADRs 0023 and 0024 refine remote authority and runtime residency. |
| 2026-08-12 | [0008](0008-graph-definition-versioning-and-lifecycle.md) | Accepted | Defines graph-definition identity and lifecycle. |
| 2026-08-12 | [0009](0009-read-only-graph-traversal.md) | Accepted | Defines read-only traversal and controlled definition change. |
| 2026-08-12 | [0022](0022-ambiguous-work-is-parked.md) | Accepted | Defines recovery treatment for work whose external-effect status is ambiguous. |
| 2026-08-12 | [0023](0023-remote-deployment-control-plane.md) | Accepted contract | Defines the target remote deployment-control contract without asserting implementation. |
| 2026-08-13 | [0024](0024-node-runtime-nature.md) | Accepted | Defines runtime nature and demand-driven node instances. |
| 2026-08-27 | [0027](0027-live-request-reply-ingress.md) | Accepted | Defines request/reply completion through managed ingress. |
| 2026-08-29 | [0028](0028-iteration-correlated-fan-in.md) | Accepted | Defines fan-in correlation across graph iterations. |
| 2026-08-29 | [0029](0029-model-provider-spi-after-externalization.md) | Accepted | Defines the model-provider extension boundary after AI-node externalization. |
| 2026-08-30 | [0030](0030-zero-or-more-node-emissions.md) | Accepted contract | Defines a future emission contract and states its implementation status explicitly. |
| 2026-09-03 | [0031](0031-durable-canonical-graph-definitions.md) | Accepted | Defines durable, content-addressed graph definitions and their relationship to ADR 0008 identity and ADR 0023 deployment versions. |
| 2026-09-03 | [0032](0032-durable-process-inventory-is-authoritative-rows.md) | Superseded in part | Defines the durable, tenant-scoped process inventory as authoritative rows with a derived, unstored recovery classification. Its statement that the product keeps no durable record of an operator hold is superseded by 0033; the derived-classification decision stands. |
| 2026-09-04 | [0033](0033-durable-operator-holds.md) | Accepted | Defines an operator hold on a traversal as a durable record committed at a writable traversal boundary, with the traversal stored as `WAITING`, no claimable work, and continuation only by an authorized resume. |
| 2026-09-04 | [0034](0034-immutable-resolved-execution-manifests.md) | Accepted | Extends ADR 0031: the pinned document is joined by the resolved dependency set an execution was accepted against, and recovery verifies both. |
| 2026-09-05 | [0035](0035-cancellation-as-a-distinct-termination-reason.md) | Accepted | Defines a nullable termination reason beside an unchanged terminal status, and a dedicated terminal event type, so a cancelled execution is distinguishable from an ordinary failure everywhere it is reported. |
| 2026-09-04 | [0036](0036-layered-graph-drawing-in-the-design-editor.md) | Accepted | Defines the additive layered arrangements of the design editor: ELK layered placement and routing consumed together, label-aware spacing, back edges routed outside the band, and the existing arrangements left unchanged. |

## Publication rules

- An accepted architecture contract is not, by itself, an implementation claim.
- A partially superseded record identifies the newer decision that governs the replaced portion.
- A superseded record remains available only when it explains an important historical boundary.
- Public references resolve to this repository's documentation or to another published ADR.
