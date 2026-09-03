# Durable process inventory

A tenant-scoped, restart-surviving record of process instances and their traversals, read from the same rows the lifecycle already writes.

## Invariants

- The inventory is served from the authoritative `process_instance`, `traversal`, `invocation`, `attempt`, and `lease` rows the lifecycle transition writes inside one transaction; there is no projection and no repairable offset.
- Pagination orders by `(createdAt descending, processInstanceId descending)`. Both components are immutable per row, so a row cannot move between pages of an in-flight scan.
- A key belonging to another tenant is indistinguishable from one that does not exist, at every read path this inventory exposes.
- A recovery disposition — active, waiting, interrupted, parked, or terminal — is derived at read time under a total precedence with parked ranked highest, and is never itself a stored value.
- There is no durable "paused" disposition. Pause and resume act on process-local runtime state today, so a paused instance reports its stored lifecycle status through the inventory rather than a durable pause fact.

## Runtime relationships

- The live-execution view stays what it always was: process-local, in-memory bookkeeping of non-terminal executions, read directly from runtime state rather than from storage. The durable inventory is a separate, tenant-scoped authority read from the store, and it is what remains queryable after the process that ran the work has restarted. Neither view substitutes for the other, and a caller should not have to guess which one answers "what exists."
- A parked attempt outranks even a terminal disposition: an instance can finish while carrying an attempt whose real-world effect outcome was never learned, and the inventory reports that instance as needing operator attention rather than as having nothing left to do.
- A per-tenant retention floor accompanies every page, so an instance's absence can be read correctly: absence at or after the floor means the identifier does not name a real instance; absence before it means a terminal instance aged out of the configured retention window.
- Deployment, workload, and correlation identities are recorded on a row as annotations, not as part of the lifecycle: a write that supplies them sets them, and a write that does not leaves the stored values untouched. They are populated at the two admission paths that know them — transient submission and deployment-hosted ingress — each recording the correlation identity of the request that caused the instance, and deployment-hosted ingress additionally recording its own deployment and the traversal as the owning workload.
- A traversal admitted through the live request/reply ingress contract is not itself covered by this durable record: that ingress path is deliberately live and process-local, and opens no execution recorder of its own. The inventory should not be read as a complete log of every traversal a deployment has ever admitted; it is complete for the admission paths that write to the execution store.

## Architectural consequence

Because the disposition is computed rather than persisted, a durable pause state can be introduced later with no schema migration, no backfill, and no change to any row already written — the same freedom that keeps the inventory from ever needing to reconcile a stored classification against the lifecycle it describes. Recovery tooling can read this inventory to discover which instances look abandoned after a restart, but discovery is all a bare inventory row supports: it carries no attempt identity or fencing token, so acting on what it finds is a separate step.

## Related reading

- [Durability, events, and recovery](durability-events.md)
- [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md)
- [HTTP API and CLI](../reference/api-cli.md)
