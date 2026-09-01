# ADR 0021: Deployment runtime ownership

- Status: Accepted, partially superseded
- Date: 2026-08-11
- Supersedes: Treating every graph execution as an unrelated runtime without deployment ownership
- Superseded by: [ADR 0023](0023-remote-deployment-control-plane.md) for remote authority and reconciliation; [ADR 0024](0024-node-runtime-nature.md) for eager per-node actor residency
- Public references: [Deployment and startup](../docs/operator-guide/deployment-startup.md), [execution and actor model](../docs/architecture/execution-actor-model.md), [execution events](../docs/reference/execution-events.md)

## Context

Long-lived deployed graphs need explicit ownership of runtime resources, admission, observability,
and shutdown. Creating unrelated actor systems or unbounded resident actors for graph submissions
would make capacity and failure domains accidental.

## Decision

A server process owns one clustered actor system per pod. Each deployed graph version has a
segregated execution and supervision domain inside it, with explicit deployment identity, admission,
capability checks, observability, and acknowledgement boundaries. Capacity is an operator-visible
deployment constraint rather than an unbounded consequence of graph size.

The original record's eager actor-per-graph-node model is superseded by ADR 0024. Its open remote
authority, poison-event, and durable-registry questions are superseded by ADR 0023. The actor-system,
deployment-domain, capability, admission, and observability boundaries remain accepted.

The implemented Phase A is process-local and single-pod: one server process owns deployment domains,
admission, lifecycle, and observability inside that process. Phase B is not implemented. Cross-pod
actor clustering, workload sharding, and durable acknowledgement/reconciliation remain blocked on
their distributed authority and storage contracts; the words “clustered” and “sharding” in the
target architecture are not claims about the shipped runtime.

## Consequences

- A deployment owns and can drain its runtime resources as one unit.
- Failure and capacity accounting are scoped to a deployment rather than to the whole process.
- Phase A provides single-pod deployment domains; Phase B cross-pod clustering, sharding, and
  durable acknowledgement are unimplemented.
- Distributed placement is not automatic; later contracts govern remote reconciliation and the
  residency required by each node nature.
