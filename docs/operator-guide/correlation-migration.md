# Upgrade correlated deployment history

The deployment execution and history contract adds SQLite schema **41** after 40 and PostgreSQL
schema **21** after 20. Opening the supported store applies its forward migrations under the
existing migration transaction and lock. Take and verify a consistent backup before opening an old
store with the new binary. Do not edit schema version stamps or delete an initial-start marker.

## What changes

A deployment admits one initial START traversal. Completing, failing, pausing or restarting that
traversal does not make START available again. To execute another initial START, explicitly create
another deployment, even when its GraphML is identical. Deploy alone activates listeners without
executing START. First Run associates the originating document with its exact deployed graph and
reconciles the initial identities across response loss and durable host restoration.

SOURCE events enter at their owning source. An ordinary edge reaching a SOURCE ends that branch at
a source boundary. Review graphs that formerly depended on accidentally entering START for a source
event, or on traversing through a listener as an ordinary worker. Sibling branches continue normally.

New bounded records retain initial-start identity, application history, actual correlated traversal
relationships, SOURCE input custody, correlated joins and their continuation outbox, and scalar edge
coverage. Existing executions, pending attempts, timers, source checkpoints and idempotency records
remain in their original tables and identity domains. Migration does not infer a business identity
from an old process ID, request ID, payload, filename or transport property. Old traversals appear
as uncorrelated. Edge coverage for a traversal predating the projection is `NOT_RECORDED`; an empty
new table cannot establish zero historical edge traversals. Payload capture can independently be
disabled, expired or redacted.

An old source receipt does not contain the new retained entry intent. Migration cannot recreate
missing original input from that receipt. Existing recoverable attempts/timers remain claimable under
their current authority and repeatability rules; ambiguous effects remain under recovery or park
authority. New committed SOURCE receipts transfer custody only after retaining bounded immutable
input, original context and entry. Their recovery does not depend on activity capture or producer
redelivery. See [SOURCE custody](deployment-startup.md#source-receipt-custody-and-restart).

The AMQP adapter also preserves valid UTF-8 long-string headers as text instead of exposing their
bytes as a Base64 object. Explicit binary byte arrays and malformed or control-bearing long strings
remain binary. Review selectors that intentionally addressed the older representation. Business
mapping still requires explicit selectors and the operator's exact allowed header names.

## Back up and restore

1. Quiesce admissions and external producers, drain or explicitly account for running work, and stop
   all writers using the store. Keep the exact graph versions, host configuration and credentials in
   their existing governed custody. Record which external effects may already have occurred.
2. For SQLite, create a consistent database backup with the SQLite backup API or `VACUUM INTO` while
   the source connection has a consistent view. Copying only the main file while a writer or WAL is
   active is insufficient. Verify the backup opens, retains its expected schema version and contains
   the original pending work. Keep the backup separate from the upgraded file.
3. For PostgreSQL, use a matching supported `pg_dump -Fc` against the governed database. Include every
   required schema and its dependent objects. Restore with `pg_restore --exit-on-error` into a separate
   empty database and verify schema, rows, ordering, constraints and pending work before relying on it.
4. Start the new binary against the original store, verify readiness, pinned deployment identities,
   migration history and representative legacy/new history reads, then resume external admission.
5. To roll back, stop every new writer and restore the verified **pre-upgrade backup** to a separate
   target using the old binary and its original configuration. Switch service ownership only after
   checking it. The old binary refuses the upgraded store; there is no in-place reverse migration.

A backup restore loses every accepted operation after that backup. External effects may have survived
outside the database. Reconcile those effects and producer delivery state before resuming; restoring
an earlier initial-start marker state cannot prove that its effect never occurred. Retain audit and
backup evidence through the incident. This procedure does not promise exactly-once external effects.

## Executed upgrade contract

`SqliteBusinessHistoryMigrationUpgradeTest` and `PostgresBusinessHistoryMigrationUpgradeTest` create
actual old schemas by running only migrations through 40 and 20. They populate a SOURCE-root traversal,
a scheduled downstream attempt with an explicit command, retained timer input, source checkpoint and
idempotency receipt. They compare every pre-existing table across the additive upgrade, reopen the
supported store, verify honest uncorrelated/`NOT_RECORDED` reads, and claim the original pending work.

The tests then accept new SOURCE custody through the public store API and reopen it with the original
input, context, target, acceptance time and deadline. They exercise old-runner refusal of the upgraded
database and restore actual SQLite and `pg_dump` backups into separate old-schema targets. The existing
SOURCE recovery tests separately kill real JVMs after a committed receipt and during partial fan-out;
a migration row comparison alone does not prove runtime recovery.

The feature targets **0.9.0-alpha.1** release readiness. Version preparation, promotion, tagging and
publication follow the [release procedure](../governance/releasing.md); merging this contract does not
publish a release.
