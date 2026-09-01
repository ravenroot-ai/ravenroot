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

## Architectural consequence

Recovery reconstructs accepted work from durable authority; live delivery accelerates observation but never substitutes for stored truth.

## Related reading

- [Exact contract](../reference/execution-events.md)
- [Procedure or recovery](../troubleshooting/events-persistence.md)
