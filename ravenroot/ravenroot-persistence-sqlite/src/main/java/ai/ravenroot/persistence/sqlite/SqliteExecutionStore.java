package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventDigest;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The durable single-host {@link ExecutionStore}, backed by one SQLite database in WAL mode (PERS-03).
 *
 * <h2>What it declares, and on what evidence</h2>
 * <ul>
 *   <li>{@link StoreCapability#DURABLE} — every batch commits to a WAL that is fsynced, because
 *   {@link SqliteStoreConfig.SynchronousMode#FULL} is the default. State is read back from the file on
 *   reopen, so nothing survives in memory across a restart.</li>
 *   <li>{@link StoreCapability#TRANSACTIONAL_BATCH} — one {@code BEGIN IMMEDIATE} per batch, one
 *   {@code COMMIT}, and every rejection raised before it. A batch is either wholly in the file or
 *   wholly absent.</li>
 *   <li>{@link StoreCapability#CROSS_PROCESS_LEASE} — leases and fencing tokens live in the database,
 *   and SQLite's file locking serializes writers across operating-system processes. <strong>Scope:
 *   this is honest for several processes on one host sharing a local filesystem, and for nothing
 *   else.</strong> SQLite's locking is built on POSIX advisory locks, which are unreliable over NFS,
 *   SMB and most network or overlay filesystems — there the exclusion silently does not hold and two
 *   processes can both believe they own a lease. Placing the database on such a volume does not
 *   degrade this capability, it falsifies it. Multi-host coordination is PERS-08's problem, not a
 *   configuration of this one.</li>
 *   <li>{@link StoreCapability#IDEMPOTENCY_PURGE} — {@code purgeExpiredIdempotencyRecords} is
 *   implemented and advances the per-tenant watermark.</li>
 * </ul>
 *
 * <h2>The store is its own clock authority</h2>
 * <p>Every temporal predicate is evaluated against the injected {@link Clock}, read once per operation
 * in Java and bound as a parameter. No SQL in this adapter contains {@code strftime('now')},
 * {@code CURRENT_TIMESTAMP} or any other database-side clock. ADR 0010 sections 4 and 7 require expiry
 * and due-ness to be evaluated on the store's clock, and the conformance suite supplies a clock it
 * moves by hand; a predicate reading SQLite's own clock would ignore the injected one entirely, so
 * every lease and timer assertion would either never fire or fire for the wrong reason.</p>
 *
 * <h2>Four boundary conventions, and they are not uniform</h2>
 * <p>Each of these is inclusive or exclusive on its own merits, matching {@code InMemoryExecutionStore}
 * exactly. Writing one from intuition gets at least one of them wrong.</p>
 * <ol>
 *   <li><strong>A lease is live while {@code now < expiresAt}</strong>, strictly. At exactly
 *   {@code expiresAt} it is already gone — the suite sets its clock to precisely the expiry a
 *   contended claim reported and requires the next claim to succeed there, so a caller that waits for
 *   the reported instant and no longer is not busy-looping.</li>
 *   <li><strong>A timer is due when {@code dueAt <= now}</strong>, inclusive. A timer scheduled for the
 *   current instant is due now, not one tick later.</li>
 *   <li><strong>A claimed work item is visible again when {@code visibleAgainAt <= now}</strong>,
 *   inclusive, mirroring the lease it is derived from at the far end of the window.</li>
 *   <li><strong>An idempotency record is collectable when {@code expiresAt < now}</strong>,
 *   <em>strictly</em> — the odd one out. {@code expiresAt} is defined as the earliest instant at which
 *   the store is <em>permitted</em> to forget the record, and a store that forgot it exactly then
 *   would forget it at the first instant of the last moment it promised to retain it.</li>
 * </ol>
 * <p>Two further boundaries govern classification rather than collection, and are evaluated in Java
 * because they compare against a watermark rather than a row: a caller's {@code keyIssuedAt} is
 * rejected only when it is <em>strictly after</em> {@code now + maxClockSkew}, and an absent record is
 * provably-never-recorded when {@code keyIssuedAt - maxClockSkew} is <em>at or after</em> the tenant's
 * watermark, so every case ambiguous within the budget fails closed to expired.</p>
 *
 * <h2>Threading</h2>
 * <p>One connection, confined to one thread, with every operation submitted to it. That is what makes
 * the {@link CompletionStage} signatures honest: the port's own documentation says the adapter must
 * own its execution context because only the adapter knows its blocking profile, and this one blocks
 * on disk. Running the work on the caller's thread would put an fsync on an actor dispatcher once
 * PERS-04 lands. Thread confinement also removes any question of the connection's own thread safety
 * and makes intra-process write serialization structural rather than a lock to remember.</p>
 *
 * <h2>close() and the lease it does not release</h2>
 * <p>{@link ExecutionStore#close()} describes releasing this session's leases as a latency
 * optimisation for rolling restarts and states explicitly that it is "never a correctness
 * requirement". This adapter does not perform it, and cannot: the conformance suite's
 * {@code crossProcessLeaseSurvivesAReopenAndItsFencingTokensAreNeverReused} closes the store and then
 * requires a live lease to still be held by its original worker, so an adapter declaring
 * {@link StoreCapability#CROSS_PROCESS_LEASE} that released on close would fail it. Skipping the
 * optimisation satisfies both documents; performing it satisfies only one. What {@code close()} does
 * do is checkpoint the WAL with {@code TRUNCATE} and close the connection, so the next open starts
 * from a compact file. Leases lapse on the store's clock exactly as they would after a {@code kill -9},
 * which is the invariant section 13.1 actually governs.</p>
 */
public final class SqliteExecutionStore implements ExecutionStore {

    private static final Set<StoreCapability> CAPABILITIES = Set.of(
            StoreCapability.DURABLE,
            StoreCapability.TRANSACTIONAL_BATCH,
            StoreCapability.CROSS_PROCESS_LEASE,
            StoreCapability.IDEMPOTENCY_PURGE,
            StoreCapability.EVENT_JOURNAL,
            StoreCapability.JOURNAL_COMPACTION,
            StoreCapability.DURABLE_HANDLERS);

    /**
     * The one projection every handler read uses, aliased so a correlated subquery cannot silently
     * bind an unqualified column to its own table instead of to this one.
     */
    private static final String HANDLER_COLUMNS = "SELECT h.* FROM execution_handler h";

    private static final int SQLITE_PERM = 3;
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_READONLY = 8;
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_AUTH = 23;
    private static final int SQLITE_NOTADB = 26;

    private final SqliteStoreLocation location;
    private final Path databaseFile;
    private final Clock clock;
    private final SqliteStoreConfig config;
    private final CommitBoundary commitBoundary;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Connection connection;

    public SqliteExecutionStore(Path databaseFile, Clock clock) {
        this(SqliteStoreLocation.ofFile(databaseFile), clock, SqliteStoreConfig.defaults());
    }

    public SqliteExecutionStore(Path databaseFile, Clock clock, SqliteStoreConfig config) {
        this(SqliteStoreLocation.ofFile(databaseFile), clock, config);
    }

    public SqliteExecutionStore(SqliteStoreLocation location, Clock clock) {
        this(location, clock, SqliteStoreConfig.defaults());
    }

    public SqliteExecutionStore(SqliteStoreLocation location, Clock clock, SqliteStoreConfig config) {
        this(location, clock, config, CommitBoundary.NONE);
    }

    SqliteExecutionStore(Path databaseFile, Clock clock, SqliteStoreConfig config,
                         CommitBoundary commitBoundary) {
        this(SqliteStoreLocation.ofFile(databaseFile), clock, config, commitBoundary);
    }

    SqliteExecutionStore(SqliteStoreLocation location, Clock clock, SqliteStoreConfig config,
                         CommitBoundary commitBoundary) {
        this.location = Objects.requireNonNull(location, "location");
        this.databaseFile = location.databaseFile();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.commitBoundary = Objects.requireNonNull(commitBoundary, "commitBoundary");
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ravenroot-sqlite-" + this.databaseFile.getFileName());
            thread.setDaemon(true);
            return thread;
        });
        try {
            this.connection = onWorker(this::open);
        } catch (RuntimeException failed) {
            worker.shutdownNow();
            throw failed;
        }
    }

    // ---------------------------------------------------------------- static self-description

    @Override
    public Set<StoreCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Duration maxLeaseTtl() {
        return config.maxLeaseTtl();
    }

    @Override
    public int maxPayloadBytes() {
        return config.maxPayloadBytes();
    }

    @Override
    public Duration maxClockSkew() {
        return config.maxClockSkew();
    }

    // ---------------------------------------------------------------- write path

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        return async(() -> {
            Objects.requireNonNull(batch, "batch");
            // Decidable from the request alone, so it happens before a transaction is even opened.
            requireNoFencingTokenUnderNotPresent(batch);
            batch.timersToSchedule().forEach(timer -> requireWithinPayloadLimit(timer.payload()));
            batch.idempotency().ifPresent(write -> {
                requireWithinPayloadLimit(write.requestFingerprint());
                requireWithinPayloadLimit(write.outcomeRef());
            });
            batch.handlerTransitions().forEach(transition ->
                    requireWithinPayloadLimit(transition.outcomePayload()));
            requireEnvelopesMatchBatch(batch);
            return inWriteTransaction(batch.key(), () -> applyLocked(batch));
        });
    }

    private StoredProcessInstance applyLocked(ExecutionBatch batch) throws SQLException {
        ExecutionKey key = batch.key();
        InstanceMeta existing = readMeta(key);

        // ADR 0010 section 13.2: fencing -> replay -> expectation -> fold.
        //
        // Fencing precedes every check that could yield a SUCCESS. Answering a fenced worker from its
        // own idempotency record would tell it its work landed and let it briefly believe it still
        // owns the instance, which is the split-brain belief the fence exists to destroy; the new
        // owner is the party that needs the outcome, and it holds a current token.
        //
        // Existence is a PRECONDITION of fencing, not a competitor to it, which is why this is
        // guarded on `existing != null`. A lease on an instance that does not exist is not stale, it
        // is impossible, and reporting FencedOut with a current token of zero would fabricate a value
        // to stand in for the absence of any token.
        if (existing != null) {
            requireFencingTokenCurrent(key, batch, existing);
        }

        StoredProcessInstance replay = replayOf(batch, existing);
        if (replay != null) {
            return replay;
        }

        // Expectation LAST. A write that already happened necessarily bumped the revision, so a
        // retrying caller's expectation is stale by construction; checking it first would make every
        // legitimate replay fail with ConcurrencyConflict.
        requireExpectationMet(key, batch.expectation(), existing, batch);

        ProcessInstance current = existing == null ? null : readAggregate(key, existing);
        ProcessInstance folded = fold(batch, current);
        GraphVersionPin pin = pinFor(key, batch, existing);

        Instant now = clock.instant();
        long revision = existing == null ? 1L : existing.revision() + 1L;
        long fencingToken = existing == null ? 0L : existing.fencingToken();

        writeInstanceRow(key, folded.status(), pin, revision, fencingToken, now);
        AggregateStorage.write(connection, key, folded);
        writeTimers(key, batch);
        // After the aggregate, because a registration may name an invocation this batch created and a
        // terminal transition must name a traversal this batch added; both are validated against the
        // post-fold aggregate. A rejection rolls the enclosing transaction back, which is what makes
        // a wait -- and a re-entry -- atomic with the transitions beside it.
        writeHandlers(key, batch, folded, revision);
        batch.idempotency().ifPresent(write -> writeIdempotencyRecord(key, write, revision, now));
        // Inside the same transaction as the transition above, which is the entirety of PERS-07's
        // shared transactional boundary. There is no publish step to crash between, because there is
        // no publish step: delivery reads the committed journal afterwards.
        writeJournal(key, batch, revision, now);
        dropAcknowledgementsForRescheduledWork(key);

        return new StoredProcessInstance(folded, revision, pin, key.tenantId(), now);
    }

    @Override
    public CompletionStage<StoredProcessInstance> load(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return inReadTransaction(key, () -> {
                InstanceMeta meta = readMeta(key);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                ProcessInstance state = readAggregate(key, meta);
                return new StoredProcessInstance(state, meta.revision(), meta.graphVersionPin(),
                        key.tenantId(), meta.updatedAt());
            });
        });
    }

    // ---------------------------------------------------------------- leases

    @Override
    public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            requireLeaseTtl(ttl);
            requireWorkerId(workerId);
            return inWriteTransaction(key, () -> {
                InstanceMeta meta = readMeta(key);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                Instant now = clock.instant();
                LeaseHandle held = readLease(key, meta.fencingToken());
                if (held != null && !held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
                    // Failing to ACQUIRE, not losing one that was held: ordinary contention, nothing
                    // started and nothing at risk. Reporting it as LeaseLost would bury the rare
                    // critical signal under routine noise.
                    throw failure(new ExecutionStoreFailure.LeaseHeldByAnother(key, held.workerId(),
                            held.expiresAt()));
                }
                return issueLease(key, meta.fencingToken(), held, workerId, ttl, now);
            });
        });
    }

    @Override
    public CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            requireLeaseTtl(ttl);
            ExecutionKey key = lease.key();
            return inWriteTransaction(key, () -> {
                InstanceMeta meta = readMeta(key);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                LeaseHandle held = readLease(key, meta.fencingToken());
                if (held == null || !held.workerId().equals(lease.workerId())
                        || meta.fencingToken() != lease.fencingToken()) {
                    throw failure(new ExecutionStoreFailure.LeaseLost(key, lease.workerId()));
                }
                Instant now = clock.instant();
                if (!now.isBefore(held.expiresAt())) {
                    throw failure(new ExecutionStoreFailure.LeaseLost(key, lease.workerId()));
                }
                // Renewal extends the window without rotating the token: rotating it would fence out
                // the very holder being renewed.
                var renewed = new LeaseHandle(key, lease.workerId(), meta.fencingToken(),
                        held.claimedAt(), now.plus(ttl));
                upsertLease(renewed);
                return renewed;
            });
        });
    }

    @Override
    public CompletionStage<Void> release(LeaseHandle lease) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            ExecutionKey key = lease.key();
            return inWriteTransaction(key, () -> {
                InstanceMeta meta = readMeta(key);
                if (meta == null || meta.fencingToken() != lease.fencingToken()) {
                    // Releasing a lease already lost is a no-op, never a failure.
                    return null;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM lease WHERE tenant_id = ? AND process_instance_id = ? AND worker_id = ?")) {
                    statement.setString(1, key.tenantId());
                    statement.setString(2, key.processInstanceId().toString());
                    statement.setString(3, lease.workerId());
                    statement.executeUpdate();
                }
                // The fencing token is deliberately left where it is. It is not the lease's property:
                // the next claimant must receive a strictly greater one whether the previous holder
                // released early or simply lapsed.
                return null;
            });
        });
    }

    @Override
    public CompletionStage<List<LeaseHandle>> leases(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inReadTransaction(null, () -> {
                Instant now = clock.instant();
                var active = new ArrayList<LeaseHandle>();
                String sql = "SELECT l.process_instance_id, l.worker_id, l.claimed_at_epoch_second, "
                        + "l.claimed_at_nano, l.expires_at_epoch_second, l.expires_at_nano, p.fencing_token "
                        + "FROM lease l JOIN process_instance p ON p.tenant_id = l.tenant_id "
                        + "AND p.process_instance_id = l.process_instance_id "
                        + "WHERE l.tenant_id = ? AND " + StoredInstant.strictlyAfter("l.expires_at")
                        + " ORDER BY l.rowid";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var key = new ExecutionKey(tenantId,
                                    UUID.fromString(rows.getString("process_instance_id")));
                            active.add(new LeaseHandle(key, rows.getString("worker_id"),
                                    rows.getLong("fencing_token"),
                                    StoredInstant.read(rows, "claimed_at"),
                                    StoredInstant.read(rows, "expires_at")));
                        }
                    }
                }
                return List.copyOf(active);
            });
        });
    }

    // ---------------------------------------------------------------- pending work

    @Override
    public CompletionStage<List<PendingWork>> claimPendingWork(String tenantId, String workerId, int limit,
                                                               Duration leaseTtl) {
        return async(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            return inWriteTransaction(null, () -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork>();
                for (ExecutionKey key : instanceKeysOf(tenantId)) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    InstanceMeta meta = readMeta(key);
                    if (meta == null || leasedByOther(key, meta, workerId, now)) {
                        continue;
                    }
                    List<ScheduledAttempt> attempts = claimableAttempts(key, now);
                    List<TimerSchedule> timers = claimableTimers(key, now);
                    List<DurableHandler> triggers = claimableTriggers(key, now);
                    if (attempts.isEmpty() && timers.isEmpty() && triggers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(key, meta.fencingToken(),
                            readLease(key, meta.fencingToken()), workerId, leaseTtl, now);
                    for (ScheduledAttempt attempt : attempts) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimAttempt(key, attempt, lease, now, leaseTtl));
                    }
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(key, timer, lease, now, leaseTtl));
                    }
                    for (DurableHandler handler : triggers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTrigger(key, handler, lease, now, leaseTtl));
                    }
                }
                return List.copyOf(claimed);
            });
        });
    }

    @Override
    public CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId,
                                                                      int limit, Duration leaseTtl) {
        return async(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            return inWriteTransaction(null, () -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork.TimerDue>();
                for (ExecutionKey key : instanceKeysOf(tenantId)) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    InstanceMeta meta = readMeta(key);
                    if (meta == null || leasedByOther(key, meta, workerId, now)) {
                        continue;
                    }
                    List<TimerSchedule> timers = claimableTimers(key, now);
                    if (timers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(key, meta.fencingToken(),
                            readLease(key, meta.fencingToken()), workerId, leaseTtl, now);
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(key, timer, lease, now, leaseTtl));
                    }
                }
                return List.copyOf(claimed);
            });
        });
    }

    @Override
    public CompletionStage<Void> ack(PendingWork item) {
        return async(() -> {
            Objects.requireNonNull(item, "item");
            ExecutionKey key = item.key();
            return inWriteTransaction(key, () -> {
                InstanceMeta meta = readMeta(key);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                if (meta.fencingToken() != item.fencingToken()) {
                    throw failure(new ExecutionStoreFailure.FencedOut(key, item.fencingToken(),
                            meta.fencingToken()));
                }
                int removed;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM work_claim WHERE tenant_id = ? AND process_instance_id = ? "
                                + "AND work_item_id = ?")) {
                    bindItem(statement, key, item.workItemId());
                    removed = statement.executeUpdate();
                }
                if (removed == 0) {
                    throw failure(ExecutionStoreFailure.unknownWorkItem(item.workItemId()));
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO work_acknowledgement (tenant_id, process_instance_id, "
                                + "work_item_id) VALUES (?, ?, ?)")) {
                    bindItem(statement, key, item.workItemId());
                    statement.executeUpdate();
                }
                if (item instanceof PendingWork.TimerDue) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM timer WHERE tenant_id = ? AND process_instance_id = ? "
                                    + "AND timer_id = ?")) {
                        bindItem(statement, key, item.workItemId());
                        statement.executeUpdate();
                    }
                }
                return null;
            });
        });
    }

    // ---------------------------------------------------------------- idempotency

    @Override
    public CompletionStage<Optional<IdempotencyRecord>> lookupIdempotency(String tenantId, String key,
                                                                          Instant keyIssuedAt) {
        return async(() -> {
            requireTenantId(tenantId);
            if (key == null || key.isBlank()) {
                throw failure(ExecutionStoreFailure.invalid("idempotency key cannot be blank"));
            }
            return inReadTransaction(null, () -> {
                requireIssuanceWithinSkewBudget(keyIssuedAt);
                IdempotencyRecord record = readIdempotencyRecord(tenantId, key);
                if (record != null) {
                    return Optional.of(record);
                }
                if (provablyNeverRecorded(tenantId, keyIssuedAt)) {
                    // Any record for this key would still carry an expiresAt at or above the tenant's
                    // watermark, so its absence PROVES it was never written and the caller may safely
                    // apply. That is an absent answer, not a missing entity: nothing was looked up by
                    // instance key, so there is no ExecutionKey a NotFound could truthfully name.
                    return Optional.empty();
                }
                // Expiry stays a failure. Empty means proceed, a failure means stop and resolve;
                // collapsing them in either direction silently re-executes completed work.
                throw failure(new ExecutionStoreFailure.IdempotencyRecordExpired(key));
            });
        });
    }

    @Override
    public CompletionStage<Long> idempotencyRecordCount(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inReadTransaction(null, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ?")) {
                    statement.setString(1, tenantId);
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next() ? rows.getLong(1) : 0L;
                    }
                }
            });
        });
    }

    /**
     * Purges in <strong>two</strong> transactions: the watermark advances in the first, the records
     * are deleted in the second.
     *
     * <p>ADR 0010 section 6.1 makes the order load-bearing — advance, then delete — and one
     * transaction would make that order unobservable, because a crash would undo both and the ordering
     * would never have been tested against anything. Split, the interrupted state is real and
     * reachable: the watermark is ahead of the deletions, which is the conservative direction. A
     * record still present is still answered <em>from the record</em>, so an early watermark costs
     * nothing; the reverse — deleting first and dying before the watermark moved — would leave absent
     * records below a stale watermark, which reads as "never recorded" and silently re-executes.</p>
     *
     * <p>The zero guard is equally load-bearing in the other direction. A purge that forgot nothing
     * must not move the watermark at all: doing so would destroy provable absence for every key the
     * tenant issued before that instant, and a periodic purge job would inflict it on every tick.</p>
     */
    @Override
    public CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();

            boolean anythingToForget = inWriteTransaction(null, () -> {
                if (countExpired(tenantId, now) == 0L) {
                    return false;
                }
                advanceWatermark(tenantId, now);
                return true;
            });
            if (!anythingToForget) {
                return 0L;
            }
            return inWriteTransaction(null, () -> {
                String sql = "DELETE FROM idempotency_record WHERE tenant_id = ? AND "
                        + StoredInstant.strictlyBefore("expires_at");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    @Override
    public CompletionStage<Instant> forgottenBefore(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inReadTransaction(null, () -> watermarkOf(tenantId));
        });
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        try {
            onWorker(() -> {
                try (Statement statement = connection.createStatement()) {
                    // An explicit TRUNCATE checkpoint on a graceful close, so the next open starts
                    // from a compact file and a copy of the database directory is not accompanied by
                    // an arbitrarily large -wal. Automatic checkpointing is left at SQLite's default
                    // during operation: overriding it would trade write latency for a property no
                    // caller asked for, and the port deliberately exposes no checkpoint operation,
                    // because when to checkpoint is an adapter's internal storage concern that no
                    // remote or in-memory adapter could honour.
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // A checkpoint is an optimisation; failing it must not prevent the close.
                }
                connection.close();
                return null;
            });
        } catch (RuntimeException ignored) {
            // Already reported through the connection's own state; closing twice must not throw.
        } finally {
            worker.shutdown();
        }
    }

    Path databaseFile() {
        return databaseFile;
    }

    /** Where this store lives, and the administration that can be performed on it while it is closed. */
    public SqliteStoreLocation location() {
        return location;
    }

    // ---------------------------------------------------------------- administration

    /**
     * Takes a consistent snapshot of the live database <strong>without stopping writers</strong>.
     *
     * <h3>Why {@code VACUUM INTO} and not a file copy</h3>
     * <p>Copying the three files of a WAL database while a writer is running reads them at three
     * different instants, so the snapshot can contain a {@code -wal} describing pages the main file
     * does not have yet. The result usually opens, frequently passes an integrity check, and is a
     * database that never existed. {@code VACUUM INTO} instead runs inside SQLite's own read
     * transaction: it sees one committed point in time, excludes transactions still in flight, and
     * writes a single self-contained file with <em>no</em> sidecars — so the artifact an operator has
     * to store, move and verify is one file rather than a set that must stay together.</p>
     *
     * <p>This is measured behaviour, not an assumption: under a concurrent connection holding
     * {@code BEGIN IMMEDIATE} with an uncommitted row, the snapshot contains the committed rows and
     * not the uncommitted one, and reports {@code integrity_check = ok}.</p>
     *
     * <h3>It runs outside a transaction, deliberately</h3>
     * <p>SQLite refuses {@code VACUUM} from within a transaction, so this is the one operation on this
     * store that is not wrapped in {@code inReadTransaction}. It still runs on the confined connection
     * thread, so it serializes against this store's own writes rather than racing them.</p>
     *
     * <h3>It never overwrites</h3>
     * <p>An existing target is refused rather than replaced. A backup command that silently overwrites
     * is one mistyped path away from destroying the copy an operator is about to depend on, and the
     * cost of the alternative is that the caller chooses a new name.</p>
     *
     * @param target the file to create; its directory is created if absent
     * @return the snapshot path, once written
     */
    public CompletionStage<Path> backupTo(Path target) {
        Objects.requireNonNull(target, "target");
        Path destination = target.toAbsolutePath().normalize();
        return async(() -> {
            if (java.nio.file.Files.exists(destination)) {
                throw failure(ExecutionStoreFailure.invalid("there is already a file at " + destination
                        + "; a backup never overwrites, so choose a name that does not exist yet"));
            }
            Path parent = destination.getParent();
            if (parent != null && !java.nio.file.Files.exists(parent)) {
                try {
                    java.nio.file.Files.createDirectories(parent);
                } catch (java.io.IOException failed) {
                    throw new ExecutionStoreException(new ExecutionStoreFailure.NotAuthorized(
                            "cannot create the backup directory " + parent + ": " + failed), failed);
                }
            }
            try (Statement statement = connection.createStatement()) {
                // Single-quoted SQL literal: the path is an operator-supplied argument, so the quote
                // doubling matters even though a path containing one is rare.
                statement.execute("VACUUM INTO '" + destination.toString().replace("'", "''") + "'");
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed, null);
            }
            return destination;
        });
    }

    /**
     * Reads a pragma on this store's own connection.
     *
     * <p>Package-private and used only by tests, because {@code synchronous} is a <em>connection</em>
     * setting rather than a file one: a second connection opened to check it would report its own
     * default and cheerfully confirm a setting this store never applied.</p>
     */
    String pragmaForTest(String pragma) {
        return onWorker(() -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA " + pragma)) {
                return rows.next() ? rows.getString(1) : null;
            }
        });
    }

    // ---------------------------------------------------------------- opening

    private Connection open() {
        // Before JDBC, because SQLite reports "cannot open" for a missing path, an exhausted descriptor
        // table and a directory the process may not enter, all as the same SQLITE_CANTOPEN. Classifying
        // that as NotAuthorized would be a guess and classifying it as Unavailable tells a caller to
        // retry a permission failure forever, so the filesystem is asked plainly first.
        location.prepare();
        Connection opened;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        } catch (SQLException failed) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                    "cannot open the execution store at " + databaseFile + ": " + failed.getMessage()), failed);
        }
        try {
            configure(opened);
            SqliteSchema.migrate(opened, clock);
            return opened;
        } catch (SQLException failed) {
            closeQuietly(opened);
            throw new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                    "cannot prepare the execution store at " + databaseFile + ": " + failed.getMessage()),
                    failed);
        } catch (RuntimeException failed) {
            closeQuietly(opened);
            throw failed;
        }
    }

    private static void closeQuietly(Connection opened) {
        try {
            opened.close();
        } catch (SQLException ignored) {
            // The failure that got us here is the one worth reporting.
        }
    }

    private void configure(Connection opened) throws SQLException {
        try (Statement statement = opened.createStatement()) {
            // WAL is what allows readers to proceed during a write and what makes a commit an append
            // plus an fsync rather than a rollback-journal round trip. It is a persistent property of
            // the file, so this is a no-op on every open after the first.
            String journalMode;
            try (ResultSet rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                journalMode = rows.next() ? rows.getString(1) : "unknown";
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                        "the database at " + databaseFile + " refused WAL mode and reported '" + journalMode
                                + "'; DURABLE and CROSS_PROCESS_LEASE are not honourable without it"));
            }
            statement.execute("PRAGMA synchronous=" + config.synchronousMode().pragmaValue());
            // Without this, a writer that finds the database locked by another process fails
            // immediately with SQLITE_BUSY. With it, contention becomes a bounded wait, which is what
            // makes CROSS_PROCESS_LEASE usable rather than merely correct.
            statement.execute("PRAGMA busy_timeout=" + config.busyTimeout().toMillis());
            // Off by default in SQLite, and the aggregate rewrite depends on ON DELETE CASCADE to
            // clear invocations, causal edges and attempts when their traversal row goes.
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    // ---------------------------------------------------------------- transactions

    private <T> T inWriteTransaction(ExecutionKey key, SqlWork<T> work) {
        return transact(key, work, true);
    }

    private <T> T inReadTransaction(ExecutionKey key, SqlWork<T> work) {
        return transact(key, work, false);
    }

    /**
     * One transaction per operation. Writers use {@code BEGIN IMMEDIATE} so the write lock is taken
     * when the transaction opens rather than at its first write: a deferred transaction that upgrades
     * mid-flight can fail with {@code SQLITE_BUSY} after it has already read, and the standard
     * response to that is to abandon and retry work that a caller believes is in progress. Taking the
     * lock up front converts the whole class of upgrade deadlocks into a bounded wait on
     * {@code busy_timeout}.
     */
    private <T> T transact(ExecutionKey key, SqlWork<T> work, boolean write) {
        try (Statement control = connection.createStatement()) {
            control.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            T result;
            try {
                result = work.run();
            } catch (SQLException failed) {
                rollbackQuietly(control);
                throw mapBeforeCommit(failed, key);
            } catch (RuntimeException failed) {
                rollbackQuietly(control);
                throw failed;
            }
            if (write) {
                commitBoundary.beforeCommit();
            }
            try {
                control.execute("COMMIT");
            } catch (SQLException failed) {
                rollbackQuietly(control);
                // The one place OutcomeUnknown is truthful: a COMMIT that failed mid-flight is neither
                // known-applied nor known-not-applied from here, and reporting it as Unavailable would
                // assert an absence of effect this adapter cannot observe.
                throw key == null
                        ? new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                                "commit failed: " + failed.getMessage()), failed)
                        : new ExecutionStoreException(new ExecutionStoreFailure.OutcomeUnknown(key,
                                "commit failed: " + failed.getMessage()), failed);
            }
            if (write) {
                commitBoundary.afterCommit();
            }
            return result;
        } catch (SQLException failed) {
            throw mapBeforeCommit(failed, key);
        }
    }

    private static void rollbackQuietly(Statement control) {
        try {
            control.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // The transaction is already gone; the original failure is the one worth reporting.
        }
    }

    /**
     * Classifies a failure raised <em>before</em> the commit, where the adapter knows nothing was
     * applied because it has just rolled back.
     */
    private ExecutionStoreException mapBeforeCommit(SQLException failed, ExecutionKey key) {
        int primary = failed.getErrorCode() & 0xFF;
        return switch (primary) {
            case SQLITE_BUSY, SQLITE_LOCKED -> new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable("the store is locked by another writer: "
                            + failed.getMessage()), failed);
            case SQLITE_PERM, SQLITE_READONLY, SQLITE_AUTH -> new ExecutionStoreException(
                    new ExecutionStoreFailure.NotAuthorized(failed.getMessage()), failed);
            case SQLITE_CORRUPT, SQLITE_NOTADB -> key == null
                    ? new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                            "the database file is not readable: " + failed.getMessage()), failed)
                    : new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                            failed.getMessage()), failed);
            default -> new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable(failed.getMessage()), failed);
        };
    }

    // ---------------------------------------------------------------- batch helpers

    private StoredProcessInstance replayOf(ExecutionBatch batch, InstanceMeta existing) throws SQLException {
        IdempotencyWrite write = batch.idempotency().orElse(null);
        if (write == null) {
            return null;
        }
        // Rejected at WRITE time as well as at lookup, so an operator whose clock runs fast is told
        // while the clock can still be fixed rather than after the damage window has passed.
        requireIssuanceWithinSkewBudget(write.keyIssuedAt());
        ExecutionKey key = batch.key();
        IdempotencyRecord recorded = readIdempotencyRecord(key.tenantId(), write.key());
        if (recorded == null) {
            if (provablyNeverRecorded(key.tenantId(), write.keyIssuedAt())) {
                return null;
            }
            // Applying a batch whose key may have been purged is exactly the silent re-execution this
            // mechanism exists to prevent, so absence is classified on the write path too.
            throw failure(new ExecutionStoreFailure.IdempotencyRecordExpired(write.key()));
        }
        if (!recorded.requestFingerprint().equals(write.requestFingerprint())) {
            throw failure(new ExecutionStoreFailure.IdempotencyConflict(write.key()));
        }
        if (existing == null) {
            // ADR 0010 section 13.3: a record can outlive its instance, because retention windows and
            // instance lifetime are independent. IdempotencyRecordExpired would be wrong here, because
            // the store CAN answer about the record; it is the instance that is gone.
            throw failure(new ExecutionStoreFailure.NotFound(key));
        }
        // A replay answers with CURRENT state, not the state as of recordedAtRevision: its purpose is
        // to let the caller proceed without re-executing, and a caller handed a stale revision derives
        // an expectation from it and loops on ConcurrencyConflict forever.
        return new StoredProcessInstance(readAggregate(key, existing), existing.revision(),
                existing.graphVersionPin(), key.tenantId(), existing.updatedAt());
    }

    private void requireFencingTokenCurrent(ExecutionKey key, ExecutionBatch batch, InstanceMeta existing) {
        if (batch.fencingToken().isEmpty()) {
            return;
        }
        long presented = batch.fencingToken().getAsLong();
        // Inequality, not "lower than": a caller presenting an unissued higher token must not be able
        // to fence out the legitimate owner.
        if (presented != existing.fencingToken()) {
            throw failure(new ExecutionStoreFailure.FencedOut(key, presented, existing.fencingToken()));
        }
    }

    private void requireExpectationMet(ExecutionKey key, RevisionExpectation expectation,
                                       InstanceMeta existing, ExecutionBatch batch) {
        switch (expectation) {
            case RevisionExpectation.NotPresent ignored -> {
                if (existing != null) {
                    throw failure(new ExecutionStoreFailure.AlreadyExists(key, existing.revision()));
                }
            }
            case RevisionExpectation.Exactly exactly -> {
                if (existing == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                if (existing.revision() != exactly.revision()) {
                    throw failure(new ExecutionStoreFailure.ConcurrencyConflict(key, expectation,
                            existing.revision()));
                }
            }
            case RevisionExpectation.Any ignored -> {
                if (existing == null && !createsInstance(batch)) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
            }
        }
    }

    private ProcessInstance fold(ExecutionBatch batch, ProcessInstance current) {
        ProcessInstance folded = current;
        for (ExecutionTransition transition : batch.transitions()) {
            try {
                folded = transition.applyTo(folded);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // A caller's illegal transition is a caller bug, not stored corruption. The two are
                // told apart by WHERE the rejection came from, not by its type: reconstruction of
                // stored rows is Corrupted, folding a caller's batch is InvalidRequest.
                throw new ExecutionStoreException(
                        ExecutionStoreFailure.invalid(rejected.getMessage()), rejected);
            }
        }
        if (folded == null) {
            throw failure(ExecutionStoreFailure.invalid("batch produced no aggregate state"));
        }
        return folded;
    }

    private GraphVersionPin pinFor(ExecutionKey key, ExecutionBatch batch, InstanceMeta existing) {
        GraphVersionPin created = null;
        for (ExecutionTransition transition : batch.transitions()) {
            if (transition instanceof ExecutionTransition.ProcessCreated process) {
                if (existing != null) {
                    throw failure(ExecutionStoreFailure.invalid("graph version pin is write-once and "
                            + "cannot be reset on " + key.processInstanceId()));
                }
                created = process.graphVersionPin();
            }
        }
        if (created != null) {
            return created;
        }
        if (existing == null) {
            throw failure(ExecutionStoreFailure.invalid(
                    "a new process instance requires a graph version pin"));
        }
        return existing.graphVersionPin();
    }

    private static boolean createsInstance(ExecutionBatch batch) {
        return batch.transitions().stream().anyMatch(ExecutionTransition.ProcessCreated.class::isInstance);
    }

    // ---------------------------------------------------------------- row access

    private record InstanceMeta(long revision, long fencingToken, GraphVersionPin graphVersionPin,
                                ProcessInstanceStatus status, Instant updatedAt) {
    }

    private record ScheduledAttempt(UUID traversalId, UUID invocationId, UUID attemptId, int ordinal,
                                    ai.ravenroot.api.execution.NodeCommand command) {
    }

    private InstanceMeta readMeta(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision, fencing_token, graph_version_pin, status, updated_at_epoch_second, "
                        + "updated_at_nano FROM process_instance "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new InstanceMeta(rows.getLong("revision"), rows.getLong("fencing_token"),
                        new GraphVersionPin(rows.getString("graph_version_pin")),
                        ProcessInstanceStatus.valueOf(rows.getString("status")),
                        StoredInstant.read(rows, "updated_at"));
            }
        }
    }

    private ProcessInstance readAggregate(ExecutionKey key, InstanceMeta meta) throws SQLException {
        try {
            return AggregateStorage.read(connection, key, meta.status());
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            // The detection point the in-memory adapter can only simulate: rows that no longer
            // reconstruct into a legal aggregate must never escape into the runtime.
            throw new ExecutionStoreException(
                    new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()), corrupted);
        }
    }

    private void writeInstanceRow(ExecutionKey key, ProcessInstanceStatus status, GraphVersionPin pin,
                                  long revision, long fencingToken, Instant now) throws SQLException {
        // An upsert rather than INSERT OR REPLACE: REPLACE deletes the row first, which would cascade
        // through every child table and, with it, the leases and work claims that must outlive a write.
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO process_instance (tenant_id, process_instance_id, status, graph_version_pin, "
                        + "revision, fencing_token, updated_at_epoch_second, updated_at_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id) DO UPDATE SET status = excluded.status, "
                        + "graph_version_pin = excluded.graph_version_pin, revision = excluded.revision, "
                        + "updated_at_epoch_second = excluded.updated_at_epoch_second, "
                        + "updated_at_nano = excluded.updated_at_nano")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            statement.setString(3, status.name());
            statement.setString(4, pin.reference());
            statement.setLong(5, revision);
            statement.setLong(6, fencingToken);
            StoredInstant.bindValue(statement, 7, now);
            statement.executeUpdate();
        }
    }

    private void writeTimers(ExecutionKey key, ExecutionBatch batch) throws SQLException {
        for (UUID timerId : batch.timersToCancel()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM timer WHERE tenant_id = ? AND process_instance_id = ? AND timer_id = ?")) {
                bindItem(statement, key, timerId);
                statement.executeUpdate();
            }
        }
        // Cancellations first, then schedules: a batch that cancels and reschedules the same id must
        // end with the new timer, not with nothing.
        for (TimerSchedule timer : batch.timersToSchedule()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO timer (tenant_id, process_instance_id, timer_id, traversal_id, "
                            + "invocation_id, payload_content_type, payload_bytes, due_at_epoch_second, "
                            + "due_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, process_instance_id, timer_id) DO UPDATE SET "
                            + "traversal_id = excluded.traversal_id, invocation_id = excluded.invocation_id, "
                            + "payload_content_type = excluded.payload_content_type, "
                            + "payload_bytes = excluded.payload_bytes, "
                            + "due_at_epoch_second = excluded.due_at_epoch_second, "
                            + "due_at_nano = excluded.due_at_nano")) {
                statement.setString(1, key.tenantId());
                statement.setString(2, key.processInstanceId().toString());
                statement.setString(3, timer.timerId().toString());
                statement.setString(4, timer.traversalId() == null ? null : timer.traversalId().toString());
                statement.setString(5, timer.invocationId() == null ? null : timer.invocationId().toString());
                statement.setString(6, timer.payload().contentType());
                statement.setBytes(7, timer.payload().bytes());
                StoredInstant.bindValue(statement, 8, timer.dueAt());
                statement.executeUpdate();
            }
        }
    }

    private void writeIdempotencyRecord(ExecutionKey key, IdempotencyWrite write, long revision, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_record (tenant_id, idempotency_key, "
                        + "request_fingerprint_content_type, request_fingerprint_bytes, "
                        + "outcome_ref_content_type, outcome_ref_bytes, recorded_at_revision, "
                        + "expires_at_epoch_second, expires_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET "
                        + "outcome_ref_content_type = excluded.outcome_ref_content_type, "
                        + "outcome_ref_bytes = excluded.outcome_ref_bytes, "
                        + "recorded_at_revision = excluded.recorded_at_revision, "
                        + "expires_at_epoch_second = excluded.expires_at_epoch_second, "
                        + "expires_at_nano = excluded.expires_at_nano")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, write.key());
            statement.setString(3, write.requestFingerprint().contentType());
            statement.setBytes(4, write.requestFingerprint().bytes());
            statement.setString(5, write.outcomeRef().contentType());
            statement.setBytes(6, write.outcomeRef().bytes());
            statement.setLong(7, revision);
            StoredInstant.bindValue(statement, 8, plusClamped(now, write.retentionWindow()));
            statement.executeUpdate();
        } catch (SQLException failed) {
            throw mapBeforeCommit(failed, key);
        }
    }

    private IdempotencyRecord readIdempotencyRecord(String tenantId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_fingerprint_content_type, request_fingerprint_bytes, "
                        + "outcome_ref_content_type, outcome_ref_bytes, recorded_at_revision, "
                        + "expires_at_epoch_second, expires_at_nano FROM idempotency_record "
                        + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new IdempotencyRecord(key,
                        OpaquePayload.of(rows.getBytes("request_fingerprint_bytes"),
                                rows.getString("request_fingerprint_content_type")),
                        OpaquePayload.of(rows.getBytes("outcome_ref_bytes"),
                                rows.getString("outcome_ref_content_type")),
                        rows.getLong("recorded_at_revision"),
                        StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    private long countExpired(String tenantId, Instant now) throws SQLException {
        String sql = "SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ? AND "
                + StoredInstant.strictlyBefore("expires_at");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            StoredInstant.bindComparison(statement, 2, now);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private void advanceWatermark(String tenantId, Instant now) throws SQLException {
        Instant current = watermarkOf(tenantId);
        // Monotonically non-decreasing: a watermark never retreats, whatever order purges arrive in.
        if (!now.isAfter(current)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_watermark (tenant_id, forgotten_before_epoch_second, "
                        + "forgotten_before_nano) VALUES (?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET "
                        + "forgotten_before_epoch_second = excluded.forgotten_before_epoch_second, "
                        + "forgotten_before_nano = excluded.forgotten_before_nano")) {
            statement.setString(1, tenantId);
            StoredInstant.bindValue(statement, 2, now);
            statement.executeUpdate();
        }
    }

    private Instant watermarkOf(String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT forgotten_before_epoch_second, forgotten_before_nano FROM idempotency_watermark "
                        + "WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // An absent row IS Instant.MIN. Writing one at store creation would be a lie in a
                // table whose whole purpose is to record that something was forgotten, and there is
                // no moment at which a tenant first exists for this adapter to write it at.
                return rows.next() ? StoredInstant.read(rows, "forgotten_before") : Instant.MIN;
            }
        }
    }

    // ---------------------------------------------------------------- leases and work

    private LeaseHandle readLease(ExecutionKey key, long fencingToken) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT worker_id, claimed_at_epoch_second, claimed_at_nano, expires_at_epoch_second, "
                        + "expires_at_nano FROM lease WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new LeaseHandle(key, rows.getString("worker_id"), fencingToken,
                        StoredInstant.read(rows, "claimed_at"), StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    private LeaseHandle issueLease(ExecutionKey key, long currentToken, LeaseHandle held, String workerId,
                                   Duration ttl, Instant now) throws SQLException {
        if (held != null && held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
            // The same worker re-claiming keeps its token; reissuing would fence out its own writes.
            var extended = new LeaseHandle(key, workerId, currentToken, held.claimedAt(), now.plus(ttl));
            upsertLease(extended);
            return extended;
        }
        long nextToken = currentToken + 1L;
        // Durable, and never reset on reopen: the counter is a column on process_instance, so a
        // restart reads it back rather than starting again from zero. A session is not a fencing
        // domain, and a token that repeated across a restart would let a worker resurrected from an
        // old handle write as though it still owned the instance.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE process_instance SET fencing_token = ? WHERE tenant_id = ? "
                        + "AND process_instance_id = ?")) {
            statement.setLong(1, nextToken);
            statement.setString(2, key.tenantId());
            statement.setString(3, key.processInstanceId().toString());
            statement.executeUpdate();
        }
        var lease = new LeaseHandle(key, workerId, nextToken, now, now.plus(ttl));
        upsertLease(lease);
        return lease;
    }

    private void upsertLease(LeaseHandle lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lease (tenant_id, process_instance_id, worker_id, claimed_at_epoch_second, "
                        + "claimed_at_nano, expires_at_epoch_second, expires_at_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id) DO UPDATE SET "
                        + "worker_id = excluded.worker_id, "
                        + "claimed_at_epoch_second = excluded.claimed_at_epoch_second, "
                        + "claimed_at_nano = excluded.claimed_at_nano, "
                        + "expires_at_epoch_second = excluded.expires_at_epoch_second, "
                        + "expires_at_nano = excluded.expires_at_nano")) {
            statement.setString(1, lease.key().tenantId());
            statement.setString(2, lease.key().processInstanceId().toString());
            statement.setString(3, lease.workerId());
            int index = StoredInstant.bindValue(statement, 4, lease.claimedAt());
            StoredInstant.bindValue(statement, index, lease.expiresAt());
            statement.executeUpdate();
        }
    }

    private boolean leasedByOther(ExecutionKey key, InstanceMeta meta, String workerId, Instant now)
            throws SQLException {
        LeaseHandle held = readLease(key, meta.fencingToken());
        return held != null && !held.workerId().equals(workerId) && now.isBefore(held.expiresAt());
    }

    private List<ExecutionKey> instanceKeysOf(String tenantId) throws SQLException {
        var keys = new ArrayList<ExecutionKey>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT process_instance_id FROM process_instance WHERE tenant_id = ? ORDER BY rowid")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(new ExecutionKey(tenantId, UUID.fromString(rows.getString(1))));
                }
            }
        }
        return keys;
    }

    /**
     * Outstanding attempts that are neither acknowledged nor inside a live visibility window.
     *
     * <p>The visibility predicate is the negation of "still invisible", which is why it reads as
     * {@code NOT EXISTS (... AND visible_again_at > now)} rather than
     * {@code visible_again_at <= now}: an item with no claim row at all has never been delivered and
     * must be claimable, and a plain comparison against a missing row is never true.</p>
     *
     * <h3>Why {@code RUNNING} is claimable, and why {@code PARKED} is not (PERS-04, ADR 0022)</h3>
     * <p>An attempt that is still {@code SCHEDULED} has provably not started, because the runtime
     * persists the {@code RUNNING} transition before the engine send. An attempt stuck in
     * {@code RUNNING} is exactly the crash case PERS-04 exists for: dispatched, outcome never learned.
     * Restricting this query to {@code SCHEDULED} would make that case <em>permanently invisible</em>
     * — the work would never be redelivered, so the decided ambiguity rule ({@code deliveryAttempt}
     * greater than one on a {@code RUNNING} attempt) could never fire and a crashed attempt would be
     * silently lost, which the ambiguity rule prevents.</p>
     *
     * <p>A <em>healthy</em> long-running attempt is not redelivered by this, because
     * {@code claimPendingWork} skips any instance whose lease is held by another worker and has not
     * expired: a live runtime renews its lease while its node runs, and only a runtime that has
     * stopped renewing — that is, one that died — lets its instance become claimable again.</p>
     *
     * <p>{@code PARKED} is excluded because parking is a terminal disposition for the claim loop:
     * an attempt whose outcome is unknown must not be handed out again to be re-decided by a machine
     * on every poll. It leaves the loop and waits for a human. {@code WAITING} is excluded because its
     * dispatch outcome is known; it resumes through a timer or a trigger.</p>
     */
    private List<ScheduledAttempt> claimableAttempts(ExecutionKey key, Instant now) throws SQLException {
        String sql = "SELECT i.traversal_id, a.invocation_id, a.attempt_id, a.ordinal, i.node_command "
                + "FROM attempt a JOIN invocation i ON i.tenant_id = a.tenant_id "
                + "AND i.process_instance_id = a.process_instance_id AND i.invocation_id = a.invocation_id "
                + "JOIN traversal tr ON tr.tenant_id = i.tenant_id "
                + "AND tr.process_instance_id = i.process_instance_id AND tr.traversal_id = i.traversal_id "
                + "WHERE a.tenant_id = ? AND a.process_instance_id = ? "
                + "AND a.status IN ('SCHEDULED', 'RUNNING') "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = a.tenant_id "
                + "AND k.process_instance_id = a.process_instance_id AND k.work_item_id = a.attempt_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = a.tenant_id "
                + "AND c.process_instance_id = a.process_instance_id AND c.work_item_id = a.attempt_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                + "ORDER BY tr.position, i.position, a.ordinal";
        var ready = new ArrayList<ScheduledAttempt>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            StoredInstant.bindComparison(statement, 3, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ready.add(new ScheduledAttempt(UUID.fromString(rows.getString("traversal_id")),
                            UUID.fromString(rows.getString("invocation_id")),
                            UUID.fromString(rows.getString("attempt_id")), rows.getInt("ordinal"),
                            ai.ravenroot.api.execution.NodeCommand.parse(rows.getString("node_command"))));
                }
            }
        }
        return ready;
    }

    private List<TimerSchedule> claimableTimers(ExecutionKey key, Instant now) throws SQLException {
        String sql = "SELECT t.timer_id, t.traversal_id, t.invocation_id, t.payload_content_type, "
                + "t.payload_bytes, t.due_at_epoch_second, t.due_at_nano FROM timer t "
                + "WHERE t.tenant_id = ? AND t.process_instance_id = ? "
                + "AND " + StoredInstant.atOrBefore("t.due_at") + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = t.tenant_id "
                + "AND k.process_instance_id = t.process_instance_id AND k.work_item_id = t.timer_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = t.tenant_id "
                + "AND c.process_instance_id = t.process_instance_id AND c.work_item_id = t.timer_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                + "ORDER BY t.due_at_epoch_second, t.due_at_nano, t.rowid";
        var due = new ArrayList<TimerSchedule>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            int index = StoredInstant.bindComparison(statement, 3, now);
            StoredInstant.bindComparison(statement, index, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String traversalId = rows.getString("traversal_id");
                    String invocationId = rows.getString("invocation_id");
                    due.add(new TimerSchedule(UUID.fromString(rows.getString("timer_id")),
                            StoredInstant.read(rows, "due_at"),
                            traversalId == null ? null : UUID.fromString(traversalId),
                            invocationId == null ? null : UUID.fromString(invocationId),
                            OpaquePayload.of(rows.getBytes("payload_bytes"),
                                    rows.getString("payload_content_type"))));
                }
            }
        }
        return due;
    }

    private PendingWork claimAttempt(ExecutionKey key, ScheduledAttempt attempt, LeaseHandle lease,
                                     Instant now, Duration leaseTtl) throws SQLException {
        int delivery = registerClaim(key, attempt.attemptId(), now, leaseTtl);
        return new PendingWork.AttemptDispatch(key, attempt.attemptId(), attempt.traversalId(),
                attempt.invocationId(), attempt.attemptId(), attempt.ordinal(), lease.fencingToken(),
                lease.expiresAt(), delivery, attempt.command());
    }

    private PendingWork.TimerDue claimTimer(ExecutionKey key, TimerSchedule timer, LeaseHandle lease,
                                            Instant now, Duration leaseTtl) throws SQLException {
        int delivery = registerClaim(key, timer.timerId(), now, leaseTtl);
        return new PendingWork.TimerDue(key, timer.timerId(), timer.traversalId(), timer.invocationId(),
                timer.dueAt(), timer.payload(), lease.fencingToken(), lease.expiresAt(), delivery);
    }

    private int registerClaim(ExecutionKey key, UUID workItemId, Instant now, Duration leaseTtl)
            throws SQLException {
        int previous = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delivery_attempt FROM work_claim WHERE tenant_id = ? AND process_instance_id = ? "
                        + "AND work_item_id = ?")) {
            bindItem(statement, key, workItemId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    previous = rows.getInt(1);
                }
            }
        }
        int delivery = previous + 1;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO work_claim (tenant_id, process_instance_id, work_item_id, delivery_attempt, "
                        + "visible_again_at_epoch_second, visible_again_at_nano) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id, work_item_id) DO UPDATE SET "
                        + "delivery_attempt = excluded.delivery_attempt, "
                        + "visible_again_at_epoch_second = excluded.visible_again_at_epoch_second, "
                        + "visible_again_at_nano = excluded.visible_again_at_nano")) {
            bindItem(statement, key, workItemId);
            statement.setInt(4, delivery);
            StoredInstant.bindValue(statement, 5, now.plus(leaseTtl));
            statement.executeUpdate();
        }
        return delivery;
    }

    /**
     * Clears acknowledgements and claims whose work no longer exists.
     *
     * <p>A retry appends a new attempt id rather than reviving the old one, so this only removes
     * bookkeeping for work that has genuinely gone — a cancelled timer, or an attempt the fold
     * replaced. Leaving it would keep a permanently acknowledged ghost in the way of nothing, but
     * would also let the table grow without bound across a long-lived instance.</p>
     */
    private void dropAcknowledgementsForRescheduledWork(ExecutionKey key) throws SQLException {
        // Handler identities are work-item identities too, and a terminal handler is retained rather
        // than deleted, so its acknowledgement must be retained with it. Omitting the third arm here
        // would drop the acknowledgement on the next write and redeliver a resolved handler's trigger
        // forever.
        String liveWork = "SELECT attempt_id FROM attempt WHERE tenant_id = ? AND process_instance_id = ? "
                + "UNION ALL SELECT timer_id FROM timer WHERE tenant_id = ? AND process_instance_id = ? "
                + "UNION ALL SELECT handler_id FROM execution_handler "
                + "WHERE tenant_id = ? AND process_instance_id = ?";
        for (String table : List.of("work_acknowledgement", "work_claim")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE tenant_id = ? AND process_instance_id = ? "
                            + "AND work_item_id NOT IN (" + liveWork + ")")) {
                String tenantId = key.tenantId();
                String instanceId = key.processInstanceId().toString();
                for (int pair = 0; pair < 4; pair++) {
                    statement.setString(pair * 2 + 1, tenantId);
                    statement.setString(pair * 2 + 2, instanceId);
                }
                statement.executeUpdate();
            }
        }
    }

    private static void bindItem(PreparedStatement statement, ExecutionKey key, UUID workItemId)
            throws SQLException {
        statement.setString(1, key.tenantId());
        statement.setString(2, key.processInstanceId().toString());
        statement.setString(3, workItemId.toString());
    }

    // ---------------------------------------------------------------- durable handlers

    @Override
    public CompletionStage<Optional<DurableHandler>> loadHandler(ExecutionKey key, UUID handlerId) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(handlerId, "handlerId");
            return inReadTransaction(key, () -> {
                // Absent instance and absent handler answer the same way. Distinguishing them would
                // let a probe learn that a process instance exists in a tenant it cannot read.
                try (PreparedStatement statement = connection.prepareStatement(
                        HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.process_instance_id = ? "
                                + "AND h.handler_id = ?")) {
                    bindItem(statement, key, handlerId);
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next() ? Optional.of(readHandler(rows)) : Optional.<DurableHandler>empty();
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<Optional<DurableHandler>> findHandler(String tenantId, String handlerName,
                                                                 String correlationKey) {
        return async(() -> {
            requireTenantId(tenantId);
            HandlerRegistration.requireBoundedKey(handlerName, "handlerName");
            HandlerRegistration.requireBoundedKey(correlationKey, "correlationKey");
            return inReadTransaction(null, () -> {
                // The partial unique index makes this at most one row, so the answer does not depend
                // on ordering. A LIMIT here would have hidden a violated invariant behind an
                // arbitrary winner.
                try (PreparedStatement statement = connection.prepareStatement(
                        HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.name = ? AND h.correlation_key = ? "
                                + "AND h.status IN ('WAITING', 'ESCALATED')")) {
                    statement.setString(1, tenantId);
                    statement.setString(2, handlerName);
                    statement.setString(3, correlationKey);
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next() ? Optional.of(readHandler(rows)) : Optional.<DurableHandler>empty();
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<List<DurableHandler>> handlers(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return inReadTransaction(key, () -> {
                var found = new ArrayList<DurableHandler>();
                try (PreparedStatement statement = connection.prepareStatement(
                        HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.process_instance_id = ? "
                                + "ORDER BY h.position")) {
                    statement.setString(1, key.tenantId());
                    statement.setString(2, key.processInstanceId().toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            found.add(readHandler(rows));
                        }
                    }
                }
                return List.copyOf(found);
            });
        });
    }

    /**
     * Folds this batch's registrations and handler transitions, inside the enclosing transaction.
     *
     * <p>Runs after the aggregate is written, because a registration may name an invocation the same
     * batch created and a terminal transition must name a traversal the same batch added; both are
     * validated against the post-fold aggregate that is already in {@code folded}. A rejection here
     * rolls the whole transaction back, which is what makes a wait, or a re-entry, atomic.</p>
     */
    private void writeHandlers(ExecutionKey key, ExecutionBatch batch, ProcessInstance folded,
                               long revision) throws SQLException {
        for (HandlerRegistration registration : batch.handlersToRegister()) {
            registerHandler(key, folded, registration, revision);
        }
        for (HandlerTransition transition : batch.handlerTransitions()) {
            transitionHandler(key, folded, transition, revision);
        }
    }

    private void registerHandler(ExecutionKey key, ProcessInstance folded, HandlerRegistration registration,
                                 long revision) throws SQLException {
        requireInvocationExists(folded, registration.traversalId(), registration.invocationId(),
                "handler " + registration.handlerId());

        DurableHandler byDeduplication = handlerByDeduplicationKey(key.tenantId(),
                registration.deduplicationKey());
        if (byDeduplication != null) {
            // A retried wait re-sends the identical batch and must be a no-op. A DIFFERENT
            // registration under the same key is a caller bug, and answering it as a success would
            // silently discard a handler somebody asked for.
            if (!byDeduplication.matches(registration)) {
                throw failure(ExecutionStoreFailure.invalid("deduplication key "
                        + registration.deduplicationKey() + " already registers handler "
                        + byDeduplication.handlerId() + ", which is not the handler being registered"));
            }
            return;
        }
        DurableHandler contender = liveHandler(key.tenantId(), registration.name(),
                registration.correlationKey());
        if (contender != null && !contender.handlerId().equals(registration.handlerId())) {
            throw failure(new ExecutionStoreFailure.HandlerCorrelationTaken(registration.name(),
                    registration.correlationKey()));
        }
        DurableHandler existing = readHandler(key, registration.handlerId());
        if (existing != null) {
            throw failure(ExecutionStoreFailure.invalid("handler " + registration.handlerId()
                    + " is already registered under a different deduplication key"));
        }
        insertHandler(DurableHandler.waiting(key, registration, revision), nextHandlerPosition(key));
    }

    private void transitionHandler(ExecutionKey key, ProcessInstance folded, HandlerTransition transition,
                                   long revision) throws SQLException {
        DurableHandler current = readHandler(key, transition.handlerId());
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
            requireTraversalExists(folded, transition.resumeTraversalId(),
                    "handler " + current.handlerId() + " resume");
        }
        if (transition.next() == HandlerStatus.RESOLVED) {
            // Only a resolution supplies the body the handler was declared to be waiting for. A
            // denial carries a refusal reason, which is a different shape by nature.
            Optional<String> refusal = current.payloadSchema().rejectionOf(transition.outcomePayload());
            if (refusal.isPresent()) {
                throw failure(ExecutionStoreFailure.invalid("handler " + current.handlerId()
                        + " payload was refused: " + refusal.get()));
            }
        }
        updateHandler(current.apply(transition, revision));
    }

    private void insertHandler(DurableHandler handler, int position) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_handler (tenant_id, process_instance_id, handler_id, position, "
                        + "name, traversal_id, invocation_id, correlation_key, deduplication_key, "
                        + "schema_content_type, schema_ref, schema_max_bytes, required_roles, "
                        + "required_scopes, status, resume_traversal_id, actor, outcome_content_type, "
                        + "outcome_bytes, revision) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, handler.key().tenantId());
            statement.setString(2, handler.key().processInstanceId().toString());
            statement.setString(3, handler.handlerId().toString());
            statement.setInt(4, position);
            statement.setString(5, handler.name());
            statement.setString(6, handler.traversalId().toString());
            statement.setString(7, handler.invocationId().toString());
            statement.setString(8, handler.correlationKey());
            statement.setString(9, handler.deduplicationKey());
            statement.setString(10, handler.payloadSchema().contentType());
            statement.setString(11, handler.payloadSchema().schemaRef());
            statement.setInt(12, handler.payloadSchema().maxBytes());
            statement.setString(13, joinTokens(handler.authorization().requiredRoles()));
            statement.setString(14, joinTokens(handler.authorization().requiredScopes()));
            statement.setString(15, handler.status().name());
            statement.setString(16, handler.resumeTraversalId() == null ? null
                    : handler.resumeTraversalId().toString());
            statement.setString(17, handler.actor());
            statement.setString(18, handler.outcomePayload().contentType());
            statement.setBytes(19, handler.outcomePayload().bytes());
            statement.setLong(20, handler.revision());
            statement.executeUpdate();
        }
    }

    private void updateHandler(DurableHandler handler) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE execution_handler SET status = ?, resume_traversal_id = ?, actor = ?, "
                        + "outcome_content_type = ?, outcome_bytes = ?, revision = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND handler_id = ?")) {
            statement.setString(1, handler.status().name());
            statement.setString(2, handler.resumeTraversalId() == null ? null
                    : handler.resumeTraversalId().toString());
            statement.setString(3, handler.actor());
            statement.setString(4, handler.outcomePayload().contentType());
            statement.setBytes(5, handler.outcomePayload().bytes());
            statement.setLong(6, handler.revision());
            statement.setString(7, handler.key().tenantId());
            statement.setString(8, handler.key().processInstanceId().toString());
            statement.setString(9, handler.handlerId().toString());
            statement.executeUpdate();
        }
    }

    private int nextHandlerPosition(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM execution_handler "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private DurableHandler readHandler(ExecutionKey key, UUID handlerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.process_instance_id = ? AND h.handler_id = ?")) {
            bindItem(statement, key, handlerId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHandler(rows) : null;
            }
        }
    }

    private DurableHandler liveHandler(String tenantId, String handlerName, String correlationKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.name = ? AND h.correlation_key = ? "
                        + "AND h.status IN ('WAITING', 'ESCALATED')")) {
            statement.setString(1, tenantId);
            statement.setString(2, handlerName);
            statement.setString(3, correlationKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHandler(rows) : null;
            }
        }
    }

    private DurableHandler handlerByDeduplicationKey(String tenantId, String deduplicationKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.deduplication_key = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, deduplicationKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHandler(rows) : null;
            }
        }
    }

    /**
     * Reconstructs a stored handler through its canonical constructor.
     *
     * <p>A row that no longer satisfies the record's invariants — a resuming status with no resume
     * traversal, an unknown status name written by a newer binary — surfaces as
     * {@link ExecutionStoreFailure.Corrupted} rather than escaping into the runtime, matching how the
     * aggregate itself is revalidated on the way out.</p>
     */
    private DurableHandler readHandler(ResultSet rows) throws SQLException {
        var key = new ExecutionKey(rows.getString("tenant_id"),
                UUID.fromString(rows.getString("process_instance_id")));
        String resumeTraversalId = rows.getString("resume_traversal_id");
        try {
            return new DurableHandler(UUID.fromString(rows.getString("handler_id")), key,
                    rows.getString("name"), UUID.fromString(rows.getString("traversal_id")),
                    UUID.fromString(rows.getString("invocation_id")), rows.getString("correlation_key"),
                    rows.getString("deduplication_key"),
                    new HandlerPayloadSchema(rows.getString("schema_content_type"),
                            rows.getString("schema_ref"), rows.getInt("schema_max_bytes")),
                    new HandlerAuthorization(splitTokens(rows.getString("required_roles")),
                            splitTokens(rows.getString("required_scopes"))),
                    HandlerStatus.valueOf(rows.getString("status")),
                    resumeTraversalId == null ? null : UUID.fromString(resumeTraversalId),
                    rows.getString("actor"),
                    OpaquePayload.of(rows.getBytes("outcome_bytes"),
                            rows.getString("outcome_content_type")),
                    rows.getLong("revision"));
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    /**
     * Newline-delimited, which is unambiguous because {@link HandlerAuthorization} rejects a token
     * carrying a control character. An escaping scheme invented here would be one every other adapter
     * would have to reproduce exactly.
     */
    private static String joinTokens(java.util.Set<String> tokens) {
        return String.join("\n", tokens);
    }

    private static java.util.Set<String> splitTokens(String stored) {
        if (stored == null || stored.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.LinkedHashSet<>(List.of(stored.split("\n", -1)));
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

    private List<DurableHandler> claimableTriggers(ExecutionKey key, Instant now) throws SQLException {
        String sql = HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.process_instance_id = ? "
                + "AND h.status IN ('RESOLVED', 'DENIED', 'EXPIRED') "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = h.tenant_id "
                + "AND k.process_instance_id = h.process_instance_id AND k.work_item_id = h.handler_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = h.tenant_id "
                + "AND c.process_instance_id = h.process_instance_id AND c.work_item_id = h.handler_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                + "ORDER BY h.position";
        var ready = new ArrayList<DurableHandler>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            StoredInstant.bindComparison(statement, 3, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ready.add(readHandler(rows));
                }
            }
        }
        return ready;
    }

    private PendingWork.HandlerTrigger claimTrigger(ExecutionKey key, DurableHandler handler,
                                                    LeaseHandle lease, Instant now, Duration leaseTtl)
            throws SQLException {
        int delivery = registerClaim(key, handler.handlerId(), now, leaseTtl);
        // The RE-ENTRY traversal, never the one that was waiting: the claimant runs the traversal the
        // resolution authorized, and the waiting traversal's own history stays closed.
        return new PendingWork.HandlerTrigger(key, handler.handlerId(), handler.resumeTraversalId(),
                handler.invocationId(), handler.name(), handler.outcomePayload(),
                lease.fencingToken(), lease.expiresAt(), delivery);
    }

    // ---------------------------------------------------------------- validation

    /**
     * Fail-closed classification of an absent key. Comparing {@code keyIssuedAt - maxClockSkew} rather
     * than {@code keyIssuedAt} means every case ambiguous within the declared budget resolves to
     * expired rather than to "safe to apply".
     */
    private boolean provablyNeverRecorded(String tenantId, Instant keyIssuedAt) throws SQLException {
        return !minusClamped(keyIssuedAt, config.maxClockSkew()).isBefore(watermarkOf(tenantId));
    }

    private void requireIssuanceWithinSkewBudget(Instant keyIssuedAt) {
        if (keyIssuedAt == null) {
            throw failure(ExecutionStoreFailure.invalid("keyIssuedAt is mandatory"));
        }
        if (keyIssuedAt.isAfter(plusClamped(clock.instant(), config.maxClockSkew()))) {
            throw failure(ExecutionStoreFailure.invalid("keyIssuedAt " + keyIssuedAt
                    + " is later than the store clock plus the declared " + config.maxClockSkew()
                    + " skew budget; the caller's clock is wrong"));
        }
    }

    /** {@code Instant.MIN}/{@code MAX} arithmetic overflows rather than saturating, so clamp it. */
    private static Instant minusClamped(Instant instant, Duration amount) {
        try {
            return instant.minus(amount);
        } catch (ArithmeticException | DateTimeException overflow) {
            return Instant.MIN;
        }
    }

    private static Instant plusClamped(Instant instant, Duration amount) {
        try {
            return instant.plus(amount);
        } catch (ArithmeticException | DateTimeException overflow) {
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
     * rejection, and why it is decided here — before any row is read.</p>
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
        if (payload.size() > config.maxPayloadBytes()) {
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.size(), config.maxPayloadBytes()));
        }
    }

    private void requireLeaseTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw failure(ExecutionStoreFailure.invalid("lease ttl must be positive"));
        }
        if (ttl.compareTo(config.maxLeaseTtl()) > 0) {
            throw failure(ExecutionStoreFailure.invalid(
                    "lease ttl " + ttl + " exceeds the declared maximum " + config.maxLeaseTtl()));
        }
    }

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

    // ---------------------------------------------------------------- event journal and outbox

    @Override
    public Duration journalRetention() {
        return config.journalRetention();
    }

    @Override
    public CompletionStage<List<JournalRecord>> readJournal(String tenantId, long afterOffset, int limit) {
        return async(() -> {
            requireTenantId(tenantId);
            if (afterOffset < 0) {
                throw failure(ExecutionStoreFailure.invalid("afterOffset cannot be negative"));
            }
            requireLimit(limit);
            return inReadTransaction(null, () -> {
                long retainedFrom = readRetainedFrom(tenantId);
                // Strictly below: a caller resuming from the last offset it saw is asking for what
                // comes after a record it already holds, and that record being the oldest survivor is
                // the healthy steady state rather than a truncation.
                if (afterOffset + 1 < retainedFrom) {
                    throw failure(new ExecutionStoreFailure.JournalTruncated(tenantId, afterOffset, retainedFrom));
                }
                var page = new ArrayList<JournalRecord>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM event_journal WHERE tenant_id = ? AND journal_offset > ? "
                                + "ORDER BY journal_offset LIMIT ?")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, afterOffset);
                    statement.setInt(3, limit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            page.add(readJournalRecord(tenantId, rows));
                        }
                    }
                }
                return List.copyOf(page);
            });
        });
    }

    /**
     * Reconstructs one journal row and verifies its digest.
     *
     * <p>The check is on the <strong>read</strong> path deliberately. Verifying only at write time
     * would prove the digest was computed correctly and nothing whatever about whether the bytes
     * survived on disk, which is the entire question a digest exists to answer. This is also the
     * mechanism by which {@code Corrupted} becomes reachable for events without any fault-injection
     * point on the port: a row edited out of band reads back with a digest that no longer describes
     * it.</p>
     */
    private JournalRecord readJournalRecord(String tenantId, ResultSet rows) throws SQLException {
        UUID instanceId = UUID.fromString(rows.getString("process_instance_id"));
        var envelope = new EventEnvelope(
                rows.getInt("envelope_version"),
                UUID.fromString(rows.getString("event_id")),
                tenantId,
                rows.getString("event_type"),
                instanceId,
                UUID.fromString(rows.getString("traversal_id")),
                uuidOrNull(rows.getString("invocation_id")),
                uuidOrNull(rows.getString("attempt_id")),
                uuidOrNull(rows.getString("causation_id")),
                rows.getString("correlation_id"),
                rows.getString("graph_version"),
                StoredInstant.read(rows, "occurred_at"),
                OpaquePayload.of(rows.getBytes("payload_bytes"), rows.getString("payload_content_type")),
                EventDigest.of(rows.getBytes("digest")));
        if (!envelope.digestMatchesContent()) {
            throw failure(new ExecutionStoreFailure.Corrupted(new ExecutionKey(tenantId, instanceId),
                    "journal offset " + rows.getLong("journal_offset") + " carries digest "
                            + envelope.digest().hex() + ", which does not match its stored content"));
        }
        return new JournalRecord(envelope, rows.getLong("stream_sequence"), rows.getLong("journal_offset"),
                rows.getLong("committed_at_revision"), StoredInstant.read(rows, "recorded_at"));
    }

    @Override
    public CompletionStage<Long> journalRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inReadTransaction(null, () -> readRetainedFrom(tenantId));
        });
    }

    @Override
    public CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
        return async(() -> {
            requireTenantId(tenantId);
            requireDestination(destination);
            return inReadTransaction(null, () ->
                    new JournalCursor(tenantId, destination, readCursor(tenantId, destination)));
        });
    }

    @Override
    public CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected, long throughOffset) {
        return async(() -> {
            Objects.requireNonNull(expected, "expected");
            if (throughOffset < expected.deliveredThrough()) {
                throw failure(ExecutionStoreFailure.invalid("a cursor cannot retreat: destination "
                        + expected.destination() + " is at " + expected.deliveredThrough()
                        + " and was asked to move to " + throughOffset));
            }
            return inWriteTransaction(null, () -> {
                long stored = readCursor(expected.tenantId(), expected.destination());
                if (stored != expected.deliveredThrough()) {
                    throw failure(new ExecutionStoreFailure.OutboxCursorConflict(expected.tenantId(),
                            expected.destination(), expected.deliveredThrough(), stored));
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO outbox_cursor (tenant_id, destination, delivered_through) VALUES (?, ?, ?) "
                                + "ON CONFLICT (tenant_id, destination) DO UPDATE SET delivered_through = ?")) {
                    statement.setString(1, expected.tenantId());
                    statement.setString(2, expected.destination());
                    statement.setLong(3, throughOffset);
                    statement.setLong(4, throughOffset);
                    statement.executeUpdate();
                }
                return new JournalCursor(expected.tenantId(), expected.destination(), throughOffset);
            });
        });
    }

    @Override
    public CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId, UUID eventId,
                                                        Duration retention) {
        return async(() -> {
            requireTenantId(tenantId);
            if (consumerId == null || consumerId.isBlank()) {
                throw failure(ExecutionStoreFailure.invalid("consumerId cannot be blank"));
            }
            Objects.requireNonNull(eventId, "eventId");
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw failure(ExecutionStoreFailure.invalid("inbox retention must be positive and is mandatory"));
            }
            return inWriteTransaction(null, () -> {
                Instant expiresAt = plusClamped(clock.instant(), retention);
                Instant recorded = null;
                try (PreparedStatement probe = connection.prepareStatement(
                        "SELECT expires_at_epoch_second, expires_at_nano FROM inbox_record "
                                + "WHERE tenant_id = ? AND consumer_id = ? AND event_id = ?")) {
                    probe.setString(1, tenantId);
                    probe.setString(2, consumerId);
                    probe.setString(3, eventId.toString());
                    try (ResultSet rows = probe.executeQuery()) {
                        if (rows.next()) {
                            recorded = StoredInstant.read(rows, "expires_at");
                        }
                    }
                }
                if (recorded == null) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO inbox_record (tenant_id, consumer_id, event_id, "
                                    + "expires_at_epoch_second, expires_at_nano) VALUES (?, ?, ?, ?, ?)")) {
                        insert.setString(1, tenantId);
                        insert.setString(2, consumerId);
                        insert.setString(3, eventId.toString());
                        StoredInstant.bindValue(insert, 4, expiresAt);
                        insert.executeUpdate();
                    }
                    return Boolean.TRUE;
                }
                // Already recorded, so the caller must not apply the effect. The expiry is extended
                // rather than left alone: a redelivery is evidence the sender still believes this
                // event is in flight, so forgetting it on the original schedule would let the next
                // redelivery be treated as a first delivery.
                if (expiresAt.isAfter(recorded)) {
                    try (PreparedStatement extend = connection.prepareStatement(
                            "UPDATE inbox_record SET expires_at_epoch_second = ?, expires_at_nano = ? "
                                    + "WHERE tenant_id = ? AND consumer_id = ? AND event_id = ?")) {
                        StoredInstant.bindValue(extend, 1, expiresAt);
                        extend.setString(3, tenantId);
                        extend.setString(4, consumerId);
                        extend.setString(5, eventId.toString());
                        extend.executeUpdate();
                    }
                }
                return Boolean.FALSE;
            });
        });
    }

    @Override
    public CompletionStage<Long> inboxRecordCount(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inReadTransaction(null, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM inbox_record WHERE tenant_id = ?")) {
                    statement.setString(1, tenantId);
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next() ? rows.getLong(1) : 0L;
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<Long> compactJournal(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inWriteTransaction(null, () -> {
                // "Delivered by every known destination" is the minimum over all cursors, and with no
                // destination at all it is zero, so nothing is compactable. That is the conservative
                // direction and it is deliberate: reading "nobody is listening" as "everybody has
                // received it" would discard the whole backlog of a deployment whose projection has
                // not been enabled yet, and no publisher would ever notice, because a publisher that
                // never saw an event has nothing to miss.
                long deliveredEverywhere = minimumCursor(tenantId);
                Instant cutoff = clock.instant().minus(config.journalRetention());

                // Only a contiguous prefix goes. Punching a hole in the middle would leave surviving
                // offsets that no single retained_from could honestly describe.
                long ceiling;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT MIN(journal_offset) FROM event_journal WHERE tenant_id = ? "
                                + "AND (journal_offset > ? OR " + StoredInstant.strictlyAfter("recorded_at") + ")")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, deliveredEverywhere);
                    StoredInstant.bindComparison(statement, 3, cutoff);
                    try (ResultSet rows = statement.executeQuery()) {
                        long survivor = rows.next() ? rows.getLong(1) : 0L;
                        ceiling = rows.wasNull() || survivor == 0L ? Long.MAX_VALUE : survivor - 1;
                    }
                }
                if (ceiling == Long.MAX_VALUE) {
                    // Nothing survives the filter, so everything currently stored is compactable.
                    ceiling = readNextOffset(tenantId) - 1;
                }

                long discarded;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM event_journal WHERE tenant_id = ? AND journal_offset <= ?")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, ceiling);
                    discarded = statement.executeUpdate();
                }
                if (discarded > 0) {
                    writeRetainedFrom(tenantId, ceiling + 1);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM inbox_record WHERE tenant_id = ? AND "
                                + StoredInstant.strictlyBefore("expires_at"))) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, clock.instant());
                    statement.executeUpdate();
                }
                return discarded;
            });
        });
    }

    /**
     * Writes this batch's envelopes inside the batch's own transaction.
     *
     * <p>Called from {@code applyLocked}, between the aggregate write and the return, so the event
     * rows and the transition rows are inside one {@code COMMIT}. That single fact is the whole of
     * PERS-07's first contract requirement and the whole of its documented requirements: there is no
     * window between committing the transition and recording the event, because there is no second
     * write to perform.</p>
     */
    private void writeJournal(ExecutionKey key, ExecutionBatch batch, long revision, Instant now)
            throws SQLException {
        if (batch.events().isEmpty()) {
            return;
        }
        long offset = readNextOffset(key.tenantId());
        long stream = readNextStreamSequence(key);
        for (EventEnvelope envelope : batch.events()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO event_journal (tenant_id, journal_offset, stream_sequence, "
                            + "process_instance_id, committed_at_revision, envelope_version, event_id, "
                            + "event_type, traversal_id, invocation_id, attempt_id, causation_id, "
                            + "correlation_id, graph_version, occurred_at_epoch_second, occurred_at_nano, "
                            + "payload_content_type, payload_bytes, digest, recorded_at_epoch_second, "
                            + "recorded_at_nano) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, key.tenantId());
                statement.setLong(2, offset);
                statement.setLong(3, stream);
                statement.setString(4, key.processInstanceId().toString());
                statement.setLong(5, revision);
                statement.setInt(6, envelope.envelopeVersion());
                statement.setString(7, envelope.eventId().toString());
                statement.setString(8, envelope.eventType());
                statement.setString(9, envelope.traversalId().toString());
                statement.setString(10, textOrNull(envelope.invocationId()));
                statement.setString(11, textOrNull(envelope.attemptId()));
                statement.setString(12, textOrNull(envelope.causationId()));
                statement.setString(13, envelope.correlationId());
                statement.setString(14, envelope.graphVersion());
                StoredInstant.bindValue(statement, 15, envelope.occurredAt());
                statement.setString(17, envelope.payload().contentType());
                statement.setBytes(18, envelope.payload().bytes());
                statement.setBytes(19, envelope.digest().value());
                StoredInstant.bindValue(statement, 20, now);
                statement.executeUpdate();
            }
            offset++;
            stream++;
        }
        writeNextOffset(key.tenantId(), offset);
        writeNextStreamSequence(key, stream);
    }

    private long readNextOffset(String tenantId) throws SQLException {
        return readWatermarkColumn(tenantId, "next_offset");
    }

    private long readRetainedFrom(String tenantId) throws SQLException {
        return readWatermarkColumn(tenantId, "retained_from");
    }

    private long readWatermarkColumn(String tenantId, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM journal_watermark WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // Offsets start at one, so an absent watermark and an empty journal agree.
                return rows.next() ? rows.getLong(1) : 1L;
            }
        }
    }

    private void writeNextOffset(String tenantId, long nextOffset) throws SQLException {
        upsertWatermark(tenantId, nextOffset, readRetainedFrom(tenantId));
    }

    private void writeRetainedFrom(String tenantId, long retainedFrom) throws SQLException {
        upsertWatermark(tenantId, readNextOffset(tenantId), retainedFrom);
    }

    private void upsertWatermark(String tenantId, long nextOffset, long retainedFrom) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO journal_watermark (tenant_id, next_offset, retained_from) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET next_offset = ?, retained_from = ?")) {
            statement.setString(1, tenantId);
            statement.setLong(2, nextOffset);
            statement.setLong(3, retainedFrom);
            statement.setLong(4, nextOffset);
            statement.setLong(5, retainedFrom);
            statement.executeUpdate();
        }
    }

    private long readNextStreamSequence(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT next_sequence FROM journal_stream_sequence WHERE tenant_id = ? "
                        + "AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 1L;
            }
        }
    }

    private void writeNextStreamSequence(ExecutionKey key, long next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO journal_stream_sequence (tenant_id, process_instance_id, next_sequence) "
                        + "VALUES (?, ?, ?) ON CONFLICT (tenant_id, process_instance_id) DO UPDATE SET "
                        + "next_sequence = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            statement.setLong(3, next);
            statement.setLong(4, next);
            statement.executeUpdate();
        }
    }

    private long readCursor(String tenantId, String destination) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delivered_through FROM outbox_cursor WHERE tenant_id = ? AND destination = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, destination);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private long minimumCursor(String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT MIN(delivered_through), COUNT(*) FROM outbox_cursor WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(2) == 0L) {
                    return 0L;
                }
                return rows.getLong(1);
            }
        }
    }

    private static void requireDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("destination cannot be blank"));
        }
    }

    private static String textOrNull(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    /**
     * Every envelope must name this batch's own tenant and instance, decided before any stored state
     * is read (ADR 0010 section 12.3).
     *
     * <p>The tenant half is a security guard, not a consistency one. An envelope naming another
     * tenant, accepted into this tenant's journal, is delivered to <em>this</em> journal's
     * subscribers — a cross-tenant disclosure produced by a caller bug, and one that the physical
     * database isolation cannot catch, because the row never reaches the other tenant's
     * database in the first place.</p>
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

    // ---------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> CompletionStage<T> async(java.util.function.Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable("this execution store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }

    /** Runs work on the confined connection thread and rethrows its failure to the caller. */
    private <T> T onWorker(Work<T> work) {
        try {
            return worker.submit(work::run).get(1, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                    "interrupted while waiting for the store connection"), interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable(String.valueOf(cause)), cause);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                    "the store connection did not respond"), timedOut);
        }
    }
}
