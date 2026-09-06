package ai.ravenroot.extensions.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TeamsDeliveryStoreTest {
    @TempDir Path directory;
    @Test void bindingSurvivesRestartAndRejectsCollisionWithoutContent() {
        TeamsConfiguration configuration = TeamsTestSupport.configuration(directory.resolve("deliveries.db"));
        String first = "a".repeat(64); String second = "b".repeat(64);
        assertEquals(TeamsDeliveryStore.Decision.FIRST_SEEN,
                new SqliteTeamsDeliveryStore(configuration.store(), TeamsTestSupport.fixedClock())
                        .bind(TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE, "event", "Ev01234567", first,
                                deadline(), new NeverCancelled()));
        var restarted = new SqliteTeamsDeliveryStore(configuration.store(), TeamsTestSupport.fixedClock());
        assertEquals(TeamsDeliveryStore.Decision.REPLAY,
                restarted.bind(TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE, "event", "Ev01234567", first,
                        deadline(), new NeverCancelled()));
        TeamsException collision = assertThrows(TeamsException.class, () -> restarted.bind(
                TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE, "event", "Ev01234567", second,
                deadline(), new NeverCancelled()));
        assertEquals(TeamsException.Code.FORBIDDEN, collision.code());
        assertFalse(collision.getMessage().contains(first));
    }

    @Test void concurrentIdenticalDeliveryHasOneWinnerAndStableReplays() {
        var store = new SqliteTeamsDeliveryStore(
                TeamsTestSupport.configuration(directory.resolve("concurrent.db")).store(),
                TeamsTestSupport.fixedClock());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<TeamsDeliveryStore.Decision>> calls = new ArrayList<>();
            for (int index = 0; index < 4; index++) calls.add(CompletableFuture.supplyAsync(() -> store.bind(
                    TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE, "event", "activity-concurrent",
                    "c".repeat(64), System.nanoTime() + TimeUnit.SECONDS.toNanos(15), new NeverCancelled()), executor));
            List<TeamsDeliveryStore.Decision> results = calls.stream().map(CompletableFuture::join).toList();
            assertEquals(1, results.stream().filter(value -> value == TeamsDeliveryStore.Decision.FIRST_SEEN).count());
            assertEquals(3, results.stream().filter(value -> value == TeamsDeliveryStore.Decision.REPLAY).count());
        }
    }

    @Test void deadlineAndCancellationInterruptContentionWithoutLateCommit() throws Exception {
        Path path = directory.resolve("contention.db");
        var store = new SqliteTeamsDeliveryStore(TeamsTestSupport.configuration(path).store(),
                TeamsTestSupport.fixedClock());
        try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = blocker.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            TeamsException deadline = assertThrows(TeamsException.class, () -> store.bind(
                    TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE, "event", "deadline",
                    "d".repeat(64), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80), new NeverCancelled()));
            assertEquals(TeamsException.Code.DURABILITY_UNAVAILABLE, deadline.code());

            TestCancellation cancellation = new TestCancellation();
            CompletableFuture<TeamsException> waiting = CompletableFuture.supplyAsync(() -> assertThrows(
                    TeamsException.class, () -> store.bind(TeamsTestSupport.TENANT, TeamsTestSupport.PROFILE,
                            "event", "cancelled", "e".repeat(64),
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(3), cancellation)));
            Thread.sleep(30); cancellation.cancel();
            assertEquals(TeamsException.Code.CANCELLED, waiting.get(1, TimeUnit.SECONDS).code());
            statement.execute("ROLLBACK");
        }
        assertEquals(TeamsDeliveryStore.Decision.FIRST_SEEN, store.bind(TeamsTestSupport.TENANT,
                TeamsTestSupport.PROFILE, "event", "deadline", "d".repeat(64), deadline(), new NeverCancelled()));
        assertEquals(TeamsDeliveryStore.Decision.FIRST_SEEN, store.bind(TeamsTestSupport.TENANT,
                TeamsTestSupport.PROFILE, "event", "cancelled", "e".repeat(64), deadline(), new NeverCancelled()));
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(1); }
    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
    private static final class TestCancellation implements ai.ravenroot.api.execution.CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public boolean cancelled() { return cancelled.get(); }
        @Override public void onCancel(Runnable listener) {
            if (cancelled.get()) listener.run(); else listeners.add(listener);
        }
        void cancel() { if (cancelled.compareAndSet(false, true)) listeners.forEach(Runnable::run); }
    }
}
