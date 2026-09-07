package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentIdSource;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.deployment.registry.GraphVersion;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The durable shared {@link DeploymentRegistry}, backed by the same PostgreSQL database as the
 * execution, definition and manifest stores.
 *
 * <h2>Why this is not a port of the single-host adapter</h2>
 * <p>{@code SqliteDeploymentRegistry} takes the whole database's write lock with
 * {@code BEGIN IMMEDIATE}, so it can read an aggregate and then write a decision derived from that
 * read with nothing able to intervene. Every one of those sequences is a <strong>lost update</strong>
 * here, because the competing writer is a different JVM on a different host and no lock this process
 * could take would be visible to it. Reproducing them would compile, pass the single-threaded
 * conformance suite unchanged, and lose updates in production under exactly the contention this
 * adapter exists to survive.</p>
 *
 * <p>Every such sequence is therefore one of two shapes and never a third:</p>
 * <ul>
 *   <li><strong>The aggregate's own row, taken {@code FOR UPDATE}.</strong> Eight of the nine
 *   mutations address an existing deployment, and each of them begins by locking
 *   {@code deployment (tenant_id, deployment_id)} inside the transaction that will write. That row is
 *   the serialization point for the whole aggregate: the revision, the fence, the generation, the
 *   lifecycle level, the lease and the command ledger all move under it. It is what makes the ledger
 *   replay, the revision and generation expectations, the fencing check, the version counter and the
 *   lease decision sound, because each of them is read and written inside the same held lock.</li>
 *   <li><strong>A conditional write whose {@code WHERE} carries the value the decision was made
 *   on.</strong> {@link #saveAggregate} pins the revision it read, and a partial unique index decides
 *   the winner of two concurrent {@code create} calls carrying one idempotency key — the one
 *   read-then-write with no row to lock, because the row does not exist yet.</li>
 * </ul>
 *
 * <p>There is no process-local lock anywhere in this class, and there could not be one that helped.
 * There is also no ordering anywhere that comes from a row identity the database assigns: PostgreSQL
 * has no {@code rowid}, and a listing whose order the planner was free to choose would page
 * inconsistently under concurrent inserts.</p>
 *
 * <h2>Why the lease is read in a second statement rather than joined into the lock</h2>
 * <p>The locked path reads {@code deployment} and then {@code deployment_lease} as two statements,
 * although {@link #get} and {@link #list} read them as one {@code LEFT JOIN}. That asymmetry is
 * deliberate. Under {@code READ COMMITTED} a statement that waits on {@code FOR UPDATE} re-fetches the
 * locked row after the holder commits, and the <em>next</em> statement then takes a fresh snapshot that
 * includes everything the holder wrote — so a separate lease read is guaranteed to observe the lease
 * the previous transaction left. Folding the lease into the locking statement would make its value
 * depend on how the server re-evaluates the outer join during that re-fetch, which is a subtlety no
 * reader of this class should have to reason about and which no test here could reliably provoke.
 * {@link #get} and {@link #list} take no lock at all, so their single statement is one snapshot of
 * both tables by construction and needs no such argument.</p>
 *
 * <h2>Normalized rows, not a serialized blob</h2>
 * <p>Every table {@link PostgresSchema}'s migration 2 adds is columns, for the reason the schema states
 * for the execution aggregate: a blob column makes the stored format an encoding of a Java type, so
 * the aggregate cannot change shape without a data migration, and nothing on disk is queryable by an
 * operator. {@link #get} and {@link #version} reconstruct their return values through
 * {@link DeploymentRegistry.Record}'s and {@link GraphVersion}'s own canonical constructors, which is
 * where this port's validation lives; a row that cannot pass it is a defect in this adapter, not a
 * state the domain accepts.</p>
 *
 * <h2>The registry is its own clock authority</h2>
 * <p>Every instant this class records, and every expiry it evaluates, comes from the injected
 * {@link Clock}, read once per attempt and bound as a parameter. No SQL here contains {@code now()},
 * {@code CURRENT_TIMESTAMP} or any other database-side clock. A predicate reading the server's clock
 * would ignore the injected one entirely, so every lease-expiry assertion would either never fire or
 * fire for the wrong reason — and with several hosts there is a second reason: the database's clock
 * and the deployment's clocks are different clocks, and only one of them is the one the caller reasons
 * about.</p>
 *
 * <h2>Why {@code maxClockSkew} is not zero here</h2>
 * <p>{@code InMemoryDeploymentRegistry} publishes {@link Limits#maxClockSkew()} as
 * {@link Duration#ZERO} because its clock <em>is</em> the caller's clock: one process, one
 * {@link Clock} instance, no skew to allow. That equivalence is at its least true here. A lease holder
 * renewing against this registry is routinely a different process on a different machine, which is the
 * whole reason a durable registry exists, so publishing zero would tell every such caller it may trust
 * its own clock to the instant against an authority it demonstrably does not share one with. Five
 * seconds covers ordinary NTP-disciplined drift between hosts without eating a meaningful fraction of
 * the five-minute maximum lease published beside it, and {@link Limits}'s own canonical constructor
 * enforces that the allowance stays strictly inside that bound.</p>
 *
 * <h2>Bounded retention on the command ledger</h2>
 * <p>{@code deployment_command} exists so a replayed command returns the exact outcome it produced the
 * first time rather than the aggregate's current, possibly divergent, state — which is also why each
 * row stores the whole {@link DeploymentRegistry.Record} snapshot it produced, as columns, rather than
 * a reference into the live {@code deployment} row. Left unbounded that ledger only ever grows: every
 * accepted mutation of every deployment a tenant has ever created adds a row nothing removes, and the
 * failure is not dramatic but terminal — a table an operator eventually cannot back up, index or
 * migrate inside a maintenance window. {@link #purgeExpiredCommandRecords} is the lever for that; it is
 * deliberately not invoked automatically anywhere in this class, on the model of
 * {@code ExecutionStore#purgeExpiredIdempotencyRecords} being scheduler- or operator-driven rather
 * than a background reaper an application cannot see or pace.</p>
 *
 * <h2>A forced compromise in the frozen port: {@code FailureReason} has no store-fault member</h2>
 * <p>{@link DeploymentRegistry.FailureReason} is sealed to {@code NotFound}, {@code Conflict},
 * {@code Fenced}, {@code LeaseLost} and {@code InvalidRequest}, and this class may not add a sixth.
 * Every business rejection below lands on one of those five by genuine meaning. A residue does not: an
 * unreachable database, a credential that cannot do what the adapter needs, a statement that waited out
 * its lock timeout, and — reachable here in a way it is not on one host — a {@code COMMIT} whose
 * outcome is unknown because the network dropped between the client sending it and the server's
 * acknowledgement arriving. {@link #mapSqlFailure} routes that residue to {@code InvalidRequest}
 * because it is the only member carrying a message a caller can log, not because any of it is an
 * invalid request; a caller reading {@code InvalidRequest} as "the caller's mistake" will misclassify
 * an adapter-side fault, and in the outcome-unknown case will also miss that it must reconcile against
 * the store rather than retry. This is a genuine gap in what the frozen port lets an adapter say, most
 * plausibly closed by future store-fault and outcome-unknown members the way
 * {@code ExecutionStoreFailure} already has them; it is flagged here rather than resolved by inventing
 * a meaning {@code FailureReason} was not given.</p>
 *
 * <h2>The connection is never named here</h2>
 * <p>This adapter is handed a {@link DataSource} and never sees a URL, a credential or a pool setting,
 * exactly as the other stores in this package are. It follows that {@link #close()} does not close the
 * {@code DataSource}: this registry did not open it, and it is very likely the same one the execution
 * store of the same deployment is using.</p>
 */
public final class PostgresDeploymentRegistry implements DeploymentRegistry {

    /**
     * The shared cursor wire form's version marker.
     *
     * <p>Byte-for-byte the scheme {@code InMemoryDeploymentRegistry} and the single-host adapter use:
     * {@code "rr1\0tenant\0lastId"}, base64url, unpadded. A cursor a caller received from one adapter
     * must be usable against another backing the same tenant, so the wire form is not this adapter's
     * to reinvent.
     */
    private static final String CURSOR_VERSION = "rr1";

    private static final Duration DEFAULT_COMMAND_RETENTION = Duration.ofDays(7);

    /**
     * The bounds this registry publishes, chosen to equal the single-host adapter's so that a
     * deployment moving between the two does not discover a different limit.
     *
     * <p>That equality is a <strong>convention, not an invariant</strong>, and nothing in this build
     * holds the two in step. The single-host adapter is a module this one does not depend on and must
     * not, so no assertion here can read its values, and the shared conformance suite does not pin them
     * either — it checks each adapter's own limits for internal consistency, which these satisfy at any
     * number. An editor changing one side is therefore not stopped by anything, and the claim is
     * written as what it is so that a reader does not take a stronger guarantee from it than exists.</p>
     *
     * <p>The three numbers also coincide with {@code maxInventoryPageSize}, {@code maxLeaseTtl} and
     * {@code maxClockSkew} in {@link PostgresStoreConfig#defaults()}, and that coincidence is
     * deliberately not turned into a derivation: a deployment lease is not an execution lease and a
     * deployment listing page is not an inventory page, so reading them from the store config would
     * make an adopter widening one silently move the other.</p>
     */
    private static final Limits LIMITS = new Limits(100, Duration.ofMinutes(5), Duration.ofSeconds(5));

    /**
     * The ledger slot a command key is recorded under.
     *
     * <p>Stored as its own column rather than folded into the key, because the same key used for two
     * different actions on one deployment is two different decisions and must not replay across them.
     * The names are persisted, so they are part of the stored format and may not be renamed.
     */
    private enum Action { CREATE, APPEND, COMMAND, OBSERVE, FAIL, TOMBSTONE, ACQUIRE, RENEW, RELEASE }

    /**
     * The aggregate and its lease in one read.
     *
     * <p>Used only by the unlocked readers; see the class documentation for why the locked path reads
     * the two tables in separate statements instead.
     */
    private static final String AGGREGATE_WITH_LEASE =
            "SELECT d.*, l.owner AS lease_owner, l.fence AS lease_fence, "
                    + "l.acquired_at_epoch_second AS lease_acquired_at_epoch_second, "
                    + "l.acquired_at_nano AS lease_acquired_at_nano, "
                    + "l.expires_at_epoch_second AS lease_expires_at_epoch_second, "
                    + "l.expires_at_nano AS lease_expires_at_nano "
                    + "FROM deployment d LEFT JOIN deployment_lease l "
                    + "ON l.tenant_id = d.tenant_id AND l.deployment_id = d.deployment_id ";

    private static final String LEDGER_COLUMNS =
            "SELECT digest, recorded_latest_version, recorded_generation, recorded_revision, "
                    + "recorded_desired_kind, recorded_desired_version, recorded_update_strategy, "
                    + "recorded_observed_kind, recorded_observed_version, recorded_observed_generation, "
                    + "recorded_observed_at_epoch_second, recorded_observed_at_nano, recorded_lease_owner, "
                    + "recorded_lease_fence, recorded_lease_acquired_at_epoch_second, "
                    + "recorded_lease_acquired_at_nano, recorded_lease_expires_at_epoch_second, "
                    + "recorded_lease_expires_at_nano, recorded_failure_code, recorded_failure_message, "
                    + "recorded_failure_at_epoch_second, recorded_failure_at_nano, recorded_tombstone_reason, "
                    + "recorded_tombstone_at_epoch_second, recorded_tombstone_at_nano, "
                    + "recorded_created_at_epoch_second, recorded_created_at_nano, "
                    + "recorded_updated_at_epoch_second, recorded_updated_at_nano";

    private final Clock clock;
    private final DeploymentIdSource ids;
    private final Duration commandRetention;
    private final Transactions transactions;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Opens the registry over {@code dataSource}, minting deployment ids as random UUIDs and retaining
     * ledger rows for seven days.
     *
     * @param dataSource the database this registry shares with the deployment's other stores.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     */
    public PostgresDeploymentRegistry(DataSource dataSource, Clock clock) {
        this(dataSource, clock, tenant -> DeploymentId.of(UUID.randomUUID().toString()));
    }

    /**
     * Opens the registry with an explicit id source.
     *
     * @param dataSource the database this registry shares with the deployment's other stores.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     */
    public PostgresDeploymentRegistry(DataSource dataSource, Clock clock, DeploymentIdSource ids) {
        this(dataSource, clock, ids, DEFAULT_COMMAND_RETENTION);
    }

    /**
     * Opens the registry with an explicit id source and command-ledger retention.
     *
     * <p>There is no database file to name and no store identity to pass, which is the whole
     * difference from the single-host adapter's constructors: this registry addresses whatever
     * database the {@link DataSource} resolves to, and the schema is what guarantees it is the same
     * one the execution store of that deployment is using.
     *
     * @param dataSource the database this registry shares with the deployment's other stores.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     * @param commandRetention how long a {@code deployment_command} row survives past its recording
     *                         before {@link #purgeExpiredCommandRecords} may remove it; must be
     *                         positive.
     */
    public PostgresDeploymentRegistry(DataSource dataSource, Clock clock, DeploymentIdSource ids,
                                      Duration commandRetention) {
        this(dataSource, clock, ids, commandRetention, PostgresStoreConfig.defaults());
    }

    /**
     * Opens the registry with explicit contention settings as well.
     *
     * <p>Every shorter constructor applies {@link PostgresStoreConfig#defaults()}, and this is the one
     * form that lets a deployment change them. Only three of the record's fields reach this class, and
     * they are the three {@link Transactions} reads: {@code lockTimeout}, {@code statementTimeout} and
     * {@code serializationRetries}. They matter here for the same reason they matter to the execution
     * store — a registry contended by several hosts waits on {@code deployment}'s row lock on every
     * mutation — and a deployment that had tuned them for its execution store while this registry
     * stayed at the defaults would be running the same database under two different contention
     * policies without anything saying so.</p>
     *
     * <p>The record's remaining fields are the execution store's published bounds and are deliberately
     * <em>not</em> consulted here. In particular {@link #limits()} is not derived from
     * {@code maxLeaseTtl}, {@code maxClockSkew} or {@code maxInventoryPageSize}: a deployment lease is
     * not an execution lease and a deployment listing page is not an inventory page, so wiring them
     * together would let an adopter widening an inventory page silently change what this registry
     * publishes to its callers. There is no further overload between this one and the four-argument
     * form on purpose — one constructor per subset of five parameters is how a constructor set stops
     * being readable, and this one is the complete form.</p>
     *
     * @param dataSource the database this registry shares with the deployment's other stores.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     * @param commandRetention how long a {@code deployment_command} row survives past its recording
     *                         before {@link #purgeExpiredCommandRecords} may remove it; must be
     *                         positive.
     * @param config the lock timeout, statement timeout and serialization-retry budget this registry's
     *               transactions run under.
     */
    public PostgresDeploymentRegistry(DataSource dataSource, Clock clock, DeploymentIdSource ids,
                                      Duration commandRetention, PostgresStoreConfig config) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(commandRetention, "commandRetention");
        Objects.requireNonNull(config, "config");
        if (commandRetention.isNegative() || commandRetention.isZero()) {
            throw new IllegalArgumentException("commandRetention must be positive");
        }
        this.commandRetention = commandRetention;
        this.transactions = new Transactions(dataSource, config, CommitBoundary.NONE);
        // Named and daemon so a thread dump says which registry is blocked and a forgotten close cannot
        // hold the JVM open. Unbounded because the DataSource is the real bound: a task that cannot get
        // a connection blocks there, which is where the deployment configured the limit.
        var sequence = new AtomicLong();
        this.worker = Executors.newCachedThreadPool(runnable -> {
            var thread = new Thread(runnable, "ravenroot-postgres-deployment-registry-"
                    + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            worker.shutdownNow();
            throw mapSqlFailure(failed);
        } catch (RuntimeException failed) {
            worker.shutdownNow();
            throw failed;
        }
    }

    @Override
    public Limits limits() {
        return LIMITS;
    }

    // ---------------------------------------------------------------- mutations

    @Override
    public CompletionStage<Record> create(GraphVersion.Content content, CreateCommand command) {
        return async(() -> {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(command, "command");
            try {
                return transactions.inTransaction(connection -> {
                    LedgerEntry prior = loadCreateLedgerEntry(connection, command.tenantId(),
                            command.key());
                    if (prior != null) {
                        return replay(prior, command.digest());
                    }
                    return insertNewDeployment(connection, content, command);
                });
            } catch (OutcomeUnknownException unknown) {
                throw outcomeUnknown(unknown);
            } catch (SQLException failed) {
                if (SqlStates.isUniqueViolation(failed)) {
                    // The lookup above found nothing and the insert then collided, so between the two a
                    // competing transaction committed. The whole attempt rolled back -- deployment row,
                    // version row and ledger row together -- which is exactly right: the identity this
                    // attempt minted must not survive a create it lost. What the collision means is
                    // read from the winner rather than guessed from a constraint name, because the two
                    // possible constraints give different answers and the driver's names are not part
                    // of any contract this adapter can hold the database to.
                    return resolveCreateCollision(command);
                }
                throw mapSqlFailure(failed);
            }
        });
    }

    /**
     * Answers a {@code create} that lost a unique-index race, from the row that won it.
     *
     * <p>Two constraints can reject the insert and they are different situations. A collision on
     * {@code idx_deployment_command_create_replay} means another host created this deployment under
     * this very idempotency key, and the honest answer is that key's recorded outcome — the same answer
     * a sequential replay would have produced. A collision on {@code deployment}'s primary key means
     * the id source minted an identity this tenant already uses, which is not a replay of anything and
     * is reported as a conflict, exactly as the single-host adapter's explicit existence check
     * reports it.</p>
     */
    private Record resolveCreateCollision(CreateCommand command) {
        LedgerEntry winner = read(connection ->
                loadCreateLedgerEntry(connection, command.tenantId(), command.key()));
        if (winner == null) {
            throw failure(new FailureReason.Conflict());
        }
        return replay(winner, command.digest());
    }

    private Record insertNewDeployment(Connection connection, GraphVersion.Content content,
                                       CreateCommand command) throws SQLException {
        Instant now = now();
        if (content.createdAt().isAfter(now)) {
            throw invalid("createdAt is in the future");
        }
        DeploymentId id = Objects.requireNonNull(ids.mint(command.tenantId()), "minted deploymentId");
        Desired desired = new Desired(DesiredKind.STOPPED, null, null, 0);
        Observation observed = new Observation(ObservedKind.COLD, null, 0, now);
        Record result = new Record(command.tenantId(), id, 1, 0, 1, desired, observed, null, null, null,
                now, now);
        insertDeploymentRow(connection, command.tenantId(), id.value(), now);
        insertVersionRow(connection, command.tenantId(), id.value(), 1, content);
        insertLedgerEntry(connection, command.tenantId(), id.value(), Action.CREATE, command.key(),
                command.digest(), result, now, now.plus(commandRetention));
        return result;
    }

    @Override
    public CompletionStage<Record> append(long version, GraphVersion.Content content, Command command) {
        return async(() -> {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.APPEND,
                        command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                // The counter is read from the row this transaction holds locked, so the successor it
                // derives cannot be derived twice. The deployment_version primary key is the second
                // half of the same guarantee and is not redundant with it: it is what would refuse the
                // write if this method were ever reached without the lock.
                if (version != current.latestVersion() + 1) {
                    throw failure(new FailureReason.Conflict());
                }
                Instant now = now();
                if (content.createdAt().isBefore(current.createdAt()) || content.createdAt().isAfter(now)) {
                    throw invalid("incoherent version timestamp");
                }
                insertVersionRow(connection, tenant, deploymentId, version, content);
                Record next = new Record(current.tenantId(), current.deploymentId(), version,
                        current.generation(), current.revision() + 1, current.desired(),
                        current.observed(), current.lease(), current.failure(), current.tombstone(),
                        current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.APPEND, command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> command(Desired desired, Command command) {
        return async(() -> {
            Objects.requireNonNull(desired, "desired");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.COMMAND,
                        command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                if (desired.kind() == DesiredKind.RUNNING
                        && !versionExists(connection, tenant, deploymentId, desired.desiredVersion())) {
                    throw invalid("unknown desired version");
                }
                Instant now = now();
                long newGeneration = current.generation() + 1;
                Desired stamped = new Desired(desired.kind(), desired.desiredVersion(),
                        desired.updateStrategy(), newGeneration);
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), newGeneration, current.revision() + 1, stamped,
                        current.observed(), current.lease(), null, current.tombstone(),
                        current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.COMMAND, command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> observe(Observation observation, Lease lease, Command command) {
        return async(() -> {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                Instant now = now();
                ownerGuard(aggregate, lease, now);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.OBSERVE,
                        command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                evidenceTime(current, observation.observedAt(), now);
                if (observation.observedGeneration() > current.generation()) {
                    throw invalid("observation generation is ahead");
                }
                if (observation.activeVersion() != null
                        && !versionExists(connection, tenant, deploymentId, observation.activeVersion())) {
                    throw invalid("unknown active version");
                }
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), current.generation(), current.revision() + 1,
                        current.desired(), observation, current.lease(), current.failure(),
                        current.tombstone(), current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.OBSERVE, command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> fail(Failure reported, Lease lease, Command command) {
        return async(() -> {
            Objects.requireNonNull(reported, "reported");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                Instant now = now();
                ownerGuard(aggregate, lease, now);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.FAIL,
                        command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                evidenceTime(current, reported.at(), now);
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), current.generation(), current.revision() + 1,
                        current.desired(), current.observed(), current.lease(), reported,
                        current.tombstone(), current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.FAIL, command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> tombstone(Tombstone tombstone, Command command) {
        return async(() -> {
            Objects.requireNonNull(tombstone, "tombstone");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                // Not requireMutableAggregate: removal is the one mutation an already-removed
                // deployment must be able to answer, because its own replay has to remain readable.
                Aggregate aggregate = requireAggregate(connection, tenant, deploymentId);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.TOMBSTONE,
                        command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                Record current = aggregate.record();
                // Read under this transaction's lock on the aggregate row, so two hosts removing at
                // once cannot both find no tombstone and both write one; the loser is told the
                // deployment is already gone rather than silently overwriting the winner's reason.
                if (current.tombstone() != null) {
                    throw failure(new FailureReason.Conflict());
                }
                expect(aggregate, command);
                Instant now = now();
                evidenceTime(current, tombstone.at(), now);
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), current.generation(), current.revision() + 1,
                        current.desired(), current.observed(), null, current.failure(), tombstone,
                        current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.TOMBSTONE, command.key(), command.digest(), now);
            });
        });
    }

    // ---------------------------------------------------------------- leases

    @Override
    public CompletionStage<Record> acquire(String owner, Duration ttl, Command command) {
        return async(() -> {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                // FOR UPDATE, because the next write is a fencing token derived from the one read
                // here. Two hosts acquiring at once without it both read fence N and both write N + 1,
                // and the fence -- whose entire purpose is that two owners cannot exist -- would hand
                // the same number to both of them.
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                ttl(ttl);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.ACQUIRE,
                        command.key());
                Instant now = now();
                if (prior != null) {
                    if (!prior.digest().equals(command.digest())) {
                        throw failure(new FailureReason.Conflict());
                    }
                    // A replayed acquire is answered from the ledger only while the lease it recorded
                    // is still this caller's and still live. Otherwise the caller would be told it
                    // holds a lease that a takeover has since revoked.
                    Lease recordedLease = prior.record().lease();
                    if (recordedLease == null || !isCurrentLive(aggregate, recordedLease, now)) {
                        throw failure(new FailureReason.LeaseLost());
                    }
                    return prior.record();
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                if (current.lease() != null && current.lease().expiresAt().isAfter(now)) {
                    throw failure(new FailureReason.Conflict());
                }
                long newFence = aggregate.fence() + 1;
                Lease lease = new Lease(tenant, current.deploymentId(), owner, newFence, now,
                        now.plus(ttl));
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), current.generation(), current.revision() + 1,
                        current.desired(), current.observed(), lease, current.failure(),
                        current.tombstone(), current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, newFence), Action.ACQUIRE,
                        command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> renew(Lease lease, Duration ttl, Command command) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                // FOR UPDATE even though no fence is written: the decision "this lease is still mine"
                // is read from the aggregate's fence, and an acquire committing between that read and
                // the lease write would let this call extend a lease it no longer owns.
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                Instant now = now();
                ownerGuard(aggregate, lease, now);
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.RENEW,
                        command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                ttl(ttl);
                Record current = aggregate.record();
                // Extends the window without rotating the token: rotating it would fence out the very
                // holder being renewed.
                Lease renewed = new Lease(tenant, current.deploymentId(), lease.owner(), lease.fence(),
                        lease.acquiredAt(), now.plus(ttl));
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), current.generation(), current.revision() + 1,
                        current.desired(), current.observed(), renewed, current.failure(),
                        current.tombstone(), current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.RENEW, command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> release(Lease lease, Command command) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return write(connection -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(connection, tenant, deploymentId);
                Instant now = now();
                LedgerEntry prior = loadLedgerEntry(connection, tenant, deploymentId, Action.RELEASE,
                        command.key());
                if (prior != null) {
                    if (!prior.digest().equals(command.digest())) {
                        throw failure(new FailureReason.Conflict());
                    }
                    // A replayed release is idempotent against its own outcome -- the lease it removed
                    // is gone and the aggregate says so -- but a caller whose authority has since been
                    // superseded is still told that first, because a fenced caller learning only that
                    // its release "already happened" would keep believing it is the owner.
                    if (aggregate.fence() != lease.fence()) {
                        throw failure(new FailureReason.Fenced());
                    }
                    if (aggregate.record().lease() != null) {
                        ownerGuard(aggregate, lease, now);
                    }
                    return prior.record();
                }
                ownerGuard(aggregate, lease, now);
                expect(aggregate, command);
                Record current = aggregate.record();
                Record next = new Record(current.tenantId(), current.deploymentId(),
                        current.latestVersion(), current.generation(), current.revision() + 1,
                        current.desired(), current.observed(), null, current.failure(),
                        current.tombstone(), current.createdAt(), now);
                return persist(connection, aggregate, new Aggregate(next, aggregate.fence()),
                        Action.RELEASE, command.key(), command.digest(), now);
            });
        });
    }

    // ---------------------------------------------------------------- reads

    @Override
    public CompletionStage<Optional<Record>> get(String tenantId, DeploymentId deploymentId) {
        return async(() -> {
            Objects.requireNonNull(deploymentId, "deploymentId");
            return read(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(AGGREGATE_WITH_LEASE
                        + "WHERE d.tenant_id = ? AND d.deployment_id = ?")) {
                    statement.setString(1, tenantId);
                    statement.setString(2, deploymentId.value());
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(recordFromJoinedRow(rows, tenantId, deploymentId.value()));
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<Optional<GraphVersion>> version(String tenantId, DeploymentId deploymentId,
                                                           long version) {
        return async(() -> {
            Objects.requireNonNull(deploymentId, "deploymentId");
            return read(connection -> {
                String sql = "SELECT format_version, canonical_bytes, digest, author, "
                        + "created_at_epoch_second, created_at_nano FROM deployment_version "
                        + "WHERE tenant_id = ? AND deployment_id = ? AND version = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    statement.setString(2, deploymentId.value());
                    statement.setLong(3, version);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return Optional.empty();
                        }
                        byte[] canonical = rows.getBytes("canonical_bytes");
                        byte[] storedDigest = rows.getBytes("digest");
                        if (!Arrays.equals(storedDigest, sha256(canonical))) {
                            // See the class documentation's note on FailureReason: this is a store
                            // fault, not a caller mistake, and InvalidRequest is the least-bad member.
                            throw invalid("a stored deployment version failed digest verification");
                        }
                        GraphVersion.Content content = new GraphVersion.Content(
                                rows.getInt("format_version"), canonical, rows.getString("author"),
                                StoredInstant.read(rows, "created_at"));
                        return Optional.of(GraphVersion.bind(tenantId, deploymentId, version, content));
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<Page> list(String tenantId, String cursor, int limit) {
        return async(() -> {
            if (tenantId == null || tenantId.isBlank() || limit < 1 || limit > LIMITS.maximumPageSize()) {
                throw invalid("limit or tenant");
            }
            String after = decodeCursor(tenantId, cursor);
            return read(connection -> {
                // Ordered by the deployment identity itself, under the bytewise C collation the
                // listing index carries. The order has to come from data: PostgreSQL has no row
                // identity a query could fall back on, and an order the planner were free to choose
                // would let one page repeat a row another page already returned.
                String sql = AGGREGATE_WITH_LEASE
                        + "WHERE d.tenant_id = ? AND d.deployment_id COLLATE \"C\" > ? "
                        + "ORDER BY d.deployment_id COLLATE \"C\" LIMIT ?";
                List<Record> fetched = new ArrayList<>();
                List<String> identities = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    statement.setString(2, after == null ? "" : after);
                    // One more than asked for, so "is there another page" is answered by the same
                    // statement rather than by a second count that could disagree with it.
                    statement.setInt(3, limit + 1);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            String deploymentId = rows.getString("deployment_id");
                            fetched.add(recordFromJoinedRow(rows, tenantId, deploymentId));
                            identities.add(deploymentId);
                        }
                    }
                }
                boolean hasMore = fetched.size() > limit;
                List<Record> page = hasMore ? fetched.subList(0, limit) : fetched;
                String next = hasMore ? encodeCursor(tenantId, identities.get(limit - 1)) : null;
                return new Page(page, next);
            });
        });
    }

    /**
     * Removes {@code deployment_command} rows recorded for {@code tenantId} whose retention window has
     * elapsed on this registry's clock.
     *
     * <p>Not on {@link DeploymentRegistry}: the port is frozen for this change, and retention is
     * adapter-local administration — an in-memory adapter has no ledger table to bound. Operator- or
     * scheduler-driven, exactly like {@code ExecutionStore#purgeExpiredIdempotencyRecords}; nothing in
     * this class calls it on its own.</p>
     *
     * <p>Deliberately not taking any aggregate's row lock. A purge removes rows whose retention has
     * already elapsed, so it never competes with a live decision for the same row: a ledger row a
     * concurrent mutation is about to read is one the mutation just wrote, whose expiry is a whole
     * retention window away. Locking every affected aggregate would make routine housekeeping block
     * production writes for no property this needs.</p>
     *
     * @param tenantId tenant whose expired command records may be removed.
     * @return stage completing with the number of rows removed.
     */
    public CompletionStage<Long> purgeExpiredCommandRecords(String tenantId) {
        return async(() -> {
            if (tenantId == null || tenantId.isBlank()) {
                throw invalid("tenantId cannot be blank");
            }
            return write(connection -> {
                String sql = "DELETE FROM deployment_command WHERE tenant_id = ? AND "
                        + StoredInstant.strictlyBefore("expires_at");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now());
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    /**
     * Releases what the <em>process</em> owns, and no lease.
     *
     * <p>A {@code kill -9} releases no lease, so this does not either: an orderly shutdown and a crash
     * must differ only in latency, or takeover would follow different paths in the two cases and every
     * test of it would be evidence about the path nobody experiences in production. The
     * {@link DataSource} is not closed, because this registry did not open it and the deployment's
     * other stores are very likely still using it.</p>
     */
    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        worker.shutdown();
    }

    // ---------------------------------------------------------------- business rules

    private Aggregate requireAggregate(Connection connection, String tenant, String deploymentId)
            throws SQLException {
        Aggregate aggregate = lockAggregate(connection, tenant, deploymentId);
        if (aggregate == null) {
            throw failure(new FailureReason.NotFound());
        }
        return aggregate;
    }

    private Aggregate requireMutableAggregate(Connection connection, String tenant, String deploymentId)
            throws SQLException {
        Aggregate aggregate = requireAggregate(connection, tenant, deploymentId);
        if (aggregate.record().tombstone() != null) {
            throw failure(new FailureReason.Conflict());
        }
        return aggregate;
    }

    // Both expectations, revision first. They answer different questions (Command's own Javadoc): the
    // revision asks whether anything was written, the generation whether the lifecycle moved. Both
    // violations are Conflict, because FailureReason is sealed and describes store faults; the
    // client-facing StaleGeneration distinction is the coordinator's to draw from the record it holds.
    private void expect(Aggregate aggregate, Command command) {
        if (command.expectedRevision().revision() != aggregate.record().revision()) {
            throw failure(new FailureReason.Conflict());
        }
        if (command.expectedGeneration() instanceof GenerationExpectation.Exactly exact
                && exact.generation() != aggregate.record().generation()) {
            throw failure(new FailureReason.Conflict());
        }
    }

    // Fencing precedes lease liveness: a fenced-out caller is told so before anything about the current
    // holder, because answering out of a liveness check first would let a fenced caller learn it merely
    // lost a race rather than that its authority is gone for good.
    private void ownerGuard(Aggregate aggregate, Lease lease, Instant now) {
        Record record = aggregate.record();
        if (!record.tenantId().equals(lease.tenantId())
                || !record.deploymentId().equals(lease.deploymentId())) {
            throw failure(new FailureReason.NotFound());
        }
        if (aggregate.fence() != lease.fence()) {
            throw failure(new FailureReason.Fenced());
        }
        if (record.lease() == null || !record.lease().owner().equals(lease.owner())
                || !record.lease().expiresAt().isAfter(now)) {
            throw failure(new FailureReason.LeaseLost());
        }
    }

    private boolean isCurrentLive(Aggregate aggregate, Lease lease, Instant now) {
        Record record = aggregate.record();
        return aggregate.fence() == lease.fence() && record.lease() != null
                && record.lease().owner().equals(lease.owner())
                && record.lease().expiresAt().isAfter(now);
    }

    private void evidenceTime(Record record, Instant at, Instant now) {
        if (at.isBefore(record.createdAt()) || at.isAfter(now)) {
            throw invalid("incoherent evidence timestamp");
        }
    }

    private void ttl(Duration value) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(LIMITS.maximumLeaseTtl()) > 0) {
            throw invalid("ttl");
        }
    }

    private Record replay(LedgerEntry prior, String digest) {
        if (!prior.digest().equals(digest)) {
            throw failure(new FailureReason.Conflict());
        }
        return prior.record();
    }

    private Record persist(Connection connection, Aggregate loaded, Aggregate next, Action action,
                           String key, String digest, Instant now) throws SQLException {
        saveAggregate(connection, loaded.record().revision(), next);
        insertLedgerEntry(connection, next.record().tenantId(), next.record().deploymentId().value(),
                action, key, digest, next.record(), now, now.plus(commandRetention));
        return next.record();
    }

    // ---------------------------------------------------------------- rows: deployment aggregate

    /**
     * Reads the aggregate and holds its row for the rest of the transaction.
     *
     * <p>There is no unlocked variant of this method, and that is the point. Every caller is a mutation
     * that will write a value derived from what it reads here — a revision, a fence, a generation, a
     * lifecycle level or a replay decision — so a reader that took no lock would be a reader that
     * decides against a state another host is free to move underneath it. The two queries that
     * genuinely only report, {@link #get} and {@link #list}, do not come through here at all.</p>
     */
    private Aggregate lockAggregate(Connection connection, String tenant, String deploymentId)
            throws SQLException {
        long latestVersion;
        long generation;
        long revision;
        long fence;
        Desired desired;
        Observation observed;
        Failure failure;
        Tombstone tombstone;
        Instant createdAt;
        Instant updatedAt;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM deployment WHERE tenant_id = ? AND deployment_id = ? FOR UPDATE")) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                latestVersion = rows.getLong("latest_version");
                generation = rows.getLong("generation");
                revision = rows.getLong("revision");
                fence = rows.getLong("fence");
                desired = desiredFrom(rows, "", generation);
                observed = observationFrom(rows, "");
                failure = failureFrom(rows, "");
                tombstone = tombstoneFrom(rows, "");
                createdAt = StoredInstant.read(rows, "created_at");
                updatedAt = StoredInstant.read(rows, "updated_at");
            }
        }
        // Issued after the lock is held, so this statement's own snapshot is taken later than every
        // transaction that could have written the lease -- each of which had to hold the row above.
        Lease lease = readLease(connection, tenant, deploymentId);
        Record record = new Record(tenant, DeploymentId.of(deploymentId), latestVersion, generation,
                revision, desired, observed, lease, failure, tombstone, createdAt, updatedAt);
        return new Aggregate(record, fence);
    }

    private Record recordFromJoinedRow(ResultSet rows, String tenant, String deploymentId)
            throws SQLException {
        long generation = rows.getLong("generation");
        return new Record(tenant, DeploymentId.of(deploymentId), rows.getLong("latest_version"),
                generation, rows.getLong("revision"), desiredFrom(rows, "", generation),
                observationFrom(rows, ""), leaseFrom(rows, "lease_", tenant, deploymentId),
                failureFrom(rows, ""), tombstoneFrom(rows, ""), StoredInstant.read(rows, "created_at"),
                StoredInstant.read(rows, "updated_at"));
    }

    private Lease readLease(Connection connection, String tenant, String deploymentId)
            throws SQLException {
        String sql = "SELECT owner, fence, acquired_at_epoch_second, acquired_at_nano, "
                + "expires_at_epoch_second, expires_at_nano FROM deployment_lease "
                + "WHERE tenant_id = ? AND deployment_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? leaseFrom(rows, "", tenant, deploymentId) : null;
            }
        }
    }

    private void insertDeploymentRow(Connection connection, String tenant, String deploymentId,
                                     Instant now) throws SQLException {
        String sql = "INSERT INTO deployment (tenant_id, deployment_id, latest_version, generation, "
                + "revision, fence, desired_kind, desired_version, update_strategy, observed_kind, "
                + "observed_version, observed_generation, observed_at_epoch_second, observed_at_nano, "
                + "failure_code, failure_message, failure_at_epoch_second, failure_at_nano, "
                + "tombstone_reason, tombstone_at_epoch_second, tombstone_at_nano, "
                + "created_at_epoch_second, created_at_nano, updated_at_epoch_second, updated_at_nano) "
                + "VALUES (?, ?, 1, 0, 1, 0, ?, NULL, NULL, ?, NULL, 0, ?, ?, NULL, NULL, NULL, NULL, "
                + "NULL, NULL, NULL, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, tenant);
            statement.setString(index++, deploymentId);
            statement.setString(index++, DesiredKind.STOPPED.name());
            statement.setString(index++, ObservedKind.COLD.name());
            index = StoredInstant.bindValue(statement, index, now);
            index = StoredInstant.bindValue(statement, index, now);
            StoredInstant.bindValue(statement, index, now);
            statement.executeUpdate();
        }
    }

    /**
     * Advances the aggregate's row, with the revision the decision was made on in the {@code WHERE}.
     *
     * <p>The guard is redundant while the caller holds this row's {@code FOR UPDATE} lock, and it is
     * kept precisely because that is an argument about a caller rather than about this statement. A
     * conditional update is wrong only if the condition is wrong; an unconditional one is wrong the
     * moment any future caller reaches it without the lock, and nothing would say so.</p>
     *
     * <p>{@code created_at} is absent from the {@code SET} because it is write-once, which is what
     * makes a listing's ordering stable while writes continue.</p>
     */
    private void saveAggregate(Connection connection, long expectedRevision, Aggregate aggregate)
            throws SQLException {
        Record record = aggregate.record();
        String tenant = record.tenantId();
        String deploymentId = record.deploymentId().value();
        String sql = "UPDATE deployment SET latest_version = ?, generation = ?, revision = ?, "
                + "fence = ?, desired_kind = ?, desired_version = ?, update_strategy = ?, "
                + "observed_kind = ?, observed_version = ?, observed_generation = ?, "
                + "observed_at_epoch_second = ?, observed_at_nano = ?, failure_code = ?, "
                + "failure_message = ?, failure_at_epoch_second = ?, failure_at_nano = ?, "
                + "tombstone_reason = ?, tombstone_at_epoch_second = ?, tombstone_at_nano = ?, "
                + "updated_at_epoch_second = ?, updated_at_nano = ? "
                + "WHERE tenant_id = ? AND deployment_id = ? AND revision = ?";
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, record.latestVersion());
            statement.setLong(index++, record.generation());
            statement.setLong(index++, record.revision());
            statement.setLong(index++, aggregate.fence());
            statement.setString(index++, record.desired().kind().name());
            index = setNullableLong(statement, index, record.desired().desiredVersion());
            statement.setString(index++, record.desired().updateStrategy() == null
                    ? null : record.desired().updateStrategy().name());
            statement.setString(index++, record.observed().state().name());
            index = setNullableLong(statement, index, record.observed().activeVersion());
            statement.setLong(index++, record.observed().observedGeneration());
            index = StoredInstant.bindValue(statement, index, record.observed().observedAt());
            if (record.failure() != null) {
                statement.setString(index++, record.failure().code());
                statement.setString(index++, record.failure().message());
                index = StoredInstant.bindValue(statement, index, record.failure().at());
            } else {
                statement.setString(index++, null);
                statement.setString(index++, null);
                index = bindNullableInstant(statement, index, null);
            }
            if (record.tombstone() != null) {
                statement.setString(index++, record.tombstone().reason());
                index = StoredInstant.bindValue(statement, index, record.tombstone().at());
            } else {
                statement.setString(index++, null);
                index = bindNullableInstant(statement, index, null);
            }
            index = StoredInstant.bindValue(statement, index, record.updatedAt());
            statement.setString(index++, tenant);
            statement.setString(index++, deploymentId);
            statement.setLong(index, expectedRevision);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            // Unreachable while the lock above is held, and reported rather than ignored because the
            // only way to get here is that the lock was not held: another writer moved the revision
            // this decision was derived from, and continuing would write an aggregate assembled from a
            // state that no longer exists.
            throw failure(new FailureReason.Conflict());
        }
        if (record.lease() != null) {
            upsertLease(connection, tenant, deploymentId, record.lease());
        } else {
            deleteLease(connection, tenant, deploymentId);
        }
    }

    private void upsertLease(Connection connection, String tenant, String deploymentId, Lease lease)
            throws SQLException {
        // An upsert rather than a delete followed by an insert, on the model PostgresExecutionStore
        // already establishes: the pair would be observable as a removal to anything watching this row
        // and would cascade needlessly against any future child table.
        String sql = "INSERT INTO deployment_lease (tenant_id, deployment_id, owner, fence, "
                + "acquired_at_epoch_second, acquired_at_nano, expires_at_epoch_second, "
                + "expires_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id, deployment_id) DO UPDATE SET owner = EXCLUDED.owner, "
                + "fence = EXCLUDED.fence, "
                + "acquired_at_epoch_second = EXCLUDED.acquired_at_epoch_second, "
                + "acquired_at_nano = EXCLUDED.acquired_at_nano, "
                + "expires_at_epoch_second = EXCLUDED.expires_at_epoch_second, "
                + "expires_at_nano = EXCLUDED.expires_at_nano";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, tenant);
            statement.setString(index++, deploymentId);
            statement.setString(index++, lease.owner());
            statement.setLong(index++, lease.fence());
            index = StoredInstant.bindValue(statement, index, lease.acquiredAt());
            StoredInstant.bindValue(statement, index, lease.expiresAt());
            statement.executeUpdate();
        }
    }

    private void deleteLease(Connection connection, String tenant, String deploymentId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM deployment_lease WHERE tenant_id = ? AND deployment_id = ?")) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- rows: graph versions

    private void insertVersionRow(Connection connection, String tenant, String deploymentId,
                                  long version, GraphVersion.Content content) throws SQLException {
        byte[] canonical = content.canonicalSnapshot();
        byte[] digest = sha256(canonical);
        String sql = "INSERT INTO deployment_version (tenant_id, deployment_id, version, "
                + "format_version, canonical_bytes, digest, author, created_at_epoch_second, "
                + "created_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, tenant);
            statement.setString(index++, deploymentId);
            statement.setLong(index++, version);
            statement.setInt(index++, content.snapshotFormatVersion());
            statement.setBytes(index++, canonical);
            statement.setBytes(index++, digest);
            statement.setString(index++, content.createdBy());
            StoredInstant.bindValue(statement, index, content.createdAt());
            statement.executeUpdate();
        }
    }

    private boolean versionExists(Connection connection, String tenant, String deploymentId,
                                  long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM deployment_version WHERE tenant_id = ? AND deployment_id = ? "
                        + "AND version = ?")) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            statement.setLong(3, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ---------------------------------------------------------------- rows: command ledger

    private LedgerEntry loadLedgerEntry(Connection connection, String tenant, String deploymentId,
                                        Action action, String key) throws SQLException {
        String sql = LEDGER_COLUMNS + " FROM deployment_command WHERE tenant_id = ? "
                + "AND deployment_id = ? AND action = ? AND command_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            statement.setString(3, action.name());
            statement.setString(4, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new LedgerEntry(rows.getString("digest"),
                        recordFromLedgerRow(rows, tenant, deploymentId));
            }
        }
    }

    /**
     * Resolves a {@code create} replay by tenant and key alone, reading the minted identity back out of
     * the row it finds.
     *
     * <p>This is the one ledger lookup that cannot name a deployment, because the identity a create
     * produces is exactly what the caller is asking to be told again.</p>
     */
    private LedgerEntry loadCreateLedgerEntry(Connection connection, String tenant, String key)
            throws SQLException {
        String sql = "SELECT deployment_id, " + LEDGER_COLUMNS.substring("SELECT ".length())
                + " FROM deployment_command WHERE tenant_id = ? AND action = 'CREATE' "
                + "AND command_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String deploymentId = rows.getString("deployment_id");
                return new LedgerEntry(rows.getString("digest"),
                        recordFromLedgerRow(rows, tenant, deploymentId));
            }
        }
    }

    private static Record recordFromLedgerRow(ResultSet rows, String tenant, String deploymentId)
            throws SQLException {
        long generation = rows.getLong("recorded_generation");
        return new Record(tenant, DeploymentId.of(deploymentId),
                rows.getLong("recorded_latest_version"), generation, rows.getLong("recorded_revision"),
                desiredFrom(rows, "recorded_", generation), observationFrom(rows, "recorded_"),
                leaseFrom(rows, "recorded_lease_", tenant, deploymentId), failureFrom(rows, "recorded_"),
                tombstoneFrom(rows, "recorded_"), StoredInstant.read(rows, "recorded_created_at"),
                StoredInstant.read(rows, "recorded_updated_at"));
    }

    private void insertLedgerEntry(Connection connection, String tenant, String deploymentId,
                                   Action action, String key, String digest, Record record,
                                   Instant recordedAt, Instant expiresAt) throws SQLException {
        String sql = "INSERT INTO deployment_command (tenant_id, deployment_id, action, command_key, "
                + "digest, recorded_latest_version, recorded_generation, recorded_revision, "
                + "recorded_desired_kind, recorded_desired_version, recorded_update_strategy, "
                + "recorded_observed_kind, recorded_observed_version, recorded_observed_generation, "
                + "recorded_observed_at_epoch_second, recorded_observed_at_nano, recorded_lease_owner, "
                + "recorded_lease_fence, recorded_lease_acquired_at_epoch_second, "
                + "recorded_lease_acquired_at_nano, recorded_lease_expires_at_epoch_second, "
                + "recorded_lease_expires_at_nano, recorded_failure_code, recorded_failure_message, "
                + "recorded_failure_at_epoch_second, recorded_failure_at_nano, "
                + "recorded_tombstone_reason, recorded_tombstone_at_epoch_second, "
                + "recorded_tombstone_at_nano, recorded_created_at_epoch_second, "
                + "recorded_created_at_nano, recorded_updated_at_epoch_second, "
                + "recorded_updated_at_nano, recorded_at_epoch_second, recorded_at_nano, "
                + "expires_at_epoch_second, expires_at_nano) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, tenant);
            statement.setString(index++, deploymentId);
            statement.setString(index++, action.name());
            statement.setString(index++, key);
            statement.setString(index++, digest);
            statement.setLong(index++, record.latestVersion());
            statement.setLong(index++, record.generation());
            statement.setLong(index++, record.revision());
            statement.setString(index++, record.desired().kind().name());
            index = setNullableLong(statement, index, record.desired().desiredVersion());
            statement.setString(index++, record.desired().updateStrategy() == null
                    ? null : record.desired().updateStrategy().name());
            statement.setString(index++, record.observed().state().name());
            index = setNullableLong(statement, index, record.observed().activeVersion());
            statement.setLong(index++, record.observed().observedGeneration());
            index = StoredInstant.bindValue(statement, index, record.observed().observedAt());
            if (record.lease() != null) {
                statement.setString(index++, record.lease().owner());
                statement.setLong(index++, record.lease().fence());
                index = StoredInstant.bindValue(statement, index, record.lease().acquiredAt());
                index = StoredInstant.bindValue(statement, index, record.lease().expiresAt());
            } else {
                statement.setString(index++, null);
                index = setNullableLong(statement, index, null);
                index = bindNullableInstant(statement, index, null);
                index = bindNullableInstant(statement, index, null);
            }
            if (record.failure() != null) {
                statement.setString(index++, record.failure().code());
                statement.setString(index++, record.failure().message());
                index = StoredInstant.bindValue(statement, index, record.failure().at());
            } else {
                statement.setString(index++, null);
                statement.setString(index++, null);
                index = bindNullableInstant(statement, index, null);
            }
            if (record.tombstone() != null) {
                statement.setString(index++, record.tombstone().reason());
                index = StoredInstant.bindValue(statement, index, record.tombstone().at());
            } else {
                statement.setString(index++, null);
                index = bindNullableInstant(statement, index, null);
            }
            index = StoredInstant.bindValue(statement, index, record.createdAt());
            index = StoredInstant.bindValue(statement, index, record.updatedAt());
            index = StoredInstant.bindValue(statement, index, recordedAt);
            StoredInstant.bindValue(statement, index, expiresAt);
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- column codecs shared by both tables

    private static Desired desiredFrom(ResultSet rows, String prefix, long generation)
            throws SQLException {
        DesiredKind kind = DesiredKind.valueOf(rows.getString(prefix + "desired_kind"));
        Long version = nullableLong(rows, prefix + "desired_version");
        String strategy = rows.getString(prefix + "update_strategy");
        return new Desired(kind, version, strategy == null ? null : UpdateStrategy.valueOf(strategy),
                generation);
    }

    private static Observation observationFrom(ResultSet rows, String prefix) throws SQLException {
        return new Observation(ObservedKind.valueOf(rows.getString(prefix + "observed_kind")),
                nullableLong(rows, prefix + "observed_version"),
                rows.getLong(prefix + "observed_generation"),
                StoredInstant.read(rows, prefix + "observed_at"));
    }

    private static Lease leaseFrom(ResultSet rows, String prefix, String tenant, String deploymentId)
            throws SQLException {
        String owner = rows.getString(prefix + "owner");
        if (owner == null) {
            return null;
        }
        return new Lease(tenant, DeploymentId.of(deploymentId), owner, rows.getLong(prefix + "fence"),
                StoredInstant.read(rows, prefix + "acquired_at"),
                StoredInstant.read(rows, prefix + "expires_at"));
    }

    private static Failure failureFrom(ResultSet rows, String prefix) throws SQLException {
        String code = rows.getString(prefix + "failure_code");
        if (code == null) {
            return null;
        }
        return new Failure(code, rows.getString(prefix + "failure_message"),
                StoredInstant.read(rows, prefix + "failure_at"));
    }

    private static Tombstone tombstoneFrom(ResultSet rows, String prefix) throws SQLException {
        String reason = rows.getString(prefix + "tombstone_reason");
        if (reason == null) {
            return null;
        }
        return new Tombstone(reason, StoredInstant.read(rows, prefix + "tombstone_at"));
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static int setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
        return index + 1;
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

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    // ---------------------------------------------------------------- cursor codec (rr1, shared wire form)

    private static String encodeCursor(String tenant, String lastId) {
        String raw = CURSOR_VERSION + "\u0000" + tenant + "\u0000" + lastId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String tenant, String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\u0000", -1);
            if (parts.length != 3 || !CURSOR_VERSION.equals(parts[0]) || !tenant.equals(parts[1])
                    || parts[2].isBlank()) {
                throw invalid("invalid cursor");
            }
            return parts[2];
        } catch (IllegalArgumentException malformed) {
            throw invalid("invalid cursor");
        }
    }

    // ---------------------------------------------------------------- plumbing

    private <T> T write(Transactions.Work<T> work) {
        try {
            return transactions.inTransaction(work);
        } catch (OutcomeUnknownException unknown) {
            throw outcomeUnknown(unknown);
        } catch (SQLException failed) {
            throw mapSqlFailure(failed);
        }
    }

    /**
     * A read of exactly one statement.
     *
     * <p>Every reader here is one statement by construction — {@link #get} and {@link #list} join the
     * lease in rather than fetching it separately, and {@link #version} touches one table — so none of
     * them needs {@code Transactions.readConsistent}. That is a property to preserve rather than an
     * accident: a reader that grew a second statement would silently assemble its answer from two
     * committed states, because {@code READ COMMITTED} takes a fresh snapshot per statement whether or
     * not a transaction is open.</p>
     */
    private <T> T read(Transactions.Work<T> work) {
        try {
            return transactions.readOnly(work);
        } catch (SQLException failed) {
            throw mapSqlFailure(failed);
        }
    }

    /**
     * Reports a {@code COMMIT} that neither succeeded nor demonstrably failed.
     *
     * <p>The single-host adapter records this state as unreachable, because its {@code COMMIT} is a
     * local write. Here it is ordinary: the network can drop between the client sending {@code COMMIT}
     * and the acknowledgement arriving, and in that window the transaction may have been applied.
     * Retrying is not available — a retry of a committed mutation is a duplicate — so the honest answer
     * is that the outcome is unknown and the caller must resolve it against the registry, which is what
     * the message says. It is carried on {@code InvalidRequest} only because
     * {@link DeploymentRegistry.FailureReason} has no member that can say it; see the class
     * documentation.</p>
     */
    private static RegistryException outcomeUnknown(OutcomeUnknownException unknown) {
        return invalid("the outcome of this deployment registry mutation could not be confirmed and "
                + "must be resolved by reading the deployment back: " + unknown.getMessage());
    }

    /**
     * Maps a storage fault onto the only member of the sealed {@code FailureReason} that can carry it.
     *
     * <p>The driver's own message is deliberately dropped and a classifier substituted, on the rule this
     * repository already states for {@code DurableExecutionResult} and
     * {@code DeploymentCommandOutcome.Failed}: a database message may carry bound parameter fragments,
     * a credential or author-controlled text, and this value reaches both the caller and the log. The
     * {@code SQLSTATE} is kept, because it is a fixed five-character code defined by the server and is
     * exactly the part an operator needs.</p>
     */
    private static RegistryException mapSqlFailure(SQLException failed) {
        String state = String.valueOf(failed.getSQLState());
        if (SqlStates.isNotAuthorized(failed)) {
            return invalid("the deployment registry's credential cannot perform this operation "
                    + "(SQLSTATE " + state + ")");
        }
        if (SqlStates.isCorrupted(failed)) {
            return invalid("the deployment registry's database reports damage to its own storage "
                    + "(SQLSTATE " + state + ")");
        }
        if (SqlStates.isTimedOut(failed)) {
            return invalid("a deployment registry statement exceeded its lock or statement timeout "
                    + "(SQLSTATE " + state + ")");
        }
        if (SqlStates.isUnavailable(failed)) {
            return invalid("the deployment registry's database is unreachable (SQLSTATE " + state + ")");
        }
        return invalid("deployment registry storage fault (SQLSTATE " + state + ")");
    }

    private Instant now() {
        return clock.instant();
    }

    private static RegistryException invalid(String message) {
        return failure(new FailureReason.InvalidRequest(message));
    }

    private static RegistryException failure(FailureReason reason) {
        return new RegistryException(reason);
    }

    private <T> CompletionStage<T> async(java.util.function.Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(invalid("this deployment registry is closed"));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }

    /** A deployment's current {@code Record} paired with its fence, which {@code Record} does not carry. */
    private record Aggregate(Record record, long fence) {
    }

    /** One ledger row's recorded digest and the exact {@code Record} snapshot it produced. */
    private record LedgerEntry(String digest, Record record) {
    }
}
