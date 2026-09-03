package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphDefinitionStoreException;
import ai.ravenroot.api.persistence.GraphDefinitionStoreFailure;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredGraphDefinition;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable {@link GraphDefinitionStore} over the same SQLite database the execution store uses.
 *
 * <h2>Why the same database file, and not a second one</h2>
 * <p>Three consequences follow from co-location, and each of them is load-bearing. A backup of the
 * execution store is one transactionally consistent snapshot of one file, so definitions ride along
 * in it automatically rather than being a second artefact an operator must remember to capture in
 * the same instant. Retention can ask whether any instance still pins a definition <em>inside the
 * transaction that removes it</em>, which is what makes reachability a decision rather than a race
 * against a concurrent acceptance. And the schema version that describes both is a single number, so
 * a binary can never open a file whose executions it understands and whose definitions it does not.</p>
 *
 * <p>The connection is this adapter's own, confined to one thread, exactly as the execution store
 * confines its own. Two connections to one file is what write-ahead logging is for: readers proceed
 * during a write, and two writers serialise on the file's write lock with the bounded wait
 * {@code busy_timeout} sets rather than failing immediately.</p>
 *
 * <h2>Verification</h2>
 * <p>Every read hashes the stored bytes and compares the result to the address the row is filed
 * under. The redundant {@code digest} column is not a second copy for its own sake: it separates
 * <em>the bytes changed</em>, which is a definite verdict about definite content, from <em>this row
 * is internally inconsistent</em>, which is a corrupt row and a different operator problem.</p>
 */
public final class SqliteGraphDefinitionStore implements GraphDefinitionStore {

    /**
     * Matches the ceiling GraphML ingest already enforces on a submitted document. A smaller bound
     * here would accept a document at the edge and then fail to persist it.
     */
    public static final int DEFAULT_MAX_DEFINITION_BYTES = 10 * 1024 * 1024;

    private static final int SQLITE_PERM = 3;
    private static final int SQLITE_READONLY = 8;
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_AUTH = 23;
    private static final int SQLITE_NOTADB = 26;

    private final SqliteStoreLocation location;
    private final Path databaseFile;
    private final Clock clock;
    private final GraphDefinitionReferences references;
    private final int maxDefinitionBytes;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Connection connection;

    /**
     * Opens the definition store held in the execution store's database file.
     *
     * @param databaseFile the execution store database this adapter shares.
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle composed with this store's own reachability query before a removal.
     */
    public SqliteGraphDefinitionStore(Path databaseFile, Clock clock,
                                      GraphDefinitionReferences references) {
        this(SqliteStoreLocation.ofFile(databaseFile), clock, references, DEFAULT_MAX_DEFINITION_BYTES);
    }

    /**
     * Opens the definition store at an explicit store location.
     *
     * @param location the execution store database this adapter shares.
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle composed with this store's own reachability query before a removal.
     */
    public SqliteGraphDefinitionStore(SqliteStoreLocation location, Clock clock,
                                      GraphDefinitionReferences references) {
        this(location, clock, references, DEFAULT_MAX_DEFINITION_BYTES);
    }

    /**
     * Opens the definition store with an explicit definition bound.
     *
     * @param location the execution store database this adapter shares.
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle composed with this store's own reachability query before a removal.
     * @param maxDefinitionBytes largest canonical definition this instance accepts.
     */
    public SqliteGraphDefinitionStore(SqliteStoreLocation location, Clock clock,
                                      GraphDefinitionReferences references, int maxDefinitionBytes) {
        this.location = Objects.requireNonNull(location, "location");
        this.databaseFile = location.databaseFile();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
        if (maxDefinitionBytes < 1) {
            throw new IllegalArgumentException("maxDefinitionBytes must be positive");
        }
        this.maxDefinitionBytes = maxDefinitionBytes;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ravenroot-sqlite-definitions-"
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
    public Set<StoreCapability> capabilities() {
        // DURABLE and nothing else. Every other member of the vocabulary describes an execution-store
        // facility this port does not offer, and declaring one to look better furnished would assert
        // a behaviour no assertion could then check.
        return Set.of(StoreCapability.DURABLE);
    }

    @Override
    public int maxDefinitionBytes() {
        return maxDefinitionBytes;
    }

    @Override
    public CompletionStage<StoredGraphDefinition> put(String tenantId, GraphDefinitionIdentity identity,
                                                      CanonicalGraphMl canonical) {
        return async(() -> {
            requireTenantId(tenantId);
            require(identity != null, "identity cannot be null");
            require(canonical != null, "canonical GraphML cannot be null");
            // Decided from the request alone, so it happens before the write lock is taken and before
            // anything could have been written.
            if (canonical.size() > maxDefinitionBytes) {
                throw failure(new GraphDefinitionStoreFailure.DefinitionTooLarge(
                        canonical.size(), maxDefinitionBytes));
            }
            var key = new GraphDefinitionKey(tenantId, canonical.contentId());
            // The overwhelmingly common case for an accepted execution is that this exact document is
            // already stored and already bound: a deployment accepts its thousandth traversal, or a
            // caller resubmits a graph it has run before. Deciding that in a read transaction keeps it
            // off the file's single write lock -- which the execution store beside this one needs for
            // the acceptance that follows -- and keeps a document of up to the size limit from being
            // read back and re-hashed once per acceptance.
            //
            // It is only a fast path, never an authority: every branch that must change something
            // returns null and the write transaction below re-reads under the lock, so a row inserted
            // between the two is seen there rather than raced.
            StoredGraphDefinition alreadyStored =
                    inReadTransaction(key, () -> storedAndBound(key, identity, canonical));
            if (alreadyStored != null) {
                return alreadyStored;
            }
            return inWriteTransaction(key, () -> {
                GraphContentId bound = readBinding(tenantId, identity);
                if (bound != null && !bound.equals(canonical.contentId())) {
                    throw failure(new GraphDefinitionStoreFailure.IdentityConflict(
                            tenantId, identity, bound, canonical.contentId()));
                }
                Row existing = readRow(key);
                Row row;
                if (existing == null) {
                    row = insertDefinition(key, identity, canonical, clock.instant());
                } else {
                    // A repeated write must not overwrite corruption into looking healthy, and must
                    // not restamp content that was already durable.
                    verify(key, existing);
                    row = existing;
                }
                if (bound == null) {
                    insertBinding(tenantId, identity, canonical.contentId(), clock.instant());
                }
                return row.stored(key, row.identity());
            });
        });
    }

    @Override
    public CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key) {
        return async(() -> {
            require(key != null, "key cannot be null");
            return inReadTransaction(key, () -> {
                Row row = readRow(key);
                if (row == null) {
                    throw failure(new GraphDefinitionStoreFailure.NotFound(key));
                }
                verify(key, row);
                return row.stored(key, row.identity());
            });
        });
    }

    @Override
    public CompletionStage<StoredGraphDefinition> resolve(String tenantId, GraphDefinitionIdentity identity) {
        return async(() -> {
            requireTenantId(tenantId);
            require(identity != null, "identity cannot be null");
            return inReadTransaction(null, () -> {
                GraphContentId bound = readBinding(tenantId, identity);
                if (bound == null) {
                    throw failure(new GraphDefinitionStoreFailure.NotFound(
                            new GraphDefinitionKey(tenantId, unboundAddress())));
                }
                var key = new GraphDefinitionKey(tenantId, bound);
                Row row = readRow(key);
                if (row == null) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "a graph version binding names content this store does not hold"));
                }
                verify(key, row);
                return row.stored(key, identity);
            });
        });
    }

    @Override
    public CompletionStage<Boolean> contains(GraphDefinitionKey key) {
        return async(() -> {
            require(key != null, "key cannot be null");
            return inReadTransaction(key, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM graph_definition WHERE tenant_id = ? AND content_id = ?")) {
                    statement.setString(1, key.tenantId());
                    statement.setString(2, key.contentId().value());
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next();
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<Void> remove(GraphDefinitionKey key) {
        return async(() -> {
            require(key != null, "key cannot be null");
            return inWriteTransaction(key, () -> {
                if (readRow(key) == null) {
                    throw failure(new GraphDefinitionStoreFailure.NotFound(key));
                }
                // Inside the removal transaction, so a concurrent acceptance either committed its pin
                // before this read saw it, or is still waiting for the write lock this transaction
                // holds. There is no window in which both observations are stale.
                if (isReferenced(key)) {
                    throw failure(new GraphDefinitionStoreFailure.StillReferenced(key));
                }
                deleteDefinition(key);
                return null;
            });
        });
    }

    @Override
    public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return inWriteTransaction(null, () -> {
                List<GraphContentId> candidates = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT content_id FROM graph_definition WHERE tenant_id = ?")) {
                    statement.setString(1, tenantId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            candidates.add(new GraphContentId(rows.getString("content_id")));
                        }
                    }
                }
                long removed = 0;
                for (GraphContentId candidate : candidates) {
                    var key = new GraphDefinitionKey(tenantId, candidate);
                    if (!isReferenced(key)) {
                        deleteDefinition(key);
                        removed++;
                    }
                }
                return removed;
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
            // The connection is being abandoned either way; a failure here must not mask the reason
            // the caller is closing.
        } finally {
            worker.shutdown();
        }
    }

    // ---------------------------------------------------------------- reachability

    /**
     * Whether any retained work still reaches this definition. The two sources are a conjunction: a
     * definition is removable only when both say unreachable.
     */
    private boolean isReferenced(GraphDefinitionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM process_instance WHERE tenant_id = ? AND graph_version_pin = ? LIMIT 1")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return true;
                }
            }
        }
        return references.isReferenced(key);
    }

    // ---------------------------------------------------------------- rows

    /**
     * The definition as stored, when it is already stored under this exact content and already bound
     * to this exact version, and {@code null} whenever anything must change.
     *
     * <p>Reads no document bytes and computes no digest over them. It does compare the digest
     * recorded beside the definition against the address the row is filed under, which is 32 bytes
     * and catches an internally inconsistent row; it does not re-derive the digest from the content,
     * which is the check every read performs at the moment the content is actually handed out.</p>
     */
    private StoredGraphDefinition storedAndBound(GraphDefinitionKey key, GraphDefinitionIdentity identity,
                                                 CanonicalGraphMl canonical) throws SQLException {
        StoredMeta meta = readMeta(key);
        if (meta == null) {
            return null;
        }
        if (meta.formatVersion() != canonical.formatVersion()) {
            // Stored under a different canonical-form version than the caller presents. Not a fast
            // path: the write transaction reads the real row and answers from what is stored.
            return null;
        }
        if (!HexFormat.of().formatHex(meta.digest()).equals(key.contentId().value())) {
            throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                    "the digest recorded beside the definition does not match the address it is "
                            + "filed under"));
        }
        GraphContentId bound = readBinding(key.tenantId(), identity);
        if (!canonical.contentId().equals(bound)) {
            // Either unbound, which needs a write, or bound elsewhere, which is a conflict the write
            // transaction decides under the lock rather than from a snapshot.
            return null;
        }
        // The caller's own value carries the bytes; they hash to this address by construction, so
        // returning it copies nothing and hashes nothing.
        return new StoredGraphDefinition(key, meta.identity(), canonical, meta.storedAt());
    }

    /** The small columns of one definition row: never the document, never a hash of it. */
    private StoredMeta readMeta(GraphDefinitionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format_version, digest, first_graph_id, first_version_id, "
                        + "stored_at_epoch_second, stored_at_nano "
                        + "FROM graph_definition WHERE tenant_id = ? AND content_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                byte[] digest = rows.getBytes("digest");
                int formatVersion = rows.getInt("format_version");
                if (digest == null || formatVersion < 1) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition row is missing its digest or carries an illegal "
                                    + "format version"));
                }
                GraphDefinitionIdentity identity;
                try {
                    identity = new GraphDefinitionIdentity(rows.getString("first_graph_id"),
                            rows.getString("first_version_id"));
                } catch (IllegalArgumentException illegal) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition names an illegal graph version identity"));
                }
                return new StoredMeta(formatVersion, digest, identity,
                        StoredInstant.read(rows, "stored_at"));
            }
        }
    }

    private Row readRow(GraphDefinitionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format_version, definition_bytes, digest, byte_length, first_graph_id, "
                        + "first_version_id, stored_at_epoch_second, stored_at_nano "
                        + "FROM graph_definition WHERE tenant_id = ? AND content_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                int formatVersion = rows.getInt("format_version");
                byte[] bytes = rows.getBytes("definition_bytes");
                byte[] digest = rows.getBytes("digest");
                long byteLength = rows.getLong("byte_length");
                String graphId = rows.getString("first_graph_id");
                String versionId = rows.getString("first_version_id");
                Instant storedAt = StoredInstant.read(rows, "stored_at");
                if (bytes == null || digest == null || formatVersion < 1) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition row is missing content or carries an illegal "
                                    + "format version"));
                }
                if (bytes.length != byteLength) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition is " + bytes.length + " bytes but the row records "
                                    + byteLength));
                }
                GraphDefinitionIdentity identity;
                try {
                    identity = new GraphDefinitionIdentity(graphId, versionId);
                } catch (IllegalArgumentException illegal) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition names an illegal graph version identity"));
                }
                return new Row(formatVersion, bytes, digest, identity, storedAt);
            }
        }
    }

    private Row insertDefinition(GraphDefinitionKey key, GraphDefinitionIdentity identity,
                                 CanonicalGraphMl canonical, Instant storedAt) throws SQLException {
        byte[] bytes = canonical.bytes();
        byte[] digest = sha256(bytes);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_definition (tenant_id, content_id, format_version, definition_bytes, "
                        + "digest, byte_length, first_graph_id, first_version_id, "
                        + "stored_at_epoch_second, stored_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            statement.setInt(3, canonical.formatVersion());
            statement.setBytes(4, bytes);
            statement.setBytes(5, digest);
            statement.setLong(6, bytes.length);
            statement.setString(7, identity.graphId());
            statement.setString(8, identity.versionId());
            StoredInstant.bindValue(statement, 9, storedAt);
            statement.executeUpdate();
        }
        return new Row(canonical.formatVersion(), bytes, digest, identity, storedAt);
    }

    private GraphContentId readBinding(String tenantId, GraphDefinitionIdentity identity)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT content_id FROM graph_definition_binding "
                        + "WHERE tenant_id = ? AND graph_id = ? AND version_id = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, identity.graphId());
            statement.setString(3, identity.versionId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new GraphContentId(rows.getString("content_id")) : null;
            }
        }
    }

    private void insertBinding(String tenantId, GraphDefinitionIdentity identity,
                               GraphContentId contentId, Instant boundAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_definition_binding (tenant_id, graph_id, version_id, content_id, "
                        + "bound_at_epoch_second, bound_at_nano) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, tenantId);
            statement.setString(2, identity.graphId());
            statement.setString(3, identity.versionId());
            statement.setString(4, contentId.value());
            StoredInstant.bindValue(statement, 5, boundAt);
            statement.executeUpdate();
        }
    }

    /** Bindings go with the content through {@code ON DELETE CASCADE}. */
    private void deleteDefinition(GraphDefinitionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM graph_definition WHERE tenant_id = ? AND content_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            statement.executeUpdate();
        }
    }

    private static void verify(GraphDefinitionKey key, Row row) {
        byte[] observed = sha256(row.bytes());
        if (!Arrays.equals(observed, row.digest())) {
            throw failure(new GraphDefinitionStoreFailure.DigestMismatch(key,
                    HexFormat.of().formatHex(observed)));
        }
        String recorded = HexFormat.of().formatHex(row.digest());
        if (!recorded.equals(key.contentId().value())) {
            throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                    "the digest recorded beside the definition does not match the address it is "
                            + "filed under"));
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static GraphContentId unboundAddress() {
        return new GraphContentId("0".repeat(64));
    }

    // ---------------------------------------------------------------- connection

    private Connection open() {
        // Before JDBC, because SQLite reports the same code for a missing path, an exhausted
        // descriptor table and a directory the process may not enter.
        try {
            location.prepare();
        } catch (RuntimeException refused) {
            throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "cannot prepare the store directory for " + databaseFile + ": "
                            + refused.getMessage()), refused);
        }
        Connection opened;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        } catch (SQLException failed) {
            throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "cannot open the graph definition store at " + databaseFile + ": "
                            + failed.getMessage()), failed);
        }
        try (Statement statement = opened.createStatement()) {
            // Asserted, not requested. This adapter declares DURABLE and shares a file with a writer
            // that holds the same expectation; a database that silently fell back to a rollback
            // journal would make a commit here a different operation than the one the execution store
            // beside it performs, and the divergence would only be visible after a crash.
            String journalMode;
            try (ResultSet rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                journalMode = rows.next() ? rows.getString(1) : "unknown";
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                        "the database at " + databaseFile + " refused write-ahead logging and reported '"
                                + journalMode + "'; DURABLE is not honourable without it"));
            }
            // Two connections write to this file: a definition commit and an execution commit take the
            // same lock in turn. Without a busy timeout the second would fail immediately instead of
            // waiting, turning ordinary contention into a refused acceptance.
            statement.execute("PRAGMA busy_timeout=" + SqliteStoreConfig.defaults().busyTimeout().toMillis());
            // The definition-to-binding cascade depends on this, and it is off by default in SQLite.
            statement.execute("PRAGMA foreign_keys=ON");
            SqliteSchema.migrate(opened, clock);
            return opened;
        } catch (SQLException | RuntimeException failed) {
            try {
                opened.close();
            } catch (SQLException ignored) {
                // The failure that got us here is the one worth reporting.
            }
            if (failed instanceof GraphDefinitionStoreException classified) {
                throw classified;
            }
            throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "cannot prepare the graph definition store at " + databaseFile + ": "
                            + failed.getMessage()), failed);
        }
    }

    private <T> T inReadTransaction(GraphDefinitionKey key, SqlWork<T> work) {
        return transact(key, work, false);
    }

    private <T> T inWriteTransaction(GraphDefinitionKey key, SqlWork<T> work) {
        return transact(key, work, true);
    }

    /**
     * One transaction per operation. Writers use {@code BEGIN IMMEDIATE} so the write lock is taken
     * when the transaction opens rather than at its first write, which converts the whole class of
     * lock-upgrade deadlocks into a bounded wait on {@code busy_timeout}.
     */
    private <T> T transact(GraphDefinitionKey key, SqlWork<T> work, boolean write) {
        try (Statement control = connection.createStatement()) {
            control.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            T result;
            try {
                result = work.run();
            } catch (SQLException failed) {
                rollbackQuietly(control);
                throw mapBeforeCommit(failed);
            } catch (RuntimeException failed) {
                rollbackQuietly(control);
                throw failed;
            }
            try {
                control.execute("COMMIT");
            } catch (SQLException failed) {
                rollbackQuietly(control);
                // A COMMIT that failed mid-flight is neither known-applied nor known-not-applied from
                // here. Because the write is content-addressed, re-reading the address settles it.
                throw key == null
                        ? new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                                "commit failed: " + failed.getMessage()), failed)
                        : new GraphDefinitionStoreException(
                                new GraphDefinitionStoreFailure.OutcomeUnknown(key,
                                        "commit failed: " + failed.getMessage()), failed);
            }
            return result;
        } catch (SQLException failed) {
            throw mapBeforeCommit(failed);
        }
    }

    private static void rollbackQuietly(Statement control) {
        try {
            control.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // The transaction is already gone; the original failure is the one worth reporting.
        }
    }

    /** Classifies a failure raised before the commit, where nothing was applied. */
    private GraphDefinitionStoreException mapBeforeCommit(SQLException failed) {
        int code = failed.getErrorCode();
        if (code == SQLITE_CORRUPT || code == SQLITE_NOTADB) {
            return new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "the database at " + databaseFile + " is not readable as a SQLite database"), failed);
        }
        if (code == SQLITE_PERM || code == SQLITE_AUTH || code == SQLITE_READONLY) {
            return new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.NotAuthorized(
                    "the database at " + databaseFile + " is not writable by this process"), failed);
        }
        return new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                failed.getMessage()), failed);
    }

    private static void requireTenantId(String tenantId) {
        require(tenantId != null && !tenantId.isBlank(), "tenantId cannot be blank");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw failure(new GraphDefinitionStoreFailure.InvalidRequest(reason));
        }
    }

    private static GraphDefinitionStoreException failure(GraphDefinitionStoreFailure classified) {
        return new GraphDefinitionStoreException(classified);
    }

    private <T> CompletionStage<T> async(java.util.function.Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.Unavailable("this graph definition store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }

    private <T> T onWorker(Work<T> work) {
        try {
            return worker.submit(work::run).get(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "interrupted while waiting for the store connection"), interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.Unavailable(String.valueOf(cause)), cause);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "the store connection did not respond"), timedOut);
        }
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    /** Everything about a stored definition except the document itself. */
    private record StoredMeta(int formatVersion, byte[] digest, GraphDefinitionIdentity identity,
                              Instant storedAt) {
    }

    private record Row(int formatVersion, byte[] bytes, byte[] digest,
                       GraphDefinitionIdentity identity, Instant storedAt) {

        StoredGraphDefinition stored(GraphDefinitionKey key, GraphDefinitionIdentity under) {
            return new StoredGraphDefinition(key, under,
                    CanonicalGraphMl.of(formatVersion, bytes), storedAt);
        }
    }
}
