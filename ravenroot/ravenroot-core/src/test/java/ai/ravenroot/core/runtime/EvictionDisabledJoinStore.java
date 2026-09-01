package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The eviction mutant: a {@link JoinStore} that behaves correctly except that it never forgets.
 *
 * <p>It exists so the retention assertions can be shown to have teeth. An assertion that a store
 * stays empty proves nothing unless the same assertion is also run against a store that does not
 * evict and is observed to fail — otherwise "stays at zero" and "nothing was ever written" look
 * identical, which is how a retention bug survives a green suite.</p>
 */
final class EvictionDisabledJoinStore implements JoinStore {

    private final JoinStore delegate;

    EvictionDisabledJoinStore(JoinStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean durable() {
        return delegate.durable();
    }

    @Override
    public CompletionStage<Optional<JoinRecord>> load(JoinKey key) {
        return delegate.load(key);
    }

    @Override
    public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
        return delegate.compareAndSet(desired);
    }

    @Override
    public CompletionStage<Boolean> discard(JoinKey key) {
        // The mutation, and the only difference from the real adapter.
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    @Override
    public CompletionStage<List<JoinRecord>> openJoins(String tenantId) {
        return delegate.openJoins(tenantId);
    }

    @Override
    public CompletionStage<Long> recordCount(String tenantId) {
        return delegate.recordCount(tenantId);
    }

    @Override
    public CompletionStage<Long> purgeSettledBefore(String tenantId, Instant cutoff) {
        return delegate.purgeSettledBefore(tenantId, cutoff);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
