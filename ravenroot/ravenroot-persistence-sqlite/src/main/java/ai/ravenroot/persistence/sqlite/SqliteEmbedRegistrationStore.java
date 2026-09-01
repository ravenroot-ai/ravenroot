package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedGraphProjectionCodec;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedRegistrationRules;
import ai.ravenroot.api.embed.EmbedRegistrationState;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedRevokeOutcome;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.api.embed.VerifiedEmbedSessionGrant;
import ai.ravenroot.api.security.RequestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The durable embed registration authority for a packaged single-host deployment.
 *
 * <h2>Its own file, not a table in the execution store</h2>
 * <p>The execution store is operationally disposable by design — it can be switched off entirely and
 * {@link SqliteStoreLocation#restoreFrom} replaces the whole file with a backup. An operator who
 * restores an execution-store backup must not thereby resurrect embed registrations that were revoked
 * afterwards, which is precisely the failure a shared file would produce: the revocation is the thing
 * a rollback most needs to preserve. {@code SqliteUserCredentialStore} separates its file from the
 * execution store for the same class of reason.</p>
 *
 * <h2>Compare-and-set, and why {@code BEGIN IMMEDIATE} is load-bearing</h2>
 * <p>Every mutation reads the current row and writes the next one inside a single transaction opened
 * with {@code BEGIN IMMEDIATE}, so the write lock is held from before the read. A deferred
 * transaction would let two writers both read revision <em>n</em> and then race to upgrade, and the
 * loser's retry is a second chance to overwrite a decision it never saw. With the lock taken up
 * front, a second writer in another process waits out {@code busy_timeout} and then observes the
 * first writer's revision, so its expectation fails and it is told {@link EmbedProvisionOutcome.Conflict}
 * rather than silently winning. The {@code WHERE ... AND revision = ?} clause on the update is belt
 * and braces on top of that, not the mechanism.</p>
 *
 * <h2>What this adapter is not</h2>
 * <p><strong>It is not a shared store.</strong> SQLite is a local file; two replicas on two hosts do
 * not see each other's writes, and two replicas on one host sharing a volume get cross-process
 * mutual exclusion only for as long as the file lock semantics of that volume hold, which is exactly
 * what network filesystems do not guarantee. A process-local or single-host store must not be
 * presented as multi-replica, so the composition root refuses to enable the embed at more than one
 * replica instead of this class pretending otherwise. Sticky routing is not a substitute: it makes
 * reads land on the writer <em>usually</em>, and a revocation that is visible usually is not a
 * revocation.</p>
 *
 * <h2>Reconstruction is revalidation</h2>
 * <p>{@link #readRow} rebuilds every aggregate through {@link EmbedRegistrationAggregate}'s canonical
 * constructor and the codec's strict parse. A hand-edited row, a half-applied migration or a file
 * restored from another deployment therefore fails to load rather than becoming an aggregate whose
 * grant and payload disagree — the inconsistency this reconstruction rule prevents.</p>
 */
public final class SqliteEmbedRegistrationStore implements EmbedRegistrationAuthority, AutoCloseable {

    /** Spelled out so two deployments agree on the file without configuring its name. */
    public static final String FILE_NAME = "ravenroot-embed-registrations.db";

    /** The highest schema version this binary knows. A file above it is refused, never opened. */
    private static final int SCHEMA_VERSION = 1;

    /**
     * Seven gates in a fixed order, one character each.
     *
     * <p>A column per gate would be the textbook shape, but the gates are read and written only as a
     * set and are never queried individually, and a fixed-width string makes a partially-migrated row
     * fail the length check instead of defaulting the missing gate to {@code true}. The order is
     * frozen: appending a gate is a schema migration, not an edit to this constant.</p>
     */
    private static final int ELIGIBILITY_GATES = 7;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS embed_registration (
                registration_id    TEXT    NOT NULL PRIMARY KEY,
                revision           INTEGER NOT NULL,
                state              TEXT    NOT NULL,
                tenant_id          TEXT    NOT NULL,
                workload_issuer    TEXT    NOT NULL,
                workload_subject   TEXT    NOT NULL,
                parent_origin      TEXT    NOT NULL,
                capabilities       TEXT    NOT NULL,
                theme_override     TEXT,
                resource_id        TEXT    NOT NULL,
                deployment_id      TEXT    NOT NULL,
                deployment_version INTEGER NOT NULL,
                graph_id           TEXT    NOT NULL,
                graph_version_id   TEXT    NOT NULL,
                canonical_digest   TEXT    NOT NULL,
                policy_revision    TEXT    NOT NULL,
                snapshot_lifecycle TEXT    NOT NULL,
                eligibility_gates  TEXT    NOT NULL,
                projection_json    TEXT    NOT NULL,
                provisioned_at     TEXT    NOT NULL
            ) WITHOUT ROWID
            """;

    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS embed_registration_tenant "
                    + "ON embed_registration (tenant_id, registration_id)";

    private static final String SELECT_ALL_COLUMNS =
            "SELECT registration_id, revision, state, tenant_id, workload_issuer, workload_subject, "
                    + "parent_origin, capabilities, theme_override, resource_id, deployment_id, "
                    + "deployment_version, graph_id, graph_version_id, canonical_digest, policy_revision, "
                    + "snapshot_lifecycle, eligibility_gates, projection_json, provisioned_at "
                    + "FROM embed_registration WHERE registration_id = ?";

    private final Connection connection;
    private final Clock clock;
    private final EmbedProjectionBudget budget;
    private final CommitBoundary commitBoundary;
    private final Path databaseFile;

    private SqliteEmbedRegistrationStore(Connection connection, Clock clock, EmbedProjectionBudget budget,
                                         CommitBoundary commitBoundary, Path databaseFile) {
        this.connection = connection;
        this.clock = clock;
        this.budget = budget;
        this.commitBoundary = commitBoundary;
        this.databaseFile = databaseFile;
    }

    /** Opens (creating if absent) the registration store in {@code directory}. */
    public static SqliteEmbedRegistrationStore openUnder(Path directory) {
        return openUnder(directory, Clock.systemUTC(), EmbedProjectionBudget.DEFAULTS);
    }

    public static SqliteEmbedRegistrationStore openUnder(Path directory, Clock clock,
                                                         EmbedProjectionBudget budget) {
        return openUnder(directory, clock, budget, CommitBoundary.NONE);
    }

    static SqliteEmbedRegistrationStore openUnder(Path directory, Clock clock, EmbedProjectionBudget budget,
                                                  CommitBoundary commitBoundary) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(commitBoundary, "commitBoundary");
        Path databaseFile = directory.toAbsolutePath().normalize().resolve(FILE_NAME);
        try {
            Files.createDirectories(databaseFile.getParent());
        } catch (java.io.IOException failed) {
            throw new IllegalStateException(
                    "cannot create the embed registration directory " + databaseFile.getParent(), failed);
        }
        Connection opened = null;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            prepare(opened);
            restrictPermissions(databaseFile);
            return new SqliteEmbedRegistrationStore(opened, clock, budget, commitBoundary, databaseFile);
        } catch (SQLException failed) {
            closeQuietly(opened);
            throw new IllegalStateException(
                    "cannot open the embed registration store at " + databaseFile, failed);
        } catch (RuntimeException failed) {
            closeQuietly(opened);
            throw failed;
        }
    }

    private static void prepare(Connection opened) throws SQLException {
        try (Statement statement = opened.createStatement()) {
            String journalMode;
            try (ResultSet rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                journalMode = rows.next() ? rows.getString(1) : "unknown";
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw new IllegalStateException("the embed registration store refused WAL mode and "
                        + "reported '" + journalMode + "'; a durable revocation is not honourable "
                        + "without it");
            }
            // FULL, not NORMAL. A revocation reported as written and then lost to a host failure is
            // the one durability gap in this store that has a security consequence rather than an
            // operational one, so the fsync is not negotiable here even though it costs.
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
            int version;
            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                version = rows.next() ? rows.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new IllegalStateException("the embed registration store was written by a newer "
                        + "Ravenroot (schema " + version + " > " + SCHEMA_VERSION + ") and is not opened");
            }
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
            if (version < SCHEMA_VERSION) statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    /** Best effort, and claimed as nothing more; see {@code SqliteUserCredentialStore} for the limits. */
    private static void restrictPermissions(Path databaseFile) {
        try {
            Files.setPosixFilePermissions(databaseFile,
                    EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException notPosix) {
            // A non-POSIX filesystem is not a reason to refuse to run; it is a reason not to claim
            // the protection. The claim is made in the class comment, not here.
        }
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * The current aggregate for one registration under one tenant, for operator administration.
     *
     * <p>Adapter-local administration rather than a port method, the same line
     * {@link SqliteStoreLocation#restoreFrom} draws: a caller of the port is a browser boundary
     * resolving a session and has a workload identity to be compared against, while this answers «what
     * revision am I about to compare-and-set against», which only an operator asks and only of a
     * store they can already read. It is still tenant-scoped, so it cannot be used to enumerate
     * another tenant's registrations, and it never returns a revoked aggregate's payload to a caller
     * that asked about a different tenant.</p>
     *
     * <p>It deliberately does not authorize: {@code EmbedRegistrationCommand} in the CLI holds the
     * operator identity and the audit trail, and putting a second, weaker authorization decision here
     * would be a second answer to a question that already has one.</p>
     */
    public Optional<EmbedRegistrationAggregate> currentForOperator(String tenantId, String registrationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(registrationId, "registrationId");
        try {
            EmbedRegistrationAggregate current = readRow(registrationId);
            return current == null || !current.tenantId().equals(tenantId)
                    ? Optional.empty() : Optional.of(current);
        } catch (SQLException unavailable) {
            throw new IllegalStateException("the embed registration store could not be read", unavailable);
        }
    }

    // ---------------------------------------------------------------- port

    @Override
    public EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
        Objects.requireNonNull(command, "command");
        EmbedProvisionOutcome.Reason refusal = EmbedRegistrationRules.rejectionOf(command, budget);
        if (refusal != null) return new EmbedProvisionOutcome.Rejected(refusal);
        Instant now = clock.instant();
        try {
            return inWriteTransaction(() -> {
                EmbedRegistrationAggregate current = readRow(command.registrationId());
                if (current == null) {
                    if (command.expectedRevision() != 0) {
                        return new EmbedProvisionOutcome.Conflict(command.expectedRevision(), 0);
                    }
                } else {
                    if (!current.tenantId().equals(command.tenantId())) {
                        return new EmbedProvisionOutcome.Rejected(
                                EmbedProvisionOutcome.Reason.IDENTITY_INCOHERENT);
                    }
                    if (!current.active()) {
                        return new EmbedProvisionOutcome.Rejected(
                                EmbedProvisionOutcome.Reason.REGISTRATION_REVOKED);
                    }
                    if (current.revision() != command.expectedRevision()) {
                        return new EmbedProvisionOutcome.Conflict(command.expectedRevision(),
                                current.revision());
                    }
                }
                EmbedRegistrationAggregate written = command.aggregateAt(now);
                writeRow(written, current != null);
                return new EmbedProvisionOutcome.Provisioned(written);
            });
        } catch (SQLException | RuntimeException failed) {
            return EmbedProvisionOutcome.Unavailable.INSTANCE;
        }
    }

    @Override
    public EmbedRevokeOutcome revoke(EmbedRevokeCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        try {
            return inWriteTransaction(() -> {
                EmbedRegistrationAggregate current = readRow(command.registrationId());
                if (current == null || !current.tenantId().equals(command.tenantId())) {
                    return EmbedRevokeOutcome.NotFound.INSTANCE;
                }
                if (!current.active()) return new EmbedRevokeOutcome.AlreadyRevoked(current.revision());
                if (current.revision() != command.expectedRevision()) {
                    return new EmbedRevokeOutcome.Conflict(command.expectedRevision(), current.revision());
                }
                EmbedRegistrationAggregate revoked = current.revokedAt(current.revision() + 1, now);
                writeRow(revoked, true);
                return new EmbedRevokeOutcome.Revoked(revoked.revision());
            });
        } catch (SQLException | RuntimeException failed) {
            return EmbedRevokeOutcome.Unavailable.INSTANCE;
        }
    }

    @Override
    public EmbedRegistrationResolution resolveCurrent(RequestContext workload, String registrationId) {
        Objects.requireNonNull(workload, "workload");
        if (registrationId == null || registrationId.isBlank()) {
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }
        EmbedRegistrationAggregate current;
        try {
            current = readRow(registrationId);
        } catch (SQLException unavailable) {
            return EmbedRegistrationResolution.Temporary.INSTANCE;
        } catch (RuntimeException corrupted) {
            // A row that will not reconstruct is not a temporary condition and must not invite a
            // retry loop; it is an unusable registration and stays refused until an operator acts.
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }
        if (current == null || !current.active()) return EmbedRegistrationResolution.Unavailable.INSTANCE;
        VerifiedEmbedSessionGrant grant = current.sessionGrant();
        if (!grant.tenantId().equals(workload.tenantId())
                || !grant.workloadIssuer().equals(workload.issuer())
                || !grant.workloadSubject().equals(workload.subject())) {
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }
        return new EmbedRegistrationResolution.Available(current);
    }

    @Override
    public boolean isCurrent(EmbedRegistrationAggregate captured) {
        if (captured == null || !captured.active()) return false;
        try {
            return captured.sameRevisionAs(readRow(captured.registrationId()));
        } catch (SQLException | RuntimeException unavailable) {
            // Fail closed: a store that cannot confirm currency has not confirmed it.
            return false;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing is best effort; every commit was already fsynced.
        }
    }

    // ---------------------------------------------------------------- rows

    private EmbedRegistrationAggregate readRow(String registrationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL_COLUMNS)) {
            statement.setString(1, registrationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return aggregateOf(rows);
            }
        }
    }

    private static EmbedRegistrationAggregate aggregateOf(ResultSet rows) throws SQLException {
        String gates = rows.getString("eligibility_gates");
        if (gates == null || gates.length() != ELIGIBILITY_GATES) {
            throw new IllegalStateException("the stored eligibility gate set has an unexpected width");
        }
        var eligibility = new EmbedProjectionEligibility(rows.getString("policy_revision"),
                gate(gates, 0), gate(gates, 1), gate(gates, 2), gate(gates, 3),
                gate(gates, 4), gate(gates, 5), gate(gates, 6));
        var graphGrant = new VerifiedEmbedGraphGrant(rows.getString("tenant_id"),
                rows.getString("resource_id"), rows.getString("deployment_id"),
                rows.getLong("deployment_version"), rows.getString("graph_id"),
                rows.getString("graph_version_id"), rows.getString("canonical_digest"),
                rows.getString("policy_revision"));
        Set<EmbedCapability> capabilities = capabilitiesOf(rows.getString("capabilities"));
        String theme = rows.getString("theme_override");
        var sessionGrant = new VerifiedEmbedSessionGrant(rows.getString("registration_id"),
                rows.getLong("revision"), rows.getString("workload_issuer"),
                rows.getString("workload_subject"), rows.getString("tenant_id"),
                rows.getString("parent_origin"), capabilities, graphGrant,
                theme == null ? Optional.empty() : Optional.of(EmbedTheme.fromWire(theme)));
        EmbedGraphProjection projection =
                EmbedGraphProjectionCodec.decode(rows.getString("projection_json"));
        return new EmbedRegistrationAggregate(rows.getString("registration_id"), rows.getLong("revision"),
                EmbedRegistrationState.valueOf(rows.getString("state")), sessionGrant,
                EmbedSnapshotLifecycle.valueOf(rows.getString("snapshot_lifecycle")), eligibility,
                projection, Instant.parse(rows.getString("provisioned_at")));
    }

    private static boolean gate(String gates, int index) {
        char value = gates.charAt(index);
        // Only '1' is true and only '0' is false. Anything else is a row this store did not write.
        if (value == '1') return true;
        if (value == '0') return false;
        throw new IllegalStateException("the stored eligibility gate set is not a bit string");
    }

    private static Set<EmbedCapability> capabilitiesOf(String encoded) {
        var capabilities = EnumSet.noneOf(EmbedCapability.class);
        for (String name : encoded.split(",")) {
            if (!name.isBlank()) capabilities.add(EmbedCapability.valueOf(name.trim()));
        }
        return Set.copyOf(capabilities);
    }

    private void writeRow(EmbedRegistrationAggregate aggregate, boolean replacing) throws SQLException {
        if (replacing) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM embed_registration WHERE registration_id = ? AND revision < ?")) {
                delete.setString(1, aggregate.registrationId());
                delete.setLong(2, aggregate.revision());
                if (delete.executeUpdate() != 1) {
                    // The row we read a moment ago inside this same IMMEDIATE transaction is gone or
                    // has moved. That cannot happen under the write lock; if it did, the transaction
                    // is not what it appears to be and the safe answer is to abort it.
                    throw new IllegalStateException("the registration row moved inside a write transaction");
                }
            }
        }
        VerifiedEmbedSessionGrant grant = aggregate.sessionGrant();
        VerifiedEmbedGraphGrant graph = aggregate.graphGrant();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO embed_registration (registration_id, revision, state, tenant_id, "
                        + "workload_issuer, workload_subject, parent_origin, capabilities, theme_override, "
                        + "resource_id, deployment_id, deployment_version, graph_id, graph_version_id, "
                        + "canonical_digest, policy_revision, snapshot_lifecycle, eligibility_gates, "
                        + "projection_json, provisioned_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, aggregate.registrationId());
            insert.setLong(2, aggregate.revision());
            insert.setString(3, aggregate.state().name());
            insert.setString(4, grant.tenantId());
            insert.setString(5, grant.workloadIssuer());
            insert.setString(6, grant.workloadSubject());
            insert.setString(7, grant.parentOrigin());
            insert.setString(8, encodeCapabilities(grant.capabilities()));
            insert.setString(9, grant.themeOverride().map(EmbedTheme::wireValue).orElse(null));
            insert.setString(10, graph.resourceId());
            insert.setString(11, graph.deploymentId());
            insert.setLong(12, graph.deploymentVersion());
            insert.setString(13, graph.graphId());
            insert.setString(14, graph.graphVersionId());
            insert.setString(15, graph.canonicalDigest());
            insert.setString(16, graph.projectionPolicyRevision());
            insert.setString(17, aggregate.snapshotLifecycle().name());
            insert.setString(18, encodeGates(aggregate.eligibility()));
            insert.setString(19, EmbedGraphProjectionCodec.encode(aggregate.projection()));
            insert.setString(20, aggregate.provisionedAt().toString());
            insert.executeUpdate();
        }
    }

    private static String encodeCapabilities(Set<EmbedCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String encodeGates(EmbedProjectionEligibility eligibility) {
        return bit(eligibility.deploymentAllowed()) + bit(eligibility.provenanceAllowed())
                + bit(eligibility.classificationAllowed()) + bit(eligibility.retentionAllowed())
                + bit(eligibility.dsrSuppressionClear()) + bit(eligibility.takedownClear())
                + bit(eligibility.eeaDeployment());
    }

    private static String bit(boolean value) {
        return value ? "1" : "0";
    }

    // ---------------------------------------------------------------- transaction

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    private <T> T inWriteTransaction(Work<T> work) throws SQLException {
        try (Statement control = connection.createStatement()) {
            control.execute("BEGIN IMMEDIATE");
            T result;
            try {
                result = work.run();
            } catch (SQLException | RuntimeException failed) {
                rollbackQuietly(control);
                throw failed;
            }
            commitBoundary.beforeCommit();
            control.execute("COMMIT");
            commitBoundary.afterCommit();
            return result;
        }
    }

    private static void rollbackQuietly(Statement control) {
        try {
            control.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // The failure that brought us here is the one worth reporting.
        }
    }

    private static void closeQuietly(Connection opened) {
        if (opened == null) return;
        try {
            opened.close();
        } catch (SQLException ignored) {
            // As above.
        }
    }
}
