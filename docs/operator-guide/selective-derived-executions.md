# Selective derived executions

Selective derived execution starts a fresh process from retained output at a proven downstream boundary. Use it when completed upstream work must remain historical while selected downstream work runs again.

The source is never mutated. The derived process receives new process, traversal, invocation, and attempt identifiers plus immutable ancestry naming the source, exact retained predecessor closure, requester, reason, and external-effect decision. Admission pins the content-addressed graph and execution manifest. It also copies the selected output and attributes into the derived transaction, so recovery does not depend on the source remaining retained.

## Supported boundary

The current contract accepts one acyclic pending node with one completed predecessor invocation. The predecessor closure may include a completed parallel join: the join evidence must identify all contributing parent invocations, and every parent must still have retained evidence. The preview reports the exact inherited invocation set and the possible downstream graph scope. Conditional routes in that scope may or may not run according to new outputs; it is not a historical invocation list.

Ravenroot refuses a request when proof is absent or ambiguous. Stable refusal codes cover missing retained inputs, changed source settlement or pins, direct join or mapped boundaries, unsupported iteration or cycle closure, incompatible runtime dimensions, missing external-effect authorization, and an absent repeatability decision when effects are authorized. A terminal source alone is insufficient. The source must also have a positive post-quiescence fence recorded after runtime callbacks and invocations have returned.

Quiescence and outcome certainty are separate. If a begun source attempt failed, retried, or returned only after cancellation made its traversal terminal, the settlement retains `sourceOutcomeAmbiguous=true`. This source-wide fact applies even when the attempt is in a parallel sibling outside the selected downstream scope. Preview then returns `AMBIGUOUS_SOURCE_OUTCOME_AUTHORIZATION_REQUIRED`, or `AMBIGUOUS_SOURCE_OUTCOME_DECISION_REQUIRED` when authorization was requested without a decision. Admission requires both a non-empty reconciliation decision and execution-control authority. Work that stayed queued and never began does not create source-outcome ambiguity. Legacy settlement rows predate this distinction and migrate as ambiguous. Ravenroot does not infer causal independence from topology, a failed aggregate row, or the absence of a live actor.

## Operator flow

1. Select a terminal source in the execution inventory. Completed, cancelled, and failed sources are eligible only while their evidence and settlement fence remain retained.
2. Discover payload-free choices with `GET /v1/executions/{source-process-id}/derived/boundaries`, the **Discover boundaries** action in the Workbench, or:

   ```text
   ravenroot --server <url> --token-file <path> derive-boundaries <source-process-id>
   ```

3. Preview the selected node and predecessor. The CLI creates a request key by default and prints it; pass `--key=<same-value>` for a retry that must resolve to the same request:

   ```text
   ravenroot --server <url> --token-file <path> derive-preview \
     <source-process-id> <boundary-node-id> <predecessor-invocation-id> \
     "operator reason" "repeatability decision" --authorize-effects
   ```

4. Review `admissible`, refusal codes, exact inherited invocation ids, possible scope nodes, missing inputs, possible external-effect nodes, and `sourceOutcomeAmbiguous`. Future effect nodes describe the new execution; source-outcome ambiguity describes begun historical attempts whose external result requires reconciliation.
5. Start only after the effect decision is authorized:

   ```text
   ravenroot --server <url> --token-file <path> derive \
     <source-process-id> <boundary-node-id> <predecessor-invocation-id> \
     "operator reason" "repeatability decision" --authorize-effects \
     --key=<idempotency-key>
   ```

Preview requires execution-read authority. Admission requires execution-read and execution-start authority. Requests that authorize possible external effects also require execution-control authority. The boolean records intent; it does not grant that authority.

## Retention and recovery

Replay evidence is payload-bearing operational state, not an event-log field. Stores enforce their payload limit, the source process retention deadline, tenant isolation, and a bounded 512-record planning read. Purging an expired source removes its evidence and settlement. Already admitted ancestry survives source deletion, and its copied seed remains recoverable.

A crash after atomic admission leaves one scheduled boundary attempt. The normal recovery coordinator claims that exact attempt, verifies the derived execution's pinned manifest and graph, and uses the copied seed. It does not create another boundary invocation or read expired source payloads. An ambiguous already-running effect still follows the ordinary repeatability and reconciliation policy; recovery does not infer an external outcome.
