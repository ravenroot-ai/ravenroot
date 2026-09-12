package ai.ravenroot.extensions.matrix;

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

class MatrixSyncStoreTest {
    @TempDir Path directory;

    @Test void cursorAndBodyBindingsAreScopedToDeploymentAndNodeAcrossRestart() {
        MatrixConfiguration configuration = MatrixTestSupport.configuration(directory.resolve("sync.db"));
        MatrixSyncStore.SourceKey first = new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment-a", "node-a");
        MatrixSyncStore.SourceKey second = new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment-b", "node-b");
        var store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        store.advance(first, null, "cursor-a", deadline(), new NeverCancelled());
        assertNull(store.cursor(second));
        store.advance(second, null, "cursor-b", deadline(), new NeverCancelled());
        assertEquals("cursor-a", new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock()).cursor(first));
        assertEquals("cursor-b", store.cursor(second));
        assertEquals(MatrixSyncStore.Decision.FIRST_SEEN,
                store.bindEvent(first, "$event:example.org", "a".repeat(64), deadline(), new NeverCancelled()));
        assertEquals(MatrixSyncStore.Decision.REPLAY,
                store.bindEvent(first, "$event:example.org", "a".repeat(64), deadline(), new NeverCancelled()));
        assertEquals(MatrixSyncStore.Decision.FIRST_SEEN,
                store.bindEvent(second, "$event:example.org", "b".repeat(64), deadline(), new NeverCancelled()));
    }

    @Test void cursorCompareAndEventCollisionFailClosed() {
        MatrixConfiguration configuration = MatrixTestSupport.configuration(directory.resolve("conflict.db"));
        var store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        MatrixSyncStore.SourceKey key = new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "node");
        store.advance(key, null, "one", deadline(), new NeverCancelled());
        assertEquals(MatrixException.Code.FORBIDDEN, assertThrows(MatrixException.class,
                () -> store.advance(key, null, "two", deadline(), new NeverCancelled())).code());
        store.bindEvent(key, "$event:example.org", "a".repeat(64), deadline(), new NeverCancelled());
        assertEquals(MatrixException.Code.FORBIDDEN, assertThrows(MatrixException.class,
                () -> store.bindEvent(key, "$event:example.org", "b".repeat(64),
                        deadline(), new NeverCancelled())).code());
    }

    @Test void concurrentEventBindingHasOneWinnerAndStableReplays() {
        MatrixConfiguration configuration = MatrixTestSupport.configuration(directory.resolve("concurrent.db"));
        var store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        MatrixSyncStore.SourceKey key = new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "node");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<MatrixSyncStore.Decision>> calls = new ArrayList<>();
            for (int index = 0; index < 4; index++) calls.add(CompletableFuture.supplyAsync(() -> store.bindEvent(
                    key, "$concurrent:example.org", "c".repeat(64),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(15), new NeverCancelled()), executor));
            List<MatrixSyncStore.Decision> decisions = calls.stream().map(CompletableFuture::join).toList();
            assertEquals(1, decisions.stream().filter(value -> value == MatrixSyncStore.Decision.FIRST_SEEN).count());
            assertEquals(3, decisions.stream().filter(value -> value == MatrixSyncStore.Decision.REPLAY).count());
        }
    }

    @Test void deadlineAndCancellationInterruptContentionWithoutLateCommit() throws Exception {
        Path path = directory.resolve("contention.db");
        MatrixConfiguration configuration = MatrixTestSupport.configuration(path);
        var store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        MatrixSyncStore.SourceKey key = new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "node");
        try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = blocker.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            MatrixException expired = assertThrows(MatrixException.class, () -> store.bindEvent(key,
                    "$deadline:example.org", "d".repeat(64),
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80), new NeverCancelled()));
            assertEquals(MatrixException.Code.DURABILITY_UNAVAILABLE, expired.code());

            TestCancellation cancellation = new TestCancellation();
            CompletableFuture<MatrixException> waiting = CompletableFuture.supplyAsync(() -> assertThrows(
                    MatrixException.class, () -> store.advance(key, null, "cancelled-cursor",
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(3), cancellation)));
            Thread.sleep(30); cancellation.cancel();
            assertEquals(MatrixException.Code.CANCELLED, waiting.get(1, TimeUnit.SECONDS).code());
            statement.execute("ROLLBACK");
        }
        assertEquals(MatrixSyncStore.Decision.FIRST_SEEN, store.bindEvent(key, "$deadline:example.org",
                "d".repeat(64), deadline(), new NeverCancelled()));
        assertNull(store.cursor(key), "cancelled cursor write must not commit later");
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(2); }
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
