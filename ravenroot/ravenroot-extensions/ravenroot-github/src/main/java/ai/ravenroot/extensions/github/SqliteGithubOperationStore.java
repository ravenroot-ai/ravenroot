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
    private static final int SCHEMA_VERSION = 2;
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
                                               String requestDigest, long deadlineEpochMs, BeginPolicy beginPolicy) {
        safe(tenant, 160); safe(profile, 64); safe(kind, 64); safe(key, 256); digest(requestDigest);
        java.util.Objects.requireNonNull(beginPolicy);
        long now = clock.millis();
        String leaseOwner = UUID.randomUUID().toString();
        try (Connection connection = open()) {
            beginImmediate(connection);
            prune(connection, tenant, profile, now);
            Record existing = read(connection, tenant, profile, kind, key, now).orElse(null);
            boolean rollover = existing != null && existing.terminal() && !"AMBIGUOUS".equals(existing.state())
                    && beginPolicy.rollTerminal()
                    && !existing.requestDigest().equals(requestDigest)
                    && existing.generation() == beginPolicy.expectedGeneration();
            if (existing != null && !existing.requestDigest().equals(requestDigest) && !rollover) {
                rollback(connection); throw new GithubException(GithubException.Code.CAS_LOST);
            }
            if (existing != null && existing.terminal() && !rollover) {
                if ("AMBIGUOUS".equals(existing.state()) && beginPolicy.reconcileAmbiguous()) {
                    if (now < Math.max(existing.deadlineEpochMs(),
                            existing.updatedEpochMs() + beginPolicy.takeoverGraceMs())) {
                        commit(connection); return new Lease(this, tenant, profile, kind, key, "", existing, false);
                    }
                    acquire(connection, tenant, profile, kind, key, leaseOwner, now, "RECONCILING");
                    commit(connection); return new Lease(this, tenant, profile, kind, key, leaseOwner, existing, true);
                }
                commit(connection); return new Lease(this, tenant, profile, kind, key, "", existing, false);
            }
            if (existing != null && !existing.owned()) {
                rollback(connection); throw new GithubException(GithubException.Code.CAPACITY);
            }
            if (existing != null && existing.expiredLease()
                    && now < existing.updatedEpochMs() + beginPolicy.takeoverGraceMs()) {
                rollback(connection); throw new GithubException(GithubException.Code.CAPACITY);
            }
            boolean takeover = existing != null && existing.expiredLease();
            if (existing == null) {
                if (count(connection, tenant, profile) >= policy.maxOperations()) {
                    rollback(connection); throw new GithubException(GithubException.Code.CAPACITY);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO github_operations
                        (tenant_id,profile_name,kind,operation_key,request_digest,state,generation,attempts,
                         deadline_ms,remote_id,detail_digest,result_json,lease_owner,lease_until_ms,updated_ms)
                        VALUES(?,?,?,?,?,'RUNNING',?,0,?,'','','',?,?,?)
                        """)) {
                    bindKey(insert, tenant, profile, kind, key); insert.setString(5, requestDigest);
                    insert.setLong(6, Math.max(0, beginPolicy.expectedGeneration()));
                    insert.setLong(7, deadlineEpochMs); insert.setString(8, leaseOwner);
                    insert.setLong(9, now + policy.leaseMs()); insert.setLong(10, now); insert.executeUpdate();
                }
            } else if (rollover) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE github_operations SET request_digest=?,state='RUNNING',attempts=0,deadline_ms=?,
                          remote_id='',detail_digest='',result_json='',lease_owner=?,lease_until_ms=?,updated_ms=?
                        WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=?
                        """)) {
                    update.setString(1, requestDigest); update.setLong(2, deadlineEpochMs); update.setString(3, leaseOwner);
                    update.setLong(4, now + policy.leaseMs()); update.setLong(5, now);
                    bindKey(update, 6, tenant, profile, kind, key); update.executeUpdate();
                }
            } else {
                acquire(connection, tenant, profile, kind, key, leaseOwner, now, existing.state());
            }
            Record acquired = read(connection, tenant, profile, kind, key, now).orElseThrow();
            commit(connection);
            return new Lease(this, tenant, profile, kind, key, leaseOwner, acquired, takeover);
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    private void acquire(Connection connection, String tenant, String profile, String kind, String key,
                         String leaseOwner, long now, String state) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE github_operations SET state=?,lease_owner=?,lease_until_ms=?,updated_ms=?
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=?
                """)) {
            update.setString(1, state); update.setString(2, leaseOwner);
            update.setLong(3, now + policy.leaseMs()); update.setLong(4, now);
            bindKey(update, 5, tenant, profile, kind, key); update.executeUpdate();
        }
    }

    @Override public synchronized Optional<Record> find(String tenant, String profile, String kind, String key) {
        try (Connection connection = open()) { return read(connection, tenant, profile, kind, key, clock.millis()); }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void save(Lease lease, String state, long generation, long attempts,
                                             long deadlineEpochMs, String remoteId, String detailDigest,
                                             String resultJson,
                                             boolean terminal) {
        validateSave(lease, state, remoteId, detailDigest, resultJson, terminal);
        try (Connection connection = open()) {
            update(connection, lease, state, generation, attempts, deadlineEpochMs, remoteId, detailDigest,
                    resultJson, terminal, clock.millis());
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void saveAndAudit(Lease lease, String state, long generation, long attempts,
                                                      long deadlineEpochMs, String remoteId, String detailDigest,
                                                      String resultJson, String disposition, String reason,
                                                      String evidenceDigest) {
        validateSave(lease, state, remoteId, detailDigest, resultJson, true);
        safe(disposition, 32); safe(reason, 64); digest(evidenceDigest);
        try (Connection connection = open()) {
            beginImmediate(connection);
            update(connection, lease, state, generation, attempts, deadlineEpochMs, remoteId, detailDigest,
                    resultJson, true, clock.millis());
            insertAudit(connection, lease, disposition, reason, evidenceDigest);
            commit(connection);
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void saveWaitingAndAuditRelease(Lease lease, long generation, long attempts,
                                                                   long deadlineEpochMs, String remoteId,
                                                                   String detailDigest, String resultJson,
                                                                   String reason, String evidenceDigest) {
        validateSave(lease, "WAITING", remoteId, detailDigest, resultJson, false);
        safe(reason, 64); digest(evidenceDigest);
        try (Connection connection = open()) {
            beginImmediate(connection);
            update(connection, lease, "WAITING", generation, attempts, deadlineEpochMs, remoteId, detailDigest,
                    resultJson, false, clock.millis());
            insertAudit(connection, lease, "WAITING", reason, evidenceDigest);
            release(connection, lease, true);
            commit(connection);
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    private void update(Connection connection, Lease lease, String state, long generation, long attempts,
                        long deadlineEpochMs, String remoteId, String detailDigest, String resultJson,
                        boolean terminal, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
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
        }
    }

    private static void validateSave(Lease lease, String state, String remoteId, String detailDigest,
                                     String resultJson, boolean terminal) {
        if (lease.owner().isEmpty()) throw new GithubException(GithubException.Code.CAS_LOST);
        safe(state, 32); safeOptional(remoteId, 256); safeOptional(detailDigest, 64);
        if (terminal != SetNames.TERMINAL.contains(state) || terminal && (resultJson == null || resultJson.isEmpty()))
            throw GithubValues.invalid();
        if (resultJson != null && resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw GithubValues.invalid();
    }

    @Override public synchronized void audit(Lease lease, String disposition, String reason, String evidenceDigest) {
        safe(disposition, 32); safe(reason, 64); digest(evidenceDigest);
        try (Connection connection = open()) {
            insertAudit(connection, lease, disposition, reason, evidenceDigest);
        } catch (SQLException failure) { throw unavailable(); }
    }

    private void insertAudit(Connection connection, Lease lease, String disposition, String reason,
                             String evidenceDigest) throws SQLException {
        pruneAudit(connection, lease.tenant(), lease.profile());
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO github_operation_audit
                  (tenant_id,profile_name,kind,operation_key,recorded_ms,disposition,reason,evidence_digest)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            bindKey(insert, lease.tenant(), lease.profile(), lease.kind(), lease.key());
            insert.setLong(5, clock.millis()); insert.setString(6, disposition); insert.setString(7, reason);
            insert.setString(8, evidenceDigest); insert.executeUpdate();
        }
    }

    @Override public synchronized DeliveryDecision bindDelivery(String tenant, String profile, String deliveryId,
                                                                String bindingDigest) {
        safe(tenant, 160); safe(profile, 64); safe(deliveryId, 128); digest(bindingDigest);
        long now = clock.millis();
        try (Connection connection = open()) {
            beginImmediate(connection); prune(connection, tenant, profile, now);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT binding_digest FROM github_deliveries
                    WHERE tenant_id=? AND profile_name=? AND delivery_id=?
                    """)) {
                select.setString(1, tenant); select.setString(2, profile); select.setString(3, deliveryId);
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        if (!bindingDigest.equals(result.getString(1))) {
                            rollback(connection); throw new GithubException(GithubException.Code.CAS_LOST);
                        }
                        commit(connection); return DeliveryDecision.REPLAY;
                    }
                }
            }
            if (deliveryCount(connection, tenant, profile) >= policy.maxOperations()) {
                rollback(connection); throw new GithubException(GithubException.Code.CAPACITY);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO github_deliveries(tenant_id,profile_name,delivery_id,binding_digest,updated_ms)
                    VALUES(?,?,?,?,?)
                    """)) {
                insert.setString(1, tenant); insert.setString(2, profile); insert.setString(3, deliveryId);
                insert.setString(4, bindingDigest); insert.setLong(5, now); insert.executeUpdate();
            }
            commit(connection); return DeliveryDecision.FIRST_SEEN;
        } catch (GithubException failure) { throw failure; }
        catch (SQLException failure) { throw unavailable(); }
    }

    @Override public synchronized void renew(Lease lease) {
        if (lease.owner().isEmpty()) throw new GithubException(GithubException.Code.CAS_LOST);
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
        try (Connection connection = open()) {
            release(connection, lease, false);
        } catch (SQLException failure) { throw unavailable(); }
    }

    private static void release(Connection connection, Lease lease, boolean requireOwner) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE github_operations SET lease_owner='',lease_until_ms=0
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=? AND lease_owner=?
                """)) {
            bindKey(update, lease.tenant(), lease.profile(), lease.kind(), lease.key());
            update.setString(5, lease.owner());
            int changed = update.executeUpdate();
            if (requireOwner && changed != 1) throw new GithubException(GithubException.Code.CAS_LOST);
        }
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
            }
            if (version < 2) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS github_deliveries (
                          tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, delivery_id TEXT NOT NULL,
                          binding_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL,
                          PRIMARY KEY (tenant_id,profile_name,delivery_id))
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

    private void prune(Connection connection, String tenant, String profile, long now) throws SQLException {
        long cutoff = now - java.time.Duration.ofHours(policy.retentionHours()).toMillis();
        try (PreparedStatement audit = connection.prepareStatement(
                     "DELETE FROM github_operation_audit WHERE tenant_id=? AND profile_name=? AND recorded_ms<?");
             PreparedStatement operations = connection.prepareStatement(
                     "DELETE FROM github_operations WHERE tenant_id=? AND profile_name=? AND updated_ms<? AND lease_until_ms<=?");
             PreparedStatement deliveries = connection.prepareStatement(
                     "DELETE FROM github_deliveries WHERE tenant_id=? AND profile_name=? AND updated_ms<?")) {
            audit.setString(1, tenant); audit.setString(2, profile); audit.setLong(3, cutoff); audit.executeUpdate();
            operations.setString(1, tenant); operations.setString(2, profile); operations.setLong(3, cutoff);
            operations.setLong(4, now); operations.executeUpdate();
            deliveries.setString(1, tenant); deliveries.setString(2, profile); deliveries.setLong(3, cutoff);
            deliveries.executeUpdate();
        }
    }

    private static long count(Connection connection, String tenant, String profile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM github_operations WHERE tenant_id=? AND profile_name=?")) {
            statement.setString(1, tenant); statement.setString(2, profile);
            try (ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : Long.MAX_VALUE;
            }
        }
    }

    private static long deliveryCount(Connection connection, String tenant, String profile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM github_deliveries WHERE tenant_id=? AND profile_name=?")) {
            statement.setString(1, tenant); statement.setString(2, profile);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : Long.MAX_VALUE;
            }
        }
    }

    private void pruneAudit(Connection connection, String tenant, String profile) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM github_operation_audit WHERE sequence IN (
                  SELECT sequence FROM github_operation_audit WHERE tenant_id=? AND profile_name=?
                  ORDER BY sequence DESC LIMIT -1 OFFSET ?)
                """)) {
            delete.setString(1, tenant); delete.setString(2, profile);
            delete.setInt(3, Math.max(0, policy.maxOperations() - 1)); delete.executeUpdate();
        }
    }

    private Optional<Record> read(Connection connection, String tenant, String profile, String kind,
                                  String key, long now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT state,generation,attempts,deadline_ms,remote_id,detail_digest,result_json,request_digest,
                       lease_owner,lease_until_ms,updated_ms FROM github_operations
                WHERE tenant_id=? AND profile_name=? AND kind=? AND operation_key=?
                """)) {
            bindKey(select, tenant, profile, kind, key);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String leaseOwner = result.getString(9); long leaseUntil = result.getLong(10);
                boolean expired = !leaseOwner.isEmpty() && leaseUntil <= now;
                boolean owned = leaseOwner.isEmpty() || expired;
                return Optional.of(new Record(result.getString(1), result.getLong(2), result.getLong(3),
                        result.getLong(4), result.getString(5), result.getString(6), result.getString(7),
                        result.getString(8), owned, expired, result.getLong(11)));
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
