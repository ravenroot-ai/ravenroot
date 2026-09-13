package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.memory.SessionMemoryStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** SQLite implementation of the isolated session-memory SPI. */
public final class SqliteSessionMemoryStore implements SessionMemoryStore {
    private final Clock clock;
    private final int maximumValueBytes;
    private final Connection connection;

    public SqliteSessionMemoryStore(Path databaseFile, Clock clock) {
        this(databaseFile, clock, DEFAULT_MAX_VALUE_BYTES);
    }

    public SqliteSessionMemoryStore(Path databaseFile, Clock clock, int maximumValueBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumValueBytes < 1) throw new IllegalArgumentException("maximumValueBytes must be positive");
        this.maximumValueBytes = maximumValueBytes;
        try {
            var location = SqliteStoreLocation.ofFile(databaseFile);
            location.prepare();
            connection = DriverManager.getConnection("jdbc:sqlite:" + location.databaseFile());
            SqliteSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            throw new IllegalStateException("session memory store unavailable", failed);
        }
    }

    @Override public int maximumValueBytes() { return maximumValueBytes; }

    @Override
    public synchronized CompletionStage<Optional<Entry>> get(Key key) {
        Objects.requireNonNull(key, "key");
        try {
            Entry found = select("session_memory", "", key, null);
            if (found != null && !found.expiresAt().isAfter(clock.instant())) {
                deleteRow(key);
                found = null;
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(found));
        } catch (SQLException failed) {
            return unavailable(failed);
        }
    }

    @Override
    public synchronized CompletionStage<Entry> put(Write write) {
        Objects.requireNonNull(write, "write");
        if (write.value().length > maximumValueBytes) {
            return failed(new Failure.TooLarge(maximumValueBytes, write.value().length));
        }
        Instant now = clock.instant();
        if (!write.expiresAt().isAfter(now)) {
            return failed(new Failure.InvalidRequest("expiresAt must be in the future"));
        }
        try {
            connection.setAutoCommit(false);
            try {
                String digest = digest(write);
                Entry replay = select("session_memory_command", "", write.key(), write.idempotencyKey());
                if (replay != null) {
                    String priorDigest;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT digest FROM session_memory_command WHERE tenant_id=? AND command_key=?")) {
                        statement.setString(1, write.key().tenantId());
                        statement.setString(2, write.idempotencyKey());
                        try (ResultSet rows = statement.executeQuery()) { rows.next(); priorDigest = rows.getString(1); }
                    }
                    if (!priorDigest.equals(digest)) throw new StoreException(
                            new Failure.IdempotencyConflict(write.idempotencyKey()));
                    connection.commit();
                    return CompletableFuture.completedFuture(replay);
                }
                Entry current = select("session_memory", "", write.key(), null);
                if (current != null && !current.expiresAt().isAfter(now)) {
                    deleteRow(write.key());
                    current = null;
                }
                if (!matches(write.expectation(), current)) throw new StoreException(
                        new Failure.Conflict(current == null ? 0 : current.revision()));
                Entry next = new Entry(write.key(), current == null ? 1 : current.revision() + 1,
                        write.value(), write.contentType(), current == null ? now : current.createdAt(),
                        now, write.expiresAt());
                upsert(next);
                insertLedger(write.idempotencyKey(), digest, next);
                connection.commit();
                return CompletableFuture.completedFuture(next);
            } catch (RuntimeException | SQLException failed) {
                connection.rollback();
                if (failed instanceof StoreException storeFailure) {
                    return CompletableFuture.failedFuture(storeFailure);
                }
                if (failed instanceof SQLException sqlFailure) return unavailable(sqlFailure);
                return CompletableFuture.failedFuture(failed);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failed) {
            return unavailable(failed);
        }
    }

    @Override
    public synchronized CompletionStage<Boolean> delete(Key key, Expectation expectation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expectation, "expectation");
        try {
            Entry current = select("session_memory", "", key, null);
            if (current == null || !current.expiresAt().isAfter(clock.instant())) {
                if (current != null) deleteRow(key);
                return expectation instanceof Expectation.Exactly
                        ? failed(new Failure.NotFound()) : CompletableFuture.completedFuture(false);
            }
            if (!matches(expectation, current)) return failed(new Failure.Conflict(current.revision()));
            deleteRow(key);
            return CompletableFuture.completedFuture(true);
        } catch (SQLException failed) {
            return unavailable(failed);
        }
    }

    @Override
    public synchronized CompletionStage<Long> purgeExpired(String tenantId, Instant through, int limit) {
        if (tenantId == null || tenantId.isBlank() || through == null || limit < 1) {
            return failed(new Failure.InvalidRequest("tenant, time, and positive limit are required"));
        }
        String sql = "DELETE FROM session_memory WHERE rowid IN (SELECT rowid FROM session_memory "
                + "WHERE tenant_id=? AND (expires_at_epoch_second < ? OR "
                + "(expires_at_epoch_second=? AND expires_at_nano<=?)) "
                + "ORDER BY expires_at_epoch_second, expires_at_nano LIMIT ?)";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenantId);
                statement.setLong(2, through.getEpochSecond());
                statement.setLong(3, through.getEpochSecond());
                statement.setInt(4, through.getNano());
                statement.setInt(5, limit);
                long purged = statement.executeUpdate();
                purgeLedger(tenantId, through);
                connection.commit();
                return CompletableFuture.completedFuture(purged);
            } catch (SQLException failed) {
                connection.rollback();
                return unavailable(failed);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failed) {
            return unavailable(failed);
        }
    }

    private void purgeLedger(String tenantId, Instant through) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM session_memory_command WHERE tenant_id=? AND "
                        + "(expires_at_epoch_second<? OR "
                        + "(expires_at_epoch_second=? AND expires_at_nano<=?))")) {
            statement.setString(1, tenantId);
            statement.setLong(2, through.getEpochSecond());
            statement.setLong(3, through.getEpochSecond());
            statement.setInt(4, through.getNano());
            statement.executeUpdate();
        }
    }

    private Entry select(String table, String ignored, Key key, String commandKey) throws SQLException {
        boolean ledger = commandKey != null;
        String where = ledger ? "tenant_id=? AND command_key=?"
                : "tenant_id=? AND session_id=? AND scope=? AND process_instance_id=? AND node_id=?";
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE " + where)) {
            statement.setString(1, key.tenantId());
            if (ledger) statement.setString(2, commandKey); else bindKey(statement, 2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Key storedKey = new Key(rows.getString("tenant_id"), rows.getString("session_id"),
                        Scope.valueOf(rows.getString("scope")), uuid(rows.getString("process_instance_id")),
                        empty(rows.getString("node_id")));
                return new Entry(storedKey, rows.getLong("revision"), rows.getBytes("value"),
                        rows.getString("content_type"), instant(rows, "created_at"),
                        instant(rows, "updated_at"), instant(rows, "expires_at"));
            }
        }
    }

    private void upsert(Entry entry) throws SQLException {
        String sql = "INSERT INTO session_memory VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(tenant_id,session_id,scope,process_instance_id,node_id) DO UPDATE SET "
                + "revision=excluded.revision,value=excluded.value,content_type=excluded.content_type,"
                + "updated_at_epoch_second=excluded.updated_at_epoch_second,updated_at_nano=excluded.updated_at_nano,"
                + "expires_at_epoch_second=excluded.expires_at_epoch_second,expires_at_nano=excluded.expires_at_nano";
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bindEntry(statement, 1, entry); statement.executeUpdate(); }
    }

    private void insertLedger(String commandKey, String digest, Entry entry) throws SQLException {
        String sql = "INSERT INTO session_memory_command VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.key().tenantId());
            statement.setString(2, commandKey);
            statement.setString(3, digest);
            bindEntryWithoutTenant(statement, 4, entry);
            statement.executeUpdate();
        }
    }

    private void deleteRow(Key key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM session_memory WHERE "
                + "tenant_id=? AND session_id=? AND scope=? AND process_instance_id=? AND node_id=?")) {
            statement.setString(1, key.tenantId()); bindKey(statement, 2, key); statement.executeUpdate();
        }
    }

    private static int bindEntry(PreparedStatement s, int i, Entry e) throws SQLException {
        s.setString(i++, e.key().tenantId()); return bindEntryWithoutTenant(s, i, e);
    }
    private static int bindEntryWithoutTenant(PreparedStatement s, int i, Entry e) throws SQLException {
        s.setString(i++, e.key().sessionId()); s.setString(i++, e.key().scope().name());
        s.setString(i++, e.key().processInstanceId() == null ? "" : e.key().processInstanceId().toString());
        s.setString(i++, e.key().nodeId() == null ? "" : e.key().nodeId()); s.setLong(i++, e.revision());
        s.setBytes(i++, e.value()); s.setString(i++, e.contentType());
        i = bindInstant(s, i, e.createdAt()); i = bindInstant(s, i, e.updatedAt()); return bindInstant(s, i, e.expiresAt());
    }
    private static void bindKey(PreparedStatement s, int i, Key key) throws SQLException {
        s.setString(i++, key.sessionId()); s.setString(i++, key.scope().name());
        s.setString(i++, key.processInstanceId() == null ? "" : key.processInstanceId().toString());
        s.setString(i, key.nodeId() == null ? "" : key.nodeId());
    }
    private static int bindInstant(PreparedStatement s, int i, Instant value) throws SQLException {
        s.setLong(i++, value.getEpochSecond()); s.setInt(i++, value.getNano()); return i;
    }
    private static Instant instant(ResultSet r, String prefix) throws SQLException {
        return Instant.ofEpochSecond(r.getLong(prefix + "_epoch_second"), r.getInt(prefix + "_nano"));
    }
    private static UUID uuid(String value) { return value == null || value.isEmpty() ? null : UUID.fromString(value); }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }
    private static boolean matches(Expectation e, Entry current) {
        return e instanceof Expectation.Any || e instanceof Expectation.Absent && current == null
                || e instanceof Expectation.Exactly exact && current != null && exact.revision() == current.revision();
    }
    private static String digest(Write write) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(write.key().toString().getBytes(StandardCharsets.UTF_8)); d.update((byte) 0);
            d.update(write.value()); d.update((byte) 0);
            d.update(write.contentType().getBytes(StandardCharsets.UTF_8)); d.update((byte) 0);
            d.update(write.expiresAt().toString().getBytes(StandardCharsets.UTF_8)); d.update((byte) 0);
            d.update(write.expectation().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> CompletionStage<T> failed(Failure failure) {
        return CompletableFuture.failedFuture(new StoreException(failure));
    }
    private static <T> CompletionStage<T> unavailable(SQLException failed) {
        return CompletableFuture.failedFuture(new StoreException(new Failure.Unavailable()));
    }
    @Override public synchronized void close() {
        try { connection.close(); } catch (SQLException failed) { throw new IllegalStateException("close failed", failed); }
    }
}
