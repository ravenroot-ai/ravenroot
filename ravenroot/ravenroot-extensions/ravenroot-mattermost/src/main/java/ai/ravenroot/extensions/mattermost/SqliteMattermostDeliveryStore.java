package ai.ravenroot.extensions.mattermost;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Bounded durable binding of a tenant/profile/source/post identity to its body digest. */
final class SqliteMattermostDeliveryStore implements MattermostDeliveryStore {
    private static final long COMPLETION_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private final MattermostConfiguration.StorePolicy policy;
    private final Clock clock;
    private final String url;

    SqliteMattermostDeliveryStore(MattermostConfiguration.StorePolicy policy) { this(policy, Clock.systemUTC()); }
    SqliteMattermostDeliveryStore(MattermostConfiguration.StorePolicy policy, Clock clock) {
        this.policy = java.util.Objects.requireNonNull(policy); this.clock = java.util.Objects.requireNonNull(clock);
        try { if (policy.path().getParent() != null) Files.createDirectories(policy.path().getParent()); }
        catch (IOException failure) { throw unavailable(); }
        url = "jdbc:sqlite:" + policy.path(); migrate();
    }

    @Override public Decision bind(String tenant, String profile, String source, String postId, String digest,
                                   long deadlineNanos, CancellationSignal cancellation) {
        safe(tenant, 160); safe(profile, 64); safe(source, 128); MattermostProfile.id(postId);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw invalid();
        java.util.Objects.requireNonNull(cancellation);
        long storeDeadline;
        try { storeDeadline = Math.subtractExact(deadlineNanos, COMPLETION_MARGIN_NANOS); }
        catch (ArithmeticException failure) { throw unavailable(); }
        requireLive(storeDeadline, cancellation);
        long now = clock.millis(); AtomicReference<SQLiteConnection> active = new AtomicReference<>();
        cancellation.onCancel(() -> interrupt(active.get()));
        try (Connection raw = open(0)) {
            if (!(raw instanceof SQLiteConnection connection)) throw unavailable();
            active.set(connection);
            BusyHandler.setHandler(connection, new BusyHandler() {
                @Override protected int callback(int previousInvocations) {
                    if (!live(storeDeadline, cancellation)) return 0;
                    LockSupport.parkNanos(Math.min(POLL_NANOS, storeDeadline - System.nanoTime()));
                    return live(storeDeadline, cancellation) ? 1 : 0;
                }
            });
            requireLive(storeDeadline, cancellation); begin(connection); prune(connection, tenant, profile, source, now);
            requireLive(storeDeadline, cancellation);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT body_digest FROM mattermost_deliveries
                    WHERE tenant_id=? AND profile_name=? AND source_id=? AND post_id=?
                    """)) {
                bind(select, tenant, profile, source, postId);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        if (!digest.equals(result.getString(1))) {
                            rollback(connection); throw new MattermostException(MattermostException.Code.FORBIDDEN);
                        }
                        requireLive(storeDeadline, cancellation); commit(connection); return Decision.REPLAY;
                    }
                }
            }
            if (count(connection, tenant, profile, source) >= policy.maxDeliveries()) {
                rollback(connection); throw new MattermostException(MattermostException.Code.CAPACITY);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO mattermost_deliveries
                    (tenant_id,profile_name,source_id,post_id,body_digest,updated_ms) VALUES(?,?,?,?,?,?)
                    """)) {
                bind(insert, tenant, profile, source, postId);
                insert.setString(5, digest); insert.setLong(6, now); insert.executeUpdate();
            }
            requireLive(storeDeadline, cancellation); commit(connection); return Decision.FIRST_SEEN;
        } catch (MattermostException failure) { throw failure; }
        catch (SQLException failure) {
            if (cancellation.cancelled()) throw new MattermostException(MattermostException.Code.CANCELLED);
            throw unavailable();
        } finally { active.set(null); }
    }

    private void migrate() {
        try (Connection connection = open(3_000); Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version > 1) throw unavailable();
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mattermost_deliveries (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, source_id TEXT NOT NULL,
                      post_id TEXT NOT NULL, body_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id,profile_name,source_id,post_id))
                    """);
            statement.executeUpdate("PRAGMA user_version=1");
        } catch (SQLException failure) { throw unavailable(); }
    }
    private Connection open(int busyTimeoutMs) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        if (!(connection instanceof SQLiteConnection sqlite)) throw new SQLException("unsupported SQLite driver");
        sqlite.setBusyTimeout(busyTimeoutMs);
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }
    private void prune(Connection connection, String tenant, String profile, String source, long now) throws SQLException {
        long cutoff = now - java.time.Duration.ofHours(policy.retentionHours()).toMillis();
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM mattermost_deliveries
                WHERE tenant_id=? AND profile_name=? AND source_id=? AND updated_ms<?
                """)) {
            delete.setString(1, tenant); delete.setString(2, profile); delete.setString(3, source);
            delete.setLong(4, cutoff); delete.executeUpdate();
        }
    }
    private static long count(Connection connection, String tenant, String profile, String source) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT COUNT(*) FROM mattermost_deliveries WHERE tenant_id=? AND profile_name=? AND source_id=?
                """)) {
            select.setString(1, tenant); select.setString(2, profile); select.setString(3, source);
            try (ResultSet result = select.executeQuery()) { return result.next() ? result.getLong(1) : Long.MAX_VALUE; }
        }
    }
    private static void bind(PreparedStatement statement, String tenant, String profile,
                             String source, String postId) throws SQLException {
        statement.setString(1, tenant); statement.setString(2, profile);
        statement.setString(3, source); statement.setString(4, postId);
    }
    private static void begin(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
    }
    private static void commit(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute("COMMIT"); }
    }
    private static void rollback(Connection c) {
        try (Statement s = c.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException ignored) { }
    }
    private static boolean live(long deadlineNanos, CancellationSignal cancellation) {
        return !cancellation.cancelled() && System.nanoTime() < deadlineNanos;
    }
    private static void requireLive(long deadlineNanos, CancellationSignal cancellation) {
        if (!live(deadlineNanos, cancellation)) throw new MattermostException(cancellation.cancelled()
                ? MattermostException.Code.CANCELLED : MattermostException.Code.DURABILITY_UNAVAILABLE);
    }
    private static void interrupt(SQLiteConnection connection) {
        if (connection == null) return;
        try { connection.getDatabase().interrupt(); } catch (SQLException ignored) { }
    }
    private static void safe(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw invalid();
    }
    private static MattermostException invalid() { return new MattermostException(MattermostException.Code.INVALID_INPUT); }
    private static MattermostException unavailable() {
        return new MattermostException(MattermostException.Code.DURABILITY_UNAVAILABLE);
    }
}
