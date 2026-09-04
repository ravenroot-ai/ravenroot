package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionOrigin;
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
import ai.ravenroot.api.persistence.InventoryCursor;
import ai.ravenroot.api.persistence.InventoryDisposition;
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
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.persistence.ToolApprovalTransition;

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
 * The durable single-host {@link ExecutionStore}, backed by one SQLite database in WAL mode.
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
 *   degrade this capability, it falsifies it. Coordinating execution state across several hosts is
 *   a different adapter's problem, not a configuration of this one.</li>
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
 * on disk. Running the work on the caller's thread would put an fsync on an actor dispatcher the
 * moment one drives this store. Thread confinement also removes any question of the connection's own
 * thread safety and makes intra-process write serialization structural rather than a lock to
 * remember.</p>
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
            StoreCapability.DURABLE_HANDLERS,
            // The inventory reads the same process_instance, traversal, invocation, attempt and lease
            // rows that apply() writes inside one transaction, so it is atomic with the lifecycle by
            // construction rather than by a projection that has to be kept in step. There is no
            // offset to repair and no rebuild that could invent work.
            StoreCapability.PROCESS_INVENTORY,
            StoreCapability.INVENTORY_RETENTION,
            StoreCapability.TOOL_APPROVALS);

    /**
     * The one projection every handler read uses, aliased so a correlated subquery cannot silently
     * bind an unqualified column to its own table instead of to this one.
     */
    private static final String HANDLER_COLUMNS = "SELECT h.* FROM execution_handler h";
    private static final String TOOL_APPROVAL_COLUMNS = "SELECT a.* FROM tool_approval a";

    /**
     * {@code ('WAITING', 'ESCALATED')} and {@code ('RESOLVED', 'DENIED', 'EXPIRED')}, derived from
     * {@link HandlerStatus#terminal()} rather than written out as SQL text.
     *
     * <p>Restating the split as a literal in every query is how the two adapters come to disagree
     * without anything failing: a sixth, non-terminal status would be enforced by the in-memory
     * adapter's own {@code terminal()} check and quietly ignored by a hand-written {@code IN} list
     * here, so correlation-key uniqueness would hold on one store and not the other. Deriving it
     * means adding a status changes both at once.</p>
     *
     * <p>The frozen migration DDL cannot use these — a migration's text is history and must never be
     * rewritten — so {@code SqliteHandlerStatusSqlTest} pins that literal against the same enum
     * instead, and fails the build when a new status makes the shipped partial index wrong. That pin
     * finds the migration by its <em>description</em>, not by its number: the handler migration
     * shipped as 5 and became 6 when it merged behind another feature that had taken that number, so
     * the number is a merge outcome rather than an identity.</p>
     */
    static final String LIVE_HANDLER_STATUSES = statusList(false);

    /** The terminal counterpart of {@link #LIVE_HANDLER_STATUSES}. */
    static final String TERMINAL_HANDLER_STATUSES = statusList(true);

    private static String statusList(boolean terminal) {
        return java.util.Arrays.stream(HandlerStatus.values())
                .filter(status -> status.terminal() == terminal)
                .map(status -> "'" + status.name() + "'")
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

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

    @Override
    public int maxInventoryPageSize() {
        return config.maxInventoryPageSize();
    }

    @Override
    public Duration terminalRetention() {
        return config.terminalRetention();
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
            batch.toolApprovalsToRegister().forEach(registration -> {
                requireWithinPayloadLimit(OpaquePayload.of(registration.canonicalArguments(),
                        "application/json"));
                requireWithinPayloadLimit(OpaquePayload.of(registration.continuation(),
                        "application/vnd.ravenroot.tool-continuation"));
            });
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

        // created_at is written once and never rewritten, which is what makes the inventory's sort key
        // immutable and therefore its pagination stable while writes continue.
        Instant createdAt = existing == null ? now : existing.createdAt();
        // One increment per authoritative status transition ACTUALLY APPLIED, counted from the batch
        // rather than by comparing the status before and after: a batch that moves an instance
        // RUNNING -> WAITING -> RUNNING applied two transitions and an endpoint comparison would see
        // none. Creation is itself the first transition, into the initial status. A replayed batch
        // returns before this point, so an at-least-once redelivery cannot inflate the count, which is
        // what keeps the generation meaningful under duplicate work delivery.
        //
        // It is NOT the fencing token and must never be conflated with one: a generation counts how
        // far the lifecycle has moved, a token names who is allowed to move it. One worker applies
        // many transitions under a single token, and a contested instance changes owner -- and token --
        // without its status moving at all.
        long generation = (existing == null ? 1L : existing.lifecycleGeneration())
                + processTransitionCount(batch);
        ExecutionOrigin origin = (existing == null ? ExecutionOrigin.none() : existing.origin())
                .mergedWith(batch.origin());
        // Retention starts when the instance becomes terminal and never restarts, because a terminal
        // instance cannot transition again. A non-terminal row carries no deadline at all rather than a
        // far-future one: NULL means "retention has not started", and a sentinel date would be readable
        // as a real deadline by anyone looking at the row.
        Instant retainedUntil = folded.status().terminal()
                ? (existing != null && existing.retainedUntil() != null
                        ? existing.retainedUntil() : plusClamped(now, config.terminalRetention()))
                : null;

        writeInstanceRow(key, folded.status(), pin, revision, fencingToken, now, createdAt, generation,
                origin, retainedUntil);
        AggregateStorage.write(connection, key, folded);
        writeTimers(key, batch);
        // After the aggregate, because a registration may name an invocation this batch created and a
        // terminal transition must name a traversal this batch added; both are validated against the
        // post-fold aggregate. A rejection rolls the enclosing transaction back, which is what makes
        // a wait -- and a re-entry -- atomic with the transitions beside it.
        writeHandlers(key, batch, folded, revision);
        writeToolApprovals(key, batch, folded, pin, revision, now);
        batch.idempotency().ifPresent(write -> writeIdempotencyRecord(key, write, revision, now));
        // Inside the same transaction as the transition above, which is the entirety of the shared
        // transactional boundary the event journal promises. There is no publish step to crash
        // between, because there is no publish step: delivery reads the committed journal afterwards.
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

    // ---------------------------------------------------------------- durable execution inventory

    /**
     * The columns every inventory row needs, plus the two aggregate counts and the lease, in one
     * statement.
     *
     * <p>The counts are correlated subqueries rather than a {@code GROUP BY} over two joins, because
     * joining traversals and attempts in the same query multiplies the rows and every aggregate then
     * has to be de-duplicated with {@code DISTINCT} — which is both slower and, more to the point, the
     * kind of query that is subtly wrong in a way no assertion notices until a row has two traversals
     * and three attempts.</p>
     *
     * <p>The lease is a {@code LEFT JOIN}: an instance with no lease is the normal shape of interrupted
     * work and must appear in the listing, so an inner join would hide precisely the cohort the
     * inventory exists to surface.</p>
     */
    private static final String INVENTORY_COLUMNS =
            "SELECT p.process_instance_id, p.status, p.graph_version_pin, p.revision, p.fencing_token, "
                    + "p.lifecycle_generation, p.deployment_id, p.workload_id, p.correlation_id, "
                    + "p.created_at_epoch_second, p.created_at_nano, p.updated_at_epoch_second, "
                    + "p.updated_at_nano, p.retained_until_epoch_second, p.retained_until_nano, "
                    + "l.worker_id AS lease_worker_id, l.expires_at_epoch_second AS lease_expires_at_epoch_second, "
                    + "l.expires_at_nano AS lease_expires_at_nano, "
                    + "(SELECT COUNT(*) FROM traversal t WHERE t.tenant_id = p.tenant_id "
                    + "AND t.process_instance_id = p.process_instance_id) AS traversal_count, "
                    + "(SELECT COUNT(*) FROM attempt a WHERE a.tenant_id = p.tenant_id "
                    + "AND a.process_instance_id = p.process_instance_id AND a.status = 'PARKED') "
                    + "AS parked_count "
                    + "FROM process_instance p LEFT JOIN lease l ON l.tenant_id = p.tenant_id "
                    + "AND l.process_instance_id = p.process_instance_id ";

    @Override
    public CompletionStage<ProcessInventoryPage> listProcessInstances(String tenantId,
                                                                      ProcessInventoryQuery query) {
        return async(() -> {
            requireTenantId(tenantId);
            requireInventoryQuery(query);
            InventoryCursor.Position after = query.cursor()
                    .map(cursor -> InventoryCursor.decode(tenantId, cursor))
                    .orElse(null);
            return inReadTransaction(null, () -> {
                Instant now = clock.instant();
                var sql = new StringBuilder(INVENTORY_COLUMNS).append("WHERE p.tenant_id = ?");
                var binds = new ArrayList<Object>();
                binds.add(tenantId);

                if (!query.statuses().isEmpty()) {
                    sql.append(" AND p.status IN (")
                            .append("?, ".repeat(query.statuses().size() - 1)).append("?)");
                    query.statuses().stream().map(Enum::name).forEach(binds::add);
                }
                if (!query.includeTerminal()) {
                    // Built from the enum rather than written out, so a status added later is
                    // classified by the domain's own terminal() rather than by a literal here that
                    // nobody would remember to revisit.
                    var terminal = java.util.Arrays.stream(ProcessInstanceStatus.values())
                            .filter(ProcessInstanceStatus::terminal).map(Enum::name).toList();
                    sql.append(" AND p.status NOT IN (")
                            .append("?, ".repeat(terminal.size() - 1)).append("?)");
                    binds.addAll(terminal);
                }
                query.deploymentId().ifPresent(deployment -> {
                    sql.append(" AND p.deployment_id = ?");
                    binds.add(deployment);
                });
                query.ownerWorkerId().ifPresent(worker -> {
                    // A live lease only. A lapsed lease names the worker that has stopped renewing,
                    // and answering "owned by w" with work w has abandoned is the opposite of what an
                    // operator draining a worker is asking for.
                    sql.append(" AND l.worker_id = ? AND ")
                            .append(StoredInstant.strictlyAfter("l.expires_at"));
                    binds.add(worker);
                    binds.add(now);
                });
                if (after != null) {
                    // Strictly after the cursor under (created_at DESC, process_instance_id DESC).
                    // Written out rather than expressed as a row-value comparison because SQLite orders
                    // the id as TEXT, and the in-memory adapter matches that with String.compareTo --
                    // if the two disagreed on ties, a cursor minted by one would skip or repeat rows
                    // against the other.
                    sql.append(" AND (p.created_at_epoch_second < ? "
                            + "OR (p.created_at_epoch_second = ? AND p.created_at_nano < ?) "
                            + "OR (p.created_at_epoch_second = ? AND p.created_at_nano = ? "
                            + "AND p.process_instance_id < ?))");
                    // The first instant fills the (second, second, nano) shape every comparison
                    // fragment in this adapter uses; the tie-breaking clause needs (second, nano, id)
                    // instead, so its three operands are bound individually rather than through it.
                    binds.add(after.createdAt());
                    binds.add(after.createdAt().getEpochSecond());
                    binds.add(after.createdAt().getNano());
                    binds.add(after.processInstanceId().toString());
                }
                sql.append(" ORDER BY p.created_at_epoch_second DESC, p.created_at_nano DESC, "
                        + "p.process_instance_id DESC LIMIT ?");

                var page = new ArrayList<ProcessInventoryEntry>(query.limit());
                boolean more = false;
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    int index = 1;
                    for (Object bind : binds) {
                        index = bindInventoryArgument(statement, index, bind);
                    }
                    // One row past the page. A next cursor minted merely because the page filled would
                    // cost every caller an empty round trip, and a caller that reads a present cursor
                    // as "there is more" would report outstanding work that does not exist.
                    statement.setInt(index, query.limit() + 1);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (page.size() == query.limit()) {
                                more = true;
                                break;
                            }
                            page.add(readInventoryRow(tenantId, rows, now));
                        }
                    }
                }
                Optional<String> next = more
                        ? Optional.of(InventoryCursor.encode(tenantId, page.getLast().createdAt(),
                                page.getLast().key().processInstanceId()))
                        : Optional.empty();
                return new ProcessInventoryPage(List.copyOf(page), next, inventoryFloorOf(tenantId));
            });
        });
    }

    @Override
    public CompletionStage<Optional<ProcessInventoryEntry>> findProcessInstance(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return inReadTransaction(key, () -> {
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement(INVENTORY_COLUMNS
                        + "WHERE p.tenant_id = ? AND p.process_instance_id = ?")) {
                    // Both halves of the key are in the predicate, so a row belonging to another tenant
                    // is not excluded by a check that could be forgotten -- it is not selected at all.
                    // Absent and not-yours are therefore the same empty answer by construction, and the
                    // store cannot be used as a cross-tenant existence oracle.
                    statement.setString(1, key.tenantId());
                    statement.setString(2, key.processInstanceId().toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next()
                                ? Optional.of(readInventoryRow(key.tenantId(), rows, now))
                                : Optional.<ProcessInventoryEntry>empty();
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<List<TraversalInventoryEntry>> listTraversals(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return inReadTransaction(key, () -> {
                InstanceMeta meta = readMeta(key);
                if (meta == null) {
                    // NotFound rather than an empty list: an instance that exists with no traversals
                    // honestly reports none, and collapsing the two would make "you asked about
                    // nothing" indistinguishable from "it has nothing".
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                boolean leaseLive = leaseLive(key, clock.instant());
                String sql = "SELECT t.traversal_id, t.position, t.ingress_node_id, t.status, "
                        + "(SELECT COUNT(*) FROM invocation i WHERE i.tenant_id = t.tenant_id "
                        + "AND i.process_instance_id = t.process_instance_id "
                        + "AND i.traversal_id = t.traversal_id) AS invocation_count, "
                        + "(SELECT COUNT(*) FROM attempt a JOIN invocation i2 "
                        + "ON i2.tenant_id = a.tenant_id "
                        + "AND i2.process_instance_id = a.process_instance_id "
                        + "AND i2.invocation_id = a.invocation_id "
                        + "WHERE a.tenant_id = t.tenant_id "
                        + "AND a.process_instance_id = t.process_instance_id "
                        + "AND i2.traversal_id = t.traversal_id AND a.status = 'PARKED') AS parked_count "
                        + "FROM traversal t WHERE t.tenant_id = ? AND t.process_instance_id = ? "
                        + "ORDER BY t.position";
                var rowsOut = new ArrayList<TraversalInventoryEntry>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, key.tenantId());
                    statement.setString(2, key.processInstanceId().toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            TraversalStatus status = traversalStatusOf(key, rows.getString("status"));
                            int parked = rows.getInt("parked_count");
                            rowsOut.add(new TraversalInventoryEntry(key,
                                    UUID.fromString(rows.getString("traversal_id")),
                                    rows.getInt("position"), rows.getString("ingress_node_id"), status,
                                    InventoryDisposition.ofTraversal(status, leaseLive, parked > 0),
                                    rows.getInt("invocation_count"), parked));
                        }
                    }
                }
                return List.copyOf(rowsOut);
            });
        });
    }

    @Override
    public CompletionStage<Instant> inventoryRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inReadTransaction(null, () -> inventoryFloorOf(tenantId));
        });
    }

    /**
     * Purges in <strong>two</strong> transactions, floor first and deletions second, for the reason
     * {@link #purgeExpiredIdempotencyRecords(String)} already records.
     *
     * <p>The interrupted state has to be real and reachable in order to have been chosen, and it has to
     * be the conservative one. A floor ahead of the deletions says "rows before this instant may be
     * gone" while they are in fact still present: a false alarm, and a caller that then finds the row
     * is answered from the row. The reverse — deleting first and dying before the floor moved — leaves
     * rows genuinely gone under a floor that claims completeness, and every absent instance then reads
     * as one that never existed.</p>
     *
     * <p>The zero guard is load-bearing in the other direction. A purge that removed nothing must leave
     * the floor exactly where it is, because advancing it would report a retention gap that does not
     * exist, and a periodic purge job would report one on every tick.</p>
     */
    @Override
    public CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();
            // updated_at + retention <= now, rearranged so the comparison is against a stored column
            // rather than against an expression SQLite would have to evaluate per row.
            Instant lapsed = minusClamped(now, config.terminalRetention());

            Instant floor = inWriteTransaction(null, () -> latestExpiredDeadline(tenantId, now, lapsed));
            if (floor == null) {
                return 0L;
            }
            inWriteTransaction(null, () -> {
                advanceInventoryFloor(tenantId, floor);
                return null;
            });
            return inWriteTransaction(null, () -> {
                // ON DELETE CASCADE clears the traversals, invocations, causal edges, attempts, timers,
                // leases and work bookkeeping of every removed instance. The journal is deliberately
                // NOT cascaded: its offsets must never be reissued, and journal_stream_sequence keeps
                // the per-instance counter of a removed instance for exactly that reason. Retention on
                // events is compactJournal's, and the two windows are independent by design.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM process_instance WHERE tenant_id = ? AND process_instance_id IN ("
                                + expiredInstanceIdQuery() + ")")) {
                    bindExpiredInstanceQuery(statement, tenantId, now, lapsed);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- inventory helpers

    /**
     * The eligibility predicate, in one place because the floor query and the delete must not be able
     * to disagree about what "expired" means.
     *
     * <p>Only terminal rows are eligible however old a non-terminal one is: age is not evidence that
     * work has finished, and pruning a stuck instance would destroy the row an operator needs in order
     * to discover that it is stuck. The second arm of the disjunction is the upgrade path — a terminal
     * row written before schema 6 carries no deadline, and its updated_at <em>is</em> its terminal
     * transition instant, because a terminal instance is never written again.</p>
     */
    private static String expiredInstanceIdQuery() {
        var terminal = java.util.Arrays.stream(ProcessInstanceStatus.values())
                .filter(ProcessInstanceStatus::terminal).map(name -> "'" + name.name() + "'").toList();
        return "SELECT process_instance_id FROM process_instance WHERE tenant_id = ? AND status IN ("
                + String.join(", ", terminal) + ") AND ("
                + "(retained_until_epoch_second IS NOT NULL AND "
                + StoredInstant.atOrBefore("retained_until") + ") OR "
                + "(retained_until_epoch_second IS NULL AND "
                + StoredInstant.atOrBefore("updated_at") + "))";
    }

    /**
     * Binds a statement of the form {@code ... WHERE tenant_id = ? AND process_instance_id IN
     * (}{@link #expiredInstanceIdQuery()}{@code )}: the outer tenant, then the subquery's own tenant,
     * then the two comparisons. The tenant appears twice because the subquery must carry it too — an
     * instance id is unique only within a tenant, so a delete whose subquery were unscoped would match
     * a colliding id belonging to somebody else.
     */
    private static void bindExpiredInstanceQuery(PreparedStatement statement, String tenantId, Instant now,
                                                 Instant lapsed) throws SQLException {
        statement.setString(1, tenantId);
        statement.setString(2, tenantId);
        int index = StoredInstant.bindComparison(statement, 3, now);
        StoredInstant.bindComparison(statement, index, lapsed);
    }

    /**
     * The <strong>latest</strong> retention deadline among the rows this purge will remove, or
     * {@code null} when none is eligible. This is the floor.
     *
     * <p>It has to be the latest, and taking the earliest was a real defect. The floor's guarantee runs
     * in the direction "everything past it is still here", so it must sit at or beyond every boundary
     * the purge actually crossed. With the earliest, a run that removes two rows whose deadlines are
     * further apart than the retention window publishes a floor the later row sits <em>after</em> — and
     * a caller following the documented rule then concludes that a genuinely completed execution never
     * existed. That is the ambiguity inverted into the unsafe direction, which is the exact opposite of
     * what this method exists for. One row is the degenerate case where earliest and latest coincide,
     * which is why the mistake survives any test that purges only one.</p>
     *
     * <p>It is deliberately not {@code now} either. Advancing to {@code now} would claim a gap covering
     * every instant up to the present, including instants at which rows are still present, and a caller
     * would treat live terminal instances as possibly expired — safe, but uselessly pessimistic. The
     * latest crossed boundary is the tightest honest answer.</p>
     */
    private Instant latestExpiredDeadline(String tenantId, Instant now, Instant lapsed)
            throws SQLException {
        String sql = "SELECT retained_until_epoch_second, retained_until_nano, "
                + "updated_at_epoch_second, updated_at_nano FROM process_instance "
                + "WHERE tenant_id = ? AND process_instance_id IN (" + expiredInstanceIdQuery() + ")";
        Instant latest = null;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExpiredInstanceQuery(statement, tenantId, now, lapsed);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // The same resolution the read publishes, so the floor is expressed in deadlines a
                    // caller has actually been shown rather than in a quantity only this method knows.
                    Instant deadline = retentionDueAt(nullableInstant(rows, "retained_until"),
                            StoredInstant.read(rows, "updated_at"));
                    if (latest == null || deadline.isAfter(latest)) {
                        latest = deadline;
                    }
                }
            }
        }
        return latest;
    }

    /**
     * The instant a terminal row becomes purgeable: its stored deadline, or — for a row written before
     * schema 6, which has none — its last write plus the configured retention. That fallback is exact
     * rather than approximate, because a terminal instance is never written again, so its updated_at is
     * its terminal transition instant.
     */
    private Instant retentionDueAt(Instant retainedUntil, Instant updatedAt) {
        return retainedUntil != null ? retainedUntil : plusClamped(updatedAt, config.terminalRetention());
    }

    private Instant inventoryFloorOf(String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT retained_from_epoch_second, retained_from_nano FROM inventory_watermark "
                        + "WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // An absent row IS Instant.MIN. Writing one when a tenant first appears would record a
                // forgetting that never happened, in a table whose only purpose is to record one.
                return rows.next() ? StoredInstant.read(rows, "retained_from") : Instant.MIN;
            }
        }
    }

    private void advanceInventoryFloor(String tenantId, Instant floor) throws SQLException {
        // Monotonically non-decreasing: a floor never retreats, whatever order purges arrive in.
        if (!floor.isAfter(inventoryFloorOf(tenantId))) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO inventory_watermark (tenant_id, retained_from_epoch_second, "
                        + "retained_from_nano) VALUES (?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET "
                        + "retained_from_epoch_second = excluded.retained_from_epoch_second, "
                        + "retained_from_nano = excluded.retained_from_nano")) {
            statement.setString(1, tenantId);
            StoredInstant.bindValue(statement, 2, floor);
            statement.executeUpdate();
        }
    }

    private boolean leaseLive(ExecutionKey key, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at_epoch_second, expires_at_nano FROM lease "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                // Strictly before the expiry, matching the lease boundary the rest of this adapter
                // uses: at exactly expiresAt the lease is already gone.
                return rows.next() && now.isBefore(StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    private ProcessInventoryEntry readInventoryRow(String tenantId, ResultSet rows, Instant now)
            throws SQLException {
        var key = new ExecutionKey(tenantId, UUID.fromString(rows.getString("process_instance_id")));
        ProcessInstanceStatus status = processStatusOf(key, rows.getString("status"));
        String worker = rows.getString("lease_worker_id");
        Instant leaseExpiresAt = worker == null ? null : nullableInstant(rows, "lease_expires_at");
        boolean leaseLive = leaseExpiresAt != null && now.isBefore(leaseExpiresAt);
        return new ProcessInventoryEntry(key, status,
                InventoryDisposition.ofProcess(status, leaseLive, rows.getInt("parked_count") > 0),
                rows.getLong("revision"), rows.getLong("lifecycle_generation"),
                new GraphVersionPin(rows.getString("graph_version_pin")),
                Optional.ofNullable(rows.getString("deployment_id")),
                Optional.ofNullable(rows.getString("workload_id")),
                Optional.ofNullable(rows.getString("correlation_id")),
                leaseLive ? Optional.of(worker) : Optional.empty(),
                rows.getLong("fencing_token"),
                leaseLive ? Optional.of(leaseExpiresAt) : Optional.empty(),
                rows.getInt("traversal_count"), StoredInstant.read(rows, "created_at"),
                StoredInstant.read(rows, "updated_at"),
                retainedUntilOf(status, nullableInstant(rows, "retained_until"),
                        StoredInstant.read(rows, "updated_at")));
    }

    /**
     * What a reader is told about retention, resolved the same way the purge decides it.
     *
     * <p>The stored column is not the answer on its own. A terminal row written before schema 6 has no
     * deadline stored, and the purge already resolves that case through {@link #retentionDueAt} — so a
     * read that returned the raw column would report no deadline for a row the purge is about to remove
     * on schedule. Two paths disagreeing about the same fact is how a caller comes to trust the wrong
     * one; routing both through {@link #retentionDueAt} makes them incapable of it, which is the same
     * treatment {@link #expiredInstanceIdQuery()} already gives the floor query and the delete.</p>
     *
     * <p>The terminal test is the gate, and it is what stops the fallback from inventing a deadline for
     * a running instance: retention has not started for a non-terminal row, and absent is how that is
     * said. That is also why the fallback is exact rather than approximate for the rows it does apply
     * to — a terminal instance is never written again, so its {@code updated_at} is its terminal
     * transition instant.</p>
     */
    private Optional<Instant> retainedUntilOf(ProcessInstanceStatus status, Instant storedDeadline,
                                              Instant updatedAt) {
        return status.terminal()
                ? Optional.of(retentionDueAt(storedDeadline, updatedAt))
                : Optional.empty();
    }

    /** Binds one accumulated filter argument; an instant occupies the three slots of a comparison. */
    private static int bindInventoryArgument(PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value instanceof Instant instant) {
            return StoredInstant.bindComparison(statement, index, instant);
        }
        if (value instanceof Long epochSecond) {
            statement.setLong(index, epochSecond);
            return index + 1;
        }
        if (value instanceof Integer nano) {
            statement.setInt(index, nano);
            return index + 1;
        }
        statement.setString(index, (String) value);
        return index + 1;
    }

    private void requireInventoryQuery(ProcessInventoryQuery query) {
        if (query == null) {
            throw failure(ExecutionStoreFailure.invalid("query is mandatory"));
        }
        if (query.limit() < 1) {
            throw failure(ExecutionStoreFailure.invalid("inventory limit must be positive"));
        }
        // Rejected rather than clamped. A silently reduced page is indistinguishable from a last page,
        // and a caller paginating on "fewer rows than I asked for means I am done" would stop early and
        // never learn that it had.
        if (query.limit() > config.maxInventoryPageSize()) {
            throw failure(ExecutionStoreFailure.invalid("inventory limit " + query.limit()
                    + " exceeds the declared maximum " + config.maxInventoryPageSize()));
        }
        if (query.isSelfContradictory()) {
            throw failure(ExecutionStoreFailure.invalid("a query that filters only for terminal "
                    + "statuses while excluding terminal rows can never match; an empty page would be "
                    + "indistinguishable from there being none"));
        }
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

    private static long processTransitionCount(ExecutionBatch batch) {
        return batch.transitions().stream()
                .filter(ExecutionTransition.ProcessTransitioned.class::isInstance)
                .count();
    }

    // ---------------------------------------------------------------- row access

    /**
     * @param createdAt          write-once, and half of the inventory's sort key
     * @param lifecycleGeneration count of authoritative status transitions, exact from schema 6 and a
     *                           floor of one for a row that predates it
     * @param retainedUntil      the <em>raw</em> stored column and not the answer a caller is given:
     *                           null while non-terminal, and also null on a terminal row written before
     *                           schema 6. Every path that reports or acts on a deadline resolves it
     *                           through {@link #retentionDueAt} instead, so no reader sees this value
     *                           unmediated
     */
    private record InstanceMeta(long revision, long fencingToken, GraphVersionPin graphVersionPin,
                                ProcessInstanceStatus status, Instant updatedAt, Instant createdAt,
                                long lifecycleGeneration, ExecutionOrigin origin, Instant retainedUntil) {
    }

    private record ScheduledAttempt(UUID traversalId, UUID invocationId, UUID attemptId, int ordinal,
                                    ai.ravenroot.api.execution.NodeCommand command) {
    }

    private InstanceMeta readMeta(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision, fencing_token, graph_version_pin, status, updated_at_epoch_second, "
                        + "updated_at_nano, created_at_epoch_second, created_at_nano, "
                        + "lifecycle_generation, deployment_id, workload_id, correlation_id, "
                        + "retained_until_epoch_second, retained_until_nano FROM process_instance "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new InstanceMeta(rows.getLong("revision"), rows.getLong("fencing_token"),
                        new GraphVersionPin(rows.getString("graph_version_pin")),
                        processStatusOf(key, rows.getString("status")),
                        StoredInstant.read(rows, "updated_at"),
                        StoredInstant.read(rows, "created_at"),
                        rows.getLong("lifecycle_generation"),
                        ExecutionOrigin.of(rows.getString("deployment_id"), rows.getString("workload_id"),
                                rows.getString("correlation_id")),
                        nullableInstant(rows, "retained_until"));
            }
        }
    }

    /**
     * Reads a two-column instant that may be NULL, which the plain reader cannot express: it would
     * report a missing value as the epoch, and an epoch retention deadline is a deadline that has
     * already passed.
     */
    private static Instant nullableInstant(ResultSet rows, String column) throws SQLException {
        long second = rows.getLong(column + "_epoch_second");
        if (rows.wasNull()) {
            return null;
        }
        return Instant.ofEpochSecond(second, rows.getInt(column + "_nano"));
    }

    /**
     * Maps an unrecognised stored status name to {@link ExecutionStoreFailure.Corrupted}.
     *
     * <p>Statuses are persisted by name, which is what lets a member be added without a data
     * migration, and the cost of that is a one-way rollback gate: a row written by a newer binary is
     * unreadable by an older one. It has to surface as corruption rather than as a raw
     * {@link IllegalArgumentException} escaping the port, and above all it must never be skipped — an
     * inventory that silently dropped the rows it could not parse would report a shorter, cleaner
     * world than the one on disk, and nothing would say so.</p>
     */
    private static ProcessInstanceStatus processStatusOf(ExecutionKey key, String name) {
        try {
            return ProcessInstanceStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "process instance status '" + name + "' is not a status this build understands"),
                    unknown);
        }
    }

    private static TraversalStatus traversalStatusOf(ExecutionKey key, String name) {
        try {
            return TraversalStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "traversal status '" + name + "' is not a status this build understands"), unknown);
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
                                  long revision, long fencingToken, Instant now, Instant createdAt,
                                  long lifecycleGeneration, ExecutionOrigin origin,
                                  Instant retainedUntil) throws SQLException {
        // An upsert rather than INSERT OR REPLACE: REPLACE deletes the row first, which would cascade
        // through every child table and, with it, the leases and work claims that must outlive a write.
        //
        // created_at is deliberately absent from the DO UPDATE SET. It is bound in the VALUES so a
        // first insert records it, and it is never assigned on conflict, which makes write-once a
        // property of the statement rather than a rule the caller has to keep -- and write-once is
        // exactly what makes the inventory's ordering stable while writes continue.
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO process_instance (tenant_id, process_instance_id, status, graph_version_pin, "
                        + "revision, fencing_token, updated_at_epoch_second, updated_at_nano, "
                        + "created_at_epoch_second, created_at_nano, lifecycle_generation, "
                        + "deployment_id, workload_id, correlation_id, retained_until_epoch_second, "
                        + "retained_until_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id) DO UPDATE SET status = excluded.status, "
                        + "graph_version_pin = excluded.graph_version_pin, revision = excluded.revision, "
                        + "updated_at_epoch_second = excluded.updated_at_epoch_second, "
                        + "updated_at_nano = excluded.updated_at_nano, "
                        + "lifecycle_generation = excluded.lifecycle_generation, "
                        + "deployment_id = excluded.deployment_id, workload_id = excluded.workload_id, "
                        + "correlation_id = excluded.correlation_id, "
                        + "retained_until_epoch_second = excluded.retained_until_epoch_second, "
                        + "retained_until_nano = excluded.retained_until_nano")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            statement.setString(3, status.name());
            statement.setString(4, pin.reference());
            statement.setLong(5, revision);
            statement.setLong(6, fencingToken);
            int index = StoredInstant.bindValue(statement, 7, now);
            index = StoredInstant.bindValue(statement, index, createdAt);
            statement.setLong(index++, lifecycleGeneration);
            statement.setString(index++, origin.deploymentId().orElse(null));
            statement.setString(index++, origin.workloadId().orElse(null));
            statement.setString(index++, origin.correlationId().orElse(null));
            if (retainedUntil == null) {
                statement.setNull(index++, java.sql.Types.INTEGER);
                statement.setNull(index, java.sql.Types.INTEGER);
            } else {
                StoredInstant.bindValue(statement, index, retainedUntil);
            }
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
     * <h3>Why {@code RUNNING} is claimable, and why {@code PARKED} is not (ADR 0022)</h3>
     * <p>An attempt that is still {@code SCHEDULED} has provably not started, because the runtime
     * persists the {@code RUNNING} transition before the engine send. An attempt stuck in
     * {@code RUNNING} is exactly the crash case parking exists for: dispatched, outcome never learned.
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
                                + "AND h.status IN " + LIVE_HANDLER_STATUSES)) {
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
            transitionHandler(key, batch, folded, transition, revision);
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

    private void transitionHandler(ExecutionKey key, ExecutionBatch batch, ProcessInstance folded,
                                   HandlerTransition transition, long revision) throws SQLException {
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
            requireBatchCreatedTraversal(batch, transition.resumeTraversalId(),
                    "handler " + current.handlerId() + " resume");
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
                        + "AND h.status IN " + LIVE_HANDLER_STATUSES)) {
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

    // ---------------------------------------------------------------- durable tool approvals

    @Override
    public CompletionStage<Optional<DurableToolApproval>> loadToolApproval(ExecutionKey key,
                                                                           UUID approvalId) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(approvalId, "approvalId");
            return inReadTransaction(key, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        TOOL_APPROVAL_COLUMNS + " WHERE a.tenant_id = ? AND a.process_instance_id = ? "
                                + "AND a.approval_id = ?")) {
                    bindItem(statement, key, approvalId);
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next() ? Optional.of(readToolApproval(rows)) : Optional.empty();
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<List<DurableToolApproval>> toolApprovals(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return inReadTransaction(key, () -> {
                var approvals = new ArrayList<DurableToolApproval>();
                try (PreparedStatement statement = connection.prepareStatement(
                        TOOL_APPROVAL_COLUMNS + " WHERE a.tenant_id = ? AND a.process_instance_id = ? "
                                + "ORDER BY a.position")) {
                    statement.setString(1, key.tenantId());
                    statement.setString(2, key.processInstanceId().toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) approvals.add(readToolApproval(rows));
                    }
                }
                return List.copyOf(approvals);
            });
        });
    }

    private void writeToolApprovals(ExecutionKey key, ExecutionBatch batch, ProcessInstance folded,
                                    GraphVersionPin pin,
                                    long revision, Instant now) throws SQLException {
        for (ToolApprovalRegistration registration : batch.toolApprovalsToRegister()) {
            requireInvocationExists(folded, registration.traversalId(), registration.invocationId(),
                    "tool approval " + registration.approvalId());
            if (!key.tenantId().equals(registration.requester().tenantId())
                    || !pin.equals(registration.graphVersionPin())) {
                throw failure(ExecutionStoreFailure.invalid(
                        "tool approval identity or graph pin does not match its execution"));
            }
            if (!now.isBefore(registration.expiresAt())) {
                throw failure(ExecutionStoreFailure.invalid("tool approval expiry must be after store time"));
            }
            DurableToolApproval existing = readToolApproval(key, registration.approvalId());
            if (existing != null) {
                if (!existing.request().sameRequest(registration)) {
                    throw failure(ExecutionStoreFailure.invalid("tool approval " + registration.approvalId()
                            + " is already registered with a different request"));
                }
                continue;
            }
            insertToolApproval(DurableToolApproval.pending(key, registration, revision),
                    nextToolApprovalPosition(key));
        }
        for (ToolApprovalTransition transition : batch.toolApprovalTransitions()) {
            DurableToolApproval current = readToolApproval(key, transition.approvalId());
            if (current == null) {
                throw failure(ExecutionStoreFailure.invalid("unknown tool approval "
                        + transition.approvalId()));
            }
            if (current.alreadyApplied(transition)) continue;
            if (!current.status().canTransitionTo(transition.next())) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        current.request().approvalId(), current.status(), transition.next()));
            }
            if (transition.next() == ToolApprovalStatus.EXPIRED
                    && now.isBefore(current.request().expiresAt())) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        current.request().approvalId(), current.status(), ToolApprovalStatus.EXPIRED));
            }
            if ((transition.next() == ToolApprovalStatus.APPROVED
                    || transition.next() == ToolApprovalStatus.DENIED
                    || transition.next() == ToolApprovalStatus.CONSUMED)
                    && !now.isBefore(current.request().expiresAt())) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        current.request().approvalId(), current.status(), ToolApprovalStatus.EXPIRED));
            }
            updateToolApproval(current.apply(transition, revision));
        }
    }

    private void insertToolApproval(DurableToolApproval approval, int position) throws SQLException {
        ToolApprovalRegistration request = approval.request();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tool_approval (tenant_id, process_instance_id, approval_id, position, "
                        + "traversal_id, invocation_id, attempt_id, call_id, node_id, tool, "
                        + "canonical_arguments, arguments_digest, requester_request_id, requester_subject, "
                        + "requester_principal_type, requester_issuer, graph_version_pin, policy_version, "
                        + "expires_at_epoch_second, expires_at_nano, required_roles, required_scopes, "
                        + "requester_may_approve, continuation_version, continuation, continuation_digest, "
                        + "status, actor, revision) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?, ?)")) {
            statement.setString(1, approval.key().tenantId());
            statement.setString(2, approval.key().processInstanceId().toString());
            statement.setString(3, request.approvalId().toString());
            statement.setInt(4, position);
            statement.setString(5, request.traversalId().toString());
            statement.setString(6, request.invocationId().toString());
            statement.setString(7, request.attemptId().toString());
            statement.setString(8, request.callId().toString());
            statement.setString(9, request.nodeId());
            statement.setString(10, request.tool());
            statement.setBytes(11, request.canonicalArguments());
            statement.setString(12, request.argumentsDigest());
            statement.setString(13, request.requester().requestId());
            statement.setString(14, request.requester().subject());
            statement.setString(15, request.requester().principalType().name());
            statement.setString(16, request.requester().issuer());
            statement.setString(17, request.graphVersionPin().reference());
            statement.setString(18, request.policyVersion());
            StoredInstant.bindValue(statement, 19, request.expiresAt());
            statement.setString(21, joinTokens(request.approverRequirements().requiredRoles()));
            statement.setString(22, joinTokens(request.approverRequirements().requiredScopes()));
            statement.setInt(23, request.requesterMayApprove() ? 1 : 0);
            statement.setInt(24, request.continuationVersion());
            statement.setBytes(25, request.continuation());
            statement.setString(26, request.continuationDigest());
            statement.setString(27, approval.status().name());
            statement.setString(28, approval.actor());
            statement.setLong(29, approval.revision());
            statement.executeUpdate();
        }
    }

    private void updateToolApproval(DurableToolApproval approval) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE tool_approval SET status = ?, actor = ?, revision = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND approval_id = ?")) {
            statement.setString(1, approval.status().name());
            statement.setString(2, approval.actor());
            statement.setLong(3, approval.revision());
            statement.setString(4, approval.key().tenantId());
            statement.setString(5, approval.key().processInstanceId().toString());
            statement.setString(6, approval.request().approvalId().toString());
            statement.executeUpdate();
        }
    }

    private int nextToolApprovalPosition(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM tool_approval "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private DurableToolApproval readToolApproval(ExecutionKey key, UUID approvalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                TOOL_APPROVAL_COLUMNS + " WHERE a.tenant_id = ? AND a.process_instance_id = ? "
                        + "AND a.approval_id = ?")) {
            bindItem(statement, key, approvalId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readToolApproval(rows) : null;
            }
        }
    }

    private DurableToolApproval readToolApproval(ResultSet rows) throws SQLException {
        var key = new ExecutionKey(rows.getString("tenant_id"),
                UUID.fromString(rows.getString("process_instance_id")));
        try {
            var request = new ToolApprovalRegistration(
                    UUID.fromString(rows.getString("approval_id")),
                    UUID.fromString(rows.getString("traversal_id")),
                    UUID.fromString(rows.getString("invocation_id")),
                    UUID.fromString(rows.getString("attempt_id")),
                    UUID.fromString(rows.getString("call_id")), rows.getString("node_id"),
                    rows.getString("tool"), rows.getBytes("canonical_arguments"),
                    rows.getString("arguments_digest"),
                    new ai.ravenroot.api.security.SecurityContext(
                            rows.getString("requester_request_id"), key.tenantId(),
                            rows.getString("requester_subject"),
                            ai.ravenroot.api.security.PrincipalType.valueOf(
                                    rows.getString("requester_principal_type")),
                            rows.getString("requester_issuer")),
                    new GraphVersionPin(rows.getString("graph_version_pin")),
                    rows.getString("policy_version"), StoredInstant.read(rows, "expires_at"),
                    new HandlerAuthorization(splitTokens(rows.getString("required_roles")),
                            splitTokens(rows.getString("required_scopes"))),
                    rows.getInt("requester_may_approve") == 1,
                    rows.getInt("continuation_version"), rows.getBytes("continuation"),
                    rows.getString("continuation_digest"));
            return new DurableToolApproval(key, request,
                    ToolApprovalStatus.valueOf(rows.getString("status")), rows.getString("actor"),
                    rows.getLong("revision"));
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    private void requireTraversalExists(ProcessInstance folded, UUID traversalId, String what) {
        if (folded == null || !folded.traversals().containsKey(traversalId)) {
            throw failure(ExecutionStoreFailure.invalid(what + " names traversal " + traversalId
                    + ", which this batch neither found nor created"));
        }
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
                + "AND h.status IN " + TERMINAL_HANDLER_STATUSES + " "
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
     * the event journal's atomicity requirement: there is no window between committing the transition
     * and recording the event, because there is no second write to perform.</p>
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
