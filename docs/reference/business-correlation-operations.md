# Explicit business context operations

START and SOURCE entries establish one immutable application context for each actual traversal.
Ordinary transforms can replace the payload without changing that context. The business identity,
transport message identity, process identity, traversal identity, node visit, and attempt remain
separate values.

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
