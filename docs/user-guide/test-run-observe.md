# Test, Run, and execution control

Choose execution intent explicitly and use state plus event evidence to control a live traversal.

## Procedure

1. Use **Test** first: it is the UI default and bypasses behavior effects while retaining traversal evidence. Test executes an immutable GraphML snapshot and keeps monitoring in the originating editor document, so its identity, edits, selection, and viewport stay in place.
2. Use **Run** only after reviewing the graph’s connectors, model calls, agent tools, and programs; the UI asks for effect confirmation. Run also executes a captured snapshot and reports the result in its originating document.
3. Pause when new dispatch must stop after the active node; resume to reopen dispatch; cancel when the execution should reach a cancellation outcome. A cancelled execution reports `status=FAILED` alongside `terminationReason=CANCELLED` — read the two together, since the status by itself reads the same as an ordinary failure.
4. Read the execution resource for terminal sets and the event stream for chronological changes.

## Authority boundary

The user may request execution controls for resources they own. Readiness, drain, adapter availability, and global policy remain operator controls.

## Verification

Correlate the submission ID with one terminal resource and its ordered events; confirm Test bypass evidence differs from the reviewed Run.

Reload still restores a previously persisted Test or deployed snapshot and its provenance, but it does not reattach
the old execution, event stream, or runtime session. Reconcile live runtime state from the service.

- [Reference contract](../reference/execution-events.md)
- [Concept or recovery](../troubleshooting/graph-execution.md)
