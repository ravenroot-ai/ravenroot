# Graph validation and execution

Start from validator output or terminal execution evidence; change only the document, payload, route, or dependency named by that evidence.

## Validation exits 1

**Diagnosis:** The document violates the GraphML envelope, key rules, node identity, terminal cardinality, edge references, or behavior resolution.

**Action:** Run `ravenroot inspect FILE`; correct the first structural refusal without deleting unknown extension data. Keep one directed top-level graph, one START, one END, and at most one ERROR.

**Verify:** Run `ravenroot validate FILE` and require exit 0 before submission.

## Execution is accepted but behavior is bypassed

**Diagnosis:** The request used Test mode, or unknown-behavior policy resolved the node through pass-through.

**Action:** Inspect `bypassedNodes` and `defaultedNodes`, confirm the requested mode, and compare the behavior ID with `/v1/node-types`. Use Run only when effects are intended.

**Verify:** Repeat Test to prove routing, then a reviewed Run; the terminal evidence must show the expected classification.

## A failure reaches ERROR or stops traversal

**Diagnosis:** A node returned classified failure and either the undeclared failure edge selected ERROR or no handler was eligible.

**Action:** Read ordered events for the failing attempt, inspect `handledFailureNodes` and `untakenEdges`, then fix input, dependency, or route. Do not retry an effectful node until its idempotency is understood.

**Verify:** Submit the minimal failing payload in Test where possible, then verify one terminal path and the expected handled status.

## Related contracts

- [Primary contract](../reference/graphml.md)
- [Control procedure](../reference/execution-events.md)
