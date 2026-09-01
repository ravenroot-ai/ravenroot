# Ravenroot Kafka extension

`kafka.produce` produces one bounded record and `kafka.consume` hosts a long-lived consumer group
through Apache Kafka client 4.1.2. This is an optional
extension artifact: it is absent from the standard image and palette until an operator builds,
installs, and explicitly enables a plugin bundle containing `KafkaNodePackage`.

## Operator profile and security

GraphML carries only `clusterProfile` plus exact topic selection and tighter deadline, concurrency,
record-size, and correlation defaults. The tenant-scoped operator profile owns bootstrap servers,
DNS lookup, TLS/SASL, client id, credential reference, exact topic/header authority, partition and
timestamp permissions, compression, auto-creation, quotas and all producer durability settings.
Production endpoints require authenticated `SASL_SSL`, JVM trust validation and hostname checking;
plaintext is accepted only for an exact loopback bootstrap address. Ravenroot generates the JAAS
entry for PLAIN or SCRAM and accepts no raw JAAS, security protocol, serializer, interceptor, class
name, admin operation or arbitrary Kafka property from a graph. Environment resolver keys encode
tenant/profile/reference identifiers as injective UTF-8 hex values.

The profile value `RAVENROOT_KAFKA_PROFILE_<TENANT_HEX>_<PROFILE_HEX>` has these semicolon-separated
fields: `bootstrapServers;clientDnsLookup;tls;saslMechanism;username;credentialRef;clientId;defaultTopic;
additionalTopics;approvedHeaders;allowPartition;maxPartition;allowTimestamp;compression;acks;idempotence;
retries;maxInFlight;allowAutoCreate;maxConcurrency;maxPerSecond;timeoutMs;maxRecordBytes;bufferMemoryBytes`.
Credentials are resolved per producer creation from `RAVENROOT_KAFKA_CREDENTIAL_<REFERENCE_HEX>` and
mutable copies are erased on exit. The Kafka API internally requires a Java `String`, so erasure at
the client boundary is best effort.

Idempotence is fail-closed: only `acks=all`, `idempotence=true`, retries greater than zero and
`max.in.flight.requests.per.connection` from 1 through 5 are accepted. This prevents duplicates from
Kafka-client retries within that producer session and preserves per-partition ordering; it is not a
transaction, cross-session exactly-once guarantee or consumer-side deduplication. Ravenroot never
resubmits after `send` begins. Topic auto-creation is off unless the operator explicitly enables it;
the extension has no AdminClient or topology path.

## Payload and result

Input version is `kafka.produce.v1`. Exactly one of `valueText`, `valueJson`, or `valueBase64` is
required; at most one corresponding `key*` field is optional. Optional exact-authorized topic,
partition, timestamp, string headers and correlation id are validated and serialized to bounded byte
arrays before credentials or network access. Text keys, header names/values, correlation ids, and every
string value or map key in the original JSON object graph reject C0/C1 controls and malformed UTF-16
before JSON canonicalization. Fixed byte-array serializers are used.

Results are `ACKNOWLEDGED`, `REJECTED`, `RATE_LIMITED`, `TEMPORARY_FAILURE`, `PERMANENT_FAILURE`, or
`AMBIGUOUS`. Authentication, authorization, invalid configuration/serialization and an unknown topic
when auto-creation is disabled are terminal. Once send ownership begins, timeout or an uncertain
transport failure is ambiguous and must be reconciled by caller idempotency. Output contains only
sanitized topic, partition/offset/broker timestamp, serialized sizes, attempt count and correlation.

One monotonic deadline bounds client construction, send, callback, flush and close. Late-created
clients remain attempt-owned and are closed with zero wait after cancellation. A claimed producer's
admission lease transfers to tracked cleanup and is not released until the close worker has atomically
revoked client use and acknowledged that `close` has begun. If cleanup has not been scheduled when the
deadline expires, the result remains bounded but capacity stays unavailable; close may finish
asynchronously only after that handoff. Admission is bounded globally, per tenant, profile and action
before credentials/client creation. Every invocation owns one producer; there is no static cache or
pool. The deterministic tests exercise the injectable client seam; they do not claim a live Kafka or
Testcontainers run. A local broker example may use loopback SASL plaintext only; production must use
authenticated TLS.

Primary references: [producer configuration](https://kafka.apache.org/41/configuration/producer-configs/)
and [KafkaProducer API](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html).

## Long-lived `kafka.consume`

The consumer is created only when a deployment containing the node starts. Startup first resolves
and validates the tenant's opaque profile, probes that `TrustedIngress` supports durable receipts,
resolves the credential, creates exactly one poll-thread-owned `KafkaConsumer`, subscribes, joins the
group and completes readiness only after assignment. Stop, rollback and shutdown all close admission,
perform the configured bounded drain, commit only the safe frontier, call `wakeup`, leave the group
and close the consumer. Start and stop are single-flight and restart creates no duplicate poll loop.

The consumer profile environment value
`RAVENROOT_KAFKA_CONSUMER_PROFILE_<TENANT_HEX>_<PROFILE_HEX>` has these semicolon-separated fields:
`bootstrapServers;clientDnsLookup;tls;saslMechanism;username;credentialRef;clientId;groupLogicalName;
groupId;staticMemberId;topics;anchoredTopicPattern;approvedHeaders;assignmentStrategy;autoOffsetReset;
isolationLevel;startupTimeoutMs;pollTimeoutMs;maxPollIntervalMs;sessionTimeoutMs;heartbeatIntervalMs;
maxInFlight;maxFetchBytes;maxPartitionFetchBytes;maxRecordBytes;maxKeyBytes;maxValueBytes;maxHeaderBytes;
drainTimeoutMs;retryBackoffMs;maxRetryBackoffMs;poisonAttempts;poisonPolicy;deadLetterTopic`.
Exactly one of exact topics or an anchored pattern is profile-authorized. The profile owns the physical
group id, optional static member id, assignment strategy, reset policy, fetch/session/heartbeat bounds
and DLQ. GraphML can select only the opaque profile, its logical group name, an authorized topic subset
or the exact authorized pattern, and tighter bounds. Hidden conditional values are ignored unless
their mode is active. Raw bootstrap, group id, JAAS, deserializers, interceptors, arbitrary properties
and security weakening are never accepted from a graph.

The client always uses byte-array deserializers, `enable.auto.commit=false`,
`allow.auto.create.topics=false`, and `isolation.level=read_committed`; aborted transactional records
therefore remain hidden. `auto.offset.reset` is an explicit operator decision. Consumer liveness is
maintained by continuing to poll while assigned partitions are paused for bounded ingress pressure or
retry backoff.

Kafka's official PLAIN/SCRAM JAAS parser retains an immutable credential-bearing string for the
client lifetime, so erasure beyond the mutable resolver copy is not possible. The extension builds
that value directly from the erasable character array, immediately wraps it in Kafka's
password-masking configuration type, reuses one masked object for the consumer and optional DLQ
producer, and never logs client properties or raw client exceptions. Kafka configuration snapshots
render the value as `[hidden]`; a JVM memory dump with sufficient privilege remains able to recover
the live client's credential and must be protected as secret material.

Each record becomes an immutable bounded `kafka.record.v1` event containing topic, partition, offset,
timestamp/type, safe UTF-8-or-base64 key and value representations, allowlisted headers, serialized
sizes, leader epoch, logical group, attempt, correlation and source provenance. No `ConsumerRecord`,
client, commit callback or acknowledgement handle crosses into a traversal. Ordering is preserved per
partition; no ordering is claimed across partitions.

Offsets are manual and at-least-once. The durable idempotency key includes cluster profile, logical
group, topic, partition and offset; deployment/tenant/source scoping is added by `offerDurably`.
Only `DurablyCommitted` and `Duplicate` make a record safe. `Ambiguous` re-offers the same key and does
not commit; `VolatileCustody` fails the source. The consumer commits Kafka's next offset only after the
ordered safe frontier and never jumps over an earlier unsafe record. Rebalance revocation fences the
generation, drains within its bound and commits that frontier; lost ownership never commits. Kafka
committed offsets are the sole source position: Ravenroot's source checkpoint is a capability probe,
not a competing mirror.

After bounded retry, poison policy either halts the partition/degrades the deployment or writes the
original bytes and safe origin metadata to the operator-authorized DLQ and advances only after the DLQ
producer acknowledges. There is no silent skip and no exactly-once claim. Durable ingress receipts are
per process/store; Kafka group ownership provides multi-replica partition fencing, but effects remain
at-least-once. Ravenroot does not automatically resume a traversal interrupted mid-crash or quarantine
its partial effects, so downstream effects must be idempotent.

The deterministic tests exercise the byte consumer seam, assignment/readiness, ordering, legal offset
gaps, durable/ambiguous/volatile receipts, pause/resume, rebalance loss, cleanup, descriptor conditions,
GraphML secrecy and client hardening. A live broker/container suite is not bundled with this optional
module; operators should additionally run their Kafka ACL/TLS and group policy in their environment.

Consumer references: [consumer configuration](https://kafka.apache.org/41/configuration/consumer-configs/),
[KafkaConsumer API](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html),
and [consumer groups](https://kafka.apache.org/documentation/#consumerconfigs_group.id).
