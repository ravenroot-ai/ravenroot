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
| `visitedNodes` | Nodes entered during traversal |
| `defaultedNodes` | Nodes resolved through default behavior |
| `bypassedNodes` | Nodes traversed without executing behavior |
| `handledFailureNodes` | Failures routed through graph handling |
| `untakenEdges` | Edge identifiers or descriptions not selected |

A handled failure can coexist with an overall successful terminal path. Consumers use events for ordering and result fields for set membership.

## Event delivery

`GET /v1/events` is the live SSE stream. `GET /v1/events/recent` returns events after a cursor in ascending order. Its optional limit above the server cap is refused rather than clamped. `source=DURABLE` identifies journal replay; `source=RING` identifies the bounded live buffer.

If the requested cursor predates retained history, the response marks a retention gap. The client must reconcile from the execution resource or another snapshot before continuing; it must not infer missing state.

For API paths see [HTTP API and CLI](api-cli.md). For diagnosis see [Events and persistence](../troubleshooting/events-persistence.md).
