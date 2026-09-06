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
- A traversal that can no longer reach any outcome — nothing running on its behalf, no join deadline armed, and a branch parked at a fan-in waiting for an arrival that can never come — is identified from that positive evidence rather than from elapsed time, and is ended by an explicit reconciliation rather than left open. Cancellation cannot recover this state and is not a substitute for it: a stop refuses the traversal's next hop, and this traversal has none. Reconciliation ends it through the same release every traversal performs, so the admission capacity it was holding is returned exactly once, and it is recorded as `FAILED` qualified by its own termination reason. Unlike a cancellation this is a fault and stays in the failure series; the reason is what says that no node broke. Where a stop was already asked for, the traversal is ended as that cancellation instead — by reconciliation and by the forced teardown alike, since a cooperative stop has no next hop to refuse on such a traversal and the teardown is the half an operator's cancel reaches. An operator's deliberate act is therefore not reported back as an incident. A shutdown that catches a traversal nobody stopped is not a cancellation.
- Reconciliation is caller-invoked and is never a background sweep. A sweep would need a period, and a period over this condition would reintroduce exactly the elapsed-time guess the criterion exists to avoid.
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
- [Decision record](https://github.com/ravenroot-ai/ravenroot/blob/dev/adr/0035-cancellation-as-a-distinct-termination-reason.md)
