package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An {@link ExecutionStore} that delegates everything and lets a test observe or perturb the two
 * lease operations under test: the fenced write, and the claim that takes the lease.
 *
 * <p>Used instead of a blocking graph node so that the observation point is the write itself rather
 * than a timing window — a sleep-based approximation would make the coexistence assertion depend on
 * scheduling rather than on the guard under test.
 */
final class ObservableExecutionStore implements ExecutionStore {

    private final ExecutionStore delegate;
    private final List<ExecutionKey> fencedWrites = new CopyOnWriteArrayList<>();
    private final Consumer<ExecutionKey> onFirstFencedWrite;
    private final boolean refuseEveryClaim;
    private volatile boolean fired;

    private ObservableExecutionStore(ExecutionStore delegate, Consumer<ExecutionKey> onFirstFencedWrite,
                                     boolean refuseEveryClaim) {
        this.delegate = delegate;
        this.onFirstFencedWrite = onFirstFencedWrite;
        this.refuseEveryClaim = refuseEveryClaim;
    }

    /** Observes the first write made under a fence, which is the moment the traversal is live. */
    static ObservableExecutionStore observing(ExecutionStore delegate, Consumer<ExecutionKey> observer) {
        return new ObservableExecutionStore(delegate, observer, false);
    }

    /** Reports every instance as held by another worker — the only way to reach the busy branch. */
    static ObservableExecutionStore alwaysBusy(ExecutionStore delegate) {
        return new ObservableExecutionStore(delegate, key -> { }, true);
    }

    /** Every process-instance key this store has seen written under a fence, in order. */
    List<ExecutionKey> fencedWrites() {
        return List.copyOf(fencedWrites);
    }

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        if (batch.fencingToken().isPresent()) {
            fencedWrites.add(batch.key());
            if (!fired) {
                fired = true;
                onFirstFencedWrite.accept(batch.key());
            }
        }
        return delegate.apply(batch);
    }

    @Override
    public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
        if (refuseEveryClaim) {
            return CompletableFuture.failedFuture(new ExecutionStoreException(
                    new ExecutionStoreFailure.LeaseHeldByAnother(key, "another-worker", Instant.MAX)));
        }
        return delegate.claim(key, workerId, ttl);
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
