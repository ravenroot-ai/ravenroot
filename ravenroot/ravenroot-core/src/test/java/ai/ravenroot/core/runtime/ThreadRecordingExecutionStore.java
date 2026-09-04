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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link ExecutionStore} decorator that records <em>which thread</em> issued each durable write.
 *
 * <p>{@link ExecutionRecorder#record} joins {@link ExecutionStore#apply} on its caller's thread, so
 * the name captured here is the name of the thread that paid for the write. That is the whole
 * required measurement: not how long a write took — a duration on one machine is not a property of
 * the code — but whose thread it was charged to.</p>
 *
 * <p>Only {@code apply} is instrumented. The lease claim and the reads are not writes, and widening
 * the probe to them would make the assertion "the control thread touched the store" rather than "the
 * control thread wrote to the journal", which is a different and weaker statement than the one the
 * test establishes.</p>
 *
 * <h2>A control call's own settlement is recorded apart from graph work</h2>
 * <p>Releasing a durable hold commits the settlement of that hold, and it commits on the caller's
 * thread necessarily: the control call answers whether the traversal was released, and it cannot
 * answer that before knowing whether the release committed. Deferring it would mean reporting a
 * resume that had not happened, which is the precise failure {@code GraphRunner} refuses.</p>
 *
 * <p>That write is the control operation <em>being performed</em>, not a hop's prologue being
 * charged to the wrong thread, so it is counted separately rather than folded into the same total.
 * The distinction is read off the batch itself — a settlement is the batch carrying an execution
 * pause transition — and not off the thread, so it cannot absorb an unrelated write that happens to
 * land on the same thread. {@link #graphWritesFrom} is the number the ordering assertion is made
 * over; {@link #holdSettlementsFrom} exists so a test can assert the settlement <em>did</em> land
 * there, which is what keeps the separation from becoming a hole.</p>
 */
final class ThreadRecordingExecutionStore implements ExecutionStore {

    private final ExecutionStore delegate;
    private final List<String> writingThreads = new CopyOnWriteArrayList<>();
    private final List<String> holdSettlementThreads = new CopyOnWriteArrayList<>();

    ThreadRecordingExecutionStore(ExecutionStore delegate) {
        this.delegate = delegate;
    }

    /** How many durable writes of any kind were issued from the thread with this name. */
    long writesFrom(String threadName) {
        return writingThreads.stream().filter(threadName::equals).count();
    }

    /** How many of those were graph work rather than a control call settling a hold it released. */
    long graphWritesFrom(String threadName) {
        return writesFrom(threadName) - holdSettlementsFrom(threadName);
    }

    /** How many hold settlements were issued from the thread with this name. */
    long holdSettlementsFrom(String threadName) {
        return holdSettlementThreads.stream().filter(threadName::equals).count();
    }

    /** Every write's thread name, in order — for the failure message, so a red run names the culprit. */
    List<String> writingThreads() {
        return List.copyOf(writingThreads);
    }

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        String thread = Thread.currentThread().getName();
        writingThreads.add(thread);
        if (!batch.executionPauseTransitions().isEmpty()) {
            holdSettlementThreads.add(thread);
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

    @Override
    public void close() {
        delegate.close();
    }
}
