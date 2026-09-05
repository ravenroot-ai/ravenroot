# Execution and actor model

Each accepted execution owns traversal state while actor messaging isolates dispatch, supervision, and control.

## Invariants

- An execution identifier is allocated at acceptance and remains the correlation key for state, result, and events.
- The graph runner dispatches only nodes made eligible by completed outcomes.
- Visited and evidence collections are unique sets; chronological reconstruction uses events.

## Runtime relationships

- A node actor contains one attempt and reports a classified result to its supervisor.
- Pause lets the in-flight node finish and then closes the dispatch gate; resume reopens it.
- Cancellation and completion race through named terminal outcomes so callers can distinguish the winner. Cancellation does not preempt a node computation already in flight: it refuses the next dispatch and releases a held pause gate or retry backoff, so effects already issued before the cancellation was observed stand.
- A cancelled traversal is recorded as `FAILED`, the same terminal status an ordinary fault produces, qualified by a distinct, nullable termination reason carried beside it rather than a third status value. A reader that inspects the status alone cannot tell the two apart.
- Each live traversal owns one monotonic budget shared by branches and cycle re-entry. Fan-out reserves
  every child delivery atomically before the first child is dispatched. A retry reserves its new
  traversal step, non-root amplification, and exact payload-plus-attribute bytes before its durable
  retry transition or second send.
- Demand-created worker and traversal actors, in-flight hops, and admission waiters have runtime
  ceilings below the adapters' emergency stash backstops. Dynamic actor capacity also remains charged
  at runner scope while an actor is retiring, so successive traversals cannot evade the limit by
  accumulating slow or non-terminating stops.

## Architectural consequence

Actor isolation turns node attempts into supervised messages while the execution aggregate remains the sole owner of traversal and terminal state.

## Related reading

- [Exact contract](../reference/execution-events.md)
- [Procedure or recovery](../user-guide/test-run-observe.md)
- [Decision record](../../adr/0035-cancellation-as-a-distinct-termination-reason.md)
