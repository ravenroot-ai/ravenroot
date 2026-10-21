package ai.ravenroot.api.correlation;

import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Atomic history admission, relation and closure port. IDs are lookup keys, never authorization. */
public interface BusinessHistoryStore {
    /** Published operator-owned resource policy. */
    default BusinessHistoryPolicy businessHistoryPolicy() { return BusinessHistoryPolicy.DEFAULTS; }
    /** Creates/rejoins an open scoped history and idempotently records its exact admitted traversal. */
    default CompletionStage<BusinessHistory> recordCorrelatedTraversal(CorrelatedTraversal traversal,
                                                                      CorrelationMapping.Mode mode) { return unsupported(); }
    /** Finds an exact scoped history, including retained closure/expiry tombstones. */
    default CompletionStage<Optional<BusinessHistory>> businessHistory(BusinessScope scope, String applicationId) { return unsupported(); }
    /** Searches in the store and limits before allocating public summaries. */
    default CompletionStage<BusinessHistoryPage> businessHistories(BusinessScope scope, BusinessHistoryQuery query) { return unsupported(); }
    /** Lists actual traversals inside one exact scoped history. */
    default CompletionStage<CorrelatedTraversalPage> correlatedTraversals(BusinessScope scope, String applicationId,
                                                                         Optional<String> cursor, int limit) { return unsupported(); }
    /** Loads the immutable application context for an exact original execution, including recovery. */
    default CompletionStage<Optional<CorrelatedTraversal>> correlatedTraversal(String tenantId, UUID processId,
                                                                               UUID traversalId) { return unsupported(); }
    /** Idempotently settles an admitted traversal without implicitly declaring business completion. */
    default CompletionStage<Void> settleCorrelatedTraversal(String tenantId, UUID processId, UUID traversalId,
            TraversalStatus status, ExecutionTerminationReason reason) { return unsupported(); }
    /** Explicit compare-and-set closure; OPEN cannot be restored by this method or a late arrival. */
    default CompletionStage<BusinessHistory> closeBusinessHistory(BusinessScope scope, String applicationId,
            long expectedRevision, BusinessHistory.Lifecycle closure) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ai.ravenroot.api.persistence.ExecutionStoreException(
                new ai.ravenroot.api.persistence.ExecutionStoreFailure.CapabilityNotSupported(
                        ai.ravenroot.api.persistence.StoreCapability.BUSINESS_HISTORIES)));
    }
}
