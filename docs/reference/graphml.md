# GraphML profile

This reference defines the accepted executable GraphML surface. Validation is syntactic and semantic: valid XML can still be refused when it violates the execution profile.

## Document envelope

| Contract | Requirement |
|---|---|
| Namespace | `http://graphml.graphdrawing.org/xmlns` |
| Graph count | Exactly one top-level `graph` |
| Edge mode | `edgedefault="directed"` |
| Key placement | All `key` declarations precede `graph` |
| Node identity | Every node has a non-empty unique `id` |
| Edge identity | Optional; an absent ID receives a deterministic import-only handle |
| Excluded XML | DTDs, entities, and external references |
| Excluded GraphML | nested graphs, `hyperedge`, `port`, and `locator` |
| Compression | Compressed input is not accepted |

## Keys and scalar values

A key declares `id`, `for`, `attr.name`, and `attr.type`. Accepted scalar types are `boolean`, `int`, `long`, `float`, `double`, and `string`. Scope determines whether a key applies to nodes, edges, graphs, or all elements. Duplicate effective property names in one scope are refused.

The runtime interprets these effective names:

| Property | Element | Meaning |
|---|---|---|
| `kind` | node | `START`, `END`, `ERROR`, `BEHAVIOR`, or `PASSTHROUGH` |
| `behavior` | node | Catalog identifier dispatched for a behavior node |
| `command` | node | Command-style behavior input where supported |
| `outcome` | edge | Named route selected from a node result |

Behavior-specific properties such as `template`, `durationMs`, or `path` are defined by the node catalog.

## Executable structure

A graph contains exactly one `START`, exactly one `END`, and no more than one `ERROR`. Every edge source and target resolves to a declared node. The validator also checks behavior discovery and routing structure before execution is accepted.

## Preservation and export

Original bytes are authoritative. Unknown keys, data, and extensions are retained for an unmodified round trip. A mutation after import invalidates byte-exact preservation; export refuses instead of emitting a lossy document under a preservation claim.

## Validation interface

`ravenroot validate graph.graphml` exits 0 for acceptance, 1 for a refused or invalid document, and 2 for CLI misuse. The HTTP inspection surface is `POST /v1/graphs/inspect`.

See [Graph semantics](../architecture/graph-semantics.md) for lifecycle invariants and [Graph validation](../troubleshooting/graph-execution.md) for recovery.
