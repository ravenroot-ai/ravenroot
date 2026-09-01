package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinPhase;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.api.persistence.JoinStoreException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link JoinStore}, and the default when no durable adapter is configured.
 *
 * <p>It declares {@link #durable()} {@code false}, so a conformance suite skips the restart
 * assertions rather than being satisfied by an adapter that cannot honour them.</p>
 *
 * <h2>Retained state</h2>
 * <p>One entry per join that has received at least one arrival. <strong>Bounded by</strong> the
 * number of fan-in nodes in a graph multiplied by the number of traversals concurrently in flight —
 * not by the number of traversals ever run. <strong>Evicted by</strong>
 * {@link #discard(JoinKey)}, which the runtime calls for every join of a traversal when that
 * traversal ends, whether it succeeded or failed; by {@link #purgeSettledBefore(String, Instant)}
 * for records a dead runtime left behind; and wholesale by {@link #close()}.</p>
 *
 * <p>The compare-and-set is a single {@code compute} on a {@link ConcurrentHashMap}, so the read of
 * the current revision and the conditional write are one atomic step. Doing it as a {@code get}
 * followed by a {@code replace} would leave a window in which two callers both observe the same
 * revision and both believe they won.</p>
 */
public final class InMemoryJoinStore implements JoinStore {

    private final ConcurrentHashMap<JoinKey, JoinRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean durable() {
        return false;
    }

    @Override
    public CompletionStage<Optional<JoinRecord>> load(JoinKey key) {
        requireKey(key);
        return CompletableFuture.completedFuture(Optional.ofNullable(records.get(key)));
    }

    @Override
    public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
        if (desired == null) {
            return failed(new JoinStoreException(JoinStoreException.Reason.INVALID_REQUEST, null,
                    "desired record cannot be null"));
        }
        long expected = desired.revision() - 1;
        var conflict = new java.util.concurrent.atomic.AtomicReference<JoinStoreException>();
        JoinRecord stored = records.compute(desired.key(), (key, current) -> {
            long actual = current == null ? JoinRecord.ABSENT_REVISION : current.revision();
            if (actual != expected) {
                conflict.set(new JoinStoreException(JoinStoreException.Reason.CONCURRENCY_CONFLICT, key,
                        "join " + key.joinNodeId() + " is at revision " + actual + ", not " + expected));
                return current;
            }
            return desired;
        });
        JoinStoreException failure = conflict.get();
        return failure == null ? CompletableFuture.completedFuture(stored) : failed(failure);
    }

    @Override
    public CompletionStage<Boolean> discard(JoinKey key) {
        requireKey(key);
        return CompletableFuture.completedFuture(records.remove(key) != null);
    }

    @Override
    public CompletionStage<List<JoinRecord>> openJoins(String tenantId) {
        requireTenant(tenantId);
        return CompletableFuture.completedFuture(records.values().stream()
                .filter(record -> record.key().tenantId().equals(tenantId))
                .filter(record -> record.phase() == JoinPhase.OPEN)
                .toList());
    }

    @Override
    public CompletionStage<Long> recordCount(String tenantId) {
        requireTenant(tenantId);
        return CompletableFuture.completedFuture(records.values().stream()
                .filter(record -> record.key().tenantId().equals(tenantId))
                .count());
    }

    @Override
    public CompletionStage<Long> purgeSettledBefore(String tenantId, Instant cutoff) {
        requireTenant(tenantId);
        if (cutoff == null) {
            return failed(new JoinStoreException(JoinStoreException.Reason.INVALID_REQUEST, null,
                    "cutoff cannot be null"));
        }
        // Removed one entry at a time with a value-conditional remove, rather than with removeIf,
        // for two reasons: removeIf reports only whether anything changed, and a count derived by
        // measuring the map before and after would attribute a concurrent discard to this purge.
        // The conditional remove also guarantees a record rewritten between the scan and the delete
        // is not removed on the strength of its previous phase.
        long removed = 0;
        for (var entry : records.entrySet()) {
            JoinRecord record = entry.getValue();
            if (record.key().tenantId().equals(tenantId)
                    && record.phase().terminal()
                    && record.settledAt().isBefore(cutoff)
                    && records.remove(entry.getKey(), record)) {
                removed++;
            }
        }
        return CompletableFuture.completedFuture(removed);
    }

    /** Total number of retained records across every tenant. Diagnostics and bound assertions only. */
    public long totalRecordCount() {
        return records.size();
    }

    @Override
    public void close() {
        records.clear();
    }

    private static void requireKey(JoinKey key) {
        if (key == null) {
            throw new JoinStoreException(JoinStoreException.Reason.INVALID_REQUEST, null, "key cannot be null");
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new JoinStoreException(JoinStoreException.Reason.INVALID_REQUEST, null,
                    "tenantId cannot be blank");
        }
    }

    private static <T> CompletionStage<T> failed(JoinStoreException failure) {
        return CompletableFuture.failedFuture(failure);
    }
}
