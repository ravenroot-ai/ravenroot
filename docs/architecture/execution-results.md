# Durable execution results

A terminal execution's canonical result, keyed by tenant and traversal, kept in the configured store instead of only in one process's memory.

## Invariants

- A terminal outcome is converted into `DurableExecutionResult` once, at the boundary every producer shares — the pinned graph version, the terminal status and its termination reason, start and end instants, a store-assigned retention deadline, the payload, a failure classifier, and five ordered, bounded node sets. This is a projection of `ExecutionOutcome`, not a second copy of it: the in-process value's payload is a plain object and cannot itself be persisted by any remote adapter.
- A payload has exactly four possible fates, and each is its own state, never a nullable field: `NONE` (nothing was produced), `RETAINED` (the projection is present, possibly redacted or truncated), `WITHHELD` (the projection exceeded the adapter's published byte cap and is refused, though its size is still reported), and `UNCONVERTIBLE` (the value does not project onto the closed payload model at all). A fifth, `EXPIRED`, is derived from a record's age at read time and is never written down.
- A read answers in exactly four states, and every one of them maps a real record to a real answer. `Found` reports a live or terminal execution in full. `Expired` reports a terminal execution whose payload has aged past the retention deadline, with status and termination reason still present. `Redacted` reports a terminal execution whose payload was never retainable — `WITHHELD` or `UNCONVERTIBLE` only — with status and termination reason still present. `Unknown` reports that this process holds no record for the id, for any reason, including another tenant's execution. Nothing that exists is ever reported as `Unknown`.
- Recording is idempotent by refusal, never by overwrite. An identical re-delivery of a terminal result is a no-op; a genuinely different outcome for a traversal id that already has a recorded result is refused, naming both digests, and the committed record is left untouched.
- A result's retention window must never exceed its process instance's terminal-retention window, because a result names the instance and traversal it belongs to. Both bundled adapters enforce this at construction, refusing to start otherwise.

## Runtime relationships

- `GET /v1/executions/{id}` reads the process-local result cache first and falls through to the durable store on a miss, so a result readable before a restart is readable after one, and readable from a second instance that never ran the traversal — as long as a durable, result-capable store is composed. Without one, the restart gap this feature closes reopens: a result readable before a restart still reads as unknown afterward.
- `EXECUTION_RESULT_EXPIRED` and `EXECUTION_RESULT_REDACTED` are both `410`, because both describe the identical shape of absence to an HTTP caller — the resource is known and its content is withheld — and are told apart by the closed-vocabulary code, not by the transport status. The redacted body additionally carries `payloadState` (`WITHHELD` or `UNCONVERTIBLE`), so a caller can tell a size limit an operator may raise from a node returning a value no remote adapter could ever persist. Both fields travel beside the terminal `status` and `terminationReason` for the same reason they do on the `200` body: `status` alone past either boundary reports a cancelled execution as a failure.
- Reusing a traversal id across two submissions does not make the second submission's outcome the one that gets read. The traversal runs to completion either way, but its result is refused at the durable write and never becomes the answer a subsequent read returns — not while the first result's cache entry is warm, not after it ages out, and not after a restart. The first submission's result is what is kept, permanently.
- `AuditedExecutionResultPurge` appends one `ADMINISTRATION` audit record naming the tenant, the count purged, and the operator, whether the purge succeeded or was refused — following the audit trail's own rule that a retention removal is never silent. As of this release, no scheduler, CLI verb, or HTTP route calls the purge; it exists for a caller that composes it directly.

## Architectural consequence

Because a payload's absence is now four distinguishable facts instead of one nullable field, a caller can act on the specific one it received: raise a byte cap, fix a node's return type, or simply accept that a normal run produced nothing. And because the read hierarchy gained a member rather than reusing `Found` with a null payload, that distinction survives all the way to the wire — at the cost of being a breaking change to any exhaustive `switch` over `ExecutionLookup` written outside this repository, which is the price the previous ambiguity would otherwise have kept hidden forever.

`WITHHELD` is reachable in principle but not at either bundled adapter's default configuration: the payload projection bounds an output to 16 KiB before the adapter's own byte cap is ever compared against it, so a huge payload is truncated-and-retained rather than withheld by default. Multi-instance sharing of the SQLite adapter's file is bounded to processes on one host with a local filesystem — its cross-process exclusion depends on POSIX advisory locks, which are unreliable over a network filesystem, so placing the database there does not degrade the guarantee, it falsifies it.

## Related reading

- [Decision record](../../adr/0037-durable-execution-results.md)
- [Durability, events, and recovery](durability-events.md)
- [Durable process inventory](process-inventory.md)
- [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md)
- [HTTP API and CLI](../reference/api-cli.md)
