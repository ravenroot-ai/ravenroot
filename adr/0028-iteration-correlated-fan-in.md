# ADR 0028: Iteration-correlated fan-in

- Status: Accepted
- Date: 2026-08-29
- Supersedes: Treating all later arrivals at a cyclic join as late arrivals from its first completion
- Superseded by: None
- Public references: [Execution and actor model](../docs/architecture/execution-actor-model.md), [payloads, outcomes, and routing](../docs/user-guide/payload-routing.md)

## Context

A join on a cycle completed once and then classified every arrival on a later lap as late. The graph
could report successful completion while silently skipping the downstream work on subsequent
iterations.

## Decision

Fan-in arrivals correlate by an engine-owned iteration identity. A satisfied join closes only that
iteration and re-arms for the next one. The iteration identity follows routing causality and is not
part of user payload or author-controlled graph properties. Stored and observable join state records
the correlation needed to distinguish laps.

Arrivals within an iteration still obey the declared `all` or `any` semantics. Late or duplicate
arrivals are judged against their own iteration rather than against a lifetime terminal flag on the
logical node.

## Consequences

- Cyclic graphs can revisit a join without silent truncation.
- Persistence readers must interpret join state with its iteration correlation.
- User code cannot forge or accidentally drop the engine's iteration identity.
