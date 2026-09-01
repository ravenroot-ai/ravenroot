# ADR 0030: Zero or more independently routed node emissions

- Status: Accepted contract; not implemented
- Date: 2026-08-30
- Supersedes: The assumption that every node attempt produces exactly one routed payload
- Superseded by: None
- Public references: [Graph and GraphML semantics](../docs/architecture/graph-semantics.md), [payloads, outcomes, and routing](../docs/user-guide/payload-routing.md), [ADR 0028](0028-iteration-correlated-fan-in.md)

## Context

A splitter may need to produce several payloads for different routes, or no payload at all. Encoding
that multiplicity inside one payload moves routing logic out of the graph and into downstream node
code. The result contract is a public SPI, so accepting the architecture does not authorize a binary
contract change.

## Decision

One node attempt may produce an ordered list of zero or more emissions. Each emission carries its
own payload, attributes, outcome, and routing evaluation. List order is observable; dispatch
completion order is not. A bounded per-attempt ceiling rejects the whole attempt when exceeded
rather than silently truncating data.

Emission identity is derived from attempt identity and list position. Retry repeats the attempt as a
unit. Completion is settled once before routing, while liveness accounts once for the union of
selected targets. A partial routing failure is explicit and cannot rewrite an already settled node
result. Redaction applies per emission. Persistence retains one completion event per attempt and the
exactly-one case remains representation-compatible. Multiple emissions to the same logical successor
are refused until child-invocation identity makes that case unambiguous.

## Consequences

- Graphs can express splitters without hiding routing inside payload conventions.
- Bounds and all-or-nothing admission prevent silent data loss.
- Existing exactly-one behavior remains the compatibility baseline.
- Implementation requires separate approval and compatibility evidence because `NodeResult` is a
  public SPI; this ADR alone does not change code.
