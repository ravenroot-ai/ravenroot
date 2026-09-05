package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.StoreCapability;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Bridges {@link ExecutionResultRegistry.Durable} onto the asynchronous {@link ExecutionStore} port.
 *
 * <p>The registry's contract is synchronous and the port's is not, so the waiting has to happen
 * somewhere; it happens here rather than inside the registry so that the registry stays testable with
 * no store and so that the one place that blocks on a store is named. The registry already calls this
 * outside its own monitor, which is what keeps a slow store from stalling every other execution in
 * the process.</p>
 *
 * <p>{@link #of(ExecutionStore)} returns {@code null} for a store that does not declare
 * {@link StoreCapability#EXECUTION_RESULTS}, and the registry treats {@code null} as "no durable
 * record". The absence is resolved once, at composition, rather than as a caught failure on every
 * read — an adapter that does not support results answers every call with the same refusal, and
 * catching it per read would put an exception on the common path of a deployment that simply has no
 * durable store.</p>
 */
final class DurableExecutionResults implements ExecutionResultRegistry.Durable {

    private final ExecutionStore store;

    private DurableExecutionResults(ExecutionStore store) {
        this.store = store;
    }

    /**
     * The bridge for {@code store}, or {@code null} when it cannot record results.
     *
     * @param store the composed execution store, or {@code null} when none is composed.
     * @return the bridge, or {@code null} when there is no durable result to read or write.
     */
    static DurableExecutionResults of(ExecutionStore store) {
        if (store == null || !store.supports(StoreCapability.EXECUTION_RESULTS)) {
            return null;
        }
        return new DurableExecutionResults(store);
    }

    /** The cap a caller must project a payload against before building a record for this store. */
    int maxPayloadBytes() {
        return store.maxExecutionResultPayloadBytes();
    }

    @Override
    public Optional<DurableExecutionResult> load(String tenantId, UUID traversalId) {
        return await(store.loadExecutionResult(tenantId, traversalId));
    }

    @Override
    public void record(DurableExecutionResult result) {
        await(store.recordExecutionResult(Objects.requireNonNull(result, "result")));
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }
}
