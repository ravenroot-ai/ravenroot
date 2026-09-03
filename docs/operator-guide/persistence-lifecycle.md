# Persistence, lifecycle, and recovery

Protect accepted executions and audit evidence across drain, backup, restart, and upgrade.

## Operator procedure

1. Monitor liveness separately from readiness and stop routing new work as soon as readiness falls.
2. Call `POST /v1/drain`, wait for the live-execution set to reach the approved boundary, and stop the process cleanly.
3. Take a storage-consistent backup only after the write boundary is established; record release and schema identity with it.
4. Restore into an isolated target, start against the restored state, and reconcile executions and recent events before promotion.

## Graph definitions

Accepting an execution durably stores the exact canonical GraphML document it will run, before the execution is recorded, and binds the execution to that document's content address. Acceptance is refused if the document cannot be stored. An accepted execution is therefore always one whose graph is retained, and the retained document — not a copy held by whichever process accepted it — is the authoritative record of what that execution was accepted to run.

Definitions are stored with execution state, so they are inside the same backup and come back with the same restore. They are scoped to one tenant, and two executions share one stored document only when the documents are byte-identical and belong to the same tenant. Definitions hold graph content and non-secret references only; credentials are supplied at execution time and are never stored with a definition.

Storing the document is what this release adds. **Ravenroot does not yet read it back to resume work**: no runtime reconstructs a graph from a stored definition, and reclaiming stored definitions is not yet an exposed operator command. Both arrive in a following change. Until then, treat these definitions as retained evidence that an accepted execution's graph is recoverable, not as a recovery procedure you can run today.

## Authority

Only an operator may drain, copy or replace durable state, restore a deployment, or approve an upgrade. API consumers observe these transitions but do not perform storage mutation.

## Verification

After recovery, prove `/ready`, inspect retained terminal results, resume an event cursor, and execute a bounded Test graph before reopening Run traffic.

- [Contract](../architecture/durability-events.md)
- [Decision record](../../adr/0031-durable-canonical-graph-definitions.md)
- [Runbook](../troubleshooting/embed-backup.md)
- [Bundle format and commands](../reference/backup-recovery.md)
