# Durable activity content capture

Ravenroot can retain selected node input and output content in a durable archive for audits and
diagnosis. The archive is **disabled by default** and is separate from the execution event journal:
enabling or retaining journal events does not retain payloads, and enabling activity capture does not
change the journal contract. Treat the archive as sensitive data and grant `EXECUTION_READ` only to
principals allowed to read the captured content.

The Service uses the existing persistence selection. SQLite stores activity tables in the configured
execution-store database file, including when execution journaling is disabled. PostgreSQL uses the
existing Service connection pool and database. No second database is required. Schema and archive I/O
are skipped while capture is disabled.

## Service configuration

The following startup settings are immutable until restart. System properties use the equivalent
`ravenroot.activity-capture.*` names shown in parentheses.

| Environment variable | Default | Meaning |
|---|---:|---|
| `RAVENROOT_ACTIVITY_CAPTURE_ENABLED` (`enabled`) | `false` | Enables the archive. |
| `RAVENROOT_ACTIVITY_CAPTURE_POLICY` (`policy`) | `BEST_EFFORT` | `BEST_EFFORT` or `STRICT`. |
| `RAVENROOT_ACTIVITY_CAPTURE_NODES` (`nodes`) | empty | Exact comma-separated node IDs; empty selects every node. |
| `RAVENROOT_ACTIVITY_CAPTURE_CONTENTS` (`contents`) | `INPUT_PAYLOAD,OUTPUT_PAYLOAD` | Any of `INPUT_PAYLOAD`, `INPUT_ATTRIBUTES`, `OUTPUT_PAYLOAD`, `OUTPUT_ATTRIBUTES`. |
| `RAVENROOT_ACTIVITY_CAPTURE_MAX_PAYLOAD_BYTES` (`max-payload-bytes`) | `65536` | Maximum canonical JSON bytes after redaction; 1 through 67108864. |
| `RAVENROOT_ACTIVITY_CAPTURE_MAX_IN_FLIGHT` (`max-in-flight`) | `64` | Process-wide pending writes, 1 through 10000; includes adapters that block or return a stage that never settles. |
| `RAVENROOT_ACTIVITY_CAPTURE_WRITE_TIMEOUT_MILLIS` (`write-timeout-millis`) | `2000` | Per-content persistence deadline, 1 through 300000. |
| `RAVENROOT_ACTIVITY_CAPTURE_RETENTION_SECONDS` (`retention-seconds`) | `604800` | Content retention, 1 through 315360000 seconds. |
| `RAVENROOT_ACTIVITY_CAPTURE_MAX_PAGE_SIZE` (`max-page-size`) | `100` | Maximum records accepted by one history read, from 1 through 1000. |
| `RAVENROOT_ACTIVITY_CAPTURE_REDACT_KEYS` (`redact-keys`) | `password,secret,token,authorization,api_key,apikey` | Case-insensitive object keys replaced with `[REDACTED]` before size validation and persistence. |

See [`docs/examples/activity-capture-service.env`](../examples/activity-capture-service.env) for a
minimal opt-in. Compose forwards these environment variables, and the Helm chart exposes the same
settings under `activityCapture.*`. Malformed values refuse Service startup. Redaction walks ordinary
maps/lists and the typed `PayloadValue` model under the same depth, collection, and total-value bounds
as serialization.
No captured value appears in warnings, exceptions, or capacity diagnostics.

Key redaction is a guard for structured fields, not a secret detector. It does not remove credentials
embedded in free text, renamed keys, encoded blobs, or application-specific objects. Embedded users
can supply a domain-specific `ActivityRedactor`; Service operators must select captured nodes/content
so the remaining values are permitted to persist. The archive stores content as ordinary database
bytes. Use encrypted disks/volumes, database encryption and access controls, encrypted backups, and
the platform's secure deletion procedure for the sensitivity of the selected data.

## Failure policies and the persistence boundary

`BEST_EFFORT` is the default enabled policy. The runtime submits selected records to a bounded
executor, emits a metadata-only warning on redaction, encoding, capacity, timeout, or archive failure,
and continues processing. A blocked custom adapter consumes its in-flight slot until its actual call
or returned stage settles. A timed-out late write can therefore still commit; event identity makes a
retry idempotent. Warning callback failures cannot alter execution.

`STRICT` makes capture part of execution correctness. Selected input records must persist before the
node is invoked. Selected output records must persist before Ravenroot records node completion,
publishes normal completion, or dispatches downstream work. Capacity exhaustion, a malformed
acknowledgement, write failure, or the configured timeout fails the traversal terminally and bypasses
authored node-failure routes. A timeout is a bounded observation boundary rather than a transaction
rollback: a remote or custom archive may commit after Ravenroot has failed the traversal. Ravenroot
cannot undo a connector, tool, runner, or human action that happened before its output existed; strict
output capture prevents Ravenroot from claiming or routing that output afterward.

Retries have distinct attempt IDs and ordinals. Re-entry and tool continuation preserve their actual
invocation and attempt identities. Each content slot has a stable SHA-256 identity over tenant, graph
identity, process, traversal, node, invocation, attempt, and content kind. Replaying the same slot with
a later observation timestamp retains the first row; different immutable metadata or content under
that identity is a deterministic conflict. Output records point to a selected input record when one
exists, and records also carry parent invocations and the execution-journal causation ID.

## Read retained activity

`GET /v1/activity` requires `EXECUTION_READ`. Tenant scope comes only from the authenticated request;
the route rejects a tenant query parameter. It accepts `after`, `limit`, `processInstanceId`,
`traversalId`, `nodeId`, `invocationId`, `attemptId`, and comma-separated `content` kinds. Cursors are
non-negative decimal 64-bit integers and are exclusive. Invalid, overflowing, unknown, or over-limit
values return `INVALID_REQUEST`.

Successful responses contain `gap:false`, `nextCursor`, `retainedFromCursor`, and ascending records.
When retention has removed the requested cursor, the response contains `gap:true`, no records, and
sets `nextCursor` to `retainedFromCursor - 1`; resume from that cursor. Retention maintains a
contiguous tenant-local suffix. If expiry order differs from cursor order, compaction conservatively
removes the prefix through the newest expired cursor so a read never silently steps over a hole.
The SQLite and PostgreSQL adapters run enabled-only physical pruning every minute and also prune on
append/read; an idle tenant therefore does not require new graph traffic for expiry. Deletion makes
rows logically unreachable, but SQLite WAL pages, PostgreSQL dead tuples, replicas, snapshots, and
backups can retain old bytes until their own checkpoint, vacuum, replication, and expiry procedures
run. Coordinate those controls with the configured content retention window.

Embedded applications install a custom port explicitly:

```java
var policy = new ActivityCapturePolicy(true, ActivityFailurePolicy.STRICT,
        Set.of("payments"),
        Set.of(ActivityContentKind.INPUT_PAYLOAD, ActivityContentKind.OUTPUT_PAYLOAD),
        PayloadLimits.DEFAULTS, 32, Duration.ofSeconds(2), Duration.ofDays(7),
        ActivityRedactor.none());
behaviors.withActivityCapture(new ActivityCapture(policy, myActivityArchive));
```

`ActivityArchive.append` may complete asynchronously. Ravenroot invokes it through its own bounded
executor, validates a non-null acknowledgement for the same event ID, and applies the configured
deadline. Embedded history uses `RavenrootApplication.activityAfter`; external adapters should use
`AuthorizedRavenrootApplication.activityAfter` so tenant identity is sourced from `RequestContext`.
