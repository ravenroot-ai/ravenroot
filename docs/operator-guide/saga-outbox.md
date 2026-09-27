# Durable sagas and application-command outbox

Ravenroot can coordinate an explicitly declared saga across graph nodes while leaving every business
effect in the participant that owns it. The execution store records the saga intent before dispatch
and the observed outcome afterward. It does not create a transaction across Ravenroot, a participant
database, RabbitMQ, or an HTTP service.

## Authoring contract

Saga fields appear in the normal node catalog and inspector and round-trip through GraphML. Every
forward behavior node in a scope declares `saga.scope`, `saga.step`, and `saga.participant`.
`saga.dependsOn` is a comma-separated list of logical predecessor steps. A compensatable step names
the node id in `saga.compensation`; that node declares the same scope and
`saga.role=compensation`. `saga.businessCompletionRequired=true` makes broker acceptance insufficient
for that step. `saga.deadlineMs` sets a scope-wide elapsed deadline; every explicit value in the
scope must match and be between one millisecond and 30 days. Once it expires, no new forward effect
is dispatched. Durable recovery reconciles any operation already in flight and compensates confirmed
effects.

Admission rejects duplicate logical steps, missing or cross-scope compensation nodes, dependency
cycles, unknown dependencies, saga fields on structural nodes, and participant contracts unsupported
by the registered behavior descriptor. `pure` requires a trusted `saga-pure` descriptor capability
and a descriptor that is not effectful. Writing
`saga.participant=pure` on an arbitrary custom node grants nothing. The supported effect contracts
are `jdbc-receipt-v1` on `jdbc.insert`, `amqp-inbox-v1` on `amqp.publish`, and
`http-idempotency-v1` on `http-request`.

Each accepted definition freezes the canonical graph digest, the participant-contract digest, and
the forward/compensation bindings in the durable saga snapshot. Recovery refuses a changed
definition. Because contract version 1 rejects loops and repeat visits through saga operations, a
step occurrence derives from the tenant, process instance, traversal, scope, and logical step. The
invocation and attempt ids remain correlated evidence and do not change the business identity after
a restart. The operation is bound to a canonical payload SHA-256. Reusing the identity with a
different payload is rejected.

## Durability boundaries

`ExecutionBatch` writes saga replacements and application commands in the same fenced store
transaction as the execution revision. SQLite and PostgreSQL persist both tables through numbered
migrations; the in-memory adapter implements the same reference contract for tests but does not
claim process survival. Application admission requires both `DURABLE` and `DURABLE_SAGAS`; a volatile
configuration is rejected before dispatch.

A JDBC participant still owns its local guarantee. Its approved single statement must atomically
write the business effect and a receipt, for example with an engine-supported CTE or trigger. The
ordinary `jdbc.insert` node opens and commits one connection per invocation; Ravenroot never groups
two JDBC nodes into one transaction. An HTTP participant must persist the idempotency key and expose
outcome lookup. Ravenroot propagates the trusted saga operation id as `Idempotency-Key`; forward and
lookup calls pass through the same destination policy, tool grant and server-side credential
reference. A lookup is accepted only when its bounded receipt matches operation id, request-body
fingerprint and the expected `APPLIED` or `COMPENSATED` state. An AMQP
consumer must atomically insert the stable message id in an inbox and apply its business effect in
the same local transaction.

The application-command outbox has distinct `PENDING`, `CLAIMED`, `BROKER_ACCEPTED`,
`BUSINESS_COMPLETED`, and `EXHAUSTED` stages. Claims have a bounded TTL and monotonic fence. A live
claim cannot be reacquired, including by the same worker. Expired claims preserve message identity,
consume a bounded attempt, and reject stale settlement. After a durable broker confirmation, later
sweeps perform participant receipt lookup and do not publish again. If a publisher dies before it can
store the confirmation, publication may repeat with the same message id; inbox deduplication is what
makes that interval safe. Business completion settlement and its saga-step transition commit in the
same execution-store transaction, so a crash cannot leave a terminal outbox row beside a still-pending
saga step. Exhausted delivery similarly parks the saga as `UNRESOLVED` with an actionable reason.

No global delivery order is claimed. Ordering exists only where a destination and participant
protocol enforce it. A published command cannot be withdrawn. Later failure therefore requires an
explicit compensating command carrying its own operation identity and causal message identity.

## Runtime outcomes and recovery

The runner wraps the real registered handler. It commits `DISPATCHED` before invoking the handler,
then records confirmed success, confirmed no-effect, or unknown outcome from the actual adapter
boundary. Unknown forward or compensation outcomes leave the saga `UNRESOLVED`; graph completion is
refused while a scope is `RUNNING`, `COMPENSATION_PENDING`, or `UNRESOLVED`. Successful late siblings
remain effects that must be compensated. Shutdown can stop new dispatch, but it cannot fence an
external effect already issued.

Compensation nodes can run only for a confirmed effect and in reverse dependency order. Their
operation identities are stable and their unknown results remain actionable. `SUCCEEDED` and
`COMPENSATED` are terminal saga dispositions. A compensated saga completes the recovery workflow,
but the original execution and traversal finish as `FAILED`, never as business success.
`COMPENSATION_PENDING` and `UNRESOLVED` must be retained with their
receipts and outbox records; storage cleanup must not remove them while recovery can still occur.

Saga snapshots and outbox records are tenant and process scoped through `ExecutionStore`. An
authenticated execution reader can inspect bounded, payload-free status at
`GET /v1/executions/{processInstanceId}/sagas`; the response exposes identities, step states,
outbox stages and attempt counts, and actionable reasons without receipts, destinations or command
bodies. The deployments window shows the same disposition, reason, per-step states and outbox
progress for its selected process; it derives no saga success from
the event stream. Server
managed execution writes still pass the pinned manifest authority. Tenant-wide outbox claims are
deliberately refused by that managed proxy; a deployment must compose its publisher against the raw
adapter under a separate operator-owned worker authority instead of turning a graph or HTTP caller
into an outbox worker. Operator tooling may report the public snapshot and outbox stages, but it must
not edit a step into success. `POST /v1/executions/{processInstanceId}/sagas/{sagaId}/{action}` accepts
`reconcile`, `retry-compensation`, or `compensate`, requires execution-control authority and
`X-Ravenroot-Expected-Saga-Revision`, writes ATTEMPT plus terminal control-audit records, and performs
one conditional saga transition. These actions reopen idempotent participant work or request durable
compensation; none can manufacture a successful receipt.

When the matching command is `EXHAUSTED`, `reconcile` or `retry-compensation` conditionally rearms
that exact persisted command in the same atomic batch as the operator transition. Its message id,
operation id, destination, contract and payload fingerprint cannot change. Rearming increments the
fence, clears the expired owner and opens one new bounded attempt window. A command that already has
a broker receipt resumes at `BROKER_ACCEPTED` for business-outcome lookup; it is not published again.
The store rejects a rearm for another execution, for a non-exhausted command, or without the owning
saga compare-and-set write. Repeated operator recovery therefore remains explicit and audited rather
than becoming an unbounded automatic retry loop.

## Operational limits

Command payloads are opaque, content typed, and capped at 256 KiB. A step's frozen recovery envelope,
which may contain both forward and compensation commands, is capped at 640 KiB. Delivery pages, lease TTLs,
attempts, and retry delays are bounded. The execution store also refuses an atomic batch before any
row is written when a tenant would exceed 128 non-terminal commands or 16 MiB of encoded immutable
intents. Operators may lower these process-wide admission limits with the JVM properties
`ravenroot.saga.outbox.maxOutstandingCommands` and
`ravenroot.saga.outbox.maxOutstandingBytes`; every process sharing a store must use the same values.
Terminal `BUSINESS_COMPLETED` and `EXHAUSTED` rows remain retained evidence but no longer consume
delivery capacity. Diagnostics are capped and must contain no credentials or
business payload. Deduplication and inbox retention must exceed the longest retry, recovery, and
operator-reconciliation window. If a participant cannot retain receipts for that interval or cannot
look up an uncertain operation, use no saga participant contract for it; Ravenroot will not infer an
effect outcome.
