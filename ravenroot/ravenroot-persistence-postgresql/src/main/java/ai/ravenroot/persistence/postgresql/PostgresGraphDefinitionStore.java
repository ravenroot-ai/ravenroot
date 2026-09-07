package ai.ravenroot.persistence.postgresql;

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

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.function.Supplier;

/**
 * Durable {@link GraphDefinitionStore} shared across every process addressing one PostgreSQL database.
 *
 * <h2>Why {@code put} cannot be a read followed by an insert</h2>
 * <p>The SQLite adapter this class ports reads the definition row and the version binding, decides
 * what is missing, and writes it, all of which is safe there because SQLite's write lock admits one
 * writer to the whole database for the length of that transaction (see this package's
 * {@code package-info}). Two hosts have no such lock. Reproducing the read-then-decide-then-write
 * sequence here would let two hosts both observe an absent binding, both decide to create it, and one
 * of them silently overwrite the other's decision the instant before commit — the exact lost update
 * this adapter exists to make impossible.</p>
 *
 * <p>Both writes this class performs are therefore expressed as a conditional {@code INSERT ... ON
 * CONFLICT DO NOTHING} whose row count tells the caller which branch was taken, immediately followed —
 * only on the branch that found a conflict — by a {@code SELECT ... FOR UPDATE} that both fetches the
 * authoritative row and takes the lock needed to hold it stable for the rest of the transaction. The
 * definition row's lock is taken first and kept for the whole transaction specifically so that a
 * concurrent {@link #remove(GraphDefinitionKey)} of the same content id cannot cascade away the very
 * binding this transaction is about to depend on; see {@link #upsertDefinition} for the retry this
 * still requires when a concurrent removal wins the race for that lock.</p>
 *
 * <h2>Reachability</h2>
 * <p>{@code PostgresSchema} co-locates {@code process_instance} with {@code graph_definition} in the
 * same migration and the same database, exactly as the SQLite adapter's schema does, so this store
 * checks {@code process_instance.graph_version_pin} directly, exactly as the SQLite adapter checks it —
 * see {@link #isReferenced}. It additionally checks this module's own {@code execution_manifest} table,
 * which is a strictly stronger signal than the pin column: it is keyed by the same content address this
 * store uses rather than by an opaque reference string, and it catches a manifest pinned for an
 * execution whose acceptance has not yet committed a {@code process_instance} row. Every reference class
 * neither table can answer — a deployment, anything outside this deployment's durable stores — reaches this
 * adapter only through the composed {@link GraphDefinitionReferences}, exactly as the port's javadoc
 * anticipates.</p>
 */
public final class PostgresGraphDefinitionStore implements GraphDefinitionStore {

    /** Safe default shared with GraphML ingest; composition may supply another value within the ceiling. */
    public static final int DEFAULT_MAX_DEFINITION_BYTES = GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES;

    /**
     * How many times {@link #upsertDefinition} retries after the row it locked for verification
     * turned out to have been deleted by a concurrent {@link #remove(GraphDefinitionKey)} that
     * committed while this transaction waited for the lock.
     *
     * <p>Bounded rather than unbounded so that sustained, adversarial contention between a put and a
     * repeated remove/re-put of the exact same content id fails loudly instead of spinning inside one
     * caller's transaction forever. Three matches {@link PostgresStoreConfig#serializationRetries()}'s
     * default: both bounds exist for the same reason, to turn "this is not resolving" into a reported
     * failure rather than an invisible stall.</p>
     */
    private static final int MAX_DEFINITION_UPSERT_ATTEMPTS = 3;

    private final DataSource dataSource;
    private final Clock clock;
    private final GraphDefinitionReferences references;
    private final int maxDefinitionBytes;
    private final Transactions transactions;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Opens the definition store against a shared database, applying the schema if it is not already
     * at the version this binary understands.
     *
     * @param dataSource connection factory for the shared database; never inspected for a URL or a
     *                   credential, only used to obtain connections.
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle composed with this store's own reachability query before a removal.
     */
    public PostgresGraphDefinitionStore(DataSource dataSource, Clock clock, GraphDefinitionReferences references) {
        this(dataSource, clock, references, DEFAULT_MAX_DEFINITION_BYTES);
    }

    /**
     * Opens the definition store with an explicit definition byte budget.
     *
     * @param dataSource connection factory for the shared database.
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle composed with this store's own reachability query before a removal.
     * @param maxDefinitionBytes largest canonical definition this instance accepts.
     */
    public PostgresGraphDefinitionStore(DataSource dataSource, Clock clock, GraphDefinitionReferences references,
                                        int maxDefinitionBytes) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
        if (maxDefinitionBytes < 1) {
            throw new IllegalArgumentException("maxDefinitionBytes must be positive");
        }
        if (maxDefinitionBytes > GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("maxDefinitionBytes exceeds the supported safety ceiling");
        }
        this.maxDefinitionBytes = maxDefinitionBytes;
        PostgresStoreConfig config = PostgresStoreConfig.defaults();
        this.transactions = new Transactions(dataSource, config, CommitBoundary.NONE);
        // One virtual thread per call rather than a sized platform-thread pool: every operation here
        // blocks on JDBC I/O and nothing here is CPU-bound, so there is no working set to size a pool
        // around, and a caller issuing many concurrent operations gets them genuinely concurrent
        // instead of queued behind a fixed worker count that this adapter would have to guess at.
        this.worker = Executors.newVirtualThreadPerTaskExecutor();
        ensureSchema(dataSource, clock);
    }

    @Override
    public Set<StoreCapability> capabilities() {
        // DURABLE and nothing else, for the reason the SQLite adapter's own capabilities() gives:
        // every other member of the vocabulary describes a facility this port does not offer.
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
            // Decided from the request alone, before any connection is opened and before anything
            // could have been written or locked.
            if (canonical.size() > maxDefinitionBytes) {
                throw failure(new GraphDefinitionStoreFailure.DefinitionTooLarge(
                        canonical.size(), maxDefinitionBytes));
            }
            var key = new GraphDefinitionKey(tenantId, canonical.contentId());
            // Best-effort fast path, exactly as the SQLite adapter's: the overwhelmingly common case
            // is that this exact content is already stored and already bound, and deciding that with
            // a single autocommit read avoids opening a locking transaction once per acceptance. It
            // is never authoritative — every branch that must change something falls through to the
            // transaction below, which re-decides everything under a lock rather than trusting this
            // snapshot.
            try {
                StoredGraphDefinition fastPath = transactions.readOnly(connection ->
                        storedAndBound(connection, key, identity, canonical));
                if (fastPath != null) {
                    return fastPath;
                }
                return transactions.inTransaction(connection -> {
                    Instant now = Instant.now(clock);
                    Row row = upsertDefinition(connection, key, identity, canonical, now);
                    GraphContentId bound = upsertBinding(connection, tenantId, identity, canonical.contentId(), now);
                    if (!bound.equals(canonical.contentId())) {
                        throw new SqlFailure(new GraphDefinitionStoreFailure.IdentityConflict(
                                tenantId, identity, bound, canonical.contentId()));
                    }
                    return row.stored(key, row.identity());
                });
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            } catch (OutcomeUnknownException unknown) {
                throw failure(new GraphDefinitionStoreFailure.OutcomeUnknown(key, unknown.getMessage()));
            }
        });
    }

    @Override
    public CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key) {
        return async(() -> {
            require(key != null, "key cannot be null");
            try {
                return transactions.readOnly(connection -> {
                    Row row = readRow(connection, key);
                    if (row == null) {
                        throw new SqlFailure(new GraphDefinitionStoreFailure.NotFound(key));
                    }
                    verify(key, row);
                    return row.stored(key, row.identity());
                });
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            }
        });
    }

    @Override
    public CompletionStage<StoredGraphDefinition> resolve(String tenantId, GraphDefinitionIdentity identity) {
        return async(() -> {
            requireTenantId(tenantId);
            require(identity != null, "identity cannot be null");
            try {
                // readConsistent, not readOnly, and not a locking transaction either. This reads the
                // binding and then the definition it names. Under READ COMMITTED each of those takes
                // its own snapshot even inside a transaction, so a purge committing between them
                // leaves the second statement unable to find a definition the first still pointed at -
                // which this method would then report as a binding naming content the store does not
                // hold, that is, as corruption rather than as contention. One REPEATABLE READ snapshot
                // makes the pair agree, and it does so without taking a lock: readers still never
                // block writers, so nothing is serialised for a guarantee this port does not promise.
                return transactions.readConsistent(connection -> {
                    GraphContentId bound = readBinding(connection, tenantId, identity);
                    if (bound == null) {
                        throw new SqlFailure(new GraphDefinitionStoreFailure.NotFound(
                                new GraphDefinitionKey(tenantId, unboundAddress())));
                    }
                    var key = new GraphDefinitionKey(tenantId, bound);
                    Row row = readRow(connection, key);
                    if (row == null) {
                        throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                                "a graph version binding names content this store does not hold"));
                    }
                    verify(key, row);
                    return row.stored(key, identity);
                });
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> contains(GraphDefinitionKey key) {
        return async(() -> {
            require(key != null, "key cannot be null");
            try {
                return transactions.readOnly(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT 1 FROM graph_definition WHERE tenant_id = ? AND content_id = ?")) {
                        statement.setString(1, key.tenantId());
                        statement.setString(2, key.contentId().value());
                        try (ResultSet rows = statement.executeQuery()) {
                            return rows.next();
                        }
                    }
                });
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            }
        });
    }

    @Override
    public CompletionStage<Void> remove(GraphDefinitionKey key) {
        return async(() -> {
            require(key != null, "key cannot be null");
            try {
                return transactions.inTransaction(connection -> {
                    // Locked before the reachability question is asked, and held until commit or
                    // rollback: this is what forces a concurrent put() that is racing to create a new
                    // binding for this exact content id to wait behind this transaction rather than
                    // interleave with it. See PostgresExecutionManifestStore.upsertDefinition's
                    // counterpart note for the other half of that argument.
                    Row row = readRowForUpdate(connection, key);
                    if (row == null) {
                        throw new SqlFailure(new GraphDefinitionStoreFailure.NotFound(key));
                    }
                    if (isReferenced(connection, key)) {
                        throw new SqlFailure(new GraphDefinitionStoreFailure.StillReferenced(key));
                    }
                    deleteDefinition(connection, key);
                    return null;
                });
            } catch (SqlFailure wrapped) {
                throw failure(wrapped.failure);
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            } catch (OutcomeUnknownException unknown) {
                throw failure(new GraphDefinitionStoreFailure.OutcomeUnknown(key, unknown.getMessage()));
            }
        });
    }

    @Override
    public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            try {
                return transactions.inTransaction(connection -> {
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
                        // Locked per candidate, inside this one transaction, for the same reason
                        // remove() locks: a candidate this loop is about to delete must not be
                        // acquiring a fresh binding concurrently while the decision is being made.
                        Row row = readRowForUpdate(connection, key);
                        if (row == null) {
                            // Already gone - another purge or an explicit remove() won the race for
                            // this candidate after this method listed it and before this loop reached
                            // it. Not an error: the candidate list is a snapshot, not a lock.
                            continue;
                        }
                        if (!isReferenced(connection, key)) {
                            deleteDefinition(connection, key);
                            removed++;
                        }
                    }
                    return removed;
                });
            } catch (SQLException failed) {
                throw mapBeforeCommit(failed);
            } catch (OutcomeUnknownException unknown) {
                throw failure(new GraphDefinitionStoreFailure.Unavailable(
                        "the outcome of the purge is unknown: " + unknown.getMessage()));
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Nothing here owns the DataSource - a composition root that built it is the one that closes
        // it, exactly as this class never saw a URL or a credential to have opened it in the first
        // place. Only this adapter's own executor is this adapter's to release.
        worker.shutdown();
    }

    // ---------------------------------------------------------------- reachability

    /**
     * Whether any retained work still reaches this definition. Three sources are a conjunction: a
     * definition is removable only when all three report unreachable. See the class javadoc for why
     * there are two first-party sources here rather than the SQLite adapter's one.
     */
    private boolean isReferenced(Connection connection, GraphDefinitionKey key) throws SQLException {
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM execution_manifest WHERE tenant_id = ? AND graph_content_id = ? LIMIT 1")) {
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

    // ---------------------------------------------------------------- put

    /**
     * The definition as stored, when it is already stored under this exact content and already bound
     * to this exact version, and {@code null} whenever anything must change. Never authoritative; see
     * {@link #put}.
     */
    private StoredGraphDefinition storedAndBound(Connection connection, GraphDefinitionKey key,
                                                 GraphDefinitionIdentity identity, CanonicalGraphMl canonical)
            throws SQLException {
        StoredMeta meta = readMeta(connection, key);
        if (meta == null) {
            return null;
        }
        if (meta.formatVersion() != canonical.formatVersion()) {
            return null;
        }
        if (!HexFormat.of().formatHex(meta.digest()).equals(key.contentId().value())) {
            throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                    "the digest recorded beside the definition does not match the address it is filed under"));
        }
        GraphContentId bound = readBinding(connection, key.tenantId(), identity);
        if (!canonical.contentId().equals(bound)) {
            return null;
        }
        return new StoredGraphDefinition(key, meta.identity(), canonical, meta.storedAt());
    }

    /**
     * Ensures the content-addressed definition row exists, returning it either freshly built from the
     * caller's own bytes (no re-read needed - they hash to this address by construction) or read back
     * and verified when the row already existed.
     *
     * <h2>Why this retries</h2>
     * <p>The conflict branch takes {@code SELECT ... FOR UPDATE} to read the authoritative row and to
     * hold its lock for the rest of the transaction. If a concurrent {@link #remove} committed its
     * delete of that exact row in the window between this method's insert attempt observing the
     * conflict and the locking read actually acquiring the lock, PostgreSQL resolves the wait by
     * returning no row at all - the row this method was about to lock is gone. That is not corruption;
     * it is the ordinary outcome of losing a race to a legitimate concurrent removal, and the correct
     * response is to try the insert again against what is now an empty slot, which the loop below
     * does. See {@link #MAX_DEFINITION_UPSERT_ATTEMPTS} for the bound on how long this adapter keeps
     * retrying before treating the contention as pathological.
     */
    private Row upsertDefinition(Connection connection, GraphDefinitionKey key, GraphDefinitionIdentity identity,
                                 CanonicalGraphMl canonical, Instant now) throws SQLException {
        for (int attempt = 0; attempt < MAX_DEFINITION_UPSERT_ATTEMPTS; attempt++) {
            byte[] bytes = canonical.bytes();
            byte[] digest = sha256(bytes);
            int inserted = insertDefinitionIfAbsent(connection, key, identity, canonical, bytes, digest, now);
            if (inserted > 0) {
                return new Row(canonical.formatVersion(), bytes, digest, identity, now);
            }
            Row existing = readRowForUpdate(connection, key);
            if (existing != null) {
                verify(key, existing);
                return existing;
            }
            // Lost the race to a concurrent remove(); retry against the now-empty slot.
        }
        throw new SqlFailure(new GraphDefinitionStoreFailure.Unavailable(
                "the definition row for " + key.contentId().value()
                        + " could not be stabilised after repeated concurrent removal; retry the request"));
    }

    /**
     * Ensures the version binding names this content id, returning the content id it actually names -
     * which the caller compares against what it requested, since a binding this call did not create
     * may already exist and may name something else.
     *
     * <p>No retry loop is needed here, unlike {@link #upsertDefinition}: by the time this runs, the
     * definition row for {@code contentId} is locked for the remainder of this transaction - either
     * because this transaction just inserted it (an uncommitted insert is exclusive to its own
     * transaction) or because {@link #upsertDefinition} took {@code SELECT ... FOR UPDATE} on it. A
     * concurrent {@link #remove} of that same content id therefore cannot cascade-delete this binding
     * out from under this method: it would have to delete the parent row first, and that delete blocks
     * on the lock this transaction already holds.</p>
     */
    private GraphContentId upsertBinding(Connection connection, String tenantId, GraphDefinitionIdentity identity,
                                         GraphContentId contentId, Instant now) throws SQLException {
        int inserted = insertBindingIfAbsent(connection, tenantId, identity, contentId, now);
        if (inserted > 0) {
            return contentId;
        }
        GraphContentId existing = readBindingForUpdate(connection, tenantId, identity);
        if (existing == null) {
            // The definition-row lock this method's caller already holds rules out the only concurrent
            // actor that could remove a binding (a remove() of the same content id), so this should be
            // unreachable; reported as Corrupted rather than silently retried, because a codepath that
            // reaches here is a defect in the locking argument above, not ordinary contention.
            throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(
                    new GraphDefinitionKey(tenantId, contentId),
                    "the version binding for " + identity.graphId() + "/" + identity.versionId()
                            + " reported a conflict on insert but no row could be read back"));
        }
        return existing;
    }

    // ---------------------------------------------------------------- rows

    private StoredMeta readMeta(Connection connection, GraphDefinitionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format_version, digest, first_graph_id, first_version_id, "
                        + "stored_at_epoch_second, stored_at_nano "
                        + "FROM graph_definition WHERE tenant_id = ? AND content_id = ?")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? metaFrom(key, rows) : null;
            }
        }
    }

    private Row readRow(Connection connection, GraphDefinitionKey key) throws SQLException {
        return readRow(connection, key, false);
    }

    private Row readRowForUpdate(Connection connection, GraphDefinitionKey key) throws SQLException {
        return readRow(connection, key, true);
    }

    private Row readRow(Connection connection, GraphDefinitionKey key, boolean forUpdate) throws SQLException {
        String sql = "SELECT format_version, definition_bytes, digest, byte_length, first_graph_id, "
                + "first_version_id, stored_at_epoch_second, stored_at_nano "
                + "FROM graph_definition WHERE tenant_id = ? AND content_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
                Instant storedAt = StoredInstant.read(rows, "stored_at");
                if (bytes == null || digest == null || formatVersion < 1) {
                    throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition row is missing content or carries an illegal "
                                    + "format version"));
                }
                if (bytes.length != byteLength) {
                    throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "the stored definition is " + bytes.length + " bytes but the row records "
                                    + byteLength));
                }
                GraphDefinitionIdentity identity = identityFrom(key,
                        rows.getString("first_graph_id"), rows.getString("first_version_id"));
                return new Row(formatVersion, bytes, digest, identity, storedAt);
            }
        }
    }

    private StoredMeta metaFrom(GraphDefinitionKey key, ResultSet rows) throws SQLException {
        byte[] digest = rows.getBytes("digest");
        int formatVersion = rows.getInt("format_version");
        if (digest == null || formatVersion < 1) {
            throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                    "the stored definition row is missing its digest or carries an illegal format version"));
        }
        GraphDefinitionIdentity identity = identityFrom(key,
                rows.getString("first_graph_id"), rows.getString("first_version_id"));
        return new StoredMeta(formatVersion, digest, identity, StoredInstant.read(rows, "stored_at"));
    }

    private GraphDefinitionIdentity identityFrom(GraphDefinitionKey key, String graphId, String versionId) {
        try {
            return new GraphDefinitionIdentity(graphId, versionId);
        } catch (IllegalArgumentException illegal) {
            throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                    "the stored definition names an illegal graph version identity"));
        }
    }

    private int insertDefinitionIfAbsent(Connection connection, GraphDefinitionKey key,
                                         GraphDefinitionIdentity identity, CanonicalGraphMl canonical,
                                         byte[] bytes, byte[] digest, Instant storedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_definition (tenant_id, content_id, format_version, definition_bytes, "
                        + "digest, byte_length, first_graph_id, first_version_id, "
                        + "stored_at_epoch_second, stored_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, content_id) DO NOTHING")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.contentId().value());
            statement.setInt(3, canonical.formatVersion());
            statement.setBytes(4, bytes);
            statement.setBytes(5, digest);
            statement.setLong(6, bytes.length);
            statement.setString(7, identity.graphId());
            statement.setString(8, identity.versionId());
            StoredInstant.bindValue(statement, 9, storedAt);
            return statement.executeUpdate();
        }
    }

    private GraphContentId readBinding(Connection connection, String tenantId, GraphDefinitionIdentity identity)
            throws SQLException {
        return readBinding(connection, tenantId, identity, false);
    }

    private GraphContentId readBindingForUpdate(Connection connection, String tenantId,
                                                GraphDefinitionIdentity identity) throws SQLException {
        return readBinding(connection, tenantId, identity, true);
    }

    private GraphContentId readBinding(Connection connection, String tenantId, GraphDefinitionIdentity identity,
                                       boolean forUpdate) throws SQLException {
        String sql = "SELECT content_id FROM graph_definition_binding "
                + "WHERE tenant_id = ? AND graph_id = ? AND version_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, identity.graphId());
            statement.setString(3, identity.versionId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new GraphContentId(rows.getString("content_id")) : null;
            }
        }
    }

    private int insertBindingIfAbsent(Connection connection, String tenantId, GraphDefinitionIdentity identity,
                                      GraphContentId contentId, Instant boundAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO graph_definition_binding (tenant_id, graph_id, version_id, content_id, "
                        + "bound_at_epoch_second, bound_at_nano) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, graph_id, version_id) DO NOTHING")) {
            statement.setString(1, tenantId);
            statement.setString(2, identity.graphId());
            statement.setString(3, identity.versionId());
            statement.setString(4, contentId.value());
            StoredInstant.bindValue(statement, 5, boundAt);
            return statement.executeUpdate();
        }
    }

    /** Bindings go with the content through {@code ON DELETE CASCADE}. */
    private void deleteDefinition(Connection connection, GraphDefinitionKey key) throws SQLException {
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
            throw new SqlFailure(new GraphDefinitionStoreFailure.DigestMismatch(key,
                    HexFormat.of().formatHex(observed)));
        }
        String recorded = HexFormat.of().formatHex(row.digest());
        if (!recorded.equals(key.contentId().value())) {
            throw new SqlFailure(new GraphDefinitionStoreFailure.Corrupted(key,
                    "the digest recorded beside the definition does not match the address it is filed under"));
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

    // ---------------------------------------------------------------- schema and failure mapping

    /**
     * Applies the schema this adapter and {@link PostgresExecutionManifestStore} both depend on, once
     * per construction. Idempotent and safe under concurrently starting processes; see
     * {@link SchemaRunner}.
     */
    private static void ensureSchema(DataSource dataSource, Clock clock) {
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            throw new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "cannot prepare the graph definition schema: " + failed.getMessage()), failed);
        }
    }

    /** Classifies a failure this adapter did not already classify itself. */
    private static GraphDefinitionStoreException mapBeforeCommit(SQLException failed) {
        if (SqlStates.isNotAuthorized(failed)) {
            return new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.NotAuthorized(
                    "the database credential cannot perform this operation: " + failed.getMessage()), failed);
        }
        if (SqlStates.isCorrupted(failed)) {
            return new GraphDefinitionStoreException(new GraphDefinitionStoreFailure.Unavailable(
                    "the database reports internal damage: " + failed.getMessage()), failed);
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

    private <T> CompletionStage<T> async(Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new GraphDefinitionStoreException(
                    new GraphDefinitionStoreFailure.Unavailable("this graph definition store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }

    /**
     * Carries a classified failure discovered inside a {@link Transactions.Work} back out through the
     * checked {@link SQLException} the functional interface declares, without inventing a SQLSTATE for
     * a condition the database never reported. Unwrapped immediately by every call site.
     */
    private static final class SqlFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final GraphDefinitionStoreFailure failure;

        SqlFailure(GraphDefinitionStoreFailure failure) {
            super(failure.describe());
            this.failure = failure;
        }
    }

    /** Everything about a stored definition except the document itself. */
    private record StoredMeta(int formatVersion, byte[] digest, GraphDefinitionIdentity identity,
                              Instant storedAt) {
    }

    private record Row(int formatVersion, byte[] bytes, byte[] digest,
                       GraphDefinitionIdentity identity, Instant storedAt) {

        StoredGraphDefinition stored(GraphDefinitionKey key, GraphDefinitionIdentity under) {
            return new StoredGraphDefinition(key, under, CanonicalGraphMl.of(formatVersion, bytes), storedAt);
        }
    }
}
