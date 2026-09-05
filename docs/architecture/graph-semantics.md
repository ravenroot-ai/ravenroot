# Graph and GraphML semantics

The executable graph is both a document with preservation requirements and a validated runtime model.

## Invariants

- Original GraphML bytes remain authoritative until a mutation invalidates exact preservation.
- Import resolves declared keys into effective names but retains unknown extensions.
- Validation requires one directed top-level graph, one START, one END, and no more than one ERROR.

## Runtime relationships

- START establishes entry; END establishes successful termination; ERROR offers graph-level failure handling.
- A behavior result names an outcome; outgoing edges express the eligible route.
- Catalog resolution occurs before execution so an unresolved behavior follows the configured pass-through or refusal contract.
- Linear complexity admission precedes actor allocation; cyclic graphs require the operator's finite
  cumulative traversal-step policy, which survives branch fan-out, retries, and durable re-entry.

## Architectural consequence

A document becomes executable only after preservation-aware import and semantic validation; routing remains declarative throughout runtime dispatch.

## Related reading

- [Exact contract](../reference/graphml.md)
- [Procedure or recovery](../troubleshooting/graph-execution.md)
