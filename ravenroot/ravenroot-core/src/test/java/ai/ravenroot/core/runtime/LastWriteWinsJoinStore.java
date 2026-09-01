package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The isolation mutant: a {@link JoinStore} identical to the real one except that its
 * "compare-and-set" does not compare.
 *
 * <p>This is what the join would be built on if the store took the last write instead of the
 * expected revision, and it is the version the concurrency test is measured against. Without a
 * broken counterpart, "the join fired once" is equally consistent with the branches never having
 * raced at all, and a test that cannot fail on a broken implementation is not evidence about the
 * working one.</p>
 */
final class LastWriteWinsJoinStore implements JoinStore {

    private final ConcurrentHashMap<JoinKey, JoinRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean durable() {
        return false;
    }

    @Override
    public CompletionStage<Optional<JoinRecord>> load(JoinKey key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(records.get(key)));
    }

    @Override
    public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
        records.put(desired.key(), desired);
        return CompletableFuture.completedFuture(desired);
    }

    @Override
    public CompletionStage<Boolean> discard(JoinKey key) {
        return CompletableFuture.completedFuture(records.remove(key) != null);
    }

    @Override
    public CompletionStage<List<JoinRecord>> openJoins(String tenantId) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletionStage<Long> recordCount(String tenantId) {
        return CompletableFuture.completedFuture((long) records.size());
    }

    @Override
    public CompletionStage<Long> purgeSettledBefore(String tenantId, Instant cutoff) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public void close() {
        records.clear();
    }
}
