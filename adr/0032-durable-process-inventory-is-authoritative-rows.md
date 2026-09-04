# ADR 0032: The durable process inventory is authoritative rows, not a projection

- Status: Superseded in part
- Date: 2026-09-03
- Supersedes: Discovering process instances and traversals only through the process-local live-execution view, with no tenant-scoped record surviving a restart
- Superseded by: [ADR 0033](0033-durable-operator-holds.md) for the claim that no durable pause state exists in the product
- Public references: [Durable process inventory](../docs/architecture/process-inventory.md), [Persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md), [HTTP API and CLI](../docs/reference/api-cli.md), [ADR 0007](0007-process-traversal-invocation-attempt-lifecycle.md), [ADR 0022](0022-ambiguous-work-is-parked.md)

## Context

Ravenroot could list only process-local live executions. After a restart, an authorized tenant had
no way to discover which process instances existed, which of their traversals needed observation,
control, or recovery, or which attempts were left parked under ADR 0022, other than already knowing
their identifiers. ADR 0007 supplies the identity levels the answer has to be keyed on — process
instance, traversal, invocation, attempt — but names no durable, listable record of them.

The requirement permitted either a read-model projection with its own offset, rebuildable from the
journal, or serving the listing from the aggregate rows the lifecycle transition already writes.
It also left open whether the recovery classification a caller needs — is this instance active,
waiting on purpose, apparently abandoned, or carrying unresolved work — should be a stored fact or
computed from state already held.

## Decision

The inventory is served by reading the existing `process_instance`, `traversal`, `invocation`,
`attempt`, and `lease` rows that the lifecycle transition already writes inside one transaction.
There is no second copy of the lifecycle, no projection offset, and therefore no rebuild path that
could invent successful work that never happened. A malformed row surfaces as a corruption failure
rather than being silently dropped from a listing or misread as well-formed.

A projection with a repairable, resettable offset was rejected even though the requirement allowed
it. A rebuild path is precisely a mechanism that can manufacture a row describing work that was
never actually done, and it is a second record of the lifecycle that has to be kept consistent with
the first — a consistency obligation the chosen design has no reason to carry.

The recovery classification reported alongside each row — active, waiting, interrupted, parked, or
terminal, under a total precedence with parked ranked highest because an unresolved external effect
outranks even a finished instance — is derived at read time from the stored status, whether an
unexpired lease exists, and whether any attempt is parked. It is deliberately not a stored column.
Storing it would create a second copy of the lifecycle that can disagree with the first, most
sharply at a lease's silent expiry: expiry is the passage of time rather than a write, so there is no
transaction in which a stored classification could have been corrected to match. There is
deliberately no durable "paused" value in that classification, and the reason given here at the time
— that no durable pause state exists in the product — is no longer true. [ADR 0033](0033-durable-operator-holds.md)
made an operator hold durable, and the conclusion survived it unchanged: a held traversal is stored
as `WAITING`, the same lifecycle value every other durable wait writes, so the derived classification
reports `WAITING` and the hold record beside the instance says which wait it is. Adding a `PAUSED`
rank would have been exactly the kind of value a derived answer must not invent — a second copy of a
fact that is already stored somewhere authoritative.

Pagination orders by `(createdAt descending, processInstanceId descending)`. Both components are
immutable for the life of a row, so a row cannot move between pages while a scan is in flight; work
created after a scan starts sorts before page one and is simply not seen by that scan, which is the
honest behavior of a stable cursor rather than a gap. A key belonging to another tenant is
indistinguishable from a missing one, following the rule ADR 0007's identity model already implies
for every store lookup. An over-limit page and a self-contradictory status filter are rejected
outright rather than answered with an empty page, because an empty page that means "the request was
wrong" is indistinguishable from one that means "there is none."

## Consequences

- An authorized tenant can rediscover its non-terminal work, and its terminal work within the
  retention window, after a restart, from the same rows the lifecycle already committed — with no
  rebuild delay and no window in which the inventory is known to lag the lifecycle.
- Because the recovery classification is derived rather than stored, a durable pause state can be
  added later with no schema migration, no backfill, and no change to any row already written. That
  is the specific advantage this decision buys in exchange for computing the classification on every
  read instead of storing it once.
- Until that later work lands, a process instance paused through process-local runtime state reports
  whatever its stored lifecycle status is — typically active — through the inventory. Durable
  discoverability of a paused instance as paused is not a capability of this decision, and this record
  must not be read as claiming it is.
- Terminal retention may not be configured shorter than event-journal retention, so a durable
  instance can never be pruned while its own events are still readable; a terminal row is retained a
  minimum of as long as the events that describe it.
- A stable, deterministic scan trades immediacy for correctness: an operator watching a long scan
  will not see instances created after the scan began until they ask again from page one.
- This decision defines the retention-removal operation but does not by itself expose it: no shipped
  surface calls it in this release, a deliberate scoping choice because permanently deleting terminal
  execution records needs its own confirmation posture. Until a later change exposes it, every
  terminal row is retained regardless of age in practice, and the terminal-retention window above
  binds configuration without yet removing anything.
