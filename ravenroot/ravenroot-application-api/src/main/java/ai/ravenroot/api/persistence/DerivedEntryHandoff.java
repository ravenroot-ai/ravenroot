package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import java.util.Objects;

/** New context custody committed with completion of its original authored derivation visit.
 * @param intent immutable child input, attributes, command, scope and original causal lineage
 * @param mode authored admission policy for the new business identity
 */
public record DerivedEntryHandoff(DeploymentEntryIntent intent, CorrelationMapping.Mode mode) {
    /** Requires an explicit derivation, rather than an external event disguised as one. */
    public DerivedEntryHandoff {
        Objects.requireNonNull(intent); Objects.requireNonNull(mode);
        if (intent.cause() != CorrelatedTraversal.Cause.DERIVED_CONTEXT) throw new IllegalArgumentException("handoff requires explicit derivation");
    }
    /** Verifies the actual completed original visit under the batch's current lease fence.
     * @param batch original execution transaction
     * @param folded original lifecycle after transitions have been validated
     * @param relation original immutable business relation
     */
    public void requireAuthority(ExecutionBatch batch, ProcessInstance folded, CorrelatedTraversal relation) {
        var cause = intent.derivedFrom();
        var traversal = folded.traversals().get(cause.traversalId());
        var visit = traversal == null ? null : traversal.invocations().get(cause.invocationId());
        var attempt = visit == null ? null : visit.attempts().stream().filter(value -> value.attemptId().equals(cause.attemptId())).findFirst().orElse(null);
        if (!batch.key().equals(new ExecutionKey(intent.scope().tenantId(), cause.processInstanceId())) || batch.fencingToken().isEmpty()
                || relation == null || !relation.scope().equals(intent.scope()) || !relation.processInstanceId().equals(cause.processInstanceId())
                || !relation.traversalId().equals(cause.traversalId()) || relation.context().applicationCorrelationId().equals(intent.context().applicationCorrelationId())
                || visit == null || visit.status() != NodeInvocationStatus.COMPLETED || attempt == null || attempt.status() != NodeAttemptStatus.COMPLETED)
            throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
    }
}
