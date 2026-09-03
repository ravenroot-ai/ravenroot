package ai.ravenroot.extensions.github;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Bundle-owned durable reconciliation journal with one expiring writer lease per operation. */
final class SqliteGithubOperationStore implements GithubOperationStore {
    private static final int SCHEMA_VERSION = 1;
    private final GithubConfiguration.StorePolicy policy;
    private final Clock clock;
    private final String url;

    SqliteGithubOperationStore(GithubConfiguration.StorePolicy policy) {
        this(policy, Clock.systemUTC());
    }

    SqliteGithubOperationStore(GithubConfiguration.StorePolicy policy, Clock clock) {
        this.policy = java.util.Objects.requireNonNull(policy);
        this.clock = java.util.Objects.requireNonNull(clock);
        try {
            if (policy.path().getParent() != null) Files.createDirectories(policy.path().getParent());
        } catch (IOException failure) { throw unavailable(); }
        this.url = "jdbc:sqlite:" + policy.path();
        migrate();
    }

    @Override public synchronized Lease begin(String tenant, String profile, String kind, String key,
                                               String requestDigest, long deadlineEpochMs) {
        safe(tenant, 160); safe(profile, 64); safe(kind, 64); safe(key, 256); digest(requestDigest);
        long now = clock.millis();
        String leaseOwner = UUID.randomUUID().toString();
        try (Connection connection = open()) {
            beginImmediate(connection);
            prune(connection, now);
            Record existing = read(connection, tenant, profile, kind, key, now).orElse(null);
            if (existing != null && !existing.requestDigest().equals(requestDigest)) {
                rollback(connection); throw new GithubException(GithubException.Code.CAS_LOST);
            }
            if (existing != null && existing.terminal()) {
                commit(connection); return new Lease(this, tenant, profile, kind, key, "", existing);
            }
            if (existing != null && !existing.owned()) {
                rollback(connection); throw new GithubException(GithubException.Code.CAPACITY);
            }
            if (existing == null) {
                if (count(connection) >= policy.maxOperations()) {
                    rollback(connection); throw new GithubException(GithubException.Code.CAPACITY);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO github_operations
                        (tenant_id,profile_name,kind,operation_key,request_digest,state,generation,attempts,
                         deadline_ms,remote_id,detail_digest,result_json,lease_owner,lease_until_ms,updated_ms)
                        VALUES(?,?,?,?,?,'RUNNING',0,0,?,'','','',?,?,?)
                        """)) {
                    bindKey(insert, tenant, profile, kind, key); insert.setString(5, requestDigest);
                    insert.setLong(6, deadlineEpochMs); insert.setString(7, leaseOwner);
                    insert.setLong(8, now + policy.leaseMs()); insert.setLong(9, now); insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE github_operations SET lease_owner=?, lease_until_ms=?, updated_ms=?
                        WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=?
                        """)) {
                    update.setString(1, leaseOwner); update.setLong(2, now + policy.leaseMs()); update.setLong(3, now);
                    bindKey(update, 4, tenant, profile, kind, key); update.executeUpdate();
                }
            }
            Record acquired = read(connection, tenant, profile, kind, key, now).orElseThrow();
            commit(connection);
            return new Lease(this, tenant, profile, kind, key, leaseOwner, acquired);
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized Optional<Record> find(String tenant, String profile, String kind, String key) {
        try (Connection connection = open()) { return read(connection, tenant, profile, kind, key, clock.millis()); }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void save(Lease lease, String state, long generation, long attempts,
                                             long deadlineEpochMs, String remoteId, String detailDigest,
                                             String resultJson,
                                             boolean terminal) {
        if (lease.owner().isEmpty()) return;
        safe(state, 32); safeOptional(remoteId, 256); safeOptional(detailDigest, 64);
        if (resultJson != null && resultJson.length() > 16_384) throw GithubValues.invalid();
        long now = clock.millis();
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement("""
                UPDATE github_operations SET state=?,generation=?,attempts=?,deadline_ms=?,remote_id=?,
                  detail_digest=?,result_json=?,lease_owner=?,lease_until_ms=?,updated_ms=?
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=? AND lease_owner=?
                """)) {
            update.setString(1, state); update.setLong(2, generation); update.setLong(3, attempts);
            update.setLong(4, deadlineEpochMs); update.setString(5, optional(remoteId));
            update.setString(6, optional(detailDigest)); update.setString(7, optional(resultJson));
            update.setString(8, terminal ? "" : lease.owner());
            update.setLong(9, terminal ? 0 : now + policy.leaseMs()); update.setLong(10, now);
            bindKey(update, 11, lease.tenant(), lease.profile(), lease.kind(), lease.key());
            update.setString(15, lease.owner());
            if (update.executeUpdate() != 1) throw new GithubException(GithubException.Code.CAS_LOST);
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void audit(Lease lease, String disposition, String reason, String evidenceDigest) {
        safe(disposition, 32); safe(reason, 64); digest(evidenceDigest);
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO github_operation_audit
                  (tenant_id,profile_name,kind,operation_key,recorded_ms,disposition,reason,evidence_digest)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            bindKey(insert, lease.tenant(), lease.profile(), lease.kind(), lease.key());
            insert.setLong(5, clock.millis()); insert.setString(6, disposition); insert.setString(7, reason);
            insert.setString(8, evidenceDigest); insert.executeUpdate();
        } catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void renew(Lease lease) {
        if (lease.owner().isEmpty()) return;
        long now = clock.millis();
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement("""
                UPDATE github_operations SET lease_until_ms=?,updated_ms=?
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=? AND lease_owner=?
                """)) {
            update.setLong(1, now + policy.leaseMs()); update.setLong(2, now);
            bindKey(update, 3, lease.tenant(), lease.profile(), lease.kind(), lease.key()); update.setString(7, lease.owner());
            if (update.executeUpdate() != 1) throw new GithubException(GithubException.Code.CAS_LOST);
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void release(Lease lease) {
        if (lease.owner().isEmpty()) return;
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement("""
                UPDATE github_operations SET lease_owner='',lease_until_ms=0
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=? AND lease_owner=?
                """)) {
            bindKey(update, lease.tenant(), lease.profile(), lease.kind(), lease.key());
            update.setString(5, lease.owner()); update.executeUpdate();
        } catch (SQLException failure) { throw unavailable(); }
    }

    private void migrate() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) { version = result.next() ? result.getInt(1) : 0; }
            if (version > SCHEMA_VERSION) throw unavailable();
            if (version == 0) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS github_operations (
                          tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL,
                          operation_key TEXT NOT NULL, request_digest TEXT NOT NULL, state TEXT NOT NULL,
                          generation INTEGER NOT NULL, attempts INTEGER NOT NULL, deadline_ms INTEGER NOT NULL,
                          remote_id TEXT NOT NULL, detail_digest TEXT NOT NULL, result_json TEXT NOT NULL,
                          lease_owner TEXT NOT NULL,
                          lease_until_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL,
                          PRIMARY KEY (tenant_id,profile_name,kind,operation_key))
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS github_operation_audit (
                          sequence INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT NOT NULL,
                          profile_name TEXT NOT NULL, kind TEXT NOT NULL, operation_key TEXT NOT NULL,
                          recorded_ms INTEGER NOT NULL, disposition TEXT NOT NULL, reason TEXT NOT NULL,
                          evidence_digest TEXT NOT NULL)
                        """);
                statement.executeUpdate("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        } catch (SQLException failure) { throw unavailable(); }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000"); statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private static void beginImmediate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); }
    }
    private static void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("COMMIT"); }
    }
    private static void rollback(Connection connection) {
        try (Statement statement = connection.createStatement()) { statement.execute("ROLLBACK"); }
        catch (SQLException ignored) { }
    }

    private void prune(Connection connection, long now) throws SQLException {
        long cutoff = now - java.time.Duration.ofHours(policy.retentionHours()).toMillis();
        try (PreparedStatement audit = connection.prepareStatement("DELETE FROM github_operation_audit WHERE recorded_ms<?");
             PreparedStatement operations = connection.prepareStatement(
                     "DELETE FROM github_operations WHERE updated_ms<? AND state IN ('SUCCEEDED','FAILED','TIMED_OUT','STALE','CONFLICT','AMBIGUOUS','CANCELLED')")) {
            audit.setLong(1, cutoff); audit.executeUpdate(); operations.setLong(1, cutoff); operations.executeUpdate();
        }
    }

    private static long count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM github_operations")) {
            return result.next() ? result.getLong(1) : Long.MAX_VALUE;
        }
    }

    private Optional<Record> read(Connection connection, String tenant, String profile, String kind,
                                  String key, long now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT state,generation,attempts,deadline_ms,remote_id,detail_digest,result_json,request_digest,
                       lease_owner,lease_until_ms FROM github_operations
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=?
                """)) {
            bindKey(select, tenant, profile, kind, key);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String leaseOwner = result.getString(9); long leaseUntil = result.getLong(10);
                boolean owned = leaseOwner.isEmpty() || leaseUntil <= now;
                return Optional.of(new Record(result.getString(1), result.getLong(2), result.getLong(3),
                        result.getLong(4), result.getString(5), result.getString(6), result.getString(7),
                        result.getString(8), owned));
            }
        }
    }

    private static void bindKey(PreparedStatement statement, String tenant, String profile,
                                String kind, String key) throws SQLException { bindKey(statement, 1, tenant, profile, kind, key); }
    private static void bindKey(PreparedStatement statement, int start, String tenant, String profile,
                                String kind, String key) throws SQLException {
        statement.setString(start, tenant); statement.setString(start + 1, profile);
        statement.setString(start + 2, kind); statement.setString(start + 3, key);
    }

    private static void safe(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw GithubValues.invalid();
    }
    private static void safeOptional(String value, int maximum) { if (value != null && !value.isEmpty()) safe(value, maximum); }
    private static void digest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw GithubValues.invalid(); }
    private static String optional(String value) { return value == null ? "" : value; }
    private static GithubException unavailable() { return new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE); }
}
