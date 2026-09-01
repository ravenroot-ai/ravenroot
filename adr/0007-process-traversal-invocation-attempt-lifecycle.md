# ADR 0007: Process, traversal, invocation, and attempt lifecycle

- Status: Accepted
- Date: 2026-07-30
- Supersedes: Correlation by execution identifier and node identifier alone
- Superseded by: None
- Public references: [Execution and actor model](../docs/architecture/execution-actor-model.md), [execution events](../docs/reference/execution-events.md)

## Context

An execution identifier and logical node identifier cannot distinguish repeated visits, retries,
fan-in arrivals, or work that waits and resumes. Durable state and recovery require identities whose
scope and lifecycle are explicit before any storage adapter is selected.

## Decision

Ravenroot distinguishes a process instance, a traversal through its graph, a logical node
invocation within that traversal, and attempts to perform that invocation. Each level has a stable
identifier and a defined parent. Retries create new attempts without changing invocation identity;
re-entering a node creates a distinct invocation. Events and stored transitions carry the identity
needed to correlate them without reconstructing causality from timestamps.

## Consequences

- Repeated graph visits and delivery retries are distinguishable.
- Persistence adapters can enforce idempotency and recovery against explicit units of work.
- Later fan-in and crash-recovery decisions can extend correlation without changing what an attempt
  means.
