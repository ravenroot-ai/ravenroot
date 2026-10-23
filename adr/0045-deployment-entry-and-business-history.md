# ADR 0045: Deployment entry and durable business history

- Status: Accepted
- Date: 2026-10-23

## Context

An activated graph can contain START work and multiple independent listeners. Re-entering START for
every event misrepresents topology and repeats unrelated effects. Process/traversal identities and
transport correlation fields also cannot represent one application operation across independent
callbacks or prove that all expected results arrived.

## Decision

Activation owns SOURCE lifecycle independently of graph reachability. Deploy admits no START work;
one permanent deployment marker arbitrates initial START and reconciles distinct client requests to
the original process/traversal/input. The editor persists origin-document intent before network
mutation and reconciles stable durable registration identity separately from rotating incarnation.
Explicit restore uses verified retained canonical graph custody and current lifecycle authority.

An accepted event enters at its exact activated SOURCE, emits through `continue`, and preserves its
original input. An incoming graph edge records a SOURCE boundary and stops that branch. Ownership,
generation, admission and policy checks remain runtime-controlled. Durable source input and scoped
business admission commit before a committed receipt. Recovery retains original causal identity,
deadlines and consumed budgets; it resumes missing dispatch, never silently repeats an ambiguous
real effect. Optional activity capture is not input custody.

Business identity is an immutable typed context scoped by tenant, logical deployment and exact
version. Explicit bounded JSON Pointers establish it at START/SOURCE admission; payload replacement
does not mutate it. Audited derivation commits a separate child context and causal lineage.
Dedicated outgoing business headers preserve protocol-owned identity slots. Explicit stable consumer
IDs remain transport streams across replacement deployments; an old receipt keeps its original
business/dispatch owner and cannot be reinterpreted in the successor scope.

Cross-traversal joins use application ID, node and explicit round. Distinct contribution IDs,
bounded retained input, trusted expectation producers, a monotone manifest/seal and transactional
READY outbox decide completeness. One deterministic continuation has its own process/traversal
identity while retaining original arrival causes. Existing branch/iteration joins retain their
traversal-local semantics. Quiet open histories remain waiting; explicit close refuses new external
admissions while prior causal obligations settle and cannot erase failure or missing-result facts.

Ordinary SDK/HTTP/CLI and proof-protected embedded reads project the same bounded metadata. Keyset
pages and exact server search precede serialization; payload capture is a separate authorized read.
Monitoring repeats the original pinned topology for actual traversals with lazy bounded previews and
a persistent visit/attempt inspector. Ordering supports navigation without inventing external edges
or causal sequence.

## Consequences

SQLite schema 41 and PostgreSQL schema 21 add the durable markers, relationships, join outbox,
custody and normalized history summaries. Old uncorrelated records remain readable with explicit
missing-data states. Upgrade is additive; downgrade requires restoration of a verified pre-upgrade
backup and reconciliation of work accepted afterward. Initial START repetition and source-edge
behavior are deliberate public contract changes.

Finite input, history, join, deadline, response and rendering budgets are operator-owned. Transport
acknowledgement is not business completion, and this design does not promise exactly once external
effects across ambiguous failures. See the [identity glossary](../docs/reference/execution-identity.md),
[SOURCE contract](../docs/reference/source-entry-contract.md),
[business operations](../docs/reference/business-correlation-operations.md) and
[migration guide](../docs/operator-guide/correlation-migration.md).
