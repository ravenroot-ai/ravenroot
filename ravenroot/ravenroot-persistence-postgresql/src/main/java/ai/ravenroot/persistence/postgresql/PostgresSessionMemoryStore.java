package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.memory.SessionMemoryStore;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
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

/** Shared PostgreSQL implementation of tenant-scoped session memory. */
public final class PostgresSessionMemoryStore implements SessionMemoryStore {
    private final DataSource dataSource;
    private final Clock clock;
    private final int maximumValueBytes;

    public PostgresSessionMemoryStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, DEFAULT_MAX_VALUE_BYTES, PostgresStoreConfig.defaults());
    }

    public PostgresSessionMemoryStore(DataSource dataSource, Clock clock, int maximumValueBytes,
                                      PostgresStoreConfig config) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(config, "config");
        if (maximumValueBytes < 1) throw new IllegalArgumentException("maximumValueBytes must be positive");
        this.maximumValueBytes = maximumValueBytes;
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            throw new IllegalStateException("session memory store unavailable", failed);
        }
    }

    @Override public int maximumValueBytes() { return maximumValueBytes; }

    @Override
    public CompletionStage<Optional<Entry>> get(Key key) {
        Objects.requireNonNull(key, "key");
        try (Connection connection = dataSource.getConnection()) {
            Entry entry = select(connection, "session_memory", key, null, false);
            if (entry != null && !entry.expiresAt().isAfter(clock.instant())) {
                deleteRow(connection, key);
                entry = null;
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(entry));
        } catch (SQLException failed) { return unavailable(failed); }
    }

    @Override
    public CompletionStage<Entry> put(Write write) {
        Objects.requireNonNull(write, "write");
        if (write.value().length > maximumValueBytes) {
            return failed(new Failure.TooLarge(maximumValueBytes, write.value().length));
        }
        Instant now = clock.instant();
        if (!write.expiresAt().isAfter(now)) {
            return failed(new Failure.InvalidRequest("expiresAt must be in the future"));
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lock(connection, write.key());
                String digest = digest(write);
                Entry replay = select(connection, "session_memory_command", write.key(),
                        write.idempotencyKey(), false);
                if (replay != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT digest FROM session_memory_command WHERE tenant_id=? AND command_key=?")) {
                        statement.setString(1, write.key().tenantId());
                        statement.setString(2, write.idempotencyKey());
                        try (ResultSet rows = statement.executeQuery()) {
                            rows.next();
                            if (!rows.getString(1).equals(digest)) throw new StoreException(
                                    new Failure.IdempotencyConflict(write.idempotencyKey()));
                        }
                    }
                    connection.commit();
                    return CompletableFuture.completedFuture(replay);
                }
                Entry current = select(connection, "session_memory", write.key(), null, true);
                if (current != null && !current.expiresAt().isAfter(now)) {
                    deleteRow(connection, write.key()); current = null;
                }
                if (!matches(write.expectation(), current)) throw new StoreException(
                        new Failure.Conflict(current == null ? 0 : current.revision()));
                Entry next = new Entry(write.key(), current == null ? 1 : current.revision() + 1,
                        write.value(), write.contentType(), current == null ? now : current.createdAt(),
                        now, write.expiresAt());
                upsert(connection, next); insertLedger(connection, write.idempotencyKey(), digest, next);
                connection.commit();
                return CompletableFuture.completedFuture(next);
            } catch (RuntimeException | SQLException failed) {
                connection.rollback();
                if (failed instanceof StoreException storeFailure) {
                    return CompletableFuture.failedFuture(storeFailure);
                }
                if (failed instanceof SQLException sqlFailure) return unavailable(sqlFailure);
                return CompletableFuture.failedFuture(failed);
            }
        } catch (SQLException failed) { return unavailable(failed); }
    }

    @Override
    public CompletionStage<Boolean> delete(Key key, Expectation expectation) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(expectation, "expectation");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lock(connection, key);
                Entry current = select(connection, "session_memory", key, null, true);
                if (current == null || !current.expiresAt().isAfter(clock.instant())) {
                    if (current != null) deleteRow(connection, key);
                    connection.commit();
                    return expectation instanceof Expectation.Exactly ? failed(new Failure.NotFound())
                            : CompletableFuture.completedFuture(false);
                }
                if (!matches(expectation, current)) {
                    connection.rollback(); return failed(new Failure.Conflict(current.revision()));
                }
                deleteRow(connection, key); connection.commit();
                return CompletableFuture.completedFuture(true);
            } catch (SQLException failed) { connection.rollback(); return unavailable(failed); }
        } catch (SQLException failed) { return unavailable(failed); }
    }

    @Override
    public CompletionStage<Long> purgeExpired(String tenantId, Instant through, int limit) {
        if (tenantId == null || tenantId.isBlank() || through == null || limit < 1) {
            return failed(new Failure.InvalidRequest("tenant, time, and positive limit are required"));
        }
        String sql = "DELETE FROM session_memory WHERE (tenant_id,session_id,scope,process_instance_id,node_id) "
                + "IN (SELECT tenant_id,session_id,scope,process_instance_id,node_id FROM session_memory "
                + "WHERE tenant_id=? AND (expires_at_epoch_second<? OR "
                + "(expires_at_epoch_second=? AND expires_at_nano<=?)) "
                + "ORDER BY expires_at_epoch_second,expires_at_nano LIMIT ? FOR UPDATE SKIP LOCKED)";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenantId); statement.setLong(2, through.getEpochSecond());
                statement.setLong(3, through.getEpochSecond()); statement.setInt(4, through.getNano());
                statement.setInt(5, limit);
                long purged = statement.executeUpdate();
                purgeLedger(connection, tenantId, through);
                connection.commit();
                return CompletableFuture.completedFuture(purged);
            } catch (SQLException failed) {
                connection.rollback();
                return unavailable(failed);
            }
        } catch (SQLException failed) { return unavailable(failed); }
    }

    private static void purgeLedger(Connection connection, String tenantId, Instant through)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM session_memory_command WHERE tenant_id=? AND "
                        + "(expires_at_epoch_second<? OR "
                        + "(expires_at_epoch_second=? AND expires_at_nano<=?))")) {
            statement.setString(1, tenantId); statement.setLong(2, through.getEpochSecond());
            statement.setLong(3, through.getEpochSecond()); statement.setInt(4, through.getNano());
            statement.executeUpdate();
        }
    }

    private static void lock(Connection connection, Key key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            statement.setString(1, key.tenantId() + "|" + key.sessionId() + "|" + key.scope()
                    + "|" + key.processInstanceId() + "|" + key.nodeId()); statement.execute();
        }
    }
    private static Entry select(Connection c, String table, Key key, String commandKey, boolean lock)
            throws SQLException {
        boolean ledger = commandKey != null;
        String where = ledger ? "tenant_id=? AND command_key=?"
                : "tenant_id=? AND session_id=? AND scope=? AND process_instance_id=? AND node_id=?";
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM " + table + " WHERE " + where
                + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, key.tenantId()); if (ledger) s.setString(2, commandKey); else bindKey(s, 2, key);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                Key stored = new Key(r.getString("tenant_id"), r.getString("session_id"),
                        Scope.valueOf(r.getString("scope")), uuid(r.getString("process_instance_id")),
                        empty(r.getString("node_id")));
                return new Entry(stored, r.getLong("revision"), r.getBytes("value"), r.getString("content_type"),
                        instant(r, "created_at"), instant(r, "updated_at"), instant(r, "expires_at"));
            }
        }
    }
    private static void upsert(Connection c, Entry e) throws SQLException {
        String sql = "INSERT INTO session_memory VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT "
                + "(tenant_id,session_id,scope,process_instance_id,node_id) DO UPDATE SET "
                + "revision=EXCLUDED.revision,value=EXCLUDED.value,content_type=EXCLUDED.content_type,"
                + "updated_at_epoch_second=EXCLUDED.updated_at_epoch_second,updated_at_nano=EXCLUDED.updated_at_nano,"
                + "expires_at_epoch_second=EXCLUDED.expires_at_epoch_second,expires_at_nano=EXCLUDED.expires_at_nano";
        try (PreparedStatement s = c.prepareStatement(sql)) { bindEntry(s, 1, e); s.executeUpdate(); }
    }
    private static void insertLedger(Connection c, String command, String digest, Entry e) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO session_memory_command VALUES "
                + "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, e.key().tenantId()); s.setString(2, command); s.setString(3, digest);
            bindEntryWithoutTenant(s, 4, e); s.executeUpdate();
        }
    }
    private static void deleteRow(Connection c, Key key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM session_memory WHERE tenant_id=? AND "
                + "session_id=? AND scope=? AND process_instance_id=? AND node_id=?")) {
            s.setString(1, key.tenantId()); bindKey(s, 2, key); s.executeUpdate();
        }
    }
    private static int bindEntry(PreparedStatement s, int i, Entry e) throws SQLException {
        s.setString(i++, e.key().tenantId()); return bindEntryWithoutTenant(s, i, e);
    }
    private static int bindEntryWithoutTenant(PreparedStatement s, int i, Entry e) throws SQLException {
        s.setString(i++, e.key().sessionId()); s.setString(i++, e.key().scope().name());
        s.setString(i++, e.key().processInstanceId() == null ? "" : e.key().processInstanceId().toString());
        s.setString(i++, e.key().nodeId() == null ? "" : e.key().nodeId()); s.setLong(i++, e.revision());
        s.setBytes(i++, e.value()); s.setString(i++, e.contentType()); i = bindInstant(s, i, e.createdAt());
        i = bindInstant(s, i, e.updatedAt()); return bindInstant(s, i, e.expiresAt());
    }
    private static void bindKey(PreparedStatement s, int i, Key k) throws SQLException {
        s.setString(i++, k.sessionId()); s.setString(i++, k.scope().name());
        s.setString(i++, k.processInstanceId() == null ? "" : k.processInstanceId().toString());
        s.setString(i, k.nodeId() == null ? "" : k.nodeId());
    }
    private static int bindInstant(PreparedStatement s, int i, Instant v) throws SQLException {
        s.setLong(i++, v.getEpochSecond()); s.setInt(i++, v.getNano()); return i;
    }
    private static Instant instant(ResultSet r, String p) throws SQLException {
        return Instant.ofEpochSecond(r.getLong(p + "_epoch_second"), r.getInt(p + "_nano"));
    }
    private static UUID uuid(String v) { return v == null || v.isEmpty() ? null : UUID.fromString(v); }
    private static String empty(String v) { return v == null || v.isEmpty() ? null : v; }
    private static boolean matches(Expectation e, Entry current) {
        return e instanceof Expectation.Any || e instanceof Expectation.Absent && current == null
                || e instanceof Expectation.Exactly exact && current != null && exact.revision() == current.revision();
    }
    private static String digest(Write w) {
        try { MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(w.key().toString().getBytes(StandardCharsets.UTF_8)); d.update((byte) 0);
            d.update(w.value()); d.update((byte) 0);
            d.update(w.contentType().getBytes(StandardCharsets.UTF_8)); d.update((byte) 0);
            d.update(w.expiresAt().toString().getBytes(StandardCharsets.UTF_8)); d.update((byte) 0);
            d.update(w.expectation().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> CompletionStage<T> failed(Failure f) {
        return CompletableFuture.failedFuture(new StoreException(f));
    }
    private static <T> CompletionStage<T> unavailable(SQLException ignored) {
        return CompletableFuture.failedFuture(new StoreException(new Failure.Unavailable()));
    }
}
