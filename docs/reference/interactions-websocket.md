# Authenticated interaction WebSocket

The optional interaction listener provides one versioned bidirectional protocol at
`/v1/interactions`. It is disabled by default, binds to `127.0.0.1:8081` when enabled, and requires
the exact WebSocket subprotocol `ravenroot.interactions.v1`. Ravenroot accepts no WebSocket
extensions or compression. A syntactically valid client extension offer is ignored, and the server
negotiates no extension.

## Session sequence

The first complete text message must be
`{"version":1,"type":"authenticate","bearer":"…"}`. The token is bounded, is never accepted
from a query string, cookie, upgrade `Authorization` header, or subprotocol, and is retained only for
periodic credential revalidation. Incidental bounded `Cookie` headers are ignored. An `Origin`, when
present, must be one exact canonical HTTP(S) origin from `RAVENROOT_BROWSER_ALLOWED_ORIGINS`;
non-browser clients may omit it.

The server answers successful authentication with:

```json
{"version":1,"type":"authenticated"}
```

Commands are then ready. To consume durable events, send exactly one resume selection:

```json
{"version":1,"type":"resume","afterJournalOffset":41}
```

`afterJournalOffset` is a nonnegative integer and is the last durably processed tenant journal
offset. Zero means the beginning of the journal. It does not silently jump across purged history. A
cursor older than the retained floor receives this terminal message and then a close:

```json
{"version":1,"type":"stream.truncated","code":"STREAM_RETENTION_EXCEEDED","retainedFrom":17,"resumeFrom":16}
```

Reconcile from HTTP resources before reconnecting at `resumeFrom`. Ravenroot never creates a
server-side cursor row.

Each event wraps the exact durable projection also serialized for SSE:

```json
{"version":1,"type":"execution.event","eventId":"1386d336-52a7-4e0c-8142-038ec3d41dbc","event":{"journalOffset":42,"streamSequence":7,"occurredAt":"2026-09-06T12:00:00Z","eventType":"NODE_COMPLETED","description":"Node completed.","graphVersion":"v7","processInstanceId":"57e8e94e-5661-4655-aad2-b12d63542f75","traversalId":"82159073-a1df-43d1-a012-8f691dc64f22","invocationId":null,"attemptId":null,"causationId":null,"nodeId":null,"edgeId":null,"handlerId":null}}
```

Persist `event.journalOffset` and the outer `eventId` only after processing, then acknowledge the
exact sent pair:

```json
{"version":1,"type":"ack","journalOffset":42,"eventId":"1386d336-52a7-4e0c-8142-038ec3d41dbc"}
```

Acknowledgements are cumulative through their offset. Exact duplicate pairs are accepted while they
remain in a bounded history equal to `unacknowledged-events`; an unknown offset, a pruned duplicate,
an unsent pair, or a known offset with the wrong event ID closes as a protocol error. The client-held
durable pair remains the authority across reconnects.

The v1 mutation surface contains only these commands:

```json
{"version":1,"type":"command","messageId":"client-17","command":"human-task.resolve","taskId":"ae0dd61d-0f99-4ef3-aaea-fd86eea9a7ad","generation":3,"payloadBase64":"eyJjb250cmFjdCI6InJhdmVucm9vdC5wYXlsb2FkLzEiLCJraW5kIjoiU0NBTEFSIiwic2NoZW1hIjoiYXBwcm92YWwucmVzcG9uc2UiLCJzY2hlbWFWZXJzaW9uIjoiMSIsInZhbHVlIjoiYXBwcm92ZWQifQ==","contentType":"application/octet-stream","comment":""}
```

```json
{"version":1,"type":"command","messageId":"client-18","command":"human-task.deny","taskId":"ae0dd61d-0f99-4ef3-aaea-fd86eea9a7ad","generation":3,"comment":"Needs review"}
```

```json
{"version":1,"type":"command","messageId":"client-19","command":"human-task.cancel","taskId":"ae0dd61d-0f99-4ef3-aaea-fd86eea9a7ad","generation":3}
```

Every command requires an exact UUID `taskId`, positive integer `generation`, and nonblank UTF-8
`messageId` of at most 128 bytes. Resolve additionally requires standard base64 `payloadBase64`; the
decoded bytes must be a complete `ravenroot.payload/1` envelope matching the task's pinned schema,
version, and kind as described by the [Human Task authoring contract](human-tasks.md#authoring-contract).
The message grammar admits an empty base64 string, but zero bytes do not form that required envelope,
so the durable Human Task authority returns `PAYLOAD_REFUSED`. The optional `contentType` is at most
255 UTF-8 bytes and blank or absent means `application/octet-stream`; its resolved value must also
match the pinned media type. `comment` is optional for every command, may be empty, and is at most
4,096 UTF-8 bytes. Unknown fields and unknown commands are rejected. The whole message and decoded
response must also fit the configured message ceiling.

A successful or already-settled durable operation returns:

```json
{"version":1,"type":"command.result","inReplyTo":"client-19","outcome":"cancelled","taskId":"ae0dd61d-0f99-4ef3-aaea-fd86eea9a7ad","generation":4,"resumeTraversalId":"21c5055f-39db-442c-a59b-11c4ca958dc2"}
```

`outcome` is `resolved`, `denied`, `cancelled`, `already_applied`, or `already_settled`.
`resumeTraversalId` is nullable. Refusals contain no task generation, traversal, or existence fact:

```json
{"version":1,"type":"error","inReplyTo":"client-19","code":"RESOURCE_REFUSED"}
```

Missing and unauthorized identifiers both use `RESOURCE_REFUSED`. Other command errors are
`STALE_GENERATION`, `PAYLOAD_REFUSED`, and `RESOURCE_UNAVAILABLE`. `messageId` is echoed only as the
bounded `inReplyTo`; Ravenroot mints its own trusted audit ID. Once a command reaches the durable
Human Task authority, a socket disconnect does not cancel it.

Execution pause, resume, and cancellation continue to use their existing HTTP controls and are not
WebSocket v1 commands because those controls lack the durable command identity required here.
Deployment lifecycle is not wired into the packaged server. Debugger and collaboration operations do
not yet exist as durable application commands. WebSocket v1 does not imply an alternative shipped API
for those future capabilities.

## Grammar and close behavior

Every application message is one UTF-8 JSON object with integer `version: 1` and one of the exact
types above. The first bearer is nonblank and at most 8,192 UTF-8 bytes. JSON is bounded to depth 4,
32 map entries, 128 list entries, 256 total values, and the configured aggregate message bytes. The
server accepts text split across at most `max-fragments`; binary messages are never protocol data.

Protocol faults use fixed close reasons with no token, claim, payload, identifier, or exception text:

| Code | Meaning |
|---:|---|
| `1000` | normal completion, idle deadline, or absolute lifetime reached |
| `1001` | server shutdown |
| `1002` | wrong state, version, schema, command, or acknowledgement |
| `1003` | binary message |
| `1007` | invalid UTF-8 detected by the WebSocket implementation |
| `1008` | authentication deadline/failure/expiry, identity drift, event authorization loss, or unusable replay cursor |
| `1009` | inbound message or outbound canonical event exceeds its configured bound |
| `1011` | unexpected internal or durable-store error |
| `1013` | connection/identity capacity, queue, rate, or acknowledgement deadline exhausted |

Handshake refusal uses HTTP `400` for malformed requests or forbidden credential carriers, `403` for
Origin refusal, `429` for address rate limits, and `503` for pending-authentication capacity.

## SSE and WebSocket

Use `GET /v1/events` when a one-way live stream is sufficient. SSE keeps its existing representation,
uses `Last-Event-ID`, and can fall back to its documented bounded live source. Use this WebSocket only
when the same connection must carry durable Human Task mutations and acknowledged journal replay.
WebSocket replay requires the durable event journal and never falls back to the process-local ring.

Both transports revalidate credentials at `RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS` and at token
expiry, pin subject, principal type, issuer, tenant, roles, and scopes, and reauthorize event
observation. Expiry, key-refresh failure, identity drift, or authorization loss stops dispatch and
delivery before closing the connection. OIDC revalidation observes current signing keys and policy;
it is not JWT introspection. Local-token rotation requires restart.

## Deployment

The supported public topology keeps the listener on loopback and uses a same-host or same-pod TLS
reverse proxy. Route the public HTTPS origin's `/v1/interactions` to port 8081 with HTTP/1.1 Upgrade
and `Origin` preserved, proxy buffering disabled, and proxy idle timeouts longer than Ravenroot's
60-second idle and 30-second acknowledgement deadlines. Existing HTTP health and readiness probes
remain on port 8080.

A configured non-loopback bind is intended for a bespoke network whose confidentiality and peer
reachability the operator enforces outside Ravenroot. Startup still requires `local-token` or `oidc`,
exact origin controls, and all protocol limits; it does not attest that the plaintext backend network
is private. The supplied Compose and Helm services do not publish the WebSocket port.

## Configuration

Set a JVM property `ravenroot.websocket.<suffix>` or the corresponding environment variable
`RAVENROOT_WEBSOCKET_<SUFFIX>`. A nonblank JVM property wins. A blank property is treated as absent,
so a nonblank environment value is then used; the default applies only when both are absent or blank.
Invalid nonblank values refuse startup without echoing their contents.

| Property suffix | Environment variable | Default | Bound |
|---|---|---:|---|
| `enabled` | `RAVENROOT_WEBSOCKET_ENABLED` | `false` | strict Boolean; enabled refuses authentication mode `disabled` |
| `bind` | `RAVENROOT_WEBSOCKET_BIND` | `127.0.0.1` | canonical IP literal or `localhost` |
| `port` | `RAVENROOT_WEBSOCKET_PORT` | `8081` | 1–65,535 |
| `max-connections` | `RAVENROOT_WEBSOCKET_MAX_CONNECTIONS` | `256` | 1–100,000 network connections |
| `pending-authentication` | `RAVENROOT_WEBSOCKET_PENDING_AUTHENTICATION` | `32` | 1–`max-connections` globally |
| `pending-authentication-per-address` | `RAVENROOT_WEBSOCKET_PENDING_AUTHENTICATION_PER_ADDRESS` | `4` | 1–global pending limit |
| `backend-operations` | `RAVENROOT_WEBSOCKET_BACKEND_OPERATIONS` | `128` | 1–100,000 concurrent authentication, authorization, command, and journal operations |
| `authentication-deadline-seconds` | `RAVENROOT_WEBSOCKET_AUTHENTICATION_DEADLINE_SECONDS` | `5` | 1–60 |
| `max-message-bytes` | `RAVENROOT_WEBSOCKET_MAX_MESSAGE_BYTES` | `524288` | 1,024–16,777,216 aggregate UTF-8 bytes |
| `max-fragments` | `RAVENROOT_WEBSOCKET_MAX_FRAGMENTS` | `16` | 1–1,024 per message |
| `pending-commands` | `RAVENROOT_WEBSOCKET_PENDING_COMMANDS` | `32` | 1–1,024 per connection |
| `queued-incoming-bytes` | `RAVENROOT_WEBSOCKET_QUEUED_INCOMING_BYTES` | `1048576` | message limit–67,108,864 per connection |
| `max-outgoing-frame-bytes` | `RAVENROOT_WEBSOCKET_MAX_OUTGOING_FRAME_BYTES` | `65536` | 1,024–message limit; inbound frames share this ceiling and larger outbound messages are deliberately fragmented |
| `queued-outgoing-frames` | `RAVENROOT_WEBSOCKET_QUEUED_OUTGOING_FRAMES` | `64` | 1–4,096 per connection |
| `queued-outgoing-bytes` | `RAVENROOT_WEBSOCKET_QUEUED_OUTGOING_BYTES` | `1048576` | frame limit–67,108,864 per connection |
| `unacknowledged-events` | `RAVENROOT_WEBSOCKET_UNACKNOWLEDGED_EVENTS` | `64` | 1–4,096 per connection |
| `replay-poll-millis` | `RAVENROOT_WEBSOCKET_REPLAY_POLL_MILLIS` | `100` | 50–60,000 |
| `acknowledgement-deadline-seconds` | `RAVENROOT_WEBSOCKET_ACKNOWLEDGEMENT_DEADLINE_SECONDS` | `30` | 1–300 |
| `idle-timeout-seconds` | `RAVENROOT_WEBSOCKET_IDLE_TIMEOUT_SECONDS` | `60` | 1–3,600 |
| `absolute-lifetime-seconds` | `RAVENROOT_WEBSOCKET_ABSOLUTE_LIFETIME_SECONDS` | `3600` | 1–86,400 |
| `shutdown-timeout-seconds` | `RAVENROOT_WEBSOCKET_SHUTDOWN_TIMEOUT_SECONDS` | `5` | 1–60 |

Inbound messages, fragments, pending authentication, backend operations, commands, connections, active identity streams,
unacknowledged events, and outgoing queues are bounded. Binary data, malformed JSON, incorrect state,
oversize messages, slow consumers, and authentication loss close with fixed protocol reasons that do
not contain credentials, claims, payloads, or exception text.
