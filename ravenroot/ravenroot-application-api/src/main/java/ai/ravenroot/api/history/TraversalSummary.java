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
 */
public record TraversalSummary(UUID processInstanceId, UUID traversalId, String entryNodeId,
        TraversalStatus status, ExecutionTerminationReason terminationReason, long visitCount,
        ApplicationCorrelationContext applicationCorrelation, CorrelatedTraversal.Cause cause) { }
