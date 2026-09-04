# Persistence, lifecycle, and recovery

Protect accepted executions and audit evidence across drain, backup, restart, and upgrade.

## Operator procedure

1. Monitor liveness separately from readiness and stop routing new work as soon as readiness falls.
2. Call `POST /v1/drain`, wait for the live-execution set to reach the approved boundary, and stop the process cleanly.
3. Take a storage-consistent backup only after the write boundary is established; record release and schema identity with it.
4. Restore into an isolated target, start against the restored state, and reconcile executions and recent events before promotion.
5. After restart or restore, the durable process inventory (`GET /v1/executions/inventory`, or `ravenroot inventory`) is queryable immediately, with no rebuild delay: it is read from the same rows the lifecycle committed, not from a projection that has to catch up. Use it, not the process-local live-execution view, to find work that outlived the restart. `ravenroot inventory` reads the tenant's whole answer in one call — it pages through the HTTP route internally rather than returning a first page, so its output is never a truncated view of a large tenant.
6. When an instance you expect to find is absent from `GET /v1/executions/inventory` or `ravenroot inventory`, do not compare against its creation time — the `retainedFrom` floor every inventory listing carries (both process-instance and per-instance traversal listings, and the trailing `retained-from=` line the CLI prints) is measured in retention-deadline space, not creation space, and the boundary is exclusive: a row whose own deadline (`retainedUntil`, or its terminal-transition instant plus the configured terminal retention if you never read the row) sits strictly after the floor is guaranteed still present, while a deadline at or before the floor may have been purged. Compare the instance's deadline against the floor, not when it was created, before concluding the identifier is wrong. In this release that comparison will read as "still present" for every terminal instance regardless of age: see Authority below for why.

## Graph definitions

Accepting an execution durably stores the exact canonical GraphML document it will run, before the execution is recorded, and binds the execution to that document's content address. Acceptance is refused if the document cannot be stored. An accepted execution is therefore always one whose graph is retained, and the retained document — not a copy held by whichever process accepted it — is the authoritative record of what that execution was accepted to run.

Definitions are stored with execution state, so they are inside the same backup and come back with the same restore. They are scoped to one tenant, and two executions share one stored document only when the documents are byte-identical and belong to the same tenant. Definitions hold graph content and non-secret references only; credentials are supplied at execution time and are never stored with a definition.

Storing the document is what this release adds. **Ravenroot does not yet read it back to resume work**: no runtime reconstructs a graph from a stored definition, and reclaiming stored definitions is not yet an exposed operator command. Both arrive in a following change. Until then, treat these definitions as retained evidence that an accepted execution's graph is recoverable, not as a recovery procedure you can run today.

## Authority

Only an operator may drain, copy or replace durable state, restore a deployment, or approve an upgrade. API consumers observe these transitions but do not perform storage mutation.

**No shipped surface removes expired terminal rows from the durable inventory in this release.** The retention operation exists at the store level — nothing is ever deleted implicitly by a listing or a lookup, only terminal instances are ever eligible, and running it advances the retention floor for the tenant it was run against and no other — but there is no CLI verb, no HTTP route, and no scheduler that calls it. It is reachable today only by an embedder composing its own execution store directly. This is a deliberate scoping decision, not an oversight: a verb that permanently deletes terminal execution records is destructive and needs its own confirmation posture, and it was left out of this change rather than added late. Until a future change exposes it to an operator, the retention floor stays at its minimum in every real deployment and every terminal row is retained regardless of age. That minimum is not hidden as `null`: every `retainedFrom` field and the CLI's `retained-from=` line serialise it like any other instant, so what you will actually see today is the literal `-1000000000-01-01T00:00:00Z`. Read that value as "nothing has ever been forgotten," not as a malformed timestamp — collapsing it to `null` would erase the one distinction the field exists to make, between "nothing purged yet" and "unknown."

Terminal-retention configuration cannot be set shorter than event-journal retention, so once retention removal is exposed, a terminal instance will never be pruned while its own events are still readable. The default terminal retention is seven days, chosen to span a weekend so a failure late on a Friday is still discoverable when someone looks on Monday — a bound that constrains configuration today but removes nothing until the operation above is reachable.

## Paused traversals

A traversal an operator pauses is held, and a hold now survives the process that took it. The hold is written down at the moment the traversal is actually stopped — between the node that has just finished and the node that has not yet started — together with the small amount of state needed to continue it: the node it was about to enter, the payload and attributes that dispatch was carrying, and the pinned graph version to run them against. It commits in the same transaction that moves the traversal to `WAITING`, so there is no instant at which a traversal is held and nothing records it, or is recorded as waiting with nothing able to release it.

**Recovery leaves a held traversal held, and cannot do otherwise.** A hold produces no claimable work of any kind: no scheduled attempt, no timer, no trigger. A recovery sweep across a restarted process finds nothing belonging to a held traversal and therefore dispatches nothing for it. Nothing after the hold's boundary can run in any process either, because the aggregate refuses to record a node as started on a traversal that is `WAITING` — so a held traversal is stopped by the stored state itself, not only by the process that was running it.

**Only an explicit, authorized resume continues one.** Resume and cancel are authorized and audited exactly as they were before, on the same `EXECUTION_CONTROL` decision, and both work after a restart. A resume rebuilds what it needs from the pinned graph and continues from the committed boundary: it starts the node the hold withheld, which has never run, so nothing already completed is repeated. A cancel settles the hold and ends the traversal. Whichever happens, the settled hold is retained beside the process instance as the record of who decided and what they decided, and is removed only when retention removes the instance itself.

**Stopping a deployment or a process decides nothing.** A shutdown releases the runtime resources a held traversal was occupying and leaves the hold exactly as it was, with no actor recorded against it. The next process to start reports the traversal as held, and the same resume and cancel remain available.

### Timed joins while a traversal is held

A fan-in can carry a timeout — the `joinTimeout` property on the fan-in node — and that timeout measures **active execution time**: the interval a traversal spends held by an operator is excluded from it. Taking a hold stops the deadline and records what was left of its budget; releasing the hold gives the join exactly that remainder and nothing more. A traversal held for an hour with twelve seconds left on a thirty-second `joinTimeout` resumes with eighteen seconds, not with thirty and not with none. So pausing an execution can no longer fail the work it was pausing, which is what a hold longer than the remaining budget used to do.

A hold decides nothing, and it does not create budget either. If the deadline had already run out at the moment the hold was taken, the remainder is zero and the join times out the instant it resumes rather than at some point during the hold. That is the same rule, not an exception to it: the join is given exactly what was left, and what was left was nothing.

The stopping is real rather than bookkeeping. While a traversal is held, no join of it is waiting on a deadline: the scheduled task is cancelled, and one the runtime's scheduler declines to cancel is refused when it fires instead, so a held traversal cannot be timed out either way. A branch that reaches a fan-in during a hold is recorded as arrived and its bucket is opened, but its deadline is only recorded and not started, so it too begins counting at the resume. A join that is satisfied, or that proves its quorum unreachable, while the traversal is held keeps that outcome; the resume does not give a settled join a second deadline.

What a hold does not do is survive on its own. **While a join's deadline is running, its traversal is not one a hold can be written down for.** A hold is committed at the boundary between two nodes — after one has finished and before the next has started — and a branch entering a fan-in never reaches that boundary, because it is handed to the join instead of being started as a node. The branch a timed join is waiting on is therefore never the branch a durable hold records. A restart consequently has no stored hold to reconcile against a remaining budget: it drops the process-local hold and the in-memory deadline together, along with the traversal itself. If you need a specific execution to be pausable across a restart, keep its held section linear, exactly as described below.

### What is held durably, and what is held only in the process

Not every point a traversal can be paused at is one a hold can be written down for. A hold is written down when the traversal is a single branch at a single completed node and the withheld payload is expressible in the payload type model. It is **not** written down when the traversal has fanned out at any point, when the hold lands on the traversal's very first node, when a loop is in progress, when the hold lands on a fan-in, or when the withheld payload is a value the type model does not cover — a continuation carries one hop, and writing one for a traversal that has more than one would silently discard the others on restart.

Those holds still work; they are simply the process-local holds that existed before this change, and a restart forgets them. The distinction is visible where it matters: after a restart, a traversal that was held durably is reported as paused and a traversal that was not is not reported at all, because the process running it is gone. If it matters to you that a specific execution can be paused across a restart, keep its held section linear.

### Stores that predate this state

Durable holds are a declared store capability, not an assumption. A store that does not declare it cannot be asked to write a hold, and pausing a traversal against one keeps the process-local behaviour described above, unchanged and without error.

For the bundled SQLite store this is a schema addition and nothing else. A database file written by an earlier release upgrades in place by adding one table for holds; no existing table is altered, no row is rewritten, and no data is migrated. Existing traversals are unaffected — a traversal in flight across the upgrade has no hold, and holds are only ever created by a pause issued after it. The usual downgrade rule applies unchanged: a file upgraded by this release is refused by a build that predates it, so take a backup before upgrading if you may need to roll the binary back.

## Verification

After recovery, prove `/ready`, inspect retained terminal results, resume an event cursor, and execute a bounded Test graph before reopening Run traffic. Also confirm that a process instance discoverable through the durable inventory before the restart is still discoverable afterward, and read each instance's reported disposition rather than only its lifecycle status: `PARKED` means an attempt's real-world effect outcome is still unresolved and awaits a human decision (see [Durable process inventory](../architecture/process-inventory.md)), and it can appear on an otherwise-finished instance, so do not treat a terminal status alone as "nothing left to do."

One limit to keep in mind when reading the inventory: a traversal admitted through the live request/reply ingress contract is deliberately live and process-local and writes nothing to the execution store, so the inventory is not a complete log of every traversal a deployment has ever admitted — it is complete for the admission paths that do write to the store.

A held traversal reads as `WAITING`, like any other durable wait, and is therefore not part of the interrupted cohort a restart needs to act on. Which wait it is comes from the hold itself, described under [Paused traversals](#paused-traversals) above.

- [Contract](../architecture/durability-events.md)
- [Durable process inventory](../architecture/process-inventory.md)
- [Decision record](../../adr/0031-durable-canonical-graph-definitions.md)
- [Runbook](../troubleshooting/embed-backup.md)
- [Bundle format and commands](../reference/backup-recovery.md)
- [HTTP API and CLI](../reference/api-cli.md)
