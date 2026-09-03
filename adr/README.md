# Ravenroot Architecture Decision Records

This directory is the definitive public record of Ravenroot architecture decisions. It is part of
the repository, but it is not part of the documentation-site source tree. Product documentation
lives under `docs/`; this collection remains independently browsable in the repository.

Each record states a decision and its consequences at the date shown. An accepted contract can
describe behavior that is not implemented yet; implementation status is stated explicitly and must
not be inferred from acceptance. Superseded records remain here when they explain a historically
important boundary.

The [curation manifest](CURATION-MANIFEST.md) records the publication status and relationship of
every decision in this collection. Working proposals become part of this public record only after
their architecture status and wording are ready for publication.

## Decision index

| Date | ID | Status | Decision |
|---|---|---|---|
| 2026-07-20 | 0001 | Superseded | [Ravenroot product boundaries](0001-ravenroot-product-boundaries.md) |
| 2026-07-20 | 0002 | Accepted | [Execution-engine abstraction and runtime selection](0002-execution-engine-abstraction-and-runtime-selection.md) |
| 2026-07-21 | 0003 | Accepted | [Graph editor and live execution events](0003-graph-editor-and-live-execution-events.md) |
| 2026-07-21 | 0004 | Accepted | [OCI image and deployment modes](0004-oci-image-and-deployment-modes.md) |
| 2026-07-21 | 0005 | Superseded in part | [Programmable, AI, and agentic nodes](0005-programmable-and-agentic-nodes.md) |
| 2026-07-30 | 0007 | Accepted | [Process, traversal, invocation, and attempt lifecycle](0007-process-traversal-invocation-attempt-lifecycle.md) |
| 2026-08-02 | 0012 | Accepted | [Engine supervision, cancellation, and drain](0012-engine-supervision-cancellation-and-drain.md) |
| 2026-08-02 | 0014 | Accepted | [Local execution-store operational surface](0014-local-execution-store-operational-surface.md) |
| 2026-08-02 | 0015 | Accepted | [Structured payloads and a versioned error contract](0015-structured-payloads-and-versioned-error-contract.md) |
| 2026-08-09 | 0017 | Accepted | [Product boundaries for optional generative capabilities](0017-optional-generative-capability-boundaries.md) |
| 2026-08-09 | 0018 | Accepted | [Credential boundary and user-authored secret bindings](0018-credential-boundary-and-secret-bindings.md) |
| 2026-08-10 | 0020 | Accepted | [Artifact execution admission and the TOCTOU boundary](0020-artifact-execution-admission.md) |
| 2026-08-11 | 0021 | Accepted, partially superseded | [Deployment runtime ownership](0021-deployment-runtime-ownership.md) |
| 2026-08-12 | 0008 | Accepted | [Graph-definition versioning and lifecycle](0008-graph-definition-versioning-and-lifecycle.md) |
| 2026-08-12 | 0009 | Accepted | [Read-only graph traversal and controlled definition change](0009-read-only-graph-traversal.md) |
| 2026-08-12 | 0022 | Accepted | [Ambiguous work is parked](0022-ambiguous-work-is-parked.md) |
| 2026-08-12 | 0023 | Accepted contract | [Remote deployment control plane](0023-remote-deployment-control-plane.md) |
| 2026-08-13 | 0024 | Accepted | [Node runtime nature and demand-driven actor instances](0024-node-runtime-nature.md) |
| 2026-08-27 | 0027 | Accepted | [Live request/reply ingress](0027-live-request-reply-ingress.md) |
| 2026-08-29 | 0028 | Accepted | [Iteration-correlated fan-in](0028-iteration-correlated-fan-in.md) |
| 2026-08-29 | 0029 | Accepted | [The model-provider SPI after AI nodes leave the core](0029-model-provider-spi-after-externalization.md) |
| 2026-08-30 | 0030 | Accepted contract, not implemented | [Zero or more independently routed node emissions](0030-zero-or-more-node-emissions.md) |
| 2026-09-03 | 0031 | Accepted | [Durable canonical graph definitions for accepted executions](0031-durable-canonical-graph-definitions.md) |

## Status vocabulary

- **Accepted** means the architecture contract was approved. It does not by itself claim that every
  part is implemented.
- **Superseded in part** means the unsuperseded portions remain authoritative and the record names
  the newer decisions that replaced the rest.
- **Superseded** means the record is retained for material history; newer linked records govern.
