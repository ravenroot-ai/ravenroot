package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryPage;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link ExecutionStore} decorator that holds the <em>first</em> {@link #apply} open until a test
 * lets it go, so the submission's startup window can be entered deliberately instead of raced.
 *
 * <h2>Why the store and not a sleep</h2>
 * <p>{@code DefaultRavenrootApplication.startGraphMl} joins this call on the submitting thread, so
 * blocking here parks the submission at exactly that point — after the entry is in
 * {@code activeExecutions} and therefore listed by {@code liveExecutions}, and before
 * {@code runner.execute} registers a coordinator. A sleep on the test thread would approximate that
 * point and would make the result depend on scheduling; this <em>is</em> that point.</p>
 *
 * <h2>The gate reports on itself, and that is the whole reason it exists</h2>
 * <p>{@link #firstWriteWasHeldOpen()} is answered from this class's own bookkeeping — the write was
 * reached, and the release arrived before the bound rather than after it. A test that inferred "we
 * were inside the window" from anything the code under test produced would stop watching the moment
 * that code changed, without saying so. So the guard is here, in the fixture, and it is a fixture
 * assertion the test makes explicitly.</p>
 */
final class SuspendFirstWriteExecutionStore implements ExecutionStore {

    /** Generously above any healthy duration: expiry is a failed test, never a quiet pass. */
    private static final Duration RELEASE_BOUND = Duration.ofSeconds(60);

    private final ExecutionStore delegate;
    private final AtomicBoolean firstWriteClaimed = new AtomicBoolean();
    private final CountDownLatch firstWriteReached = new CountDownLatch(1);
    private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
    private volatile boolean releasedWithinBound;

    SuspendFirstWriteExecutionStore(ExecutionStore delegate) {
        this.delegate = delegate;
    }

    /** Blocks until the submission has reached its first durable write. */
    boolean awaitFirstWrite(Duration bound) throws InterruptedException {
        return firstWriteReached.await(bound.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Lets the held write proceed, and with it the rest of the submission. */
    void releaseFirstWrite() {
        releaseFirstWrite.countDown();
    }

    /**
     * Whether the first write really was suspended and really was released on request — as opposed to
     * never having been reached, or having given up on its own bound and continued unattended.
     */
    boolean firstWriteWasHeldOpen() {
        return firstWriteReached.getCount() == 0 && releasedWithinBound;
    }

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        if (firstWriteClaimed.compareAndSet(false, true)) {
            firstWriteReached.countDown();
            try {
                releasedWithinBound = releaseFirstWrite.await(RELEASE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return delegate.apply(batch);
    }

    // ------------------------------------------------------------------ plain delegation below

    @Override
    public Set<StoreCapability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public CompletionStage<StoredProcessInstance> load(ExecutionKey key) {
        return delegate.load(key);
    }

    @Override
    public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
        return delegate.claim(key, workerId, ttl);
    }

    @Override
    public CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl) {
        return delegate.renew(lease, ttl);
    }

    @Override
    public CompletionStage<Void> release(LeaseHandle lease) {
        return delegate.release(lease);
    }

    @Override
    public CompletionStage<List<LeaseHandle>> leases(String tenantId) {
        return delegate.leases(tenantId);
    }

    @Override
    public CompletionStage<List<PendingWork>> claimPendingWork(String tenantId, String workerId, int limit,
                                                               Duration leaseTtl) {
        return delegate.claimPendingWork(tenantId, workerId, limit, leaseTtl);
    }

    @Override
    public CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId, int limit,
                                                                      Duration leaseTtl) {
        return delegate.claimDueTimers(tenantId, workerId, limit, leaseTtl);
    }

    @Override
    public CompletionStage<Void> ack(PendingWork item) {
        return delegate.ack(item);
    }

    @Override
    public CompletionStage<Instant> forgottenBefore(String tenantId) {
        return delegate.forgottenBefore(tenantId);
    }

    @Override
    public CompletionStage<Optional<IdempotencyRecord>> lookupIdempotency(String tenantId, String key,
                                                                          Instant keyIssuedAt) {
        return delegate.lookupIdempotency(tenantId, key, keyIssuedAt);
    }

    @Override
    public CompletionStage<Long> idempotencyRecordCount(String tenantId) {
        return delegate.idempotencyRecordCount(tenantId);
    }

    @Override
    public CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
        return delegate.purgeExpiredIdempotencyRecords(tenantId);
    }

    @Override
    public CompletionStage<List<JournalRecord>> readJournal(String tenantId, long afterOffset, int limit) {
        return delegate.readJournal(tenantId, afterOffset, limit);
    }

    @Override
    public CompletionStage<Long> journalRetainedFrom(String tenantId) {
        return delegate.journalRetainedFrom(tenantId);
    }

    @Override
    public CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
        return delegate.outboxCursor(tenantId, destination);
    }

    @Override
    public CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected, long throughOffset) {
        return delegate.advanceOutboxCursor(expected, throughOffset);
    }

    @Override
    public CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId, UUID eventId,
                                                         Duration retention) {
        return delegate.recordInboxDelivery(tenantId, consumerId, eventId, retention);
    }

    @Override
    public CompletionStage<Long> inboxRecordCount(String tenantId) {
        return delegate.inboxRecordCount(tenantId);
    }

    @Override
    public CompletionStage<Long> compactJournal(String tenantId) {
        return delegate.compactJournal(tenantId);
    }

    @Override
    public Duration maxLeaseTtl() {
        return delegate.maxLeaseTtl();
    }

    @Override
    public int maxPayloadBytes() {
        return delegate.maxPayloadBytes();
    }

    @Override
    public Duration maxClockSkew() {
        return delegate.maxClockSkew();
    }

    @Override
    public Duration journalRetention() {
        return delegate.journalRetention();
    }

    // The durable inventory is pure delegation here. This double exists to perturb one
    // named operation; forwarding everything else unchanged is what keeps the perturbation the only
    // difference between it and the store it wraps.

    @Override
    public int maxInventoryPageSize() {
        return delegate.maxInventoryPageSize();
    }

    @Override
    public Duration terminalRetention() {
        return delegate.terminalRetention();
    }

    @Override
    public CompletionStage<ProcessInventoryPage> listProcessInstances(String tenantId,
                                                                      ProcessInventoryQuery query) {
        return delegate.listProcessInstances(tenantId, query);
    }

    @Override
    public CompletionStage<Optional<ProcessInventoryEntry>> findProcessInstance(ExecutionKey key) {
        return delegate.findProcessInstance(key);
    }

    @Override
    public CompletionStage<List<TraversalInventoryEntry>> listTraversals(ExecutionKey key) {
        return delegate.listTraversals(key);
    }

    @Override
    public CompletionStage<Instant> inventoryRetainedFrom(String tenantId) {
        return delegate.inventoryRetainedFrom(tenantId);
    }

    @Override
    public CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
        return delegate.purgeExpiredProcessInstances(tenantId);
    }

    // Forwarded rather than left on the interface defaults. Every method below is a `default` that
    // refuses with CapabilityNotSupported, so a decorator that publishes the delegate's capabilities
    // while inheriting them would declare EXECUTION_RESULTS and then refuse every result call -- a
    // wrapper that behaves unlike the store it wraps, in exactly the direction that makes a passing
    // test evidence about nothing.

    @Override
    public java.time.Duration executionResultRetention() {
        return delegate.executionResultRetention();
    }

    @Override
    public int maxExecutionResultPayloadBytes() {
        return delegate.maxExecutionResultPayloadBytes();
    }

    @Override
    public CompletionStage<ai.ravenroot.api.persistence.DurableExecutionResult> recordExecutionResult(
            ai.ravenroot.api.persistence.DurableExecutionResult result) {
        return delegate.recordExecutionResult(result);
    }

    @Override
    public CompletionStage<java.util.Optional<ai.ravenroot.api.persistence.DurableExecutionResult>>
            loadExecutionResult(String tenantId, java.util.UUID traversalId) {
        return delegate.loadExecutionResult(tenantId, traversalId);
    }

    @Override
    public CompletionStage<Instant> executionResultsRetainedFrom(String tenantId) {
        return delegate.executionResultsRetainedFrom(tenantId);
    }

    @Override
    public CompletionStage<Long> purgeExpiredExecutionResults(String tenantId) {
        return delegate.purgeExpiredExecutionResults(tenantId);
    }

    @Override
    public CompletionStage<java.util.Optional<ai.ravenroot.api.persistence.DurableExecutionPause>>
            loadExecutionPause(ExecutionKey key, java.util.UUID pauseId) {
        return delegate.loadExecutionPause(key, pauseId);
    }

    @Override
    public CompletionStage<List<ai.ravenroot.api.persistence.DurableExecutionPause>>
            executionPauses(ExecutionKey key) {
        return delegate.executionPauses(key);
    }

    @Override
    public CompletionStage<java.util.Optional<ai.ravenroot.api.persistence.DurableExecutionPause>>
            findHeldExecutionPause(String tenantId, java.util.UUID traversalId) {
        return delegate.findHeldExecutionPause(tenantId, traversalId);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
