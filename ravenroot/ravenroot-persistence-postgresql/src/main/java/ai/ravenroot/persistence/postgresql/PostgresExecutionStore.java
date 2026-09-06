package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.EventDigest;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionResultPayload;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.IdempotencyWrite;
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
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The durable shared {@link ExecutionStore}, backed by one PostgreSQL database.
 *
 * <h2>Why this is not a port of the single-host adapter</h2>
 * <p>{@code SqliteExecutionStore} takes the whole database's write lock with {@code BEGIN IMMEDIATE},
 * so it can read state and then write a decision derived from that state with nothing able to
 * intervene. Every such read-then-write sequence is a <strong>lost update</strong> here, because the
 * second writer is a different JVM, usually on a different host, and no lock this process could take
 * would be visible to it. Reproducing those sequences would compile, pass a single-threaded
 * conformance run, and lose updates in production under contention — the one failure this adapter
 * exists to make impossible.</p>
 *
 * <p>Each of them is therefore one of two shapes, and never a third:</p>
 * <ul>
 *   <li>a {@code SELECT ... FOR UPDATE} on the {@code process_instance} row taken inside the same
 *   transaction that writes the decision, which is how {@code apply}, {@code claim}, {@code renew},
 *   {@code ack} and the claim loop are made safe. That row is the serialization point for an
 *   instance's whole lifecycle: revision, fencing token and lease all move under it;</li>
 *   <li>a conditional {@code UPDATE ... WHERE <the value the decision was made on>} or an
 *   {@code INSERT ... ON CONFLICT} whose {@code SET} is computed from the stored row, which is how the
 *   delivery-attempt counter, the journal offset and stream-sequence allocators, the outbox cursor,
 *   the inbox record, {@code release} and every watermark are made safe without a lock at all.</li>
 * </ul>
 *
 * <p>There is no process-local lock anywhere in this class, and there could not be one that helped.</p>
 *
 * <h2>What it declares, and on what evidence</h2>
 * <ul>
 *   <li>{@link StoreCapability#DURABLE} — every batch is one committed PostgreSQL transaction, and
 *   state is read back from the database on reopen.</li>
 *   <li>{@link StoreCapability#TRANSACTIONAL_BATCH} — one transaction per batch, one {@code COMMIT},
 *   every rejection raised before it. {@link Transactions} owns that boundary and is the only one.</li>
 *   <li>{@link StoreCapability#CROSS_PROCESS_LEASE} — leases and fencing tokens live in the database
 *   and are decided by row locks the server holds, so the exclusion extends past the machine, which is
 *   the half the single-host adapter has to disclaim.</li>
 *   <li>{@link StoreCapability#IDEMPOTENCY_PURGE}, {@link StoreCapability#EVENT_JOURNAL},
 *   {@link StoreCapability#JOURNAL_COMPACTION}, {@link StoreCapability#PROCESS_INVENTORY},
 *   {@link StoreCapability#INVENTORY_RETENTION} and {@link StoreCapability#EXECUTION_RESULTS} — all
 *   implemented here against the same rows the lifecycle writes.</li>
 * </ul>
 *
 * <p>{@link StoreCapability#DURABLE_HANDLERS}, {@link StoreCapability#TOOL_APPROVALS},
 * {@link StoreCapability#HUMAN_TASKS}, {@link StoreCapability#HUMAN_TASK_CONFIRMATIONS},
 * {@link StoreCapability#EXECUTION_PAUSES} and {@link StoreCapability#AGENT_AUTHORITY_BUDGETS} are
 * <strong>not</strong> declared, so the port's own defaults report
 * {@link ExecutionStoreFailure.CapabilityNotSupported} for them and the conformance suite skips their
 * assertions <em>visibly</em>. Their tables exist all the same, so the work that implements them is
 * additive rather than a migration applied to a live deployment. A batch that carries one of those
 * registrations is refused with the same classified failure rather than silently dropped: accepting it
 * and writing nothing would tell a caller its handler was registered when nothing was.</p>
 *
 * <h2>The store is its own clock authority</h2>
 * <p>Every temporal predicate is evaluated against the injected {@link Clock}, read once per operation
 * in Java and bound as a parameter. No SQL in this adapter contains {@code now()},
 * {@code CURRENT_TIMESTAMP} or any other database-side clock. A predicate reading the server's clock
 * would ignore the injected one entirely, so every lease and timer assertion would either never fire
 * or fire for the wrong reason — and with several hosts there is a second reason: the database's clock
 * and the deployment's clocks are different clocks, and only one of them is the one the caller reasons
 * about.</p>
 *
 * <h2>Four boundary conventions, and they are not uniform</h2>
 * <p>Each is inclusive or exclusive on its own merits, matching the other adapters exactly. Writing
 * one from intuition gets at least one of them wrong.</p>
 * <ol>
 *   <li><strong>A lease is live while {@code now < expiresAt}</strong>, strictly.</li>
 *   <li><strong>A timer is due when {@code dueAt <= now}</strong>, inclusive.</li>
 *   <li><strong>A claimed work item is visible again when {@code visibleAgainAt <= now}</strong>,
 *   inclusive.</li>
 *   <li><strong>An idempotency record is collectable when {@code expiresAt < now}</strong>,
 *   <em>strictly</em> — the odd one out, because {@code expiresAt} is the earliest instant at which the
 *   store is <em>permitted</em> to forget, and forgetting exactly then would forget at the first
 *   instant of the last moment it promised to retain.</li>
 * </ol>
 *
 * <h2>The connection is never named here</h2>
 * <p>This adapter is handed a {@link DataSource} and never sees a URL, a credential or a pool setting.
 * Everything operational — TLS, pool size, failover, the schema the search path resolves to — is the
 * deployment's, and the adapter's surface is deliberately too narrow to disagree with it. It follows
 * that {@link #close()} does not close the {@code DataSource}: this store did not open it and may not
 * be the only thing using it.</p>
 */
public final class PostgresExecutionStore implements ExecutionStore {

    private static final Set<StoreCapability> CAPABILITIES = Set.of(
            StoreCapability.DURABLE,
            StoreCapability.TRANSACTIONAL_BATCH,
            StoreCapability.CROSS_PROCESS_LEASE,
            StoreCapability.IDEMPOTENCY_PURGE,
            StoreCapability.EVENT_JOURNAL,
            StoreCapability.JOURNAL_COMPACTION,
            // The inventory reads the same process_instance, traversal, invocation, attempt and lease
            // rows that apply() writes inside one transaction, so it is atomic with the lifecycle by
            // construction rather than by a projection that has to be kept in step. There is no offset
            // to repair and no rebuild that could invent work.
            StoreCapability.PROCESS_INVENTORY,
            StoreCapability.INVENTORY_RETENTION,
            StoreCapability.EXECUTION_RESULTS);

    /**
     * The columns every inventory row needs, plus the two aggregate counts and the lease, in one
     * statement.
     *
     * <p>The counts are correlated subqueries rather than a {@code GROUP BY} over two joins, because
     * joining traversals and attempts in the same query multiplies the rows and every aggregate then
     * has to be de-duplicated — which is both slower and, more to the point, the kind of query that is
     * subtly wrong in a way no assertion notices until a row has two traversals and three attempts.</p>
     *
     * <p>The lease is a {@code LEFT JOIN}: an instance with no lease is the normal shape of interrupted
     * work and must appear in the listing, so an inner join would hide precisely the cohort the
     * inventory exists to surface.</p>
     */
    private static final String INVENTORY_COLUMNS =
            "SELECT p.process_instance_id, p.status, p.termination_reason, p.graph_version_pin, "
                    + "p.revision, p.fencing_token, p.lifecycle_generation, p.deployment_id, "
                    + "p.workload_id, p.correlation_id, p.created_at_epoch_second, p.created_at_nano, "
                    + "p.updated_at_epoch_second, p.updated_at_nano, p.retained_until_epoch_second, "
                    + "p.retained_until_nano, l.worker_id AS lease_worker_id, "
                    + "l.expires_at_epoch_second AS lease_expires_at_epoch_second, "
                    + "l.expires_at_nano AS lease_expires_at_nano, "
                    + "(SELECT COUNT(*) FROM traversal t WHERE t.tenant_id = p.tenant_id "
                    + "AND t.process_instance_id = p.process_instance_id) AS traversal_count, "
                    + "(SELECT COUNT(*) FROM attempt a WHERE a.tenant_id = p.tenant_id "
                    + "AND a.process_instance_id = p.process_instance_id AND a.status = 'PARKED') "
                    + "AS parked_count "
                    + "FROM process_instance p LEFT JOIN lease l ON l.tenant_id = p.tenant_id "
                    + "AND l.process_instance_id = p.process_instance_id ";

    private static final String META_COLUMNS =
            "SELECT revision, fencing_token, graph_version_pin, status, termination_reason, "
                    + "updated_at_epoch_second, updated_at_nano, created_at_epoch_second, "
                    + "created_at_nano, lifecycle_generation, deployment_id, workload_id, "
                    + "correlation_id, retained_until_epoch_second, retained_until_nano "
                    + "FROM process_instance WHERE tenant_id = ? AND process_instance_id = ?";

    private final DataSource dataSource;
    private final Clock clock;
    private final PostgresStoreConfig config;
    private final HumanTaskPolicy humanTaskPolicy;
    private final Transactions transactions;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a store over {@code dataSource}, migrating the schema if it is not current. */
    public PostgresExecutionStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, PostgresStoreConfig.defaults(), HumanTaskPolicy.DEFAULTS,
                CommitBoundary.NONE);
    }

    /** Creates a store with explicit contention and limit settings. */
    public PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config) {
        this(dataSource, clock, config, HumanTaskPolicy.DEFAULTS, CommitBoundary.NONE);
    }

    /** Creates a store that additionally publishes {@code humanTaskPolicy}'s bounds. */
    public PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config,
                                  HumanTaskPolicy humanTaskPolicy) {
        this(dataSource, clock, config, humanTaskPolicy, CommitBoundary.NONE);
    }

    PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config,
                           CommitBoundary commitBoundary) {
        this(dataSource, clock, config, HumanTaskPolicy.DEFAULTS, commitBoundary);
    }

    PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config,
                           HumanTaskPolicy humanTaskPolicy, CommitBoundary commitBoundary) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.humanTaskPolicy = Objects.requireNonNull(humanTaskPolicy, "humanTaskPolicy");
        this.transactions = new Transactions(dataSource, config,
                Objects.requireNonNull(commitBoundary, "commitBoundary"));
        // Named and daemon so a thread dump says which store is blocked and a forgotten close cannot
        // hold the JVM open. Unbounded because the DataSource is the real bound: a task that cannot get
        // a connection blocks there, which is where the deployment configured the limit.
        var sequence = new AtomicLong();
        this.worker = Executors.newCachedThreadPool(runnable -> {
            var thread = new Thread(runnable, "ravenroot-postgres-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            worker.shutdownNow();
            throw mapped(failed, null);
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

    @Override
    public Duration journalRetention() {
        return config.journalRetention();
    }

    @Override
    public Duration executionResultRetention() {
        return config.executionResultRetention();
    }

    @Override
    public int maxExecutionResultPayloadBytes() {
        return config.maxPayloadBytes();
    }

    @Override
    public int maxHumanTaskPageSize() {
        return humanTaskPolicy.inboxMaxPageSize();
    }

    @Override
    public int maxHumanTaskAttentionPageSize() {
        return humanTaskPolicy.confirmation().attentionMaxPageSize();
    }

    @Override
    public int maxHumanTaskAttentionNodeCounts() {
        return HumanTaskPolicy.Confirmation.HARD_MAX_ATTENTION_NODE_COUNTS;
    }

    @Override
    public int maxHumanTaskResponsePayloadBytes() {
        // The stable structured-payload ceiling rather than today's general execution-payload setting:
        // a task accepted under one configuration must remain resolvable after a restart under
        // another, and this number is the one that cannot move underneath a stored row.
        return PayloadLimits.HARD_MAX_ENCODED_BYTES;
    }

    // ---------------------------------------------------------------- write path

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        return async(() -> {
            Objects.requireNonNull(batch, "batch");
            // All decidable from the request alone, so they happen before a connection is taken from
            // the pool, let alone a transaction opened. A rejection that needed a round trip would make
            // a caller bug cost the same as a write.
            requireNoFencingTokenUnderNotPresent(batch);
            requireOnlyDeclaredFacilities(batch);
            batch.timersToSchedule().forEach(timer -> requireWithinPayloadLimit(timer.payload()));
            batch.idempotency().ifPresent(write -> {
                requireWithinPayloadLimit(write.requestFingerprint());
                requireWithinPayloadLimit(write.outcomeRef());
            });
            requireEnvelopesMatchBatch(batch);
            return write(batch.key(), connection -> applyLocked(connection, batch));
        });
    }

    /**
     * The whole of a batch, inside one transaction, under the instance's own row lock.
     *
     * <p>The {@code FOR UPDATE} on the first read is what makes every later decision in this method
     * sound. Fencing, replay, the revision expectation and the fold are all derived from state read
     * here, and each of them is written back before the transaction ends; without the lock a second
     * host could move the row in between and this method would write a decision about a state that no
     * longer exists.</p>
     */
    private StoredProcessInstance applyLocked(Connection connection, ExecutionBatch batch)
            throws SQLException {
        ExecutionKey key = batch.key();
        InstanceMeta existing = readMeta(connection, key, true);

        // Fencing -> replay -> expectation -> fold.
        //
        // Fencing precedes every check that could yield a SUCCESS. Answering a fenced worker from its
        // own idempotency record would tell it its work landed and let it briefly believe it still owns
        // the instance, which is the split-brain belief the fence exists to destroy; the new owner is
        // the party that needs the outcome, and it holds a current token.
        //
        // Existence is a PRECONDITION of fencing, not a competitor to it, which is why this is guarded
        // on `existing != null`. A lease on an instance that does not exist is not stale, it is
        // impossible, and reporting FencedOut with a current token of zero would fabricate a value to
        // stand in for the absence of any token.
        if (existing != null) {
            requireFencingTokenCurrent(key, batch, existing);
        }

        StoredProcessInstance replay = replayOf(connection, batch, existing);
        if (replay != null) {
            return replay;
        }

        // Expectation LAST. A write that already happened necessarily bumped the revision, so a
        // retrying caller's expectation is stale by construction; checking it first would make every
        // legitimate replay fail with ConcurrencyConflict.
        requireExpectationMet(key, batch.expectation(), existing, batch);

        ProcessInstance current = existing == null ? null : readAggregate(connection, key, existing);
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
        // none. It is NOT the fencing token and must never be conflated with one: a generation counts
        // how far the lifecycle has moved, a token names who is allowed to move it.
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

        if (existing == null) {
            insertInstanceRow(connection, key, folded, pin, revision, now, createdAt, generation, origin,
                    retainedUntil);
        } else {
            updateInstanceRow(connection, key, folded, pin, revision, now, generation, origin,
                    retainedUntil, existing.revision());
        }
        AggregateStorage.write(connection, key, folded);
        writeTimers(connection, key, batch);
        IdempotencyWrite idempotency = batch.idempotency().orElse(null);
        if (idempotency != null) {
            writeIdempotencyRecord(connection, key, idempotency, revision, now);
        }
        // Inside the same transaction as the transition above, which is the entirety of the shared
        // transactional boundary the event journal promises. There is no publish step to crash between,
        // because there is no publish step: delivery reads the committed journal afterwards.
        writeJournal(connection, key, batch, revision, now);
        dropAcknowledgementsForRescheduledWork(connection, key);

        // fencingToken is read but never written here: apply does not rotate the fence, and assigning
        // it in the UPDATE would silently undo a claim that committed between this transaction's read
        // and its write if the row lock above were ever removed.
        assert fencingToken >= 0;
        return new StoredProcessInstance(folded, revision, pin, key.tenantId(), now);
    }

    @Override
    public CompletionStage<StoredProcessInstance> load(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> {
                InstanceMeta meta = readMeta(connection, key, false);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                ProcessInstance state = readAggregate(connection, key, meta);
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
            return write(key, connection -> {
                // FOR UPDATE, because the next statement writes a fencing token derived from the one
                // read here. Two hosts claiming at once without it both read token N and both write
                // N+1, and the fence -- whose entire purpose is that two owners cannot exist -- would
                // hand the same number to both of them.
                InstanceMeta meta = readMeta(connection, key, true);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                Instant now = clock.instant();
                LeaseHandle held = readLease(connection, key, meta.fencingToken());
                if (held != null && !held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
                    // Failing to ACQUIRE, not losing one that was held: ordinary contention, nothing
                    // started and nothing at risk. Reporting it as LeaseLost would bury the rare
                    // critical signal under routine noise.
                    throw failure(new ExecutionStoreFailure.LeaseHeldByAnother(key, held.workerId(),
                            held.expiresAt()));
                }
                return issueLease(connection, key, meta.fencingToken(), held, workerId, ttl, now);
            });
        });
    }

    @Override
    public CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            requireLeaseTtl(ttl);
            ExecutionKey key = lease.key();
            return write(key, connection -> {
                // FOR UPDATE even though no token is written: the decision "this lease is still mine"
                // is read from the instance's token, and a claim committing between that read and the
                // lease upsert would let this call extend a lease it no longer owns.
                InstanceMeta meta = readMeta(connection, key, true);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                LeaseHandle held = readLease(connection, key, meta.fencingToken());
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
                upsertLease(connection, renewed);
                return renewed;
            });
        });
    }

    /**
     * Releases a held lease with one conditional statement and no row lock.
     *
     * <p>This is the shape a lost update is impossible in rather than merely unlikely: the fencing
     * token the caller's authority rests on is in the {@code WHERE} clause, so the delete either
     * happens against the state the caller reasoned about or does not happen at all. Reading the token
     * first and deleting second would be the same sequence with a window in it, and the window is where
     * a concurrent claim's lease gets deleted by the worker it just replaced.</p>
     *
     * <p>Zero rows deleted is a no-op and not a failure, because releasing a lease already lost is
     * defined as one.</p>
     */
    @Override
    public CompletionStage<Void> release(LeaseHandle lease) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            ExecutionKey key = lease.key();
            return write(key, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM lease l USING process_instance p "
                                + "WHERE l.tenant_id = ? AND l.process_instance_id = ? "
                                + "AND l.worker_id = ? AND p.tenant_id = l.tenant_id "
                                + "AND p.process_instance_id = l.process_instance_id "
                                + "AND p.fencing_token = ?")) {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    statement.setString(3, lease.workerId());
                    statement.setLong(4, lease.fencingToken());
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
            return read(null, connection -> {
                Instant now = clock.instant();
                var active = new ArrayList<LeaseHandle>();
                // A stable order has to come from data here: PostgreSQL has no rowid, and an
                // unordered listing would return the same leases in a different order on every call,
                // which an operator diffing two listings would read as churn.
                String sql = "SELECT l.process_instance_id, "
                        + "p.process_instance_id AS joined_process_id, l.worker_id, "
                        + "l.claimed_at_epoch_second, l.claimed_at_nano, l.expires_at_epoch_second, "
                        + "l.expires_at_nano, p.fencing_token "
                        + "FROM lease l LEFT JOIN process_instance p ON p.tenant_id = l.tenant_id "
                        + "AND p.process_instance_id = l.process_instance_id "
                        + "WHERE l.tenant_id = ? AND " + StoredInstant.strictlyAfter("l.expires_at")
                        + " ORDER BY l.process_instance_id";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var key = new ExecutionKey(tenantId, StoredUuid.required(rows, "lease",
                                    "process_instance_id", tenantId));
                            StoredUuid.requiredMatching(rows, "process_instance", "joined_process_id",
                                    key, key.processInstanceId());
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
            return write(null, connection -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork>();
                for (Candidate candidate : lockClaimable(connection, tenantId, workerId, now, limit, true)) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    List<ScheduledAttempt> attempts = claimableAttempts(connection, candidate.key(), now);
                    List<TimerSchedule> timers = claimableTimers(connection, candidate.key(), now);
                    if (attempts.isEmpty() && timers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(connection, candidate.key(), candidate.fencingToken(),
                            readLease(connection, candidate.key(), candidate.fencingToken()), workerId,
                            leaseTtl, now);
                    for (ScheduledAttempt attempt : attempts) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimAttempt(connection, candidate.key(), attempt, lease, now, leaseTtl));
                    }
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(connection, candidate.key(), timer, lease, now, leaseTtl));
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
            return write(null, connection -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork.TimerDue>();
                for (Candidate candidate : lockClaimable(connection, tenantId, workerId, now, limit, false)) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    List<TimerSchedule> timers = claimableTimers(connection, candidate.key(), now);
                    if (timers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(connection, candidate.key(), candidate.fencingToken(),
                            readLease(connection, candidate.key(), candidate.fencingToken()), workerId,
                            leaseTtl, now);
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(connection, candidate.key(), timer, lease, now, leaseTtl));
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
            return write(key, connection -> {
                // FOR UPDATE: the fence check below and the deletion under it are one decision, and a
                // claim committing between them would let a worker that has just been fenced out
                // acknowledge -- and so suppress -- work its replacement is about to be handed.
                InstanceMeta meta = readMeta(connection, key, true);
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
                        "INSERT INTO work_acknowledgement (tenant_id, process_instance_id, work_item_id) "
                                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
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
            return read(null, connection -> {
                requireIssuanceWithinSkewBudget(keyIssuedAt);
                // The record is read BEFORE the watermark, and the order is load-bearing rather than
                // incidental. Under READ COMMITTED each statement sees its own snapshot, so a purge can
                // land between the two; reading the record first means the watermark this call compares
                // against is at least as advanced as the one in force when the record was looked for,
                // and every ambiguity therefore falls towards "expired" rather than towards "safe to
                // apply". The reverse order fails open, which is a silent re-execution.
                IdempotencyRecord record = readIdempotencyRecord(connection, tenantId, key);
                if (record != null) {
                    return Optional.of(record);
                }
                if (provablyNeverRecorded(connection, tenantId, keyIssuedAt)) {
                    // Any record for this key would still carry an expiresAt at or above the tenant's
                    // watermark, so its absence PROVES it was never written and the caller may safely
                    // apply. That is an absent answer, not a missing entity.
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
            return read(null, connection -> countOf(connection,
                    "SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ?", tenantId));
        });
    }

    /**
     * Purges in <strong>two</strong> transactions: the watermark advances in the first, the records are
     * deleted in the second.
     *
     * <p>The order is load-bearing — advance, then delete — and one transaction would make it
     * unobservable, because a crash would undo both and the ordering would never have been tested
     * against anything. Split, the interrupted state is real and reachable, and it is the conservative
     * one: the watermark is ahead of the deletions, and a record still present is still answered
     * <em>from the record</em>, so an early watermark costs nothing. The reverse — deleting first and
     * dying before the watermark moved — would leave absent records below a stale watermark, which
     * reads as "never recorded" and silently re-executes.</p>
     *
     * <p>The zero guard is equally load-bearing in the other direction. A purge that forgot nothing must
     * not move the watermark at all: doing so would destroy provable absence for every key the tenant
     * issued before that instant, and a periodic purge job would inflict it on every tick.</p>
     */
    @Override
    public CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();

            boolean anythingToForget = write(null, connection -> {
                String sql = "SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ? AND "
                        + StoredInstant.strictlyBefore("expires_at");
                long expired;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    try (ResultSet rows = statement.executeQuery()) {
                        expired = rows.next() ? rows.getLong(1) : 0L;
                    }
                }
                if (expired == 0L) {
                    return false;
                }
                advanceWatermark(connection, "idempotency_watermark", "forgotten_before", tenantId, now);
                return true;
            });
            if (!anythingToForget) {
                return 0L;
            }
            return write(null, connection -> {
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
            return read(null, connection -> watermarkOf(connection, "idempotency_watermark",
                    "forgotten_before", tenantId));
        });
    }

    // ---------------------------------------------------------------- durable execution inventory

    @Override
    public CompletionStage<ProcessInventoryPage> listProcessInstances(String tenantId,
                                                                      ProcessInventoryQuery query) {
        return async(() -> {
            requireTenantId(tenantId);
            requireInventoryQuery(query);
            InventoryCursor.Position after = query.cursor()
                    .map(cursor -> InventoryCursor.decode(tenantId, cursor))
                    .orElse(null);
            return read(null, connection -> {
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
                    var terminal = terminalStatusNames();
                    sql.append(" AND p.status NOT IN (")
                            .append("?, ".repeat(terminal.size() - 1)).append("?)");
                    binds.addAll(terminal);
                }
                query.deploymentId().ifPresent(deployment -> {
                    sql.append(" AND p.deployment_id = ?");
                    binds.add(deployment);
                });
                query.ownerWorkerId().ifPresent(owner -> {
                    // A live lease only. A lapsed lease names the worker that has stopped renewing, and
                    // answering "owned by w" with work w has abandoned is the opposite of what an
                    // operator draining a worker is asking for.
                    sql.append(" AND l.worker_id = ? AND ")
                            .append(StoredInstant.strictlyAfter("l.expires_at"));
                    binds.add(owner);
                    binds.add(now);
                });
                if (after != null) {
                    // Strictly after the cursor under (created_at DESC, process_instance_id DESC), as a
                    // single row comparison rather than the unrolled disjunction the text-keyed adapter
                    // has to write. The third component is a native uuid, and that is sound only
                    // because PostgreSQL compares uuid bytewise while the other adapters compare the
                    // canonical lowercase string -- which is the same order, character for character.
                    // It is deliberately NOT java.util.UUID.compareTo, which is signed and would
                    // disagree for half of all identifiers.
                    sql.append(" AND (p.created_at_epoch_second, p.created_at_nano, "
                            + "p.process_instance_id) < (?, ?, ?)");
                    binds.add(after.createdAt().getEpochSecond());
                    binds.add(after.createdAt().getNano());
                    binds.add(after.processInstanceId());
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
                    // cost every caller an empty round trip, and a caller that reads a present cursor as
                    // "there is more" would report outstanding work that does not exist.
                    statement.setInt(index, query.limit() + 1);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (page.size() == query.limit()) {
                                more = true;
                                break;
                            }
                            page.add(readInventoryRow(tenantId, rows, now, null));
                        }
                    }
                }
                Optional<String> next = more
                        ? Optional.of(InventoryCursor.encode(tenantId, page.getLast().createdAt(),
                                page.getLast().key().processInstanceId()))
                        : Optional.empty();
                return new ProcessInventoryPage(List.copyOf(page), next,
                        watermarkOf(connection, "inventory_watermark", "retained_from", tenantId));
            });
        });
    }

    @Override
    public CompletionStage<Optional<ProcessInventoryEntry>> findProcessInstance(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> {
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement(INVENTORY_COLUMNS
                        + "WHERE p.tenant_id = ? AND p.process_instance_id = ?")) {
                    // Both halves of the key are in the predicate, so a row belonging to another tenant
                    // is not excluded by a check that could be forgotten -- it is not selected at all.
                    // Absent and not-yours are therefore the same empty answer by construction, and the
                    // store cannot be used as a cross-tenant existence oracle.
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next()
                                ? Optional.of(readInventoryRow(key.tenantId(), rows, now, key))
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
            return read(key, connection -> {
                InstanceMeta meta = readMeta(connection, key, false);
                if (meta == null) {
                    // NotFound rather than an empty list: an instance that exists with no traversals
                    // honestly reports none, and collapsing the two would make "you asked about
                    // nothing" indistinguishable from "it has nothing".
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                boolean leaseLive = leaseLive(connection, key, clock.instant());
                String sql = "SELECT t.traversal_id, t.position, t.ingress_node_id, t.status, "
                        + "t.termination_reason, "
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
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            TraversalStatus status = traversalStatusOf(key, rows.getString("status"));
                            int parked = rows.getInt("parked_count");
                            rowsOut.add(new TraversalInventoryEntry(key,
                                    StoredUuid.required(rows, "traversal", "traversal_id", key),
                                    rows.getInt("position"), rows.getString("ingress_node_id"), status,
                                    InventoryDisposition.ofTraversal(status, leaseLive, parked > 0),
                                    rows.getInt("invocation_count"), parked,
                                    terminationReasonOf(key, rows.getString("termination_reason"))));
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
            return read(null, connection -> watermarkOf(connection, "inventory_watermark",
                    "retained_from", tenantId));
        });
    }

    /**
     * Purges in <strong>two</strong> transactions, floor first and deletions second, for the reason
     * {@link #purgeExpiredIdempotencyRecords(String)} records: the interrupted state has to be the
     * conservative one. A floor ahead of the deletions says "rows before this instant may be gone" while
     * they are in fact still present — a false alarm the surviving row itself answers. The reverse
     * leaves rows genuinely gone under a floor that claims completeness, and every absent instance then
     * reads as one that never existed.
     */
    @Override
    public CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();
            // updated_at + retention <= now, rearranged so the comparison is against a stored column
            // rather than against an expression the planner would have to evaluate per row.
            Instant lapsed = minusClamped(now, config.terminalRetention());

            Instant floor = write(null, connection -> latestExpiredDeadline(connection, tenantId, now, lapsed));
            if (floor == null) {
                return 0L;
            }
            write(null, connection -> {
                advanceWatermark(connection, "inventory_watermark", "retained_from", tenantId, floor);
                return null;
            });
            return write(null, connection -> {
                // ON DELETE CASCADE clears the traversals, invocations, causal edges, attempts, timers,
                // leases, work bookkeeping and results of every removed instance. The journal is
                // deliberately NOT cascaded: its offsets must never be reissued, and
                // journal_stream_sequence keeps the per-instance counter of a removed instance for
                // exactly that reason.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM process_instance WHERE tenant_id = ? AND process_instance_id IN ("
                                + expiredInstanceIdQuery() + ")")) {
                    bindExpiredInstanceQuery(statement, tenantId, now, lapsed);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- durable execution results

    /**
     * Writes the result, or recognises that it is already written, inside one transaction.
     *
     * <p>The insert is {@code ON CONFLICT DO NOTHING} rather than a bare insert, and that is what makes
     * two hosts recording the same terminal event concurrently safe. Both read no row and both attempt
     * the insert; the loser writes nothing, re-reads the winner's row and answers from its fingerprint —
     * which is the same answer it would have given a moment later. A bare insert would abort the loser's
     * whole transaction on a unique violation, so the idempotent success would have to be recovered by
     * retrying an operation that had already succeeded.</p>
     *
     * <p>The deadline is computed from {@code endedAt} rather than from now, so the window a caller is
     * promised starts when the execution ended and not when the write happened to land. A retry after an
     * ambiguous write therefore reproduces the identical deadline, which is what lets the fingerprint
     * comparison stay a comparison of what the producer decided.</p>
     */
    @Override
    public CompletionStage<DurableExecutionResult> recordExecutionResult(DurableExecutionResult result) {
        return async(() -> {
            Objects.requireNonNull(result, "result");
            ExecutionKey key = result.key();
            requireResultPayloadWithinLimit(result);
            DurableExecutionResult candidate = result.withRetainedUntil(
                    plusClamped(result.endedAt(), config.executionResultRetention()));
            Instant now = clock.instant();
            return write(key, connection -> {
                DurableExecutionResult stored = readExecutionResult(connection, key.tenantId(),
                        result.traversalId());
                if (stored != null) {
                    return reconcile(stored, candidate);
                }
                requireInstanceExists(connection, key);
                boolean written;
                try {
                    written = insertExecutionResult(connection, candidate, now);
                } catch (SQLException failed) {
                    if (SqlStates.isForeignKeyViolation(failed)) {
                        // The instance was there a statement ago and is not there now: a purge or a
                        // cascade committed in between. The foreign key is the authority rather than
                        // the check above, which is why this is reported as the same NotFound and not
                        // as a generic collision -- a result whose instance is gone is a dangling row,
                        // and refusing it is the whole reason the key exists.
                        throw failure(new ExecutionStoreFailure.NotFound(key));
                    }
                    throw failed;
                }
                if (written) {
                    return candidate;
                }
                DurableExecutionResult raced = readExecutionResult(connection, key.tenantId(),
                        result.traversalId());
                if (raced == null) {
                    // The insert was refused by the primary key and the row is not there: the only way
                    // that happens is a delete committing between the two statements, which means the
                    // instance itself has gone.
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                return reconcile(raced, candidate);
            });
        });
    }

    private static DurableExecutionResult reconcile(DurableExecutionResult stored,
                                                    DurableExecutionResult candidate) {
        if (stored.fingerprint().equals(candidate.fingerprint())) {
            return stored;
        }
        throw failure(new ExecutionStoreFailure.ExecutionResultNotRecordable(candidate.traversalId(),
                stored.status(), candidate.status(), stored.fingerprint(), candidate.fingerprint()));
    }

    @Override
    public CompletionStage<Optional<DurableExecutionResult>> loadExecutionResult(String tenantId,
                                                                                 UUID traversalId) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(traversalId, "traversalId");
            Instant now = clock.instant();
            return read(null, connection -> {
                DurableExecutionResult stored = readExecutionResult(connection, tenantId, traversalId);
                if (stored == null) {
                    return Optional.<DurableExecutionResult>empty();
                }
                // Retained while now < retainedUntil, strictly, so the boundary matches the purge's
                // exactly: a row eligible for collection is a row whose payload this read no longer
                // offers. Any other pairing would produce an instant at which a result is purgeable and
                // still readable in full, or readable as expired and not yet purgeable.
                return Optional.of(now.isBefore(stored.retainedUntil()) ? stored : stored.expired());
            });
        });
    }

    @Override
    public CompletionStage<Instant> executionResultsRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> watermarkOf(connection, "execution_result_watermark",
                    "retained_from", tenantId));
        });
    }

    @Override
    public CompletionStage<Long> purgeExpiredExecutionResults(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();
            Instant floor = write(null, connection -> latestExpiredResultDeadline(connection, tenantId, now));
            if (floor == null) {
                // A purge that removed nothing must leave the floor where it is. Advancing it would
                // report a retention gap that does not exist, on every tick of a periodic job.
                return 0L;
            }
            write(null, connection -> {
                advanceWatermark(connection, "execution_result_watermark", "retained_from", tenantId, floor);
                return null;
            });
            return write(null, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM execution_result WHERE tenant_id = ? AND "
                                + StoredInstant.atOrBefore("retained_until"))) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- event journal and outbox

    @Override
    public CompletionStage<List<JournalRecord>> readJournal(String tenantId, long afterOffset, int limit) {
        return async(() -> {
            requireTenantId(tenantId);
            if (afterOffset < 0) {
                throw failure(ExecutionStoreFailure.invalid("afterOffset cannot be negative"));
            }
            requireLimit(limit);
            return read(null, connection -> {
                long retainedFrom = readWatermarkColumn(connection, tenantId, "retained_from");
                // Strictly below: a caller resuming from the last offset it saw is asking for what comes
                // after a record it already holds, and that record being the oldest survivor is the
                // healthy steady state rather than a truncation.
                if (afterOffset + 1 < retainedFrom) {
                    throw failure(new ExecutionStoreFailure.JournalTruncated(tenantId, afterOffset,
                            retainedFrom));
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

    @Override
    public CompletionStage<Long> journalRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> readWatermarkColumn(connection, tenantId, "retained_from"));
        });
    }

    @Override
    public CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
        return async(() -> {
            requireTenantId(tenantId);
            requireDestination(destination);
            return read(null, connection -> new JournalCursor(tenantId, destination,
                    readCursor(connection, tenantId, destination)));
        });
    }

    /**
     * Advances a destination's cursor, as a compare-and-set the database performs rather than one this
     * process performs on its behalf.
     *
     * <p>Both arms carry the expectation. The update's {@code WHERE} names the position the caller
     * decided from, and the insert runs only when that position is the start — because an absent row
     * <em>is</em> position zero, and an insert accepted under any other expectation would silently
     * fabricate agreement with a cursor that never existed. Reading the stored value first and updating
     * second is the same logic with a window in it, and the window is where two publishers both advance
     * past events only one of them delivered — a silent loss rather than a duplicate.</p>
     */
    @Override
    public CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected, long throughOffset) {
        return async(() -> {
            Objects.requireNonNull(expected, "expected");
            if (throughOffset < expected.deliveredThrough()) {
                throw failure(ExecutionStoreFailure.invalid("a cursor cannot retreat: destination "
                        + expected.destination() + " is at " + expected.deliveredThrough()
                        + " and was asked to move to " + throughOffset));
            }
            return write(null, connection -> {
                int moved;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE outbox_cursor SET delivered_through = ? WHERE tenant_id = ? "
                                + "AND destination = ? AND delivered_through = ?")) {
                    statement.setLong(1, throughOffset);
                    statement.setString(2, expected.tenantId());
                    statement.setString(3, expected.destination());
                    statement.setLong(4, expected.deliveredThrough());
                    moved = statement.executeUpdate();
                }
                if (moved == 0 && expected.deliveredThrough() == 0L) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO outbox_cursor (tenant_id, destination, delivered_through) "
                                    + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                        statement.setString(1, expected.tenantId());
                        statement.setString(2, expected.destination());
                        statement.setLong(3, throughOffset);
                        moved = statement.executeUpdate();
                    }
                }
                if (moved == 0) {
                    throw failure(new ExecutionStoreFailure.OutboxCursorConflict(expected.tenantId(),
                            expected.destination(), expected.deliveredThrough(),
                            readCursor(connection, expected.tenantId(), expected.destination())));
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
                throw failure(ExecutionStoreFailure.invalid(
                        "inbox retention must be positive and is mandatory"));
            }
            return write(null, connection -> {
                Instant expiresAt = plusClamped(clock.instant(), retention);
                // The insert IS the decision. Two consumers racing on the same event both attempt it and
                // exactly one row appears, so exactly one of them is told this is a first delivery -- a
                // probe followed by an insert would tell both of them, and both would apply the effect.
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO inbox_record (tenant_id, consumer_id, event_id, "
                                + "expires_at_epoch_second, expires_at_nano) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT DO NOTHING")) {
                    statement.setString(1, tenantId);
                    statement.setString(2, consumerId);
                    StoredUuid.bind(statement, 3, eventId);
                    StoredInstant.bindValue(statement, 4, expiresAt);
                    inserted = statement.executeUpdate();
                }
                if (inserted > 0) {
                    return Boolean.TRUE;
                }
                // Already recorded, so the caller must not apply the effect. The expiry is extended
                // rather than left alone: a redelivery is evidence the sender still believes this event
                // is in flight, so forgetting it on the original schedule would let the next redelivery
                // be treated as a first delivery. The extension is conditional on the stored value being
                // the earlier one, so two concurrent redeliveries cannot move it backwards.
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE inbox_record SET expires_at_epoch_second = ?, expires_at_nano = ? "
                                + "WHERE tenant_id = ? AND consumer_id = ? AND event_id = ? AND "
                                + StoredInstant.strictlyBefore("expires_at"))) {
                    int index = StoredInstant.bindValue(statement, 1, expiresAt);
                    statement.setString(index++, tenantId);
                    statement.setString(index++, consumerId);
                    StoredUuid.bind(statement, index++, eventId);
                    StoredInstant.bindComparison(statement, index, expiresAt);
                    statement.executeUpdate();
                }
                return Boolean.FALSE;
            });
        });
    }

    @Override
    public CompletionStage<Long> inboxRecordCount(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> countOf(connection,
                    "SELECT COUNT(*) FROM inbox_record WHERE tenant_id = ?", tenantId));
        });
    }

    @Override
    public CompletionStage<Long> compactJournal(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return write(null, connection -> {
                // "Delivered by every known destination" is the minimum over all cursors, and with no
                // destination at all it is zero, so nothing is compactable. That is the conservative
                // direction and it is deliberate: reading "nobody is listening" as "everybody has
                // received it" would discard the whole backlog of a deployment whose projection has not
                // been enabled yet, and no publisher would ever notice, because a publisher that never
                // saw an event has nothing to miss.
                long deliveredEverywhere = minimumCursor(connection, tenantId);
                Instant cutoff = minusClamped(clock.instant(), config.journalRetention());

                // Only a contiguous prefix goes. Punching a hole in the middle would leave surviving
                // offsets that no single retained_from could honestly describe.
                long ceiling;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT MIN(journal_offset) FROM event_journal WHERE tenant_id = ? "
                                + "AND (journal_offset > ? OR "
                                + StoredInstant.strictlyAfter("recorded_at") + ")")) {
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
                    ceiling = readWatermarkColumn(connection, tenantId, "next_offset") - 1;
                }

                long discarded;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM event_journal WHERE tenant_id = ? AND journal_offset <= ?")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, ceiling);
                    discarded = statement.executeUpdate();
                }
                if (discarded > 0) {
                    writeRetainedFrom(connection, tenantId, ceiling + 1);
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

    // ---------------------------------------------------------------- lifecycle

    /**
     * Releases what the <em>process</em> owns and no lease, and in particular not the
     * {@link DataSource}.
     *
     * <p>A {@code kill -9} releases no leases, so this does not either: a clean shutdown and a crash
     * must differ only in latency, or crash recovery and orderly recovery would follow different paths
     * and every recovery test would be evidence about the path nobody experiences in production.</p>
     *
     * <p>The {@code DataSource} was handed to this store and may be shared with the graph-definition and
     * manifest stores of the same deployment; closing it here would take those down as a side effect of
     * closing this one.</p>
     */
    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        worker.shutdown();
    }

    // ---------------------------------------------------------------- batch helpers

    private StoredProcessInstance replayOf(Connection connection, ExecutionBatch batch,
                                           InstanceMeta existing) throws SQLException {
        IdempotencyWrite write = batch.idempotency().orElse(null);
        if (write == null) {
            return null;
        }
        // Rejected at WRITE time as well as at lookup, so an operator whose clock runs fast is told
        // while the clock can still be fixed rather than after the damage window has passed.
        requireIssuanceWithinSkewBudget(write.keyIssuedAt());
        ExecutionKey key = batch.key();
        IdempotencyRecord recorded = readIdempotencyRecord(connection, key.tenantId(), write.key());
        if (recorded == null) {
            if (provablyNeverRecorded(connection, key.tenantId(), write.keyIssuedAt())) {
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
            // A record can outlive its instance, because retention windows and instance lifetime are
            // independent. IdempotencyRecordExpired would be wrong here, because the store CAN answer
            // about the record; it is the instance that is gone.
            throw failure(new ExecutionStoreFailure.NotFound(key));
        }
        // A replay answers with CURRENT state, not the state as of recordedAtRevision: its purpose is to
        // let the caller proceed without re-executing, and a caller handed a stale revision derives an
        // expectation from it and loops on ConcurrencyConflict forever.
        return new StoredProcessInstance(readAggregate(connection, key, existing), existing.revision(),
                existing.graphVersionPin(), key.tenantId(), existing.updatedAt());
    }

    private static void requireFencingTokenCurrent(ExecutionKey key, ExecutionBatch batch,
                                                   InstanceMeta existing) {
        if (batch.fencingToken().isEmpty()) {
            return;
        }
        long presented = batch.fencingToken().getAsLong();
        // Inequality, not "lower than": a caller presenting an unissued higher token must not be able to
        // fence out the legitimate owner.
        if (presented != existing.fencingToken()) {
            throw failure(new ExecutionStoreFailure.FencedOut(key, presented, existing.fencingToken()));
        }
    }

    private static void requireExpectationMet(ExecutionKey key, RevisionExpectation expectation,
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

    private static ProcessInstance fold(ExecutionBatch batch, ProcessInstance current) {
        ProcessInstance folded = current;
        for (ExecutionTransition transition : batch.transitions()) {
            try {
                folded = transition.applyTo(folded);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // A caller's illegal transition is a caller bug, not stored corruption. The two are told
                // apart by WHERE the rejection came from, not by its type: reconstruction of stored rows
                // is Corrupted, folding a caller's batch is InvalidRequest.
                throw new ExecutionStoreException(
                        ExecutionStoreFailure.invalid(rejected.getMessage()), rejected);
            }
        }
        if (folded == null) {
            throw failure(ExecutionStoreFailure.invalid("batch produced no aggregate state"));
        }
        return folded;
    }

    private static GraphVersionPin pinFor(ExecutionKey key, ExecutionBatch batch, InstanceMeta existing) {
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

    /**
     * Refuses a batch carrying a facility this build does not declare.
     *
     * <p>Silently ignoring the registration would be the worse half of the port's asymmetric
     * enforcement: the caller would be told its batch committed, and the handler, approval, task, hold
     * or budget it asked for would not exist anywhere. The tables for all of them are already in the
     * schema, so implementing them later changes this method and nothing else.</p>
     */
    private static void requireOnlyDeclaredFacilities(ExecutionBatch batch) {
        refuseUnless(batch.handlersToRegister().isEmpty() && batch.handlerTransitions().isEmpty(),
                StoreCapability.DURABLE_HANDLERS);
        refuseUnless(batch.toolApprovalsToRegister().isEmpty()
                && batch.toolApprovalTransitions().isEmpty(), StoreCapability.TOOL_APPROVALS);
        refuseUnless(batch.humanTasksToRegister().isEmpty() && batch.humanTaskTransitions().isEmpty(),
                StoreCapability.HUMAN_TASKS);
        refuseUnless(batch.executionPausesToRegister().isEmpty()
                && batch.executionPauseTransitions().isEmpty(), StoreCapability.EXECUTION_PAUSES);
        refuseUnless(batch.agentBudgetOperations().isEmpty(), StoreCapability.AGENT_AUTHORITY_BUDGETS);
    }

    private static void refuseUnless(boolean absent, StoreCapability capability) {
        if (!absent) {
            throw failure(new ExecutionStoreFailure.CapabilityNotSupported(capability));
        }
    }

    // ---------------------------------------------------------------- row access

    /**
     * @param createdAt           write-once, and half of the inventory's sort key
     * @param lifecycleGeneration count of authoritative status transitions
     * @param retainedUntil       the <em>raw</em> stored column and not the answer a caller is given:
     *                            null while non-terminal. Every path that reports or acts on a deadline
     *                            resolves it through {@link #retentionDueAt} instead
     */
    private record InstanceMeta(long revision, long fencingToken, GraphVersionPin graphVersionPin,
                                ProcessInstanceStatus status,
                                ExecutionTerminationReason terminationReason, Instant updatedAt,
                                Instant createdAt, long lifecycleGeneration, ExecutionOrigin origin,
                                Instant retainedUntil) {
    }

    /** One instance the claim loop has locked, with the token its lease will be issued against. */
    private record Candidate(ExecutionKey key, long fencingToken) {
    }

    private record ScheduledAttempt(UUID traversalId, UUID invocationId, UUID attemptId, int ordinal,
                                    NodeCommand command) {
    }

    /**
     * Reads the instance's own row, optionally taking its lock for the rest of the transaction.
     *
     * <p>{@code forUpdate} is not an optimisation switch. Every caller that will write a value derived
     * from this row passes {@code true}, and the row is the serialization point for the instance's whole
     * lifecycle; a reader that only reports passes {@code false}, because taking a write lock to answer
     * a query would make a listing block a claim.</p>
     */
    private InstanceMeta readMeta(Connection connection, ExecutionKey key, boolean forUpdate)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                META_COLUMNS + (forUpdate ? " FOR UPDATE" : ""))) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new InstanceMeta(rows.getLong("revision"), rows.getLong("fencing_token"),
                        new GraphVersionPin(rows.getString("graph_version_pin")),
                        processStatusOf(key, rows.getString("status")),
                        terminationReasonOf(key, rows.getString("termination_reason")),
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
     * report a missing value as the epoch, and an epoch retention deadline is one that has already
     * passed.
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
     * <p>Statuses are persisted by name, which is what lets a member be added without a data migration,
     * and the cost of that is a one-way rollback gate: a row written by a newer binary is unreadable by
     * an older one. It has to surface as corruption rather than as a raw exception escaping the port,
     * and above all it must never be skipped — an inventory that silently dropped the rows it could not
     * parse would report a shorter, cleaner world than the one in the database, and nothing would say
     * so.</p>
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

    /**
     * Maps a stored termination reason, distinguishing an absent one from an unreadable one.
     *
     * <p>NULL is the ordinary case and a meaningful one: nothing distinguishes this termination. A
     * <em>name</em> this build does not know is the rollback case, and it takes the route an unknown
     * status name takes — {@link ExecutionStoreFailure.Corrupted}, loudly. Reading it as an absent
     * reason would report a run that was cancelled as one that failed, which is the misreading the
     * reason exists to prevent, restored by the very code meant to carry it.
     */
    private static ExecutionTerminationReason terminationReasonOf(ExecutionKey key, String name) {
        if (name == null) {
            return null;
        }
        try {
            return ExecutionTerminationReason.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "termination reason '" + name + "' is not a reason this build understands"), unknown);
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

    private ProcessInstance readAggregate(Connection connection, ExecutionKey key, InstanceMeta meta)
            throws SQLException {
        try {
            return AggregateStorage.read(connection, key, meta.status(), meta.terminationReason());
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            // Rows that no longer reconstruct into a legal aggregate must never escape into the runtime.
            throw new ExecutionStoreException(
                    new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()), corrupted);
        }
    }

    /**
     * Creates the instance's row, refusing rather than overwriting when another host got there first.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an upsert, and rather than a bare insert. An upsert
     * would let two hosts that both read no row both write one, and the second would silently replace
     * the first's creation — the lost update this adapter exists to make impossible. A bare insert would
     * be correct but would abort the transaction on the unique violation, so the truthful revision could
     * not then be read to report it.</p>
     */
    private void insertInstanceRow(Connection connection, ExecutionKey key, ProcessInstance folded,
                                   GraphVersionPin pin, long revision, Instant now, Instant createdAt,
                                   long generation, ExecutionOrigin origin, Instant retainedUntil)
            throws SQLException {
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO process_instance (tenant_id, process_instance_id, status, "
                        + "termination_reason, graph_version_pin, revision, fencing_token, "
                        + "lifecycle_generation, deployment_id, workload_id, correlation_id, "
                        + "created_at_epoch_second, created_at_nano, updated_at_epoch_second, "
                        + "updated_at_nano, retained_until_epoch_second, retained_until_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            statement.setString(3, folded.status().name());
            statement.setString(4, folded.terminationReason() == null
                    ? null : folded.terminationReason().name());
            statement.setString(5, pin.reference());
            statement.setLong(6, revision);
            statement.setLong(7, generation);
            statement.setString(8, origin.deploymentId().orElse(null));
            statement.setString(9, origin.workloadId().orElse(null));
            statement.setString(10, origin.correlationId().orElse(null));
            int index = StoredInstant.bindValue(statement, 11, createdAt);
            index = StoredInstant.bindValue(statement, index, now);
            bindNullableInstant(statement, index, retainedUntil);
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            InstanceMeta raced = readMeta(connection, key, false);
            throw failure(new ExecutionStoreFailure.AlreadyExists(key,
                    raced == null ? revision : raced.revision()));
        }
    }

    /**
     * Advances the instance's row, with the revision the decision was made on in the {@code WHERE}.
     *
     * <p>The guard is redundant while the caller holds this row's {@code FOR UPDATE} lock, and it is
     * kept precisely because that is an argument about a caller rather than about this statement. A
     * conditional update is wrong only if the condition is wrong; an unconditional one is wrong the
     * moment any future caller reaches it without the lock, and nothing would say so.</p>
     *
     * <p>{@code created_at} and {@code fencing_token} are absent from the {@code SET}. The first is
     * write-once, which is what makes the inventory's ordering stable while writes continue; the second
     * belongs to the lease mechanism, and assigning it here would let a batch undo a claim.</p>
     */
    private void updateInstanceRow(Connection connection, ExecutionKey key, ProcessInstance folded,
                                   GraphVersionPin pin, long revision, Instant now, long generation,
                                   ExecutionOrigin origin, Instant retainedUntil, long expectedRevision)
            throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE process_instance SET status = ?, termination_reason = ?, "
                        + "graph_version_pin = ?, revision = ?, lifecycle_generation = ?, "
                        + "deployment_id = ?, workload_id = ?, correlation_id = ?, "
                        + "updated_at_epoch_second = ?, updated_at_nano = ?, "
                        + "retained_until_epoch_second = ?, retained_until_nano = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND revision = ?")) {
            statement.setString(1, folded.status().name());
            // Assigned beside the status it qualifies and never apart from it: the pair is one fact, so
            // a row must never carry a new status with the previous reason still attached to it.
            statement.setString(2, folded.terminationReason() == null
                    ? null : folded.terminationReason().name());
            statement.setString(3, pin.reference());
            statement.setLong(4, revision);
            statement.setLong(5, generation);
            statement.setString(6, origin.deploymentId().orElse(null));
            statement.setString(7, origin.workloadId().orElse(null));
            statement.setString(8, origin.correlationId().orElse(null));
            int index = StoredInstant.bindValue(statement, 9, now);
            index = bindNullableInstant(statement, index, retainedUntil);
            statement.setString(index++, key.tenantId());
            StoredUuid.bind(statement, index++, key.processInstanceId());
            statement.setLong(index, expectedRevision);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            InstanceMeta raced = readMeta(connection, key, false);
            throw failure(raced == null
                    ? new ExecutionStoreFailure.NotFound(key)
                    : new ExecutionStoreFailure.ConcurrencyConflict(key,
                            RevisionExpectation.exactly(expectedRevision), raced.revision()));
        }
    }

    private static int bindNullableInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            statement.setNull(index + 1, Types.INTEGER);
            return index + 2;
        }
        return StoredInstant.bindValue(statement, index, value);
    }

    private void writeTimers(Connection connection, ExecutionKey key, ExecutionBatch batch)
            throws SQLException {
        for (UUID timerId : batch.timersToCancel()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM timer WHERE tenant_id = ? AND process_instance_id = ? AND timer_id = ?")) {
                bindItem(statement, key, timerId);
                statement.executeUpdate();
            }
        }
        // Cancellations first, then schedules: a batch that cancels and reschedules the same id must end
        // with the new timer, not with nothing.
        for (TimerSchedule timer : batch.timersToSchedule()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO timer (tenant_id, process_instance_id, timer_id, traversal_id, "
                            + "invocation_id, payload_content_type, payload_bytes, due_at_epoch_second, "
                            + "due_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, process_instance_id, timer_id) DO UPDATE SET "
                            + "traversal_id = EXCLUDED.traversal_id, "
                            + "invocation_id = EXCLUDED.invocation_id, "
                            + "payload_content_type = EXCLUDED.payload_content_type, "
                            + "payload_bytes = EXCLUDED.payload_bytes, "
                            + "due_at_epoch_second = EXCLUDED.due_at_epoch_second, "
                            + "due_at_nano = EXCLUDED.due_at_nano")) {
                bindItem(statement, key, timer.timerId());
                bindNullableUuid(statement, 4, timer.traversalId());
                bindNullableUuid(statement, 5, timer.invocationId());
                statement.setString(6, timer.payload().contentType());
                statement.setBytes(7, timer.payload().bytes());
                StoredInstant.bindValue(statement, 8, timer.dueAt());
                statement.executeUpdate();
            }
        }
    }

    private void writeIdempotencyRecord(Connection connection, ExecutionKey key, IdempotencyWrite write,
                                        long revision, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_record (tenant_id, idempotency_key, "
                        + "request_fingerprint_content_type, request_fingerprint_bytes, "
                        + "outcome_ref_content_type, outcome_ref_bytes, recorded_at_revision, "
                        + "expires_at_epoch_second, expires_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET "
                        + "outcome_ref_content_type = EXCLUDED.outcome_ref_content_type, "
                        + "outcome_ref_bytes = EXCLUDED.outcome_ref_bytes, "
                        + "recorded_at_revision = EXCLUDED.recorded_at_revision, "
                        + "expires_at_epoch_second = EXCLUDED.expires_at_epoch_second, "
                        + "expires_at_nano = EXCLUDED.expires_at_nano")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, write.key());
            statement.setString(3, write.requestFingerprint().contentType());
            statement.setBytes(4, write.requestFingerprint().bytes());
            statement.setString(5, write.outcomeRef().contentType());
            statement.setBytes(6, write.outcomeRef().bytes());
            statement.setLong(7, revision);
            StoredInstant.bindValue(statement, 8, plusClamped(now, write.retentionWindow()));
            statement.executeUpdate();
        }
    }

    private IdempotencyRecord readIdempotencyRecord(Connection connection, String tenantId, String key)
            throws SQLException {
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

    /**
     * Advances one of the three per-tenant floors, monotonically, in a single statement.
     *
     * <p>The {@code WHERE} on the conflict arm is what makes it monotone. Reading the stored floor and
     * writing a greater one is the read-then-write this adapter cannot have: two purges committing at
     * once would both read the old value and the later one could install the lower of the two new ones,
     * retracting a floor that a caller has already been shown. Expressed as a condition on the stored
     * row, a floor that would retreat simply does not update.</p>
     */
    private static void advanceWatermark(Connection connection, String table, String column,
                                         String tenantId, Instant floor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (tenant_id, " + column + "_epoch_second, " + column
                        + "_nano) VALUES (?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET "
                        + column + "_epoch_second = EXCLUDED." + column + "_epoch_second, "
                        + column + "_nano = EXCLUDED." + column + "_nano "
                        + "WHERE (" + table + "." + column + "_epoch_second, " + table + "." + column
                        + "_nano) < (EXCLUDED." + column + "_epoch_second, EXCLUDED." + column
                        + "_nano)")) {
            statement.setString(1, tenantId);
            StoredInstant.bindValue(statement, 2, floor);
            statement.executeUpdate();
        }
    }

    private static Instant watermarkOf(Connection connection, String table, String column,
                                       String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + "_epoch_second, " + column + "_nano FROM " + table
                        + " WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // An absent row IS Instant.MIN. Writing one when a tenant first appears would record a
                // forgetting that never happened, in a table whose only purpose is to record one.
                return rows.next() ? StoredInstant.read(rows, column) : Instant.MIN;
            }
        }
    }

    // ---------------------------------------------------------------- leases and work

    private LeaseHandle readLease(Connection connection, ExecutionKey key, long fencingToken)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT worker_id, claimed_at_epoch_second, claimed_at_nano, expires_at_epoch_second, "
                        + "expires_at_nano FROM lease WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new LeaseHandle(key, rows.getString("worker_id"), fencingToken,
                        StoredInstant.read(rows, "claimed_at"), StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    /**
     * Issues or extends a lease. Every caller holds the instance's {@code FOR UPDATE} lock.
     *
     * <p>The token increment is written as {@code fencing_token = fencing_token + 1} rather than as the
     * value this process computed, so the statement is correct even where the lock is not held. That is
     * belt and braces on top of the lock, and it is the cheap half of the pair: the lock is what makes
     * the surrounding <em>decisions</em> sound, and this makes the arithmetic sound on its own.</p>
     */
    private LeaseHandle issueLease(Connection connection, ExecutionKey key, long currentToken,
                                   LeaseHandle held, String workerId, Duration ttl, Instant now)
            throws SQLException {
        if (held != null && held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
            // The same worker re-claiming keeps its token; reissuing would fence out its own writes.
            var extended = new LeaseHandle(key, workerId, currentToken, held.claimedAt(), now.plus(ttl));
            upsertLease(connection, extended);
            return extended;
        }
        long nextToken;
        // Durable, and never reset on reopen: the counter is a column on process_instance, so a restart
        // reads it back rather than starting again from zero. A session is not a fencing domain, and a
        // token that repeated across a restart would let a worker resurrected from an old handle write
        // as though it still owned the instance.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE process_instance SET fencing_token = fencing_token + 1 "
                        + "WHERE tenant_id = ? AND process_instance_id = ? RETURNING fencing_token")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                nextToken = rows.getLong(1);
            }
        }
        var lease = new LeaseHandle(key, workerId, nextToken, now, now.plus(ttl));
        upsertLease(connection, lease);
        return lease;
    }

    private void upsertLease(Connection connection, LeaseHandle lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lease (tenant_id, process_instance_id, worker_id, claimed_at_epoch_second, "
                        + "claimed_at_nano, expires_at_epoch_second, expires_at_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id) DO UPDATE SET "
                        + "worker_id = EXCLUDED.worker_id, "
                        + "claimed_at_epoch_second = EXCLUDED.claimed_at_epoch_second, "
                        + "claimed_at_nano = EXCLUDED.claimed_at_nano, "
                        + "expires_at_epoch_second = EXCLUDED.expires_at_epoch_second, "
                        + "expires_at_nano = EXCLUDED.expires_at_nano")) {
            statement.setString(1, lease.key().tenantId());
            StoredUuid.bind(statement, 2, lease.key().processInstanceId());
            statement.setString(3, lease.workerId());
            int index = StoredInstant.bindValue(statement, 4, lease.claimedAt());
            StoredInstant.bindValue(statement, index, lease.expiresAt());
            statement.executeUpdate();
        }
    }

    private boolean leaseLive(Connection connection, ExecutionKey key, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at_epoch_second, expires_at_nano FROM lease "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                // Strictly before the expiry, matching the lease boundary the rest of this adapter uses:
                // at exactly expiresAt the lease is already gone.
                return rows.next() && now.isBefore(StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    /**
     * Locks the instances of {@code tenantId} that have work this worker may take, and no others.
     *
     * <p><strong>This is the query that decides whether the claim loop scales.</strong> The single-host
     * adapter enumerates every instance of the tenant and asks each one in turn, which is affordable
     * only because one process holds the whole database's write lock anyway. Here every worker in the
     * fleet runs this loop; enumerating the tenant would make them all touch every row, and they would
     * serialize on the tenant rather than on the work they are competing for.</p>
     *
     * <p>So the eligibility predicate is pushed into the database and the rows come back already locked:
     * {@code FOR UPDATE OF p SKIP LOCKED} takes the instance-row lock the fencing token is written under
     * and steps over rows another worker is already holding, so two workers polling at the same time
     * take disjoint sets instead of one waiting for the other. {@code SKIP LOCKED} may return fewer rows
     * than {@code LIMIT}; that is correct rather than tolerated, because delivery is at-least-once and a
     * poll that returns nothing is a poll, not a failure.</p>
     *
     * <p>The ordering is by identifier so the set is deterministic and so two workers acquire locks in
     * the same order — which, with {@code SKIP LOCKED}, means they cannot build a cycle between them.</p>
     */
    private List<Candidate> lockClaimable(Connection connection, String tenantId, String workerId,
                                          Instant now, int limit, boolean includeAttempts)
            throws SQLException {
        String attemptArm = "EXISTS (SELECT 1 FROM attempt a "
                + "WHERE a.tenant_id = p.tenant_id AND a.process_instance_id = p.process_instance_id "
                + "AND a.status IN ('SCHEDULED', 'RUNNING') "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = a.tenant_id "
                + "AND k.process_instance_id = a.process_instance_id AND k.work_item_id = a.attempt_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = a.tenant_id "
                + "AND c.process_instance_id = a.process_instance_id AND c.work_item_id = a.attempt_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + "))";
        String timerArm = "EXISTS (SELECT 1 FROM timer t "
                + "WHERE t.tenant_id = p.tenant_id AND t.process_instance_id = p.process_instance_id "
                + "AND " + StoredInstant.atOrBefore("t.due_at") + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = t.tenant_id "
                + "AND k.process_instance_id = t.process_instance_id AND k.work_item_id = t.timer_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = t.tenant_id "
                + "AND c.process_instance_id = t.process_instance_id AND c.work_item_id = t.timer_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + "))";

        String sql = "SELECT p.process_instance_id, p.fencing_token FROM process_instance p "
                + "WHERE p.tenant_id = ? "
                // Held by somebody else and still live: skipped, exactly as the single-host adapter
                // skips it. A lapsed lease is not an obstacle, which is what makes a dead worker's work
                // recoverable without a reaper.
                + "AND NOT EXISTS (SELECT 1 FROM lease l WHERE l.tenant_id = p.tenant_id "
                + "AND l.process_instance_id = p.process_instance_id AND l.worker_id <> ? "
                + "AND " + StoredInstant.strictlyAfter("l.expires_at") + ") AND ("
                + (includeAttempts ? attemptArm + " OR " : "") + timerArm + ") "
                + "ORDER BY p.process_instance_id LIMIT ? FOR UPDATE OF p SKIP LOCKED";

        var candidates = new ArrayList<Candidate>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, tenantId);
            statement.setString(index++, workerId);
            index = StoredInstant.bindComparison(statement, index, now);
            if (includeAttempts) {
                index = StoredInstant.bindComparison(statement, index, now);
            }
            index = StoredInstant.bindComparison(statement, index, now);
            index = StoredInstant.bindComparison(statement, index, now);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    var key = new ExecutionKey(tenantId, StoredUuid.required(rows, "process_instance",
                            "process_instance_id", tenantId));
                    candidates.add(new Candidate(key, rows.getLong("fencing_token")));
                }
            }
        }
        return candidates;
    }

    /**
     * Outstanding attempts that are neither acknowledged nor inside a live visibility window.
     *
     * <p>The visibility predicate is the negation of "still invisible", which is why it reads as
     * {@code NOT EXISTS (... AND visible_again_at > now)} rather than {@code visible_again_at <= now}:
     * an item with no claim row at all has never been delivered and must be claimable, and a plain
     * comparison against a missing row is never true.</p>
     *
     * <p>{@code RUNNING} is claimable and {@code PARKED} is not. An attempt stuck in {@code RUNNING} is
     * exactly the crash case parking exists for — dispatched, outcome never learned — and restricting
     * this to {@code SCHEDULED} would make it permanently invisible. A healthy long-running attempt is
     * not redelivered, because the claim loop skips any instance whose lease is held by another worker
     * and has not expired: a live runtime renews while its node runs, and only one that has stopped
     * renewing lets its instance become claimable again. {@code PARKED} is excluded because parking is a
     * terminal disposition for the claim loop; it waits for a human rather than being re-decided by a
     * machine on every poll.</p>
     */
    private List<ScheduledAttempt> claimableAttempts(Connection connection, ExecutionKey key, Instant now)
            throws SQLException {
        String sql = "SELECT i.traversal_id, tr.traversal_id AS joined_traversal_id, "
                + "a.invocation_id, i.invocation_id AS joined_invocation_id, "
                + "a.attempt_id, a.ordinal, i.node_command "
                + "FROM attempt a LEFT JOIN invocation i ON i.tenant_id = a.tenant_id "
                + "AND i.process_instance_id = a.process_instance_id AND i.invocation_id = a.invocation_id "
                + "LEFT JOIN traversal tr ON tr.tenant_id = i.tenant_id "
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
            StoredUuid.bind(statement, 2, key.processInstanceId());
            StoredInstant.bindComparison(statement, 3, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID invocationId = StoredUuid.required(rows, "attempt", "invocation_id", key);
                    StoredUuid.requiredMatching(rows, "invocation", "joined_invocation_id", key,
                            invocationId);
                    UUID traversalId = StoredUuid.required(rows, "invocation", "traversal_id", key);
                    StoredUuid.requiredMatching(rows, "traversal", "joined_traversal_id", key, traversalId);
                    ready.add(new ScheduledAttempt(traversalId, invocationId,
                            StoredUuid.required(rows, "attempt", "attempt_id", key), rows.getInt("ordinal"),
                            NodeCommand.parse(rows.getString("node_command"))));
                }
            }
        }
        return ready;
    }

    private List<TimerSchedule> claimableTimers(Connection connection, ExecutionKey key, Instant now)
            throws SQLException {
        String sql = "SELECT t.timer_id, t.traversal_id, t.invocation_id, t.payload_content_type, "
                + "t.payload_bytes, t.due_at_epoch_second, t.due_at_nano FROM timer t "
                + "WHERE t.tenant_id = ? AND t.process_instance_id = ? "
                + "AND " + StoredInstant.atOrBefore("t.due_at") + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = t.tenant_id "
                + "AND k.process_instance_id = t.process_instance_id AND k.work_item_id = t.timer_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = t.tenant_id "
                + "AND c.process_instance_id = t.process_instance_id AND c.work_item_id = t.timer_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                // The identifier is the tie-break because there is no rowid to fall back on, and an
                // order that varies between two identical polls would redeliver timers in a different
                // sequence for no reason a caller could see.
                + "ORDER BY t.due_at_epoch_second, t.due_at_nano, t.timer_id";
        var due = new ArrayList<TimerSchedule>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            int index = StoredInstant.bindComparison(statement, 3, now);
            StoredInstant.bindComparison(statement, index, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    due.add(new TimerSchedule(StoredUuid.required(rows, "timer", "timer_id", key),
                            StoredInstant.read(rows, "due_at"),
                            StoredUuid.optional(rows, "timer", "traversal_id"),
                            StoredUuid.optional(rows, "timer", "invocation_id"),
                            OpaquePayload.of(rows.getBytes("payload_bytes"),
                                    rows.getString("payload_content_type"))));
                }
            }
        }
        return due;
    }

    private PendingWork claimAttempt(Connection connection, ExecutionKey key, ScheduledAttempt attempt,
                                     LeaseHandle lease, Instant now, Duration leaseTtl) throws SQLException {
        int delivery = registerClaim(connection, key, attempt.attemptId(), now, leaseTtl);
        return new PendingWork.AttemptDispatch(key, attempt.attemptId(), attempt.traversalId(),
                attempt.invocationId(), attempt.attemptId(), attempt.ordinal(), lease.fencingToken(),
                lease.expiresAt(), delivery, attempt.command());
    }

    private PendingWork.TimerDue claimTimer(Connection connection, ExecutionKey key, TimerSchedule timer,
                                            LeaseHandle lease, Instant now, Duration leaseTtl)
            throws SQLException {
        int delivery = registerClaim(connection, key, timer.timerId(), now, leaseTtl);
        return new PendingWork.TimerDue(key, timer.timerId(), timer.traversalId(), timer.invocationId(),
                timer.dueAt(), timer.payload(), lease.fencingToken(), lease.expiresAt(), delivery);
    }

    /**
     * Records this delivery and returns which delivery it is, in one statement.
     *
     * <p>The increment is computed by the database from the stored row rather than by this process from
     * a value it read a moment earlier. Read-then-write here is the classic lost update: two workers
     * that both read attempt 3 both write 4, and the delivery count — which is the evidence the
     * ambiguity rule uses to decide that a {@code RUNNING} attempt was dispatched and never heard from —
     * silently understates how many times the work has actually gone out.</p>
     */
    private int registerClaim(Connection connection, ExecutionKey key, UUID workItemId, Instant now,
                              Duration leaseTtl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO work_claim (tenant_id, process_instance_id, work_item_id, delivery_attempt, "
                        + "visible_again_at_epoch_second, visible_again_at_nano) VALUES (?, ?, ?, 1, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id, work_item_id) DO UPDATE SET "
                        + "delivery_attempt = work_claim.delivery_attempt + 1, "
                        + "visible_again_at_epoch_second = EXCLUDED.visible_again_at_epoch_second, "
                        + "visible_again_at_nano = EXCLUDED.visible_again_at_nano "
                        + "RETURNING delivery_attempt")) {
            bindItem(statement, key, workItemId);
            StoredInstant.bindValue(statement, 4, now.plus(leaseTtl));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw failure(ExecutionStoreFailure.unknownWorkItem(workItemId));
                }
                return rows.getInt(1);
            }
        }
    }

    /**
     * Clears acknowledgements and claims whose work no longer exists.
     *
     * <p>A retry appends a new attempt id rather than reviving the old one, so this only removes
     * bookkeeping for work that has genuinely gone — a cancelled timer, or an attempt the fold replaced.
     * Leaving it would keep a permanently acknowledged ghost in the way of nothing, but would also let
     * the table grow without bound across a long-lived instance.</p>
     *
     * <p>The handler arm is present even though this build registers no handlers. Handler identities are
     * work-item identities too, and a terminal handler is retained rather than deleted, so its
     * acknowledgement must be retained with it; omitting the arm now would leave a defect waiting for
     * the change that starts writing those rows.</p>
     */
    private void dropAcknowledgementsForRescheduledWork(Connection connection, ExecutionKey key)
            throws SQLException {
        String liveWork = "SELECT attempt_id FROM attempt WHERE tenant_id = ? AND process_instance_id = ? "
                + "UNION ALL SELECT timer_id FROM timer WHERE tenant_id = ? AND process_instance_id = ? "
                + "UNION ALL SELECT handler_id FROM execution_handler "
                + "WHERE tenant_id = ? AND process_instance_id = ?";
        for (String table : List.of("work_acknowledgement", "work_claim")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE tenant_id = ? AND process_instance_id = ? "
                            + "AND work_item_id NOT IN (" + liveWork + ")")) {
                for (int pair = 0; pair < 4; pair++) {
                    statement.setString(pair * 2 + 1, key.tenantId());
                    StoredUuid.bind(statement, pair * 2 + 2, key.processInstanceId());
                }
                statement.executeUpdate();
            }
        }
    }

    private static void bindItem(PreparedStatement statement, ExecutionKey key, UUID workItemId)
            throws SQLException {
        statement.setString(1, key.tenantId());
        StoredUuid.bind(statement, 2, key.processInstanceId());
        StoredUuid.bind(statement, 3, workItemId);
    }

    private static void bindNullableUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        if (value == null) {
            // The type has to be named: an untyped NULL against a uuid column is ambiguous to the
            // server, which refuses it rather than guessing.
            statement.setNull(index, Types.OTHER);
        } else {
            StoredUuid.bind(statement, index, value);
        }
    }

    // ---------------------------------------------------------------- inventory helpers

    private static List<String> terminalStatusNames() {
        return Arrays.stream(ProcessInstanceStatus.values())
                .filter(ProcessInstanceStatus::terminal).map(Enum::name).toList();
    }

    /**
     * The eligibility predicate, in one place because the floor query and the delete must not be able to
     * disagree about what "expired" means.
     *
     * <p>Only terminal rows are eligible however old a non-terminal one is: age is not evidence that
     * work has finished, and pruning a stuck instance would destroy the row an operator needs in order
     * to discover that it is stuck. The second arm of the disjunction covers a terminal row that carries
     * no stored deadline, whose {@code updated_at} <em>is</em> its terminal transition instant, because
     * a terminal instance is never written again.</p>
     */
    private static String expiredInstanceIdQuery() {
        String terminal = String.join(", ", terminalStatusNames().stream()
                .map(name -> "'" + name + "'").toList());
        return "SELECT process_instance_id FROM process_instance WHERE tenant_id = ? AND status IN ("
                + terminal + ") AND ("
                + "(retained_until_epoch_second IS NOT NULL AND "
                + StoredInstant.atOrBefore("retained_until") + ") OR "
                + "(retained_until_epoch_second IS NULL AND "
                + StoredInstant.atOrBefore("updated_at") + "))";
    }

    /**
     * Binds a statement of the form {@code ... WHERE tenant_id = ? AND process_instance_id IN
     * (}{@link #expiredInstanceIdQuery()}{@code )}. The tenant appears twice because the subquery must
     * carry it too — an instance id is unique only within a tenant, so a delete whose subquery were
     * unscoped would match a colliding id belonging to somebody else.
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
     * <p>It has to be the latest. The floor's guarantee runs in the direction "everything past it is
     * still here", so it must sit at or beyond every boundary the purge actually crossed. With the
     * earliest, a run that removes two rows whose deadlines are further apart than the retention window
     * publishes a floor the later row sits <em>after</em> — and a caller following the documented rule
     * then concludes that a genuinely completed execution never existed, which is the ambiguity inverted
     * into the unsafe direction. One row is the degenerate case where earliest and latest coincide,
     * which is why the mistake survives any test that purges only one.</p>
     *
     * <p>It is deliberately not {@code now} either: advancing to now would claim a gap covering instants
     * at which rows are still present, which is safe but uselessly pessimistic.</p>
     */
    private Instant latestExpiredDeadline(Connection connection, String tenantId, Instant now,
                                          Instant lapsed) throws SQLException {
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

    private Instant retentionDueAt(Instant retainedUntil, Instant updatedAt) {
        return retainedUntil != null ? retainedUntil : plusClamped(updatedAt, config.terminalRetention());
    }

    private ProcessInventoryEntry readInventoryRow(String tenantId, ResultSet rows, Instant now,
                                                   ExecutionKey expectedKey) throws SQLException {
        UUID processInstanceId = expectedKey == null
                ? StoredUuid.required(rows, "process_instance", "process_instance_id", tenantId)
                : StoredUuid.requiredMatching(rows, "process_instance", "process_instance_id", expectedKey,
                        expectedKey.processInstanceId());
        var key = new ExecutionKey(tenantId, processInstanceId);
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
                        StoredInstant.read(rows, "updated_at")),
                terminationReasonOf(key, rows.getString("termination_reason")));
    }

    /**
     * What a reader is told about retention, resolved the same way the purge decides it.
     *
     * <p>Two paths disagreeing about the same fact is how a caller comes to trust the wrong one; routing
     * both through {@link #retentionDueAt} makes them incapable of it. The terminal test is the gate,
     * and it is what stops the fallback from inventing a deadline for a running instance: retention has
     * not started for a non-terminal row, and absent is how that is said.</p>
     */
    private Optional<Instant> retainedUntilOf(ProcessInstanceStatus status, Instant storedDeadline,
                                              Instant updatedAt) {
        return status.terminal()
                ? Optional.of(retentionDueAt(storedDeadline, updatedAt))
                : Optional.empty();
    }

    /** Binds one accumulated filter argument; an instant occupies the two slots of a comparison. */
    private static int bindInventoryArgument(PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value instanceof Instant instant) {
            return StoredInstant.bindComparison(statement, index, instant);
        }
        if (value instanceof UUID uuid) {
            StoredUuid.bind(statement, index, uuid);
            return index + 1;
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

    // ---------------------------------------------------------------- execution result helpers

    private void requireResultPayloadWithinLimit(DurableExecutionResult result) {
        ExecutionResultPayload payload = result.payload();
        if (payload.state() == ResultPayloadState.RETAINED
                && payload.bytes() > config.maxPayloadBytes()) {
            // Refused rather than silently relabelled WITHHELD. The projection decides what to keep, and
            // a store that rewrote that decision would report a payload as refused for size by an
            // adapter the caller never asked about the size of.
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.bytes(),
                    config.maxPayloadBytes()));
        }
        if (payload.state() == ResultPayloadState.EXPIRED) {
            throw failure(ExecutionStoreFailure.invalid(
                    "EXPIRED describes a record's age and is produced by a read; it cannot be stored"));
        }
    }

    private void requireInstanceExists(Connection connection, ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM process_instance WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    // Indistinguishable from another tenant's instance, which is the point: the key
                    // carries the tenant, so a cross-tenant record is refused by the same miss.
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
            }
        }
    }

    /** @return whether this call wrote the row, as opposed to finding a concurrent writer's. */
    private boolean insertExecutionResult(Connection connection, DurableExecutionResult result,
                                          Instant recordedAt) throws SQLException {
        ExecutionResultPayload payload = result.payload();
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_result (tenant_id, process_instance_id, traversal_id, "
                        + "graph_version_pin, status, termination_reason, started_at_epoch_second, "
                        + "started_at_nano, ended_at_epoch_second, ended_at_nano, "
                        + "recorded_at_epoch_second, recorded_at_nano, retained_until_epoch_second, "
                        + "retained_until_nano, payload_state, payload_redacted, payload_truncated, "
                        + "payload_bytes, payload_content_type, payload, failure_classifier, "
                        + "fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            int index = 1;
            statement.setString(index++, result.key().tenantId());
            StoredUuid.bind(statement, index++, result.key().processInstanceId());
            StoredUuid.bind(statement, index++, result.traversalId());
            statement.setString(index++, result.graphVersionPin().reference());
            statement.setString(index++, result.status().name());
            statement.setString(index++, result.terminationReason() == null ? null
                    : result.terminationReason().name());
            index = StoredInstant.bindValue(statement, index, result.startedAt());
            index = StoredInstant.bindValue(statement, index, result.endedAt());
            index = StoredInstant.bindValue(statement, index, recordedAt);
            index = StoredInstant.bindValue(statement, index, result.retainedUntil());
            statement.setString(index++, payload.state().name());
            statement.setBoolean(index++, payload.redacted());
            statement.setBoolean(index++, payload.truncated());
            statement.setInt(index++, payload.bytes());
            statement.setString(index++, payload.contentType());
            statement.setBytes(index++, payload.retained() == null ? null : payload.retained().bytes());
            statement.setString(index++, result.failureClassifier());
            statement.setString(index, result.fingerprint());
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_result_node (tenant_id, traversal_id, node_set, position, value) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (ExecutionResultNodes.Kind kind : ExecutionResultNodes.Kind.values()) {
                List<String> entries = result.nodes().entries(kind);
                for (int position = 0; position < entries.size(); position++) {
                    statement.setString(1, result.key().tenantId());
                    StoredUuid.bind(statement, 2, result.traversalId());
                    statement.setString(3, kind.name());
                    statement.setInt(4, position);
                    statement.setString(5, entries.get(position));
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
        return true;
    }

    private DurableExecutionResult readExecutionResult(Connection connection, String tenantId,
                                                       UUID traversalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM execution_result WHERE tenant_id = ? AND traversal_id = ?")) {
            statement.setString(1, tenantId);
            StoredUuid.bind(statement, 2, traversalId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                var key = new ExecutionKey(tenantId,
                        StoredUuid.required(rows, "execution_result", "process_instance_id", tenantId));
                return new DurableExecutionResult(key, traversalId,
                        new GraphVersionPin(rows.getString("graph_version_pin")),
                        processStatusOf(key, rows.getString("status")),
                        terminationReasonOf(key, rows.getString("termination_reason")),
                        StoredInstant.read(rows, "started_at"), StoredInstant.read(rows, "ended_at"),
                        StoredInstant.read(rows, "retained_until"), readResultPayload(key, rows),
                        rows.getString("failure_classifier"),
                        readResultNodes(connection, tenantId, traversalId));
            }
        }
    }

    private static ExecutionResultPayload readResultPayload(ExecutionKey key, ResultSet rows)
            throws SQLException {
        String name = rows.getString("payload_state");
        ResultPayloadState state;
        try {
            state = ResultPayloadState.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            // The route an unknown status name takes, and never a fallback to "no payload": a state
            // this build cannot read is a rollback, and reporting it as an absent payload would tell a
            // caller the run produced nothing when it produced something unreadable.
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "payload state '" + name + "' is not a state this build understands"), unknown);
        }
        return new ExecutionResultPayload(state, rows.getBoolean("payload_redacted"),
                rows.getBoolean("payload_truncated"), rows.getInt("payload_bytes"),
                rows.getString("payload_content_type"),
                state == ResultPayloadState.RETAINED
                        ? OpaquePayload.of(rows.getBytes("payload"), rows.getString("payload_content_type"))
                        : null);
    }

    private ExecutionResultNodes readResultNodes(Connection connection, String tenantId, UUID traversalId)
            throws SQLException {
        var byKind = new EnumMap<ExecutionResultNodes.Kind, List<String>>(ExecutionResultNodes.Kind.class);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT node_set, value FROM execution_result_node WHERE tenant_id = ? AND "
                        + "traversal_id = ? ORDER BY node_set, position")) {
            statement.setString(1, tenantId);
            StoredUuid.bind(statement, 2, traversalId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String setName = rows.getString("node_set");
                    ExecutionResultNodes.Kind kind;
                    try {
                        kind = ExecutionResultNodes.Kind.valueOf(setName);
                    } catch (IllegalArgumentException unknown) {
                        throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(
                                new ExecutionKey(tenantId, traversalId),
                                "node set '" + setName + "' is not a set this build understands"), unknown);
                    }
                    byKind.computeIfAbsent(kind, ignored -> new ArrayList<>())
                            .add(rows.getString("value"));
                }
            }
        }
        return new ExecutionResultNodes(byKind.getOrDefault(ExecutionResultNodes.Kind.VISITED, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.DEFAULTED, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.BYPASSED, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.HANDLED_FAILURE, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.UNTAKEN_EDGE, List.of()));
    }

    private Instant latestExpiredResultDeadline(Connection connection, String tenantId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT retained_until_epoch_second, retained_until_nano FROM execution_result "
                        + "WHERE tenant_id = ? AND " + StoredInstant.atOrBefore("retained_until")
                        + " ORDER BY retained_until_epoch_second DESC, retained_until_nano DESC LIMIT 1")) {
            statement.setString(1, tenantId);
            StoredInstant.bindComparison(statement, 2, now);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? StoredInstant.read(rows, "retained_until") : null;
            }
        }
    }

    // ---------------------------------------------------------------- journal helpers

    /**
     * Reconstructs one journal row and verifies its digest.
     *
     * <p>The check is on the <strong>read</strong> path deliberately. Verifying only at write time would
     * prove the digest was computed correctly and nothing whatever about whether the bytes survived,
     * which is the entire question a digest exists to answer.</p>
     */
    private JournalRecord readJournalRecord(String tenantId, ResultSet rows) throws SQLException {
        UUID instanceId = StoredUuid.required(rows, "event_journal", "process_instance_id", tenantId);
        var key = new ExecutionKey(tenantId, instanceId);
        var envelope = new EventEnvelope(
                rows.getInt("envelope_version"),
                StoredUuid.required(rows, "event_journal", "event_id", key),
                tenantId,
                rows.getString("event_type"),
                instanceId,
                StoredUuid.required(rows, "event_journal", "traversal_id", key),
                StoredUuid.optional(rows, "event_journal", "invocation_id"),
                StoredUuid.optional(rows, "event_journal", "attempt_id"),
                StoredUuid.optional(rows, "event_journal", "causation_id"),
                rows.getString("correlation_id"),
                rows.getString("graph_version"),
                StoredInstant.read(rows, "occurred_at"),
                OpaquePayload.of(rows.getBytes("payload_bytes"), rows.getString("payload_content_type")),
                EventDigest.of(rows.getBytes("digest")));
        if (!envelope.digestMatchesContent()) {
            throw failure(new ExecutionStoreFailure.Corrupted(key,
                    "journal offset " + rows.getLong("journal_offset") + " carries digest "
                            + envelope.digest().hex() + ", which does not match its stored content"));
        }
        return new JournalRecord(envelope, rows.getLong("stream_sequence"), rows.getLong("journal_offset"),
                rows.getLong("committed_at_revision"), StoredInstant.read(rows, "recorded_at"));
    }

    /**
     * Writes this batch's envelopes inside the batch's own transaction.
     *
     * <p>Called from {@code applyLocked}, between the aggregate write and the return, so the event rows
     * and the transition rows are inside one {@code COMMIT}. That single fact is the whole of the event
     * journal's atomicity requirement: there is no window between committing the transition and
     * recording the event, because there is no second write to perform.</p>
     */
    private void writeJournal(Connection connection, ExecutionKey key, ExecutionBatch batch, long revision,
                              Instant now) throws SQLException {
        List<EventEnvelope> events = batch.events();
        if (events.isEmpty()) {
            return;
        }
        long count = events.size();
        long offset = allocate(connection,
                "INSERT INTO journal_watermark (tenant_id, next_offset, retained_from) "
                        + "VALUES (?, 1 + ?, 1) ON CONFLICT (tenant_id) DO UPDATE SET "
                        + "next_offset = journal_watermark.next_offset + ? RETURNING next_offset",
                statement -> statement.setString(1, key.tenantId()), 1, count) - count;
        long stream = allocate(connection,
                "INSERT INTO journal_stream_sequence (tenant_id, process_instance_id, next_sequence) "
                        + "VALUES (?, ?, 1 + ?) ON CONFLICT (tenant_id, process_instance_id) DO UPDATE "
                        + "SET next_sequence = journal_stream_sequence.next_sequence + ? "
                        + "RETURNING next_sequence",
                statement -> {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                }, 2, count) - count;

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO event_journal (tenant_id, journal_offset, stream_sequence, "
                        + "process_instance_id, committed_at_revision, envelope_version, event_id, "
                        + "event_type, traversal_id, invocation_id, attempt_id, causation_id, "
                        + "correlation_id, graph_version, occurred_at_epoch_second, occurred_at_nano, "
                        + "payload_content_type, payload_bytes, digest, recorded_at_epoch_second, "
                        + "recorded_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?)")) {
            for (EventEnvelope envelope : events) {
                statement.setString(1, key.tenantId());
                statement.setLong(2, offset++);
                statement.setLong(3, stream++);
                StoredUuid.bind(statement, 4, key.processInstanceId());
                statement.setLong(5, revision);
                statement.setInt(6, envelope.envelopeVersion());
                StoredUuid.bind(statement, 7, envelope.eventId());
                statement.setString(8, envelope.eventType());
                StoredUuid.bind(statement, 9, envelope.traversalId());
                bindNullableUuid(statement, 10, envelope.invocationId());
                bindNullableUuid(statement, 11, envelope.attemptId());
                bindNullableUuid(statement, 12, envelope.causationId());
                statement.setString(13, envelope.correlationId());
                statement.setString(14, envelope.graphVersion());
                StoredInstant.bindValue(statement, 15, envelope.occurredAt());
                statement.setString(17, envelope.payload().contentType());
                statement.setBytes(18, envelope.payload().bytes());
                statement.setBytes(19, envelope.digest().value());
                StoredInstant.bindValue(statement, 20, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Reserves {@code count} consecutive numbers and returns the first one past the reservation.
     *
     * <p>The single-host adapter reads the counter and writes {@code read + count}. Two hosts doing that
     * at once would both read the same number and both write the same one, so two different events
     * would be given the same journal offset — and a consumer resuming from an offset would then skip
     * whichever of them it did not receive, silently. Computing the new value from the stored row inside
     * one statement makes that arithmetic the database's, where concurrent callers serialize on the row
     * rather than on nothing.</p>
     */
    private static long allocate(Connection connection, String sql, Binder identity, int identityWidth,
                                 long count) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            identity.bind(statement);
            statement.setLong(identityWidth + 1, count);
            statement.setLong(identityWidth + 2, count);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("the counter statement returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /** Binds the identity columns of an allocator statement. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static long readWatermarkColumn(Connection connection, String tenantId, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM journal_watermark WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // Offsets start at one, so an absent watermark and an empty journal agree.
                return rows.next() ? rows.getLong(1) : 1L;
            }
        }
    }

    /**
     * Advances the retained floor, and only the retained floor.
     *
     * <p>The single-host adapter reads both counters and rewrites the pair. Here that would let a
     * compaction clobber a {@code next_offset} a concurrent {@code apply} had just advanced, reissuing
     * offsets that a destination cursor has already passed — the one thing the journal promises never to
     * do. Naming a single column in the {@code SET} makes the two counters independent, and the
     * comparison makes the floor monotone.</p>
     */
    private static void writeRetainedFrom(Connection connection, String tenantId, long retainedFrom)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO journal_watermark (tenant_id, next_offset, retained_from) VALUES (?, 1, ?) "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET retained_from = EXCLUDED.retained_from "
                        + "WHERE journal_watermark.retained_from < EXCLUDED.retained_from")) {
            statement.setString(1, tenantId);
            statement.setLong(2, retainedFrom);
            statement.executeUpdate();
        }
    }

    private static long readCursor(Connection connection, String tenantId, String destination)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delivered_through FROM outbox_cursor WHERE tenant_id = ? AND destination = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, destination);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private static long minimumCursor(Connection connection, String tenantId) throws SQLException {
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

    private static long countOf(Connection connection, String sql, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /**
     * Every envelope must name this batch's own tenant and instance, decided before any stored state is
     * read.
     *
     * <p>The tenant half is a security guard, not a consistency one. An envelope naming another tenant,
     * accepted into this tenant's journal, is delivered to <em>this</em> journal's subscribers — a
     * cross-tenant disclosure produced by a caller bug.</p>
     */
    private void requireEnvelopesMatchBatch(ExecutionBatch batch) {
        for (EventEnvelope envelope : batch.events()) {
            requireWithinPayloadLimit(envelope.payload());
            if (!envelope.tenantId().equals(batch.key().tenantId())) {
                throw failure(ExecutionStoreFailure.invalid("event " + envelope.eventId() + " names tenant "
                        + envelope.tenantId() + " but the batch writes to " + batch.key().tenantId()));
            }
            if (!envelope.processInstanceId().equals(batch.key().processInstanceId())) {
                throw failure(ExecutionStoreFailure.invalid("event " + envelope.eventId()
                        + " names instance " + envelope.processInstanceId()
                        + " but the batch writes to " + batch.key().processInstanceId()));
            }
        }
    }

    // ---------------------------------------------------------------- request guards

    /**
     * Fail-closed classification of an absent key. Comparing {@code keyIssuedAt - maxClockSkew} rather
     * than {@code keyIssuedAt} means every case ambiguous within the declared budget resolves to expired
     * rather than to "safe to apply".
     */
    private boolean provablyNeverRecorded(Connection connection, String tenantId, Instant keyIssuedAt)
            throws SQLException {
        return !minusClamped(keyIssuedAt, config.maxClockSkew())
                .isBefore(watermarkOf(connection, "idempotency_watermark", "forgotten_before", tenantId));
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
     * A {@code NotPresent} expectation and a fencing token contradict each other within a single
     * request.
     *
     * <p>A token is issued only by a successful claim, and a claim requires the instance to exist, so a
     * caller holding a genuine token holds proof of the very existence {@code NotPresent} denies. The
     * contradiction holds for every possible stored state, which is what makes it
     * {@link ExecutionStoreFailure.InvalidRequest} rather than a state-dependent rejection, and why it
     * is decided before any row is read.</p>
     */
    private static void requireNoFencingTokenUnderNotPresent(ExecutionBatch batch) {
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
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.size(),
                    config.maxPayloadBytes()));
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

    private static void requireDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("destination cannot be blank"));
        }
    }

    private static ExecutionStoreException failure(ExecutionStoreFailure failure) {
        return new ExecutionStoreException(failure);
    }

    // ---------------------------------------------------------------- plumbing

    private <T> T write(ExecutionKey key, Transactions.Work<T> work) {
        try {
            return transactions.inTransaction(work);
        } catch (OutcomeUnknownException unknown) {
            // The state the single-host adapter records as unreachable, and that is ordinary here: the
            // network can drop between the client sending COMMIT and the acknowledgement arriving, and
            // in that window the transaction may have been applied. Retrying is not available -- a retry
            // of a committed transaction is a duplicate -- so the honest answer is to say the outcome is
            // unknown and let the caller resolve it against the store.
            throw key == null
                    ? new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                            "the outcome of a tenant-scoped write could not be confirmed: "
                                    + unknown.getMessage()), unknown)
                    : new ExecutionStoreException(new ExecutionStoreFailure.OutcomeUnknown(key,
                            unknown.getMessage()), unknown);
        } catch (SQLException failed) {
            throw mapped(failed, key);
        }
    }

    private <T> T read(ExecutionKey key, Transactions.Work<T> work) {
        try {
            return transactions.readOnly(work);
        } catch (SQLException failed) {
            throw mapped(failed, key);
        }
    }

    /**
     * Classifies a database failure the adapter did not anticipate at its own call site.
     *
     * <p>Every arm here is reached only after a rollback, so "nothing was applied" is an observation
     * rather than an assumption — which is what separates every one of these from
     * {@link ExecutionStoreFailure.OutcomeUnknown}, raised only by {@link Transactions} and only for a
     * commit that neither succeeded nor demonstrably failed.</p>
     *
     * <p>Two arms are worth stating rather than reading off the table. A unique violation that reaches
     * here is an identity collision the call site did not anticipate; it is
     * {@link ExecutionStoreFailure.InvalidRequest} because running it again collides again, and
     * reporting it as unavailability would invite a caller to retry forever. And the rest of class 42 —
     * syntax and access-rule violations other than insufficient privilege — is a fault in this adapter
     * or a schema that does not match this binary, so it takes the same deterministic-reject route
     * rather than being laundered into a transient condition. There is deliberately no arm that turns
     * an unrecognised code into something retryable without saying so.</p>
     */
    private ExecutionStoreException mapped(SQLException failed, ExecutionKey key) {
        if (SqlStates.isNotAuthorized(failed)) {
            return new ExecutionStoreException(
                    new ExecutionStoreFailure.NotAuthorized(String.valueOf(failed.getMessage())), failed);
        }
        if (SqlStates.isCorrupted(failed)) {
            return key == null
                    ? new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                            "the database reports damage to its own storage: " + failed.getMessage()), failed)
                    : new ExecutionStoreException(
                            new ExecutionStoreFailure.Corrupted(key, failed.getMessage()), failed);
        }
        if (SqlStates.isTimedOut(failed)) {
            return new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                    "the statement exceeded its lock or statement timeout: " + failed.getMessage()), failed);
        }
        if (SqlStates.isUnavailable(failed)) {
            return new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable(String.valueOf(failed.getMessage())), failed);
        }
        if (SqlStates.isUniqueViolation(failed) || SqlStates.isForeignKeyViolation(failed)) {
            return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "the write collided with stored identity: " + failed.getMessage()), failed);
        }
        if (isProgrammingFault(failed)) {
            return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "the adapter issued a statement this database rejected, which is a defect in the "
                            + "adapter or a schema that does not match this build: "
                            + failed.getMessage()), failed);
        }
        return new ExecutionStoreException(
                new ExecutionStoreFailure.Unavailable(String.valueOf(failed.getMessage())), failed);
    }

    private static boolean isProgrammingFault(SQLException failed) {
        for (SQLException current = failed; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("42")) {
                return true;
            }
        }
        return false;
    }

    private <T> CompletionStage<T> async(java.util.function.Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable("this execution store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }
}
