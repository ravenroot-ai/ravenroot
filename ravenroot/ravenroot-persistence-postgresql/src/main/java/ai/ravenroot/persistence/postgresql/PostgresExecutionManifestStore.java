package ai.ravenroot.persistence.postgresql;

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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.function.Supplier;

/**
 * Durable {@link ExecutionManifestStore} shared across every process addressing one PostgreSQL
 * database.
 *
 * <h2>Why {@code pin} cannot be a read followed by an insert</h2>
 * <p>The SQLite adapter this class ports decides, from a single read taken under its file-wide write
 * lock, whether a manifest is already pinned and whether it agrees with the one being offered. Two
 * hosts calling {@code pin} for the same execution at nearly the same instant have no such lock: both
 * could observe "nothing pinned yet" and both proceed to insert, and without a database-enforced
 * decision one of the two inserts would have to fail or, worse, silently overwrite the other. This
 * adapter instead attempts a conditional {@code INSERT ... ON CONFLICT (tenant_id,
 * process_instance_id) DO NOTHING} first; the row count tells it, without a prior read, whether it won
 * the race. Only the loser needs to look at what is actually stored, and it does so with
 * {@code SELECT ... FOR UPDATE} so the row it compares against cannot itself be removed out from under
 * it before this transaction decides {@link ExecutionManifestStoreFailure.ManifestConflict} or
 * convergent success.</p>
 *
 * <h2>Reachability</h2>
 * <p>{@code PostgresSchema} co-locates {@code process_instance} with {@code execution_manifest} in the
 * same migration and the same database, exactly as the SQLite adapter's schema does, so this store
 * checks {@code process_instance} directly for the existence of the instance a manifest belongs to,
 * exactly as the SQLite adapter's {@code instanceExists} does — see {@link #remove}. Every reference
 * class that check cannot answer reaches this adapter only through the composed
 * {@link ExecutionManifestReferences}, exactly as the port's javadoc anticipates.</p>
 */
public final class PostgresExecutionManifestStore implements ExecutionManifestStore {

    /**
     * How many times {@link #pin} retries after the row it locked to compare against turned out to
     * have been deleted by a concurrent {@link #remove(ExecutionKey)} that committed while this
     * transaction waited for the lock. See {@link PostgresGraphDefinitionStore}'s identical bound for
     * the identical reason.
     */
    private static final int MAX_PIN_ATTEMPTS = 3;

    private final DataSource dataSource;
    private final Clock clock;
    private final ExecutionManifestReferences references;
    private final Transactions transactions;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Opens the manifest store against a shared database, applying the schema if it is not already at
     * the version this binary understands.
     *
     * @param dataSource connection factory for the shared database; never inspected for a URL or a
     *                   credential, only used to obtain connections.
     * @param clock time authority for the instant a manifest is durably recorded.
     * @param references oracle composed with this store's own reachability query before a removal.
     */
    public PostgresExecutionManifestStore(DataSource dataSource, Clock clock,
                                          ExecutionManifestReferences references) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
        PostgresStoreConfig config = PostgresStoreConfig.defaults();
        this.transactions = new Transactions(dataSource, config, CommitBoundary.NONE);
        // See PostgresGraphDefinitionStore's identical field for why this is a virtual-thread-per-call
        // executor rather than a sized platform-thread pool.
        this.worker = Executors.newVirtualThreadPerTaskExecutor();
        ensureSchema(dataSource, clock);
    }

    @Override
    public Set<StoreCapability> capabilities() {
        // DURABLE and nothing else, for the reason PostgresGraphDefinitionStore's own capabilities()
        // gives: every other member of the vocabulary describes a facility this port does not offer.
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
            try {
                return transactions.inTransaction(connection ->
                        upsertManifest(connection, manifest, digest, key));
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            } catch (OutcomeUnknownException unknown) {
                throw failure(new ExecutionManifestStoreFailure.OutcomeUnknown(key, unknown.getMessage()));
            }
        });
    }

    @Override
    public CompletionStage<StoredExecutionManifest> load(ExecutionKey key) {
        return async(() -> {
            requireKey(key);
            try {
                return transactions.readOnly(connection -> {
                    StoredExecutionManifest stored = readManifest(connection, key, false);
                    if (stored == null) {
                        throw new SqlFailure(new ExecutionManifestStoreFailure.NotFound(key));
                    }
                    return stored;
                });
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> contains(ExecutionKey key) {
        return async(() -> {
            requireKey(key);
            try {
                return transactions.readOnly(connection -> readDigest(connection, key, false) != null);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            }
        });
    }

    @Override
    public CompletionStage<Void> remove(ExecutionKey key) {
        return async(() -> {
            requireKey(key);
            try {
                return transactions.inTransaction(connection -> {
                    // Locked before the reachability question is asked and held to commit, for the
                    // same reason PostgresGraphDefinitionStore.remove locks: it forces a concurrent
                    // pin() racing to recreate this row to wait behind this transaction.
                    Existing existing = readDigest(connection, key, true);
                    if (existing == null) {
                        throw new SqlFailure(new ExecutionManifestStoreFailure.NotFound(key));
                    }
                    // Recomputed inside this removal transaction, so an acceptance committing
                    // concurrently cannot land between the question and the delete.
                    if (instanceExists(connection, key) || references.isReferenced(key)) {
                        throw new SqlFailure(new ExecutionManifestStoreFailure.StillReferenced(key));
                    }
                    deleteManifest(connection, key);
                    return null;
                });
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            } catch (OutcomeUnknownException unknown) {
                throw failure(new ExecutionManifestStoreFailure.OutcomeUnknown(key, unknown.getMessage()));
            }
        });
    }

    @Override
    public CompletionStage<Long> purgeUnreferencedManifests(String tenantId) {
        return async(() -> {
            if (tenantId == null || tenantId.isBlank()) {
                throw failure(new ExecutionManifestStoreFailure.InvalidRequest("tenantId cannot be blank"));
            }
            try {
                return transactions.inTransaction(connection -> {
                    var candidates = new ArrayList<UUID>();
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT process_instance_id FROM execution_manifest WHERE tenant_id = ?")) {
                        statement.setString(1, tenantId);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                candidates.add(decodeInstanceId(rows, tenantId));
                            }
                        }
                    }
                    long removed = 0;
                    for (UUID candidate : candidates) {
                        var key = new ExecutionKey(tenantId, candidate);
                        // Locked per candidate, inside this one transaction, for the reason
                        // PostgresGraphDefinitionStore.purgeUnreferencedDefinitions locks each of its
                        // own candidates.
                        Existing existing = readDigest(connection, key, true);
                        if (existing == null) {
                            // Already gone - another purge or an explicit remove() won the race for
                            // this candidate. The candidate list is a snapshot, not a lock.
                            continue;
                        }
                        if (!instanceExists(connection, key) && !references.isReferenced(key)) {
                            deleteManifest(connection, key);
                            removed++;
                        }
                    }
                    return removed;
                });
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            } catch (OutcomeUnknownException unknown) {
                throw failure(new ExecutionManifestStoreFailure.Unavailable(
                        "the outcome of the purge is unknown: " + unknown.getMessage()));
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // This adapter never owned the DataSource, only its own executor; see
        // PostgresGraphDefinitionStore.close for the identical argument.
        worker.shutdown();
    }

    // ---------------------------------------------------------------- pin

    /**
     * Ensures the manifest is pinned, returning it as stored either freshly (this call inserted it) or
     * as it was already pinned, after confirming the two agree.
     *
     * <p>Retries for the same reason {@link PostgresGraphDefinitionStore#upsertDefinition} retries: the
     * conflict branch's {@code SELECT ... FOR UPDATE} can legitimately find nothing if a concurrent
     * {@link #remove} deleted and committed the row in the window between this method observing the
     * insert conflict and the locking read acquiring the lock. Retrying re-attempts the insert against
     * the now-empty slot rather than treating a genuine concurrent removal as corruption.</p>
     */
    private StoredExecutionManifest upsertManifest(Connection connection, ExecutionManifest manifest,
                                                    ExecutionManifestDigest digest, ExecutionKey key)
            throws SQLException {
        for (int attempt = 0; attempt < MAX_PIN_ATTEMPTS; attempt++) {
            Instant now = Instant.now(clock);
            int inserted = insertManifestIfAbsent(connection, manifest, digest, now);
            if (inserted > 0) {
                insertPackages(connection, manifest);
                return new StoredExecutionManifest(manifest, digest, now);
            }
            Existing existing = readDigest(connection, key, true);
            if (existing != null) {
                if (!existing.digest().equals(digest.value())) {
                    throw new SqlFailure(new ExecutionManifestStoreFailure.ManifestConflict(key,
                            new ExecutionManifestDigest(existing.digest())));
                }
                return new StoredExecutionManifest(manifest, digest, existing.committedAt());
            }
            // Lost the race to a concurrent remove(); retry against the now-empty slot.
        }
        throw new SqlFailure(new ExecutionManifestStoreFailure.Unavailable(
                "the execution manifest for process instance " + key.processInstanceId()
                        + " could not be stabilised after repeated concurrent removal; retry the request"));
    }

    // ---------------------------------------------------------------- reachability

    /**
     * Whether the process instance this manifest was pinned for still exists. {@code process_instance}
     * is co-located with {@code execution_manifest} in the same database and the same migration; see
     * the class javadoc for why this adapter reads it directly rather than relying solely on the
     * composed {@link ExecutionManifestReferences}.
     */
    private boolean instanceExists(Connection connection, ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM process_instance WHERE tenant_id = ? AND process_instance_id = ? LIMIT 1")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ---------------------------------------------------------------- rows

    private Existing readDigest(Connection connection, ExecutionKey key, boolean forUpdate) throws SQLException {
        String sql = "SELECT digest, committed_at_epoch_second, committed_at_nano FROM execution_manifest "
                + "WHERE tenant_id = ? AND process_instance_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Existing(rows.getString(1), Instant.ofEpochSecond(rows.getLong(2), rows.getInt(3)));
            }
        }
    }

    private StoredExecutionManifest readManifest(Connection connection, ExecutionKey key, boolean forUpdate)
            throws SQLException {
        String recordedDigest;
        ExecutionManifest manifest;
        Instant committedAt;
        String sql = "SELECT format_version, digest, graph_content_id, graph_id, version_id, "
                + "graph_schema_version, definition_format_version, execution_policy, "
                + "unknown_behavior_mode, engine_digest, store_digest, limits_digest, "
                + "program_runtime_digest, pinned_at_epoch_second, pinned_at_nano, "
                + "committed_at_epoch_second, committed_at_nano FROM execution_manifest "
                + "WHERE tenant_id = ? AND process_instance_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
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
                            profile, readPackages(connection, key),
                            Instant.ofEpochSecond(rows.getLong(14), rows.getInt(15)));
                } catch (IllegalArgumentException | NullPointerException malformed) {
                    throw new SqlFailure(new ExecutionManifestStoreFailure.Corrupted(key,
                            String.valueOf(malformed.getMessage())));
                }
            }
        }
        ExecutionManifestDigest observed = manifest.digest();
        if (!observed.value().equals(recordedDigest)) {
            throw new SqlFailure(new ExecutionManifestStoreFailure.DigestMismatch(key, observed.value()));
        }
        return new StoredExecutionManifest(manifest, observed, committedAt);
    }

    private List<PinnedNodePackage> readPackages(Connection connection, ExecutionKey key) throws SQLException {
        var pinned = new ArrayList<PinnedNodePackage>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT package_id, identity_digest FROM execution_manifest_package "
                        + "WHERE tenant_id = ? AND process_instance_id = ? ORDER BY package_id")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    pinned.add(new PinnedNodePackage(rows.getString(1), rows.getString(2)));
                }
            }
        }
        return pinned;
    }

    private int insertManifestIfAbsent(Connection connection, ExecutionManifest manifest,
                                       ExecutionManifestDigest digest, Instant now) throws SQLException {
        ExecutionKey key = manifest.key();
        ResolvedRuntimeProfile runtime = manifest.runtime();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_manifest (tenant_id, process_instance_id, format_version, digest, "
                        + "graph_content_id, graph_id, version_id, graph_schema_version, "
                        + "definition_format_version, execution_policy, unknown_behavior_mode, "
                        + "engine_digest, store_digest, limits_digest, program_runtime_digest, "
                        + "pinned_at_epoch_second, pinned_at_nano, committed_at_epoch_second, "
                        + "committed_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id) DO NOTHING")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
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
            StoredInstant.bindValue(statement, 16, manifest.pinnedAt());
            StoredInstant.bindValue(statement, 18, now);
            return statement.executeUpdate();
        }
    }

    /**
     * Inserts the node-package children of a manifest this transaction just created.
     *
     * <p>No {@code ON CONFLICT} needed: this is only ever called immediately after this same
     * transaction's own {@code INSERT} of the parent row succeeded, so the parent id is new and these
     * child rows cannot already exist.</p>
     */
    private void insertPackages(Connection connection, ExecutionManifest manifest) throws SQLException {
        if (manifest.nodePackages().isEmpty()) {
            return;
        }
        ExecutionKey key = manifest.key();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_manifest_package (tenant_id, process_instance_id, package_id, "
                        + "identity_digest) VALUES (?, ?, ?, ?)")) {
            for (PinnedNodePackage pinned : manifest.nodePackages()) {
                statement.setString(1, key.tenantId());
                StoredUuid.bind(statement, 2, key.processInstanceId());
                statement.setString(3, pinned.packageId());
                statement.setString(4, pinned.identityDigest());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Child rows go with the parent through {@code ON DELETE CASCADE}. */
    private void deleteManifest(Connection connection, ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM execution_manifest WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            statement.executeUpdate();
        }
    }

    /**
     * Decodes one stored process-instance id through this module's single decoder, translating its
     * execution-store-flavoured corruption verdict into this port's own, for the reason the SQLite
     * adapter's identical method gives: a caller composing all three stores must not catch one
     * exception type and silently absorb another.
     */
    private UUID decodeInstanceId(ResultSet rows, String tenantId) throws SQLException {
        try {
            return StoredUuid.required(rows, "execution_manifest", "process_instance_id",
                    new ExecutionKey(tenantId, new UUID(0L, 0L)));
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException corrupt) {
            throw new SqlFailure(new ExecutionManifestStoreFailure.Corrupted(
                    new ExecutionKey(tenantId, new UUID(0L, 0L)),
                    "stored execution_manifest.process_instance_id is not a canonical UUID"));
        }
    }

    // ---------------------------------------------------------------- schema and failure mapping

    /**
     * Applies the schema this adapter and {@link PostgresGraphDefinitionStore} both depend on, once
     * per construction. Idempotent and safe under concurrently starting processes; see
     * {@link SchemaRunner}.
     */
    private static void ensureSchema(DataSource dataSource, Clock clock) {
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            throw new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "cannot prepare the execution manifest schema: " + failed.getMessage()), failed);
        }
    }

    /** Classifies a failure this adapter did not already classify itself. */
    private static ExecutionManifestStoreException mapBeforeCommit(SQLException failed) {
        if (SqlStates.isNotAuthorized(failed)) {
            return new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.NotAuthorized(
                    "the database credential cannot perform this operation: " + failed.getMessage()), failed);
        }
        if (SqlStates.isCorrupted(failed)) {
            return new ExecutionManifestStoreException(new ExecutionManifestStoreFailure.Unavailable(
                    "the database reports internal damage: " + failed.getMessage()), failed);
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

    private <T> CompletionStage<T> async(Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ExecutionManifestStoreException(
                    new ExecutionManifestStoreFailure.Unavailable("this execution manifest store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }

    /**
     * Carries a classified failure discovered inside a {@link Transactions.Work} back out through the
     * checked {@link SQLException} the functional interface declares. See
     * {@link PostgresGraphDefinitionStore}'s identical type for why this exists.
     */
    private static final class SqlFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ExecutionManifestStoreFailure failure;

        SqlFailure(ExecutionManifestStoreFailure failure) {
            super(failure.describe());
            this.failure = failure;
        }
    }

    /** The address and commit instant of a manifest already pinned, without reading the rest of it. */
    private record Existing(String digest, Instant committedAt) {
    }
}
