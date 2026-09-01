# WebSocket extension

`ai.ravenroot.extensions.websocket` contributes `websocket.send` and deployment-scoped `websocket.receive`.
Profiles are strict Base64 JSON in `RAVENROOT_WEBSOCKET_PROFILE_<hex profile name>` and own the exact `wss` destination, headers, subprotocols, credential binding, message/fragment ceilings, deadlines, reconnect and bounded ingress admission. GraphML supplies only `websocketProfile` and may tighten limits.

Send accepts `{ "version":"websocket.send.v1", "encoding":"text|base64", "data":"..." }` and returns its write outcome only. Cancellation and the actual `sendText`/`sendBinary` invocation share one linearization point: cancellation-first invokes no send and retains its deadline classification; handoff-first may be `AMBIGUOUS`. A send is never retried.

Receive emits `websocket.receive.event.v1`, is process-local and non-replayable, and has no request/reply or durable acknowledgement: generic WebSocket frames provide neither a stable delivery id nor an ack boundary. `maxBufferedEvents` counts queued and currently delivered events together. Reaching the exact limit is accepted; limit plus one, or downstream ingress refusal, first fences the connection generation and then closes it. That generation cannot reconnect or deliver late callbacks.

`maxConcurrency` is one shared ceiling per trusted tenant and operator profile across every send and receive behavior instance in this process. A receive holds its permit for its whole connection generation; a send holds it through managed transport settlement, even when the graph-visible write result has already completed. Different tenants have independent gates. Process-local receive sessions are intentionally neither durable nor replica-coordinated.

The managed runtime supplies automatic Pong and validates the profile-requested message and fragment ceilings. The fragment ceiling counts buffers delivered by the JDK listener, not necessarily wire frames.

The managed runtime resolves the credential once per connection generation. It never appears in GraphML, results, ingress events, readiness/degraded reasons or package diagnostics; only the authorized remote peer receives it. Those reasons are fixed low-cardinality codes, and the package emits no additional metrics or telemetry containing tenant, deployment, profile, header, body, frame or credential values.
