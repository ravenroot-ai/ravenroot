# Events and persistence

Protect the last safe cursor and durable store before reconciling gaps, expired results, or restart recovery.

## Recent events report a retention gap

**Diagnosis:** The requested cursor is older than the durable or ring history still retained.

**Action:** Stop incremental application. Fetch the execution resource or another authoritative snapshot, replace derived state, persist the newest safe cursor, and resume after it.

**Verify:** Confirm that subsequent events are ascending and that no state was inferred across the declared gap.

## An execution result is no longer available

**Diagnosis:** Terminal-result retention expired even though the execution once completed.

**Action:** Use audit or durable event records allowed by retention policy; do not fabricate a result from an incomplete client cache. Adjust retention only through operator configuration.

**Verify:** Execute a new bounded Test and verify its result remains retrievable for the configured interval.

## Restart recovery does not become ready

**Diagnosis:** The store is unavailable, incompatible, locked, or contains state that recovery cannot reconcile.

**Action:** Keep traffic closed. Inspect storage diagnostics, restore connectivity or use a verified backup in isolation; never delete durable state to force readiness.

**Verify:** Require recovery completion, `/ready`, execution reconciliation, and cursor replay before promotion.

## Related contracts

- [Primary contract](../architecture/durability-events.md)
- [Control procedure](../operator-guide/persistence-lifecycle.md)
