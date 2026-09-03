package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.InventoryCursor;
import ai.ravenroot.api.persistence.InventoryDisposition;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryPage;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    /**
     * The largest inventory page this adapter returns, matching the deployment registry's own page
     * bound so a caller does not learn two different maxima from one product.
     */
    private static final int DEFAULT_MAX_INVENTORY_PAGE_SIZE = 100;
    /**
     * Terminal-instance retention default. Seven days, matching {@code SqliteStoreConfig}, so swapping
     * adapters does not silently change how long a completed execution stays discoverable. The reason
     * for the number is in that record's Javadoc; it is repeated as a constant rather than shared,
     * because core must not depend on a persistence adapter.
     */
    private static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofDays(7);

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
    /**
     * Per-tenant inventory retention floor. Absent means nothing has been purged, which reads as
     * {@link Instant#MIN} — the same convention {@link #forgottenBefore} uses, and for the same
     * reason: writing a floor at store creation would record a forgetting that never happened.
     */
    private final Map<String, Instant> inventoryRetainedFrom = new LinkedHashMap<>();
    private final AtomicLong revisionSequence = new AtomicLong();
    private final Clock clock;
    private final Duration maxLeaseTtl;
    private final int maxPayloadBytes;
    private final Duration maxClockSkew;
    private final Duration journalRetention;
    private final Duration terminalRetention;

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
        this(clock, maxLeaseTtl, maxPayloadBytes, maxClockSkew, journalRetention,
                DEFAULT_TERMINAL_RETENTION);
    }

    public InMemoryExecutionStore(Clock clock, Duration maxLeaseTtl, int maxPayloadBytes,
                                  Duration maxClockSkew, Duration journalRetention,
                                  Duration terminalRetention) {
        this.terminalRetention = Objects.requireNonNull(terminalRetention, "terminalRetention");
        if (terminalRetention.isZero() || terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must be positive");
        }
        this.journalRetention = Objects.requireNonNull(journalRetention, "journalRetention");
        if (journalRetention.isZero() || journalRetention.isNegative()) {
            throw new IllegalArgumentException("journalRetention must be positive");
        }
        if (terminalRetention.compareTo(journalRetention) < 0) {
            // The same guard SqliteStoreConfig's canonical constructor applies, and it belongs on both
            // adapters because the reason for it is a property of the contract rather than of the
            // medium: a terminal instance pruned while its own events are still readable leaves the
            // journal naming an instance the inventory can no longer describe, and every event
            // replayed from there resolves to "never existed". Enforcing it in only one adapter would
            // let a deployment reach a state through the reference store that the durable store
            // refuses, and discover the difference on the day it swapped them.
            throw new IllegalArgumentException("terminalRetention " + terminalRetention
                    + " cannot be shorter than journalRetention " + journalRetention
                    + ": events would outlive the instance they name");
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
        // PROCESS_INVENTORY and INVENTORY_RETENTION join them on the same rule. Neither makes a
        // durability claim: the inventory is served from the rows this adapter already holds, ordering
        // and tenant isolation are properties of the query rather than of the medium, and retention is
        // an explicit purge. Declaring both here means every inventory assertion in the conformance
        // suite executes against two adapters instead of being skipped into invisibility.
        return Set.of(StoreCapability.TRANSACTIONAL_BATCH, StoreCapability.IDEMPOTENCY_PURGE,
                StoreCapability.EVENT_JOURNAL, StoreCapability.JOURNAL_COMPACTION,
                StoreCapability.PROCESS_INVENTORY, StoreCapability.INVENTORY_RETENTION);
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

                // createdAt is written once and never rewritten. It is half of the inventory's sort
                // key, and the whole reason that ordering is stable is that neither component of it
                // can move while a scan is in flight.
                Instant createdAt = existing == null ? now : existing.createdAt;
                // One increment per authoritative status transition actually applied, counted from the
                // batch rather than from a before/after comparison: a batch that moves an instance
                // RUNNING -> WAITING -> RUNNING has applied two transitions, and a comparison of the
                // endpoints would see none. Creation is itself the first transition, into the initial
                // status. A replayed batch never reaches here, so a duplicate delivery cannot inflate
                // the count -- which is what keeps the generation meaningful under at-least-once work
                // delivery.
                long generation = (existing == null ? 0L : existing.lifecycleGeneration)
                        + (existing == null ? 1L : 0L) + processTransitionCount(batch);
                ExecutionOrigin origin = (existing == null ? ExecutionOrigin.none() : existing.origin)
                        .mergedWith(batch.origin());
                // Retention starts when the instance becomes terminal and never restarts, because a
                // terminal instance cannot transition again. A non-terminal row has no retainedUntil at
                // all rather than a far-future one: absent means "retention has not started", and a
                // sentinel would be a date an operator could read as a real deadline.
                Instant retainedUntil = folded.status().terminal()
                        ? (existing != null && existing.retainedUntil != null
                                ? existing.retainedUntil : plusClamped(now, terminalRetention))
                        : null;

                var next = new Entry(folded, revision, pin, key.tenantId(), now,
                        existing == null ? 0L : existing.fencingToken,
                        existing == null ? null : existing.lease,
                        timers,
                        existing == null ? new HashMap<>() : new HashMap<>(existing.workClaims),
                        existing == null ? new HashSet<>() : new HashSet<>(existing.acknowledged),
                        createdAt, generation, origin, retainedUntil);
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
                    if (dispatchable.isEmpty() && dueTimers.isEmpty()) {
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

    // ---------------------------------------------------------------- durable execution inventory

    @Override
    public int maxInventoryPageSize() {
        return DEFAULT_MAX_INVENTORY_PAGE_SIZE;
    }

    @Override
    public Duration terminalRetention() {
        return terminalRetention;
    }

    @Override
    public CompletionStage<ProcessInventoryPage> listProcessInstances(String tenantId,
                                                                      ProcessInventoryQuery query) {
        return complete(() -> {
            requireCapability(StoreCapability.PROCESS_INVENTORY);
            requireTenantId(tenantId);
            requireInventoryQuery(query);
            synchronized (monitor) {
                Instant now = clock.instant();
                InventoryCursor.Position after = query.cursor()
                        .map(cursor -> InventoryCursor.decode(tenantId, cursor))
                        .orElse(null);

                var matching = new ArrayList<Map.Entry<ExecutionKey, Entry>>();
                for (var instance : instances.entrySet()) {
                    // One tenant per call. A physically isolated adapter would not see another
                    // tenant's rows at all; this adapter shares one map, so the filter is what makes
                    // the two indistinguishable to a caller.
                    if (!instance.getKey().tenantId().equals(tenantId)) {
                        continue;
                    }
                    if (!admits(instance.getValue(), query, now)) {
                        continue;
                    }
                    matching.add(instance);
                }
                matching.sort(INVENTORY_ORDER);

                var page = new ArrayList<ProcessInventoryEntry>(Math.min(query.limit(), matching.size()));
                Map.Entry<ExecutionKey, Entry> last = null;
                boolean more = false;
                for (var instance : matching) {
                    if (after != null && !after.precedes(instance.getValue().createdAt,
                            instance.getKey().processInstanceId())) {
                        continue;
                    }
                    if (page.size() == query.limit()) {
                        more = true;
                        break;
                    }
                    page.add(entryOf(instance.getKey(), instance.getValue(), now));
                    last = instance;
                }
                // A next cursor is minted only when a further row was actually seen. Handing one back
                // on a page that happens to be exactly `limit` long would cost every caller one empty
                // round trip, and worse, a caller that treats a present cursor as "there is more" would
                // report work that does not exist.
                Optional<String> next = more && last != null
                        ? Optional.of(InventoryCursor.encode(tenantId, last.getValue().createdAt,
                                last.getKey().processInstanceId()))
                        : Optional.empty();
                return new ProcessInventoryPage(List.copyOf(page), next, inventoryFloorOf(tenantId));
            }
        });
    }

    @Override
    public CompletionStage<Optional<ProcessInventoryEntry>> findProcessInstance(ExecutionKey key) {
        return complete(() -> {
            requireCapability(StoreCapability.PROCESS_INVENTORY);
            Objects.requireNonNull(key, "key");
            synchronized (monitor) {
                Entry entry = instances.get(key);
                // The map is keyed by tenant AND id together, so a cross-tenant hit is not excluded by
                // a check that could be forgotten -- it is not a lookup that can be expressed. Absent
                // and not-yours are therefore the same answer by construction rather than by policy.
                return entry == null ? Optional.<ProcessInventoryEntry>empty()
                        : Optional.of(entryOf(key, entry, clock.instant()));
            }
        });
    }

    @Override
    public CompletionStage<List<TraversalInventoryEntry>> listTraversals(ExecutionKey key) {
        return complete(() -> {
            requireCapability(StoreCapability.PROCESS_INVENTORY);
            Objects.requireNonNull(key, "key");
            synchronized (monitor) {
                Entry entry = instances.get(key);
                if (entry == null) {
                    // NotFound rather than an empty list: an instance with no traversals exists and
                    // honestly reports none, and collapsing the two would make "you asked about
                    // nothing" indistinguishable from "it has nothing".
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                boolean leaseLive = leaseLive(entry, clock.instant());
                var rows = new ArrayList<TraversalInventoryEntry>(entry.state.traversals().size());
                int position = 0;
                for (Traversal traversal : entry.state.traversals().values()) {
                    int invocations = traversal.invocations().size();
                    int parked = 0;
                    for (NodeInvocation invocation : traversal.invocations().values()) {
                        for (NodeAttempt attempt : invocation.attempts()) {
                            if (attempt.status() == NodeAttemptStatus.PARKED) {
                                parked++;
                            }
                        }
                    }
                    rows.add(new TraversalInventoryEntry(key, traversal.traversalId(), position++,
                            traversal.ingressNodeId(), traversal.status(),
                            InventoryDisposition.ofTraversal(traversal.status(), leaseLive, parked > 0),
                            invocations, parked));
                }
                return List.copyOf(rows);
            }
        });
    }

    @Override
    public CompletionStage<Instant> inventoryRetainedFrom(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                return inventoryFloorOf(tenantId);
            }
        });
    }

    /**
     * Removes this tenant's terminal instances whose retention window has elapsed and advances its
     * floor, in that order and only when something was actually removed.
     *
     * <p>The zero guard matters in the same way it does for idempotency: a purge that removed nothing
     * must leave the floor exactly where it was, because the floor is what a caller reads to decide
     * whether an absent row expired or never existed, and advancing it for a tenant that lost nothing
     * would report a retention gap that does not exist -- on every tick of a periodic job.</p>
     */
    @Override
    public CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
        return complete(() -> {
            requireCapability(StoreCapability.INVENTORY_RETENTION);
            requireTenantId(tenantId);
            synchronized (monitor) {
                Instant now = clock.instant();
                var doomed = new ArrayList<ExecutionKey>();
                Instant latest = null;
                for (var instance : instances.entrySet()) {
                    if (!instance.getKey().tenantId().equals(tenantId)) {
                        continue;
                    }
                    Entry entry = instance.getValue();
                    // Only terminal rows are eligible, however old a non-terminal one is. Age is not
                    // evidence that work is finished, and pruning a stuck instance would destroy the
                    // row an operator needs in order to discover that it is stuck.
                    //
                    // The deadline comes from the same resolution a reader is given, not from the raw
                    // field: a purge that decided eligibility differently from what findProcessInstance
                    // reports would remove a row whose own deadline said it was safe.
                    Optional<Instant> deadline = retainedUntilOf(entry);
                    if (deadline.isEmpty() || deadline.get().isAfter(now)) {
                        continue;
                    }
                    doomed.add(instance.getKey());
                    if (latest == null || deadline.get().isAfter(latest)) {
                        latest = deadline.get();
                    }
                }
                if (doomed.isEmpty()) {
                    return 0L;
                }
                doomed.forEach(instances::remove);
                doomed.forEach(streamSequences::remove);
                // The floor is the LATEST retention deadline this run actually crossed. It has to be
                // the latest, because the guarantee runs in the direction "everything past it is still
                // here": a run removing two rows whose deadlines are further apart than the retention
                // window would, with the earliest, publish a floor the later row sits after -- and a
                // caller following the documented rule would conclude a genuinely completed execution
                // never existed. One row is the degenerate case where earliest and latest coincide,
                // which is why that mistake survives any test that purges only one.
                //
                // Not `now` either: advancing to now would claim a gap covering rows that are still
                // present, which is safe but uselessly pessimistic. The latest crossed boundary is the
                // tightest honest answer.
                Instant floor = latest;
                inventoryRetainedFrom.merge(tenantId, floor,
                        (current, candidate) -> candidate.isAfter(current) ? candidate : current);
                return (long) doomed.size();
            }
        });
    }

    // ---------------------------------------------------------------- inventory helpers

    /**
     * {@code (createdAt DESC, processInstanceId DESC)}. The id is compared as text rather than by
     * {@link UUID#compareTo}, which orders by signed 64-bit halves and therefore disagrees with the
     * lexicographic order a SQL adapter gets from a TEXT column. Two adapters that ordered ties
     * differently would hand out cursors that skip or repeat rows against each other.
     */
    private static final Comparator<Map.Entry<ExecutionKey, Entry>> INVENTORY_ORDER =
            Comparator.<Map.Entry<ExecutionKey, Entry>, Instant>comparing(item -> item.getValue().createdAt)
                    .thenComparing(item -> item.getKey().processInstanceId().toString())
                    .reversed();

    private Instant inventoryFloorOf(String tenantId) {
        return inventoryRetainedFrom.getOrDefault(tenantId, Instant.MIN);
    }

    private static boolean leaseLive(Entry entry, Instant now) {
        return entry.lease != null && now.isBefore(entry.lease.expiresAt());
    }

    private static boolean anyAttemptParked(Entry entry) {
        for (Traversal traversal : entry.state.traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                for (NodeAttempt attempt : invocation.attempts()) {
                    if (attempt.status() == NodeAttemptStatus.PARKED) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean admits(Entry entry, ProcessInventoryQuery query, Instant now) {
        if (!query.admits(entry.state.status())) {
            return false;
        }
        if (query.deploymentId().isPresent()
                && !query.deploymentId().equals(entry.origin.deploymentId())) {
            return false;
        }
        if (query.ownerWorkerId().isPresent()) {
            // An owner filter matches only a LIVE lease. A lapsed lease names the worker that is no
            // longer renewing, and answering "owned by w" with rows w has abandoned is the opposite of
            // what an operator draining a worker is asking.
            if (!leaseLive(entry, now)
                    || !entry.lease.workerId().equals(query.ownerWorkerId().get())) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a reader is told about retention, and what the purge decides eligibility from — one
     * resolution, used by both, so the two cannot disagree about the same fact.
     *
     * <p>The terminal test is the gate: retention has not started for a non-terminal row, and absent
     * is how that is said rather than a sentinel date a reader could take for a real deadline. The
     * fallback to {@code updatedAt + terminalRetention} exists so this adapter states the identical
     * rule to the SQLite one, where it is reachable through the schema-5 upgrade path. Here it is
     * unreachable — every terminal entry records its deadline in the transaction that made it terminal
     * — and it is written anyway, because a rule expressed in only one of two adapters is a rule the
     * two will eventually differ on.</p>
     */
    private Optional<Instant> retainedUntilOf(Entry entry) {
        if (!entry.state.status().terminal()) {
            return Optional.empty();
        }
        return Optional.of(entry.retainedUntil != null
                ? entry.retainedUntil
                : plusClamped(entry.updatedAt, terminalRetention));
    }

    private ProcessInventoryEntry entryOf(ExecutionKey key, Entry entry, Instant now) {
        boolean leaseLive = leaseLive(entry, now);
        return new ProcessInventoryEntry(key, entry.state.status(),
                InventoryDisposition.ofProcess(entry.state.status(), leaseLive, anyAttemptParked(entry)),
                entry.revision, entry.lifecycleGeneration, entry.graphVersionPin,
                entry.origin.deploymentId(), entry.origin.workloadId(), entry.origin.correlationId(),
                leaseLive ? Optional.of(entry.lease.workerId()) : Optional.empty(),
                entry.fencingToken,
                leaseLive ? Optional.of(entry.lease.expiresAt()) : Optional.empty(),
                entry.state.traversals().size(), entry.createdAt, entry.updatedAt,
                retainedUntilOf(entry));
    }

    private void requireInventoryQuery(ProcessInventoryQuery query) {
        if (query == null) {
            throw failure(ExecutionStoreFailure.invalid("query is mandatory"));
        }
        requireInventoryLimit(query.limit(), maxInventoryPageSize());
        if (query.isSelfContradictory()) {
            throw failure(ExecutionStoreFailure.invalid("a query that filters only for terminal "
                    + "statuses while excluding terminal rows can never match; an empty page would be "
                    + "indistinguishable from there being none"));
        }
    }

    /**
     * Rejects rather than clamps. A silently reduced page is indistinguishable from a last page, and a
     * caller paginating on "fewer rows than I asked for means I am done" would stop early.
     */
    static void requireInventoryLimit(int limit, int maximum) {
        if (limit < 1) {
            throw new ExecutionStoreException(
                    ExecutionStoreFailure.invalid("inventory limit must be positive"));
        }
        if (limit > maximum) {
            throw new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "inventory limit " + limit + " exceeds the declared maximum " + maximum));
        }
    }

    private static long processTransitionCount(ExecutionBatch batch) {
        return batch.transitions().stream()
                .filter(ExecutionTransition.ProcessTransitioned.class::isInstance)
                .count();
    }

    /**
     * Reports which graph definitions this store's instances still pin, so a co-located definition
     * store can decide retention without keeping a reference count of its own.
     *
     * <p>A pin reference and a definition's content address are the same value by construction, so
     * this comparison needs no translation table. That shared identity does <em>not</em> mean an
     * execution recorded before definitions were stored has become recoverable: nothing backfills the
     * document for it, so its address resolves to nothing and it is correctly reported here as
     * referencing a definition this store does not hold. The consequence for retention is the safe
     * one -- such a pin keeps a definition alive if one is ever stored at that address, and protects
     * nothing otherwise.</p>
     *
     * @return oracle answering whether any instance of a tenant pins a given definition.
     */
    public ai.ravenroot.api.persistence.GraphDefinitionReferences graphDefinitionReferences() {
        return key -> {
            synchronized (monitor) {
                return instances.entrySet().stream().anyMatch(entry ->
                        entry.getKey().tenantId().equals(key.tenantId())
                                && entry.getValue().graphVersionPin.reference()
                                        .equals(key.contentId().value()));
            }
        };
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
            inventoryRetainedFrom.clear();
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
        entry.acknowledged.retainAll(live);
        entry.workClaims.keySet().retainAll(live);
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
        private final Map<UUID, WorkClaim> workClaims;
        private final Set<UUID> acknowledged;
        private final Instant createdAt;
        private final long lifecycleGeneration;
        private final ExecutionOrigin origin;
        /** Null while the instance is non-terminal: retention has not started, rather than started far off. */
        private final Instant retainedUntil;

        private Entry(ProcessInstance state, long revision, GraphVersionPin graphVersionPin, String tenantId,
                      Instant updatedAt, long fencingToken, LeaseHandle lease, Map<UUID, TimerSchedule> timers,
                      Map<UUID, WorkClaim> workClaims, Set<UUID> acknowledged, Instant createdAt,
                      long lifecycleGeneration, ExecutionOrigin origin, Instant retainedUntil) {
            this.state = state;
            this.revision = revision;
            this.graphVersionPin = graphVersionPin;
            this.tenantId = tenantId;
            this.updatedAt = updatedAt;
            this.fencingToken = fencingToken;
            this.lease = lease;
            this.timers = timers;
            this.workClaims = workClaims;
            this.acknowledged = acknowledged;
            this.createdAt = createdAt;
            this.lifecycleGeneration = lifecycleGeneration;
            this.origin = origin;
            this.retainedUntil = retainedUntil;
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
