# Explicit business context operations

START and SOURCE entries establish one immutable application context for each actual traversal.
Ordinary transforms can replace the payload without changing that context. The business identity,
transport message identity, process identity, traversal identity, node visit, and attempt remain
separate values.

## Preview a mapping in the editor

In Edit mode, select START, a SOURCE, or a **Derive business context** node. Its catalog controls
declare the application ID policy and explicit JSON Pointers. START reuses the same mapping schema;
SOURCE and derive controls retain the shared mapping when switching between those catalog types.
Catalog selector bounds come from the running application's configured correlation selection policy.

Enter the actual exposed input shape in **Sample input (JSON)** and choose **Preview application
context**. The editor sends the current unsaved mapping and that sample to the same bounded selector
implementation used by ingress. Missing, null, non-scalar, oversized, or conflicting selections show
the declared reason and configured selector. The preview does not fetch stored content, create a
business history, admit START, or activate a SOURCE. `REQUIRE_EXISTING` still needs an authorized open
history at actual admission; a valid sample alone cannot establish that authority.

The sample editor accepts at most 65,536 UTF-8 bytes and the service independently applies its normal
configured input limits. The sample stays in the current inspector and is not saved into GraphML.
Editing a selector or sample clears its old result. Switching documents or authority retires pending
results. A generated zero UUID is an illustrative preview placeholder, never a real execution ID.
The embedded viewer remains read-only and shows the resulting retained context and audit lineage
through the same history projections as native Monitoring.

## Close a business history

Add a **Close business history** (`correlation-close`) node with a literal, non-secret `auditReason`
(up to 500 characters). Choose the requested `disposition`: `COMPLETED`, `FAILED`, or `CANCELLED`.
The node continues through its ordinary authored edges in the original traversal.

The actual close visit and its signal commit together under that process's current lease. From that
commit onward, new external START or SOURCE admissions for the business identity are refused.
Previously admitted causal work may finish. A join that becomes READY from those arrivals may admit
its exact persisted continuation while the close is pending. A continuation cause label by itself
cannot grant this exception: the scope, node, round, process, and traversal must match the READY outbox.

The history stays open while admitted work runs or a READY continuation awaits acknowledgement.
A quiet history with such a pending continuation reports `CLOSING`. It settles only after those
obligations settle. A requested successful close becomes `FAILED` when an original traversal failed
or was cancelled, or when an open join still lacks contributions. Missing members, received members,
and the requested disposition remain available for inspection; missing collection also marks the
history incomplete. Zero running traversals alone never establishes business success.

The bounded signal retains its authored reason and original process, traversal, visit, attempt, and
store time. It contains no event payload copy. Closing an old uncorrelated traversal fails explicitly;
it does not invent a business identity for that record.

## Derive a context from transformed input

Use an ordinary transform to compute structured data, then add **Derive business context**
(`correlation-derive`). Configure the shared `correlation.*` selectors and an `auditReason`. Preview
those selectors against a sample with the ordinary correlation preview API or CLI before running.
The same bounded selector implementation processes the actual transformed input.

The derive node requires exactly one authored `continue` successor. That successor starts a distinct
process and traversal under the computed context; it is not invoked as a child visit of the original
traversal. The original traversal ends that branch at the derive node and keeps its original context.
The new business identity must differ from the original. START and SOURCE cannot be derivation targets, and
an ordinary traversal-scoped join cannot be entered through this boundary.

The original completed derive visit and the new entry's retained input, attributes, command, scoped
context, and causal lineage commit in one transaction. A failed admission rolls back the completed
marker too. Deterministic child identities reconcile a lost commit response or retry into the same
child. `REQUIRE_EXISTING` requires the computed business history to exist in the exact tenant,
logical deployment, and graph version. Generated identities use a separate domain from process,
traversal, and transport identities.

A deployment recovery pass dispatches an unstarted retained child without requiring the producer to
resend the event. A child whose real attempt already began remains under the existing recovery or
park authority; activation does not replay that effect or infer completion. Expired custody settles
visibly before dispatching a real target. Input and attribute sizes, retained entry count, and retained
entry bytes remain bounded by operator policy. These custody records are independent of optional
activity capture.

Traversal metadata retains the original deriving process, traversal, visit, attempt, and authored
reason after terminal custody input is retired. History APIs expose this scalar `derivation`, and
business summaries expose their nullable `closureSignal`. The ordinary and proof-protected embed
history routes use the same projections and authorization boundaries.

## Runtime and storage verification

The runtime tests execute an ordinary transform followed by an explicit derive operation, a
pre-close blocked arrival followed by its real join continuation, a committed handoff with a lost
response and reopened SQLite store, an expired child before effects, and close/derive against actual
legacy uncorrelated traversal records. Store contracts execute atomic admission rollback, duplicate
handoff, byte refusal, original causal lineage, retained closure, and missing-member settlement on
SQLite and PostgreSQL. Memory implements the same contract within one process; its durable-reopen
case is explicitly skipped.

A join may explicitly trust a pinned derivation entry as an expectation producer. The configured
entry must be the single target of an enabled authored derive operation; naming an arbitrary worker
does not grant that authority. Payload fields and event attributes cannot grant producer authority.

## Propagate context through an external service

The `http-request`, `amqp.publish.v1`, and `mail.send.v1` catalog types expose five optional
outgoing properties. Configure literal dedicated headers, for example:

| Property | Example target | Established value |
| --- | --- | --- |
| `correlation.outgoing.applicationHeader` | `X-Business-Operation` | Application correlation ID |
| `correlation.outgoing.contributionHeader` | `X-Business-Part` | Contribution ID, when present |
| `correlation.outgoing.kindHeader` | `X-Business-Kind` | Contribution kind, when present |
| `correlation.outgoing.roundHeader` | `X-Business-Round` | Explicit join round |
| `correlation.outgoing.referenceHeader` | `X-Business-Reference` | Public reference, when present |

Blank disables that field. A configured mapping requires established typed traversal context;
legacy uncorrelated input fails explicitly instead of interpreting a payload or attribute named
`applicationCorrelationId`. Optional absent values omit their target header. An existing target
whose optional value is absent refuses, because its relationship to the context cannot be verified.

Targets use portable `X-*` names of at most 64 characters. Names are unique without regard to case.
The runtime reserves `X-Ravenroot-*`, `X-Correlation-ID`, `X-Request-ID`, `X-Message-ID`, `X-Reply-To`,
and `X-Idempotency-Key`; HTTP also reserves the configured credential header. Dedicated business
headers leave HTTP request IDs, credentials and saga headers, AMQP `messageId`, `correlationId` and
`replyTo`, and generated SMTP `Message-ID` in their existing identity domains. In particular, mail's
payload `correlationId` does not automatically become an SMTP header.

Mapped values must contain printable ASCII characters. A valid Unicode business identity that
cannot be represented in this portable header contract fails explicitly; the runtime does not
rewrite an opaque identity. Existing native header length, count and envelope limits still apply.
AMQP and mail operator profiles must independently authorize every target header. Mapping never
expands a profile or HTTP destination/tool policy. Existing equal target values reconcile; conflicting
values and case-insensitive duplicate effective headers refuse before external I/O. Mail reports its
existing `INVALID_INPUT`, AMQP reports `REJECTED`, and HTTP returns a failed node stage.

An external processor must deliberately return those business headers. At an AMQP SOURCE the real
`amqp.delivery.v1` event exposes approved headers directly under `headers`, so configure
`correlation.selector=/headers/X-Business-Operation`,
`correlation.contributionSelector=/headers/X-Business-Part`, and
`correlation.roundSelector=/headers/X-Business-Round`. The incoming operator consumer policy must
allow those exact case-sensitive AMQP names. The event's root `messageId`, `correlationId`, and
`replyTo` remain transport fields; none automatically establishes business identity. The body is an
explicit representation under `body`, such as `{ "format": "utf8", "text": "..." }`.

At an OpenAPI SOURCE, declare each header as a header parameter in the pinned OpenAPI document and
allow it in the operator inbound profile. The real asynchronous `openapi.request.v1` and request/reply `openapi.request.v2` events expose their declared
header parameters under root `headers`; use the actual declared parameter key in the JSON Pointer.
The root `requestReply` object, when present, belongs to the request/reply waiter. Mapping a business
header does not replace that waiter or its process/traversal/source generation coordinates.
`REQUIRE_EXISTING` still requires an open history in the exact authorized tenant, deployment and
pinned graph version; selector preview alone cannot grant that authority.

Governed saga AMQP work commits a frozen participant envelope before dispatch instead of running
the inline publisher. Its optional `applicationContext` contains only the five bounded context
scalars (`applicationCorrelationId`, `contributionId`, `contributionKind`, `roundId`, `publicReference`);
absent optional fields are JSON null, and legacy uncorrelated envelopes omit the object. The committed
payload, authored properties, original causal IDs and context are covered by the intent digest.
A host-supplied `SagaCommandTransport` must decode this actual committed envelope and apply its
explicit mapping through the same approved connector authority. Frozen context preserves causal
identity; it never supplies current credentials or replaces `SagaCommandAuthority`. Current authority
is revalidated before dispatch. A broker acknowledgement is distinct from business completion.
Live compensation and approval continuation retain the current traversal's typed context even when
stored payloads or attributes contain stale or conflicting identity-like text.

The connector regressions exercise an actual RabbitMQ publisher-to-SOURCE round trip with separate
message/waiter IDs and an actual SMTP DATA exchange with generated Message-ID. Separate SQLite saga
regressions commit, reopen, decode and dispatch the frozen envelope under allowed and denied current
authority. The latter use a host transport fixture and do not claim a broker exchange.
