# Backup and recovery bundle

Ravenroot’s version 2 recovery bundle is an offline, verifiable capture of the SQLite execution store and file audit trail.

## Commands

```bash
RAVENROOT_EXECUTION_STORE_DIR=/opt/ravenroot/data/execution-store \
RAVENROOT_AUDIT_DIR=/opt/ravenroot/data/audit \
ravenroot backup /secure-backups/ravenroot-2026-08-29

ravenroot verify /secure-backups/ravenroot-2026-08-29

RAVENROOT_EXECUTION_STORE_DIR=/opt/ravenroot/data/execution-store \
RAVENROOT_AUDIT_DIR=/opt/ravenroot/data/audit \
ravenroot restore /secure-backups/ravenroot-2026-08-29
```

Backup and restore acquire the same persistent maintenance lock as the server and return `BUSY` while a live owner holds it. Stop or scale the single workload to zero; never delete `.ravenroot-maintenance.lock`.

## Bundle inventory

| Entry | Contract |
|---|---|
| `MANIFEST.txt` | Canonical UTF-8 inventory with byte length and SHA-256 digest |
| `execution-store.db` | SQLite-native `VACUUM INTO` snapshot |
| `audit/` | Every selected `.audit.jsonl` and `.audit.head` pair |

The destination must not already exist. Publication uses a sibling staging directory and atomic rename. Graph documents and external artifact source are not bundle payloads; preserve them in their owning repositories.

The manifest records `authenticity: not-provided` and `encryption: none`. Protect the complete bundle with access control, encrypted storage, and an independently authenticated transfer channel.

## Verification and limits

Verification refuses missing, extra, non-regular, symlinked, malformed, or digest-mismatched payloads. It verifies each audit chain, runs SQLite `PRAGMA integrity_check`, and checks schema compatibility.

| Limit | Maximum |
|---|---:|
| Manifest | 1 MiB |
| Manifest line | 1,024 characters |
| Inventory | 1,024 payload files |
| Each audit log or head | 64 MiB |
| All audit payloads | 256 MiB |
| Audit records | 1,000,000 |
| SQLite snapshot | 1 GiB |

## Restore safety and reasons

Restore verifies a private immutable copy before replacing live destinations and keeps a durable `.ravenroot-restore.journal` across interruption. Startup returns `RECOVERY_PENDING` until the same offline restore command completes recovery; do not remove journal, stage, or rollback artifacts manually.

Stable reasons include `BUSY`, `RECOVERY_PENDING`, `LEGACY_VERSION`, `INVENTORY_MISMATCH`, `DIGEST_MISMATCH`, `AUDIT_CHAIN_INVALID`, `SQLITE_INVALID`, `RESOURCE_LIMIT`, `DEPTH_LIMIT`, `INSUFFICIENT_SPACE`, and `CONFIGURATION_MISMATCH`. A version 1 bundle is not restored by rewriting its manifest; migrate through a compatible older release and create a fresh version 2 bundle.

Follow the [operator lifecycle procedure](../operator-guide/persistence-lifecycle.md) and the [recovery runbook](../troubleshooting/embed-backup.md).
