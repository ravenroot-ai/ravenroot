package ai.ravenroot.api.correlation;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import java.time.Instant;
import java.util.*;

/** Explicit authored close barrier, with original causal coordinates and no event payload copy.
 * @param scope exact authenticated tenant, logical deployment and pinned graph version
 * @param applicationCorrelationId original business identity being closed
 * @param requested requested completed, failed or cancelled business outcome
 * @param auditReason bounded authored non-secret reason
 * @param processInstanceId original process that requested closure
 * @param traversalId original traversal that requested closure
 * @param invocationId actual original close-node visit
 * @param attemptId actual original close-node attempt
 * @param requestedAt store-authoritative request time after normalization
 */
public record BusinessClosureSignal(BusinessScope scope, String applicationCorrelationId, BusinessHistory.Lifecycle requested,
        String auditReason, UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId, Instant requestedAt) {
    /** Validates the explicit disposition, original causality and bounded reason. */
    public BusinessClosureSignal {
        Objects.requireNonNull(scope); Objects.requireNonNull(requested); Objects.requireNonNull(processInstanceId);
        Objects.requireNonNull(traversalId); Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId); Objects.requireNonNull(requestedAt);
        if (applicationCorrelationId == null || applicationCorrelationId.isBlank() || applicationCorrelationId.length() > 4096
                || applicationCorrelationId.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid applicationCorrelationId");
        if (requested == BusinessHistory.Lifecycle.OPEN || requested == BusinessHistory.Lifecycle.EXPIRED
                || auditReason == null || auditReason.isBlank() || auditReason.length() > 500 || auditReason.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid authored business close signal");
    }
    /** Normalizes only the time; immutable original causal coordinates and reason survive retry.
     * @param now authoritative store time
     * @return original request normalized to the transaction time
     */
    public BusinessClosureSignal at(Instant now) {
        return new BusinessClosureSignal(scope, applicationCorrelationId, requested, auditReason, processInstanceId, traversalId, invocationId, attemptId, now);
    }
    /** Compares immutable operation identity independently of a transport retry's timestamp.
     * @param other proposed signal
     * @return whether this is the same original authored operation
     */
    public boolean sameRequest(BusinessClosureSignal other) {
        return scope.equals(other.scope) && applicationCorrelationId.equals(other.applicationCorrelationId) && requested == other.requested
                && auditReason.equals(other.auditReason) && processInstanceId.equals(other.processInstanceId) && traversalId.equals(other.traversalId)
                && invocationId.equals(other.invocationId) && attemptId.equals(other.attemptId);
    }
    /** Requires a current fenced batch that completes the actual original close visit.
     * @param batch original process transaction
     * @param folded post-fold actual lifecycle
     * @param relation immutable business relation for the original traversal
     */
    public void requireAuthority(ExecutionBatch batch, ProcessInstance folded, CorrelatedTraversal relation) {
        var key = new ExecutionKey(scope.tenantId(), processInstanceId);
        var traversal = folded.traversals().get(traversalId);
        var invocation = traversal == null ? null : traversal.invocations().get(invocationId);
        var attempt = invocation == null ? null : invocation.attempts().stream().filter(value -> value.attemptId().equals(attemptId)).findFirst().orElse(null);
        if (!batch.key().equals(key) || batch.fencingToken().isEmpty() || relation == null || !relation.scope().equals(scope)
                || !relation.context().applicationCorrelationId().equals(applicationCorrelationId) || !relation.traversalId().equals(traversalId)
                || invocation == null || attempt == null || invocation.status() != NodeInvocationStatus.COMPLETED || attempt.status() != NodeAttemptStatus.COMPLETED)
            throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
    }
}
