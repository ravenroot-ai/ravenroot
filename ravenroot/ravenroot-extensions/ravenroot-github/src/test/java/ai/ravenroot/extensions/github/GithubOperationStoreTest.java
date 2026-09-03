package ai.ravenroot.extensions.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GithubOperationStoreTest {
    @TempDir Path directory;
    private static final String DIGEST = "a".repeat(64);

    @Test void terminalResultReplaysAfterStoreRestart() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 1_000);
        var first = new SqliteGithubOperationStore(policy);
        var lease = first.begin("tenant-a", "profile", "review", "key", DIGEST, 123);
        first.save(lease, "SUCCEEDED", 4, 2, 123, "remote", "b".repeat(64), "{\"status\":\"done\"}", true);
        var restarted = new SqliteGithubOperationStore(policy);
        var replay = restarted.begin("tenant-a", "profile", "review", "key", DIGEST, 123);
        assertTrue(replay.record().terminal());
        assertEquals("{\"status\":\"done\"}", replay.record().resultJson());
        assertTrue(replay.owner().isEmpty());
    }

    @Test void contentChangedReplayAndLostWriterFailClosed() {
        Path path = directory.resolve("operations.db");
        GithubConfiguration.StorePolicy policy = new GithubConfiguration.StorePolicy(path, 10, 24, 10_000);
        var first = new SqliteGithubOperationStore(policy);
        var lease = first.begin("tenant-a", "profile", "project", "key", DIGEST, 123);
        assertThrows(GithubException.class, () -> first.begin("tenant-a", "profile", "project", "key", "b".repeat(64), 123));
        GithubException sameProcess = assertThrows(GithubException.class,
                () -> first.begin("tenant-a", "profile", "project", "key", DIGEST, 123));
        assertEquals(GithubException.Code.CAPACITY, sameProcess.code());
        var competing = new SqliteGithubOperationStore(policy);
        GithubException occupied = assertThrows(GithubException.class,
                () -> competing.begin("tenant-a", "profile", "project", "key", DIGEST, 123));
        assertEquals(GithubException.Code.CAPACITY, occupied.code());
        first.release(lease);
        assertFalse(competing.begin("tenant-a", "profile", "project", "key", DIGEST, 123).owner().isEmpty());
    }

    @Test void operationQuotaIsHard() {
        var store = new SqliteGithubOperationStore(new GithubConfiguration.StorePolicy(
                directory.resolve("operations.db"), 1, 24, 1_000));
        store.begin("tenant-a", "profile", "review", "one", DIGEST, 123);
        GithubException full = assertThrows(GithubException.class,
                () -> store.begin("tenant-a", "profile", "review", "two", DIGEST, 123));
        assertEquals(GithubException.Code.CAPACITY, full.code());
    }
}
