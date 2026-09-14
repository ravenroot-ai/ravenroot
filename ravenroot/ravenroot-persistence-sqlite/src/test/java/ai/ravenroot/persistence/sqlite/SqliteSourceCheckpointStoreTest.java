package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.SourceCheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable source ownership across real processes and cancellation of queued SQLite operations. */
@Timeout(30)
class SqliteSourceCheckpointStoreTest {
    private static final String TENANT = "tenant-a";
    private static final String CONSUMER = "mail-consumer-a";
    private static final String STREAM = "mail/INBOX/uidvalidity-7";
    private static final UUID EVENT = UUID.fromString("93000000-0000-0000-0000-000000000001");
    private static final Duration RETENTION = Duration.ofHours(1);

    @TempDir
    Path directory;

    @Test
    void anotherProcessIsExcludedUntilTheOwnerReleasesWhileItsStoreRemainsOpen() throws Exception {
        assertProcessOwnership(false);
    }

    @Test
    void killingTheOwnerReleasesOwnershipAndPreservesItsCheckpointAndInbox() throws Exception {
        assertProcessOwnership(true);
    }

    @Test
    void aRejectedLocalDuplicateDoesNotReleaseTheOwnersCrossProcessLock() throws Exception {
        Path database = directory.resolve("duplicate-descriptor.db");
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC());
             var duplicateStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var source = store.openSourceCheckpointStore(TENANT, CONSUMER)) {
            assertThrows(IllegalStateException.class,
                    () -> duplicateStore.openSourceCheckpointStore(TENANT, CONSUMER));
            // Closing a duplicate file descriptor can drop process-wide POSIX locks. The local
            // refusal is insufficient evidence: a different JVM must still observe the OS lock.
            try (var child = Child.start(database, "PROBE")) {
                child.awaitLine("REFUSED");
                assertTrue(child.process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, child.process.exitValue());
            }
            assertEquals(42, await(source.advance(await(source.checkpoint(STREAM)), 42)).deliveredThrough());
        }
    }

    @Test
    void aSymbolicLinkCannotReplaceTheSourceOwnershipSidecar() throws Exception {
        Path database = directory.resolve("symlink-sidecar.db");
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC())) {
            try (var source = store.openSourceCheckpointStore(TENANT, CONSUMER)) {
                assertEquals(0, await(source.checkpoint(STREAM)).deliveredThrough());
            }
            Path sidecar;
            try (var files = Files.list(directory)) {
                var sidecars = files.filter(path -> path.getFileName().toString()
                        .startsWith(database.getFileName() + ".source-")).toList();
                assertEquals(1, sidecars.size());
                sidecar = sidecars.getFirst();
            }
            Path target = directory.resolve("unrelated-lock-target");
            Files.writeString(target, "unchanged", StandardCharsets.UTF_8);
            // Replace only this test's released sidecar, modelling an unsafe pre-existing artifact.
            Files.delete(sidecar);
            Files.createSymbolicLink(sidecar, target.toAbsolutePath());
            assertThrows(IllegalStateException.class,
                    () -> store.openSourceCheckpointStore(TENANT, CONSUMER));
            assertTrue(Files.isSymbolicLink(sidecar));
            assertEquals("unchanged", Files.readString(target, StandardCharsets.UTF_8));
        }
    }

    private void assertProcessOwnership(boolean kill) throws Exception {
        Path database = directory.resolve("process-owner.db");
        // Both adapters remain open together: exclusion must be per source, not per database.
        try (var competingStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var child = Child.start(database)) {
            child.awaitLine("OWNED");
            assertTrue(child.process.isAlive());
            assertThrows(IllegalStateException.class,
                    () -> competingStore.openSourceCheckpointStore(TENANT, CONSUMER));
            try (var otherConsumer = competingStore.openSourceCheckpointStore(TENANT, "mail-consumer-b");
                 var otherTenant = competingStore.openSourceCheckpointStore("tenant-b", CONSUMER)) {
                assertEquals(0, await(otherConsumer.checkpoint(STREAM)).deliveredThrough());
                assertEquals(0, await(otherTenant.checkpoint(STREAM)).deliveredThrough());
            }

            if (kill) {
                child.process.destroyForcibly();
                assertTrue(child.process.waitFor(10, TimeUnit.SECONDS), "owner process survived forced kill");
                assertTrue(child.process.exitValue() != 0, "forced kill must bypass orderly resource closure");
            } else {
                child.send("RELEASE");
                child.awaitLine("RELEASED");
                assertTrue(child.process.isAlive(), "source release must not require its store to close");
            }

            try (var successor = competingStore.openSourceCheckpointStore(TENANT, CONSUMER)) {
                JournalCursor recovered = await(successor.checkpoint(STREAM));
                assertEquals(42, recovered.deliveredThrough(), "acknowledged position survives owner loss");
                assertFalse(await(successor.recordInbox(STREAM, EVENT, RETENTION)),
                        "an admitted event remains a duplicate across process identity changes");
                assertEquals(43, await(successor.advance(recovered, 43)).deliveredThrough());
            }
            if (!kill) {
                child.send("EXIT");
                assertTrue(child.process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, child.process.exitValue());
            }
        }
        try (var reopened = new SqliteExecutionStore(database, Clock.systemUTC());
             var source = reopened.openSourceCheckpointStore(TENANT, CONSUMER)) {
            assertEquals(43, await(source.checkpoint(STREAM)).deliveredThrough());
            assertFalse(await(source.recordInbox(STREAM, EVENT, RETENTION)));
        }
    }

    @Test
    void consumerAndTenantNamespacesIsolateCursorsAndInboxRecords() throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("isolation.db"), Clock.systemUTC());
             var first = store.openSourceCheckpointStore(TENANT, CONSUMER);
             var second = store.openSourceCheckpointStore(TENANT, CONSUMER + "-other");
             var otherTenant = store.openSourceCheckpointStore("tenant-b", CONSUMER)) {
            JournalCursor firstStart = await(first.checkpoint(STREAM));
            JournalCursor secondStart = await(second.checkpoint(STREAM));
            JournalCursor otherStart = await(otherTenant.checkpoint(STREAM));
            assertEquals(0, firstStart.deliveredThrough());
            assertEquals(0, secondStart.deliveredThrough());
            assertEquals(0, otherStart.deliveredThrough());
            await(first.advance(firstStart, 41));
            await(second.advance(secondStart, 7));
            await(otherTenant.advance(otherStart, 12));
            assertEquals(41, await(first.checkpoint(STREAM)).deliveredThrough());
            assertEquals(7, await(second.checkpoint(STREAM)).deliveredThrough());
            assertEquals(12, await(otherTenant.checkpoint(STREAM)).deliveredThrough());
            assertTrue(await(first.recordInbox(STREAM, EVENT, RETENTION)));
            assertTrue(await(second.recordInbox(STREAM, EVENT, RETENTION)));
            assertTrue(await(otherTenant.recordInbox(STREAM, EVENT, RETENTION)));
            assertFalse(await(first.recordInbox(STREAM, EVENT, RETENTION)));
            assertIllegalArgument(() -> second.advance(firstStart, 99).toCompletableFuture().join());
            assertIllegalArgument(() -> otherTenant.advance(firstStart, 99).toCompletableFuture().join());
            assertEquals(7, await(second.checkpoint(STREAM)).deliveredThrough());
            assertEquals(12, await(otherTenant.checkpoint(STREAM)).deliveredThrough());
        }
    }

    @Test
    void aForgedCursorCannotCreateASlashSuffixedDestination() throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("forged-cursor.db"), Clock.systemUTC());
             var source = store.openSourceCheckpointStore(TENANT, CONSUMER)) {
            JournalCursor start = await(source.checkpoint(STREAM));
            JournalCursor forged = new JournalCursor(TENANT, start.destination() + "/other", 0);
            assertIllegalArgument(() -> source.advance(forged, 99).toCompletableFuture().join());
            assertEquals(0, await(store.outboxCursor(TENANT, forged.destination())).deliveredThrough(),
                    "the rejected cursor must not leave a row in another destination");
            assertEquals(0, await(source.checkpoint(STREAM)).deliveredThrough());
            assertEquals(1, await(source.advance(start, 1)).deliveredThrough(),
                    "rejecting a forged cursor must not retire the legitimate owner");
        }
    }

    @Test
    void cancellingAQueuedWriteAndClosingRetainsOwnershipUntilTheWriteActuallySettles() throws Exception {
        Path database = directory.resolve("queued-cancellation.db");
        var atCommit = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        CommitBoundary boundary = new CommitBoundary() {
            @Override public void beforeCommit() {
                if (!armed.compareAndSet(true, false)) return;
                atCommit.countDown();
                try {
                    if (!releaseCommit.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("test did not release the held transaction");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC(),
                     SqliteStoreConfig.defaults(), boundary);
             var competitor = new SqliteExecutionStore(database, Clock.systemUTC());
             var source = store.openSourceCheckpointStore(TENANT, CONSUMER)) {
            JournalCursor baseline = await(source.advance(await(source.checkpoint(STREAM)), 1));
            try {
                armed.set(true);
                var admission = source.recordInbox(STREAM, EVENT, RETENTION);
                assertTrue(atCommit.await(10, TimeUnit.SECONDS), "durable write did not reach commit boundary");
                var queuedWrite = source.advance(baseline, 42).toCompletableFuture();
                assertFalse(queuedWrite.isDone(), "the cursor write must be queued behind the held transaction");
                assertTrue(queuedWrite.cancel(true));
                source.close();
                assertTrue(queuedWrite.isCancelled());
                assertThrows(IllegalStateException.class,
                        () -> competitor.openSourceCheckpointStore(TENANT, CONSUMER),
                        "cancelling the public future must not release ownership of queued SQLite work");
                assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class,
                        () -> source.checkpoint(STREAM).toCompletableFuture().join()).getCause());

                releaseCommit.countDown();
                assertTrue(await(admission));
                // This read is queued after the cancelled public future's underlying write. Its
                // completion proves the real operation settled, without a sleep or ownership poll.
                assertEquals(42, await(store.outboxCursor(TENANT, baseline.destination())).deliveredThrough());
                try (var successor = competitor.openSourceCheckpointStore(TENANT, CONSUMER)) {
                    assertEquals(42, await(successor.checkpoint(STREAM)).deliveredThrough());
                    assertFalse(await(successor.recordInbox(STREAM, EVENT, RETENTION)));
                }
            } finally {
                releaseCommit.countDown();
            }
        }
    }

    private static void assertIllegalArgument(Runnable operation) {
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, operation::run).getCause());
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Child protocol uses explicit committed/closed boundaries and never coordinates through sleeps. */
    public static final class OwnerProcess {
        public static void main(String[] args) throws Exception {
            if (args.length == 2 && args[1].equals("PROBE")) {
                try (var store = new SqliteExecutionStore(Path.of(args[0]), Clock.systemUTC())) {
                    SourceCheckpointStore source;
                    try {
                        source = store.openSourceCheckpointStore(TENANT, CONSUMER);
                    } catch (IllegalStateException refused) {
                        System.out.println("REFUSED");
                        System.out.flush();
                        return;
                    }
                    try (source) {
                        System.out.println("ACQUIRED");
                        System.out.flush();
                    }
                }
                return;
            }
            try (var store = new SqliteExecutionStore(Path.of(args[0]), Clock.systemUTC());
                 SourceCheckpointStore source = store.openSourceCheckpointStore(TENANT, CONSUMER);
                 var commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                await(source.advance(await(source.checkpoint(STREAM)), 42));
                if (!await(source.recordInbox(STREAM, EVENT, RETENTION)))
                    throw new IllegalStateException("fixture event was already admitted");
                System.out.println("OWNED");
                System.out.flush();
                if (!"RELEASE".equals(commands.readLine())) return;
                source.close();
                System.out.println("RELEASED");
                System.out.flush();
                if (!"EXIT".equals(commands.readLine()))
                    throw new IllegalStateException("unexpected owner process command");
            }
        }
    }

    private static final class Child implements AutoCloseable {
        private static final String EOF = "<owner-process-eof>";
        private final Process process;
        private final BufferedWriter commands;
        private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final Thread reader;

        private Child(Process process) {
            this.process = process;
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) output.add(line);
                } catch (IOException failure) {
                    output.add("owner process output closed");
                } finally {
                    output.add(EOF);
                }
            });
        }

        static Child start(Path database) throws IOException {
            return start(database, "HOLD");
        }

        static Child start(Path database, String mode) throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            return new Child(new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                    OwnerProcess.class.getName(), database.toString(), mode).redirectErrorStream(true).start());
        }

        void awaitLine(String expected) throws Exception {
            var transcript = new ArrayList<String>();
            while (true) {
                String line = output.poll(10, TimeUnit.SECONDS);
                assertTrue(line != null && !line.equals(EOF),
                        "owner did not report " + expected + "; output: " + transcript);
                if (expected.equals(line)) return;
                transcript.add(line);
            }
        }

        void send(String command) throws IOException {
            commands.write(command);
            commands.newLine();
            commands.flush();
        }

        @Override public void close() throws Exception {
            if (process.isAlive()) process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "owner process did not terminate");
            try { commands.close(); } catch (IOException ignored) { }
            reader.join(Duration.ofSeconds(10));
            assertFalse(reader.isAlive(), "owner output reader did not stop");
        }
    }
}
