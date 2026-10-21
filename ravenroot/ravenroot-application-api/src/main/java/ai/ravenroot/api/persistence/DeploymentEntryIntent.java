package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.security.SecurityContext;
import java.time.Instant;
import java.util.*;

/** Retained input custody for one exact deployment entry; it is never optional activity capture.
 * @param scope authenticated tenant, logical deployment and immutable version
 * @param processInstanceId original deterministic owning process identity
 * @param traversalId original actual traversal identity
 * @param entryNodeId exact authored SOURCE or explicit derivation entry
 * @param context established immutable business context
 * @param identity original authenticated admission identity without credentials
 * @param input bounded original JSON input retained until recoverable work or terminal settlement owns custody
 * @param acceptedAt original admission time
 * @param expiresAt finite custody deadline; expiry must settle visibly before input is discarded
 * @param cause source-event or explicit audited context derivation
 * @param derivedFrom original causal coordinates for a derivation, absent on a source event
 * @param derivationReason bounded authored audit reason, empty on a source event
 * @param checkpoint original runtime-owned visit and dispatch checkpoint, absent before its first start
 */
public record DeploymentEntryIntent(BusinessScope scope, UUID processInstanceId, UUID traversalId,
        String entryNodeId, ApplicationCorrelationContext context, SecurityContext identity,
        OpaquePayload input, Instant acceptedAt, Instant expiresAt, CorrelatedTraversal.Cause cause,
        JoinCause derivedFrom, String derivationReason, DeploymentEntryCheckpoint checkpoint) {
    /** Validates exact ownership and explicit derivation authority without coercing input data. */
    public DeploymentEntryIntent {
        Objects.requireNonNull(scope); Objects.requireNonNull(processInstanceId); Objects.requireNonNull(traversalId);
        Objects.requireNonNull(context); Objects.requireNonNull(identity); Objects.requireNonNull(input);
        Objects.requireNonNull(acceptedAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(cause);
        if (!scope.tenantId().equals(identity.tenantId()) || entryNodeId == null || entryNodeId.isBlank()
                || entryNodeId.length() > 4096 || entryNodeId.codePoints().anyMatch(Character::isISOControl)
                || !expiresAt.isAfter(acceptedAt) || !"application/json".equals(input.contentType())
                || cause != CorrelatedTraversal.Cause.SOURCE_EVENT && cause != CorrelatedTraversal.Cause.DERIVED_CONTEXT)
            throw new IllegalArgumentException("invalid deployment entry custody");
        derivationReason = Objects.requireNonNullElse(derivationReason, "");
        if (derivationReason.length() > 500 || derivationReason.codePoints().anyMatch(Character::isISOControl)
                || cause == CorrelatedTraversal.Cause.DERIVED_CONTEXT && (derivedFrom == null || derivationReason.isBlank())
                || cause == CorrelatedTraversal.Cause.SOURCE_EVENT && (derivedFrom != null || !derivationReason.isEmpty())
                || derivedFrom != null && !scope.tenantId().equals(derivedFrom.security().tenantId()))
            throw new IllegalArgumentException("invalid entry derivation audit");
    }
    /** Exact owning execution key.
     * @return authenticated tenant and original process identity
     */
    public ExecutionKey key() { return new ExecutionKey(scope.tenantId(), processInstanceId); }
    /** Original immutable business admission linked transactionally to retained input custody.
     * @return original relationship; retry cannot substitute target, context, input or causal IDs
     */
    public CorrelatedTraversal traversal() {
        return new CorrelatedTraversal(scope, context, processInstanceId, traversalId, entryNodeId, cause,
                TraversalStatus.ACCEPTED, null, acceptedAt, acceptedAt, null);
    }
    /** Preserves original admission while committing a later runtime-owned entry checkpoint.
     * @param next successor checkpoint recorded in the same transaction as its invocation/event
     * @return immutable original entry custody with the monotone checkpoint
     */
    public DeploymentEntryIntent checkpointed(DeploymentEntryCheckpoint next) {
        Objects.requireNonNull(next).requireSuccessorOf(checkpoint);
        return new DeploymentEntryIntent(scope, processInstanceId, traversalId, entryNodeId, context, identity, input,
                acceptedAt, expiresAt, cause, derivedFrom, derivationReason, next);
    }
    /** Rejects attempts to replace accepted source input or explicit derivation ancestry.
     * @param proposal proposed admission using the same deterministic execution identity
     */
    public void requireSameAdmission(DeploymentEntryIntent proposal) {
        if (!scope.equals(proposal.scope()) || !processInstanceId.equals(proposal.processInstanceId())
                || !traversalId.equals(proposal.traversalId()) || !entryNodeId.equals(proposal.entryNodeId())
                || !context.equals(proposal.context()) || !identity.subject().equals(proposal.identity().subject())
                || identity.principalType() != proposal.identity().principalType() || !identity.issuer().equals(proposal.identity().issuer())
                || !input.equals(proposal.input())
                || cause != proposal.cause() || !Objects.equals(derivedFrom, proposal.derivedFrom())
                || !derivationReason.equals(proposal.derivationReason()))
            throw new BusinessHistoryException(BusinessHistoryException.Reason.CONTEXT_CONFLICT);
    }
}
