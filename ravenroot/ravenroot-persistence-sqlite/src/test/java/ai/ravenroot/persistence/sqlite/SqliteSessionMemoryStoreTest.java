package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.memory.SessionMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSessionMemoryStoreTest {
    @TempDir Path directory;

    @Test
    void valueAndIdempotentOutcomeSurviveRestartWhileTenantsRemainIsolated() {
        Path database = directory.resolve("memory.db");
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var key = SessionMemoryStore.Key.session("tenant-a", "chat");
        var foreign = SessionMemoryStore.Key.session("tenant-b", "chat");
        SessionMemoryStore.Write write = new SessionMemoryStore.Write(key,
                "remember".getBytes(StandardCharsets.UTF_8), "text/plain",
                now.plus(Duration.ofDays(1)), SessionMemoryStore.Expectation.absent(), "turn-1");
        SessionMemoryStore.Entry written;
        try (var store = new SqliteSessionMemoryStore(database, clock, 32)) {
            written = store.put(write).toCompletableFuture().join();
        }
        try (var reopened = new SqliteSessionMemoryStore(database, clock, 32)) {
            assertEquals(written, reopened.get(key).toCompletableFuture().join().orElseThrow());
            assertEquals(written, reopened.put(new SessionMemoryStore.Write(key,
                    "remember".getBytes(StandardCharsets.UTF_8), "text/plain",
                    now.plus(Duration.ofDays(1)), SessionMemoryStore.Expectation.absent(), "turn-1"))
                    .toCompletableFuture().join());
            assertTrue(reopened.get(foreign).toCompletableFuture().join().isEmpty());
        }
    }
}
