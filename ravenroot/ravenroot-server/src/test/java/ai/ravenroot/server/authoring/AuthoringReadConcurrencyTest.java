package ai.ravenroot.server.authoring;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@org.junit.jupiter.api.Timeout(30)
class AuthoringReadConcurrencyTest {
    @Test
    void identicalBurstsSingleFlightFailuresRetryAndCancellationDoesNotPoisonOwner()
            throws Exception {
        var cache = new ReadObjects();
        var index = new VolatileGraphAuthoringReadIndex();
        byte[] bytes = "immutable".getBytes();
        String id = blobId(bytes);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner =
                    executor.submit(
                            () ->
                                    cache.blob(
                                            id,
                                            index,
                                            "repository",
                                            () -> {
                                                calls.incrementAndGet();
                                                entered.countDown();
                                                await(release);
                                                return bytes;
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var waiter =
                    executor.submit(
                            () ->
                                    cache.blob(
                                            id,
                                            index,
                                            "repository",
                                            () -> {
                                                throw new AssertionError();
                                            }));
            waiter.cancel(true);
            var readers = new ArrayList<Future<byte[]>>();
            for (int i = 0; i < 50; i++)
                readers.add(
                        executor.submit(
                                () ->
                                        cache.blob(
                                                id,
                                                index,
                                                "repository",
                                                () -> {
                                                    throw new AssertionError();
                                                })));
            release.countDown();
            assertArrayEquals(bytes, owner.get(5, TimeUnit.SECONDS));
            for (var reader : readers) assertArrayEquals(bytes, reader.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(bytes.length, cache.retainedBytes());
            assertEquals(0, cache.inFlight());
            String other = blobId("retry".getBytes());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            cache.blob(
                                    other,
                                    index,
                                    "repository",
                                    () -> {
                                        throw new IllegalStateException("fixture failure");
                                    }));
            assertArrayEquals(
                    "retry".getBytes(),
                    cache.blob(other, index, "repository", () -> "retry".getBytes()));
        }
    }

    @Test
    void sharedFailureWakesEveryWaiterAndRetryDoesNotCacheFailure() throws Exception {
        var cache = new ReadObjects();
        var index = new VolatileGraphAuthoringReadIndex();
        byte[] bytes = "retry shared failure".getBytes();
        String identity = blobId(bytes);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<byte[]>>();
            tasks.add(
                    executor.submit(
                            () ->
                                    cache.blob(
                                            identity,
                                            index,
                                            "repository",
                                            () -> {
                                                calls.incrementAndGet();
                                                entered.countDown();
                                                await(release);
                                                throw new IllegalStateException(
                                                        "fixture shared failure");
                                            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++)
                tasks.add(
                        executor.submit(
                                () ->
                                        cache.blob(
                                                identity,
                                                index,
                                                "repository",
                                                () -> {
                                                    throw new AssertionError(
                                                            "waiter must share owner failure");
                                                })));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cache.hits.sum() < 50 && System.nanoTime() < deadline) Thread.sleep(1);
            try {
                assertEquals(50, cache.hits.sum());
            } finally {
                release.countDown();
            }
            for (var task : tasks)
                assertInstanceOf(
                        IllegalStateException.class,
                        assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS))
                                .getCause());
            assertEquals(1, calls.get());
            assertEquals(0, cache.retainedBytes());
            assertEquals(0, cache.inFlight());
            assertArrayEquals(bytes, cache.blob(identity, index, "repository", () -> bytes));
        }
    }

    @Test
    void cancellationRetainsAdmissionUntilSlowProviderActuallyTerminates() throws Exception {
        for (boolean github : List.of(true, false)) {
            var gh = github ? new GithubAuthoringRepositoryTest.ScriptedGithub() : null;
            var gl = github ? null : new GitlabAuthoringRepositoryTest.Gitlab();
            if (github) gh.seed(10);
            else gl.seed(10);
            var entered = new CountDownLatch(64);
            var release = new CountDownLatch(1);
            Runnable slow =
                    () -> {
                        entered.countDown();
                        boolean interrupted = false;
                        while (release.getCount() != 0)
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        if (interrupted) Thread.currentThread().interrupt();
                    };
            if (github) gh.beforeRequest = slow;
            else gl.beforeRequest = slow;
            ai.ravenroot.api.authoring.GraphAuthoringRepository repository =
                    github
                            ? GithubAuthoringRepositoryTest.repository(
                                    gh, PublicationEvidence.absent())
                            : GitlabAuthoringRepositoryTest.repository(
                                    gl, PublicationEvidence.absent());
            java.util.function.LongSupplier admitted =
                    () ->
                            github
                                    ? ((GithubAuthoringRepository) repository)
                                            .readStatistics()
                                            .get("admittedOperations")
                                    : ((GitlabAuthoringRepository) repository)
                                            .readStatistics()
                                            .get("admittedOperations");
            try {
                var active = new ArrayList<CompletableFuture<?>>();
                for (int i = 0; i < 64; i++)
                    active.add(
                            repository
                                    .open(
                                            AuthoringReadIndexIntegrationTest.ACTOR,
                                            "g0000.graphml",
                                            false)
                                    .toCompletableFuture());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                active.forEach(future -> future.cancel(true));
                assertEquals(
                        64,
                        admitted.getAsLong(),
                        "cancelled but interrupt-delayed providers still own their admission");
                for (int i = 0; i < 1000; i++) {
                    repository
                            .open(AuthoringReadIndexIntegrationTest.ACTOR, "g0000.graphml", false)
                            .toCompletableFuture()
                            .cancel(true);
                    assertTrue(admitted.getAsLong() >= 64 && admitted.getAsLong() <= 128);
                }
            } finally {
                release.countDown();
                repository.close();
            }
            assertEquals(0, admitted.getAsLong());
            assertThrows(
                    CompletionException.class,
                    () ->
                            repository
                                    .open(
                                            AuthoringReadIndexIntegrationTest.ACTOR,
                                            "g0000.graphml",
                                            false)
                                    .toCompletableFuture()
                                    .join());
            assertEquals(0, admitted.getAsLong(), "submission rejection releases exactly once");
        }
    }

    @Test
    void cancellationBeforeTaskStartReleasesExactlyOnce() {
        var admission = new Semaphore(128);
        for (int i = 0; i < 1000; i++) {
            assertTrue(admission.tryAcquire());
            var task =
                    new AuthoringAdmission(
                            admission,
                            () -> {
                                throw new AssertionError("cancelled task must not execute");
                            });
            assertTrue(task.cancel(true));
            task.run();
            task.cancel(true);
            task.run();
            assertEquals(128, admission.availablePermits());
        }
    }

    @Test
    void uniqueMissAdmissionAndEvictionAreBounded() throws Exception {
        var cache = new ReadObjects();
        var index = new VolatileGraphAuthoringReadIndex();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(16);
        var rejected = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) {
                final byte[] bytes = ("unique" + i).getBytes();
                tasks.add(
                        executor.submit(
                                () -> {
                                    try {
                                        cache.blob(
                                                blobId(bytes),
                                                index,
                                                "repository",
                                                () -> {
                                                    entered.countDown();
                                                    await(release);
                                                    return bytes;
                                                });
                                    } catch (GraphAuthoringException failure) {
                                        assertEquals(
                                                GraphAuthoringException.Failure.LIMIT_EXCEEDED,
                                                failure.failure());
                                        rejected.incrementAndGet();
                                    }
                                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(cache.inFlight() <= 64);
            release.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertTrue(rejected.get() > 0);
            for (int i = 0; i < 2200; i++) {
                byte[] bytes = ("evict" + i).getBytes();
                cache.blob(blobId(bytes), index, "repository", () -> bytes);
            }
            assertTrue(cache.evictions.sum() > 0);
            assertTrue(cache.retainedBytes() <= 64 * 1024 * 1024L);
            assertEquals(0, cache.inFlight());
        }
    }

    @Test
    void digestAdmissionRejectsWrongObjectsWithoutCachingFailure() {
        var cache = new ReadObjects();
        var index = new VolatileGraphAuthoringReadIndex();
        byte[] expected = "correct".getBytes();
        String identity = blobId(expected);
        assertThrows(
                GraphAuthoringException.class,
                () -> cache.blob(identity, index, "repository", () -> "wrong".getBytes()));
        assertArrayEquals(expected, cache.blob(identity, index, "repository", () -> expected));
    }

    @Test
    void invalidationRaceRetainsPinnedOldGenerationAndReconcilesNewHead() throws Exception {
        var api = new GithubAuthoringRepositoryTest.ScriptedGithub();
        api.seed(10);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        api.beforeBlob =
                () -> {
                    entered.countDown();
                    await(release);
                };
        try (var repository =
                GithubAuthoringRepositoryTest.repository(api, PublicationEvidence.absent())) {
            var old =
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            api.external(
                    "graphs/customers/a/g0000.graphml",
                    AuthoringReadIndexIntegrationTest.graph("g0", "new-head"));
            release.countDown();
            var first = old.get(10, TimeUnit.SECONDS);
            var fresh =
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture()
                            .join();
            assertNotEquals(
                    first.items().getFirst().revision(), fresh.items().getFirst().revision());
            assertTrue(
                    new String(
                                    repository
                                            .open(
                                                    AuthoringReadIndexIntegrationTest.ACTOR,
                                                    "g0000.graphml",
                                                    false)
                                            .toCompletableFuture()
                                            .join()
                                            .graphMl())
                            .contains("new-head"));
        }
    }

    @Test
    void cancelledAdapterLoadStopsWorkAndLaterRetryRepairs() throws Exception {
        var api = new GithubAuthoringRepositoryTest.ScriptedGithub();
        api.seed(10);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        api.beforeBlob =
                () -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException failure) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new GraphAuthoringException(
                                GraphAuthoringException.Failure.UNAVAILABLE);
                    }
                };
        try (var repository =
                GithubAuthoringRepositoryTest.repository(api, PublicationEvidence.absent())) {
            var read =
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(read.cancel(true));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(
                    10,
                    repository
                            .list(AuthoringReadIndexIntegrationTest.ACTOR, "")
                            .toCompletableFuture()
                            .join()
                            .items()
                            .size());
        }
    }

    @Test
    void signalsCoalesceAndPeriodicRecoveryRepairsLostEvents() throws Exception {
        var api = new GithubAuthoringRepositoryTest.ScriptedGithub();
        api.seed(10);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        api.beforeBlob =
                () -> {
                    entered.countDown();
                    await(release);
                };
        try (var repository =
                new ReconciledGraphAuthoringRepository(
                        GithubAuthoringRepositoryTest.repository(api, PublicationEvidence.absent()),
                        Set.of("tenant-a"),
                        java.time.Duration.ofSeconds(1))) {
            var decorated =
                    ForwardingGraphAuthoringRepository.managed(
                            repository, ForwardingGraphAuthoringRepository::new);
            var first =
                    GraphAuthoringRepositories.signal(decorated, "tenant-a").toCompletableFuture();
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () ->
                            GraphAuthoringRepositories.signal(decorated, "foreign-tenant")
                                    .toCompletableFuture()
                                    .join());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var signals = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 50; i++)
                signals.add(
                        GraphAuthoringRepositories.signal(decorated, "tenant-a")
                                .toCompletableFuture());
            api.external(
                    "graphs/customers/a/g0000.graphml",
                    AuthoringReadIndexIntegrationTest.graph("g0", "event-change"));
            release.countDown();
            CompletableFuture.allOf(signals.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            first.join();
            assertTrue(api.benchmarkBlobs.get() <= 11);
            long completed = repository.reconciliationStatistics().get("completed");
            int beforeLostEvent = api.benchmarkBlobs.get();
            api.external(
                    "graphs/customers/a/g0001.graphml",
                    AuthoringReadIndexIntegrationTest.graph("g1", "lost-event"));
            // The scheduled recovery is a real event-driven wait, not production-provider stress.
            var recovered = new CountDownLatch(1);
            try (var timer = Executors.newSingleThreadScheduledExecutor()) {
                timer.scheduleWithFixedDelay(
                        () -> {
                            if (repository.reconciliationStatistics().get("completed") > completed
                                    && api.benchmarkBlobs.get() > beforeLostEvent)
                                recovered.countDown();
                        },
                        10,
                        10,
                        TimeUnit.MILLISECONDS);
                assertTrue(recovered.await(5, TimeUnit.SECONDS));
            }
            assertTrue(repository.reconciliationStatistics().get("lagMillis") >= 0);
        }
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
        }
    }

    static String blobId(byte[] bytes) {
        var framed = new java.io.ByteArrayOutputStream();
        framed.writeBytes(
                ("blob " + bytes.length + "\0")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        framed.writeBytes(bytes);
        return ReadObjects.hash("SHA-1", framed.toByteArray());
    }
}
