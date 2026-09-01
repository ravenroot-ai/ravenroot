# ADR 0008: Graph-definition versioning and lifecycle

- Status: Accepted
- Date: 2026-08-12
- Supersedes: Executing directly from a mutable graph definition with no independent identity
- Superseded by: None
- Public references: [Graph and GraphML semantics](../docs/architecture/graph-semantics.md), [GraphML profile](../docs/reference/graphml.md), [persistence and lifecycle](../docs/operator-guide/persistence-lifecycle.md)

## Context

A run previously retained a graph manager and could observe topology changes while it was executing.
Graph bytes, logical identity, lifecycle state, and the version selected for a run were not separate
concepts.

## Decision

A graph definition has a stable logical identity, a revision identity derived from a canonical
semantic form, and an explicit lifecycle. Validation, publication, activation, retirement, and
rollback are distinct operations. An execution is pinned to one immutable revision and continues to
use it even if another revision becomes active.

The existing GraphML `graphVersion` meaning is not redefined; definition identity and revision are
additive product concepts.

## Consequences

- A running traversal cannot silently change topology.
- Definition changes become reviewable lifecycle events rather than mutations of live state.
- Canonicalization and hashing must preserve semantic equality while keeping transport details out
  of identity.
