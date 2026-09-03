# Persistence, lifecycle, and recovery

Protect accepted executions and audit evidence across drain, backup, restart, and upgrade.

## Operator procedure

1. Monitor liveness separately from readiness and stop routing new work as soon as readiness falls.
2. Call `POST /v1/drain`, wait for the live-execution set to reach the approved boundary, and stop the process cleanly.
3. Take a storage-consistent backup only after the write boundary is established; record release and schema identity with it.
4. Restore into an isolated target, start against the restored state, and reconcile executions and recent events before promotion.

## Graph definitions

Accepting an execution durably stores the exact canonical GraphML document it will run, before the execution is recorded, and binds the execution to that document's content address. Acceptance is refused if the document cannot be stored, so an accepted execution is always one whose graph can be produced again.

After a restart or a transfer of ownership the stored definition is authoritative. A running process may cache the document while it holds the execution, but a recovering process reads it from durable storage, verifies it against its digest, and refuses a definition that is missing, altered, or filed under another tenant. Nothing asks the caller to resubmit the document.

Definitions are scoped to one tenant and are shared between executions of that tenant only when the documents are byte-identical. Reclaiming unreferenced definitions is an operator action, not a background sweep, and it cannot remove a definition that a retained execution still names. Definitions hold graph content and non-secret references only; credentials are supplied at execution time and never stored with a definition.

## Authority

Only an operator may drain, copy or replace durable state, restore a deployment, or approve an upgrade. API consumers observe these transitions but do not perform storage mutation.

## Verification

After recovery, prove `/ready`, inspect retained terminal results, resume an event cursor, confirm that a retained execution's graph definition is still readable from the restored store, and execute a bounded Test graph before reopening Run traffic.

- [Contract](../architecture/durability-events.md)
- [Decision record](../../adr/0031-durable-canonical-graph-definitions.md)
- [Runbook](../troubleshooting/embed-backup.md)
- [Bundle format and commands](../reference/backup-recovery.md)
