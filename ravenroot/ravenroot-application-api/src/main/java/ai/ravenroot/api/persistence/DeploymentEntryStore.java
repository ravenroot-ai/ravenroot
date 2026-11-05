package ai.ravenroot.api.persistence;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.history.HistoryPage;
import java.util.*;
import java.util.concurrent.*;
/** Durable deployment-entry input custody, independent from inbox deduplication and activity capture. */
public interface DeploymentEntryStore {
    /** Atomically retains original entry input and its immutable scoped business relationship.
     * @param proposed original authenticated entry admission
     * @param mode authored business identity creation policy
     * @return original admission or explicit unavailable/settled result; retry cannot replace input
     */
    default CompletionStage<DeploymentEntryAdmission> acceptDeploymentEntry(DeploymentEntryIntent proposed, CorrelationMapping.Mode mode) {
        return acceptDeploymentEntry(proposed, mode, Optional.empty());
    }
    /** Admits a source event or explicitly audited derivation under its original live process fence.
     * @param proposed exact original entry admission and causal ancestry
     * @param mode authored business identity creation policy
     * @param derivingFence required current original-process lease for a context derivation; absent for a source event
     * @return original admission linked transactionally to its immutable business relationship, or explicit missing/settled input
     */
    default CompletionStage<DeploymentEntryAdmission> acceptDeploymentEntry(DeploymentEntryIntent proposed,
            CorrelationMapping.Mode mode, Optional<LeaseHandle> derivingFence) { return unsupported(); }
    /** Reads retained execution input only for one exact tenant-owned selected recovery record.
     * @param key exact authenticated tenant and owning process
     * @return retained pending input, absent after custody has been visibly settled or transferred
     */
    default CompletionStage<Optional<DeploymentEntryIntent>> deploymentEntry(ExecutionKey key) { return unsupported(); }
    /** Pages pending scalar entry metadata before any input/continuation bytes are loaded.
     * @param scope exact authenticated tenant, deployment and immutable version
     * @param cursor opaque continuation bound to the same scope and page size
     * @param limit operator-bounded maximum page size
     * @return bounded pending entries, including expired work requiring visible settlement
     */
    default CompletionStage<HistoryPage<DeploymentEntrySummary>> pendingDeploymentEntries(BusinessScope scope,
            Optional<String> cursor, int limit) { return unsupported(); }
    private static <T> CompletionStage<T> unsupported() {
        return CompletableFuture.failedFuture(new ExecutionStoreException(
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.DEPLOYMENT_ENTRY_CUSTODY)));
    }
}
