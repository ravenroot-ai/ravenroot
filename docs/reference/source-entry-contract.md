# SOURCE entry and receipt contract

A SOURCE is a deployment listener and an explicit entry point. Activation starts listeners without
running graph START. An accepted event emits at its owning enabled SOURCE and follows the canonical
`continue` outcome. A graph edge arriving at a SOURCE records a source boundary and stops that branch;
it does not open another listener, emit another event, or enter START. Other branches can continue.

Every registered source descriptor exposes `continue`, including descriptors whose lifecycle interface
supplies the SOURCE nature. Existing additional connector outcomes remain in the catalog. For example,
`openapi.request-reply` also declares its response outcome; that declaration does not change the
runtime-owned emission outcome.

A source facade binds `IngressTarget.start()` to that source's own node for compatibility. An explicit
named target must be the same owner. Unknown, disabled, non-source, foreign-owner and retired-generation
targets are refused before effects. The deployment-level START surface still means the deployment's
single initial START traversal.

## Source inventory

The following are the built-in and bundled source implementations. Each target below is the exact
node that owns the active source context. A transport acknowledgement and an application correlation
identifier remain separate identities.

| Behavior | Listener implementation | Admission and transport custody |
|---|---|---|
| `timer` | `ScheduledSourceNodeFactory` | Durable occurrence receipt before advancing its source checkpoint. |
| `crontab` | `ScheduledSourceNodeFactory` | Durable occurrence receipt before advancing its source checkpoint. |
| `amqp.consume` | `AmqpConsumerSource` | Durable receipt before manual broker acknowledgement. |
| `kafka.consume` | `KafkaConsumerSource` | Durable receipt before advancing the consumer offset. |
| `mail.imap.consume` | `ImapConsumerSource` | Durable receipt before advancing retained mailbox occurrence state. |
| `websocket.receive` | `WebSocketReceiveSource` | Bounded volatile session admission; refusal revokes the session for backpressure. |
| `openapi.receive` | `OpenApiReceiveSource` | Durable receipt for the accepted HTTP event. |
| `openapi.request-reply` | `OpenApiRequestReplySource` | Bounded projected request/reply at the owning source; the HTTP caller waits for its response. |
| `discord.interactions` | `DiscordInteractionsSourceBehavior` | Verified callback; durable command receipt before accepting its transport handoff. |
| `github.events` | `GithubEventsSourceBehavior` | Verified delivery; durable receipt before accepting the webhook. |
| `slack.events` | `SlackIngressSourceBehavior` | Verified event; durable receipt before accepting the callback. |
| `slack.commands` | `SlackIngressSourceBehavior` | Verified command; durable receipt before accepting the callback. |
| `teams.outgoing-webhook` | `TeamsOutgoingWebhookSourceBehavior` | Verified activity; durable receipt before accepting the webhook. |
| `matrix.sync` | `MatrixSyncSourceBehavior` | Durable event receipt before advancing the sync cursor. |
| `mattermost.outgoing-webhook` | `MattermostOutgoingWebhookSourceBehavior` | Verified post; durable receipt before accepting the webhook. |

Volatile WebSocket admission and a live projected HTTP response do not promise a durable source
receipt. For the durable rows, SQLite and PostgreSQL retain the original input and business context
before returning a committed receipt. Recovery input is independent of activity capture and is never
copied into history metadata. See [source custody and restart](../operator-guide/deployment-startup.md#source-receipt-custody-and-restart).

## Runtime conformance

`SourceLocalTraversalTest` exercises source ownership, independent source events, source-to-source
boundaries, the same-source cycle with one admission slot, and correlation refusal before effects.
`DisconnectedSourceCommandTest` checks a truly START-disconnected source path and its explicit
application command validation. `SourceCustodyRecoveryTest` kills a separate JVM after a returned committed
receipt and at source start, source completion and outgoing dispatch boundaries. It also covers partial
fan-out, an original ambiguous real effect, retained execution budgets, cancellation, drain, expiry,
and mutation of caller-owned input after the receipt.

`DeploymentEntryContract` runs against SQLite, PostgreSQL and the memory reference adapter. It checks
atomic input/business admission, immutable duplicate input and target, explicit missing legacy input,
concurrent scope quotas, scalar keyset paging, original causal checkpoints, terminal retirement, and
strict versioned encoding. Memory does not claim durable reopen. Connector tests additionally exercise
each transport's verification, acknowledgement, cursor and backpressure rules.

Explicit business close barriers and computed context derivation are described in
[Business context operations](business-correlation-operations.md).
