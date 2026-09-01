# ADR 0014: Local execution-store operational surface

- Status: Accepted
- Date: 2026-08-02
- Supersedes: Treating a local SQLite path and file maintenance as generic store-port concerns
- Superseded by: None
- Public references: [Durability, events, and recovery](../docs/architecture/durability-events.md), [persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md), [backup and recovery](../docs/reference/backup-recovery.md)

## Context

The engine-neutral execution-store port intentionally does not expose database files, but a local
SQLite adapter still needs safe location, backup, restore, retention, and error-classification
semantics. Copying only the main database file can omit committed WAL content, and restoring beside
stale sidecars can replay transactions from another database.

## Decision

The SQLite location is an adapter-specific type. It creates a missing configured directory and
accounts for the database, WAL, and shared-memory files. Offline backup uses SQLite `VACUUM INTO` to
produce one self-contained file and refuses to overwrite an existing target. Restore validates
SQLite format, integrity, and schema compatibility before changing the destination, then removes WAL
sidecars before copying the validated backup.

Backup and restore require maintenance ownership and do not pretend an idle open connection can be
detected reliably. Retention that belongs to a caller remains per operation rather than becoming an
adapter setting. Filesystem permission failures are deterministic authorization rejections, not
retryable availability failures.

## Consequences

- Operators have an explicit, verifiable offline backup and restore path.
- A local adapter does not expand the portable execution-store API with filesystem concepts.
- Instance archival remains outside this decision because the schema cannot yet prove that retained
  idempotency records no longer refer to an instance.
