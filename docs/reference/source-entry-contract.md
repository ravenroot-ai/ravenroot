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

The [compiled descriptor inventory](source-descriptor-contracts.tsv) is generated and checked by
`PublishedNodeContractTest` from the complete first-party catalog. It requires exactly the 15 SOURCE
behaviors below, trusted catalog/package ownership, SOURCE nature, shared bounded correlation
controls and the canonical literal `continue` emission. This descriptor proof is separate from the
dispatch, protocol and custody evidence below.

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
| `github-events-source` | `GithubEventsSourceBehavior` | Verified delivery; durable receipt before accepting the webhook. |
| `slack.events` | `SlackIngressSourceBehavior` | Verified event; durable receipt before accepting the callback. |
| `slack.commands` | `SlackIngressSourceBehavior` | Verified command; durable receipt before accepting the callback. |
| `teams.outgoing-webhook` | `TeamsOutgoingWebhookSourceBehavior` | Verified activity; durable receipt before accepting the webhook. |
| `matrix.sync` | `MatrixSyncSourceBehavior` | Durable event receipt before advancing the sync cursor. |
| `mattermost.outgoing-webhook` | `MattermostOutgoingWebhookSourceBehavior` | Verified post; durable receipt before accepting the webhook. |

Volatile WebSocket admission and a live projected HTTP response do not promise a durable source
receipt. For the durable rows, SQLite and PostgreSQL retain the original input and business context
before returning a committed receipt. Recovery input is independent of activity capture and is never
copied into history metadata. See [source custody and restart](../operator-guide/deployment-startup.md#source-receipt-custody-and-restart).

An explicit stable consumer identity is a transport stream, keyed by tenant, trusted behavior,
source node and consumer ID. It deliberately preserves exclusive checkpoint/inbox continuity across
replacement deployment IDs and graph versions. That does not merge business histories: a fresh event
is admitted into the current deployment/version, while replay of an earlier accepted receipt keeps
its original scope and pending custody. It cannot pin a successor manifest, execute the old input in
the successor or create a successor business history. Missing nonterminal original input is an
explicit ambiguous reconciliation outcome. Legacy empty consumer IDs remain deployment-scoped.

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

`LakehouseExampleBrowserTest` exercises an actual managed HTTP callback through its START-disconnected
SOURCE into core HTTP effects with the embedded no-manifest composition. That explicit composition
binds the trusted policy snapshot captured at runner startup. Manifest-backed hosted runners still
require the exact resolved execution policy; missing authority is not replaced by startup defaults.

`AmqpMixedDeploymentExampleTest` executes the [mixed graph](../examples/correlation/mixed.md) against
actual RabbitMQ, HTTP, SMTP, Pekko and SQLite. It checks four listeners alongside a bounded START
cycle, sibling email completion, exact source entries and source-to-source boundaries.

`DefaultGraphDeploymentStableConsumerTest` verifies different deployment IDs and changed graph
versions, exact stream ownership, retired-generation refusal, original pending custody and explicit
missing-input behavior. `InstalledMailImapConsumeContainerTest` executes the installed mail bundle in
a JDK 21 container with actual IMAPS, SQLite and Pekko for earliest, latest and empty-latest bootstrap,
store reopen, new deployment IDs, offline mail and independent shared consumers.

Explicit business close barriers and computed context derivation are described in
[Business context operations](business-correlation-operations.md).

### Protocol and lifecycle evidence

| Actual SOURCE | Connector test and exercised contract |
|---|---|
| `timer`, `crontab` | `CoreScheduledSourceDeploymentTest`, `ScheduledSourceLifecycleTest`: lifecycle-owned occurrences, durable checkpoint order and source activation. |
| `amqp.consume` | `AmqpConsumeContractTest`, `AmqpStartupFailureDeploymentTest`: receipt/acknowledgement, reconnect/generation, source health, startup rollback and bounded protocol harness; `AmqpMixedDeploymentExampleTest` and `AmqpCorrelationHeaderProtocolTest` additionally execute real RabbitMQ. |
| `kafka.consume` | `KafkaConsumeContractTest`: manual contiguous durable offset advancement, assignment/readiness, protocol-owned delivery ordering and bounded consumer harness. This is not a live Kafka cluster test. |
| `mail.imap.consume` | `MailImapConsumeContractTest`, `MailImapConsumeGreenMailTest`, `MailTextCorrelationRecipeTest`: real IMAPS plus bounded projection, custody and checkpoint contracts; the installed container gate additionally verifies classloader/package ownership and store reopen. |
| `websocket.receive` | `WebSocketReceiveSourceTest`: bounded volatile admission, session generation, backpressure revocation and explicit non-replayable behavior. It does not claim durable input custody. |
| `openapi.receive` | `OpenApiReceiveSourceTest`: managed route/schema/receipt contract; `LakehouseExampleBrowserTest` additionally executes actual authenticated managed HTTP through runtime/store and Chromium. |
| `openapi.request-reply` | `OpenApiRequestReplySourceTest`: bounded projected request/reply, independent waiter identity, timeout/cancel and source generation. A live response is not a durable receipt. |
| `discord.interactions` | `DiscordInteractionsSourceTest`: signed callback verification, immediate protocol response versus durable command custody, and token stripping. |
| `github-events-source` | `GithubEventsSourceTest`: verified relay/delivery and durable acceptance contract. |
| `slack.events`, `slack.commands` | `SlackIngressSourceTest`: verification, event/command projection, receipt and callback response contracts for both sources. |
| `teams.outgoing-webhook` | `TeamsOutgoingWebhookSourceTest`: verified activity, bounded projection and durable webhook acceptance. |
| `matrix.sync` | `MatrixSyncSourceTest`: sync event admission and checkpoint/cursor ordering. |
| `mattermost.outgoing-webhook` | `MattermostOutgoingWebhookSourceTest`: verified post projection and durable acceptance contract. |

These tests exercise the shipped source implementations with bounded transport/protocol fixtures;
the table identifies additional live broker/server cases explicitly. Common dispatch ownership,
source boundaries, retired contexts, target validation and source-local liveness use
`SourceLocalTraversalTest`, `SourceAuthorityLifecycleTest`,
`DefaultGraphDeploymentSourceAuthorityIntegrationTest` and `DisconnectedSourceCommandTest`.
Durable adapter contracts independently verify custody and metadata scope on SQLite and PostgreSQL,
including the child-JVM recovery cases described above.

Normal runtime/example gates use Pekko and engine-independent harnesses. Akka remains a separate
optional licensed adapter with mirrored engine/source lifecycle contracts. A graph cannot enable it.
Its `akka-actor-typed_2.13:2.10.20` dependency must be available under the operator's licensed build
setup before that separate profile can be qualified.
