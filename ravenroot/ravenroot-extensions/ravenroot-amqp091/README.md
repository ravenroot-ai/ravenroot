# Ravenroot AMQP 0-9-1 extension

This extension contributes `amqp.publish` for bounded AMQP 0-9-1 publication and `amqp.consume` for
long-lived manual-ack queue consumption through the official RabbitMQ Java client. It is an extension artifact: it is not bundled in the
standard image and must be installed and enabled under the operator's extension policy.

## Trust boundary

GraphML contains an opaque `brokerProfile` and optional values that can only narrow or select within
that profile. The operator profile owns the broker host and port, TLS requirement, virtual host,
username, opaque credential reference, exact exchange/routing/header/reply-to authority and every
resource ceiling. Graph content cannot supply an endpoint, username, credential reference, password,
TLS downgrade or topology declaration. Plaintext profiles are accepted only for an exact loopback
host.

The default profile resolver reads:

```text
RAVENROOT_AMQP091_PROFILE_<TENANT_UTF8_HEX>_<PROFILE_UTF8_HEX>
```

Its semicolon-delimited value has exactly these fields:

```text
host;port;tls;vhost;username;credentialRef;defaultExchange;additionalExchanges;
defaultRoutingKey;additionalRoutingKeys;approvedHeaders;approvedReplyTo;allowPersistent;
maxPriority;maxExpirationMs;maxConcurrency;maxPerSecond;timeoutMs;maxBodyBytes;retries
```

The displayed line breaks are explanatory; the environment value itself is one line. Comma separates
the exact allow-list fields. Wildcards are not supported. Booleans are exactly `true` or `false`.
Conservative hard maxima are 16 concurrent publications per profile, 100 admissions per second,
30 seconds for the one total deadline, 1 MiB per body, priority 9, expiration 24 hours and three
pre-publish retries.

The profile's `credentialRef` is resolved for every invocation through Ravenroot's `CredentialResolver`
contract. The default resolver maps it injectively to:

```text
RAVENROOT_AMQP091_CREDENTIAL_<REFERENCE_UTF8_HEX>
```

The resolver is called exactly once per invocation, including an invocation that performs connection
retries. An injected rotating provider is therefore observed by the next invocation; a revoked or
unavailable value fails closed. Process environment values remain fixed for the process lifetime and
require a restart to change. `SecretValue` and the mutable protocol copy are cleared on every exit path.
The RabbitMQ client itself accepts a Java `String` password, so clearing is best-effort at the extension
boundary, consistent with the SDK's `SecretValue` contract.

## Inspector and payload

The inspector exposes `brokerProfile`; authorized exchange and routing defaults; mandatory publication;
content type/encoding; persistence, priority and expiration; message, correlation, reply-to, type and
application identifiers; approved headers; and timeout, concurrency and retry tightening. `mandatory`
must remain `true`. Numeric graph values can only lower the profile ceiling.

The input is an `amqp.publish.v1` object with exactly one body:

- `bodyText`: UTF-8 text;
- `bodyJson`: a bounded JSON-compatible value serialized by Ravenroot's canonical payload codec;
- `bodyBase64`: strict Base64 decoded to arbitrary bytes.

The payload may override inspector defaults only inside the same operator authority. A configured
`priority` or `expirationMs` is both the graph default and the effective cap for payload overrides;
when the graph field is absent the operator-profile ceiling remains effective and the wire default is
absent. A payload may explicitly use `null` to omit priority. It may omit expiration with `null` only
when the graph does not configure an expiration: a finite graph expiration, including zero, cannot be
cancelled by payload data. Zero remains a valid default or override. Supported AMQP metadata fields are
`contentType`, `contentEncoding`, `persistent`, `priority`, `expirationMs`,
`messageId`, `correlationId`, `replyTo`, `type`, `appId` and a bounded string-to-string `headers` map.
Every value encoded as an AMQP `shortstr`, including exchange, routing, property strings and header
names, is validated as at most 255 UTF-8 octets before credentials or network access. This is an octet
limit rather than a Java character-count limit.

## Delivery state

Each invocation opens and closes one TLS connection and confirm channel. Automatic connection and
topology recovery are disabled. The node publishes with `mandatory=true`, installs return and confirm
listeners before `basic.publish`, and completes its delivery state exactly once:

| Status | Meaning |
|---|---|
| `CONFIRMED` | broker `basic.ack`, with no preceding mandatory return |
| `RETURNED` | sanitized `basic.return` metadata proves the message was unroutable |
| `NACKED` | broker `basic.nack` |
| `REJECTED` | graph or payload validation/authority refusal before secret or network access |
| `RATE_LIMITED` | bounded local per-tenant/profile rate admission refused |
| `TEMPORARY_FAILURE` | local capacity or proven pre-publish connection establishment exhausted |
| `PERMANENT_FAILURE` | unavailable credential or terminal connection/authentication/protocol refusal |
| `AMBIGUOUS` | timeout, disconnect, channel close or client failure after publication may have begun |

Only a proven connection-establishment failure can be retried. Connection, channel creation, confirm
mode, synchronous publish, confirm waiting and orderly close all consume the same monotonic total
deadline; each stage receives only the remaining budget. A connection attempt retains ownership until
the fully established session is claimed, so a resource returned after cancellation is aborted and
cannot publish. A blocked publish or close is interrupted by forced socket abort. Orderly close and any
synchronous wait for abort consume only the remaining total budget; at expiry, forced abort is started
without adding a fixed post-deadline grace. Retries use bounded exponential backoff inside the same
total deadline. Once `basic.publish` is invoked, no failure is automatically retried;
the caller must reconcile an `AMBIGUOUS` result using its own idempotency policy. Results include only
sanitized status/reason, attempt count, safe exchange/routing and supplied message/correlation IDs;
`RETURNED` also contains bounded reply code/text and returned exchange/routing metadata.

RabbitMQ documents that a mandatory unroutable message is returned before its publisher acknowledgement,
which is the ordering used to distinguish `RETURNED` from `CONFIRMED`. See the official
[Java client API guide](https://www.rabbitmq.com/client-libraries/java-api-guide),
[publisher confirm guide](https://www.rabbitmq.com/docs/confirms), and
[TLS guide](https://www.rabbitmq.com/docs/ssl).

## Long-lived `amqp.consume`

The consumer is created only when a deployment containing the node starts. Startup resolves the
tenant's publish profile and a separate inbound authority, probes `TrustedIngress` for durable
receipts, acquires a process-local queue lease, resolves the credential, opens one connection and
one channel, applies QoS and calls `basic.consume`. Readiness is published only after the broker's
`consume-ok`. Existing publish profiles therefore remain publish-only unless the operator adds the
separate consumer value:

```text
RAVENROOT_AMQP091_CONSUMER_<TENANT_UTF8_HEX>_<PROFILE_UTF8_HEX>
```

Its semicolon-delimited value has exactly these fields:

```text
queue;prefetch;approvedHeaders;identityHeader;maxBodyBytes;maxHeaderBytes;
retryBackoffMs;maxRetryBackoffMs;poisonAttempts;poisonPolicy;drainTimeoutMs
```

The displayed break is explanatory; the value is one line. Queue, QoS, header projection, optional
identity-header fallback, reject/dead-letter poison policy and all retry/drain ceilings are operator-owned. GraphML can carry only the
opaque profile, an exact queue confirmation and numeric tightening. `poisonPolicy=dead-letter`
requires `deadLetterMode=broker-dlx`; the extension never declares, binds or modifies broker topology.
The queue must already have the operator's intended dead-letter configuration.

Exactly one source thread owns the channel and is the only caller of ack, nack, cancel and close.
RabbitMQ callback threads only enqueue immutable bounded delivery data. Automatic connection and
topology recovery are disabled; transient loss revokes the session generation, closes it and creates
a fresh session after capped exponential reconnect backoff with per-source equal jitter. Each source
owns a distinct `SecureRandom`; no extension-level random state or lock is shared. The exponential upper
envelope grows from twice the configured backoff to the operator cap, while the actual wait is sampled
between the configured minimum (or half the cap, when larger) and that cap. It is therefore never zero,
never below the enforced 100 ms minimum and never above `maxRetryBackoffMs`, whose configured cap cannot
be below one second. A
durably accepted and broker-acknowledged delivery resets that streak. A new `consume-ok` alone does
not reset it. Stop, rollback and shutdown interrupt the wait, revoke the generation before cleanup,
release the queue lease and close all resources. A late traversal completion from an old generation
cannot acknowledge.

Each delivery becomes a bounded immutable `amqp.delivery.v1` event. It contains safe exchange/routing,
redelivery state, allowlisted scalar headers, UTF-8-or-Base64 body representation, body size, attempt,
correlation and source provenance. Client objects, channels and delivery tags never cross into graph
traversal. Durable identity must be supplied by the producer's AMQP `message_id`, or by the explicitly
authorized identity header, and producers should keep that identifier unique for one logical message.
The durable key binds that selected identity to a domain-versioned SHA-256 digest over unambiguous
length-prefixed profile, queue, identity, exchange, routing key and original body bytes. Thus a true
redelivery produces the same key, while accidental producer reuse of one ID for different immutable
content cannot be mistaken for `Duplicate`. The digest is a collision guard, never a replacement for
producer identity; correlation id, delivery tag and redelivery state are not identity inputs.

The broker's unacked delivery is the sole resume state. Ravenroot's source checkpoint is used only as
a startup durability-capability probe and is never advanced. `DurablyCommitted` and `Duplicate` are
the only receipts that permit `basic.ack`. `Ambiguous` re-offers the same idempotency key while leaving
the broker delivery unacked. `Refused` uses bounded retry and `basic.nack(requeue=true)`; at the poison
ceiling it uses `basic.nack(requeue=false)` so the broker rejects or dead-letters according to queue
policy. `VolatileCustody` fails closed. These guarantees are single-process/store durability, not
cross-replica exactly-once delivery, and Ravenroot does not replay a traversal interrupted mid-crash;
downstream effects must remain idempotent.

The deterministic suite uses an injected protocol seam for lifecycle, reconnect, generation fencing,
ack/nack, redelivery, poison, backpressure and stop races. This optional module does not bundle a live
RabbitMQ/container fixture; operators should additionally verify their TLS, credentials, queue ACL,
QoS and DLX policy against their broker environment.
