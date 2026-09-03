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

/**
 * An {@link ExecutionStore} that delegates everything except the {@link StoreCapability#EVENT_JOURNAL}
 * declaration, which it withholds.
 *
 * <p>The control for the publication path. Asserting that a run journalled the right rows says
 * nothing about an adapter that cannot journal at all, and that adapter is the one the runner must
 * still record transitions through: an envelope sent to a store without the capability would take the
 * whole batch down, losing the transitions the journal is only a projection of. Withholding the
 * capability from a store that is otherwise fully working is the only way to observe that branch
 * without also changing what the store does.</p>
 */
final class JournalFreeExecutionStore implements ExecutionStore {

    private final ExecutionStore delegate;

    JournalFreeExecutionStore(ExecutionStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<StoreCapability> capabilities() {
        var without = new java.util.HashSet<>(delegate.capabilities());
        without.remove(StoreCapability.EVENT_JOURNAL);
        return Set.copyOf(without);
    }

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        return delegate.apply(batch);
    }

    @Override
    public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
        return delegate.claim(key, workerId, ttl);
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

    // The durable inventory (issue 154) is pure delegation here. This double exists to perturb one
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
