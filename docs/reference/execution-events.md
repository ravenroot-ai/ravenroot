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
| `terminationReason` | Why a terminal `status` was reached, when `status` alone would misdescribe it; `null` when nothing distinguishes the termination or the execution has not terminated. Always present, including as JSON `null`. **A cancelled execution reports `status == "FAILED"` and `terminationReason == "CANCELLED"`** — read the two together, never `status` alone, or a deliberate stop reads as an incident |
| `cancelled` | Convenience boolean equivalent to `terminationReason == "CANCELLED"` |
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

## Event delivery

`GET /v1/events` is the live SSE stream. `GET /v1/events/recent` returns events after a cursor in ascending order. Its optional limit above the server cap is refused rather than clamped. `source=DURABLE` identifies journal replay; `source=RING` identifies the bounded live buffer.

If the requested cursor predates retained history, the response marks a retention gap. The client must reconcile from the execution resource or another snapshot before continuing; it must not infer missing state.

For API paths see [HTTP API and CLI](api-cli.md). For diagnosis see [Events and persistence](../troubleshooting/events-persistence.md).
