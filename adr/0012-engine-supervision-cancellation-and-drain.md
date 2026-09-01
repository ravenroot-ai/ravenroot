# ADR 0012: Engine supervision, cancellation, and drain

- Status: Accepted
- Date: 2026-08-02
- Supersedes: Adapter-specific, incompletely observable node lifecycle behavior
- Superseded by: None
- Public references: [Execution and actor model](../docs/architecture/execution-actor-model.md), [Test, Run, and execution control](../docs/user-guide/test-run-observe.md)

## Context

The execution-engine SPI could spawn, send, stop, schedule, and close, but it did not define bounded
cancellation, engine-wide drain, observable node state, or exactly-once settlement of accepted
messages. Separate adapter implementations could therefore drift at lifecycle races.

## Decision

A node moves monotonically through `RUNNING`, `DRAINING` or `CANCELLING`, and `TERMINATED`.
`stop` refuses new work and lets accepted work finish; `cancel` refuses new work and settles accepted
work immediately at the contract boundary, while user code cooperates through its cancellation
signal. Every accepted message settles once and the first settlement wins.

Invocation failures resume the node rather than making one traversal terminate a shared graph
vertex. Recoverable `RuntimeException` and `Error` values are reported through the invocation;
`VirtualMachineError` remains fatal. Engine drain refuses new spawns and drains nodes before close;
bounded shutdown may escalate from stop to cancellation. Live status and a bounded history of recent
terminal status are shared semantics implemented by both adapters.

## Consequences

- Supported adapters must pass the same lifecycle and race conformance suite.
- Cancellation is prompt for callers but does not claim unsafe JVM preemption of arbitrary code.
- Very old terminal references may become unknown after bounded history eviction.
- Graph shutdown has a defined escalation path instead of waiting forever for one node.
