package ai.ravenroot.api.correlation;
import java.util.*;
import java.util.concurrent.*;
/** Engine-neutral durable cross-traversal state and release outbox; ordinary JoinStore remains traversal-scoped. */
public interface CorrelatedJoinStore {
    /** Reads the retained generation for an exact tenant, deployment, version, application, node and round.
 * @param key exact scoped business join identity, including its node and round
 * @return retained checkpoint for the exact join key, absent when no generation exists
 */
    default CompletionStage<Optional<CorrelatedJoinRecord>> correlatedJoin(CorrelatedJoinKey key) { return unsupported(); }
    /** CAS expected revision is desired.revision-1. The READY record and release intent commit together.
 * @param desired prospective immutable join state with the next compare-and-set revision
 * @return committed next checkpoint, including an atomic READY continuation intent when complete
 */
    default CompletionStage<CorrelatedJoinRecord> compareAndSetCorrelatedJoin(CorrelatedJoinRecord desired) { return unsupported(); }
    /** Production arrival path: the original process lease and CAS decision are checked in one transaction.
 * @param desired prospective immutable join state with the next compare-and-set revision
 * @param admissionFence live original process lease checked atomically with the contribution admission
 * @return committed next checkpoint, including an atomic READY continuation intent when complete
 */
    default CompletionStage<CorrelatedJoinRecord> compareAndSetCorrelatedJoin(CorrelatedJoinRecord desired,
            ai.ravenroot.api.persistence.LeaseHandle admissionFence) { return unsupported(); }
    /** Bounded keyset page; empty phases selects every retained phase, including tombstones.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param applicationId exact application correlation identity within the authenticated history scope
 * @param phases retained join phase filter; an empty set selects every retained phase
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded scalar join page; retained contribution payloads are not read
 */
    default CompletionStage<CorrelatedJoinPage> correlatedJoins(BusinessScope scope, String applicationId,
            Set<CorrelatedJoinRecord.Phase> phases, Optional<String> cursor, int limit) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ai.ravenroot.api.persistence.ExecutionStoreException(
                new ai.ravenroot.api.persistence.ExecutionStoreFailure.CapabilityNotSupported(
                        ai.ravenroot.api.persistence.StoreCapability.CORRELATED_JOINS)));
    }
}
