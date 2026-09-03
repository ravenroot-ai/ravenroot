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

## Verification

After recovery, prove `/ready`, inspect retained terminal results, resume an event cursor, and execute a bounded Test graph before reopening Run traffic. Also confirm that a process instance discoverable through the durable inventory before the restart is still discoverable afterward, and read each instance's reported disposition rather than only its lifecycle status: `PARKED` means an attempt's real-world effect outcome is still unresolved and awaits a human decision (see [Durable process inventory](../architecture/process-inventory.md)), and it can appear on an otherwise-finished instance, so do not treat a terminal status alone as "nothing left to do."

Two limits to keep in mind when reading the inventory. First, there is no durable "paused" state today: an instance an operator paused through process-local runtime control is not reported as paused by the durable inventory, because pause and resume have not yet been made durable. Second, a traversal admitted through the live request/reply ingress contract is deliberately live and process-local and writes nothing to the execution store, so the inventory is not a complete log of every traversal a deployment has ever admitted — it is complete for the admission paths that do write to the store.

- [Contract](../architecture/durability-events.md)
- [Durable process inventory](../architecture/process-inventory.md)
- [Decision record](../../adr/0031-durable-canonical-graph-definitions.md)
- [Runbook](../troubleshooting/embed-backup.md)
- [Bundle format and commands](../reference/backup-recovery.md)
- [HTTP API and CLI](../reference/api-cli.md)
