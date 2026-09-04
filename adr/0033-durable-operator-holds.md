# ADR 0033: Durable operator holds on traversals

- Status: Accepted
- Date: 2026-09-04
- Supersedes: A hold on a traversal living only in the process running it, with a restart forgetting it and leaving no state an operator could decide from
- Superseded by: None
- Public references: [Persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md), [Execution events](../docs/reference/execution-events.md), [Durable process inventory](../docs/architecture/process-inventory.md), [ADR 0007](0007-process-traversal-invocation-attempt-lifecycle.md), [ADR 0022](0022-ambiguous-work-is-parked.md), [ADR 0031](0031-durable-canonical-graph-definitions.md), [ADR 0032](0032-durable-process-inventory-is-authoritative-rows.md)

## Context

An operator could pause a traversal, and the hold lived only in the process running it. A restart
forgot it outright and left nothing an operator could decide from: the traversal was neither
recorded as held nor recorded as anything else deliberate, so after a restart it was
indistinguishable from work interrupted by the crash. The two call for opposite responses, and the
system could not tell them apart.

Making the hold durable is not simply a matter of writing a row. Three questions had to be answered
together, and answering any one of them wrongly makes the feature worse than its absence.

**What a hold *is*, in the durable lifecycle.** ADR 0007 defines the lifecycle levels and their
statuses. A hold is either a new member of that vocabulary or it is not.

**Where a hold can be committed.** A traversal can be held between any two node dispatches, but not
every such point can be described well enough to continue from in a different process. The runtime
holds state — fan-in correlation, iteration laps, sibling branches, arbitrary in-flight payloads —
that is not durable anywhere in this system, and ADR 0022's `JoinRecord` already settled that a
branch payload comes back with its redelivery rather than out of a store.

**What a restart is allowed to do with one.** A hold exists because somebody decided the work should
stop. Recovery resuming it would be the system overriding that decision, silently, at the worst
possible moment.

## Decision

**A hold is a durable record, not a lifecycle status.** The traversal moves to the existing
`WAITING` — the same value a durable handler registration and a tool-approval suspension already
write — and a first-class hold record beside the instance says which wait it is. Two independent
reasons keep it out of the status vocabulary, and neither depends on durability: `ProcessInstanceStatus`
is per process instance while a hold is per traversal, so an instance-level `PAUSED` could not say
which traversal is held; and a hold is orthogonal to what the traversal is doing, so a status value
would make a deliberate hold indistinguishable from a wait on a human task. The consequence for
[ADR 0032](0032-durable-process-inventory-is-authoritative-rows.md) is that its derived recovery
classification needed no new value and no change: a held instance reports `WAITING`, which already
outranks `INTERRUPTED`, so it is correctly excluded from the restart-recovery cohort.

**The record and the `WAITING` transitions commit as one batch, under the runtime's own fence.**
There is therefore no instant at which a traversal is held and nothing records it, or is recorded as
waiting with nothing able to release it. This is a declared store capability rather than an
assumption; an adapter that does not declare it cannot be asked to write a hold.

**The `WAITING` transition is the enforcement, not bookkeeping.** ADR 0007's aggregate refuses to add
an invocation to a traversal that is not `RUNNING`, and both adapters fold every batch through the
aggregate before writing. So from the commit onward no node of that traversal can be recorded as
started by *any* process — not only by the one that took the hold — until something transitions it
back. The in-process gate stops the next hop where the hold was taken; the aggregate stops every hop
everywhere else, including in the process that starts after a restart.

**A hold is committed only at a boundary whose continuation is durably expressible**, and the
boundary is the point between two dispatches, before the invocation and attempt identities are
minted. The node named by the record has therefore never run, which is what makes continuing from
the boundary incapable of repeating a completed effect — a property of where the boundary sits
rather than a promise made about the continuation. A boundary qualifies only when the traversal is a
single branch at a single completed node, is not entering a fan-in, carries no iteration lap, and
carries a payload the structured-payload type model of
[ADR 0015](0015-structured-payloads-and-versioned-error-contract.md) can represent. The stored
continuation is that payload and its attributes, the node, the command, the pinned graph version of
[ADR 0031](0031-durable-canonical-graph-definitions.md), and the completed invocation the hold sits
behind. Nothing else.

**A boundary that does not qualify keeps the earlier process-local hold** rather than being refused a
hold or given a record that would be wrong on resume. Writing a single-hop continuation for a
traversal that has more than one branch would silently discard the others on the restart the record
exists to survive, which is worse than not writing one. Which boundaries qualify is public
documentation, because it is a property an operator planning a restart has to be able to reason
about.

**Recovery leaves a held traversal held, by construction.** A hold produces no claimable work of any
kind: no scheduled attempt, no timer, no trigger. There is nothing for a recovery sweep to find, so
there is no dispatcher whose absence is load-bearing and no status filter that could be got wrong.
Only an explicit resume continues a traversal, authorized and audited on the same execution-control
decision that already governed pause and cancel — no second authorization vocabulary is introduced.
A resume settles the hold and returns the traversal to `RUNNING` in one batch, then dispatches the
withheld node into the held traversal, behind its real predecessor invocation.

**Stopping a process decides nothing.** A shutdown releases the runtime resources a held traversal
was occupying and leaves the hold untouched, with no actor recorded against it. Settling it would
make a process stopping indistinguishable from an operator giving the work up.

## Consequences

A traversal held before a restart is still reported as held afterwards, is still resumable and
cancellable, and continues from its committed boundary without repeating what had already completed.
An operator planning a rolling restart no longer has to drain or cancel held work to avoid losing
it — for holds taken at a qualifying boundary.

Two costs are accepted. The first is that the qualifying rule is visible: an operator who needs a
specific execution to be pausable across a restart has to know that its held section must be linear.
The alternative was a rule nobody could see, which fails in the unsafe direction. The second is that
runtime resources are released when the process stops rather than at the moment the hold commits: a
live held traversal keeps its actors and remains inspectable, which is the guarantee the earlier
process-local hold made and this decision does not withdraw. The bound on that retention is the
process lifetime, and the bound on the durable state is the continuation's own size cap.

Settled holds are retained beside the process instance as the record of who decided what, and are
removed only when retention removes the instance, following the retention rule ADR 0032 already
sets for the rows they hang from.
