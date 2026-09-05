package ai.ravenroot.extensions.discord;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;

/** Bounded durable binding of a Discord interaction identity to its signed body digest. */
final class SqliteDiscordDeliveryStore implements DiscordDeliveryStore {
    private final DiscordConfiguration.StorePolicy policy;
    private final Clock clock;
    private final String url;

    SqliteDiscordDeliveryStore(DiscordConfiguration.StorePolicy policy) { this(policy, Clock.systemUTC()); }
    SqliteDiscordDeliveryStore(DiscordConfiguration.StorePolicy policy, Clock clock) {
        this.policy = java.util.Objects.requireNonNull(policy); this.clock = java.util.Objects.requireNonNull(clock);
        try { if (policy.path().getParent() != null) Files.createDirectories(policy.path().getParent()); }
        catch (IOException failure) { throw unavailable(); }
        url = "jdbc:sqlite:" + policy.path(); migrate();
    }

    @Override public synchronized Decision bind(String tenant, String profile, String applicationId,
                                                  String interactionId, String digest) {
        safe(tenant, 160); safe(profile, 64); snowflake(applicationId); snowflake(interactionId);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new DiscordException(DiscordException.Code.INVALID_INPUT);
        long now = clock.millis();
        try (Connection connection = open()) {
            begin(connection); prune(connection, tenant, profile, now);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT body_digest FROM discord_deliveries
                    WHERE tenant_id=? AND profile_name=? AND application_id=? AND interaction_id=?
                    """)) {
                bind(select, tenant, profile, applicationId, interactionId);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        if (!digest.equals(result.getString(1))) {
                            rollback(connection); throw new DiscordException(DiscordException.Code.FORBIDDEN);
                        }
                        commit(connection); return Decision.REPLAY;
                    }
                }
            }
            if (count(connection, tenant, profile) >= policy.maxDeliveries()) {
                rollback(connection); throw new DiscordException(DiscordException.Code.CAPACITY);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO discord_deliveries
                    (tenant_id,profile_name,application_id,interaction_id,body_digest,updated_ms)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                bind(insert, tenant, profile, applicationId, interactionId);
                insert.setString(5, digest); insert.setLong(6, now); insert.executeUpdate();
            }
            commit(connection); return Decision.FIRST_SEEN;
        } catch (DiscordException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    private void migrate() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) { version = result.next() ? result.getInt(1) : 0; }
            if (version > 1) throw unavailable();
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS discord_deliveries (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, application_id TEXT NOT NULL,
                      interaction_id TEXT NOT NULL, body_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id,profile_name,application_id,interaction_id))
                    """);
            statement.executeUpdate("PRAGMA user_version=1");
        } catch (SQLException failure) { throw unavailable(); }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=3000"); statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }
    private void prune(Connection connection, String tenant, String profile, long now) throws SQLException {
        long cutoff = now - java.time.Duration.ofHours(policy.retentionHours()).toMillis();
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM discord_deliveries WHERE tenant_id=? AND profile_name=? AND updated_ms<?")) {
            delete.setString(1, tenant); delete.setString(2, profile); delete.setLong(3, cutoff); delete.executeUpdate();
        }
    }
    private static long count(Connection connection, String tenant, String profile) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM discord_deliveries WHERE tenant_id=? AND profile_name=?")) {
            select.setString(1, tenant); select.setString(2, profile);
            try (ResultSet result = select.executeQuery()) { return result.next() ? result.getLong(1) : Long.MAX_VALUE; }
        }
    }
    private static void begin(Connection connection) throws SQLException { try (Statement s = connection.createStatement()) { s.execute("BEGIN IMMEDIATE"); } }
    private static void commit(Connection connection) throws SQLException { try (Statement s = connection.createStatement()) { s.execute("COMMIT"); } }
    private static void rollback(Connection connection) { try (Statement s = connection.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException ignored) { } }
    private static void bind(PreparedStatement statement, String tenant, String profile,
                             String applicationId, String interactionId) throws SQLException {
        statement.setString(1, tenant); statement.setString(2, profile);
        statement.setString(3, applicationId); statement.setString(4, interactionId);
    }
    private static void safe(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new DiscordException(DiscordException.Code.INVALID_INPUT);
    }
    private static void snowflake(String value) { DiscordProfile.snowflake(value); }
    private static DiscordException unavailable() { return new DiscordException(DiscordException.Code.DURABILITY_UNAVAILABLE); }
}
