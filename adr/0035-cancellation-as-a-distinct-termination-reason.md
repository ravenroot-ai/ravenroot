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

**The rejected alternative was a `CANCELLED` member of the two status enums. The reason to prefer the
chosen shape does not lie on the rollback axis** — an earlier version of this record claimed
otherwise, and that claim was false. Carrying the reason in storage still requires a schema
migration, and the SQLite store refuses to open any database whose recorded schema version exceeds
what the running binary understands, before it reads a single row. Every migration in this schema
raises that version, this one included, so a binary that predates this change is refused at open,
unconditionally and immediately, whether or not anything was ever cancelled. A `CANCELLED` status
member would have needed no migration at all — statuses are persisted by name — so it would not have
raised the schema version, and an older binary would have kept opening the file, failing only on the
first row that actually carried the new name, as `Corrupted`. **On the rollback axis the shape chosen
here is the stricter of the two designs, not the freer one, and that is recorded as a cost of it
rather than a benefit.**

The actual reason to prefer it is the type and wire contract. `ProcessInstanceStatus` and
`TraversalStatus` are a lifecycle state machine, not merely persisted tokens: `canTransitionTo` and
`terminal()` are built on their membership, and every exhaustive switch over them — here and
downstream — would need a new arm for a value it has never seen. `RequestReplyOutcome` asserts that a
failed waiter state carries a failed process status; a `CANCELLED` status would have turned every
cancellation observed by a request/reply waiter into a construction failure at that boundary, a
concrete existing invariant with no migration guard able to mediate it. The non-durable in-memory
store has no schema version and therefore no rollback gate under either shape, so the comparison
above is specific to the durable SQLite store. The issue this decision answers also required an
additive schema change and explicitly forbade changing a persisted status value, which independently
rules out the alternative regardless of the trade-off above. The one-way schema gate the column now
creates is the same cost this store already charges for every other durable change; what it buys back
is the type safety and the invariant just described, and that is the whole justification.
[ADR 0022](0022-ambiguous-work-is-parked.md) remains the precedent for giving cancellation its own
value rather than reusing an existing one — nothing about a cancelled traversal failed, and counting
it as a fault fabricates an incident that gets paged on and reported as availability loss — but it is
not, and was never, a precedent for a compatibility claim about rollback; that argument stands or
falls on the paragraph above alone.

**Cancellation also publishes its own terminal event type, `EXECUTION_CANCELLED`, in place of
`EXECUTION_FAILED`.** `ravenroot.execution.events` and every consumer built against it are labelled
by event type alone, so while cancellation published `EXECUTION_FAILED`, an operator stop was counted
as a failure by construction; qualifying the durable record without changing what the event stream
publishes would have left that specific defect in place under a different name. `ExecutionEventType
.isTraversalTerminal()` is now the single, exhaustive classification of "does a traversal end here" —
a switch expression with no default over exactly `EXECUTION_COMPLETED`, `EXECUTION_FAILED`, and
`EXECUTION_CANCELLED` — and every consumer that previously hardcoded its own two-member terminal list
(admission accounting, the OpenTelemetry span bridge, the audit trail) now defers to it or to an
equally exhaustive classifier of its own, so a future terminal type is a compile error at the one
place that must know rather than a silent gap in three.

## Consequences

- **A binary that predates this migration cannot open an upgraded SQLite database at all**, from the
  moment the migration runs, regardless of whether any execution was ever cancelled. This is a total,
  immediate, data-independent gate, not a partial one triggered by the first cancelled row — the
  rejected status-enum alternative would have been strictly cheaper to roll back, staying openable
  until the first row actually carrying the new name. Take a backup before upgrading if rolling back
  to a binary predating this change is a live possibility.
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
