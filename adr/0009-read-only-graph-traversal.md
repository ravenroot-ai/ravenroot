# ADR 0009: Read-only graph traversal and controlled definition change

- Status: Accepted
- Date: 2026-08-12
- Supersedes: Exposing mutable traversal access to an authoritative graph
- Superseded by: None
- Public references: [Graph and GraphML semantics](../docs/architecture/graph-semantics.md), [GraphML profile](../docs/reference/graphml.md), [ADR 0008](0008-graph-definition-versioning-and-lifecycle.md)

## Context

A traversal returned against the graph manager's own graph could include mutating Gremlin steps.
The mutation completed and remained visible, contradicting the promised read-only query boundary and
allowing topology to change outside definition lifecycle controls.

## Decision

General graph traversal is read-only. Mutating traversal steps are rejected before they can affect
the authoritative graph. Definition changes use the explicit versioned lifecycle established by ADR
0008; they are not performed through a query escape hatch.

## Consequences

- Query callers cannot mutate the graph accidentally or deliberately through traversal syntax.
- All sanctioned definition changes have identity, validation, and lifecycle evidence.
- Specialized operations that genuinely change a definition must be explicit APIs rather than
  undocumented traversal conventions.
