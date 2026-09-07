# Executions, outcomes, and events

## Submission modes

`POST /v1/executions` accepts `mode=test` or `mode=run`; Test is the HTTP default. Acceptance returns HTTP 202 and an execution ID. Test preserves traversal visibility while bypassing behavior effects. Run dispatches real behavior and therefore requires every relevant adapter, credential, tool, and egress policy to be usable.

## Control transitions

| Operation | Contract |
|---|---|
| `GET /v1/executions/{id}` | Return current state or retained terminal result |
| `POST .../pause` | Let the in-flight node finish, then dispatch nothing new |
| `POST .../resume` | Continue a paused execution |
| `POST .../cancel` | Request cancellation and expose its named outcome |

Pause is a dispatch barrier, not an interruption inside a running node. Cancellation races with completion and reports which terminal action won. Cancellation does not preempt a node computation already in flight: it refuses the next hop and releases whichever wait the traversal was holding — a paused dispatch gate or a retry backoff — so effects already issued before the cancellation was observed stand and are not undone.

## Terminal evidence

The result contains unique node sets rather than an ordered trace:

| Field | Interpretation |
|---|---|
| `paused` | Whether a pause is currently held on this execution; always `false` once the execution is terminal |
| `terminationReason` | Why a terminal `status` was reached, when `status` alone would misdescribe it; `null` when nothing distinguishes the termination or the execution has not terminated. Always present, including as JSON `null`. A closed vocabulary of `"CANCELLED"` and `"UNREACHABLE"`; treat a value you do not recognize as "not a cancellation". **A cancelled execution reports `status == "FAILED"` and `terminationReason == "CANCELLED"`** — read the two together, never `status` alone, or a deliberate stop reads as an incident. **An execution ended by reconciliation reports `status == "FAILED"` and `terminationReason == "UNREACHABLE"`**: it could no longer reach any outcome of its own, because a branch was left parked at a fan-in with no node running, no deadline armed and no arrival that could ever come. That is a genuine incident and is meant to be counted as one; what the reason adds is that no node failed, so the thing to investigate is what stopped delivering |
| `cancelled` | Convenience boolean equivalent to `terminationReason == "CANCELLED"`. There is deliberately no matching boolean for `"UNREACHABLE"`: `cancelled` exists because a cancellation must be kept *out* of the failure count, and an unreachable execution belongs in it — read `terminationReason` for that distinction |
| `visitedNodes` | Unique membership of nodes entered during traversal; iteration and wire order are not visit order |
| `defaultedNodes` | Nodes resolved through default behavior |
| `bypassedNodes` | Nodes traversed without executing behavior |
| `handledFailureNodes` | Failures routed through graph handling |
| `untakenEdges` | Edge identifiers or descriptions not selected |

A handled failure can coexist with an overall successful terminal path. Consumers use result fields for set membership only. Their presentation may be stable, but it does not establish traversal chronology. Use invocation or event history when order or repeated visits matter.

`terminationReason` and `cancelled` are carried on every surface that reports a terminal status, not only the live result: the `410` body returned once a result has aged past retention, the durable process and traversal inventory rows (`GET /v1/executions/inventory` and `.../traversals`), and both CLI transports. A cancelled instance reports `FAILED` on every one of them; the reason is what keeps that readable once nothing richer than that surface is left to ask.

## Pause and resume events

`EXECUTION_PAUSED` and `EXECUTION_RESUMED` report the same hold `POST .../pause` and `POST .../resume` establish and release. A paused execution has not stopped: it keeps its state, it is still listed by `GET /v1/executions/live`, and it is still cancellable — the events and the `paused` field above both describe a hold, not a terminal outcome.

**A hold survives a restart when it was taken at a boundary the runtime can write down, and the events do not tell you which kind you have.** `EXECUTION_PAUSED` and `EXECUTION_RESUMED` report the hold, not its durability, and they are published identically either way — a durable hold is not a different hold, it is the same hold with a record behind it. Where the two differ is after a restart: a durable hold is still reported as held and is still resumable or cancellable, and a process-local one is gone with the process that kept it. Neither is ever resumed automatically; a hold writes no claimable work, so recovery has nothing to dispatch for a held traversal and only an authorized resume continues one.

A hold is written down when the traversal is a single branch at a single completed node and the withheld payload is expressible in the payload type model; it is not written down for a traversal that has fanned out, one held at its very first node or at a fan-in, one inside a loop, one whose payload the type model does not cover, or one running against a store that does not keep holds. [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md) states the rule and what to do about it. Do not plan a restart on the assumption that held work is lost: check the durable inventory, where a held instance reads as `WAITING`.

## Cancellation event

A cancelled traversal publishes `EXECUTION_CANCELLED` instead of `EXECUTION_COMPLETED` or
`EXECUTION_FAILED`. It is a traversal-terminal event with the same guarantees as the other two: exactly
one of the three per traversal, never followed by `EXECUTION_PAUSED`, published after the traversal has
stopped dispatching.

**The durable record still reports `FAILED`, by design.** This event is the observability half of the
same decision whose durable half is `terminationReason`: a cancelled execution is stored as `FAILED`
and qualified as `CANCELLED`, so a reader that predates this change still reads a status it
understands. A consumer correlating the live event stream with a durable read must expect a `FAILED`
status under an `EXECUTION_CANCELLED` event.

**An out-of-tree consumer that recognizes only `EXECUTION_COMPLETED` and `EXECUTION_FAILED` stops
seeing a terminal event at all for a cancelled traversal**, until it is updated to recognize
`EXECUTION_CANCELLED`. `EXECUTION_CANCELLED` deliberately replaces `EXECUTION_FAILED` rather than
accompanying it, because a metric or dashboard keyed on event type alone would otherwise still count
every deliberate stop as a failure under a different label.

The event's `publicReason` field is a transitional exception to that separation: for this migration
window it still carries the leaked internal exception class name that predated `terminationReason`,
so a consumer that was matching on that name is not blinded by this change. The event type is the
contract going forward — do not build new matching logic against `publicReason`'s text.

An execution ended by reconciliation publishes `EXECUTION_FAILED`, not a terminal type of its own.
That is not an omission: a cancellation left the failure series because it is not a fault, and this is
one. Nothing about an event-type consumer needs to change for it, and the distinction between "a node
broke" and "this traversal could never settle" is carried by `terminationReason` on every read and by
the failure classification on the event.

## Event delivery

`GET /v1/events` is an authenticated SSE stream. Without a selector, it serves the durable journal when available and otherwise the process-local ring. `include=diagnostics` selects the ring for the whole connection. The source does not change within a connection.

`GET /v1/events/recent` remains a separate legacy polling projection. It returns events after a cursor in ascending order; a limit above the server cap is refused rather than clamped. Its `source=DURABLE` or `source=RING` describes the selected source. Polling rows do not use the versioned stream envelope, and durable polling rows contain fewer fields than durable SSE data.

### Version 1 execution data

The HTTP response is UTF-8 `text/event-stream`, containing SSE frames, not one JSON document or a JSON array. Parse SSE framing first, then decode the JSON `data` of each `event: execution` frame. Every such object has these common fields:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Integer `1`; independent of the internal journal-envelope version. |
| `source` | `RING` or `DURABLE`; selects the explicit variant below. |
| `id` | Exact decimal string equal to the SSE `id`, and to the source's native cursor. |
| `eventType` | Canonical event classifier. Unknown classifiers remain readable. |
| `occurredAt` | Producer time in Java `Instant` text form, including supported extended years. It is not an ordering axis. |
| `processInstanceId` | Existing process-instance UUID. |
| `traversalId` | Existing traversal UUID. |

Both variants retain `description`, `graphVersion`, `invocationId`, `attemptId`, `nodeId` and `edgeId`. Nullable identities remain null where unavailable; node identity on durable events is a best-effort join to retained process structure. A null measurement means not measured, not zero.

| Variant | Source-specific fields and guarantees |
| --- | --- |
| `RING` | Numeric `sequence`, `engineId`, legacy `executionId` and `type`, `activeInstances`, `inFlightArrivals`, `fallback`, `publicReason`, `message` and redaction/truncation flags, optional `output` and flags, and nullable `processingDuration` in seconds. Diagnostics are process-local and never persisted. |
| `DURABLE` | Numeric tenant-local `journalOffset`, process-instance `streamSequence`, persisted UUID `eventId`, nullable `causationId` and `handlerId`. Causation refers to journal event UUIDs, not cursor IDs. Live diagnostic fields and measurements are unavailable and are not replaced with defaults. |

`X-Ravenroot-Event-Source` equals every execution object's `source`. `X-Ravenroot-Event-Schema-Version` is `1`. `X-Ravenroot-Event-Continuity` is `PROCESS_LOCAL` for RING and `DURABLE` for the journal. These headers describe execution data; they do not turn comments or control frames into execution envelopes. Browser clients must not assume custom headers are exposed by cross-origin policy.

Compare cursor identities only within the authenticated tenant and source. RING comparisons additionally require the same process-local continuity domain: sequence numbers reset on restart, and the stream does not provide a durable process epoch. Neither numeric cursor nor common `id` is a globally unique event UUID. Namespace client state by its authenticated context and source; do not carry another tenant's or source's cursor into a new stream. Retain the exact string `id` for resumption. JavaScript must not compare it to a numeric compatibility cursor rounded beyond its safe-integer range. Live IDs preserve signed-long values; durable offsets are positive.

### Compatibility and validation

New writers emit `eventType`. RING writers also retain `type`, with the same value, and `executionId` remains an alias of `traversalId`. Readers accept an optional equal `type` alias on either source; the DURABLE writer need not emit it. Version 1 requires canonical `eventType` in the original object. Fallback to `type` applies only to legacy unversioned inputs; conflicting aliases are invalid. This compatibility period continues until a separately announced breaking migration. No removal date or release count is scheduled.

Version 1 permits unknown extra fields and unknown event classifiers. Readers must still reject unsupported schema versions, unknown source variants and known contradictions, such as a RING event carrying a durable cursor or a DURABLE event claiming live measurements. Do not guess a version or silently reinterpret one variant as the other. Legacy source inference is possible only when the native cursor fields identify it unambiguously.

The OpenAPI document models the HTTP body as an SSE string. Its `x-ravenroot-sse-events` extension maps named frames to schemas for their decoded JSON data; ordinary OpenAPI tools may ignore this extension. The component schemas describe the common envelope and both variants. Equality between SSE IDs, payload IDs, native cursors and compatibility aliases requires semantic validation in addition to schema validation.

### Frames and retention

For example, one RING execution frame is followed here by a keepalive comment:

```text
id: 7
event: execution
data: {"schemaVersion":1,"source":"RING","id":"7","eventType":"EXECUTION_STARTED","sequence":7,"occurredAt":"2026-01-01T00:00:00Z","engineId":"pekko","graphVersion":"graph-v1","processInstanceId":"10000000-0000-0000-0000-000000000001","traversalId":"20000000-0000-0000-0000-000000000002","executionId":"20000000-0000-0000-0000-000000000002","invocationId":null,"attemptId":null,"type":"EXECUTION_STARTED","nodeId":null,"edgeId":null,"activeInstances":0,"inFlightArrivals":0,"fallback":false,"description":"Execution started.","publicReason":null,"message":null,"messageRedacted":false,"messageTruncated":false,"processingDuration":null}

: keepalive

```

A durable frame carries journal identity and causal fields instead:

```text
id: 12
event: execution
data: {"schemaVersion":1,"source":"DURABLE","id":"12","eventId":"30000000-0000-0000-0000-000000000003","journalOffset":12,"streamSequence":1,"occurredAt":"2026-01-01T00:00:00Z","eventType":"EXECUTION_STARTED","description":"Execution started.","graphVersion":"graph-v1","processInstanceId":"10000000-0000-0000-0000-000000000001","traversalId":"20000000-0000-0000-0000-000000000002","invocationId":null,"attemptId":null,"causationId":null,"nodeId":null,"edgeId":null,"handlerId":null}

```

Keepalive comments dispatch no event. `stream-overrun` terminates a slow consumer and carries `code=STREAM_CONSUMER_TOO_SLOW` with `resumeAfter`. `stream-truncated` reports unavailable durable history, carries `code=STREAM_RETENTION_EXCEEDED`, `retainedFrom` and `resumeFrom`, and terminates the stream. These are separate control shapes with no invented process or traversal identity:

```text
event: stream-truncated
data: {"code":"STREAM_RETENTION_EXCEEDED","retainedFrom":10,"resumeFrom":9}

```

When durable replay or recent-event polling declares that a cursor predates retained history, reconcile from the execution resource or another snapshot before continuing; do not infer missing state.

Durable replay survives a restart within retained history. RING eviction and restart can lose observations; the process-local stream cannot prove complete history across a restart. A closed connection or a clean end of a captured file is not evidence that an execution finished. The journal also has narrower event coverage and no live author diagnostics; do not fill gaps by interleaving ring and journal cursor domains.

Raw `detail`, tenant attributes, correlation strings and journal payloads are not published in this envelope. Live message/output fields are already bounded and targeted-redacted at their producing boundary. They remain author diagnostics, never assistant/provider context. The stable-edge limit remains 8192 UTF-8 bytes, auxiliary traversal strings share the 12287-byte escaped budget, and the complete traversal SSE frame remains below 65536 bytes.

For API paths see [HTTP API and CLI](api-cli.md). For diagnosis see [Events and persistence](../troubleshooting/events-persistence.md).
