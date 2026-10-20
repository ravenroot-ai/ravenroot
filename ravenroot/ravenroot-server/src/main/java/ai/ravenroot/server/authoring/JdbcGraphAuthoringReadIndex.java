package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.DocumentSummary;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Page;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Revision;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Shared SQL implementation with its own additive, rebuildable schema. No execution-store tables.
 */
public class JdbcGraphAuthoringReadIndex implements GraphAuthoringReadIndex {
    private static final int GENERATIONS = 16;
    private static final long GENERATION_TTL_MILLIS = 15 * 60_000L;
    private static final int CONTENT_ENTRIES = 2048;
    private static final long CONTENT_BYTES = 64 * 1024 * 1024L;
    private final DataSource source;
    private final boolean postgres;
    private final AutoCloseable owner;
    private volatile boolean closed;

    protected JdbcGraphAuthoringReadIndex(
            DataSource source, boolean postgres, AutoCloseable owner) {
        this.source = Objects.requireNonNull(source);
        this.postgres = postgres;
        this.owner = owner;
        transaction(
                connection -> {
                    if (postgres) execute(connection, "SELECT pg_advisory_xact_lock(5862026)");
                    execute(
                            connection,
                            "CREATE TABLE IF NOT EXISTS authoring_read_schema (version INTEGER"
                                    + " PRIMARY KEY)");
                    try (var statement = connection.createStatement();
                            var rows =
                                    statement.executeQuery(
                                            "SELECT version FROM authoring_read_schema")) {
                        while (rows.next()) if (rows.getInt(1) != 1) throw unavailable();
                    }
                    execute(
                            connection,
                            "CREATE TABLE IF NOT EXISTS authoring_read_scope (repository TEXT NOT"
                                + " NULL, tenant TEXT NOT NULL, generation TEXT NOT NULL, PRIMARY"
                                + " KEY(repository,tenant))");
                    execute(
                            connection,
                            "CREATE TABLE IF NOT EXISTS authoring_read_generation (repository TEXT"
                                + " NOT NULL, tenant TEXT NOT NULL, generation TEXT NOT NULL,"
                                + " draft_head TEXT NOT NULL, release_head TEXT NOT NULL,"
                                + " publication_version TEXT NOT NULL, synchronized_at BIGINT NOT"
                                + " NULL, PRIMARY KEY(repository,tenant,generation))");
                    execute(
                            connection,
                            "CREATE TABLE IF NOT EXISTS authoring_read_entry (repository TEXT NOT"
                                + " NULL, tenant TEXT NOT NULL, generation TEXT NOT NULL,"
                                + " document_id TEXT NOT NULL, draft_blob TEXT NOT NULL,"
                                + " release_blob TEXT NOT NULL, draft_digest TEXT NOT NULL,"
                                + " release_digest TEXT NOT NULL, graph_id TEXT NOT NULL,"
                                + " release_version BIGINT NOT NULL, draft_revision TEXT NOT NULL,"
                                + " release_revision TEXT NOT NULL, publication TEXT NOT NULL,"
                                + " released_graph_id TEXT NOT NULL, released_version BIGINT NOT"
                                + " NULL, PRIMARY KEY(repository,tenant,generation,document_id))");
                    execute(
                            connection,
                            "CREATE TABLE IF NOT EXISTS authoring_read_content (repository TEXT NOT"
                                + " NULL, identity TEXT NOT NULL, digest TEXT NOT NULL, content "
                                    + (postgres ? "BYTEA" : "BLOB")
                                    + " NOT NULL, retained_at BIGINT NOT NULL, PRIMARY"
                                    + " KEY(repository,identity))");
                    execute(
                            connection,
                            "INSERT INTO authoring_read_schema(version) VALUES(1) ON CONFLICT DO"
                                    + " NOTHING");
                    return null;
                });
    }

    @Override
    public Optional<Generation> latest(String repository, String tenant) {
        return transaction(
                connection -> {
                    try (var statement =
                                    prepare(
                                            connection,
                                            "SELECT"
                                                + " g.generation,g.draft_head,g.release_head,g.publication_version,g.synchronized_at"
                                                + " FROM authoring_read_generation g JOIN"
                                                + " authoring_read_scope s ON"
                                                + " s.repository=g.repository AND s.tenant=g.tenant"
                                                + " AND s.generation=g.generation WHERE"
                                                + " g.repository=? AND g.tenant=?",
                                            repository,
                                            tenant);
                            var rows = statement.executeQuery()) {
                        return rows.next()
                                ? Optional.of(
                                        new Generation(
                                                rows.getString(1),
                                                rows.getString(2),
                                                rows.getString(3),
                                                rows.getString(4),
                                                rows.getLong(5)))
                                : Optional.empty();
                    }
                });
    }

    @Override
    public List<Entry> entries(String repository, String tenant, String generation) {
        return transaction(
                connection -> {
                    var result = new ArrayList<Entry>();
                    try (var statement =
                                    prepare(
                                            connection,
                                            "SELECT * FROM authoring_read_entry WHERE repository=?"
                                                    + " AND tenant=? AND generation=? ORDER BY"
                                                    + " document_id",
                                            repository,
                                            tenant,
                                            generation);
                            var rows = statement.executeQuery()) {
                        while (rows.next()) result.add(entry(rows));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public Page<DocumentSummary> page(
            String repository, String tenant, String generation, int page, int size) {
        return transaction(
                true,
                connection -> {
                    try (var exists =
                                    prepare(
                                            connection,
                                            "SELECT generation FROM authoring_read_generation WHERE"
                                                    + " repository=? AND tenant=? AND generation=?",
                                            repository,
                                            tenant,
                                            generation);
                            var rows = exists.executeQuery()) {
                        if (!rows.next())
                            throw new GraphAuthoringException(
                                    GraphAuthoringException.Failure.INVALID_DOCUMENT);
                    }
                    var result = new ArrayList<DocumentSummary>();
                    try (var statement =
                                    prepare(
                                            connection,
                                            "SELECT * FROM authoring_read_entry WHERE repository=?"
                                                    + " AND tenant=? AND generation=? ORDER BY"
                                                    + " document_id LIMIT ? OFFSET ?",
                                            repository,
                                            tenant,
                                            generation,
                                            size + 1,
                                            Math.multiplyExact((long) page, size));
                            var rows = statement.executeQuery()) {
                        while (rows.next()) result.add(entry(rows).summary());
                    }
                    boolean more = result.size() > size;
                    if (more) result.removeLast();
                    return new Page<>(result, more ? "g1." + generation + "." + (page + 1) : "");
                });
    }

    @Override
    public void publish(
            String repository, String tenant, Generation generation, List<Entry> entries) {
        if (entries.size() > 100_000)
            throw new GraphAuthoringException(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        transaction(
                connection -> {
                    try (var insert =
                            prepare(
                                    connection,
                                    "INSERT INTO authoring_read_scope(repository,tenant,generation)"
                                            + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                                    repository,
                                    tenant,
                                    generation.id())) {
                        insert.executeUpdate();
                    }
                    // A per-repository/tenant write lock serializes publication across PostgreSQL
                    // instances.
                    try (var lock =
                            prepare(
                                    connection,
                                    "UPDATE authoring_read_scope SET generation=generation WHERE"
                                            + " repository=? AND tenant=?",
                                    repository,
                                    tenant)) {
                        lock.executeUpdate();
                    }
                    try (var insert =
                            prepare(
                                    connection,
                                    "INSERT INTO authoring_read_generation VALUES(?,?,?,?,?,?,?)",
                                    repository,
                                    tenant,
                                    generation.id(),
                                    generation.draftHead(),
                                    generation.releaseHead(),
                                    generation.publicationVersion(),
                                    generation.synchronizedAtMillis())) {
                        insert.executeUpdate();
                    }
                    try (var insert =
                            connection.prepareStatement(
                                    "INSERT INTO authoring_read_entry"
                                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        for (Entry entry : entries) {
                            var summary = entry.summary();
                            Object[] values = {
                                repository,
                                tenant,
                                generation.id(),
                                summary.documentId(),
                                entry.draftBlob(),
                                entry.releaseBlob(),
                                entry.draftDigest(),
                                entry.releaseDigest(),
                                summary.graphId(),
                                summary.releaseVersion(),
                                summary.revision().draft(),
                                summary.revision().release(),
                                summary.revision().publication(),
                                summary.releasedGraphId(),
                                summary.releasedVersion()
                            };
                            bind(insert, values);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    try (var update =
                            prepare(
                                    connection,
                                    "UPDATE authoring_read_scope SET generation=? WHERE"
                                            + " repository=? AND tenant=?",
                                    generation.id(),
                                    repository,
                                    tenant)) {
                        update.executeUpdate();
                    }
                    var stale = new ArrayList<String>();
                    try (var statement =
                                    prepare(
                                            connection,
                                            "SELECT generation,synchronized_at FROM"
                                                + " authoring_read_generation WHERE repository=?"
                                                + " AND tenant=? ORDER BY synchronized_at"
                                                + " DESC,generation DESC",
                                            repository,
                                            tenant);
                            var rows = statement.executeQuery()) {
                        int count = 0;
                        while (rows.next()) {
                            String id = rows.getString(1);
                            if (!id.equals(generation.id())
                                    && (++count >= GENERATIONS
                                            || rows.getLong(2)
                                                    < generation.synchronizedAtMillis()
                                                            - GENERATION_TTL_MILLIS)) stale.add(id);
                        }
                    }
                    for (String id : stale) {
                        try (var delete =
                                prepare(
                                        connection,
                                        "DELETE FROM authoring_read_entry WHERE repository=? AND"
                                                + " tenant=? AND generation=?",
                                        repository,
                                        tenant,
                                        id)) {
                            delete.executeUpdate();
                        }
                        try (var delete =
                                prepare(
                                        connection,
                                        "DELETE FROM authoring_read_generation WHERE repository=?"
                                                + " AND tenant=? AND generation=?",
                                        repository,
                                        tenant,
                                        id)) {
                            delete.executeUpdate();
                        }
                    }
                    return null;
                });
    }

    @Override
    public Optional<byte[]> content(String repository, String identity) {
        return transaction(
                connection -> {
                    try (var statement =
                                    prepare(
                                            connection,
                                            "SELECT content,digest FROM authoring_read_content"
                                                    + " WHERE repository=? AND identity=?",
                                            repository,
                                            identity);
                            var rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.empty();
                        byte[] bytes = rows.getBytes(1);
                        if (!ReadObjects.sha256(bytes).equals(rows.getString(2)))
                            throw unavailable();
                        return Optional.of(bytes);
                    }
                });
    }

    @Override
    public void retainContent(String repository, String identity, byte[] bytes) {
        if (bytes.length > CONTENT_BYTES) return;
        transaction(
                connection -> {
                    // Global retention bounds span repositories and instances, including concurrent
                    // writers.
                    if (postgres) execute(connection, "SELECT pg_advisory_xact_lock(5862027)");
                    try (var insert =
                            prepare(
                                    connection,
                                    "INSERT INTO authoring_read_content VALUES(?,?,?,?,?) ON"
                                            + " CONFLICT DO NOTHING",
                                    repository,
                                    identity,
                                    ReadObjects.sha256(bytes),
                                    bytes,
                                    System.currentTimeMillis())) {
                        insert.executeUpdate();
                    }
                    long retained = 0;
                    int count = 0;
                    var stale = new ArrayList<String[]>();
                    try (var statement = connection.createStatement();
                            var rows =
                                    statement.executeQuery(
                                            "SELECT repository,identity,length(content) FROM"
                                                    + " authoring_read_content ORDER BY retained_at"
                                                    + " DESC,repository,identity")) {
                        while (rows.next()) {
                            retained += rows.getLong(3);
                            if (++count > CONTENT_ENTRIES || retained > CONTENT_BYTES)
                                stale.add(new String[] {rows.getString(1), rows.getString(2)});
                        }
                    }
                    for (var key : stale)
                        try (var delete =
                                prepare(
                                        connection,
                                        "DELETE FROM authoring_read_content WHERE repository=? AND"
                                                + " identity=?",
                                        (Object[]) key)) {
                            delete.executeUpdate();
                        }
                    return null;
                });
    }

    @Override
    public long retainedBytes() {
        return transaction(
                connection -> {
                    try (var statement = connection.createStatement();
                            var rows =
                                    statement.executeQuery(
                                            "SELECT COALESCE(SUM(length(content)),0) FROM"
                                                    + " authoring_read_content")) {
                        return rows.next() ? rows.getLong(1) : 0L;
                    }
                });
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        String draft = rows.getString("draft_blob"), release = rows.getString("release_blob");
        boolean released = !release.isEmpty();
        String id = rows.getString("document_id");
        return new Entry(
                new DocumentSummary(
                        id,
                        id,
                        rows.getString("graph_id"),
                        rows.getLong("release_version"),
                        new Revision(
                                rows.getString("draft_revision"),
                                rows.getString("release_revision"),
                                rows.getString("publication")),
                        released,
                        released && !"absent".equals(rows.getString("publication")),
                        draft.isEmpty() && released,
                        rows.getString("released_graph_id"),
                        rows.getLong("released_version")),
                draft,
                release,
                rows.getString("draft_digest"),
                rows.getString("release_digest"));
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values)
            throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(30);
        bind(statement, values);
        return statement;
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T transaction(Work<T> work) {
        return transaction(false, work);
    }

    private <T> T transaction(boolean snapshot, Work<T> work) {
        if (closed) throw unavailable();
        for (int attempt = 0; ; attempt++) {
            try (var connection = source.getConnection()) {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(false);
                if (postgres)
                    connection.setTransactionIsolation(
                            snapshot
                                    ? Connection.TRANSACTION_REPEATABLE_READ
                                    : Connection.TRANSACTION_READ_COMMITTED);
                else {
                    try (var statement = connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout=30000");
                    }
                }
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                String state = failure.getSQLState();
                int code = failure.getErrorCode();
                boolean retryable =
                        "40001".equals(state)
                                || "40P01".equals(state)
                                || code == 5
                                || code == 6
                                || code == 517;
                if (!retryable || attempt >= 5 || Thread.currentThread().isInterrupted())
                    throw unavailable();
                try {
                    Thread.sleep(5L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable();
                }
            }
        }
    }

    private static GraphAuthoringException unavailable() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
    }

    @Override
    public void close() {
        closed = true;
        if (owner != null)
            try {
                owner.close();
            } catch (Exception failure) {
                throw unavailable();
            }
    }
}
