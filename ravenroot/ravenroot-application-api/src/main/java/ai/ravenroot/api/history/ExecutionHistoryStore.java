package ai.ravenroot.api.history;
import ai.ravenroot.api.persistence.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded metadata reads independent of whole-aggregate loading and activity payload retention. */
public interface ExecutionHistoryStore {
    /** Lists genuine legacy traversals in one logical deployment/version without inventing business IDs. */
    default CompletionStage<HistoryPage<TraversalSummary>> uncorrelatedDeploymentTraversals(
            ai.ravenroot.api.correlation.BusinessScope scope, Optional<String> cursor, int limit) { return unsupported(); }
    default CompletionStage<HistoryPage<TraversalSummary>> executionTraversals(ExecutionKey key,
            Optional<String> cursor, int limit) { return unsupported(); }
    default CompletionStage<Optional<TraversalSummary>> executionTraversal(ExecutionKey key, UUID traversalId) { return unsupported(); }
    default CompletionStage<HistoryPage<NodeVisitSummary>> executionVisits(ExecutionKey key, UUID traversalId,
            Optional<String> nodeId, Optional<String> cursor, int limit) { return unsupported(); }
    default CompletionStage<HistoryPage<NodeAttemptSummary>> executionVisitAttempts(ExecutionKey key, UUID traversalId,
            UUID invocationId, Optional<String> cursor, int limit) { return unsupported(); }
    default CompletionStage<HistoryPage<VisitParent>> executionVisitParents(ExecutionKey key, UUID traversalId,
            UUID invocationId, Optional<String> cursor, int limit) { return unsupported(); }
    /** Exact membership probe; no aggregate, payload or diagnostics are loaded. */
    default CompletionStage<Boolean> executionSelectionExists(ExecutionKey key, UUID traversalId,
            Optional<UUID> invocationId, Optional<UUID> attemptId) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ExecutionStoreException(
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.EXECUTION_HISTORY)));
    }
}
