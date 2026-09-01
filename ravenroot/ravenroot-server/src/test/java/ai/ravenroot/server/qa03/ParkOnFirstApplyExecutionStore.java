package ai.ravenroot.server.qa03;

import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.PendingWork;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A test-only decorator over the real {@link ExecutionStore} port, for
 * {@link DeploymentIngressKillBoundary} — the deployment-hosted-traversal cell of the crash/replay
 * matrix (QA-03).
 *
 * <h2>Why a decorator and not a production seam</h2>
 * <p>{@code CommitBoundary} (in {@code ravenroot-persistence-sqlite}) is package-private by design —
 * its own Javadoc cites ADR 0010 section 12.4, which forbids adding a fault-injection point to the
 * port. This class adds nothing to the port or to any adapter: it is an ordinary implementation of the
 * public {@link ExecutionStore} interface, the same kind of thing an in-memory fake or a real adapter
 * is, built entirely in test code and never shipped. Every method delegates to a real, wrapped
 * {@link ExecutionStore} unchanged, except {@link #apply}, whose <em>first</em> call announces the
 * boundary and then never returns — which is exactly where the real
 * {@code DefaultGraphDeployment.offerDurably} makes its first write after {@code recordInboxDelivery}
 * durably commits (see {@code DefaultGraphDeployment.openTraversalRecorder}'s own Javadoc).</p>
 *
 * <p>The delegate is a real {@code SqliteExecutionStore} against a real file, so
 * {@code recordInboxDelivery} — called before {@code apply} in the ingress sequence this cell targets
 * — genuinely reaches disk before the process is killed. Nothing here simulates that commit; only the
 * <em>next</em> write is intercepted.</p>
 */
final class ParkOnFirstApplyExecutionStore implements ExecutionStore {

    private final ExecutionStore delegate;
    private final AtomicBoolean firstApplyReached = new AtomicBoolean(false);
    private final Runnable onFirstApply;

    ParkOnFirstApplyExecutionStore(ExecutionStore delegate, Runnable onFirstApply) {
        this.delegate = delegate;
        this.onFirstApply = onFirstApply;
    }

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        if (firstApplyReached.compareAndSet(false, true)) {
            onFirstApply.run();
            // Never completes: the real DefaultGraphDeployment.openTraversalRecorder blocks on this
            // exact call (ExecutionRecorder.awaitStore joins it), so the calling thread parks here
            // until SIGKILL -- no separate latch or sleep is needed.
            return new CompletableFuture<>();
        }
        return delegate.apply(batch);
    }

    @Override
    public Set<StoreCapability> capabilities() {
        return delegate.capabilities();
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
    public CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId,
                                                                       int limit, Duration leaseTtl) {
        return delegate.claimDueTimers(tenantId, workerId, limit, leaseTtl);
    }

    @Override
    public CompletionStage<Void> ack(PendingWork item) {
        return delegate.ack(item);
    }

    @Override
    public Duration maxClockSkew() {
        return delegate.maxClockSkew();
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
    public Duration journalRetention() {
        return delegate.journalRetention();
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
    public void close() {
        delegate.close();
    }
}
