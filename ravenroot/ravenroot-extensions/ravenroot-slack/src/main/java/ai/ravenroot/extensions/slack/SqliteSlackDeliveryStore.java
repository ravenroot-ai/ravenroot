package ai.ravenroot.extensions.slack;

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

/** Bounded durable binding of a Slack delivery identity to its signed body digest. */
final class SqliteSlackDeliveryStore implements SlackDeliveryStore {
    private static final long COMPLETION_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private final SlackConfiguration.StorePolicy policy;
    private final Clock clock;
    private final String url;

    SqliteSlackDeliveryStore(SlackConfiguration.StorePolicy policy) { this(policy, Clock.systemUTC()); }
    SqliteSlackDeliveryStore(SlackConfiguration.StorePolicy policy, Clock clock) {
        this.policy = java.util.Objects.requireNonNull(policy); this.clock = java.util.Objects.requireNonNull(clock);
        try { if (policy.path().getParent() != null) Files.createDirectories(policy.path().getParent()); }
        catch (IOException failure) { throw unavailable(); }
        url = "jdbc:sqlite:" + policy.path(); migrate();
    }

    @Override public Decision bind(String tenant, String profile, String kind, String deliveryId, String digest,
                                   long deadlineNanos, CancellationSignal cancellation) {
        safe(tenant, 160); safe(profile, 64); safe(kind, 32); safe(deliveryId, 128);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new SlackException(SlackException.Code.INVALID_INPUT);
        java.util.Objects.requireNonNull(cancellation);
        long storeDeadline = Math.subtractExact(deadlineNanos, COMPLETION_MARGIN_NANOS);
        requireLive(storeDeadline, cancellation);
        long now = clock.millis();
        AtomicReference<SQLiteConnection> active = new AtomicReference<>();
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
            requireLive(storeDeadline, cancellation);
            begin(connection); prune(connection, tenant, profile, now);
            requireLive(storeDeadline, cancellation);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT body_digest FROM slack_deliveries
                    WHERE tenant_id=? AND profile_name=? AND delivery_kind=? AND delivery_id=?
                    """)) {
                bind(select, tenant, profile, kind, deliveryId);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        if (!digest.equals(result.getString(1))) {
                            rollback(connection); throw new SlackException(SlackException.Code.FORBIDDEN);
                        }
                        requireLive(storeDeadline, cancellation);
                        commit(connection); return Decision.REPLAY;
                    }
                }
            }
            if (count(connection, tenant, profile) >= policy.maxDeliveries()) {
                rollback(connection); throw new SlackException(SlackException.Code.CAPACITY);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO slack_deliveries
                    (tenant_id,profile_name,delivery_kind,delivery_id,body_digest,updated_ms)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                bind(insert, tenant, profile, kind, deliveryId);
                insert.setString(5, digest); insert.setLong(6, now); insert.executeUpdate();
            }
            requireLive(storeDeadline, cancellation);
            commit(connection); return Decision.FIRST_SEEN;
        } catch (SlackException failure) { throw failure; }
        catch (SQLException failure) {
            if (cancellation.cancelled()) throw new SlackException(SlackException.Code.CANCELLED);
            throw unavailable();
        } finally { active.set(null); }
    }

    private void migrate() {
        try (Connection connection = open(3_000); Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) { version = result.next() ? result.getInt(1) : 0; }
            if (version > 1) throw unavailable();
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS slack_deliveries (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, delivery_kind TEXT NOT NULL,
                      delivery_id TEXT NOT NULL, body_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id,profile_name,delivery_kind,delivery_id))
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
    private static boolean live(long deadlineNanos, CancellationSignal cancellation) {
        return !cancellation.cancelled() && System.nanoTime() < deadlineNanos;
    }
    private static void requireLive(long deadlineNanos, CancellationSignal cancellation) {
        if (!live(deadlineNanos, cancellation)) throw new SlackException(cancellation.cancelled()
                ? SlackException.Code.CANCELLED : SlackException.Code.DURABILITY_UNAVAILABLE);
    }
    private static void interrupt(SQLiteConnection connection) {
        if (connection == null) return;
        try { connection.getDatabase().interrupt(); } catch (SQLException ignored) { }
    }
    private void prune(Connection connection, String tenant, String profile, long now) throws SQLException {
        long cutoff = now - java.time.Duration.ofHours(policy.retentionHours()).toMillis();
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM slack_deliveries WHERE tenant_id=? AND profile_name=? AND updated_ms<?")) {
            delete.setString(1, tenant); delete.setString(2, profile); delete.setLong(3, cutoff); delete.executeUpdate();
        }
    }
    private static long count(Connection connection, String tenant, String profile) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM slack_deliveries WHERE tenant_id=? AND profile_name=?")) {
            select.setString(1, tenant); select.setString(2, profile);
            try (ResultSet result = select.executeQuery()) { return result.next() ? result.getLong(1) : Long.MAX_VALUE; }
        }
    }
    private static void begin(Connection connection) throws SQLException { try (Statement s = connection.createStatement()) { s.execute("BEGIN IMMEDIATE"); } }
    private static void commit(Connection connection) throws SQLException { try (Statement s = connection.createStatement()) { s.execute("COMMIT"); } }
    private static void rollback(Connection connection) { try (Statement s = connection.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException ignored) { } }
    private static void bind(PreparedStatement statement, String tenant, String profile,
                             String kind, String deliveryId) throws SQLException {
        statement.setString(1, tenant); statement.setString(2, profile);
        statement.setString(3, kind); statement.setString(4, deliveryId);
    }
    private static void safe(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new SlackException(SlackException.Code.INVALID_INPUT);
    }
    private static SlackException unavailable() { return new SlackException(SlackException.Code.DURABILITY_UNAVAILABLE); }
}
