package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Widens the window between a caller's {@code load} and its {@code compareAndSet}, so that the
 * lost-update race is reached deterministically instead of occasionally.
 *
 * <p>Every arriving branch is held at a barrier immediately before its write, which guarantees that
 * all of them have already read the same revision. That is the precise interleaving a
 * compare-and-set exists to survive, and without forcing it the race showed up in 9 trials out of
 * 60 — a detector too weak for its result to mean anything either way.</p>
 *
 * <p>The decorator is applied to <strong>both</strong> arms of the measurement. It is a scheduling
 * device, not a behavioural one: it changes when writes are attempted and nothing about what they
 * do, so a correct store must still admit exactly one of them.</p>
 */
final class ContendedJoinStore implements JoinStore {

    private final JoinStore delegate;
    private final CyclicBarrier beforeWrite;

    ContendedJoinStore(JoinStore delegate, int contenders) {
        this.delegate = delegate;
        this.beforeWrite = new CyclicBarrier(contenders);
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
        try {
            // Released only once every contender has loaded and is ready to write. A timeout rather
            // than an indefinite wait, because retrying losers come back through here alone and must
            // not deadlock on a barrier no one else will reach.
            beforeWrite.await(250, TimeUnit.MILLISECONDS);
        } catch (Exception released) {
            beforeWrite.reset();
        }
        return delegate.compareAndSet(desired);
    }

    @Override
    public CompletionStage<Boolean> discard(JoinKey key) {
        return delegate.discard(key);
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
