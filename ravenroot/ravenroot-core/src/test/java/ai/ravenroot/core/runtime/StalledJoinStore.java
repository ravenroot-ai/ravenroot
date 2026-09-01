package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The store that stops answering: one {@code compareAndSet} returns a stage that never completes.
 *
 * <p>A store that <em>fails</em> is already covered — a failed stage completes, so every barrier
 * built on it opens. The uncovered case is the one that neither succeeds nor fails, which is what a
 * partitioned database, an exhausted connection pool or a lost response actually looks like to the
 * caller. It is the only way to hold the coordinator's drain barrier open, and therefore the only
 * way to observe what shutdown does when the drain cannot finish.</p>
 *
 * <p>Nothing is ever completed by this class. The stalled stage stays pending for the life of the
 * test, deliberately: a double that eventually relented would let a shutdown that merely waits long
 * enough pass an assertion about a shutdown that must not wait at all.</p>
 */
final class StalledJoinStore implements JoinStore {

    private final JoinStore delegate;
    private final int stallAt;
    private final AtomicInteger attempts = new AtomicInteger();
    private final CountDownLatch stalled = new CountDownLatch(1);

    /** Stalls the first {@code compareAndSet}. */
    StalledJoinStore(JoinStore delegate) {
        this(delegate, 1);
    }

    /**
     * @param stallAt the 1-based {@code compareAndSet} call that never completes. Choosing a later
     *                call is how a test arranges for an earlier branch to have genuinely settled and
     *                parked before the store goes silent, rather than racing it.
     */
    StalledJoinStore(JoinStore delegate, int stallAt) {
        this.delegate = delegate;
        this.stallAt = stallAt;
    }

    /** Blocks until the stalling call has been entered, so a test never races the store. */
    boolean awaitStall(long millis) throws InterruptedException {
        return stalled.await(millis, TimeUnit.MILLISECONDS);
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
        if (attempts.incrementAndGet() == stallAt) {
            stalled.countDown();
            return new CompletableFuture<>();
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
