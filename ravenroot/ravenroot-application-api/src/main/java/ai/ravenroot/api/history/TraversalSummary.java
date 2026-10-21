package ai.ravenroot.api.history;
import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import java.util.UUID;
/** Actual traversal identity and lifecycle; an old uncorrelated record has no application context or cause. */
public record TraversalSummary(UUID processInstanceId, UUID traversalId, String entryNodeId,
        TraversalStatus status, ExecutionTerminationReason terminationReason, long visitCount,
        ApplicationCorrelationContext applicationCorrelation, CorrelatedTraversal.Cause cause) { }
