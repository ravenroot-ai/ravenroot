package ai.ravenroot.extensions.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class GithubOperationStoreTest {
    @TempDir Path directory;
    private static final String DIGEST = "a".repeat(64);
    private static final String PROFILE_DIGEST = "c".repeat(64);

    @Test void terminalResultReplaysAfterStoreRestart() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 1_000);
        var first = new SqliteGithubOperationStore(policy);
        var lease = first.begin("tenant-a", "profile", "review", "key", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        first.save(lease, "SUCCEEDED", 4, 2, 123, "remote", "b".repeat(64), "{\"status\":\"done\"}", true);
        var restarted = new SqliteGithubOperationStore(policy);
        var replay = restarted.begin("tenant-a", "profile", "review", "key", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        assertTrue(replay.record().terminal());
        assertEquals("{\"status\":\"done\"}", replay.record().resultJson());
        assertTrue(replay.owner().isEmpty());
    }

    @Test void contentChangedReplayAndLostWriterFailClosed() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 10_000);
        var first = new SqliteGithubOperationStore(policy);
        var lease = first.begin("tenant-a", "profile", "project", "key", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        assertThrows(GithubException.class, () -> first.begin("tenant-a", "profile", "project", "key", "b".repeat(64), PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        GithubException sameProcess = assertThrows(GithubException.class,
                () -> first.begin("tenant-a", "profile", "project", "key", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CAPACITY, sameProcess.code());
        var competing = new SqliteGithubOperationStore(policy);
        GithubException occupied = assertThrows(GithubException.class,
                () -> competing.begin("tenant-a", "profile", "project", "key", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CAPACITY, occupied.code());
        first.release(lease);
        assertFalse(competing.begin("tenant-a", "profile", "project", "key", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
    }

    @Test void operationQuotaIsHard() {
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(
                directory.resolve("operations.db"), 1, 24, 1_000));
        store.begin("tenant-a", "profile", "review", "one", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        GithubException full = assertThrows(GithubException.class,
                () -> store.begin("tenant-a", "profile", "review", "two", DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CAPACITY, full.code());
    }

    @Test void failedAndCancelledRowsReplayWithoutOwnerWhileAmbiguousRowsReconcileAfterRestart() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 1_000);
        var writer = new SqliteGithubOperationStore(policy);
        var failed = writer.begin("tenant-a", "profile", "review", "failed", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        writer.save(failed, "FAILED", 0, 0, 123, "", DIGEST,
                "{\"failureCode\":\"FORBIDDEN\"}", true);
        var cancelled = writer.begin("tenant-a", "profile", "review", "cancelled", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        writer.save(cancelled, "CANCELLED", 0, 0, 123, "", DIGEST,
                "{\"failureCode\":\"CANCELLED\"}", true);
        var ambiguous = writer.begin("tenant-a", "profile", "review", "ambiguous", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        writer.save(ambiguous, "AMBIGUOUS", 0, 0, 123, "99", DIGEST,
                "{\"status\":\"ambiguous\"}", true);

        var reopened = new SqliteGithubOperationStore(policy);
        assertTrue(reopened.begin("tenant-a", "profile", "review", "failed", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
        var ownerless = reopened.begin("tenant-a", "profile", "review", "failed", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> reopened.save(
                ownerless, "FAILED", 0, 0, 123, "", DIGEST, "{}", true)).code());
        assertTrue(reopened.begin("tenant-a", "profile", "review", "cancelled", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
        var reconciliation = reopened.begin("tenant-a", "profile", "review", "ambiguous", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.forAmbiguousReconciliation());
        assertFalse(reconciliation.owner().isEmpty());
        assertEquals("AMBIGUOUS", reconciliation.record().state());
    }

    @Test void failedProjectReplaysSameRequestButAllowsGenerationMatchedSuccessor() {
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(
                directory.resolve("operations.db"), 10, 24, 1_000));
        var failed = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(0));
        store.save(failed, "FAILED", 0, 0, 123, "", DIGEST,
                "{\"failureCode\":\"FORBIDDEN\"}", true);
        assertTrue(store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(0)).owner().isEmpty());
        assertFalse(store.begin("tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(0)).owner().isEmpty());
    }

    @Test void projectFenceRejectsRacingTargetAndRollsOnlyAtPersistedGeneration() {
        Path path = directory.resolve("operations.db");
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        var first = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(7));
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> store.begin(
                "tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(7))).code());
        store.save(first, "SUCCEEDED", 8, 3, 123, "ITEM_1", DIGEST, "{\"status\":\"done\"}", true);
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> store.begin(
                "tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(7))).code());
        var next = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(8));
        assertFalse(next.owner().isEmpty());
        assertEquals(8, next.record().generation());
    }

    @Test void semanticProfileMismatchRefusesBeforeReplayTakeoverOrProjectRolloverMutation() {
        Path path = directory.resolve("profile-binding.db");
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        var first = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(7));
        store.save(first, "SUCCEEDED", 8, 3, 123, "ITEM_1", DIGEST, "{\"status\":\"done\"}", true);
        GithubOperationStore.Record before = store.find("tenant-a", "profile", "project", "1234:ITEM_1").orElseThrow();
        GithubException mismatch = assertThrows(GithubException.class, () -> store.begin(
                "tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), "d".repeat(64), 456,
                GithubOperationStore.BeginPolicy.project(8)));
        assertEquals(GithubException.Code.CONFIGURATION, mismatch.code());
        assertEquals(before, store.find("tenant-a", "profile", "project", "1234:ITEM_1").orElseThrow(),
                "binding refusal must precede rollover, lease, prune, and replay mutation");
    }

    @Test void migratedLegacyUnboundOperationRefusesWithoutBackfillOrMutation() throws Exception {
        Path path = directory.resolve("legacy-unbound.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE github_operations (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL,
                      operation_key TEXT NOT NULL, request_digest TEXT NOT NULL, state TEXT NOT NULL,
                      generation INTEGER NOT NULL, attempts INTEGER NOT NULL, deadline_ms INTEGER NOT NULL,
                      remote_id TEXT NOT NULL, detail_digest TEXT NOT NULL, result_json TEXT NOT NULL,
                      lease_owner TEXT NOT NULL, lease_until_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id,profile_name,kind,operation_key))
                    """);
            statement.executeUpdate("CREATE TABLE github_operation_audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL, operation_key TEXT NOT NULL, recorded_ms INTEGER NOT NULL, disposition TEXT NOT NULL, reason TEXT NOT NULL, evidence_digest TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE github_deliveries (tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, delivery_id TEXT NOT NULL, binding_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY (tenant_id,profile_name,delivery_id))");
            statement.executeUpdate("INSERT INTO github_operations VALUES ('tenant-a','profile','review','legacy','" + DIGEST
                    + "','SUCCEEDED',0,0,123,'','','{}','',0,1000)");
            statement.executeUpdate("PRAGMA user_version=2");
        }
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        GithubException refused = assertThrows(GithubException.class, () -> store.begin(
                "tenant-a", "profile", "review", "legacy", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CONFIGURATION, refused.code());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var result = connection.createStatement().executeQuery(
                     "SELECT request_digest,profile_contract_digest,state,updated_ms FROM github_operations")) {
            assertTrue(result.next());
            assertEquals(DIGEST, result.getString(1));
            assertEquals("", result.getString(2));
            assertEquals("SUCCEEDED", result.getString(3));
            assertEquals(1000, result.getLong(4));
        }
    }

    @Test void interruptedExactVersionThreeColumnMigrationCompletesIdempotently() throws Exception {
        Path path = directory.resolve("partial-v3.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE github_operations (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL,
                      operation_key TEXT NOT NULL, request_digest TEXT NOT NULL, state TEXT NOT NULL,
                      generation INTEGER NOT NULL, attempts INTEGER NOT NULL, deadline_ms INTEGER NOT NULL,
                      remote_id TEXT NOT NULL, detail_digest TEXT NOT NULL, result_json TEXT NOT NULL,
                      lease_owner TEXT NOT NULL, lease_until_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL,
                      profile_contract_digest TEXT NOT NULL DEFAULT '',
                      PRIMARY KEY (tenant_id,profile_name,kind,operation_key))
                    """);
            statement.executeUpdate("CREATE TABLE github_operation_audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL, operation_key TEXT NOT NULL, recorded_ms INTEGER NOT NULL, disposition TEXT NOT NULL, reason TEXT NOT NULL, evidence_digest TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE github_deliveries (tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, delivery_id TEXT NOT NULL, binding_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY (tenant_id,profile_name,delivery_id))");
            statement.executeUpdate("PRAGMA user_version=2");
        }
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        var lease = store.begin("tenant-a", "profile", "review", "new", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        assertEquals(PROFILE_DIGEST, lease.record().profileContractDigest());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var result = connection.createStatement().executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(3, result.getInt(1));
        }
    }

    @Test void interruptedMigrationWithWrongColumnShapeRefuses() throws Exception {
        Path path = directory.resolve("partial-v3-wrong-shape.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE github_operations (
                      tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL,
                      operation_key TEXT NOT NULL, request_digest TEXT NOT NULL, state TEXT NOT NULL,
                      generation INTEGER NOT NULL, attempts INTEGER NOT NULL, deadline_ms INTEGER NOT NULL,
                      remote_id TEXT NOT NULL, detail_digest TEXT NOT NULL, result_json TEXT NOT NULL,
                      lease_owner TEXT NOT NULL, lease_until_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL,
                      profile_contract_digest TEXT DEFAULT '',
                      PRIMARY KEY (tenant_id,profile_name,kind,operation_key))
                    """);
            statement.executeUpdate("CREATE TABLE github_operation_audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, kind TEXT NOT NULL, operation_key TEXT NOT NULL, recorded_ms INTEGER NOT NULL, disposition TEXT NOT NULL, reason TEXT NOT NULL, evidence_digest TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE github_deliveries (tenant_id TEXT NOT NULL, profile_name TEXT NOT NULL, delivery_id TEXT NOT NULL, binding_digest TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY (tenant_id,profile_name,delivery_id))");
            statement.executeUpdate("PRAGMA user_version=2");
        }
        GithubException failure = assertThrows(GithubException.class, () -> new SqliteGithubOperationStore(
                new GithubConfiguration.StorePolicy(path, 10, 24, 1_000)));
        assertEquals(GithubException.Code.DURABILITY_UNAVAILABLE, failure.code());
    }

    @Test void renewalFencesExpiredTakeoverAndOldWriterCannotSave() {
        MutableClock clock = new MutableClock();
        var policy = new GithubConfiguration.StorePolicy(directory.resolve("operations.db"), 10, 24, 1_000);
        var firstStore = new SqliteGithubOperationStore(policy, clock);
        var first = firstStore.begin("tenant-a", "profile", "review", "key", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        clock.advance(500); firstStore.renew(first); clock.advance(600);
        assertEquals(GithubException.Code.CAPACITY, assertThrows(GithubException.class, () ->
                new SqliteGithubOperationStore(policy, clock).begin("tenant-a", "profile", "review", "key",
                        DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary())).code());
        clock.advance(500);
        var takeover = new SqliteGithubOperationStore(policy, clock).begin("tenant-a", "profile", "review", "key",
                DIGEST, PROFILE_DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        assertNotEquals(first.owner(), takeover.owner());
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> firstStore.save(
                first, "SUCCEEDED", 0, 0, 123, "", DIGEST, "{}", true)).code());
    }

    @Test void deliveryBindingSurvivesReopenRejectsCollisionAndIsProfileScoped() {
        var policy = new GithubConfiguration.StorePolicy(directory.resolve("operations.db"), 2, 24, 1_000);
        var store = new SqliteGithubOperationStore(policy);
        assertEquals(GithubOperationStore.DeliveryDecision.FIRST_SEEN,
                store.bindDelivery("tenant-a", "profile", "delivery", DIGEST));
        var reopened = new SqliteGithubOperationStore(policy);
        assertEquals(GithubOperationStore.DeliveryDecision.REPLAY,
                reopened.bindDelivery("tenant-a", "profile", "delivery", DIGEST));
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class,
                () -> reopened.bindDelivery("tenant-a", "profile", "delivery", "b".repeat(64))).code());
        assertEquals(GithubOperationStore.DeliveryDecision.FIRST_SEEN,
                reopened.bindDelivery("tenant-a", "other-profile", "delivery", "b".repeat(64)));
    }

    @Test void quotasArePerProfileAndExpiredStaleRowsAreReclaimedAfterRetention() {
        MutableClock clock = new MutableClock();
        var policy = new GithubConfiguration.StorePolicy(directory.resolve("operations.db"), 1, 1, 1_000);
        var store = new SqliteGithubOperationStore(policy, clock);
        store.begin("tenant-a", "profile", "review", "old", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        store.begin("tenant-a", "other", "review", "independent", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        clock.advance(java.time.Duration.ofHours(2).toMillis());
        assertFalse(store.begin("tenant-a", "profile", "review", "new", "b".repeat(64), PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
    }

    @Test void terminalSaveRollsBackWhenRequiredAuditInsertFails() throws Exception {
        Path path = directory.resolve("operations.db");
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        var lease = store.begin("tenant-a", "profile", "review", "atomic", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER reject_audit BEFORE INSERT ON github_operation_audit "
                    + "BEGIN SELECT RAISE(ABORT, 'injected'); END");
        }
        assertEquals(GithubException.Code.DURABILITY_UNAVAILABLE, assertThrows(GithubException.class,
                () -> store.saveAndAudit(lease, "SUCCEEDED", 0, 0, 123, "", DIGEST, "{}",
                        "SUCCEEDED", "NONE", DIGEST)).code());
        GithubOperationStore.Record retained = store.find("tenant-a", "profile", "review", "atomic").orElseThrow();
        assertFalse(retained.terminal());
        assertEquals("RUNNING", retained.state());
    }

    @Test void waitingSaveAuditAndReleaseRollBackTogetherWhenAuditFails() throws Exception {
        Path path = directory.resolve("waiting-atomic.db");
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        var lease = store.begin("tenant-a", "profile", "watch", "atomic", DIGEST, PROFILE_DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER reject_waiting_audit BEFORE INSERT ON github_operation_audit "
                    + "BEGIN SELECT RAISE(ABORT, 'injected'); END");
        }
        assertEquals(GithubException.Code.DURABILITY_UNAVAILABLE, assertThrows(GithubException.class,
                () -> store.saveWaitingAndAuditRelease(lease, 0, 1, 500, "remote", DIGEST,
                        "{\"status\":\"waiting\"}", "RATE_LIMITED", DIGEST)).code());
        GithubOperationStore.Record retained = store.find("tenant-a", "profile", "watch", "atomic").orElseThrow();
        assertEquals("RUNNING", retained.state());
        assertFalse(retained.owned(), "lease release must roll back with the WAITING row and audit insert");
        store.release(lease);
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000;
        void advance(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
