package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.memory.SessionMemoryStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSessionMemoryStoreTest {
    @Test
    void sharedStoreSerializesConcurrentReplacementAndSurvivesAClientRestart() {
        var dataSource = PostgresTestDatabase.dataSourceFor("session-memory-" + UUID.randomUUID());
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var key = SessionMemoryStore.Key.session("tenant", "chat");
        var create = new SessionMemoryStore.Write(key, "one".getBytes(StandardCharsets.UTF_8),
                "text/plain", now.plus(Duration.ofDays(1)), SessionMemoryStore.Expectation.absent(), "turn-1");
        var firstStore = new PostgresSessionMemoryStore(dataSource, clock, 32, PostgresStoreConfig.defaults());
        var created = firstStore.put(create).toCompletableFuture().join();
        var reopened = new PostgresSessionMemoryStore(dataSource, clock, 32, PostgresStoreConfig.defaults());
        assertEquals(created, reopened.get(key).toCompletableFuture().join().orElseThrow());
        assertEquals(created, reopened.put(new SessionMemoryStore.Write(key,
                "one".getBytes(StandardCharsets.UTF_8), "text/plain", now.plus(Duration.ofDays(1)),
                SessionMemoryStore.Expectation.absent(), "turn-1")).toCompletableFuture().join());

        var left = new SessionMemoryStore.Write(key, "left".getBytes(StandardCharsets.UTF_8), "text/plain",
                now.plus(Duration.ofDays(1)), SessionMemoryStore.Expectation.exactly(1), "turn-2a");
        var right = new SessionMemoryStore.Write(key, "right".getBytes(StandardCharsets.UTF_8), "text/plain",
                now.plus(Duration.ofDays(1)), SessionMemoryStore.Expectation.exactly(1), "turn-2b");
        var outcomes = java.util.List.of(
                CompletableFuture.supplyAsync(() -> outcome(firstStore, left)),
                CompletableFuture.supplyAsync(() -> outcome(reopened, right))).stream()
                .map(CompletableFuture::join).toList();
        assertEquals(1, outcomes.stream().filter(value -> value.equals("written")).count());
        assertEquals(1, outcomes.stream().filter(value -> value.equals("conflict")).count());
        assertEquals(2, reopened.get(key).toCompletableFuture().join().orElseThrow().revision());
        assertTrue(reopened.get(SessionMemoryStore.Key.session("other", "chat"))
                .toCompletableFuture().join().isEmpty());
    }

    private static String outcome(PostgresSessionMemoryStore store, SessionMemoryStore.Write write) {
        try {
            store.put(write).toCompletableFuture().join();
            return "written";
        } catch (java.util.concurrent.CompletionException failed) {
            var storeFailure = (SessionMemoryStore.StoreException) failed.getCause();
            return storeFailure.failure() instanceof SessionMemoryStore.Failure.Conflict
                    ? "conflict" : "unexpected";
        }
    }
}
