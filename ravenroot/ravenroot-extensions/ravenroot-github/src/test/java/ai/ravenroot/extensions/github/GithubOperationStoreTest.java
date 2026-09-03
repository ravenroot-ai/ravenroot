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

    @Test void terminalResultReplaysAfterStoreRestart() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 1_000);
        var first = new SqliteGithubOperationStore(policy);
        var lease = first.begin("tenant-a", "profile", "review", "key", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        first.save(lease, "SUCCEEDED", 4, 2, 123, "remote", "b".repeat(64), "{\"status\":\"done\"}", true);
        var restarted = new SqliteGithubOperationStore(policy);
        var replay = restarted.begin("tenant-a", "profile", "review", "key", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        assertTrue(replay.record().terminal());
        assertEquals("{\"status\":\"done\"}", replay.record().resultJson());
        assertTrue(replay.owner().isEmpty());
    }

    @Test void contentChangedReplayAndLostWriterFailClosed() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 10_000);
        var first = new SqliteGithubOperationStore(policy);
        var lease = first.begin("tenant-a", "profile", "project", "key", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        assertThrows(GithubException.class, () -> first.begin("tenant-a", "profile", "project", "key", "b".repeat(64), 123, GithubOperationStore.BeginPolicy.ordinary()));
        GithubException sameProcess = assertThrows(GithubException.class,
                () -> first.begin("tenant-a", "profile", "project", "key", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CAPACITY, sameProcess.code());
        var competing = new SqliteGithubOperationStore(policy);
        GithubException occupied = assertThrows(GithubException.class,
                () -> competing.begin("tenant-a", "profile", "project", "key", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CAPACITY, occupied.code());
        first.release(lease);
        assertFalse(competing.begin("tenant-a", "profile", "project", "key", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
    }

    @Test void operationQuotaIsHard() {
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(
                directory.resolve("operations.db"), 1, 24, 1_000));
        store.begin("tenant-a", "profile", "review", "one", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
        GithubException full = assertThrows(GithubException.class,
                () -> store.begin("tenant-a", "profile", "review", "two", DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary()));
        assertEquals(GithubException.Code.CAPACITY, full.code());
    }

    @Test void failedAndCancelledRowsReplayWithoutOwnerWhileAmbiguousRowsReconcileAfterRestart() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 1_000);
        var writer = new SqliteGithubOperationStore(policy);
        var failed = writer.begin("tenant-a", "profile", "review", "failed", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        writer.save(failed, "FAILED", 0, 0, 123, "", DIGEST,
                "{\"failureCode\":\"FORBIDDEN\"}", true);
        var cancelled = writer.begin("tenant-a", "profile", "review", "cancelled", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        writer.save(cancelled, "CANCELLED", 0, 0, 123, "", DIGEST,
                "{\"failureCode\":\"CANCELLED\"}", true);
        var ambiguous = writer.begin("tenant-a", "profile", "review", "ambiguous", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        writer.save(ambiguous, "AMBIGUOUS", 0, 0, 123, "99", DIGEST,
                "{\"status\":\"ambiguous\"}", true);

        var reopened = new SqliteGithubOperationStore(policy);
        assertTrue(reopened.begin("tenant-a", "profile", "review", "failed", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
        var ownerless = reopened.begin("tenant-a", "profile", "review", "failed", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> reopened.save(
                ownerless, "FAILED", 0, 0, 123, "", DIGEST, "{}", true)).code());
        assertTrue(reopened.begin("tenant-a", "profile", "review", "cancelled", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
        var reconciliation = reopened.begin("tenant-a", "profile", "review", "ambiguous", DIGEST, 123,
                GithubOperationStore.BeginPolicy.forAmbiguousReconciliation());
        assertFalse(reconciliation.owner().isEmpty());
        assertEquals("AMBIGUOUS", reconciliation.record().state());
    }

    @Test void failedProjectReplaysSameRequestButAllowsGenerationMatchedSuccessor() {
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(
                directory.resolve("operations.db"), 10, 24, 1_000));
        var failed = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(0));
        store.save(failed, "FAILED", 0, 0, 123, "", DIGEST,
                "{\"failureCode\":\"FORBIDDEN\"}", true);
        assertTrue(store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(0)).owner().isEmpty());
        assertFalse(store.begin("tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), 123,
                GithubOperationStore.BeginPolicy.project(0)).owner().isEmpty());
    }

    @Test void projectFenceRejectsRacingTargetAndRollsOnlyAtPersistedGeneration() {
        Path path = directory.resolve("operations.db");
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(path, 10, 24, 1_000));
        var first = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", DIGEST, 123,
                GithubOperationStore.BeginPolicy.project(7));
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> store.begin(
                "tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), 123,
                GithubOperationStore.BeginPolicy.project(7))).code());
        store.save(first, "SUCCEEDED", 8, 3, 123, "ITEM_1", DIGEST, "{\"status\":\"done\"}", true);
        assertEquals(GithubException.Code.CAS_LOST, assertThrows(GithubException.class, () -> store.begin(
                "tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), 123,
                GithubOperationStore.BeginPolicy.project(7))).code());
        var next = store.begin("tenant-a", "profile", "project", "1234:ITEM_1", "b".repeat(64), 123,
                GithubOperationStore.BeginPolicy.project(8));
        assertFalse(next.owner().isEmpty());
        assertEquals(8, next.record().generation());
    }

    @Test void renewalFencesExpiredTakeoverAndOldWriterCannotSave() {
        MutableClock clock = new MutableClock();
        var policy = new GithubConfiguration.StorePolicy(directory.resolve("operations.db"), 10, 24, 100);
        var firstStore = new SqliteGithubOperationStore(policy, clock);
        var first = firstStore.begin("tenant-a", "profile", "review", "key", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        clock.advance(50); firstStore.renew(first); clock.advance(60);
        assertEquals(GithubException.Code.CAPACITY, assertThrows(GithubException.class, () ->
                new SqliteGithubOperationStore(policy, clock).begin("tenant-a", "profile", "review", "key",
                        DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary())).code());
        clock.advance(50);
        var takeover = new SqliteGithubOperationStore(policy, clock).begin("tenant-a", "profile", "review", "key",
                DIGEST, 123, GithubOperationStore.BeginPolicy.ordinary());
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
        var policy = new GithubConfiguration.StorePolicy(directory.resolve("operations.db"), 1, 1, 100);
        var store = new SqliteGithubOperationStore(policy, clock);
        store.begin("tenant-a", "profile", "review", "old", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        store.begin("tenant-a", "other", "review", "independent", DIGEST, 123,
                GithubOperationStore.BeginPolicy.ordinary());
        clock.advance(java.time.Duration.ofHours(2).toMillis());
        assertFalse(store.begin("tenant-a", "profile", "review", "new", "b".repeat(64), 123,
                GithubOperationStore.BeginPolicy.ordinary()).owner().isEmpty());
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
