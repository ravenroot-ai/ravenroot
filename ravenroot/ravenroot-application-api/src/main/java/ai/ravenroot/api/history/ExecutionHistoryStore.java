package ai.ravenroot.api.history;
import ai.ravenroot.api.persistence.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded metadata reads independent of whole-aggregate loading and activity payload retention. */
public interface ExecutionHistoryStore {
    /** Resolves only requested durable invocation-to-node bindings, without reading execution data.
     * @param key authenticated tenant and exact original process identity
     * @param invocationIds at most 1000 exact invocation identities selected from a bounded journal page
     * @return immutable retained bindings; absent or foreign identities have no entry
     */
    default CompletionStage<Map<UUID, String>> executionInvocationNodeIds(ExecutionKey key, Set<UUID> invocationIds) {
        return unsupported();
    }
    /** Lists genuine legacy traversals in one logical deployment/version without inventing business IDs.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual legacy traversals in the exact deployment/version scope
 */
    default CompletionStage<HistoryPage<TraversalSummary>> uncorrelatedDeploymentTraversals(
            ai.ravenroot.api.correlation.BusinessScope scope, Optional<String> cursor, int limit) { return unsupported(); }
    /** Lists actual constituent traversals without loading a whole process aggregate.
 * @param key authenticated tenant and exact original process identity
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual constituent traversals of the tenant-exact process
 */
    default CompletionStage<HistoryPage<TraversalSummary>> executionTraversals(ExecutionKey key,
            Optional<String> cursor, int limit) { return unsupported(); }
    /** Finds exact actual traversal metadata within its tenant-owned process.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @return actual selected traversal metadata, absent when the process or traversal is not retained
 */
    default CompletionStage<Optional<TraversalSummary>> executionTraversal(ExecutionKey key, UUID traversalId) { return unsupported(); }
    /** Pages actual node visits in durable creation order without reading captured payloads.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @param nodeId actual node identity within the pinned graph
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded visits in durable creation order within the selected actual traversal
 */
    default CompletionStage<HistoryPage<NodeVisitSummary>> executionVisits(ExecutionKey key, UUID traversalId,
            Optional<String> nodeId, Optional<String> cursor, int limit) { return unsupported(); }
    /** Pages actual retry attempts for an exact selected visit.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual retry attempts of the selected node visit
 */
    default CompletionStage<HistoryPage<NodeAttemptSummary>> executionVisitAttempts(ExecutionKey key, UUID traversalId,
            UUID invocationId, Optional<String> cursor, int limit) { return unsupported(); }
    /** Pages actual causal parents, retaining each parent's original traversal identity.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded causal parent identities with each parent's actual traversal
 */
    default CompletionStage<HistoryPage<VisitParent>> executionVisitParents(ExecutionKey key, UUID traversalId,
            UUID invocationId, Optional<String> cursor, int limit) { return unsupported(); }
    /** Exact membership probe; no aggregate, payload or diagnostics are loaded.
 * @param key authenticated tenant and exact original process identity
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param attemptId actual delivery-attempt identity within the visit
 * @return whether every supplied identity belongs to the exact selected process, traversal and visit
 */
    default CompletionStage<Boolean> executionSelectionExists(ExecutionKey key, UUID traversalId,
            Optional<UUID> invocationId, Optional<UUID> attemptId) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ExecutionStoreException(
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.EXECUTION_HISTORY)));
    }
}
