package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentIdSource;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistryDefaults;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.deployment.registry.GraphVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable {@link DeploymentRegistry} over the same SQLite database the execution store uses.
 *
 * <h2>Why the same database file</h2>
 * <p>The reasons {@link SqliteExecutionManifestStore} and {@link SqliteGraphDefinitionStore} both give
 * apply unchanged: one backup captures a deployment aggregate together with the executions it
 * dispatches, one schema version describes both so a binary cannot open a file whose executions it
 * understands and whose deployments it does not, and an operator inspecting one file sees the whole
 * durable state of one host rather than piecing it together from several. There is deliberately no
 * foreign key from {@code deployment} to any execution-store table and none in the other direction:
 * ADR 0038 D2 stratifies fencing so that the deployment fence and {@code LeaseHandle.fencingToken} govern
 * disjoint concerns, and a foreign key would wire two aggregates together that the port explicitly
 * keeps independent.</p>
 *
 * <h2>Normalized rows, not a serialized blob</h2>
 * <p>Every table {@link SqliteSchema}'s migration 22 adds is columns, for the reason the schema states
 * for the execution aggregate: a blob column makes the on-disk format an encoding of a Java type, so
 * the aggregate cannot change shape without a data migration, and nothing on disk is queryable by an
 * operator. {@link #get} and {@link #version} reconstruct their return values through
 * {@link DeploymentRegistry.Record}'s and {@link GraphVersion}'s own canonical constructors, which is
 * where this port's validation lives; a row that cannot pass it is a defect in this adapter, not a
 * state the domain accepts.</p>
 *
 * <h2>Why {@code maxClockSkew} is not zero here</h2>
 * <p>{@code InMemoryDeploymentRegistry}, referenced only in this Javadoc because it lives in
 * {@code ravenroot-core} and this module does not depend on it, is deliberately allowed to publish
 * {@link Limits#maxClockSkew()} as {@link Duration#ZERO}: its clock <em>is</em> the caller's clock,
 * one process, one {@link Clock} instance, so there is no skew to allow. That equivalence does not
 * hold here. This adapter's clock is read by whichever process happens to be running it, and a
 * lease holder that renews or observes against this store is very often a different process entirely
 * (that is the whole point of {@code CROSS_PROCESS_LEASE} coverage against a durable adapter).
 * Publishing zero would tell every such caller it can trust its own clock down to the instant against
 * an authority it does not share a clock with, which is false in the same direction that made zero
 * true for the in-memory reference: there the caller and the authority are provably the same clock;
 * here they are provably not. Five seconds is chosen because it comfortably covers ordinary NTP-
 * disciplined drift between hosts without eating a meaningful fraction of the five-minute maximum
 * lease this adapter also publishes, and {@link Limits}'s own canonical constructor enforces that the
 * allowance stays strictly inside that bound.</p>
 *
 * <h2>Bounded retention on the command ledger</h2>
 * <p>{@code deployment_command} exists so a replayed command returns the exact outcome it produced the
 * first time (ADR 0038 D11) rather than the aggregate's current, possibly divergent, state — which is
 * also why each row stores the whole {@link DeploymentRegistry.Record} snapshot it produced, as
 * columns, rather than a reference into the live {@code deployment} row. Left unbounded that ledger is
 * a table that only ever grows: every accepted mutation of every deployment a tenant has ever created
 * adds one more row that nothing removes, so the failure this adapter is built to avoid is not a
 * dramatic one, it is a table an operator eventually cannot back up, index or migrate in a maintenance
 * window, discovered long after the commands it records stopped mattering. {@link #purgeExpiredCommandRecords}
 * exists to give an operator that lever; it is deliberately not invoked automatically anywhere in this
 * class, on the model of {@code ExecutionStore#purgeExpiredIdempotencyRecords} being scheduler- or
 * operator-driven rather than a background reaper an application cannot see or pace.</p>
 *
 * <h2>A forced compromise in the frozen port: {@code FailureReason} has no store-fault member</h2>
 * <p>{@link DeploymentRegistry.FailureReason} is sealed to {@code NotFound}, {@code Conflict},
 * {@code Fenced}, {@code LeaseLost} and {@code InvalidRequest}, and this class may not add a sixth. Every
 * business rejection below lands on one of those five by genuine meaning. A small residue does not: a
 * disk fault, a corrupt page, a database this process cannot open. {@link #mapSqlFailure} routes that
 * residue to {@code InvalidRequest} because it is the only member with a message a caller can log, not
 * because the failure is an invalid request — a caller that reads {@code InvalidRequest} as "the
 * caller's mistake" will misclassify an adapter-side fault. This is a genuine gap in what the frozen
 * port lets an adapter say, most plausibly closed by a future store-fault member the way
 * {@code ExecutionStoreFailure} already has one; it is flagged here rather than resolved by inventing
 * a meaning {@code FailureReason} was not given.</p>
 */
public final class SqliteDeploymentRegistry implements DeploymentRegistry {

    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_NOTADB = 26;

    private static final String CURSOR_VERSION = "rr1";
    private enum Action { CREATE, APPEND, COMMAND, OBSERVE, FAIL, TOMBSTONE, ACQUIRE, RENEW, RELEASE }

    private final SqliteStoreLocation location;
    private final Path databaseFile;
    private final Clock clock;
    private final DeploymentIdSource ids;
    private final Duration commandRetention;
    private final Limits limits;
    private final int busyTimeoutMillis;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Connection connection;

    /**
     * Opens the registry over an existing execution-store database file, minting deployment ids as
     * random UUIDs and retaining ledger rows for the durable default retention.
     *
     * @param databaseFile the execution store database this adapter shares.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     */
    public SqliteDeploymentRegistry(Path databaseFile, Clock clock) {
        this(databaseFile, clock, tenant -> DeploymentId.of(java.util.UUID.randomUUID().toString()));
    }

    /**
     * Opens the registry with an explicit id source, retaining ledger rows for
     * the durable default retention.
     *
     * @param databaseFile the execution store database this adapter shares.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     */
    public SqliteDeploymentRegistry(Path databaseFile, Clock clock, DeploymentIdSource ids) {
        this(databaseFile, clock, ids, DeploymentRegistryDefaults.durableCommandRetention());
    }

    /**
     * Opens the registry with an explicit id source and command-ledger retention.
     *
     * @param databaseFile the execution store database this adapter shares.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     * @param commandRetention how long a {@code deployment_command} row survives past its recording
     *                         before {@link #purgeExpiredCommandRecords} may remove it; must be
     *                         positive.
     */
    public SqliteDeploymentRegistry(Path databaseFile, Clock clock, DeploymentIdSource ids,
                                    Duration commandRetention) {
        this(SqliteStoreLocation.ofFile(databaseFile), clock, ids, commandRetention);
    }

    /**
     * Opens the registry at an explicit store location.
     *
     * @param location the execution store database this adapter shares.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     * @param commandRetention how long a {@code deployment_command} row survives past its recording
     *                         before {@link #purgeExpiredCommandRecords} may remove it; must be
     *                         positive.
     */
    public SqliteDeploymentRegistry(SqliteStoreLocation location, Clock clock, DeploymentIdSource ids,
                                    Duration commandRetention) {
        this(location, clock, ids, commandRetention, DeploymentRegistryDefaults.durableLimits(),
                SqliteStoreConfig.defaults());
    }

    /**
     * Opens the registry with explicit registry policy and SQLite connection settings.
     *
     * @param location the execution store database this adapter shares.
     * @param clock time authority for every instant this registry records or evaluates expiry against.
     * @param ids server-side seam that mints a stable identity for each newly created deployment.
     * @param commandRetention how long a command row survives before explicit purge may remove it.
     * @param limits published deployment page, lease, and clock-skew limits.
     * @param config SQLite connection settings; only its busy timeout applies to this registry.
     */
    public SqliteDeploymentRegistry(SqliteStoreLocation location, Clock clock, DeploymentIdSource ids,
                                    Duration commandRetention, Limits limits,
                                    SqliteStoreConfig config) {
        Objects.requireNonNull(config, "config");
        int configuredBusyTimeoutMillis = busyTimeoutMillis(config.busyTimeout());
        this.location = Objects.requireNonNull(location, "location");
        this.databaseFile = location.databaseFile();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(commandRetention, "commandRetention");
        if (commandRetention.isNegative() || commandRetention.isZero()) {
            throw new IllegalArgumentException("commandRetention must be positive");
        }
        this.commandRetention = commandRetention;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.busyTimeoutMillis = configuredBusyTimeoutMillis;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ravenroot-sqlite-deployment-registry-"
                    + this.databaseFile.getFileName());
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

    @Override
    public Limits limits() {
        return limits;
    }

    @Override
    public CompletionStage<Record> create(GraphVersion.Content content, CreateCommand command) {
        return async(() -> {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                CreateLedgerEntry prior = loadCreateLedgerEntry(command.tenantId(), command.key());
                if (prior != null) {
                    return replay(new LedgerEntry(prior.digest(), prior.record()), command.digest());
                }
                Instant now = now();
                if (content.createdAt().isAfter(now)) {
                    throw invalid("createdAt is in the future");
                }
                Instant commandExpiresAt = commandExpiry(now);
                DeploymentId id = Objects.requireNonNull(ids.mint(command.tenantId()), "minted deploymentId");
                String deploymentId = id.value();
                if (deploymentExists(command.tenantId(), deploymentId)) {
                    throw failure(new FailureReason.Conflict());
                }
                Desired desired = new Desired(DesiredKind.STOPPED, null, null, 0);
                Observation observed = new Observation(ObservedKind.COLD, null, 0, now);
                Record result = new Record(command.tenantId(), id, 1, 0, 1, desired, observed, null, null, null,
                        now, now);
                insertDeploymentRow(command.tenantId(), deploymentId, now);
                insertVersionRow(command.tenantId(), deploymentId, 1, content);
                insertLedgerEntry(command.tenantId(), deploymentId, Action.CREATE, command.key(),
                        command.digest(), result, now, commandExpiresAt);
                return result;
            });
        });
    }

    @Override
    public CompletionStage<Record> append(long version, GraphVersion.Content content, Command command) {
        return async(() -> {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.APPEND, command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                if (version != current.latestVersion() + 1) {
                    throw failure(new FailureReason.Conflict());
                }
                Instant now = now();
                if (content.createdAt().isBefore(current.createdAt()) || content.createdAt().isAfter(now)) {
                    throw invalid("incoherent version timestamp");
                }
                insertVersionRow(tenant, deploymentId, version, content);
                Record next = new Record(current.tenantId(), current.deploymentId(), version, current.generation(),
                        current.revision() + 1, current.desired(), current.observed(), current.lease(),
                        current.failure(), current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.APPEND, command.key(),
                        command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> command(Desired desired, Command command) {
        return async(() -> {
            Objects.requireNonNull(desired, "desired");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.COMMAND, command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                if (desired.kind() == DesiredKind.RUNNING
                        && !versionExists(tenant, deploymentId, desired.desiredVersion())) {
                    throw invalid("unknown desired version");
                }
                Instant now = now();
                long newGeneration = current.generation() + 1;
                Desired stamped = new Desired(desired.kind(), desired.desiredVersion(), desired.updateStrategy(),
                        newGeneration);
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        newGeneration, current.revision() + 1, stamped, current.observed(), current.lease(), null,
                        current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.COMMAND, command.key(),
                        command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> observe(Observation observation, Lease lease, Command command) {
        return async(() -> {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                Instant now = now();
                ownerGuard(aggregate, lease, now);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.OBSERVE, command.key());
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
                        && !versionExists(tenant, deploymentId, observation.activeVersion())) {
                    throw invalid("unknown active version");
                }
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        current.generation(), current.revision() + 1, current.desired(), observation,
                        current.lease(), current.failure(), current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.OBSERVE, command.key(),
                        command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> fail(Failure reported, Lease lease, Command command) {
        return async(() -> {
            Objects.requireNonNull(reported, "reported");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                Instant now = now();
                ownerGuard(aggregate, lease, now);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.FAIL, command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                Record current = aggregate.record();
                evidenceTime(current, reported.at(), now);
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        current.generation(), current.revision() + 1, current.desired(), current.observed(),
                        current.lease(), reported, current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.FAIL, command.key(),
                        command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> tombstone(Tombstone tombstone, Command command) {
        return async(() -> {
            Objects.requireNonNull(tombstone, "tombstone");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireAggregate(tenant, deploymentId);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.TOMBSTONE, command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                Record current = aggregate.record();
                if (current.tombstone() != null) {
                    throw failure(new FailureReason.Conflict());
                }
                expect(aggregate, command);
                Instant now = now();
                evidenceTime(current, tombstone.at(), now);
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        current.generation(), current.revision() + 1, current.desired(), current.observed(), null,
                        current.failure(), tombstone, current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.TOMBSTONE, command.key(),
                        command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Optional<Record>> get(String tenantId, DeploymentId deploymentId) {
        return async(() -> inReadTransaction(() -> {
            Aggregate aggregate = loadAggregate(tenantId, deploymentId.value());
            return Optional.ofNullable(aggregate).map(Aggregate::record);
        }));
    }

    @Override
    public CompletionStage<Optional<GraphVersion>> version(String tenantId, DeploymentId deploymentId, long version) {
        return async(() -> inReadTransaction(() -> {
            String sql = "SELECT format_version, canonical_bytes, digest, author, created_at_epoch_second, "
                    + "created_at_nano FROM deployment_version WHERE tenant_id = ? AND deployment_id = ? "
                    + "AND version = ?";
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
                        // See the class Javadoc's note on FailureReason: this is a store fault, not a
                        // caller mistake, and InvalidRequest is the least-bad available member.
                        throw invalid("stored deployment_version row for " + tenantId + "/" + deploymentId
                                + "/" + version + " failed digest verification");
                    }
                    GraphVersion.Content content = new GraphVersion.Content(rows.getInt("format_version"),
                            canonical, rows.getString("author"), StoredInstant.read(rows, "created_at"));
                    return Optional.of(GraphVersion.bind(tenantId, deploymentId, version, content));
                }
            }
        }));
    }

    @Override
    public CompletionStage<Page> list(String tenantId, String cursor, int limit) {
        return async(() -> inReadTransaction(() -> {
            if (tenantId == null || tenantId.isBlank() || limit < 1
                    || limit > limits.maximumPageSize()) {
                throw invalid("limit or tenant");
            }
            String after = decodeCursor(tenantId, cursor);
            String sql = "SELECT * FROM deployment WHERE tenant_id = ? AND deployment_id > ? "
                    + "ORDER BY deployment_id LIMIT ?";
            List<Record> fetched = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenantId);
                statement.setString(2, after == null ? "" : after);
                statement.setInt(3, limit + 1);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String deploymentId = rows.getString("deployment_id");
                        fetched.add(recordFromDeploymentRow(rows, tenantId, deploymentId));
                        ids.add(deploymentId);
                    }
                }
            }
            boolean hasMore = fetched.size() > limit;
            List<Record> page = hasMore ? fetched.subList(0, limit) : fetched;
            String next = hasMore ? encodeCursor(tenantId, ids.get(limit - 1)) : null;
            return new Page(page, next);
        }));
    }

    @Override
    public CompletionStage<Record> acquire(String owner, Duration ttl, Command command) {
        return async(() -> {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                ttl(ttl);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.ACQUIRE, command.key());
                Instant now = now();
                if (prior != null) {
                    if (!prior.digest().equals(command.digest())) {
                        throw failure(new FailureReason.Conflict());
                    }
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
                Instant expiresAt = leaseExpiry(now, ttl);
                long newFence = aggregate.fence() + 1;
                Lease lease = new Lease(tenant, current.deploymentId(), owner, newFence, now, expiresAt);
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        current.generation(), current.revision() + 1, current.desired(), current.observed(), lease,
                        current.failure(), current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, newFence), Action.ACQUIRE, command.key(), command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> renew(Lease lease, Duration ttl, Command command) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                Instant now = now();
                ownerGuard(aggregate, lease, now);
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.RENEW, command.key());
                if (prior != null) {
                    return replay(prior, command.digest());
                }
                expect(aggregate, command);
                ttl(ttl);
                Record current = aggregate.record();
                Instant expiresAt = leaseExpiry(now, ttl);
                Lease renewed = new Lease(tenant, current.deploymentId(), lease.owner(), lease.fence(),
                        lease.acquiredAt(), expiresAt);
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        current.generation(), current.revision() + 1, current.desired(), current.observed(),
                        renewed, current.failure(), current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.RENEW, command.key(),
                        command.digest(), now);
            });
        });
    }

    @Override
    public CompletionStage<Record> release(Lease lease, Command command) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(command, "command");
            return inWriteTransaction(() -> {
                String tenant = command.tenantId();
                String deploymentId = command.deploymentId().value();
                Aggregate aggregate = requireMutableAggregate(tenant, deploymentId);
                Instant now = now();
                LedgerEntry prior = loadLedgerEntry(tenant, deploymentId, Action.RELEASE, command.key());
                if (prior != null) {
                    if (!prior.digest().equals(command.digest())) {
                        throw failure(new FailureReason.Conflict());
                    }
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
                Record next = new Record(current.tenantId(), current.deploymentId(), current.latestVersion(),
                        current.generation(), current.revision() + 1, current.desired(), current.observed(), null,
                        current.failure(), current.tombstone(), current.createdAt(), now);
                return persist(new Aggregate(next, aggregate.fence()), Action.RELEASE, command.key(),
                        command.digest(), now);
            });
        });
    }

    /**
     * Removes {@code deployment_command} rows recorded for {@code tenantId} whose retention window has
     * elapsed on this registry's clock.
     *
     * <p>Not on {@link DeploymentRegistry}: the port is frozen for this change, and retention is
     * adapter-local administration in the same sense {@link SqliteStoreLocation}'s restore operations
     * are — a remote or in-memory adapter has no ledger table to bound. Operator- or scheduler-driven,
     * exactly like {@code ExecutionStore#purgeExpiredIdempotencyRecords}; nothing in this class calls
     * it on its own.</p>
     *
     * @param tenantId tenant whose expired command records may be removed.
     * @return stage completing with the number of rows removed.
     */
    public CompletionStage<Long> purgeExpiredCommandRecords(String tenantId) {
        return async(() -> {
            if (tenantId == null || tenantId.isBlank()) {
                throw invalid("tenantId cannot be blank");
            }
            return inWriteTransaction(() -> {
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            onWorker(() -> {
                connection.close();
                return null;
            });
        } catch (RuntimeException ignored) {
            // Closing twice, or closing a connection the driver already dropped, is not actionable.
        } finally {
            worker.shutdown();
        }
    }

    // ---------------------------------------------------------------- business rules

    private Aggregate requireAggregate(String tenant, String deploymentId) throws SQLException {
        Aggregate aggregate = loadAggregate(tenant, deploymentId);
        if (aggregate == null) {
            throw failure(new FailureReason.NotFound());
        }
        return aggregate;
    }

    private Aggregate requireMutableAggregate(String tenant, String deploymentId) throws SQLException {
        Aggregate aggregate = requireAggregate(tenant, deploymentId);
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

    // Fencing precedes lease liveness (ADR 0038 D0 rule 3): a fenced-out caller is told so before
    // anything about the current holder, because answering out of a liveness check first would let a
    // fenced caller learn it merely lost a race rather than that its authority is gone for good.
    private void ownerGuard(Aggregate aggregate, Lease lease, Instant now) {
        Record record = aggregate.record();
        if (!record.tenantId().equals(lease.tenantId()) || !record.deploymentId().equals(lease.deploymentId())) {
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
                && record.lease().owner().equals(lease.owner()) && record.lease().expiresAt().isAfter(now);
    }

    private void evidenceTime(Record record, Instant at, Instant now) {
        if (at.isBefore(record.createdAt()) || at.isAfter(now)) {
            throw invalid("incoherent evidence timestamp");
        }
    }

    private void ttl(Duration value) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(limits.maximumLeaseTtl()) > 0) {
            throw invalid("ttl");
        }
    }

    private Record replay(LedgerEntry prior, String digest) {
        if (!prior.digest().equals(digest)) {
            throw failure(new FailureReason.Conflict());
        }
        return prior.record();
    }

    private Record persist(Aggregate next, Action action, String key, String digest, Instant now)
            throws SQLException {
        Instant commandExpiresAt = commandExpiry(now);
        saveAggregate(next);
        insertLedgerEntry(next.record().tenantId(), next.record().deploymentId().value(), action, key, digest,
                next.record(), now, commandExpiresAt);
        return next.record();
    }

    // ---------------------------------------------------------------- rows: deployment aggregate

    private Aggregate loadAggregate(String tenant, String deploymentId) throws SQLException {
        String sql = "SELECT * FROM deployment WHERE tenant_id = ? AND deployment_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Record record = recordFromDeploymentRow(rows, tenant, deploymentId);
                return new Aggregate(record, rows.getLong("fence"));
            }
        }
    }

    private Record recordFromDeploymentRow(ResultSet rows, String tenant, String deploymentId) throws SQLException {
        long latestVersion = rows.getLong("latest_version");
        long generation = rows.getLong("generation");
        long revision = rows.getLong("revision");
        Desired desired = desiredFrom(rows, "", generation);
        Observation observed = observationFrom(rows, "");
        Failure failure = failureFrom(rows, "");
        Tombstone tombstone = tombstoneFrom(rows, "");
        Instant createdAt = StoredInstant.read(rows, "created_at");
        Instant updatedAt = StoredInstant.read(rows, "updated_at");
        Lease lease = loadLease(tenant, deploymentId);
        return new Record(tenant, DeploymentId.of(deploymentId), latestVersion, generation, revision, desired,
                observed, lease, failure, tombstone, createdAt, updatedAt);
    }

    private Lease loadLease(String tenant, String deploymentId) throws SQLException {
        String sql = "SELECT owner, fence, acquired_at_epoch_second, acquired_at_nano, "
                + "expires_at_epoch_second, expires_at_nano FROM deployment_lease "
                + "WHERE tenant_id = ? AND deployment_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return leaseFrom(rows, "", tenant, deploymentId);
            }
        }
    }

    private void insertDeploymentRow(String tenant, String deploymentId, Instant now) throws SQLException {
        String sql = "INSERT INTO deployment (tenant_id, deployment_id, latest_version, generation, "
                + "revision, fence, desired_kind, desired_version, update_strategy, observed_kind, "
                + "observed_version, observed_generation, observed_at_epoch_second, observed_at_nano, "
                + "failure_code, failure_message, failure_at_epoch_second, failure_at_nano, "
                + "tombstone_reason, tombstone_at_epoch_second, tombstone_at_nano, created_at_epoch_second, "
                + "created_at_nano, updated_at_epoch_second, updated_at_nano) "
                + "VALUES (?, ?, 1, 0, 1, 0, ?, NULL, NULL, ?, NULL, 0, ?, ?, NULL, NULL, NULL, NULL, NULL, "
                + "NULL, NULL, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, tenant);
            statement.setString(i++, deploymentId);
            statement.setString(i++, DesiredKind.STOPPED.name());
            statement.setString(i++, ObservedKind.COLD.name());
            i = StoredInstant.bindValue(statement, i, now);
            i = StoredInstant.bindValue(statement, i, now);
            StoredInstant.bindValue(statement, i, now);
            statement.executeUpdate();
        }
    }

    private void saveAggregate(Aggregate aggregate) throws SQLException {
        Record r = aggregate.record();
        String tenant = r.tenantId();
        String deploymentId = r.deploymentId().value();
        String sql = "UPDATE deployment SET latest_version = ?, generation = ?, revision = ?, fence = ?, "
                + "desired_kind = ?, desired_version = ?, update_strategy = ?, observed_kind = ?, "
                + "observed_version = ?, observed_generation = ?, observed_at_epoch_second = ?, "
                + "observed_at_nano = ?, failure_code = ?, failure_message = ?, failure_at_epoch_second = ?, "
                + "failure_at_nano = ?, tombstone_reason = ?, tombstone_at_epoch_second = ?, "
                + "tombstone_at_nano = ?, updated_at_epoch_second = ?, updated_at_nano = ? "
                + "WHERE tenant_id = ? AND deployment_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setLong(i++, r.latestVersion());
            statement.setLong(i++, r.generation());
            statement.setLong(i++, r.revision());
            statement.setLong(i++, aggregate.fence());
            statement.setString(i++, r.desired().kind().name());
            i = setNullableLong(statement, i, r.desired().desiredVersion());
            statement.setString(i++, r.desired().updateStrategy() == null ? null : r.desired().updateStrategy().name());
            statement.setString(i++, r.observed().state().name());
            i = setNullableLong(statement, i, r.observed().activeVersion());
            statement.setLong(i++, r.observed().observedGeneration());
            i = StoredInstant.bindValue(statement, i, r.observed().observedAt());
            if (r.failure() != null) {
                statement.setString(i++, r.failure().code());
                statement.setString(i++, r.failure().message());
                i = StoredInstant.bindValue(statement, i, r.failure().at());
            } else {
                statement.setString(i++, null);
                statement.setString(i++, null);
                i = bindNullableInstant(statement, i, null);
            }
            if (r.tombstone() != null) {
                statement.setString(i++, r.tombstone().reason());
                i = StoredInstant.bindValue(statement, i, r.tombstone().at());
            } else {
                statement.setString(i++, null);
                i = bindNullableInstant(statement, i, null);
            }
            i = StoredInstant.bindValue(statement, i, r.updatedAt());
            statement.setString(i++, tenant);
            statement.setString(i, deploymentId);
            statement.executeUpdate();
        }
        if (r.lease() != null) {
            upsertLease(tenant, deploymentId, r.lease());
        } else {
            deleteLease(tenant, deploymentId);
        }
    }

    private void upsertLease(String tenant, String deploymentId, Lease lease) throws SQLException {
        // An upsert rather than INSERT OR REPLACE, on the model SqliteExecutionStore already
        // establishes: REPLACE deletes the row first, which would needlessly cascade against any
        // future child table and is observable as a delete-then-insert to anything watching this row.
        String sql = "INSERT INTO deployment_lease (tenant_id, deployment_id, owner, fence, "
                + "acquired_at_epoch_second, acquired_at_nano, expires_at_epoch_second, expires_at_nano) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id, deployment_id) DO UPDATE SET owner = excluded.owner, "
                + "fence = excluded.fence, acquired_at_epoch_second = excluded.acquired_at_epoch_second, "
                + "acquired_at_nano = excluded.acquired_at_nano, "
                + "expires_at_epoch_second = excluded.expires_at_epoch_second, "
                + "expires_at_nano = excluded.expires_at_nano";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, tenant);
            statement.setString(i++, deploymentId);
            statement.setString(i++, lease.owner());
            statement.setLong(i++, lease.fence());
            i = StoredInstant.bindValue(statement, i, lease.acquiredAt());
            StoredInstant.bindValue(statement, i, lease.expiresAt());
            statement.executeUpdate();
        }
    }

    private void deleteLease(String tenant, String deploymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM deployment_lease WHERE tenant_id = ? AND deployment_id = ?")) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            statement.executeUpdate();
        }
    }

    private boolean deploymentExists(String tenant, String deploymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM deployment WHERE tenant_id = ? AND deployment_id = ? LIMIT 1")) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ---------------------------------------------------------------- rows: graph versions

    private void insertVersionRow(String tenant, String deploymentId, long version, GraphVersion.Content content)
            throws SQLException {
        byte[] canonical = content.canonicalSnapshot();
        byte[] digest = sha256(canonical);
        String sql = "INSERT INTO deployment_version (tenant_id, deployment_id, version, format_version, "
                + "canonical_bytes, digest, author, created_at_epoch_second, created_at_nano) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, tenant);
            statement.setString(i++, deploymentId);
            statement.setLong(i++, version);
            statement.setInt(i++, content.snapshotFormatVersion());
            statement.setBytes(i++, canonical);
            statement.setBytes(i++, digest);
            statement.setString(i++, content.createdBy());
            StoredInstant.bindValue(statement, i, content.createdAt());
            statement.executeUpdate();
        }
    }

    private boolean versionExists(String tenant, String deploymentId, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM deployment_version WHERE tenant_id = ? AND deployment_id = ? AND version = ? "
                        + "LIMIT 1")) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            statement.setLong(3, version);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ---------------------------------------------------------------- rows: command ledger

    private LedgerEntry loadLedgerEntry(String tenant, String deploymentId, Action action, String key)
            throws SQLException {
        String sql = ledgerColumns() + " FROM deployment_command WHERE tenant_id = ? AND deployment_id = ? "
                + "AND action = ? AND command_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, deploymentId);
            statement.setString(3, action.name());
            statement.setString(4, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new LedgerEntry(rows.getString("digest"), recordFromLedgerRow(rows, tenant, deploymentId));
            }
        }
    }

    private CreateLedgerEntry loadCreateLedgerEntry(String tenant, String key) throws SQLException {
        String sql = "SELECT deployment_id, " + ledgerColumns().substring("SELECT ".length())
                + " FROM deployment_command WHERE tenant_id = ? AND action = 'CREATE' AND command_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String deploymentId = rows.getString("deployment_id");
                return new CreateLedgerEntry(deploymentId, rows.getString("digest"),
                        recordFromLedgerRow(rows, tenant, deploymentId));
            }
        }
    }

    private static String ledgerColumns() {
        return "SELECT digest, recorded_latest_version, recorded_generation, recorded_revision, "
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
    }

    private static Record recordFromLedgerRow(ResultSet rows, String tenant, String deploymentId)
            throws SQLException {
        long latestVersion = rows.getLong("recorded_latest_version");
        long generation = rows.getLong("recorded_generation");
        long revision = rows.getLong("recorded_revision");
        Desired desired = desiredFrom(rows, "recorded_", generation);
        Observation observed = observationFrom(rows, "recorded_");
        Failure failure = failureFrom(rows, "recorded_");
        Tombstone tombstone = tombstoneFrom(rows, "recorded_");
        Instant createdAt = StoredInstant.read(rows, "recorded_created_at");
        Instant updatedAt = StoredInstant.read(rows, "recorded_updated_at");
        Lease lease = leaseFrom(rows, "recorded_lease_", tenant, deploymentId);
        return new Record(tenant, DeploymentId.of(deploymentId), latestVersion, generation, revision, desired,
                observed, lease, failure, tombstone, createdAt, updatedAt);
    }

    private void insertLedgerEntry(String tenant, String deploymentId, Action action, String key, String digest,
                                   Record record, Instant recordedAt, Instant expiresAt) throws SQLException {
        String sql = "INSERT INTO deployment_command (tenant_id, deployment_id, action, command_key, digest, "
                + "recorded_latest_version, recorded_generation, recorded_revision, recorded_desired_kind, "
                + "recorded_desired_version, recorded_update_strategy, recorded_observed_kind, "
                + "recorded_observed_version, recorded_observed_generation, recorded_observed_at_epoch_second, "
                + "recorded_observed_at_nano, recorded_lease_owner, recorded_lease_fence, "
                + "recorded_lease_acquired_at_epoch_second, recorded_lease_acquired_at_nano, "
                + "recorded_lease_expires_at_epoch_second, recorded_lease_expires_at_nano, "
                + "recorded_failure_code, recorded_failure_message, recorded_failure_at_epoch_second, "
                + "recorded_failure_at_nano, recorded_tombstone_reason, recorded_tombstone_at_epoch_second, "
                + "recorded_tombstone_at_nano, recorded_created_at_epoch_second, recorded_created_at_nano, "
                + "recorded_updated_at_epoch_second, recorded_updated_at_nano, recorded_at_epoch_second, "
                + "recorded_at_nano, expires_at_epoch_second, expires_at_nano) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, tenant);
            statement.setString(i++, deploymentId);
            statement.setString(i++, action.name());
            statement.setString(i++, key);
            statement.setString(i++, digest);
            statement.setLong(i++, record.latestVersion());
            statement.setLong(i++, record.generation());
            statement.setLong(i++, record.revision());
            statement.setString(i++, record.desired().kind().name());
            i = setNullableLong(statement, i, record.desired().desiredVersion());
            statement.setString(i++, record.desired().updateStrategy() == null
                    ? null : record.desired().updateStrategy().name());
            statement.setString(i++, record.observed().state().name());
            i = setNullableLong(statement, i, record.observed().activeVersion());
            statement.setLong(i++, record.observed().observedGeneration());
            i = StoredInstant.bindValue(statement, i, record.observed().observedAt());
            if (record.lease() != null) {
                statement.setString(i++, record.lease().owner());
                statement.setLong(i++, record.lease().fence());
                i = StoredInstant.bindValue(statement, i, record.lease().acquiredAt());
                i = StoredInstant.bindValue(statement, i, record.lease().expiresAt());
            } else {
                statement.setString(i++, null);
                i = setNullableLong(statement, i, null);
                i = bindNullableInstant(statement, i, null);
                i = bindNullableInstant(statement, i, null);
            }
            if (record.failure() != null) {
                statement.setString(i++, record.failure().code());
                statement.setString(i++, record.failure().message());
                i = StoredInstant.bindValue(statement, i, record.failure().at());
            } else {
                statement.setString(i++, null);
                statement.setString(i++, null);
                i = bindNullableInstant(statement, i, null);
            }
            if (record.tombstone() != null) {
                statement.setString(i++, record.tombstone().reason());
                i = StoredInstant.bindValue(statement, i, record.tombstone().at());
            } else {
                statement.setString(i++, null);
                i = bindNullableInstant(statement, i, null);
            }
            i = StoredInstant.bindValue(statement, i, record.createdAt());
            i = StoredInstant.bindValue(statement, i, record.updatedAt());
            i = StoredInstant.bindValue(statement, i, recordedAt);
            StoredInstant.bindValue(statement, i, expiresAt);
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- column codecs shared by both tables

    private static Desired desiredFrom(ResultSet rows, String prefix, long generation) throws SQLException {
        DesiredKind kind = DesiredKind.valueOf(rows.getString(prefix + "desired_kind"));
        Long version = nullableLong(rows, prefix + "desired_version");
        String strategyRaw = rows.getString(prefix + "update_strategy");
        UpdateStrategy strategy = strategyRaw == null ? null : UpdateStrategy.valueOf(strategyRaw);
        return new Desired(kind, version, strategy, generation);
    }

    private static Observation observationFrom(ResultSet rows, String prefix) throws SQLException {
        ObservedKind kind = ObservedKind.valueOf(rows.getString(prefix + "observed_kind"));
        Long version = nullableLong(rows, prefix + "observed_version");
        long observedGeneration = rows.getLong(prefix + "observed_generation");
        Instant observedAt = StoredInstant.read(rows, prefix + "observed_at");
        return new Observation(kind, version, observedGeneration, observedAt);
    }

    private static Lease leaseFrom(ResultSet rows, String prefix, String tenant, String deploymentId)
            throws SQLException {
        String owner = rows.getString(prefix + "owner");
        if (owner == null) {
            return null;
        }
        long fence = rows.getLong(prefix + "fence");
        Instant acquiredAt = StoredInstant.read(rows, prefix + "acquired_at");
        Instant expiresAt = StoredInstant.read(rows, prefix + "expires_at");
        return new Lease(tenant, DeploymentId.of(deploymentId), owner, fence, acquiredAt, expiresAt);
    }

    private static Failure failureFrom(ResultSet rows, String prefix) throws SQLException {
        String code = rows.getString(prefix + "failure_code");
        if (code == null) {
            return null;
        }
        String message = rows.getString(prefix + "failure_message");
        Instant at = StoredInstant.read(rows, prefix + "failure_at");
        return new Failure(code, message, at);
    }

    private static Tombstone tombstoneFrom(ResultSet rows, String prefix) throws SQLException {
        String reason = rows.getString(prefix + "tombstone_reason");
        if (reason == null) {
            return null;
        }
        Instant at = StoredInstant.read(rows, prefix + "tombstone_at");
        return new Tombstone(reason, at);
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static int setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
        return index + 1;
    }

    private static int bindNullableInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
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

    // Byte-for-byte the same scheme InMemoryDeploymentRegistry uses: "rr1\0tenant\0lastId", base64url,
    // unpadded. A cursor a caller received from one adapter must be usable against another backing the
    // same tenant, so the wire form is not this adapter's to reinvent.
    private static String encodeCursor(String tenant, String lastId) {
        String raw = CURSOR_VERSION + "\u0000" + tenant + "\u0000" + lastId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
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

    // ---------------------------------------------------------------- connection and transactions

    private Connection open() {
        try {
            location.prepare();
        } catch (RuntimeException refused) {
            throw invalid("cannot prepare the store directory for " + databaseFile + ": "
                    + refused.getMessage());
        }
        Connection opened;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        } catch (SQLException failed) {
            throw invalid("cannot open the deployment registry at " + databaseFile + ": "
                    + failed.getMessage());
        }
        try (Statement statement = opened.createStatement()) {
            String journalMode;
            try (ResultSet rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                journalMode = rows.next() ? rows.getString(1) : "unknown";
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw invalid("the database at " + databaseFile + " refused write-ahead logging and "
                        + "reported '" + journalMode + "'");
            }
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            statement.execute("PRAGMA foreign_keys=ON");
            SqliteSchema.migrate(opened, clock);
            return opened;
        } catch (SQLException | RuntimeException failed) {
            try {
                opened.close();
            } catch (SQLException ignored) {
                // The failure that got us here is the one worth reporting.
            }
            if (failed instanceof RegistryException classified) {
                throw classified;
            }
            throw invalid("cannot prepare the deployment registry at " + databaseFile + ": "
                    + failed.getMessage());
        }
    }

    private <T> T inReadTransaction(SqlWork<T> work) {
        return transact(work, false);
    }

    private <T> T inWriteTransaction(SqlWork<T> work) {
        return transact(work, true);
    }

    /** One transaction per operation; writers open with {@code BEGIN IMMEDIATE}. */
    private <T> T transact(SqlWork<T> work, boolean write) {
        try (Statement control = connection.createStatement()) {
            control.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            T result;
            try {
                result = work.run();
            } catch (SQLException failed) {
                rollbackQuietly(control);
                throw mapSqlFailure(failed);
            } catch (RuntimeException failed) {
                rollbackQuietly(control);
                throw failed;
            }
            try {
                control.execute("COMMIT");
            } catch (SQLException failed) {
                rollbackQuietly(control);
                throw mapSqlFailure(failed);
            }
            return result;
        } catch (SQLException failed) {
            throw mapSqlFailure(failed);
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
     * Maps a storage fault onto the only member of the sealed {@code FailureReason} that can carry it.
     *
     * <p>See the class Javadoc's note: {@code FailureReason} has no store-fault member, so an adapter
     * that genuinely could not answer is reported as {@code InvalidRequest}, which a caller branching
     * on it will read as "your request was malformed". That is a compromise and is flagged rather than
     * hidden; closing it means adding a member to a sealed type that every exhaustive switch outside
     * this repository already covers.</p>
     *
     * <p>What the compromise does <em>not</em> extend to is the text. The driver's message is dropped
     * and a classifier substituted, on the rule this repository already states for
     * {@code DurableExecutionResult} and {@code DeploymentCommandOutcome.Failed}: a message may carry
     * bound parameter fragments, credentials or author-controlled text, and this value reaches the
     * caller and the log. The database path is dropped for the same reason — the adapter knows which
     * file it opened and the caller has no use for it, while a filesystem path in a caller-facing
     * message is exactly the kind of detail an authority should not volunteer.</p>
     */
    private RegistryException mapSqlFailure(SQLException failed) {
        int code = failed.getErrorCode();
        if (code == SQLITE_CORRUPT || code == SQLITE_NOTADB) {
            return invalid("the deployment registry database is not readable as a SQLite database");
        }
        return invalid("deployment registry storage fault: " + failed.getClass().getName());
    }

    private Instant now() {
        return clock.instant();
    }

    private Instant commandExpiry(Instant now) {
        return expiry(now, commandRetention, "command retention expiry");
    }

    private static Instant leaseExpiry(Instant now, Duration ttl) {
        return expiry(now, ttl, "lease expiry");
    }

    private static Instant expiry(Instant now, Duration duration, String contract) {
        try {
            return now.plus(duration);
        } catch (DateTimeException | ArithmeticException unrepresentable) {
            throw invalid(contract + " is outside the supported instant range");
        }
    }

    private static int busyTimeoutMillis(Duration timeout) {
        Objects.requireNonNull(timeout, "busyTimeout");
        try {
            long millis = timeout.toMillis();
            if (timeout.isNegative() || millis > Integer.MAX_VALUE
                    || !Duration.ofMillis(millis).equals(timeout)) {
                throw new IllegalArgumentException(
                        "busyTimeout must be a whole number of milliseconds in range 0..2147483647");
            }
            return Math.toIntExact(millis);
        } catch (ArithmeticException unrepresentable) {
            throw new IllegalArgumentException(
                    "busyTimeout must be a whole number of milliseconds in range 0..2147483647");
        }
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

    private <T> T onWorker(Work<T> work) {
        try {
            return worker.submit(work::run).get(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw invalid("interrupted while waiting for the store connection");
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw invalid(String.valueOf(cause));
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw invalid("the store connection did not respond");
        }
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    /** A deployment's current {@code Record} paired with its fence counter, which {@code Record} does not carry. */
    private record Aggregate(Record record, long fence) {
    }

    /** One ledger row's recorded digest and the exact {@code Record} snapshot it produced. */
    private record LedgerEntry(String digest, Record record) {
    }

    /** A create-ledger row: the minted deployment id, alongside what {@link LedgerEntry} already carries. */
    private record CreateLedgerEntry(String deploymentId, String digest, Record record) {
    }
}
