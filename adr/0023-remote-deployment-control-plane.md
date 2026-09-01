# ADR 0023: Remote deployment control plane

- Status: Accepted contract; implementation is not implied
- Date: 2026-08-12
- Supersedes: Open remote-authority, poison-event, and durable-registry choices in ADR 0021
- Superseded by: None
- Public references: [Deployment and startup](../docs/operator-guide/deployment-startup.md), [configuration reference](../docs/reference/configuration.md), [ADR 0021](0021-deployment-runtime-ownership.md)

## Context

A process-local deployment can own an engine directly, but a remote control plane must survive
restarts, concurrent reconcilers, failed acknowledgements, and events that cannot be applied. The
contract must not bind the product to one actor runtime or one storage implementation.

## Decision

Desired deployment state is durable and reconciled by a fenced authority. A reconciler acquires
time-bounded ownership, compares desired and observed state, performs idempotent transitions, and
records acknowledgements before advancing durable state. Registry identity, monotonic revision, and
fencing prevent a stale owner from committing after authority moves.

Unprocessable events enter a durable, replayable dead-letter queue. A record retains deployment and
event identity, ordering and revision history, a redacted failure classification, and either only
the payload necessary to replay the operation or a protected reference to that payload. Retention is
explicit and bounded. Records and replay access are tenant-scoped; retained sensitive payload is
encrypted at rest and diagnostic views are redacted. Reading, replaying, discarding, or quarantining
a record is an audited disposition.

Replay requires an authorized principal and re-runs current admission, authorization, and policy
checks; the original acceptance is not reusable authority. Runtime construction remains behind the
execution and deployment SPIs; the durable registry and dead-letter storage remain behind ports.

## Consequences

- Control-plane recovery is reconciliation from durable intent, not replay of in-memory commands.
- Multiple reconcilers may exist, but only the current fenced owner may advance one deployment.
- Dead-letter retention and replay preserve enough protected state to reproduce work without turning
  metadata into a false substitute for payload; every disposition is tenant-scoped and audited.
- This accepted target contract does not claim that clustered authority, durable dead letters, or
  every remote adapter is already implemented.
