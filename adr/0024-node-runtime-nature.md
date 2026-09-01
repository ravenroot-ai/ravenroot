# ADR 0024: Node runtime nature and demand-driven actor instances

- Status: Accepted
- Date: 2026-08-13
- Supersedes: The eager actor-per-graph-node residency described by ADR 0021
- Superseded by: None
- Public references: [Execution and actor model](../docs/architecture/execution-actor-model.md), [node catalog, payloads, and limits](../docs/reference/nodes-payload-limits.md), [ADR 0021](0021-deployment-runtime-ownership.md)

## Context

A logical graph node does not always require a continuously resident actor. Eagerly spawning one
actor for every node makes deployment cost proportional to graph size even when most nodes are idle,
while some sources or keyed authorities genuinely need durable residency.

## Decision

The trusted catalog declares each node's runtime nature. `WORKER` instances are demand-created per
invocation. `TRAVERSAL` instances are demand-created for a logical node within one traversal.
`SOURCE` nodes own deployment-lifetime inbound resources and resident dispatch. `AUTHORITY` and
`KEYED` require fenced residency semantics and fail closed until a conforming implementation exists.

Admission limits are catalog-governed and independent of nature. Topological fan-out to several
successors is distinct from data-parallel expansion into child invocations. Runtime nature is
derived from trusted catalog data rather than materialized as an author-controlled GraphML claim.

## Consequences

- Idle transformation nodes do not consume resident actors merely because they appear in a graph.
- Source and authority lifecycles remain explicit deployment responsibilities.
- Unsupported residency semantics are rejected instead of approximated with unsafe worker behavior.
- ADR 0021's deployment domain remains in force while its eager residency model does not.
