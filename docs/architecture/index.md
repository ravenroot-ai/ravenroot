# Architecture and concepts

Understand the invariants connecting graph semantics, actor execution, durability, AI, and deployment control.

## Reading path

- [Product boundaries and authority](product-boundaries.md) — Separate graph authorship, application invocation, integration, and operator authority.
- [Graph and GraphML semantics](graph-semantics.md) — Follow a graph from authoritative bytes through validation, import, execution, and exact export.
- [Execution and actor model](execution-actor-model.md) — Relate traversal state, node dispatch, supervision, pause, cancellation, and terminal evidence.
- [Durability, events, and recovery](durability-events.md) — See how accepted work, journaled events, retained results, gaps, and recovery fit together.
- [Durable process inventory](process-inventory.md) — Distinguish the process-local live-execution view from the durable, tenant-scoped inventory that survives a restart, and how its recovery disposition is derived.
- [AI, programs, and extension boundaries](ai-extension-boundaries.md) — Distinguish declarative graph intent from privileged adapters, tools, credentials, and code execution.
- [Embedded projection security model](embed-security-model.md) — Trace operator attestations and short-lived sessions into a restricted read-only projection.

## Authority boundary

Architecture pages own meanings and invariants. They link to Reference for exact fields and to audience guides for actions, avoiding a second copy of either contract.
