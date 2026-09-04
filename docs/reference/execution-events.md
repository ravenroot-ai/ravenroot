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

Pause is a dispatch barrier, not an interruption inside a running node. Cancellation races with completion and reports which terminal action won.

## Terminal evidence

The result contains unique node sets rather than an ordered trace:

| Field | Interpretation |
|---|---|
| `paused` | Whether a pause is currently held on this execution; always `false` once the execution is terminal |
| `visitedNodes` | Nodes entered during traversal |
| `defaultedNodes` | Nodes resolved through default behavior |
| `bypassedNodes` | Nodes traversed without executing behavior |
| `handledFailureNodes` | Failures routed through graph handling |
| `untakenEdges` | Edge identifiers or descriptions not selected |

A handled failure can coexist with an overall successful terminal path. Consumers use events for ordering and result fields for set membership.

## Pause and resume events

`EXECUTION_PAUSED` and `EXECUTION_RESUMED` report the same hold `POST .../pause` and `POST .../resume` establish and release. A paused execution has not stopped: it keeps its state, it is still listed by `GET /v1/executions/live`, and it is still cancellable — the events and the `paused` field above both describe a hold, not a terminal outcome.

**A hold survives a restart when it was taken at a boundary the runtime can write down, and the events do not tell you which kind you have.** `EXECUTION_PAUSED` and `EXECUTION_RESUMED` report the hold, not its durability, and they are published identically either way — a durable hold is not a different hold, it is the same hold with a record behind it. Where the two differ is after a restart: a durable hold is still reported as held and is still resumable or cancellable, and a process-local one is gone with the process that kept it. Neither is ever resumed automatically; a hold writes no claimable work, so recovery has nothing to dispatch for a held traversal and only an authorized resume continues one.

A hold is written down when the traversal is a single branch at a single completed node and the withheld payload is expressible in the payload type model; it is not written down for a traversal that has fanned out, one held at its very first node or at a fan-in, one inside a loop, one whose payload the type model does not cover, or one running against a store that does not keep holds. [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md) states the rule and what to do about it. Do not plan a restart on the assumption that held work is lost: check the durable inventory, where a held instance reads as `WAITING`.

## Event delivery

`GET /v1/events` is the live SSE stream. `GET /v1/events/recent` returns events after a cursor in ascending order. Its optional limit above the server cap is refused rather than clamped. `source=DURABLE` identifies journal replay; `source=RING` identifies the bounded live buffer.

If the requested cursor predates retained history, the response marks a retention gap. The client must reconcile from the execution resource or another snapshot before continuing; it must not infer missing state.

For API paths see [HTTP API and CLI](api-cli.md). For diagnosis see [Events and persistence](../troubleshooting/events-persistence.md).
