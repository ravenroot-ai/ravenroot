package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference in-memory {@link ExecutionStore}, following the {@code InMemoryArtifactRegistry}
 * precedent of hosting an application-api port's default adapter in core.
 *
 * <p>It is the adapter the PERS-02 conformance suite runs against, and the baseline PERS-03's SQLite
 * store must match. It is deliberately <strong>not</strong> durable and deliberately does
 * <strong>not</strong> declare {@link StoreCapability#DURABLE} or
 * {@link StoreCapability#CROSS_PROCESS_LEASE}: it loses all state with the JVM, and it cannot
 * guarantee that a fencing token is never reused across a restart. Declaring either in order to skip
 * a conformance assertion would defeat the purpose of the suite.</p>
 *
 * <p>The store is its own clock authority. A {@link Clock} may be injected so the suite can drive
 * lease expiry and timer due-ness deterministically instead of sleeping.</p>
 *
 * <p>Revisions come from a store-wide sequence rather than a per-instance counter. That models a log
 * offset, which the contract explicitly permits, and it means per-instance revisions are strictly
 * increasing but <em>not</em> contiguous — so no caller can quietly come to depend on {@code +1}.</p>
 *
 * <p>All state is guarded by one monitor. That is sufficient for a reference adapter and makes
 * all-or-nothing batch application structural: a batch is fully validated and folded before anything
 * is published, so a rejected batch cannot leave a partial write behind.</p>
 */
public final class InMemoryExecutionStore implements ExecutionStore {

    private static final Duration DEFAULT_MAX_LEASE_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final Duration DEFAULT_MAX_CLOCK_SKEW = Duration.ofSeconds(5);
    /**
     * Journal retention default. Twenty-four hours is an operational default and not a product
     * promise — ADR 0010 leaves concrete retention values to configuration — but it has to be
     * <em>some</em> declared number, because {@link #journalRetention()} is what a consumer reads to
     * learn how long it may be disconnected and still resume.
     */
    private static final Duration DEFAULT_JOURNAL_RETENTION = Duration.ofHours(24);

    private final Object monitor = new Object();
    private final Map<ExecutionKey, Entry> instances = new LinkedHashMap<>();
    private final Map<IdempotencyKey, IdempotencyRecord> idempotency = new LinkedHashMap<>();
    /**
     * Per-tenant temporal low-water-mark. Every record whose {@code expiresAt} is at or after the
     * tenant's watermark is present; one before it may have been forgotten. This is what makes the
     * absence of a key decidable without keeping a marker per key: state is O(tenants), not O(keys),
     * so it prevents the disk incident that a per-key tombstone would merely defer.
     */
    private final Map<String, Instant> forgottenBefore = new LinkedHashMap<>();
    /**
     * One journal per tenant, which is the same shape physical isolation gives PERS-03: there is no
     * cross-tenant structure here to accidentally read from, so an operation that forgot its tenant
     * cannot silently answer for another one.
     */
    private final Map<String, TenantJournal> journals = new LinkedHashMap<>();
    /**
     * Per-instance event counters, held outside {@link #journals} so that compaction cannot reset
     * them. A stream sequence that restarted after its records were discarded would make two
     * different events share a position within one instance.
     */
    private final Map<ExecutionKey, Long> streamSequences = new LinkedHashMap<>();
    /** Inbox deduplication records, keyed per tenant, consumer and event, with their expiry. */
    private final Map<InboxKey, Instant> inbox = new LinkedHashMap<>();
    private final AtomicLong revisionSequence = new AtomicLong();
    private final Clock clock;
    private final Duration maxLeaseTtl;
    private final int maxPayloadBytes;
    private final Duration maxClockSkew;
    private final Duration journalRetention;

    public InMemoryExecutionStore() {
        this(Clock.systemUTC(), DEFAULT_MAX_LEASE_TTL, DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_CLOCK_SKEW);
    }

    public InMemoryExecutionStore(Clock clock) {
        this(clock, DEFAULT_MAX_LEASE_TTL, DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_CLOCK_SKEW);
    }

    public InMemoryExecutionStore(Clock clock, Duration maxLeaseTtl, int maxPayloadBytes) {
        this(clock, maxLeaseTtl, maxPayloadBytes, DEFAULT_MAX_CLOCK_SKEW);
    }

    public InMemoryExecutionStore(Clock clock, Duration maxLeaseTtl, int maxPayloadBytes,
                                  Duration maxClockSkew) {
        this(clock, maxLeaseTtl, maxPayloadBytes, maxClockSkew, DEFAULT_JOURNAL_RETENTION);
    }

    public InMemoryExecutionStore(Clock clock, Duration maxLeaseTtl, int maxPayloadBytes,
                                  Duration maxClockSkew, Duration journalRetention) {
        this.journalRetention = Objects.requireNonNull(journalRetention, "journalRetention");
        if (journalRetention.isZero() || journalRetention.isNegative()) {
            throw new IllegalArgumentException("journalRetention must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxLeaseTtl = Objects.requireNonNull(maxLeaseTtl, "maxLeaseTtl");
        if (maxLeaseTtl.isZero() || maxLeaseTtl.isNegative()) {
            throw new IllegalArgumentException("maxLeaseTtl must be positive");
        }
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxClockSkew = Objects.requireNonNull(maxClockSkew, "maxClockSkew");
        if (maxClockSkew.isNegative()) {
            throw new IllegalArgumentException("maxClockSkew cannot be negative");
        }
    }

    @Override
    public Set<StoreCapability> capabilities() {
        // EVENT_JOURNAL and JOURNAL_COMPACTION are declared here as well as by the SQLite adapter,
        // deliberately. ADR 0010 section 11.1 rules that a capability-gated assertion no in-tree
        // adapter runs manufactures the appearance of coverage; declaring both on both adapters means
        // every journal assertion in the conformance suite executes twice rather than being skipped
        // into invisibility. Neither capability makes a durability claim, so an in-memory adapter can
        // honour both honestly: they are about atomicity with the batch and about pruning, not about
        // surviving process death, which is what DURABLE says and what this adapter still must not say.
        // DURABLE_HANDLERS is declared for the same reason as the two journal capabilities: it makes
        // a claim about transactionality and about the handler mechanism existing, not about
        // surviving process death, so this adapter can honour it honestly and every PERS-05
        // conformance assertion executes here as well as against SQLite instead of being skipped
        // into invisibility on one of them.
        return Set.of(StoreCapability.TRANSACTIONAL_BATCH, StoreCapability.IDEMPOTENCY_PURGE,
                StoreCapability.EVENT_JOURNAL, StoreCapability.JOURNAL_COMPACTION,
                StoreCapability.DURABLE_HANDLERS);
    }

    @Override
    public Duration maxLeaseTtl() {
        return maxLeaseTtl;
    }

    @Override
    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    @Override
    public Duration maxClockSkew() {
        return maxClockSkew;
    }

    @Override
    public CompletionStage<Instant> forgottenBefore(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                return watermarkOf(tenantId);
            }
        });
    }

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        return complete(() -> {
            Objects.requireNonNull(batch, "batch");
            // Decidable from the request alone, so it happens before the monitor is even entered.
            requireNoFencingTokenUnderNotPresent(batch);
            batch.timersToSchedule().forEach(timer -> requireWithinPayloadLimit(timer.payload()));
            batch.handlerTransitions().forEach(transition ->
                    requireWithinPayloadLimit(transition.outcomePayload()));
            batch.idempotency().ifPresent(write -> {
                requireWithinPayloadLimit(write.requestFingerprint());
                requireWithinPayloadLimit(write.outcomeRef());
            });
            requireEnvelopesMatchBatch(batch);

            synchronized (monitor) {
                ExecutionKey key = batch.key();
                Entry existing = instances.get(key);

                // ADR 0010 section 13.2: fencing -> replay -> expectation -> fold.
                //
                // Fencing precedes every check that could yield a SUCCESS; it is not a priority
                // ordering among rejections. Answering a fenced worker's batch successfully from an
                // idempotency record would tell it its work landed and let it briefly believe it
                // still owns the instance -- the split-brain belief the fence exists to destroy. It
                // does no lasting damage, since its next mutating call is rejected, but it costs a
                // round trip and a false belief for nothing. The fenced worker does not need the
                // outcome: it is no longer the owner. The NEW owner needs it, and holds a current
                // token.
                //
                // Existence is a PRECONDITION of fencing rather than a competitor to it, which is
                // why this is guarded. A token is a claim about a lease, and a lease on an instance
                // that does not exist is not stale -- it is impossible. Reporting
                // FencedOut(presented, current = 0) here would fabricate a current token to stand in
                // for the absence of any token, the same defect as the nil-UUID ExecutionKey that
                // section 6.3 removed, and it would tell an operator "another worker took over"
                // when nobody did. NotFound is truthful for every sub-case -- never created,
                // archived, forged token -- and the batch is rejected either way, with the same
                // DETERMINISTIC_REJECT classification, so no successful answer can escape.
                if (existing != null) {
                    requireFencingTokenCurrent(key, batch, existing);
                }

                // A recorded, matching replay is answered from the record and never re-applied.
                var replay = replayOf(batch, existing);
                if (replay != null) {
                    return replay;
                }

                // Expectation LAST. The opposite order breaks replay entirely: a write that already
                // happened necessarily bumped the revision, so a retrying caller's expectation is
                // stale by construction and every legitimate replay would fail with
                // ConcurrencyConflict. An expectation is meaningful only for an act about to apply.
                requireExpectationMet(key, batch.expectation(), existing, batch);

                ProcessInstance folded = fold(batch, existing);
                Instant now = clock.instant();
                long revision = revisionSequence.incrementAndGet();
                GraphVersionPin pin = pinFor(key, batch, existing);

                var timers = existing == null ? new LinkedHashMap<UUID, TimerSchedule>()
                        : new LinkedHashMap<>(existing.timers);
                batch.timersToCancel().forEach(timers::remove);
                for (TimerSchedule schedule : batch.timersToSchedule()) {
                    timers.put(schedule.timerId(), schedule);
                }

                var handlers = existing == null ? new LinkedHashMap<UUID, DurableHandler>()
                        : new LinkedHashMap<>(existing.handlers);
                // Handler writes fold after the aggregate, because a registration may name an
                // invocation the same batch created and a terminal transition must name a traversal
                // the same batch added. Both are validated against the POST-fold aggregate; folding
                // them first would force a caller to split one atomic wait, or one atomic re-entry,
                // across two batches and reopen exactly the crash window PERS-05 exists to close.
                applyHandlerWrites(key, batch, folded, handlers, revision);

                var next = new Entry(folded, revision, pin, key.tenantId(), now,
                        existing == null ? 0L : existing.fencingToken,
                        existing == null ? null : existing.lease,
                        timers,
                        handlers,
                        existing == null ? new HashMap<>() : new HashMap<>(existing.workClaims),
                        existing == null ? new HashSet<>() : new HashSet<>(existing.acknowledged));
                dropAcknowledgementsForRescheduledWork(next);

                batch.idempotency().ifPresent(write -> idempotency.put(new IdempotencyKey(key.tenantId(), write.key()),
                        new IdempotencyRecord(write.key(), write.requestFingerprint(), write.outcomeRef(),
                                revision, now.plus(write.retentionWindow()))));

                // Published only here: every rejection above happens before any mutation is visible.
                // The journal write is inside this same block and under the same monitor, which is
                // what makes the shared transactional boundary real rather than described: there is
                // no instant at which the transition is visible and its events are not.
                instances.put(key, next);
                appendToJournal(key, batch, revision, now);
                return next.toStored();
            }
        });
    }

    @Override
    public CompletionStage<StoredProcessInstance> load(ExecutionKey key) {
        return complete(() -> {
            Objects.requireNonNull(key, "key");
            synchronized (monitor) {
                Entry entry = instances.get(key);
                if (entry == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                return entry.toStoredRevalidated(key);
            }
        });
    }

    @Override
    public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
        return complete(() -> {
            Objects.requireNonNull(key, "key");
            requireLeaseTtl(ttl);
            requireWorkerId(workerId);
            synchronized (monitor) {
                Entry entry = instances.get(key);
                if (entry == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                Instant now = clock.instant();
                if (entry.lease != null && !entry.lease.workerId().equals(workerId)
                        && now.isBefore(entry.lease.expiresAt())) {
                    // Failing to ACQUIRE, not losing one that was held: ordinary contention, nothing
                    // started, nothing at risk. Reporting it as LeaseLost would bury the rare
                    // critical signal in routine noise.
                    throw failure(new ExecutionStoreFailure.LeaseHeldByAnother(key,
                            entry.lease.workerId(), entry.lease.expiresAt()));
                }
                return issueLease(key, entry, workerId, ttl, now);
            }
        });
    }

    @Override
    public CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl) {
        return complete(() -> {
            Objects.requireNonNull(lease, "lease");
            requireLeaseTtl(ttl);
            synchronized (monitor) {
                Entry entry = instances.get(lease.key());
                if (entry == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(lease.key()));
                }
                if (entry.lease == null || !entry.lease.workerId().equals(lease.workerId())
                        || entry.fencingToken != lease.fencingToken()) {
                    throw failure(new ExecutionStoreFailure.LeaseLost(lease.key(), lease.workerId()));
                }
                Instant now = clock.instant();
                if (!now.isBefore(entry.lease.expiresAt())) {
                    throw failure(new ExecutionStoreFailure.LeaseLost(lease.key(), lease.workerId()));
                }
                // Renewal extends the window without issuing a new token: the holder is unchanged.
                var renewed = new LeaseHandle(lease.key(), lease.workerId(), entry.fencingToken,
                        entry.lease.claimedAt(), now.plus(ttl));
                entry.lease = renewed;
                return renewed;
            }
        });
    }

    @Override
    public CompletionStage<Void> release(LeaseHandle lease) {
        return complete(() -> {
            Objects.requireNonNull(lease, "lease");
            synchronized (monitor) {
                Entry entry = instances.get(lease.key());
                if (entry != null && entry.lease != null
                        && entry.lease.workerId().equals(lease.workerId())
                        && entry.fencingToken == lease.fencingToken()) {
                    entry.lease = null;
                }
                // Releasing a lease already lost is a no-op, never a failure.
                return null;
            }
        });
    }

    @Override
    public CompletionStage<List<LeaseHandle>> leases(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                Instant now = clock.instant();
                var active = new ArrayList<LeaseHandle>();
                instances.forEach((key, entry) -> {
                    if (!key.tenantId().equals(tenantId)) {
                        return;
                    }
                    if (entry.lease != null && now.isBefore(entry.lease.expiresAt())) {
                        active.add(entry.lease);
                    }
                });
                return List.copyOf(active);
            }
        });
    }

    @Override
    public CompletionStage<List<PendingWork>> claimPendingWork(String tenantId, String workerId, int limit,
                                                               Duration leaseTtl) {
        return complete(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            synchronized (monitor) {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork>();
                for (var instance : instances.entrySet()) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    ExecutionKey key = instance.getKey();
                    Entry entry = instance.getValue();
                    // One tenant per call. A physically isolated adapter would not even see another
                    // tenant's rows here; this adapter shares one map, so the filter is what makes
                    // the two indistinguishable to a caller.
                    if (!key.tenantId().equals(tenantId)) {
                        continue;
                    }
                    if (leasedByOther(entry, workerId, now)) {
                        continue;
                    }
                    var dispatchable = scheduledAttempts(entry, now);
                    var dueTimers = dueTimerEntries(entry, now);
                    var triggers = claimableTriggers(entry, now);
                    if (dispatchable.isEmpty() && dueTimers.isEmpty() && triggers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(key, entry, workerId, leaseTtl, now);
                    for (ScheduledAttempt attempt : dispatchable) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimAttempt(key, entry, attempt, lease, now, leaseTtl));
                    }
                    for (TimerSchedule timer : dueTimers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(key, entry, timer, lease, now, leaseTtl));
                    }
                    for (DurableHandler handler : triggers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTrigger(key, entry, handler, lease, now, leaseTtl));
                    }
                }
                return List.copyOf(claimed);
            }
        });
    }

    @Override
    public CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId,
                                                                      int limit, Duration leaseTtl) {
        return complete(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            synchronized (monitor) {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork.TimerDue>();
                for (var instance : instances.entrySet()) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    ExecutionKey key = instance.getKey();
                    Entry entry = instance.getValue();
                    if (!key.tenantId().equals(tenantId)) {
                        continue;
                    }
                    if (leasedByOther(entry, workerId, now)) {
                        continue;
                    }
                    var due = dueTimerEntries(entry, now);
                    if (due.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(key, entry, workerId, leaseTtl, now);
                    for (TimerSchedule timer : due) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(key, entry, timer, lease, now, leaseTtl));
                    }
                }
                return List.copyOf(claimed);
            }
        });
    }

    @Override
    public CompletionStage<Void> ack(PendingWork item) {
        return complete(() -> {
            Objects.requireNonNull(item, "item");
            synchronized (monitor) {
                Entry entry = instances.get(item.key());
                if (entry == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(item.key()));
                }
                if (entry.fencingToken != item.fencingToken()) {
                    throw failure(new ExecutionStoreFailure.FencedOut(item.key(), item.fencingToken(),
                            entry.fencingToken));
                }
                if (entry.workClaims.remove(item.workItemId()) == null) {
                    throw failure(ExecutionStoreFailure.unknownWorkItem(item.workItemId()));
                }
                entry.acknowledged.add(item.workItemId());
                if (item instanceof PendingWork.TimerDue) {
                    entry.timers.remove(item.workItemId());
                }
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Optional<IdempotencyRecord>> lookupIdempotency(String tenantId, String key,
                                                                          Instant keyIssuedAt) {
        return complete(() -> {
            requireTenantId(tenantId);
            if (key == null || key.isBlank()) {
                throw failure(ExecutionStoreFailure.invalid("idempotency key cannot be blank"));
            }
            var lookup = new IdempotencyKey(tenantId, key);
            synchronized (monitor) {
                requireIssuanceWithinSkewBudget(keyIssuedAt);
                IdempotencyRecord record = idempotency.get(lookup);
                if (record != null) {
                    return Optional.of(record);
                }
                if (provablyNeverRecorded(tenantId, keyIssuedAt)) {
                    // Any record for this key would still be above the watermark, so its absence
                    // proves it was never written and the caller may safely apply. That is an ABSENT
                    // ANSWER, not a missing entity: there is no ExecutionKey to report, because
                    // nothing was looked up by instance key. The earlier NotFound had to fabricate
                    // one with a nil UUID, which then reached describe() and the logs and showed an
                    // operator a real-looking key for an entity that never existed. It is also the
                    // outcome of the first attempt of every idempotent operation, so a failure here
                    // would force every caller onto a catch block to learn it may proceed.
                    return Optional.empty();
                }
                // Expiry stays a failure: the store cannot answer and the caller must stop and
                // resolve. Empty means proceed, failure means stop -- different channels because
                // they demand different actions.
                throw failure(new ExecutionStoreFailure.IdempotencyRecordExpired(key));
            }
        });
    }

    @Override
    public CompletionStage<Long> idempotencyRecordCount(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                return idempotency.keySet().stream()
                        .filter(recorded -> recorded.tenantId().equals(tenantId))
                        .count();
            }
        });
    }

    @Override
    public CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                Instant now = clock.instant();
                var doomed = idempotency.entrySet().stream()
                        .filter(entry -> entry.getKey().tenantId().equals(tenantId))
                        .filter(entry -> entry.getValue().expiresAt().isBefore(now))
                        .map(Map.Entry::getKey)
                        .toList();

                // The order is load-bearing (ADR 0010 section 6.1). Advance the watermark first,
                // then delete. Advancing early is conservative: the watermark governs only how
                // ABSENCE is interpreted, and records still present are still answered from the
                // record. Deleting first and failing before the watermark moved would leave absent
                // records below the true purge line under a stale watermark, which reads as "never
                // recorded" and silently re-executes.
                //
                // The guard is equally load-bearing in the other direction: a purge that forgot
                // NOTHING must not move the watermark. Moving it would destroy provable absence for
                // every key this tenant issued before now, turning safe applies into
                // IdempotencyRecordExpired for work that was never recorded -- and a periodic purge
                // job would do it on every tick.
                if (doomed.isEmpty()) {
                    return 0L;
                }
                forgottenBefore.merge(tenantId, now,
                        (current, candidate) -> candidate.isAfter(current) ? candidate : current);
                doomed.forEach(idempotency::remove);
                return (long) doomed.size();
            }
        });
    }

    /**
     * Discards everything, which for a <strong>non-durable</strong> adapter is exactly right
     * (ADR 0010 section 13.1): retaining state across close would falsely simulate durability, which
     * section 11 forbids, and this adapter declares neither {@link StoreCapability#DURABLE} nor
     * {@link StoreCapability#CROSS_PROCESS_LEASE}.
     *
     * <p>The governing invariant is that {@code close()} may only accelerate what lease expiry would
     * do anyway, from which session-scoped release follows: a store mints a session identifier when
     * it opens, a lease records the session that took it, and {@code close()} releases only that
     * session's leases. Here that scoping is <em>inert rather than absent</em>: this store's state is
     * not shared with any other session, so the whole of it <em>is</em> this session's state and
     * discarding it releases exactly this session's leases and nothing else. A durable, multi-pod
     * adapter (PERS-03, PERS-08) must implement the session identifier for real, because there
     * "clear the lease table" would let one draining pod strip a live pod of its leases — something
     * expiry would never do. Fencing tokens must not reset on reopen either; a session is not a
     * fencing domain.</p>
     */
    @Override
    public void close() {
        synchronized (monitor) {
            instances.clear();
            idempotency.clear();
            forgottenBefore.clear();
        }
    }

    // ---------------------------------------------------------------- batch helpers

    private StoredProcessInstance replayOf(ExecutionBatch batch, Entry existing) {
        var write = batch.idempotency().orElse(null);
        if (write == null) {
            return null;
        }
        // Rejected at WRITE time as well as at lookup, so an operator whose clock runs fast is told
        // while the clock can still be fixed, rather than after the damage window has passed.
        requireIssuanceWithinSkewBudget(write.keyIssuedAt());
        var lookup = new IdempotencyKey(batch.key().tenantId(), write.key());
        IdempotencyRecord recorded = idempotency.get(lookup);
        if (recorded == null) {
            // Absence must be classified here too. Applying a batch whose key may have been purged
            // is exactly the silent re-execution this mechanism exists to prevent.
            if (provablyNeverRecorded(batch.key().tenantId(), write.keyIssuedAt())) {
                return null;
            }
            throw failure(new ExecutionStoreFailure.IdempotencyRecordExpired(write.key()));
        }
        if (!recorded.requestFingerprint().equals(write.requestFingerprint())) {
            throw failure(new ExecutionStoreFailure.IdempotencyConflict(write.key()));
        }
        if (existing == null) {
            // ADR 0010 section 13.3, now stated rather than implicit: an idempotency record can
            // outlive its process instance, because retention windows and instance lifetime are
            // independent. NotFound carries the REAL batch key -- there is a key here, unlike in
            // lookupIdempotency -- and IdempotencyRecordExpired would be wrong, because the store
            // CAN answer about the record; it is the instance that is gone, and saying otherwise
            // would misdirect the operator. This adapter has no deletion or archival operation, so
            // the branch is unreachable through the port today.
            throw failure(new ExecutionStoreFailure.NotFound(batch.key()));
        }
        // A replay answers with CURRENT state (section 6.2), not the state as of
        // recordedAtRevision: the purpose of a replay is to let the caller PROCEED without
        // re-executing, and proceeding requires current state. A caller handed a stale revision
        // would derive an expectation from it and loop on ConcurrencyConflict forever.
        return existing.toStored();
    }

    private void requireExpectationMet(ExecutionKey key, RevisionExpectation expectation, Entry existing,
                                       ExecutionBatch batch) {
        switch (expectation) {
            case RevisionExpectation.NotPresent ignored -> {
                if (existing != null) {
                    throw failure(new ExecutionStoreFailure.AlreadyExists(key, existing.revision));
                }
            }
            case RevisionExpectation.Exactly exactly -> {
                if (existing == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                if (existing.revision != exactly.revision()) {
                    throw failure(new ExecutionStoreFailure.ConcurrencyConflict(key, expectation,
                            existing.revision));
                }
            }
            case RevisionExpectation.Any ignored -> {
                if (existing == null && !createsInstance(batch)) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
            }
        }
    }

    private void requireFencingTokenCurrent(ExecutionKey key, ExecutionBatch batch, Entry existing) {
        if (batch.fencingToken().isEmpty()) {
            return;
        }
        long presented = batch.fencingToken().getAsLong();
        long current = existing == null ? 0L : existing.fencingToken;
        // Inequality, not "lower than": a caller presenting an unissued higher token must not be
        // able to fence out the legitimate owner.
        if (presented != current) {
            throw failure(new ExecutionStoreFailure.FencedOut(key, presented, current));
        }
    }

    private ProcessInstance fold(ExecutionBatch batch, Entry existing) {
        ProcessInstance folded = existing == null ? null : existing.state;
        for (ExecutionTransition transition : batch.transitions()) {
            try {
                folded = transition.applyTo(folded);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // A caller's illegal transition is a caller bug, not stored corruption.
                throw new ExecutionStoreException(
                        ExecutionStoreFailure.invalid(rejected.getMessage()), rejected);
            }
        }
        if (folded == null) {
            throw failure(ExecutionStoreFailure.invalid("batch produced no aggregate state"));
        }
        return folded;
    }

    private GraphVersionPin pinFor(ExecutionKey key, ExecutionBatch batch, Entry existing) {
        GraphVersionPin created = null;
        for (ExecutionTransition transition : batch.transitions()) {
            if (transition instanceof ExecutionTransition.ProcessCreated process) {
                if (existing != null) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "graph version pin is write-once and cannot be reset on " + key.processInstanceId()));
                }
                created = process.graphVersionPin();
            }
        }
        if (created != null) {
            return created;
        }
        if (existing == null) {
            throw failure(ExecutionStoreFailure.invalid("a new process instance requires a graph version pin"));
        }
        return existing.graphVersionPin;
    }

    private static boolean createsInstance(ExecutionBatch batch) {
        return batch.transitions().stream().anyMatch(ExecutionTransition.ProcessCreated.class::isInstance);
    }

    // ---------------------------------------------------------------- lease and work helpers

    private LeaseHandle issueLease(ExecutionKey key, Entry entry, String workerId, Duration ttl, Instant now) {
        if (entry.lease != null && entry.lease.workerId().equals(workerId)
                && now.isBefore(entry.lease.expiresAt())) {
            // The same worker re-claiming keeps its token; reissuing would fence out its own writes.
            var extended = new LeaseHandle(key, workerId, entry.fencingToken,
                    entry.lease.claimedAt(), now.plus(ttl));
            entry.lease = extended;
            return extended;
        }
        // Tokens are never reused after a lease is lost. Within one JVM lifetime this holds because
        // the counter only rises; across a restart it cannot, which is why this adapter does not
        // declare CROSS_PROCESS_LEASE.
        entry.fencingToken++;
        var lease = new LeaseHandle(key, workerId, entry.fencingToken, now, now.plus(ttl));
        entry.lease = lease;
        return lease;
    }

    private static boolean leasedByOther(Entry entry, String workerId, Instant now) {
        return entry.lease != null && !entry.lease.workerId().equals(workerId)
                && now.isBefore(entry.lease.expiresAt());
    }

    private List<ScheduledAttempt> scheduledAttempts(Entry entry, Instant now) {
        var ready = new ArrayList<ScheduledAttempt>();
        for (Traversal traversal : entry.state.traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                for (NodeAttempt attempt : invocation.attempts()) {
                    // SCHEDULED is provably effect-free; RUNNING is the crash case PERS-04 exists for
                    // and must be redeliverable or it would be permanently invisible. PARKED is
                    // deliberately excluded: it has left the claim loop and waits for a human.
                    // See SqliteExecutionStore#claimableAttempts for the full reasoning.
                    if (attempt.status() != NodeAttemptStatus.SCHEDULED
                            && attempt.status() != NodeAttemptStatus.RUNNING) {
                        continue;
                    }
                    if (entry.acknowledged.contains(attempt.attemptId())) {
                        continue;
                    }
                    if (claimVisible(entry, attempt.attemptId(), now)) {
                        continue;
                    }
                    ready.add(new ScheduledAttempt(traversal.traversalId(), invocation.invocationId(), attempt,
                            invocation.command()));
                }
            }
        }
        return ready;
    }

    private List<TimerSchedule> dueTimerEntries(Entry entry, Instant now) {
        var due = new ArrayList<TimerSchedule>();
        for (TimerSchedule timer : entry.timers.values()) {
            if (now.isBefore(timer.dueAt())) {
                continue;
            }
            if (entry.acknowledged.contains(timer.timerId())) {
                continue;
            }
            if (claimVisible(entry, timer.timerId(), now)) {
                continue;
            }
            due.add(timer);
        }
        return due;
    }

    /** A claimed item stays invisible until its visibility window elapses, then is redelivered. */
    private static boolean claimVisible(Entry entry, UUID workItemId, Instant now) {
        WorkClaim claim = entry.workClaims.get(workItemId);
        return claim != null && now.isBefore(claim.visibleAgainAt);
    }

    private PendingWork claimAttempt(ExecutionKey key, Entry entry, ScheduledAttempt attempt,
                                     LeaseHandle lease, Instant now, Duration leaseTtl) {
        int delivery = registerClaim(entry, attempt.attempt().attemptId(), now, leaseTtl);
        return new PendingWork.AttemptDispatch(key, attempt.attempt().attemptId(), attempt.traversalId(),
                attempt.invocationId(), attempt.attempt().attemptId(), attempt.attempt().ordinal(),
                lease.fencingToken(), lease.expiresAt(), delivery, attempt.command());
    }

    private PendingWork.TimerDue claimTimer(ExecutionKey key, Entry entry, TimerSchedule timer,
                                            LeaseHandle lease, Instant now, Duration leaseTtl) {
        int delivery = registerClaim(entry, timer.timerId(), now, leaseTtl);
        return new PendingWork.TimerDue(key, timer.timerId(), timer.traversalId(),
                timer.invocationId(), timer.dueAt(), timer.payload(),
                lease.fencingToken(), lease.expiresAt(), delivery);
    }

    private static int registerClaim(Entry entry, UUID workItemId, Instant now, Duration leaseTtl) {
        WorkClaim previous = entry.workClaims.get(workItemId);
        int delivery = previous == null ? 1 : previous.deliveryAttempt + 1;
        entry.workClaims.put(workItemId, new WorkClaim(delivery, now.plus(leaseTtl)));
        return delivery;
    }

    /**
     * An attempt that left {@code SCHEDULED} and later returns — a retry appends a new attempt id, so
     * this only clears acknowledgements whose work no longer exists.
     */
    private static void dropAcknowledgementsForRescheduledWork(Entry entry) {
        var live = new HashSet<UUID>();
        entry.state.traversals().values().forEach(traversal ->
                traversal.invocations().values().forEach(invocation ->
                        invocation.attempts().forEach(attempt -> live.add(attempt.attemptId()))));
        live.addAll(entry.timers.keySet());
        // Handler identities are work-item identities too, and a terminal handler is retained rather
        // than deleted, so its acknowledgement must be retained with it. Omitting them here would
        // drop the acknowledgement on the next write and redeliver a resolved handler's trigger
        // forever.
        live.addAll(entry.handlers.keySet());
        entry.acknowledged.retainAll(live);
        entry.workClaims.keySet().retainAll(live);
    }

    // ---------------------------------------------------------------- durable handlers

    @Override
    public CompletionStage<Optional<DurableHandler>> loadHandler(ExecutionKey key, UUID handlerId) {
        return complete(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(handlerId, "handlerId");
            synchronized (monitor) {
                Entry entry = instances.get(key);
                // Absent instance and absent handler answer the same way. The caller's next step is
                // identical in both cases, and distinguishing them would let a probe learn that a
                // process instance exists in a tenant it cannot otherwise read.
                return entry == null ? Optional.empty()
                        : Optional.ofNullable(entry.handlers.get(handlerId));
            }
        });
    }

    @Override
    public CompletionStage<Optional<DurableHandler>> findHandler(String tenantId, String handlerName,
                                                                 String correlationKey) {
        return complete(() -> {
            requireTenantId(tenantId);
            HandlerRegistration.requireBoundedKey(handlerName, "handlerName");
            HandlerRegistration.requireBoundedKey(correlationKey, "correlationKey");
            synchronized (monitor) {
                // No batch in flight on the read path, so committed state is all there is to see.
                return liveHandler(tenantId, null, Map.of(), handlerName, correlationKey, null);
            }
        });
    }

    @Override
    public CompletionStage<List<DurableHandler>> handlers(ExecutionKey key) {
        return complete(() -> {
            Objects.requireNonNull(key, "key");
            synchronized (monitor) {
                Entry entry = instances.get(key);
                return entry == null ? List.<DurableHandler>of() : List.copyOf(entry.handlers.values());
            }
        });
    }

    /**
     * Folds this batch's registrations and handler transitions into {@code handlers}.
     *
     * <p>Called with the monitor held and with nothing published yet, so every rejection below leaves
     * the store exactly as it found it. The map is a copy of the instance's own; it replaces the
     * committed one only after {@link #apply(ExecutionBatch)} finishes validating.</p>
     */
    private void applyHandlerWrites(ExecutionKey key, ExecutionBatch batch, ProcessInstance folded,
                                    Map<UUID, DurableHandler> handlers, long revision) {
        for (HandlerRegistration registration : batch.handlersToRegister()) {
            registerHandler(key, folded, handlers, registration, revision);
        }
        for (HandlerTransition transition : batch.handlerTransitions()) {
            transitionHandler(batch, folded, handlers, transition, revision);
        }
    }

    private void registerHandler(ExecutionKey key, ProcessInstance folded,
                                 Map<UUID, DurableHandler> handlers, HandlerRegistration registration,
                                 long revision) {
        requireInvocationExists(folded, registration.traversalId(), registration.invocationId(),
                "handler " + registration.handlerId());

        DurableHandler byDeduplication = handlerByDeduplicationKey(key.tenantId(), key, handlers,
                registration.deduplicationKey());
        if (byDeduplication != null) {
            // Registration is exactly-once, and this is what makes a retried wait safe: a crash
            // between the WAITING transition and this registration is recovered by re-sending the
            // identical batch. A DIFFERENT registration under the same key is a caller bug rather
            // than a retry, and answering it as a success would silently discard a handler somebody
            // asked for.
            if (!byDeduplication.matches(registration)) {
                throw failure(ExecutionStoreFailure.invalid("deduplication key "
                        + registration.deduplicationKey() + " already registers handler "
                        + byDeduplication.handlerId() + ", which is not the handler being registered"));
            }
            return;
        }

        Optional<DurableHandler> contender = liveHandler(key.tenantId(), key, handlers,
                registration.name(), registration.correlationKey(), registration.handlerId());
        if (contender.isPresent()) {
            throw failure(new ExecutionStoreFailure.HandlerCorrelationTaken(registration.name(),
                    registration.correlationKey()));
        }
        if (handlers.containsKey(registration.handlerId())) {
            throw failure(ExecutionStoreFailure.invalid("handler " + registration.handlerId()
                    + " is already registered under a different deduplication key"));
        }
        handlers.put(registration.handlerId(), DurableHandler.waiting(key, registration, revision));
    }

    private void transitionHandler(ExecutionBatch batch, ProcessInstance folded,
                                   Map<UUID, DurableHandler> handlers, HandlerTransition transition,
                                   long revision) {
        DurableHandler current = handlers.get(transition.handlerId());
        if (current == null) {
            // InvalidRequest rather than NotFound: NotFound names a process instance, and the
            // instance is present -- it is the handler inside it that this batch invented.
            throw failure(ExecutionStoreFailure.invalid("unknown handler " + transition.handlerId()));
        }
        // A redelivered escalation timer must not be able to turn an escalation into a failure.
        // Every other repeat is a duplicate and is refused.
        if (transition.next() == HandlerStatus.ESCALATED && current.status() == HandlerStatus.ESCALATED) {
            return;
        }
        if (!current.status().canTransitionTo(transition.next())) {
            throw failure(new ExecutionStoreFailure.HandlerNotResolvable(current.handlerId(),
                    current.status(), transition.next()));
        }
        if (transition.next().resumesProcess()) {
            requireBatchCreatedTraversal(batch, transition.resumeTraversalId(),
                    "handler " + current.handlerId() + " resume");
            requireTraversalExists(folded, transition.resumeTraversalId(),
                    "handler " + current.handlerId() + " resume");
        }
        if (transition.next() == HandlerStatus.RESOLVED) {
            // Only a resolution supplies the body the handler was declared to be waiting for. A
            // denial carries a refusal reason, which is a different shape by nature, and holding it
            // to the awaited schema would make "no" unrepresentable.
            current.payloadSchema().rejectionOf(transition.outcomePayload())
                    .ifPresent(reason -> {
                        throw failure(ExecutionStoreFailure.invalid("handler " + current.handlerId()
                                + " payload was refused: " + reason));
                    });
        }
        handlers.put(current.handlerId(), current.apply(transition, revision));
    }

    /**
     * The single live handler for one correlation key, ignoring {@code excludedHandlerId}.
     *
     * <p>Reads through {@link #tenantHandlers}, so it sees the batch's own registrations as well as
     * committed ones. The exclusion exists so a re-registration of the same handler does not collide
     * with itself.</p>
     */
    private Optional<DurableHandler> liveHandler(String tenantId, ExecutionKey writtenKey,
                                                 Map<UUID, DurableHandler> pending, String handlerName,
                                                 String correlationKey, UUID excludedHandlerId) {
        for (DurableHandler handler : tenantHandlers(tenantId, writtenKey, pending)) {
            if (handler.status().terminal()) {
                continue;
            }
            if (!handler.name().equals(handlerName) || !handler.correlationKey().equals(correlationKey)) {
                continue;
            }
            if (handler.handlerId().equals(excludedHandlerId)) {
                continue;
            }
            return Optional.of(handler);
        }
        return Optional.empty();
    }

    private DurableHandler handlerByDeduplicationKey(String tenantId, ExecutionKey writtenKey,
                                                     Map<UUID, DurableHandler> pending,
                                                     String deduplicationKey) {
        for (DurableHandler handler : tenantHandlers(tenantId, writtenKey, pending)) {
            if (handler.deduplicationKey().equals(deduplicationKey)) {
                return handler;
            }
        }
        return null;
    }

    /**
     * Every handler of {@code tenantId} as this batch will leave them, not as they were committed.
     *
     * <p>{@code pending} is the in-flight copy of {@code writtenKey}'s handlers, already carrying the
     * registrations this batch has folded so far, and it therefore <strong>replaces</strong> that
     * instance's committed map rather than adding to it. Reading committed state here instead would
     * make both uniqueness rules blind to the batch's own earlier registrations: two handlers sharing
     * a correlation key, or a deduplication key, would be refused when they arrive in two batches and
     * accepted when they arrive in one. A physically isolated adapter gets this for free — its
     * lookups are queries inside the write transaction, so they already see rows the same transaction
     * inserted — and an in-memory adapter that skipped it would diverge from every real one on a
     * uniqueness rule that decides which of two concurrent triggers wins.</p>
     *
     * <p>Called with the monitor held, and {@code pending} is a batch-local copy, so nothing here can
     * observe a partially folded batch belonging to another writer.</p>
     * @param tenantId tenant whose handlers are being enumerated.
     * @param writtenKey the instance this batch writes, whose committed handlers {@code pending}
     *                   supersedes, or {@code null} on a read path where no batch is in flight.
     * @param pending in-flight handler map for {@code writtenKey}; empty on a read path.
     * @return handlers visible to this batch, in instance then registration order.
     */
    private List<DurableHandler> tenantHandlers(String tenantId, ExecutionKey writtenKey,
                                                Map<UUID, DurableHandler> pending) {
        var visible = new ArrayList<DurableHandler>(pending.values());
        for (var instance : instances.entrySet()) {
            if (!instance.getKey().tenantId().equals(tenantId)) {
                continue;
            }
            // Superseded, not merged: `pending` already contains this instance's committed handlers
            // plus whatever the batch has folded, so adding the committed map again would return the
            // pre-batch copy of a handler this batch has just transitioned.
            if (instance.getKey().equals(writtenKey)) {
                continue;
            }
            visible.addAll(instance.getValue().handlers.values());
        }
        return visible;
    }

    /**
     * Requires that {@code traversalId} is a traversal <em>this batch created</em>.
     *
     * <p>Existence in the post-fold aggregate is not enough. A terminal handler transition naming a
     * traversal that was already there — the very traversal that was waiting, for instance — would
     * commit, and the trigger the store then offers would point a claimant at a traversal still in
     * {@code WAITING} that nothing authorized it to resume. The re-entry point has to be created by
     * the same batch that authorizes it, which is the whole of "the resolution and the traversal it
     * authorizes commit together or neither does".</p>
     */
    private void requireBatchCreatedTraversal(ExecutionBatch batch, UUID traversalId, String what) {
        boolean created = batch.transitions().stream()
                .anyMatch(transition -> transition instanceof ExecutionTransition.TraversalAdded added
                        && added.traversal().traversalId().equals(traversalId));
        if (!created) {
            throw failure(ExecutionStoreFailure.invalid(what + " names traversal " + traversalId
                    + ", which this batch did not create"));
        }
    }

    private void requireTraversalExists(ProcessInstance folded, UUID traversalId, String what) {
        if (folded == null || !folded.traversals().containsKey(traversalId)) {
            throw failure(ExecutionStoreFailure.invalid(what + " names traversal " + traversalId
                    + ", which this batch neither found nor created"));
        }
    }

    private void requireInvocationExists(ProcessInstance folded, UUID traversalId, UUID invocationId,
                                         String what) {
        requireTraversalExists(folded, traversalId, what);
        if (!folded.traversals().get(traversalId).invocations().containsKey(invocationId)) {
            throw failure(ExecutionStoreFailure.invalid(what + " names invocation " + invocationId
                    + ", which traversal " + traversalId + " does not contain"));
        }
    }

    private List<DurableHandler> claimableTriggers(Entry entry, Instant now) {
        var ready = new ArrayList<DurableHandler>();
        for (DurableHandler handler : entry.handlers.values()) {
            if (!handler.status().resumesProcess()) {
                continue;
            }
            if (entry.acknowledged.contains(handler.handlerId())) {
                continue;
            }
            if (claimVisible(entry, handler.handlerId(), now)) {
                continue;
            }
            ready.add(handler);
        }
        return ready;
    }

    private PendingWork.HandlerTrigger claimTrigger(ExecutionKey key, Entry entry, DurableHandler handler,
                                                    LeaseHandle lease, Instant now, Duration leaseTtl) {
        int delivery = registerClaim(entry, handler.handlerId(), now, leaseTtl);
        // The RE-ENTRY traversal, never the one that was waiting: the claimant runs the traversal the
        // resolution authorized, and the waiting traversal's own history stays closed.
        //
        // The invocation is ABSENT, not the waiting one. Pairing a new traversal with an invocation
        // that lives under the old one produces a pair no lookup resolves -- a claimant asking the
        // re-entry traversal for that invocation gets null -- and it is the invocation the wait is
        // over for, so naming it would also read as work still to do. The claimant creates the
        // re-entry invocation itself; the waiting one stays reachable through the handler, whose id
        // is this item's own workItemId.
        return new PendingWork.HandlerTrigger(key, handler.handlerId(), handler.resumeTraversalId(),
                null, handler.name(), handler.outcomePayload(),
                lease.fencingToken(), lease.expiresAt(), delivery);
    }

    // ---------------------------------------------------------------- validation helpers

    private Instant watermarkOf(String tenantId) {
        return forgottenBefore.getOrDefault(tenantId, Instant.MIN);
    }

    /**
     * Fail-closed classification of an absent key. Comparing {@code keyIssuedAt - maxClockSkew}
     * rather than {@code keyIssuedAt} means every case ambiguous within the declared budget resolves
     * to expired rather than to "safe to apply".
     */
    private boolean provablyNeverRecorded(String tenantId, Instant keyIssuedAt) {
        return !minusClamped(keyIssuedAt, maxClockSkew).isBefore(watermarkOf(tenantId));
    }

    private void requireIssuanceWithinSkewBudget(Instant keyIssuedAt) {
        if (keyIssuedAt == null) {
            throw failure(ExecutionStoreFailure.invalid("keyIssuedAt is mandatory"));
        }
        if (keyIssuedAt.isAfter(plusClamped(clock.instant(), maxClockSkew))) {
            throw failure(ExecutionStoreFailure.invalid("keyIssuedAt " + keyIssuedAt
                    + " is later than the store clock plus the declared " + maxClockSkew
                    + " skew budget; the caller's clock is wrong"));
        }
    }

    /** {@code Instant.MIN}/{@code MAX} arithmetic overflows rather than saturating, so clamp it. */
    private static Instant minusClamped(Instant instant, Duration amount) {
        try {
            return instant.minus(amount);
        } catch (ArithmeticException | java.time.DateTimeException overflow) {
            return Instant.MIN;
        }
    }

    private static Instant plusClamped(Instant instant, Duration amount) {
        try {
            return instant.plus(amount);
        } catch (ArithmeticException | java.time.DateTimeException overflow) {
            return Instant.MAX;
        }
    }

    /**
     * ADR 0010 section 13.2: a {@code NotPresent} expectation and a fencing token contradict each
     * other within a single request.
     *
     * <p>A token is issued only by a successful claim, and a claim requires the instance to exist,
     * so a caller holding a genuine token holds proof of the very existence {@code NotPresent}
     * denies. The contradiction holds for every possible stored state, which is what makes it
     * {@link ExecutionStoreFailure.InvalidRequest} under section 12.3 rather than a state-dependent
     * rejection, and why it is decided here — before any stored state is consulted.</p>
     *
     * <p>Accepting it silently, as this store previously did, left the caller believing it had
     * written under a fence when it had not: no error, no observable effect, no signal to an
     * operator.</p>
     */
    private void requireNoFencingTokenUnderNotPresent(ExecutionBatch batch) {
        if (batch.expectation() instanceof RevisionExpectation.NotPresent
                && batch.fencingToken().isPresent()) {
            throw failure(ExecutionStoreFailure.invalid(
                    "a batch asserting NotPresent cannot present a fencing token (presented "
                            + batch.fencingToken().getAsLong() + "): a token is issued only by a "
                            + "claim on an existing instance, so the request contradicts itself"));
        }
    }

    private void requireWithinPayloadLimit(OpaquePayload payload) {
        if (payload.size() > maxPayloadBytes) {
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.size(), maxPayloadBytes));
        }
    }

    // ---------------------------------------------------------------- event journal and outbox

    @Override
    public Duration journalRetention() {
        return journalRetention;
    }

    @Override
    public CompletionStage<List<JournalRecord>> readJournal(String tenantId, long afterOffset, int limit) {
        return complete(() -> {
            requireCapability(StoreCapability.EVENT_JOURNAL);
            requireTenantId(tenantId);
            if (afterOffset < 0) {
                throw failure(ExecutionStoreFailure.invalid("afterOffset cannot be negative"));
            }
            if (limit < 1) {
                throw failure(ExecutionStoreFailure.invalid("limit must be positive"));
            }
            synchronized (monitor) {
                TenantJournal journal = journalOf(tenantId);
                // Strictly below, not at or below: a caller resuming from the last offset it saw is
                // asking for what comes after a record it already has, and that record being the
                // oldest survivor is the healthy steady state rather than a truncation.
                if (afterOffset + 1 < journal.retainedFrom) {
                    throw failure(new ExecutionStoreFailure.JournalTruncated(tenantId, afterOffset,
                            journal.retainedFrom));
                }
                var page = new ArrayList<JournalRecord>();
                for (JournalRecord record : journal.records) {
                    if (record.journalOffset() <= afterOffset) {
                        continue;
                    }
                    // The integrity check is on the READ path deliberately. Verifying only on write
                    // would prove the digest was computed correctly and nothing about whether the
                    // bytes survived, which is the entire question a digest exists to answer.
                    if (!record.envelope().digestMatchesContent()) {
                        throw failure(new ExecutionStoreFailure.Corrupted(record.key(),
                                "journal offset " + record.journalOffset() + " carries digest "
                                        + record.envelope().digest().hex() + ", which does not match its content"));
                    }
                    page.add(record);
                    if (page.size() == limit) {
                        break;
                    }
                }
                return List.copyOf(page);
            }
        });
    }

    @Override
    public CompletionStage<Long> journalRetainedFrom(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                return journalOf(tenantId).retainedFrom;
            }
        });
    }

    @Override
    public CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
        return complete(() -> {
            requireCapability(StoreCapability.EVENT_JOURNAL);
            requireTenantId(tenantId);
            requireDestination(destination);
            synchronized (monitor) {
                return new JournalCursor(tenantId, destination,
                        journalOf(tenantId).cursors.getOrDefault(destination, 0L));
            }
        });
    }

    @Override
    public CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected, long throughOffset) {
        return complete(() -> {
            requireCapability(StoreCapability.EVENT_JOURNAL);
            Objects.requireNonNull(expected, "expected");
            if (throughOffset < expected.deliveredThrough()) {
                throw failure(ExecutionStoreFailure.invalid("a cursor cannot retreat: destination "
                        + expected.destination() + " is at " + expected.deliveredThrough()
                        + " and was asked to move to " + throughOffset));
            }
            synchronized (monitor) {
                TenantJournal journal = journalOf(expected.tenantId());
                long stored = journal.cursors.getOrDefault(expected.destination(), 0L);
                if (stored != expected.deliveredThrough()) {
                    // RETRY_AFTER_REREAD, so the loser re-reads instead of advancing past events it
                    // never delivered. A blind retry of a stale advance is the silent-loss case.
                    throw failure(new ExecutionStoreFailure.OutboxCursorConflict(expected.tenantId(),
                            expected.destination(), expected.deliveredThrough(), stored));
                }
                journal.cursors.put(expected.destination(), throughOffset);
                return new JournalCursor(expected.tenantId(), expected.destination(), throughOffset);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId, UUID eventId,
                                                        Duration retention) {
        return complete(() -> {
            requireCapability(StoreCapability.EVENT_JOURNAL);
            requireTenantId(tenantId);
            if (consumerId == null || consumerId.isBlank()) {
                throw failure(ExecutionStoreFailure.invalid("consumerId cannot be blank"));
            }
            Objects.requireNonNull(eventId, "eventId");
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw failure(ExecutionStoreFailure.invalid("inbox retention must be positive and is mandatory"));
            }
            synchronized (monitor) {
                var key = new InboxKey(tenantId, consumerId, eventId);
                Instant expiresAt = plusClamped(clock.instant(), retention);
                Instant present = inbox.get(key);
                if (present != null) {
                    // Already recorded, so the caller must NOT apply the effect. The retention is
                    // extended rather than left alone: a redelivery is evidence the sender still
                    // believes this event is in flight, so forgetting it on the original schedule
                    // would let the next redelivery be treated as first.
                    if (expiresAt.isAfter(present)) {
                        inbox.put(key, expiresAt);
                    }
                    return Boolean.FALSE;
                }
                inbox.put(key, expiresAt);
                return Boolean.TRUE;
            }
        });
    }

    @Override
    public CompletionStage<Long> inboxRecordCount(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                return inbox.keySet().stream().filter(key -> key.tenantId().equals(tenantId)).count();
            }
        });
    }

    @Override
    public CompletionStage<Long> compactJournal(String tenantId) {
        return complete(() -> {
            requireCapability(StoreCapability.JOURNAL_COMPACTION);
            requireTenantId(tenantId);
            synchronized (monitor) {
                TenantJournal journal = journalOf(tenantId);
                // "Delivered by every known destination" is the minimum over all cursors. With no
                // destination at all the minimum is zero, so nothing is compactable — the
                // conservative direction, chosen because treating "nobody is listening" as "everybody
                // received it" would discard the backlog of a deployment whose projection is not
                // enabled yet.
                long deliveredEverywhere = journal.cursors.isEmpty() ? 0L
                        : journal.cursors.values().stream().mapToLong(Long::longValue).min().orElse(0L);
                Instant cutoff = clock.instant().minus(journalRetention);

                long discarded = 0;
                var survivors = new ArrayList<JournalRecord>(journal.records.size());
                for (JournalRecord record : journal.records) {
                    boolean expired = !record.recordedAt().isAfter(cutoff);
                    boolean delivered = record.journalOffset() <= deliveredEverywhere;
                    if (expired && delivered && survivors.isEmpty()) {
                        // Only a contiguous prefix is discarded. Punching a hole in the middle would
                        // leave a journal whose surviving offsets no longer describe a resumable
                        // range, so retainedFrom could not honestly describe it.
                        discarded++;
                        journal.retainedFrom = record.journalOffset() + 1;
                        continue;
                    }
                    survivors.add(record);
                }
                journal.records.clear();
                journal.records.addAll(survivors);
                inbox.entrySet().removeIf(entry -> entry.getKey().tenantId().equals(tenantId)
                        && entry.getValue().isBefore(clock.instant()));
                return discarded;
            }
        });
    }

    /**
     * Appends this batch's envelopes to the tenant journal. Called with the monitor held, from inside
     * {@code apply}, after the instance mutation is published and before anything else can observe
     * either.
     */
    private void appendToJournal(ExecutionKey key, ExecutionBatch batch, long revision, Instant now) {
        if (batch.events().isEmpty()) {
            return;
        }
        TenantJournal journal = journalOf(key.tenantId());
        long stream = streamSequences.getOrDefault(key, 0L);
        for (EventEnvelope envelope : batch.events()) {
            stream++;
            journal.records.add(new JournalRecord(envelope, stream, journal.nextOffset++, revision, now));
        }
        streamSequences.put(key, stream);
    }

    private TenantJournal journalOf(String tenantId) {
        return journals.computeIfAbsent(tenantId, ignored -> new TenantJournal());
    }

    private void requireCapability(StoreCapability capability) {
        if (!capabilities().contains(capability)) {
            throw failure(new ExecutionStoreFailure.CapabilityNotSupported(capability));
        }
    }

    private static void requireDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("destination cannot be blank"));
        }
    }

    /**
     * Every envelope must name this batch's own tenant and instance, decided before any stored state
     * is read (ADR 0010 section 12.3).
     *
     * <p>The tenant half is a security guard rather than a consistency one. An envelope naming
     * another tenant, accepted into this tenant's journal, is delivered to <em>this</em> journal's
     * subscribers — a cross-tenant disclosure manufactured by a caller bug, and one that physical
     * database isolation cannot catch because the row never reaches the other tenant's database.</p>
     */
    private void requireEnvelopesMatchBatch(ExecutionBatch batch) {
        for (EventEnvelope envelope : batch.events()) {
            requireWithinPayloadLimit(envelope.payload());
            if (!envelope.tenantId().equals(batch.key().tenantId())) {
                throw failure(ExecutionStoreFailure.invalid("event " + envelope.eventId() + " names tenant "
                        + envelope.tenantId() + " but the batch writes to " + batch.key().tenantId()));
            }
            if (!envelope.processInstanceId().equals(batch.key().processInstanceId())) {
                throw failure(ExecutionStoreFailure.invalid("event " + envelope.eventId() + " names instance "
                        + envelope.processInstanceId() + " but the batch writes to "
                        + batch.key().processInstanceId()));
            }
        }
    }

    /**
     * One tenant's journal. {@code nextOffset} and {@code retainedFrom} both survive compaction of
     * every record, which is why they are fields rather than derivations from {@code records}: an
     * emptied journal that recomputed them would reissue offsets a destination cursor has already
     * passed, and those events would never be delivered to it.
     */
    private static final class TenantJournal {
        private final List<JournalRecord> records = new ArrayList<>();
        private final Map<String, Long> cursors = new LinkedHashMap<>();
        private long nextOffset = 1L;
        private long retainedFrom = 1L;
    }

    private record InboxKey(String tenantId, String consumerId, UUID eventId) {
    }

    private void requireLeaseTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw failure(ExecutionStoreFailure.invalid("lease ttl must be positive"));
        }
        if (ttl.compareTo(maxLeaseTtl) > 0) {
            throw failure(ExecutionStoreFailure.invalid(
                    "lease ttl " + ttl + " exceeds the declared maximum " + maxLeaseTtl));
        }
    }

    /**
     * The tenant is the routing key a physically isolated adapter would resolve to a connection pool,
     * so a blank one is a caller bug decidable from the request alone, never a lookup that happens to
     * find nothing.
     */
    private static void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("tenantId cannot be blank"));
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("workerId cannot be blank"));
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw failure(ExecutionStoreFailure.invalid("limit must be positive"));
        }
    }

    private static ExecutionStoreException failure(ExecutionStoreFailure failure) {
        return new ExecutionStoreException(failure);
    }

    private static <T> CompletionStage<T> complete(java.util.function.Supplier<T> operation) {
        try {
            return CompletableFuture.completedFuture(operation.get());
        } catch (ExecutionStoreException expected) {
            return CompletableFuture.failedFuture(expected);
        }
    }

    // ---------------------------------------------------------------- state

    private static final class Entry {
        private final ProcessInstance state;
        private final long revision;
        private final GraphVersionPin graphVersionPin;
        private final String tenantId;
        private final Instant updatedAt;
        private long fencingToken;
        private LeaseHandle lease;
        private final Map<UUID, TimerSchedule> timers;
        /** Registration order, which is the order {@code handlers(key)} promises to return. */
        private final Map<UUID, DurableHandler> handlers;
        private final Map<UUID, WorkClaim> workClaims;
        private final Set<UUID> acknowledged;

        private Entry(ProcessInstance state, long revision, GraphVersionPin graphVersionPin, String tenantId,
                      Instant updatedAt, long fencingToken, LeaseHandle lease, Map<UUID, TimerSchedule> timers,
                      Map<UUID, DurableHandler> handlers,
                      Map<UUID, WorkClaim> workClaims, Set<UUID> acknowledged) {
            this.state = state;
            this.revision = revision;
            this.graphVersionPin = graphVersionPin;
            this.tenantId = tenantId;
            this.updatedAt = updatedAt;
            this.fencingToken = fencingToken;
            this.lease = lease;
            this.timers = timers;
            this.handlers = handlers;
            this.workClaims = workClaims;
            this.acknowledged = acknowledged;
        }

        private StoredProcessInstance toStored() {
            return new StoredProcessInstance(state, revision, graphVersionPin, tenantId, updatedAt);
        }

        /**
         * Reconstructs the aggregate through its canonical constructor before handing it out, so a
         * state that no longer satisfies the domain invariants surfaces as {@code Corrupted} rather
         * than escaping into the runtime. An in-memory store holds the already-validated object, so
         * this is defence in depth here; for a store that folds transitions off disk it is the real
         * detection point.
         */
        private StoredProcessInstance toStoredRevalidated(ExecutionKey key) {
            try {
                var revalidated = new ProcessInstance(state.processInstanceId(), state.status(),
                        state.traversals());
                return new StoredProcessInstance(revalidated, revision, graphVersionPin, tenantId, updatedAt);
            } catch (IllegalArgumentException | IllegalStateException corrupted) {
                throw new ExecutionStoreException(
                        new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()), corrupted);
            }
        }
    }

    private record WorkClaim(int deliveryAttempt, Instant visibleAgainAt) {
    }

    private record ScheduledAttempt(UUID traversalId, UUID invocationId, NodeAttempt attempt,
                                    ai.ravenroot.api.execution.NodeCommand command) {
    }

    private record IdempotencyKey(String tenantId, String key) {
    }
}
