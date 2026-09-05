# ADR 0037: Durable canonical execution results

- Status: Accepted
- Date: 2026-09-05
- Supersedes: A terminal execution's result lived only in one process's bounded, in-memory
  `ExecutionResultRegistry`, so it read back as `Unknown` — indistinguishable from an id that never
  existed — after a restart, and was invisible to any other instance
- Superseded by: None
- Public references: [Persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md),
  [Durable execution results](../docs/architecture/execution-results.md),
  [Durability, events, and recovery](../docs/architecture/durability-events.md),
  [HTTP API and CLI](../docs/reference/api-cli.md),
  [ADR 0014](0014-local-execution-store-operational-surface.md),
  [ADR 0032](0032-durable-process-inventory-is-authoritative-rows.md)

## Context

`ExecutionOutcome` is a value that lives inside one JVM: its payload field is a plain `Object`, and
`OpaquePayload` states plainly why that is unstorable — no remote adapter can persist an arbitrary
JVM object, and Java serialization of it would be a security defect. Before this decision, the only
place a terminal result was kept was `ExecutionResultRegistry`, a bounded, count-limited, in-process
cache. A caller who could read a result before a restart could not read it after one, and a caller
connected to a second instance could not read it at all, even though the traversal had genuinely
completed. The gap was structural rather than an omission: nothing converted the in-process value into
a form any store could keep.

A second, narrower gap sat beside it. `ExecutionLookup` had exactly three answers — `Found`,
`Expired`, `Unknown` — and a payload the runtime refused to keep, whether for exceeding a size cap or
for not projecting onto the payload model at all, arrived as a `Found` carrying a null payload. That
is the identical shape a run that legitimately produced nothing reports, so a caller had no way to
tell "there was nothing" from "there was something and you cannot have it."

## Decision

**`DurableExecutionResult` is the boundary where an in-process outcome is converted, once, for every
producer.** It carries the tenant-scoped key, the traversal id, the pinned graph version, the terminal
status and its termination reason, start and end instants, a store-assigned retention deadline, a
payload with an explicit retention state, a failure classifier drawn from the failure's class name
rather than its message, and five ordered, bounded node sets. `ExecutionStore` gains
`recordExecutionResult`, `loadExecutionResult`, `executionResultsRetainedFrom`, and
`purgeExpiredExecutionResults`, gated on the new `StoreCapability.EXECUTION_RESULTS`, implemented by
both `InMemoryExecutionStore` and `SqliteExecutionStore` (schema 18).

**A payload has four possible fates, and each is its own `ResultPayloadState`, never a nullable
field.** `NONE` — the execution produced nothing; a positive statement, never used to report something
that was kept and then dropped. `RETAINED` — the projection is present, with independent `redacted`
and `truncated` flags that qualify it. `WITHHELD` — a payload existed and none of it is stored,
because a configured budget refused it. Two producers reach that state: an encoded projection larger
than the adapter's published byte cap, and a value the runtime's own payload boundary rejected for a
size, depth, element-count, value-count or length budget before any encoding of it existed, which
terminates the traversal on the rejection. `bytes()` reports the size that was refused in the first
case, so an operator can see by how much to raise the cap; it is zero in the second, where there was
never an encoding to measure and the store's cap is not the budget to look at. `UNCONVERTIBLE` — the
value does not project onto the closed payload model at all, which calls for fixing the node rather
than raising a limit. `EXPIRED` is a fifth, read-only member: it is derived from the record's age against the store's
clock at read time and is never written, because a writer cannot know a fact that becomes true later.
The rejected alternative — a nullable payload field with size and validity reported separately — was
rejected because it recreates exactly the ambiguity this decision exists to remove: three of these
five facts collapse onto "the field is null" under that shape, and a caller cannot act on an ambiguity
it cannot observe.

**`ExecutionLookup` gains a fourth member, `Redacted`, and there is no fifth.** `Found` is a live or
terminal execution reported in full. `Expired` is a terminal execution whose record survives but whose
payload has aged past the retention deadline. `Redacted` is a terminal execution whose payload was
never retainable in the first place — `WITHHELD` or `UNCONVERTIBLE` only, never `RETAINED` or `NONE`,
which the canonical constructor enforces by rejecting the other two. `Unknown` remains the answer for
an id this process has no record of, for any reason, including another tenant's id. Both `Expired` and
`Redacted` carry the terminal status and termination reason, for the same reason: `status` alone past
either boundary reports a cancelled execution as a failure, which is a wrong answer, not a partial one.
The two are told apart on the wire by distinct codes, `EXECUTION_RESULT_EXPIRED` and
`EXECUTION_RESULT_REDACTED`, both `410`, because they describe the identical shape of absence to an
HTTP caller — the resource is known and its content is not being returned — and are distinguished by
the closed-vocabulary code rather than by the transport status. The redacted body additionally names
`payloadState` (`WITHHELD` or `UNCONVERTIBLE`), so a caller can distinguish a size limit an operator
may raise from a node returning a value no remote adapter could ever persist. Both CLI transports,
`EmbeddedBackend` and `RemoteBackend`, are driven through the identical `ExecutionLookup` values and
proven to fail with the same message for the same execution.

**The process-local cache decides no terminal question by itself.** `ExecutionResultRegistry` answers
without consulting the store in exactly one case — a `Found` whose status is not terminal — and that
line is drawn where a store read is provably a miss rather than merely expensive:
`DurableExecutionResult` refuses a non-terminal status, so a traversal still running cannot have a
record. Every other local answer defers, a warm full result included. Two narrower rules were tried
and both failed the same criterion. Returning a tombstone's `Expired` outright reported a cache
eviction as a retention expiry, and the cache's bounds are counts, so the eviction says nothing about
the store's clock. Short-circuiting a warm `Found` was that defect read from the other end: expiry is
applied on the durable read path and nowhere else, so an instance quiet enough not to evict a result
went on serving its payload past the deadline the store had assigned it, while a sibling — and the
same instance after a restart — answered `EXECUTION_RESULT_EXPIRED` for the identical id. The cost is
stated rather than hidden: a terminal read costs one store read even when this process holds the
answer. It buys the property the whole record exists to establish, and it leaves untouched the only
traffic the in-memory-first ordering was ever defended on, which is a caller polling an execution that
has not finished.

**A traversal that terminated on a rejected payload is an answer, not an exception.** The registry
retains the typed rejection and used to re-raise it from the read, which the server renders as the
rejection's own recommended status. The record for the same execution says `Redacted`, so one
identifier carried two different wire codes depending on which instance was asked — and, because the
re-raise was rendered through the payload-rejection path, produced one audit record per read on that
instance and none on any other. The retained rejection is now rendered as `Redacted` through the same
`ExecutionResultPayload#refused` classification the durable write applies to it, so the warm answer
and the record cannot disagree about which refusal it was. Nothing about the terminal outcome changed:
the traversal still fails, the rejection is still what failed it, and its reason is still published —
as `payloadState`, on a body that also carries the terminal status and its termination reason.

**Recording is idempotent by refusal, and never by overwrite.** Identity for this comparison is
`DurableExecutionResult#fingerprint()`, a digest over every component the producer decided except the
store-assigned `retainedUntil`, computed over the node sets in their now-deterministic stored order so
an unordered in-memory set does not make two writes of the same result compare unequal. Three outcomes
follow, and there is no fourth: no record exists, so it is written; a record exists with an identical
fingerprint, so nothing is written and the stored record is returned — a duplicate terminal event is
free; a record exists with a different fingerprint, so the write is refused with
`ExecutionStoreFailure.ExecutionResultNotRecordable`, naming both digests and both terminal statuses,
and the committed record is left exactly as it was. The refusal is deliberate rather than a merge: a
terminal result is the answer other systems have already been given, and replacing it would make a
result read once and the same result read again disagree, with nothing in either read saying which is
current.

**The direct consequence, stated because it is counter-intuitive: if a caller reuses a traversal id
across two submissions, the first submission's result is what is kept and read, forever.** The second
execution still runs to completion, but its result is refused at the write and never becomes the
answer any reader receives — not immediately, not after the first result's cache entry ages out, and
not after a restart. This was originally lost rather than merely refused: `startGraphMl`'s completion
seam did not observe the `CompletionStage` the refusal was thrown from, so it vanished into a stage
nothing looked at. It was then only logged, while the process-local cache still held the second
submission's payload, so a live read returned whichever of the two depended on nothing but whether the
cache entry was still warm — a direct violation of the source-of-truth property this feature exists to
provide, introduced by the fix that stopped the refusal from disappearing. The final shape corrects the
cache at the moment of refusal — `ExecutionResultRegistry#forgetLocally` erases the stale entry so the
next read falls through to the durable record — precisely when the failure is the classified,
deterministic `ExecutionResultNotRecordable` conflict; an unclassified failure leaves the cache alone,
because nothing durable exists yet to fall through to. Refusing a reused id at submission time, before
the second execution runs and produces output that can never be kept, is a real but separate question:
it needs a synchronous existence check on every submission's hot path, and is left to a change that can
weigh that cost deliberately rather than as a side effect of this one.

**A result's retention window must never exceed its process instance's.** A recorded result names the
process instance and traversal it belongs to; a result outliving its instance would name a row the
durable inventory can no longer describe. Both `InMemoryExecutionStore` and `SqliteExecutionStore`
enforce `executionResultRetention() <= terminalRetention()` in their constructors, refusing
construction otherwise, for the identical reason `terminalRetention() >= journalRetention()` is already
enforced: the cheaper row to keep outlives the more expensive one it describes, and the check exists at
construction precisely because the schema — `execution_result` cascades from `process_instance` —
makes the violation otherwise unreachable in practice, which is exactly why an operator would otherwise
discover the real number during an investigation rather than at startup.

**A purge leaves an audit record, following the audit trail's own rule that it never deletes.**
`AuditedExecutionResultPurge` composes `ExecutionStore` and `AuditTrail` without coupling either port
to the other — the same reason `ExecutionStore` states its own class is audit-agnostic by design — and
appends one `ADMINISTRATION` record naming the tenant, the count purged, and the operator, on success
or on a refused purge alike, modelled on `AuditTrail#redact`'s own tombstone. As of this decision, no
scheduler, CLI verb, or HTTP route calls `purgeExpiredExecutionResults`, `purgeExpiredIdempotencyRecords`,
or `purgeExpiredProcessInstances` in production; this class is the ready-to-call, already-audited
operation such a trigger composes against, so the audit obligation is met the moment a caller exists.

## Consequences

- **Adding `Redacted` to `ExecutionLookup` is a breaking change to an exhaustive `switch` written
  outside this repository**, stated rather than softened, and it is the identical cost
  `ExecutionTransition` paid when it gained `RecoveryWithheld`. Every in-tree consumer switches on the
  sealed hierarchy exhaustively, so all of them needed a new arm; an external consumer that added a
  `default` mapping `Redacted` beside `Expired` — status and reason present, payload absent — is
  correct without reading further, and must not map it to `Unknown`, which would report a real
  execution as one that never happened.
- **A result's write is exactly-once by refusal.** A caller that resubmits a traversal id after an
  ambiguous prior write gets back the first result whether or not it matches what the second run
  actually produced; the codebase does not merge, does not overwrite, and — after the cache-consistency
  fix — does not disagree with itself between a warm and a cold read.
- **`WITHHELD` has two producers, and they differ sharply in how reachable they are.** The
  store-cap producer is unreachable at either bundled adapter's default configuration:
  `RuntimeActivityData`'s own projection bounds an output to 16 KiB before `DurableExecutionResult`
  ever compares the encoding against the adapter's published cap (1 MiB by default for both adapters),
  so a huge payload is truncated-and-retained rather than withheld. This is a deliberate ordering — a
  declared partial answer beats a refusal — pinned by a contract test
  (`aHugePayloadIsTruncatedAndRetainedRatherThanWithheldAtTheAdaptersDefaultCap`) so a future change to
  either bound cannot silently invert it, and it becomes reachable only when an adapter is configured
  with a cap below the projection's own bound. The payload-boundary producer is ordinary at default
  settings: a node whose value the runtime's payload limits refuse terminates the traversal on that
  rejection, which the built-in JSON parse and path behaviours raise unwrapped, and the result is
  recorded as `WITHHELD` carrying no size, because no encoding of the refused value ever existed. The
  operator response differs with the producer — the first is the store's byte cap, the second the
  payload limits — and `bytes()` is what tells them apart, being non-zero only for the first.
- **The SQLite adapter's schema-18 migration is a one-way binary gate, like every migration in this
  store, and this record does not claim otherwise.** `SqliteSchema.migrate` refuses to open any
  database whose recorded schema version exceeds what the running binary understands, before it reads
  a single row. A binary predating this migration cannot open an upgraded database at all, from the
  moment the migration runs, regardless of whether any result was ever recorded. Take a backup before
  upgrading if rolling back to a binary predating this change is a live possibility.
- **Multi-instance sharing of one durable result store means several processes on one host sharing a
  local filesystem, not several hosts.** `SqliteExecutionStore`'s cross-process exclusion is built on
  POSIX advisory locks, which are unreliable over NFS, SMB, and most network or distributed
  filesystems — locks may be silently ignored, cached, or lost on a client reconnect. Placing the
  database file there does not degrade the multi-instance guarantee, it falsifies it. A second
  instance's read is also not a live subscription: a traversal still running elsewhere, or one whose
  result has not yet committed, has no durable result yet and reads as `Unknown` from that instance
  until the write commits.
- **Without a durable, result-capable store composed, the restart gap this decision closes reopens.** A
  result readable before a restart still reads as `Unknown` afterward, indistinguishable from an id
  that never existed. That is a deployment choice, stated rather than hidden.
- **The engine-level contract addition, proving a real traversal's terminal outcome is durably readable
  by an instance that never ran it, is verified against Pekko only.** Akka's own engine contract test
  could not be built in this environment because its BSL-licensed dependency is unresolvable offline;
  the store-level and core-level contract assertions run against both `InMemoryExecutionStore` and
  `SqliteExecutionStore` regardless of engine.
