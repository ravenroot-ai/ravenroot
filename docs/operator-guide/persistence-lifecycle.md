# Persistence, lifecycle, and recovery

Protect accepted executions and audit evidence across drain, backup, restart, and upgrade.

## Operator procedure

1. Monitor liveness separately from readiness and stop routing new work as soon as readiness falls.
2. Call `POST /v1/drain`, wait for the live-execution set to reach the approved boundary, and stop the process cleanly.
3. Take a storage-consistent backup only after the write boundary is established; record release and schema identity with it.
4. Restore into an isolated target, start against the restored state, and reconcile executions and recent events before promotion.
5. After restart or restore, the durable process inventory (`GET /v1/executions/inventory`, or `ravenroot inventory`) is queryable immediately, with no rebuild delay: it is read from the same rows the lifecycle committed, not from a projection that has to catch up. Use it, not the process-local live-execution view, to find work that outlived the restart.
6. When an instance you expect to find is absent, compare its expected creation time against the inventory's `retainedFrom` floor in the same response before concluding the identifier is wrong: an absence at or after the floor means no such instance exists or it is not visible to your tenant; an absence before the floor means a terminal instance aged out of the configured terminal-retention window.

## Graph definitions

Accepting an execution durably stores the exact canonical GraphML document it will run, before the execution is recorded, and binds the execution to that document's content address. Acceptance is refused if the document cannot be stored. An accepted execution is therefore always one whose graph is retained, and the retained document — not a copy held by whichever process accepted it — is the authoritative record of what that execution was accepted to run.

Definitions are stored with execution state, so they are inside the same backup and come back with the same restore. They are scoped to one tenant, and two executions share one stored document only when the documents are byte-identical and belong to the same tenant. Definitions hold graph content and non-secret references only; credentials are supplied at execution time and are never stored with a definition.

Storing the document is what this release adds. **Ravenroot does not yet read it back to resume work**: no runtime reconstructs a graph from a stored definition, and reclaiming stored definitions is not yet an exposed operator command. Both arrive in a following change. Until then, treat these definitions as retained evidence that an accepted execution's graph is recoverable, not as a recovery procedure you can run today.

## Authority

Only an operator may drain, copy or replace durable state, restore a deployment, or approve an upgrade. API consumers observe these transitions but do not perform storage mutation. Removing expired terminal rows from the durable inventory follows the same rule: nothing is deleted implicitly by a listing or a lookup, removal is an explicit operator or scheduled maintenance action, only terminal instances are ever eligible, and it advances the retention floor for the tenant it was run against and no other.

Terminal-retention configuration cannot be set shorter than event-journal retention, so a terminal instance is never pruned while its own events are still readable. The default terminal retention is seven days, chosen to span a weekend: a failure late on a Friday must still be discoverable when someone looks on Monday.

## Verification

After recovery, prove `/ready`, inspect retained terminal results, resume an event cursor, and execute a bounded Test graph before reopening Run traffic. Also confirm that a process instance discoverable through the durable inventory before the restart is still discoverable afterward, and read each instance's reported disposition rather than only its lifecycle status: `PARKED` means an attempt's real-world effect outcome is still unresolved and awaits a human decision (see [Durable process inventory](../architecture/process-inventory.md)), and it can appear on an otherwise-finished instance, so do not treat a terminal status alone as "nothing left to do."

Two limits to keep in mind when reading the inventory. First, there is no durable "paused" state today: an instance an operator paused through process-local runtime control is not reported as paused by the durable inventory, because pause and resume have not yet been made durable. Second, a traversal admitted through the live request/reply ingress contract is deliberately live and process-local and writes nothing to the execution store, so the inventory is not a complete log of every traversal a deployment has ever admitted — it is complete for the admission paths that do write to the store.

- [Contract](../architecture/durability-events.md)
- [Durable process inventory](../architecture/process-inventory.md)
- [Decision record](../../adr/0031-durable-canonical-graph-definitions.md)
- [Runbook](../troubleshooting/embed-backup.md)
- [Bundle format and commands](../reference/backup-recovery.md)
- [HTTP API and CLI](../reference/api-cli.md)
