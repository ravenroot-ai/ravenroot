# ADR 0035: Cancellation as a distinct execution termination reason

- Status: Accepted
- Date: 2026-09-05
- Supersedes: A cancelled execution recorded and published as an ordinary failure, with no durable or
  observable signal distinguishing the two
- Superseded by: None
- Public references: [Executions, outcomes, and events](../docs/reference/execution-events.md),
  [HTTP API and CLI](../docs/reference/api-cli.md),
  [Durability, events, and recovery](../docs/architecture/durability-events.md),
  [Execution and actor model](../docs/architecture/execution-actor-model.md),
  [Persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md),
  [ADR 0007](0007-process-traversal-invocation-attempt-lifecycle.md),
  [ADR 0012](0012-engine-supervision-cancellation-and-drain.md),
  [ADR 0022](0022-ambiguous-work-is-parked.md)

## Context

`ProcessInstanceStatus` and `TraversalStatus` have exactly two terminal values, `COMPLETED` and
`FAILED`. A cancelled execution has always been recorded as `FAILED`, both durably and on the event
stream, because "stopped on request" is not a completion. The only signal that ever distinguished a
cancellation from a genuine fault was a Java exception class name leaked into one event field, which
no durable read carried and no other projection repeated. After a restart, or from the durable
inventory, from `GET /v1/executions/inventory`, from the CLI, or from any dashboard reading
`ravenroot.execution.events`, an operator's deliberate stop was indistinguishable from an incident,
and every cancellation raised the apparent failure rate.

## Decision

**A nullable `ExecutionTerminationReason` is carried beside the unchanged terminal status, and
`CANCELLED` is its first value.** `ExecutionOutcome`, `ProcessInstance`, `Traversal`, the process and
traversal transitions, `ExecutionLookup.Expired`, and the SQLite `process_instance` and `traversal`
tables each gain the reason as an additive component, behind a compatibility constructor that
preserves the previous shape. A cancelled execution therefore still reports `status == FAILED`
everywhere it always did, and gains `terminationReason == CANCELLED` beside it. Absence of a reason
means only "nothing distinguishes this termination" and covers an ordinary failure and a row written
before the column existed identically — both are truthfully "not a cancellation," and conflating them
is safe because neither is one. One classifier, `ExecutionTermination.reasonOf`, walks the failure's
cause chain once and is shared by the runner's terminal handlers, the durable pause service, the
result registry, and the request/reply coordinator, so the durable aggregate and every read path
reach the same conclusion about the same run.

**The rejected alternative was a `CANCELLED` member of the two status enums, and it was rejected on
compatibility, not on taste.** A status is persisted by name. The first row carrying a new name is
unreadable to any binary that predates it, which is a forward-only enlargement: a rollback past that
row fails loudly. [ADR 0022](0022-ambiguous-work-is-parked.md) accepted exactly that one-way gate once,
for `NodeAttemptStatus#PARKED`, because the alternative there was silently forging an outcome nobody
observed — a price worth paying once, not a precedent for paying it again on the two busiest tables in
the schema. A nullable column beside an unchanged status is compatible in both directions: an older
binary never selects a column it does not know about, sees the `FAILED` it has always seen, and keeps
working; a downgrade after this change stays safe permanently, which the SQLite migration's own
schema note calls out as the reason for the shape rather than a side effect. The same argument
[ADR 0022](0022-ambiguous-work-is-parked.md) makes for `OPERATOR_VERIFIED` justifies giving
cancellation its own value rather than reusing an existing one: nothing about a cancelled traversal
failed, and counting it as a fault fabricates an incident that gets paged on and reported as
availability loss.

**Cancellation also publishes its own terminal event type, `EXECUTION_CANCELLED`, in place of
`EXECUTION_FAILED`.** `ravenroot.execution.events` and every consumer built against it are labelled
by event type alone, so while cancellation published `EXECUTION_FAILED`, an operator stop was counted
as a failure by construction; qualifying the durable record without changing what the event stream
publishes would have left that specific defect in place under a different name. `EXECUTION_EVENT_TYPE
.isTraversalTerminal()` is now the single, exhaustive classification of "does a traversal end here" —
a switch expression with no default over exactly `EXECUTION_COMPLETED`, `EXECUTION_FAILED`, and
`EXECUTION_CANCELLED` — and every consumer that previously hardcoded its own two-member terminal list
(admission accounting, the OpenTelemetry span bridge, the audit trail) now defers to it or to an
equally exhaustive classifier of its own, so a future terminal type is a compile error at the one
place that must know rather than a silent gap in three.

## Consequences

- **An out-of-tree event consumer that recognizes only `EXECUTION_COMPLETED` and `EXECUTION_FAILED`
  stops seeing a terminal event at all for a cancelled traversal**, until it is taught the new type.
  This is the direct, stated cost of giving cancellation its own event type, accepted because the
  alternative — publishing `EXECUTION_FAILED` for a cancellation — is the defect this decision exists
  to remove.
- **A reader must consult the reason beside the status, never the status alone.** Every type that
  carries both documents this in its own Javadoc, and every projection — the REST `200` and `410`
  bodies, the durable process and traversal inventories, both CLI transports, the OpenTelemetry span
  status, the audit trail's own action, and the workspace UI's outcome panel — carries the reason
  wherever it carries the status, live and after a restart.
- **The vocabulary grows only for a termination whose provenance a reader would otherwise get wrong.**
  A reader that switches over `ExecutionTerminationReason` must tolerate a value it does not know and
  treat it as "not a cancellation," and a durable reason name a build does not recognize replays as
  corrupted rather than as silently absent, so an unrecognized value is never misread as an ordinary
  termination.
- **Cancellation does not preempt a node computation already in flight.** It refuses the next hop —
  the dispatch gate every hop passes through before an invocation is minted — and releases whichever
  wait the traversal was holding, a paused dispatch gate or a retry backoff. Effects issued before the
  cancellation was observed are not undone and cannot be; this was already the supervision model ADR
  0012 describes, and this decision changes how the outcome is recorded, not how promptly it takes
  effect.
