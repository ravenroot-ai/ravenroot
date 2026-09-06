package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.execution.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MattermostDeliveryStoreTest {
    @TempDir Path directory;
    private static final String DIGEST = "a".repeat(64);

    @Test void survivesRestartAndIsolatesTenantProfileAndSource() {
        Path database = directory.resolve("deliveries.db");
        var policy = new MattermostConfiguration.StorePolicy(database, 8, 24);
        assertEquals(MattermostDeliveryStore.Decision.FIRST_SEEN,
                bind(new SqliteMattermostDeliveryStore(policy), "tenant-a", "ops", "source-a", DIGEST));
        assertEquals(MattermostDeliveryStore.Decision.REPLAY,
                bind(new SqliteMattermostDeliveryStore(policy), "tenant-a", "ops", "source-a", DIGEST));
        assertEquals(MattermostDeliveryStore.Decision.FIRST_SEEN,
                bind(new SqliteMattermostDeliveryStore(policy), "tenant-b", "ops", "source-a", DIGEST));
        assertEquals(MattermostDeliveryStore.Decision.FIRST_SEEN,
                bind(new SqliteMattermostDeliveryStore(policy), "tenant-a", "other", "source-a", DIGEST));
        assertEquals(MattermostDeliveryStore.Decision.FIRST_SEEN,
                bind(new SqliteMattermostDeliveryStore(policy), "tenant-a", "ops", "source-b", DIGEST));
        MattermostException collision = assertThrows(MattermostException.class, () ->
                bind(new SqliteMattermostDeliveryStore(policy), "tenant-a", "ops", "source-a", "b".repeat(64)));
        assertEquals(MattermostException.Code.FORBIDDEN, collision.code());
    }

    @Test void enforcesPerSourceCapacityAndPrunesExpiredRows() {
        Path database = directory.resolve("bounded.db");
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var policy = new MattermostConfiguration.StorePolicy(database, 1, 1);
        var first = new SqliteMattermostDeliveryStore(policy, Clock.fixed(now.minusSeconds(7_200), ZoneOffset.UTC));
        assertEquals(MattermostDeliveryStore.Decision.FIRST_SEEN, first.bind("tenant-a", "ops", "source-a",
                MattermostTestSupport.POST, DIGEST, deadline(), new NeverCancelled()));
        var current = new SqliteMattermostDeliveryStore(policy, Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(MattermostDeliveryStore.Decision.FIRST_SEEN, current.bind("tenant-a", "ops", "source-a",
                "ffffffffffffffffffffffffff", DIGEST, deadline(), new NeverCancelled()));
        MattermostException capacity = assertThrows(MattermostException.class, () -> current.bind(
                "tenant-a", "ops", "source-a", "gggggggggggggggggggggggggg", DIGEST,
                deadline(), new NeverCancelled()));
        assertEquals(MattermostException.Code.CAPACITY, capacity.code());
    }

    @Test void busyDatabaseHonorsDeadlineAndCancellation() throws Exception {
        Path database = directory.resolve("busy.db");
        var store = new SqliteMattermostDeliveryStore(new MattermostConfiguration.StorePolicy(database, 8, 24));
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            lock.createStatement().execute("BEGIN IMMEDIATE");
            long started = System.nanoTime();
            MattermostException deadline = assertThrows(MattermostException.class, () -> store.bind("tenant-a", "ops",
                    "source-a", MattermostTestSupport.POST, DIGEST,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100), new NeverCancelled()));
            assertEquals(MattermostException.Code.DURABILITY_UNAVAILABLE, deadline.code());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));

            TestCancellation cancellation = new TestCancellation();
            var task = java.util.concurrent.CompletableFuture.runAsync(() -> assertThrows(MattermostException.class,
                    () -> store.bind("tenant-a", "ops", "source-a", MattermostTestSupport.POST, DIGEST,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), cancellation)));
            Thread.sleep(50); cancellation.cancel(); task.get(1, TimeUnit.SECONDS);
        }
    }

    private static MattermostDeliveryStore.Decision bind(MattermostDeliveryStore store, String tenant,
                                                          String profile, String source, String digest) {
        return store.bind(tenant, profile, source, MattermostTestSupport.POST, digest,
                deadline(), new NeverCancelled());
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(2); }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
    private static final class TestCancellation implements CancellationSignal {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>(); private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) { if (cancelled) listener.run(); else listeners.add(listener); }
        void cancel() { cancelled = true; listeners.forEach(Runnable::run); }
    }
}
