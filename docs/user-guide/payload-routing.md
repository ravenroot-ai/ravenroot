# Payloads, outcomes, and routing

Design data flow and route selection so every visible edge has a deliberate meaning.

## Procedure

1. Start with a payload inside the documented size and structural budgets.
2. Choose transformation nodes for data changes and decision nodes for named outcomes.
3. Connect each named outcome to its intended target; reserve the undeclared failure route for classified node failure.
4. Inspect `untakenEdges` after a Test to prove which alternatives were not selected.

## Authority boundary

Graph routing expresses control flow; it does not authorize an effect. An effectful node still requires operator-owned capability at Run time.

## Verification

Run a Test payload for each named outcome and confirm selected routes through events plus the complementary `untakenEdges` evidence.

- [Reference contract](../reference/nodes-payload-limits.md)
- [Concept or recovery](../architecture/graph-semantics.md)
