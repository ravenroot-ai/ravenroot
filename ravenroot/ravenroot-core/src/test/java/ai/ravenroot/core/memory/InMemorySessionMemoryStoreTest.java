package ai.ravenroot.core.memory;

import ai.ravenroot.api.memory.SessionMemoryStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySessionMemoryStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

    @Test
    void scopesAreDistinctTenantIsolatedReplaceableAndReplaySafe() {
        var clock = new MutableClock(NOW);
        var store = new InMemorySessionMemoryStore(clock, 32);
        UUID process = UUID.randomUUID();
        var session = SessionMemoryStore.Key.session("tenant-a", "chat-1");
        var processKey = SessionMemoryStore.Key.process("tenant-a", "chat-1", process);
        var node = SessionMemoryStore.Key.node("tenant-a", "chat-1", process, "agent");
        var foreign = SessionMemoryStore.Key.session("tenant-b", "chat-1");

        var first = put(store, session, "one", SessionMemoryStore.Expectation.absent(), "write-1");
        assertEquals(1, first.revision());
        assertEquals(first, put(store, session, "one", SessionMemoryStore.Expectation.absent(), "write-1"),
                "replay returns the original write instead of applying memory twice");
        assertInstanceOf(SessionMemoryStore.Failure.IdempotencyConflict.class,
                failure(() -> put(store, session, "different", SessionMemoryStore.Expectation.any(), "write-1")));

        var second = put(store, session, "two", SessionMemoryStore.Expectation.exactly(1), "write-2");
        put(store, processKey, "process", SessionMemoryStore.Expectation.absent(), "write-3");
        put(store, node, "node", SessionMemoryStore.Expectation.absent(), "write-4");
        put(store, foreign, "foreign", SessionMemoryStore.Expectation.absent(), "write-5");
        assertEquals(2, second.revision());
        assertEquals("process", text(store.get(processKey).toCompletableFuture().join().orElseThrow()));
        assertEquals("node", text(store.get(node).toCompletableFuture().join().orElseThrow()));
        assertEquals("foreign", text(store.get(foreign).toCompletableFuture().join().orElseThrow()));

        assertTrue(store.delete(session, SessionMemoryStore.Expectation.exactly(2))
                .toCompletableFuture().join());
        assertTrue(store.get(session).toCompletableFuture().join().isEmpty());
        assertTrue(store.get(processKey).toCompletableFuture().join().isPresent(),
                "session deletion must not broaden into process scope");
    }

    @Test
    void sizeRetentionAndBoundedPurgeAreEnforced() {
        var clock = new MutableClock(NOW);
        var store = new InMemorySessionMemoryStore(clock, 4);
        var first = SessionMemoryStore.Key.session("tenant", "one");
        var second = SessionMemoryStore.Key.session("tenant", "two");
        assertInstanceOf(SessionMemoryStore.Failure.TooLarge.class,
                failure(() -> put(store, first, "12345", SessionMemoryStore.Expectation.absent(), "large")));
        put(store, first, "1234", SessionMemoryStore.Expectation.absent(), "first");
        put(store, second, "12", SessionMemoryStore.Expectation.absent(), "second");
        clock.advance(Duration.ofHours(2));
        assertEquals(1, store.purgeExpired("tenant", clock.instant(), 1).toCompletableFuture().join());
        assertEquals(1, store.purgeExpired("tenant", clock.instant(), 1).toCompletableFuture().join());
    }

    @Test
    void agentFacingServiceRedactsBeforeTheDurableWriteAndCapsRetention() {
        var clock = new MutableClock(NOW);
        var store = new InMemorySessionMemoryStore(clock, 64);
        var service = new SessionMemoryService(store, clock, Duration.ofDays(7),
                (key, value, contentType) -> new String(value, StandardCharsets.UTF_8)
                        .replace("secret", "[redacted]").getBytes(StandardCharsets.UTF_8));
        var key = SessionMemoryStore.Key.session("tenant", "chat");
        service.put(new SessionMemoryStore.Write(key, "a secret".getBytes(StandardCharsets.UTF_8),
                "text/plain", NOW.plus(Duration.ofDays(1)), SessionMemoryStore.Expectation.absent(), "safe"))
                .toCompletableFuture().join();
        assertEquals("a [redacted]", text(store.get(key).toCompletableFuture().join().orElseThrow()));
        assertInstanceOf(SessionMemoryStore.Failure.InvalidRequest.class, failure(() ->
                service.put(new SessionMemoryStore.Write(key, new byte[]{1}, "application/octet-stream",
                        NOW.plus(Duration.ofDays(8)), SessionMemoryStore.Expectation.any(), "too-long"))
                        .toCompletableFuture().join()));
    }

    private static SessionMemoryStore.Entry put(InMemorySessionMemoryStore store,
                                                 SessionMemoryStore.Key key, String value,
                                                 SessionMemoryStore.Expectation expectation, String id) {
        return store.put(new SessionMemoryStore.Write(key, value.getBytes(StandardCharsets.UTF_8),
                "application/json", NOW.plus(Duration.ofHours(1)), expectation, id))
                .toCompletableFuture().join();
    }

    private static SessionMemoryStore.Failure failure(Runnable call) {
        var thrown = assertThrows(CompletionException.class, call::run);
        return assertInstanceOf(SessionMemoryStore.StoreException.class, thrown.getCause()).failure();
    }

    private static String text(SessionMemoryStore.Entry entry) {
        return new String(entry.value(), StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
