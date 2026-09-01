package ai.ravenroot.server.assistant;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The consent register, on disk, as required by the consent contract.
 *
 * <h2>Why SQLite, and why not a new mechanism</h2>
 * <p>Because it is what this product already uses for state that must survive a restart:
 * {@code ravenroot-persistence-sqlite} is a compile dependency of this module, its driver is already
 * on the classpath, and its adapter already establishes the settings that make a commit actually
 * durable — WAL, {@code synchronous=FULL}, a busy timeout so cross-process contention is a wait rather
 * than an error. Inventing a second durable-state mechanism for one table would mean a second set of
 * answers to crash-safety, concurrency and backup, and the second set is the one nobody tests.</p>
 *
 * <h2>Why its own database file and not a table in the execution store</h2>
 * <p>This is the decision most likely to look like duplication, so it is worth the paragraph. The
 * execution store is <b>operationally disposable by design</b>, in two specific ways that a consent
 * record cannot tolerate:</p>
 * <ul>
 *   <li>{@code ExecutionStoreConfiguration.ENABLED_VARIABLE} switches it off. An operator who sets
 *       {@code RAVENROOT_EXECUTION_STORE_ENABLED=false} intends to stop persisting executions; if the
 *       consent rows lived there, that variable would also silently erase every author's recorded
 *       refusal — and the failure mode of a <em>lost refusal</em> is context being sent, not context
 *       being withheld.</li>
 *   <li>{@code SqliteStoreLocation#restoreFrom} replaces the whole file with a backup. Restoring
 *       yesterday's execution store to recover an audit trail would roll the consent register back to
 *       yesterday too: consents since revoked would return, and consents since granted would vanish.
 *       Neither is something an operator restoring executions is choosing.</li>
 * </ul>
 * <p>A consent record is a statement about what an author permitted, not a cache of one. It must
 * outlive the retention decisions taken about execution data, so it is kept in a file whose lifecycle
 * is its own.</p>
 *
 * <h2>Failing closed</h2>
 * <p>Every read here throws rather than returning a default when the database cannot be reached. The
 * caller is {@code AssistantService#send}, which consults this register <em>before</em> composing a
 * request, so a throw ends the turn with nothing sent. The alternative — answering an unreachable
 * register with an empty set — is quieter and worse: it is indistinguishable from a genuine refusal,
 * so an operator would see authors mysteriously losing context rather than an error naming the
 * database.</p>
 */
public final class SqliteAssistantConsentStore implements AssistantConsentStore, AutoCloseable {

    /** Where the register lives. Its own directory, for the reason this class's Javadoc gives. */
    public static final String DIRECTORY_VARIABLE = "RAVENROOT_ASSISTANT_CONSENT_DIR";

    /**
     * Relative on purpose, and the reason is a container fact worth stating rather than relying on.
     *
     * <p>The image sets {@code WORKDIR /opt/ravenroot} and {@code compose.yaml} mounts the persistent
     * volume at {@code /opt/ravenroot/data}, so this default resolves <em>inside</em> that volume: an
     * operator who backs up the data volume takes the consent register with it without being told to,
     * and the same is true of {@code RAVENROOT_EXECUTION_STORE_DIR} and {@code RAVENROOT_AUDIT_DIR},
     * which use the same convention.</p>
     *
     * <p><b>That property depends on the WORKDIR and would break silently if it changed</b> — the
     * register would land outside the volume and be discarded on the next container replacement, with
     * no error and no missing file, just every author's recorded choice quietly gone. It is written
     * here so the next person to move the WORKDIR has a chance of seeing what it carries. The
     * operator-facing half of this note belongs in the deployment variable table; see
     * the documented contract, which tracks it.</p>
     */
    private static final String DEFAULT_DIRECTORY = "./data/assistant-consent";

    /** Spelled out so two deployments agree on the file without configuring its name. */
    public static final String FILE_NAME = "ravenroot-assistant-consent.db";

    /** The highest schema version this binary knows. A file above it is refused, never opened. */
    private static final int SCHEMA_VERSION = 1;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS assistant_consent (
                subject       TEXT NOT NULL,
                provider      TEXT NOT NULL,
                context_class TEXT NOT NULL,
                granted_at    TEXT NOT NULL,
                PRIMARY KEY (subject, provider, context_class)
            ) WITHOUT ROWID
            """;

    private final Connection connection;
    private final Clock clock;

    private SqliteAssistantConsentStore(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** The register the operator's environment names, or the default location beside it. */
    public static SqliteAssistantConsentStore fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(DIRECTORY_VARIABLE);
        Path directory;
        try {
            directory = Path.of(configured == null || configured.isBlank()
                    ? DEFAULT_DIRECTORY : configured.trim());
        } catch (RuntimeException invalidPath) {
            // Deliberately not carrying the cause: an InvalidPathException repeats the raw environment
            // value, and the only useful answer at startup is that this setting is unusable. The same
            // reasoning ExecutionStoreConfiguration applies to its own directory variable.
            throw new IllegalArgumentException("Invalid assistant consent directory configuration");
        }
        return openUnder(directory);
    }

    /** The register in {@code directory}, creating the directory and the schema if absent. */
    public static SqliteAssistantConsentStore openUnder(Path directory) {
        return openUnder(directory, Clock.systemUTC());
    }

    static SqliteAssistantConsentStore openUnder(Path directory, Clock clock) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(clock, "clock");
        Path databaseFile = directory.toAbsolutePath().normalize().resolve(FILE_NAME);
        try {
            java.nio.file.Files.createDirectories(databaseFile.getParent());
        } catch (java.io.IOException failed) {
            throw new IllegalStateException(
                    "cannot create the assistant consent directory " + databaseFile.getParent(), failed);
        }
        Connection opened = null;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            prepare(opened);
            return new SqliteAssistantConsentStore(opened, clock);
        } catch (SQLException failed) {
            closeQuietly(opened);
            throw new IllegalStateException(
                    "cannot open the assistant consent register at " + databaseFile, failed);
        } catch (RuntimeException failed) {
            closeQuietly(opened);
            throw failed;
        }
    }

    /**
     * Connection settings and schema, in the order they have to happen.
     *
     * <p>{@code synchronous=FULL} is not conservatism: under {@code NORMAL} in WAL mode a commit is not
     * fsynced, so a host failure between commit and checkpoint loses a consent decision the author was
     * told had been recorded. {@code SqliteStoreConfig} states the same reasoning for the execution
     * store, and a consent decision is the wrong place to be less careful than an execution record.</p>
     */
    private static void prepare(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            try (ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                mode.next();
            }
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        int version = userVersion(connection);
        if (version > SCHEMA_VERSION) {
            // The same downgrade guard SqliteSchema applies, for the same reason: an older binary
            // cannot see columns it does not know about, so it would write rows the newer binary reads
            // as incomplete. Refusing to open is loud and reversible; opening is silent and is not.
            throw new IllegalStateException("the assistant consent register was written by a newer "
                    + "build (schema version " + version + ", this build knows " + SCHEMA_VERSION + ")");
        }
        if (version < SCHEMA_VERSION) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
                // Written inside the same transaction as the DDL it describes, so the file can never be
                // structurally changed but labelled with the old version.
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();
                throw failed;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    @Override
    public Set<AssistantContextClass> consentedClasses(String subject, String provider) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(provider, "provider");
        var granted = new LinkedHashSet<AssistantContextClass>();
        synchronized (connection) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT context_class FROM assistant_consent WHERE subject = ? AND provider = ?")) {
                query.setString(1, subject);
                query.setString(2, provider);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        // A row naming a class this build no longer has is skipped rather than fatal.
                        // Consent to a context class that no longer exists authorizes sending data that
                        // no longer exists either, so dropping it grants nothing; failing the read
                        // instead would take the whole assistant down over a retired enum constant.
                        classNamed(rows.getString(1)).ifPresent(granted::add);
                    }
                }
            } catch (SQLException failed) {
                throw new IllegalStateException(
                        "cannot read the assistant consent register; refusing to compose a turn "
                                + "without knowing what this author consented to", failed);
            }
        }
        return Set.copyOf(granted);
    }

    /**
     * Records exactly what this author consents to send to this provider, replacing whatever stood
     * before.
     *
     * <p><b>Replace, not merge</b>, and it is the whole write API on purpose. A register with separate
     * {@code grant} and {@code revoke} verbs has to answer what happens when they interleave, and the
     * author is not expressing a delta — they are looking at a list of classes with boxes ticked and
     * saying "this is what I permit". Passing the empty set is therefore a complete revocation, and it
     * needs no second method.</p>
     *
     * <p>Not on {@link AssistantConsentStore}: the composer holds that interface and must not be able
     * to record consent on the author's behalf. See that interface's Javadoc.</p>
     */
    public void recordConsent(String subject, String provider, Set<AssistantContextClass> classes) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(classes, "classes");
        var recorded = classes.isEmpty()
                ? EnumSet.noneOf(AssistantContextClass.class) : EnumSet.copyOf(classes);
        String at = clock.instant().toString();
        synchronized (connection) {
            try {
                connection.setAutoCommit(false);
                // One transaction, so an author's choice is never observable half-applied: a reader
                // between the delete and the inserts would otherwise see a revocation that was really
                // a narrowing.
                try (PreparedStatement clear = connection.prepareStatement(
                        "DELETE FROM assistant_consent WHERE subject = ? AND provider = ?")) {
                    clear.setString(1, subject);
                    clear.setString(2, provider);
                    clear.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO assistant_consent (subject, provider, context_class, granted_at) "
                                + "VALUES (?, ?, ?, ?)")) {
                    for (AssistantContextClass consented : recorded) {
                        insert.setString(1, subject);
                        insert.setString(2, provider);
                        insert.setString(3, consented.name());
                        insert.setString(4, at);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException failed) {
                rollbackQuietly();
                throw new IllegalStateException("cannot record assistant consent", failed);
            } finally {
                autoCommitQuietly();
            }
        }
    }

    @Override
    public void close() {
        synchronized (connection) {
            closeQuietly(connection);
        }
    }

    private static java.util.Optional<AssistantContextClass> classNamed(String stored) {
        if (stored == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(
                    AssistantContextClass.valueOf(stored.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The commit already failed; a rollback that also fails adds no information the caller
            // can act on, and masking the original failure with it would.
        }
    }

    private void autoCommitQuietly() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Same reasoning as rollbackQuietly: restoring the connection mode is best-effort cleanup.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing a caller can do about a connection that will not close.
        }
    }
}
