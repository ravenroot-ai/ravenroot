package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestDigest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredExecutionManifest;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable {@link ExecutionManifestStore} over the same SQLite database the execution store uses.
 *
 * <h2>Why the same database file</h2>
 * <p>The three consequences {@link SqliteGraphDefinitionStore} states apply unchanged and are the
 * reason this adapter shares the file rather than opening its own: one backup captures an execution
 * together with the manifest it needs, retention can ask whether an instance still exists inside the
 * transaction that removes its manifest, and one schema version describes both so a binary cannot
 * open a file whose executions it understands and whose manifests it does not.</p>
 *
 * <h2>No foreign key to {@code process_instance}, deliberately</h2>
 * <p>The manifest is committed <em>before</em> the acceptance that references it, which is the
 * ordering the port requires. A foreign key to {@code process_instance} would make that ordering
 * impossible to express: the parent row does not exist yet. The relationship is therefore enforced
 * in the other direction, at removal, by asking whether the instance exists — which is the same
 * reachability question {@link SqliteGraphDefinitionStore} asks and, unlike a constraint, is one a
 * pinned-then-abandoned manifest can legitimately answer "no" to.</p>
 *
 * <h2>Normalized rows, not a serialized blob</h2>
 * <p>The manifest is stored as columns and a child table rather than as an encoded object, for the
 * reason the schema states for the execution aggregate: a blob makes the on-disk format an encoding
 * of a Java type, so the record cannot change shape without a data migration, and nothing on disk is
 * queryable or inspectable by an operator. It also makes
 * {@link ExecutionManifestStoreFailure.Corrupted} detectable, because reconstruction runs through the
 * manifest's own canonical constructors.</p>
 *
 * <h2>Verification</h2>
 * <p>Every read reconstructs the manifest from its rows, re-derives the digest from the
 * reconstructed fields, and compares that to the {@code digest} column. The redundant column
 * separates <em>a field changed</em>, a definite verdict about definite content, from <em>this row
 * cannot be read back as a manifest</em>, which is a corrupt row and a different operator problem.</p>
 */
public final class SqliteExecutionManifestStore implements ExecutionManifestStore {

    private static final int SQLITE_PERM = 3;
    private static final int SQLITE_READONLY = 8;
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_AUTH = 23;
    private static final int SQLITE_NOTADB = 26;

    private final SqliteStoreLocation location;
    private final Path databaseFile;
    private final Clock clock;
    private final ExecutionManifestReferences references;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Connection connection;

    /**
     * Opens the manifest store over an existing execution store database file.
     *
     * @param databaseFile the execution store database this adapter shares.
     * @param clock time authority for the instant a manifest is durably recorded.
     * @param references oracle composed with this store's own reachability query before a removal.
     */
    public SqliteExecutionManifestStore(Path databaseFile, Clock clock,
                                        ExecutionManifestReferences references) {
        this(SqliteStoreLocation.ofFile(databaseFile), clock, references);
    }

    /**
     * Opens the manifest store at an explicit store location.
     *
     * @param location the execution store database this adapter shares.
     * @param clock time authority for the instant a manifest is durably recorded.
     * @param references oracle composed with this store's own reachability query before a removal.
     */
    public SqliteExecutionManifestStore(SqliteStoreLocation location, Clock clock,
                                        ExecutionManifestReferences references) {
        this.location = Objects.requireNonNull(location, "location");
        this.databaseFile = location.databaseFile();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ravenroot-sqlite-manifests-"
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
        // DURABLE and nothing else, for the reason the definition store gives: every other member of
        // the vocabulary describes an execution-store facility this port does not offer.
        return Set.of(StoreCapability.DURABLE);
    }

    @Override
    public CompletionStage<StoredExecutionManifest> pin(ExecutionManifest manifest) {
        return async(() -> {
            if (manifest == null) {
                throw failure(new ExecutionManifestStoreFailure.InvalidRequest("manifest cannot be null"));
            }
            ExecutionKey key = manifest.key();
            ExecutionManifestDigest digest = manifest.digest();
            return inWriteTransaction(key, () -> {
                Existing existing = readDigest(key);
                if (existing != null) {
                    if (!existing.digest().equals(digest.value())) {
                        throw failure(new ExecutionManifestStoreFailure.ManifestConflict(key,
                                new ExecutionManifestDigest(existing.digest())));
                    }
                    return new StoredExecutionManifest(manifest, digest, existing.committedAt());
                }
                Instant now = Instant.now(clock);
                insert(manifest, digest, now);
                return new StoredExecutionManifest(manifest, digest, now);
            });
        });
    }

    @Override
    public CompletionStage<StoredExecutionManifest> load(ExecutionKey key) {
        return async(() -> {
            requireKey(key);
            return inReadTransaction(key, () -> {
                StoredExecutionManifest stored = readManifest(key);
                if (stored == null) {
                    throw failure(new ExecutionManifestStoreFailure.NotFound(key));
                }
                return stored;
            });
        });
    }

    @Override
    public CompletionStage<Boolean> contains(ExecutionKey key) {
        return async(() -> {
            requireKey(key);
            return inReadTransaction(key, () -> readDigest(key) != null);
        });
    }

    @Override
    public CompletionStage<Void> remove(ExecutionKey key) {
        return async(() -> {
            requireKey(key);
            return inWriteTransaction(key, () -> {
                if (readDigest(key) == null) {
                    throw failure(new ExecutionManifestStoreFailure.NotFound(key));
                }
                // Recomputed inside the removal transaction, so an acceptance committing concurrently
                // cannot land between the question and the delete.
                if (instanceExists(key) || references.isReferenced(key)) {
                    throw failure(new ExecutionManifestStoreFailure.StillReferenced(key));
                }
                deleteManifest(key);
                return (Void) null;
            });
        });
    }

    @Override
    public CompletionStage<Long> purgeUnreferencedManifests(String tenantId) {
        return async(() -> {
            if (tenantId == null || tenantId.isBlank()) {
                throw failure(new ExecutionManifestStoreFailure.InvalidRequest("tenantId cannot be blank"));
            }
            return inWriteTransaction(null, () -> {
                var candidates = new ArrayList<UUID>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT process_instance_id FROM execution_manifest WHERE tenant_id = ?")) {
                    statement.setString(1, tenantId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            // Through the one decoder every persisted identity in this module goes
                            // through, so a corrupt id is classified rather than parsed leniently
                            // here. A reclamation pass deliberately does not delete such a row: it
                            // cannot prove the row is unreferenced without an identity to ask about,
                            // and silently deleting durable state it could not read would be the one
                            // outcome worse than refusing.
                            candidates.add(decodeInstanceId(rows, tenantId));
                        }
                    }
                }
                long removed = 0;
                for (UUID candidate : candidates) {
                    var key = new ExecutionKey(tenantId, candidate);
                    if (!instanceExists(key) && !references.isReferenced(key)) {
                        deleteManifest(key);
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
            // Closing twice, or closing a connection the driver already dropped, is not actionable.
        } finally {
            worker.shutdown();
        }
    }

    // ---------------------------------------------------------------- rows

    private Existing readDigest(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT digest, committed_at_epoch_second, committed_at_nano FROM execution_manifest "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Existing(rows.getString(1),
                        Instant.ofEpochSecond(rows.getLong(2), rows.getInt(3)));
            }
        }
    }

    private boolean instanceExists(ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM process_instance WHERE tenant_id = ? AND process_instance_id = ? LIMIT 1")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private StoredExecutionManifest readManifest(ExecutionKey key) throws SQLException {
        String recordedDigest;
        ExecutionManifest manifest;
        Instant committedAt;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format_version, digest, graph_content_id, graph_id, version_id, "
                        + "graph_schema_version, definition_format_version, execution_policy, "
                        + "unknown_behavior_mode, engine_digest, store_digest, limits_digest, "
                        + "program_runtime_digest, pinned_at_epoch_second, pinned_at_nano, "
                        + "committed_at_epoch_second, committed_at_nano FROM execution_manifest "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                recordedDigest = rows.getString(2);
                committedAt = Instant.ofEpochSecond(rows.getLong(16), rows.getInt(17));
                try {
                    var profile = new ResolvedRuntimeProfile(rows.getInt(6), rows.getInt(7),
                            rows.getString(8), rows.getString(9), rows.getString(10),
                            rows.getString(11), rows.getString(12), rows.getString(13));
                    manifest = new ExecutionManifest(rows.getInt(1), key,
                            new GraphContentId(rows.getString(3)),
                            new GraphDefinitionIdentity(rows.getString(4), rows.getString(5)),
                            profile, readPackages(key),
                            Instant.ofEpochSecond(rows.getLong(14), rows.getInt(15)));
                } catch (IllegalArgumentException | NullPointerException malformed) {
                    throw failure(new ExecutionManifestStoreFailure.Corrupted(key,
                            String.valueOf(malformed.getMessage())));
                }
            }
        }
        ExecutionManifestDigest observed = manifest.digest();
        if (!observed.value().equals(recordedDigest)) {
            throw failure(new ExecutionManifestStoreFailure.DigestMismatch(key, observed.value()));
        }
        return new StoredExecutionManifest(manifest, observed, committedAt);
    }

    private List<PinnedNodePackage> readPackages(ExecutionKey key) throws SQLException {
        var pinned = new ArrayList<PinnedNodePackage>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT package_id, identity_digest FROM execution_manifest_package "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY package_id")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    pinned.add(new PinnedNodePackage(rows.getString(1), rows.getString(2)));
                }
            }
        }
        return pinned;
    }

    private void insert(ExecutionManifest manifest, ExecutionManifestDigest digest, Instant now)
            throws SQLException {
        ExecutionKey key = manifest.key();
        ResolvedRuntimeProfile runtime = manifest.runtime();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_manifest (tenant_id, process_instance_id, format_version, digest, "
                        + "graph_content_id, graph_id, version_id, graph_schema_version, "
                        + "definition_format_version, execution_policy, unknown_behavior_mode, "
                        + "engine_digest, store_digest, limits_digest, program_runtime_digest, "
                        + "pinned_at_epoch_second, pinned_at_nano, committed_at_epoch_second, "
                        + "committed_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            statement.setInt(3, manifest.formatVersion());
            statement.setString(4, digest.value());
            statement.setString(5, manifest.graphContentId().value());
            statement.setString(6, manifest.graphIdentity().graphId());
            statement.setString(7, manifest.graphIdentity().versionId());
            statement.setInt(8, runtime.graphSchemaVersion());
            statement.setInt(9, runtime.definitionFormatVersion());
            statement.setString(10, runtime.executionPolicy());
            statement.setString(11, runtime.unknownBehaviorMode());
            statement.setString(12, runtime.engineDigest());
            statement.setString(13, runtime.storeDigest());
            statement.setString(14, runtime.executionLimitsDigest());
            statement.setString(15, runtime.programRuntimeDigest());
            statement.setLong(16, manifest.pinnedAt().getEpochSecond());
            statement.setInt(17, manifest.pinnedAt().getNano());
            statement.setLong(18, now.getEpochSecond());
            statement.setInt(19, now.getNano());
            statement.executeUpdate();
        }
        if (manifest.nodePackages().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_manifest_package (tenant_id, process_instance_id, package_id, "
                        + "identity_digest) VALUES (?, ?, ?, ?)")) {
            for (PinnedNodePackage pinned : manifest.nodePackages()) {
                statement.setString(1, key.tenantId());
                statement.setString(2, key.processInstanceId().toString());
                statement.setString(3, pinned.packageId());
                statement.setString(4, pinned.identityDigest());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Decodes one stored process-instance id through this module's single decoder.
     *
     * <p>{@link StoredUuid} classifies a corrupt id as an <em>execution store</em> failure, which is
     * the right classification for the store it was written for and the wrong type to escape this
     * port: a caller composing all three stores must not catch one and silently absorb another. The
     * verdict is reused and the type is translated, rather than a second decoder being written that
     * could parse the same bytes differently.</p>
     */
    private UUID decodeInstanceId(ResultSet rows, String tenantId) throws SQLException {
        try {
            return StoredUuid.required(rows, "execution_manifest", "process_instance_id", tenantId);
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException corrupt) {
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Corrupted(
                    new ExecutionKey(tenantId, new UUID(0L, 0L)),
                    "stored execution_manifest.process_instance_id is not a canonical UUID"), corrupt);
        }
    }

    private void deleteManifest(ExecutionKey key) throws SQLException {
        // The child rows go through the cascade the schema declares; deleting the parent is enough
        // only because `PRAGMA foreign_keys=ON` is asserted when this connection opens.
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM execution_manifest WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.processInstanceId().toString());
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- connection

    private Connection open() {
        try {
            location.prepare();
        } catch (RuntimeException refused) {
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "cannot prepare the store directory for " + databaseFile + ": "
                            + refused.getMessage()), refused);
        }
        Connection opened;
        try {
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        } catch (SQLException failed) {
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "cannot open the execution manifest store at " + databaseFile + ": "
                            + failed.getMessage()), failed);
        }
        try (Statement statement = opened.createStatement()) {
            String journalMode;
            try (ResultSet rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                journalMode = rows.next() ? rows.getString(1) : "unknown";
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                        "the database at " + databaseFile + " refused write-ahead logging and reported '"
                                + journalMode + "'; DURABLE is not honourable without it"));
            }
            statement.execute("PRAGMA busy_timeout="
                    + SqliteStoreConfig.defaults().busyTimeout().toMillis());
            statement.execute("PRAGMA foreign_keys=ON");
            SqliteSchema.migrate(opened, clock);
            return opened;
        } catch (SQLException | RuntimeException failed) {
            try {
                opened.close();
            } catch (SQLException ignored) {
                // The failure that got us here is the one worth reporting.
            }
            if (failed instanceof ExecutionManifestStoreException classified) {
                throw classified;
            }
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "cannot prepare the execution manifest store at " + databaseFile + ": "
                            + failed.getMessage()), failed);
        }
    }

    private <T> T inReadTransaction(ExecutionKey key, SqlWork<T> work) {
        return transact(key, work, false);
    }

    private <T> T inWriteTransaction(ExecutionKey key, SqlWork<T> work) {
        return transact(key, work, true);
    }

    /** One transaction per operation; writers open with {@code BEGIN IMMEDIATE}. */
    private <T> T transact(ExecutionKey key, SqlWork<T> work, boolean write) {
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
                // Neither known-applied nor known-not-applied from here. The write is addressed by the
                // execution, so re-reading that address settles it.
                throw new ExecutionManifestStoreException(
                        new ExecutionManifestStoreFailure.OutcomeUnknown(key,
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
    private ExecutionManifestStoreException mapBeforeCommit(SQLException failed) {
        int code = failed.getErrorCode();
        if (code == SQLITE_CORRUPT || code == SQLITE_NOTADB) {
            return new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "the database at " + databaseFile + " is not readable as a SQLite database"), failed);
        }
        if (code == SQLITE_PERM || code == SQLITE_AUTH || code == SQLITE_READONLY) {
            return new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.NotAuthorized(
                    "the database at " + databaseFile + " is not writable by this process"), failed);
        }
        return new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                failed.getMessage()), failed);
    }

    private static void requireKey(ExecutionKey key) {
        if (key == null) {
            throw failure(new ExecutionManifestStoreFailure.InvalidRequest("key cannot be null"));
        }
    }

    private static ExecutionManifestStoreException failure(ExecutionManifestStoreFailure classified) {
        return new ExecutionManifestStoreException(classified);
    }

    private <T> CompletionStage<T> async(java.util.function.Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ExecutionManifestStoreException(
                    new ExecutionManifestStoreFailure.Unavailable("this execution manifest store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }

    private <T> T onWorker(Work<T> work) {
        try {
            return worker.submit(work::run).get(1, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "interrupted while waiting for the store connection"), interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ExecutionManifestStoreException(
                    new ExecutionManifestStoreFailure.Unavailable(String.valueOf(cause)), cause);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
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

    /** The address and commit instant of a manifest already pinned, without reading the rest of it. */
    private record Existing(String digest, Instant committedAt) {
    }
}
