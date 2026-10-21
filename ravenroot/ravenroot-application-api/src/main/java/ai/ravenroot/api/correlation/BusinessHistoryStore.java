package ai.ravenroot.api.correlation;

import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Atomic history admission, relation and closure port. IDs are lookup keys, never authorization. */
public interface BusinessHistoryStore {
    /** Published operator-owned resource policy.
 * @return operator-owned finite history admission, retention, read and response policy
 */
    default BusinessHistoryPolicy businessHistoryPolicy() { return BusinessHistoryPolicy.DEFAULTS; }
    /** Creates/rejoins an open scoped history and idempotently records its exact admitted traversal.
 * @param mode explicit policy for generating, accepting or requiring an existing application identity
 * @return admitted scoped history, preserving the original traversal context on an identical retry
 * @param traversal original admitted traversal with its immutable established context
 */
    default CompletionStage<BusinessHistory> recordCorrelatedTraversal(CorrelatedTraversal traversal,
                                                                      CorrelationMapping.Mode mode) { return unsupported(); }
    /** Finds an exact scoped history, including retained closure/expiry tombstones.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param applicationId exact application correlation identity within the authenticated history scope
 * @return exact retained history summary, including closure or expiry, when present
 */
    default CompletionStage<Optional<BusinessHistory>> businessHistory(BusinessScope scope, String applicationId) { return unsupported(); }
    /** Searches in the store and limits before allocating public summaries.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param query bounded exact selection, filters, page size and continuation
 * @return bounded selected history summaries and a scope-bound continuation
 */
    default CompletionStage<BusinessHistoryPage> businessHistories(BusinessScope scope, BusinessHistoryQuery query) { return unsupported(); }
    /** Lists actual traversals inside one exact scoped history.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param applicationId exact application correlation identity within the authenticated history scope
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded original traversals related to the exact business history
 */
    default CompletionStage<CorrelatedTraversalPage> correlatedTraversals(BusinessScope scope, String applicationId,
                                                                         Optional<String> cursor, int limit) { return unsupported(); }
    /** Loads the immutable application context for an exact original execution, including recovery.
 * @param tenantId authenticated tenant owning this history scope
 * @param processId original process identity owning the selected traversal
 * @param traversalId actual traversal identity within the owning process
 * @return immutable established context for the exact original process and traversal, when retained
 */
    default CompletionStage<Optional<CorrelatedTraversal>> correlatedTraversal(String tenantId, UUID processId,
                                                                               UUID traversalId) { return unsupported(); }
    /** Idempotently settles an admitted traversal without implicitly declaring business completion.
 * @param tenantId authenticated tenant owning this history scope
 * @param processId original process identity owning the selected traversal
 * @param traversalId actual traversal identity within the owning process
 * @param status authoritative recorded lifecycle status at the represented level
 * @param reason closed payload-safe failure classification
 * @return stage completing after the original traversal result is settled without implicit business closure
 */
    default CompletionStage<Void> settleCorrelatedTraversal(String tenantId, UUID processId, UUID traversalId,
            TraversalStatus status, ExecutionTerminationReason reason) { return unsupported(); }
    /** Explicit compare-and-set closure; OPEN cannot be restored by this method or a late arrival.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param applicationId exact application correlation identity within the authenticated history scope
 * @param expectedRevision exact retained history revision required for the closure to commit
 * @param closure explicit terminal business lifecycle; this operation never reopens a history
 * @return closed history at the next revision; late arrivals cannot silently reopen it
 */
    default CompletionStage<BusinessHistory> closeBusinessHistory(BusinessScope scope, String applicationId,
            long expectedRevision, BusinessHistory.Lifecycle closure) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ai.ravenroot.api.persistence.ExecutionStoreException(
                new ai.ravenroot.api.persistence.ExecutionStoreFailure.CapabilityNotSupported(
                        ai.ravenroot.api.persistence.StoreCapability.BUSINESS_HISTORIES)));
    }
}
