# Execution and actor model

Each accepted execution owns traversal state while actor messaging isolates dispatch, supervision, and control.

## Invariants

- An execution identifier is allocated at acceptance and remains the correlation key for state, result, and events.
- The graph runner dispatches only nodes made eligible by completed outcomes.
- Visited and evidence collections are unique sets; chronological reconstruction uses events.

## Runtime relationships

- A node actor contains one attempt and reports a classified result to its supervisor.
- Pause lets the in-flight node finish and then closes the dispatch gate; resume reopens it.
- Cancellation and completion race through named terminal outcomes so callers can distinguish the winner.
- Each live traversal owns one monotonic budget shared by branches and cycle re-entry. Fan-out reserves
  every child delivery atomically before the first child is dispatched.
- Demand-created worker and traversal actors, in-flight hops, and admission waiters have runtime
  ceilings below the adapters' emergency stash backstops.

## Architectural consequence

Actor isolation turns node attempts into supervised messages while the execution aggregate remains the sole owner of traversal and terminal state.

## Related reading

- [Exact contract](../reference/execution-events.md)
- [Procedure or recovery](../user-guide/test-run-observe.md)
