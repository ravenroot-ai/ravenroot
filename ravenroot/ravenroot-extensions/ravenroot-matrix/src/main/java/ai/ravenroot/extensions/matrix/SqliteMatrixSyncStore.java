package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.execution.CancellationSignal;
import org.sqlite.BusyHandler;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Per-source Matrix sync cursor and event/body bindings. */
final class SqliteMatrixSyncStore implements MatrixSyncStore {
    private static final long MARGIN = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long POLL = TimeUnit.MILLISECONDS.toNanos(5);
    private final MatrixConfiguration.StorePolicy policy;
    private final Clock clock;
    private final String url;

    SqliteMatrixSyncStore(MatrixConfiguration.StorePolicy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy); this.clock = Objects.requireNonNull(clock);
        try { if (policy.path().getParent() != null) Files.createDirectories(policy.path().getParent()); }
        catch (IOException failure) { throw unavailable(); }
        url = "jdbc:sqlite:" + policy.path(); migrate();
    }

    @Override public String cursor(SourceKey source) {
        validate(source);
        try (Connection connection = open(3_000); PreparedStatement select = connection.prepareStatement("""
                SELECT cursor_value FROM matrix_sync_sources
                WHERE tenant_id=? AND profile_name=? AND deployment_id=? AND node_id=?
                """)) {
            bindSource(select, source);
            try (ResultSet result = select.executeQuery()) { return result.next() ? result.getString(1) : null; }
        } catch (SQLException failure) { throw unavailable(); }
    }

    @Override public Decision bindEvent(SourceKey source, String eventId, String digest,
                                        long deadlineNanos, CancellationSignal cancellation) {
        validate(source); safe(eventId, 255);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw invalid();
        long deadline = Math.subtractExact(deadlineNanos, MARGIN);
        AtomicReference<SQLiteConnection> active = new AtomicReference<>();
        cancellation.onCancel(() -> interrupt(active.get()));
        try (Connection raw = open(0)) {
            SQLiteConnection connection = sqlite(raw); active.set(connection); busy(connection, deadline, cancellation);
            requireLive(deadline, cancellation); begin(connection); prune(connection, source, clock.millis());
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT body_digest FROM matrix_sync_deliveries
                    WHERE tenant_id=? AND profile_name=? AND deployment_id=? AND node_id=? AND event_id=?
                    """)) {
                bindSource(select, source); select.setString(5, eventId);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        if (!digest.equals(result.getString(1))) { rollback(connection); throw forbidden(); }
                        requireLive(deadline, cancellation); commit(connection); return Decision.REPLAY;
                    }
                }
            }
            if (countDeliveries(connection, source) >= policy.maxDeliveries()) {
                rollback(connection); throw new MatrixException(MatrixException.Code.CAPACITY);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO matrix_sync_deliveries
                    (tenant_id,profile_name,deployment_id,node_id,event_id,body_digest,updated_ms)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                bindSource(insert, source); insert.setString(5, eventId); insert.setString(6, digest);
                insert.setLong(7, clock.millis()); insert.executeUpdate();
            }
            requireLive(deadline, cancellation); commit(connection); return Decision.FIRST_SEEN;
        } catch (MatrixException failure) { throw failure; }
        catch (SQLException failure) { throw cancellation.cancelled() ? cancelled() : unavailable(); }
        finally { active.set(null); }
    }

    @Override public void advance(SourceKey source, String expected, String next,
                                  long deadlineNanos, CancellationSignal cancellation) {
        validate(source); if (expected != null) MatrixProfile.opaque(expected, 2_048); MatrixProfile.opaque(next, 2_048);
        long deadline = Math.subtractExact(deadlineNanos, MARGIN);
        AtomicReference<SQLiteConnection> active = new AtomicReference<>();
        cancellation.onCancel(() -> interrupt(active.get()));
        try (Connection raw = open(0)) {
            SQLiteConnection connection = sqlite(raw); active.set(connection); busy(connection, deadline, cancellation);
            requireLive(deadline, cancellation); begin(connection);
            String actual = selectCursor(connection, source);
            if (!Objects.equals(actual, expected)) { rollback(connection); throw forbidden(); }
            if (actual == null) {
                if (countSources(connection) >= policy.maxSources()) {
                    rollback(connection); throw new MatrixException(MatrixException.Code.CAPACITY);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO matrix_sync_sources
                        (tenant_id,profile_name,deployment_id,node_id,cursor_value,updated_ms) VALUES(?,?,?,?,?,?)
                        """)) {
                    bindSource(insert, source); insert.setString(5, next); insert.setLong(6, clock.millis());
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE matrix_sync_sources SET cursor_value=?,updated_ms=?
                        WHERE tenant_id=? AND profile_name=? AND deployment_id=? AND node_id=?
                        """)) {
                    update.setString(1, next); update.setLong(2, clock.millis());
                    update.setString(3, source.tenant()); update.setString(4, source.profile());
                    update.setString(5, source.deployment()); update.setString(6, source.node());
                    if (update.executeUpdate() != 1) { rollback(connection); throw unavailable(); }
                }
            }
            requireLive(deadline, cancellation); commit(connection);
        } catch (MatrixException failure) { throw failure; }
        catch (SQLException failure) { throw cancellation.cancelled() ? cancelled() : unavailable(); }
        finally { active.set(null); }
    }

    private void migrate() {
        try (Connection connection = open(3_000); Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version > 1) throw unavailable();
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS matrix_sync_sources (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, deployment_id TEXT NOT NULL,
                      node_id TEXT NOT NULL, cursor_value TEXT NOT NULL, updated_ms INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id,profile_name,deployment_id,node_id))
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS matrix_sync_deliveries (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, deployment_id TEXT NOT NULL,
                      node_id TEXT NOT NULL, event_id TEXT NOT NULL, body_digest TEXT NOT NULL,
                      updated_ms INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id,profile_name,deployment_id,node_id,event_id))
                    """);
            statement.executeUpdate("PRAGMA user_version=1");
        } catch (SQLException failure) { throw unavailable(); }
    }

    private Connection open(int timeout) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        sqlite(connection).setBusyTimeout(timeout);
        return connection;
    }
    private static SQLiteConnection sqlite(Connection connection) throws SQLException {
        if (connection instanceof SQLiteConnection sqlite) return sqlite;
        throw new SQLException("unsupported SQLite driver");
    }
    private static void busy(SQLiteConnection connection, long deadline, CancellationSignal cancellation)
            throws SQLException {
        BusyHandler.setHandler(connection, new BusyHandler() {
            @Override protected int callback(int ignored) {
                if (!live(deadline, cancellation)) return 0;
                LockSupport.parkNanos(Math.min(POLL, Math.max(1, deadline - System.nanoTime())));
                return live(deadline, cancellation) ? 1 : 0;
            }
        });
    }
    private static String selectCursor(Connection connection, SourceKey source) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT cursor_value FROM matrix_sync_sources
                WHERE tenant_id=? AND profile_name=? AND deployment_id=? AND node_id=?
                """)) {
            bindSource(select, source);
            try (ResultSet result = select.executeQuery()) { return result.next() ? result.getString(1) : null; }
        }
    }
    private void prune(Connection connection, SourceKey source, long now) throws SQLException {
        long cutoff = now - java.time.Duration.ofHours(policy.retentionHours()).toMillis();
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM matrix_sync_deliveries
                WHERE tenant_id=? AND profile_name=? AND deployment_id=? AND node_id=? AND updated_ms<?
                """)) {
            bindSource(delete, source); delete.setLong(5, cutoff); delete.executeUpdate();
        }
    }
    private static long countDeliveries(Connection connection, SourceKey source) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT COUNT(*) FROM matrix_sync_deliveries
                WHERE tenant_id=? AND profile_name=? AND deployment_id=? AND node_id=?
                """)) {
            bindSource(select, source);
            try (ResultSet result = select.executeQuery()) { return result.next() ? result.getLong(1) : Long.MAX_VALUE; }
        }
    }
    private static long countSources(Connection connection) throws SQLException {
        try (Statement select = connection.createStatement(); ResultSet result = select.executeQuery(
                "SELECT COUNT(*) FROM matrix_sync_sources")) {
            return result.next() ? result.getLong(1) : Long.MAX_VALUE;
        }
    }
    private static void bindSource(PreparedStatement statement, SourceKey source) throws SQLException {
        statement.setString(1, source.tenant()); statement.setString(2, source.profile());
        statement.setString(3, source.deployment()); statement.setString(4, source.node());
    }
    private static void validate(SourceKey source) {
        Objects.requireNonNull(source); safe(source.tenant(), 160); safe(source.profile(), 64);
        safe(source.deployment(), 255); safe(source.node(), 255);
    }
    private static void safe(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw invalid();
    }
    private static boolean live(long deadline, CancellationSignal cancellation) {
        return !cancellation.cancelled() && System.nanoTime() < deadline;
    }
    private static void requireLive(long deadline, CancellationSignal cancellation) {
        if (!live(deadline, cancellation)) throw cancellation.cancelled() ? cancelled() : unavailable();
    }
    private static void interrupt(SQLiteConnection connection) {
        if (connection == null) return;
        try { connection.getDatabase().interrupt(); } catch (SQLException ignored) { }
    }
    private static void begin(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }
    private static void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
    }
    private static void rollback(Connection connection) {
        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
        catch (SQLException ignored) { }
    }
    private static MatrixException invalid() { return new MatrixException(MatrixException.Code.INVALID_INPUT); }
    private static MatrixException forbidden() { return new MatrixException(MatrixException.Code.FORBIDDEN); }
    private static MatrixException unavailable() { return new MatrixException(MatrixException.Code.DURABILITY_UNAVAILABLE); }
    private static MatrixException cancelled() { return new MatrixException(MatrixException.Code.CANCELLED); }
}
