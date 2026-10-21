package ai.ravenroot.api.correlation;
import java.util.*;
import java.util.concurrent.*;
/** Engine-neutral durable cross-traversal state and release outbox; ordinary JoinStore remains traversal-scoped. */
public interface CorrelatedJoinStore {
    default CompletionStage<Optional<CorrelatedJoinRecord>> correlatedJoin(CorrelatedJoinKey key) { return unsupported(); }
    /** CAS expected revision is desired.revision-1. The READY record and release intent commit together. */
    default CompletionStage<CorrelatedJoinRecord> compareAndSetCorrelatedJoin(CorrelatedJoinRecord desired) { return unsupported(); }
    /** Production arrival path: the original process lease and CAS decision are checked in one transaction. */
    default CompletionStage<CorrelatedJoinRecord> compareAndSetCorrelatedJoin(CorrelatedJoinRecord desired,
            ai.ravenroot.api.persistence.LeaseHandle admissionFence) { return unsupported(); }
    /** Bounded keyset page; empty phases selects every retained phase, including tombstones. */
    default CompletionStage<CorrelatedJoinPage> correlatedJoins(BusinessScope scope, String applicationId,
            Set<CorrelatedJoinRecord.Phase> phases, Optional<String> cursor, int limit) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ai.ravenroot.api.persistence.ExecutionStoreException(
                new ai.ravenroot.api.persistence.ExecutionStoreFailure.CapabilityNotSupported(
                        ai.ravenroot.api.persistence.StoreCapability.CORRELATED_JOINS)));
    }
}
