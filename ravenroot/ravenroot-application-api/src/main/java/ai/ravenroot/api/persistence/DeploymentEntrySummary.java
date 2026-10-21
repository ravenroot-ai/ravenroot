package ai.ravenroot.api.persistence;
import ai.ravenroot.api.correlation.*;
import java.time.Instant;
import java.util.UUID;
/** Scalar pending-entry page member; retained input is fetched only for the selected recovery work.
 * @param scope exact authenticated deployment/version scope
 * @param processInstanceId original owning process identity
 * @param traversalId original actual traversal identity
 * @param entryNodeId original authored entry, never an inferred START alias
 * @param applicationCorrelationId established business identity
 * @param cause source event or explicit audited context derivation
 * @param inputBytes retained input byte count, without content
 * @param acceptedAt original admission time
 * @param expiresAt declared finite custody deadline
 * @param entryStarted whether the original runtime-owned entry visit has a durable start
 * @param entryCompleted whether that original visit has a durable completion
 */
public record DeploymentEntrySummary(BusinessScope scope, UUID processInstanceId, UUID traversalId, String entryNodeId,
        String applicationCorrelationId, CorrelatedTraversal.Cause cause, long inputBytes, Instant acceptedAt,
        Instant expiresAt, boolean entryStarted, boolean entryCompleted) {
    /** Projects one retained entry without copying its input or continuation bytes.
     * @param intent exact retained input-custody record
     * @return scalar pending-entry metadata
     */
    public static DeploymentEntrySummary of(DeploymentEntryIntent intent) {
        return new DeploymentEntrySummary(intent.scope(), intent.processInstanceId(), intent.traversalId(), intent.entryNodeId(),
                intent.context().applicationCorrelationId(), intent.cause(), intent.input().size(), intent.acceptedAt(), intent.expiresAt(),
                intent.checkpoint() != null, intent.checkpoint() != null && intent.checkpoint().completedEventId() != null);
    }
}
