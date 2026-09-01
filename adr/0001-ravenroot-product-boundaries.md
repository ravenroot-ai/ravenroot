# ADR 0001: Ravenroot product boundaries

- Status: Superseded
- Date: 2026-07-20
- Supersedes: None
- Superseded by: [ADR 0007](0007-process-traversal-invocation-attempt-lifecycle.md), [ADR 0008](0008-graph-definition-versioning-and-lifecycle.md), [ADR 0009](0009-read-only-graph-traversal.md), and [ADR 0024](0024-node-runtime-nature.md)
- Public references: [Product boundaries](../docs/architecture/product-boundaries.md), [GraphML profile](../docs/reference/graphml.md)

## Context

The original code combined graph semantics, runtime-framework types, process startup, HTTP,
vertical integrations, and demonstrations. That made the engine difficult to embed and made product
boundaries depend on one actor implementation.

## Decision

Ravenroot is one product composed of a framework-neutral application API, graph core, execution
engine adapters, standalone server, CLI, UI, and distribution. The sample remains a separate
embedding example. The core owns graph semantics and preserves arbitrary vertex and edge properties;
GraphML remains an official exchange format and Gremlin remains the traversal technology.

One authoritative graph manager belongs to an engine or graph space rather than to the JVM. Runtime
frameworks and external systems are reached through injected ports or adapters, not imported into
the domain. Embedded and standalone modes use the same application contract and graph semantics.

## Consequences

- Product modules can evolve independently without making transport or actor types part of the core.
- Graph import, editing, execution, and export must not silently discard unknown properties.
- Later ADRs refine graph identity, read-only traversal, execution identity, and demand-driven node
  lifecycles. Those records govern where this historical decision conflicts with them.
