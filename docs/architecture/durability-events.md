# Durability, events, and recovery

Durability connects admission, state changes, event ordering, result retention, and restart recovery without treating SSE as storage.

## Invariants

- Accepted durable work is recoverable from the configured store; a live connection is never the sole record.
- Event cursors are monotonic within their stream contract and retained replay is returned in ascending order.
- A cursor older than retained history produces an explicit gap rather than a fabricated continuous stream.

## Runtime relationships

- The durable journal is authoritative for replay; the ring buffer serves bounded recent delivery.
- Terminal results have a retention boundary independent from event consumption.
- Drain stops new admission, allows accepted work to reach its defined boundary, and makes shutdown observable through readiness.
- The durable process inventory is a separate, tenant-scoped authority over which process instances and traversals exist, read from the same rows the lifecycle writes; it answers "what exists and in what recovery state" where the journal answers "what happened."
- A cancelled execution is durably recorded as `FAILED`, with a nullable termination reason of `CANCELLED` carried beside it rather than a new status value, so the two status enums remain an unchanged state machine and every existing exhaustive switch over them still compiles. The schema change that carries the reason is still a one-way migration like every other in this store — a binary predating it cannot open the upgraded database at all, the same cost every prior durable change here already pays. The event stream separately publishes a dedicated terminal event, `EXECUTION_CANCELLED`, because the event-type label is what an external metric or dashboard keys on.

## Architectural consequence

Recovery reconstructs accepted work from durable authority; live delivery accelerates observation but never substitutes for stored truth.

## Related reading

- [Exact contract](../reference/execution-events.md)
- [Procedure or recovery](../troubleshooting/events-persistence.md)
- [Durable process inventory](process-inventory.md)
- [Decision record](../../adr/0035-cancellation-as-a-distinct-termination-reason.md)
