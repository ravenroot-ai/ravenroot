package ai.ravenroot.api.history;
import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import java.util.UUID;
/** Actual traversal identity and lifecycle; an old uncorrelated record has no application context or cause.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param entryNodeId actual admitted entry node; a source event does not pretend to enter at START
 * @param status authoritative recorded lifecycle status at the represented level
 * @param terminationReason recorded terminal reason, absent when none was recorded
 * @param visitCount number of actual node invocations in this traversal, including repeated loop visits
 * @param applicationCorrelation established application context, absent for legacy uncorrelated records
 * @param cause recorded entry cause, absent for legacy records that contain no such evidence
 * @param derivation immutable original causal coordinates and authored reason for an explicit derived context
 */
public record TraversalSummary(UUID processInstanceId, UUID traversalId, String entryNodeId,
        TraversalStatus status, ExecutionTerminationReason terminationReason, long visitCount,
        ApplicationCorrelationContext applicationCorrelation, CorrelatedTraversal.Cause cause, CorrelationDerivation derivation) {
    /** Creates a traversal summary without explicit derivation lineage.
     * @param processInstanceId original owning process
     * @param traversalId actual traversal identity
     * @param entryNodeId actual admitted entry
     * @param status recorded lifecycle
     * @param terminationReason recorded terminal reason
     * @param visitCount actual visit count
     * @param applicationCorrelation immutable business context, absent for old records
     * @param cause recorded entry cause, absent for old records
     */
    public TraversalSummary(UUID processInstanceId, UUID traversalId, String entryNodeId, TraversalStatus status,
            ExecutionTerminationReason terminationReason, long visitCount, ApplicationCorrelationContext applicationCorrelation,
            CorrelatedTraversal.Cause cause) {
        this(processInstanceId, traversalId, entryNodeId, status, terminationReason, visitCount, applicationCorrelation, cause, null);
    }
}
