# ADR 0022: Ambiguous work is parked

- Status: Accepted
- Date: 2026-08-12
- Supersedes: Retrying crashed in-flight effects as though they had never started
- Superseded by: None
- Public references: [Durability, events, and recovery](../docs/architecture/durability-events.md), [persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md)

## Context

After a crash, scheduled work is known not to have started, but work persisted as running may have
performed an external effect before its outcome was recorded. Treating both states as retryable can
repeat a non-idempotent effect; treating both as complete can lose work.

## Decision

The store records intention before dispatch. Recovery may redispatch work still known to be
scheduled. Work found running has an unknown outcome and is parked unless the node declaration says
the effect is repeatable and the recovery policy authorizes another attempt. The repeatability
declaration is validated catalog data, not an arbitrary reserved graph property.

Parking is a truthful recovery status, distinct from an operator pausing a known execution. Adapters
must return crashed running work to recovery so the shared policy can classify it; they must not
filter it out as though recovery were complete.

## Consequences

- Ravenroot does not silently convert uncertainty into success or automatic repetition.
- Authors must declare repeatability where recovery may safely repeat an effect.
- Operators can inspect and resolve ambiguous work without corrupting its recorded state.
- Supporting design evidence is provenance only and is not a second ADR.
