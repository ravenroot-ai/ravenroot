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

## A waiting process does not resume when a trigger is sent

**Diagnosis:** A trigger resolves a durable handler by tenant, handler name and correlation key, and
only a handler that is still waiting or escalated answers one. A handler that has already been
resolved, denied or expired is retained as evidence but is no longer live, so a later trigger for the
same key matches nothing.

**Action:** Read the handler's own record before re-sending anything. A retained handler names its
final state, the principal that closed it and the traversal it started; an absent record means the
key was never registered for this tenant.

**Verify:** Confirm the process carries a re-entry traversal created by the resolution that did
succeed, and confirm no second traversal was created by the trigger that was refused.

## A refused trigger is audited as "no live handler matches"

**Diagnosis:** Every refusal a trigger can receive is audited, but four causes deliberately share one
outcome: an unknown correlation key, a key belonging to another tenant, a handler already settled by
an earlier trigger, and a handler whose wait has expired. **A duplicate approval is therefore audited
as "no live handler matches the presented name and correlation key", not as "already approved."**

This is a deliberate refusal to distinguish them, not missing detail. Reporting "already settled"
would confirm that a correlation key exists and is live, which would let anyone able to reach the
trigger surface enumerate another tenant's business keys one guess at a time by watching which
refusal came back. The audit record still carries the tenant, the principal, the correlation key
presented and the time, which is what an investigation actually needs.

Note which identifier each record carries, because they differ by design: an accepted trigger is
recorded against the handler it resolved, while a refused one can only be recorded against the
correlation key that was presented, since no handler was resolved to name. Join the two on the
correlation key, not on a resource identifier.

**Action:** Distinguish the causes from the handler record rather than from the refusal. A settled
handler in the store means a duplicate; no handler at all means an unknown or foreign key.

**Verify:** Confirm exactly one accepted record exists for the handler, and that the refused attempts
changed neither the handler's final state nor the process revision.

## Related contracts

- [Primary contract](../reference/graphml.md)
- [Control procedure](../reference/execution-events.md)
