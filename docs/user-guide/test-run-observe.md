# Test, Run, and execution control

Choose execution intent explicitly and use state plus event evidence to control a live traversal.

## Procedure

1. Use **Test** first: it is the UI default and bypasses behavior effects while retaining traversal evidence.
2. Use **Run** only after reviewing the graph’s connectors, model calls, agent tools, and programs; the UI asks for effect confirmation.
3. Pause when new dispatch must stop after the active node; resume to reopen dispatch; cancel when the execution should reach a cancellation outcome.
4. Read the execution resource for terminal sets and the event stream for chronological changes.

## Authority boundary

The user may request execution controls for resources they own. Readiness, drain, adapter availability, and global policy remain operator controls.

## Verification

Correlate the submission ID with one terminal resource and its ordered events; confirm Test bypass evidence differs from the reviewed Run.

- [Reference contract](../reference/execution-events.md)
- [Concept or recovery](../troubleshooting/graph-execution.md)
