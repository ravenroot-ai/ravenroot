package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker recorded {@code RUNNING} under a valid fence and dispatched a node, and loses the
 * fence while that node is still executing -- not before dispatch, which is already proven,
 * and not because it crashed (a killed process makes no further write attempt at all, so it cannot
 * demonstrate this). It is a live worker that discovers the loss only when it finally tries to record
 * what the node produced.
 *
 * <h2>Why {@link ExecutionRecorderCoexistenceTest#anEngineThatLostTheFenceStopsWritingRatherThanDiscoveringItAfterTheNextEffect()}
 * is not this test</h2>
 * <p>That test proves the same logical property -- record-after-losing-the-fence is refused -- but
 * inside one JVM, one thread, one shared {@code InMemoryExecutionStore} and one shared {@code Clock}
 * reference the two "workers" both read. The cross-process contract requires the opposite: proof
 * between real operating-system processes, sharing nothing but a database file, because that is the
 * only way to exercise the actual mechanism this depends on -- a real SQLite-backed lease and a real
 * fencing token compared across two independent connections, not two threads cooperating inside one.
 * {@link ai.ravenroot.persistence.sqlite.SqliteLeaseAbandonedByKillTest} proves the store-level half of
 * this cross-process for a <em>killed</em> process; this test proves the runtime-level half -- what
 * {@link ExecutionRecorder} itself does with the store's refusal -- for a process that stays alive.
 *
 * <h2>What this does and does not prove</h2>
 * <p>It proves the fenced-out worker's own late write never lands, so it can never silently overwrite
 * or coexist with whatever the worker that took over has already committed. It does not, and cannot,
 * prevent the fenced-out worker's <em>external</em> effect (whatever the node itself did) from having
 * already happened before this write was attempted -- that is the residual window {@link
 * ExecutionRecorder}'s own class Javadoc now declares. What this test rules out is the store ever
 * recording two conflicting truths about the same attempt.
 */
class ExecutionRecorderCrossProcessFenceLossTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @TempDir
    Path databaseDirectory;

    @Test
    void aLiveWorkerThatLosesTheFenceMidExecutionIsRefusedWhenItFinallyRecordsCompletion() throws Exception {
        Path file = databaseDirectory.resolve("fence-loss-mid-execution.db");
        var tenantId = "acme";
        var processInstanceId = UUID.randomUUID();
        var traversalId = UUID.randomUUID();
        var invocationId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        var key = new ExecutionKey(tenantId, processInstanceId);

        bootstrapInstance(file, key, traversalId);

        Process child = startChild(file, tenantId, processInstanceId, traversalId, invocationId, attemptId);
        var transcript = new ArrayList<String>();
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            assertTrue(readUntilBoundary(output, transcript).isPresent(),
                    "the child never reached the boundary after committing RUNNING; transcript: " + transcript);

            long revisionAfterRunning = readRevision(file, key);

            // Exclusion still holds while the child is genuinely alive and its lease has not expired.
            var recoveryClock = new MutableClock(EPOCH);
            try (var contendedStore = new SqliteExecutionStore(file, recoveryClock)) {
                assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class,
                        failureOf(() -> await(contendedStore.claim(key, "recovery-worker", TTL))),
                        "the fence must still exclude a second claimant while the first is genuinely "
                                + "still holding it -- if this fails, the takeover below proves nothing "
                                + "about losing a fence, only about there never having been one");
            }

            // Another worker takes over -- exactly what ExecutionRecoveryService does once it decides
            // the lease is expired -- while the ORIGINAL process is still alive, still holding its file
            // handle open, still about to try to record what its node produced. Not a killed process:
            // a live one that simply no longer owns the fence, discovered only at its next write.
            recoveryClock.set(EPOCH.plus(TTL).plusSeconds(1));
            try (var recoveryStore = new SqliteExecutionStore(file, recoveryClock)) {
                await(recoveryStore.claim(key, "recovery-worker", TTL));
            }
            long revisionAfterTakeover = readRevision(file, key);
            assertEquals(revisionAfterRunning, revisionAfterTakeover,
                    "claiming the lease alone must not itself add a transition, or the assertion below "
                            + "-- that the fenced-out process changes nothing -- would be measuring the "
                            + "wrong write");

            // The node's external call, in this stand-in, has just returned -- after another worker
            // already took over.
            child.getOutputStream().write((RecordAcrossFenceLossInAnotherProcess.PROCEED + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            child.getOutputStream().flush();

            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "the child never exited");
            readRemaining(output, transcript);
            assertEquals(0, child.exitValue(), "a fenced-out worker discovering a refusal is not a "
                    + "crash -- it must exit cleanly, or this test cannot tell a caught refusal from "
                    + "an uncaught one; transcript: " + transcript);

            assertTrue(transcript.stream().anyMatch(line -> line.startsWith(
                            RecordAcrossFenceLossInAnotherProcess.WRITE_REFUSED)),
                    "the fenced-out worker's completion write must be refused once another worker has "
                            + "taken over mid-execution -- otherwise its late write can land on top of "
                            + "state the new owner already believes only it can change; transcript: "
                            + transcript);
            assertFalse(transcript.stream().anyMatch(line -> line.startsWith(
                            RecordAcrossFenceLossInAnotherProcess.WRITE_SUCCEEDED)),
                    "the fenced-out worker's write must never be reported as successful; transcript: "
                            + transcript);

            assertEquals(revisionAfterTakeover, readRevision(file, key),
                    "no transition from the fenced-out process may have reached the store after the "
                            + "takeover: the revision must be exactly what the new owner left it at, "
                            + "proving the store never recorded two conflicting truths about this "
                            + "attempt");
        } finally {
            child.destroyForcibly();
        }
    }

    private void bootstrapInstance(Path file, ExecutionKey key, UUID traversalId) {
        try (var store = new SqliteExecutionStore(file, Clock.fixed(EPOCH, ZoneOffset.UTC))) {
            var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                    Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
            StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1")))
                    .build()));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .build()));
        }
    }

    private long readRevision(Path file, ExecutionKey key) {
        try (var store = new SqliteExecutionStore(file, Clock.fixed(EPOCH, ZoneOffset.UTC))) {
            return await(store.load(key)).revision();
        }
    }

    private Process startChild(Path file, String tenantId, UUID processInstanceId, UUID traversalId,
                               UUID invocationId, UUID attemptId) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        var command = List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                RecordAcrossFenceLossInAnotherProcess.class.getName(), file.toString(), tenantId,
                processInstanceId.toString(), traversalId.toString(), invocationId.toString(),
                attemptId.toString(), EPOCH.toString(), TTL.toString());
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (RecordAcrossFenceLossInAnotherProcess.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static void readRemaining(BufferedReader output, List<String> transcript) throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            throw wrapped;
        }
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ai.ravenroot.api.persistence.ExecutionStoreException failure =
                ai.ravenroot.api.persistence.ExecutionStoreException.unwrap(thrown);
        org.junit.jupiter.api.Assertions.assertNotNull(failure, "adapters must not leak non-store exceptions: "
                + thrown);
        return failure.failure();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant value) {
            now = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
