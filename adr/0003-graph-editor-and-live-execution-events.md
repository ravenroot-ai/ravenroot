# ADR 0003: Graph editor and live execution events

- Status: Accepted
- Date: 2026-07-21
- Supersedes: Read-only graph viewing as the only browser workflow
- Superseded by: None
- Public references: [Workspace and graph authoring](../docs/user-guide/workspace-authoring.md), [GraphML profile](../docs/reference/graphml.md), [execution events](../docs/reference/execution-events.md)

## Context

The browser could render GraphML but could not submit an edited workflow through the same product
path used for execution. Runtime snapshots exposed aggregate counters and could not provide an
ordered, execution-correlated account of node activity.

## Decision

The UI edits and exchanges GraphML while preserving unknown graph properties and XML extensions.
Submission, validation, and execution go through the framework-neutral application API rather than
through a UI-specific runner. Live activity is exposed as ordered, execution-correlated events at
the server boundary and projected into editor state without introducing HTTP, SSE, or rendering
library types into the core.

## Consequences

- Editing and execution share the production graph-loading and runtime path.
- The UI can show node activity without treating a point-in-time counter snapshot as an event log.
- Transport reconnection, retention, and authorization remain server concerns; visual rendering
  remains a UI concern.
