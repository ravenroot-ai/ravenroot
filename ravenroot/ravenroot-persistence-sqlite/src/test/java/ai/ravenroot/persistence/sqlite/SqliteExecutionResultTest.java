package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable canonical result, against the adapter that can actually lose one.
 *
 * <p>These assertions live here rather than in the shared conformance suite because the properties
 * the work is about — that a result written by one process is readable by a second one that never ran
 * it, and that it survives the file being closed and reopened — are only observable on an adapter
 * that survives a restart. The adapter-neutral half belongs in the suite, and is deliberately left
 * for it; what cannot be delegated is the reopen.</p>
 */
class SqliteExecutionResultTest {

    private static final String TENANT = "tenant-a";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static SqliteExecutionStore store(Path directory, MutableClock clock) {
        return new SqliteExecutionStore(directory.resolve("results.db"), clock,
                SqliteStoreConfig.defaults().withExecutionResultRetention(Duration.ofHours(1)));
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Executable call) {
        var thrown = assertThrows(CompletionException.class, call::run);
        ExecutionStoreException unwrapped = ExecutionStoreException.unwrap(thrown);
        assertNotNull(unwrapped, "expected a classified store failure, got " + thrown.getCause());
        return unwrapped.failure();
    }

    /** A call that is expected to fail, kept out of the assertion so the failure is the subject. */
    private interface Executable {
        void run();
    }

    private static DurableExecutionResult completedResult(ExecutionKey key, UUID traversalId,
                                                          Object payload, int cap) {
        return DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                ProcessInstanceStatus.COMPLETED, null, START, START.plusSeconds(3), payload,
                ExecutionResultNodes.of(List.of("start", "finish"), List.of(), List.of("skipped"),
                        List.of(), List.of()),
                null, cap);
    }

    private static UUID recordInstance(SqliteExecutionStore store, ExecutionKey key, UUID traversalId) {
        await(store.apply(Fixtures.creationBatch(key, traversalId)));
        return traversalId;
    }

    @Test
    void theResultOfATerminalExecutionSurvivesClosingAndReopeningTheDatabase(@TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        DurableExecutionResult recorded;
        try (SqliteExecutionStore store = store(directory, clock)) {
            assertTrue(store.supports(StoreCapability.EXECUTION_RESULTS));
            recordInstance(store, key, traversalId);
            recorded = await(store.recordExecutionResult(
                    completedResult(key, traversalId, Map.of("answer", 42L),
                            store.maxExecutionResultPayloadBytes())));
        }

        // A second store over the same file, with no memory of the first: this is the multi-instance
        // read and the restart recovery at once, because nothing distinguishes "another process" from
        // "this process after a restart" once the only shared state is the file.
        try (SqliteExecutionStore reopened = store(directory, clock)) {
            DurableExecutionResult read = await(reopened.loadExecutionResult(TENANT, traversalId))
                    .orElseThrow();
            assertEquals(recorded.fingerprint(), read.fingerprint(),
                    "the record read back must be the record written, byte for byte in its canonical form");
            assertEquals(ResultPayloadState.RETAINED, read.payload().state());
            assertEquals(ProcessInstanceStatus.COMPLETED, read.status());
            assertEquals(new GraphVersionPin("graph-v1"), read.graphVersionPin());
            assertEquals(List.of("finish", "start"), read.nodes().visitedNodes(),
                    "node sets are stored in their canonical order, not in the order they were offered");
            assertEquals(List.of("skipped"), read.nodes().bypassedNodes());
            assertEquals(recorded.retainedUntil(), read.retainedUntil());
        }
    }

    @Test
    void recordingTheIdenticalResultTwiceChangesNothingAndSucceeds(@TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            int cap = store.maxExecutionResultPayloadBytes();
            DurableExecutionResult first = await(store.recordExecutionResult(
                    completedResult(key, traversalId, Map.of("answer", 42L), cap)));

            // The re-delivery arrives later on the store's clock. It must still be a no-op: the
            // deadline is derived from endedAt rather than from now for exactly this reason, so the
            // duplicate reproduces the first record instead of conflicting with it.
            clock.advance(Duration.ofMinutes(5));
            DurableExecutionResult second = await(store.recordExecutionResult(
                    completedResult(key, traversalId, Map.of("answer", 42L), cap)));

            assertEquals(first.fingerprint(), second.fingerprint());
            assertEquals(first.retainedUntil(), second.retainedUntil(),
                    "a duplicate must not move the retention deadline it found");
            assertEquals(first, await(store.loadExecutionResult(TENANT, traversalId)).orElseThrow());
        }
    }

    @Test
    void recordingADifferentResultForTheSameTraversalIsRefusedAndNamesBothOutcomes(
            @TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            int cap = store.maxExecutionResultPayloadBytes();
            DurableExecutionResult committed = await(store.recordExecutionResult(
                    completedResult(key, traversalId, Map.of("answer", 42L), cap)));

            var conflicting = DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                    ProcessInstanceStatus.FAILED, ExecutionTerminationReason.CANCELLED, START,
                    START.plusSeconds(3), null, ExecutionResultNodes.empty(), null, cap);
            ExecutionStoreFailure failure =
                    failureOf(() -> await(store.recordExecutionResult(conflicting)));

            var refusal = assertInstanceOfNotRecordable(failure);
            assertEquals(traversalId, refusal.traversalId());
            assertEquals(ProcessInstanceStatus.COMPLETED, refusal.current());
            assertEquals(ProcessInstanceStatus.FAILED, refusal.requested());
            assertEquals(committed.fingerprint(), refusal.currentFingerprint());
            assertEquals(conflicting.withRetainedUntil(committed.retainedUntil()).fingerprint(),
                    refusal.requestedFingerprint(),
                    "the refusal must name the digest of what was actually offered");
            assertEquals(ai.ravenroot.api.persistence.Retryability.DETERMINISTIC_REJECT,
                    failure.retryability());

            // And the committed outcome is left exactly as it was: a refusal is not a partial write.
            assertEquals(committed, await(store.loadExecutionResult(TENANT, traversalId)).orElseThrow());
        }
    }

    private static ExecutionStoreFailure.ExecutionResultNotRecordable assertInstanceOfNotRecordable(
            ExecutionStoreFailure failure) {
        assertTrue(failure instanceof ExecutionStoreFailure.ExecutionResultNotRecordable,
                "expected a result refusal, got " + failure);
        return (ExecutionStoreFailure.ExecutionResultNotRecordable) failure;
    }

    @Test
    void anotherTenantReadsAResultAsIndistinguishableFromAMissingOne(@TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            await(store.recordExecutionResult(completedResult(key, traversalId, Map.of("answer", 42L),
                    store.maxExecutionResultPayloadBytes())));

            Optional<DurableExecutionResult> foreign =
                    await(store.loadExecutionResult("tenant-b", traversalId));
            Optional<DurableExecutionResult> absent =
                    await(store.loadExecutionResult("tenant-b", UUID.randomUUID()));
            assertEquals(absent, foreign,
                    "a cross-tenant read and a nonexistent id must be the same answer, or the store "
                            + "is an existence oracle");
            assertTrue(foreign.isEmpty());
            assertTrue(await(store.loadExecutionResult(TENANT, traversalId)).isPresent());
        }
    }

    @Test
    void aResultPastItsRetentionDeadlineReadsAsExpiredAndThenAsAbsentOncePurged(@TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            DurableExecutionResult recorded = await(store.recordExecutionResult(
                    DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                            ProcessInstanceStatus.FAILED, ExecutionTerminationReason.CANCELLED, START,
                            START.plusSeconds(1), Map.of("partial", "output"),
                            ExecutionResultNodes.empty(), null,
                            store.maxExecutionResultPayloadBytes())));
            assertEquals(Instant.MIN, await(store.executionResultsRetainedFrom(TENANT)),
                    "a tenant that has purged nothing has forgotten nothing");

            clock.set(recorded.retainedUntil());
            DurableExecutionResult expired = await(store.loadExecutionResult(TENANT, traversalId))
                    .orElseThrow();
            assertEquals(ResultPayloadState.EXPIRED, expired.payload().state());
            assertNull(expired.payload().retained(), "an expired read offers no bytes");
            assertEquals(ProcessInstanceStatus.FAILED, expired.status());
            assertTrue(expired.cancelled(),
                    "past the horizon the status alone says a cancelled run faulted, so the reason "
                            + "has to outlive the payload");

            assertEquals(1L, await(store.purgeExpiredExecutionResults(TENANT)));
            assertEquals(recorded.retainedUntil(), await(store.executionResultsRetainedFrom(TENANT)),
                    "the floor is the latest deadline the purge actually crossed");
            assertTrue(await(store.loadExecutionResult(TENANT, traversalId)).isEmpty());

            assertEquals(0L, await(store.purgeExpiredExecutionResults(TENANT)));
            assertEquals(recorded.retainedUntil(), await(store.executionResultsRetainedFrom(TENANT)),
                    "a purge that removed nothing must not move the floor");
        }
    }

    @Test
    void aPayloadTooLargeToProjectIsWithheldWithItsSizeRatherThanReportedAsAbsent(
            @TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            // A cap of one byte cannot hold any encoding at all, so the projection is refused for
            // size while the metadata that says by how much survives.
            await(store.recordExecutionResult(
                    completedResult(key, traversalId, Map.of("answer", 42L), 1)));

            DurableExecutionResult read = await(store.loadExecutionResult(TENANT, traversalId))
                    .orElseThrow();
            assertEquals(ResultPayloadState.WITHHELD, read.payload().state());
            assertTrue(read.payload().bytes() > 1,
                    "a refused payload still reports the size that was refused");
            assertNull(read.payload().retained());
            assertFalse(read.payload().available());
        }
    }

    @Test
    void anExecutionThatProducedNothingIsDistinguishableFromOneWhoseOutputWasRefused(
            @TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            await(store.recordExecutionResult(
                    completedResult(key, traversalId, null, store.maxExecutionResultPayloadBytes())));

            DurableExecutionResult read = await(store.loadExecutionResult(TENANT, traversalId))
                    .orElseThrow();
            assertEquals(ResultPayloadState.NONE, read.payload().state());
            assertEquals(0, read.payload().bytes());
            assertNull(read.payload().contentType(),
                    "nothing was produced, so there is no media type to report");
        }
    }

    @Test
    void aResultNamingAnInstanceThisTenantDoesNotHaveIsRefusedRatherThanWrittenOrphaned(
            @TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        try (SqliteExecutionStore store = store(directory, clock)) {
            ExecutionStoreFailure failure = failureOf(() -> await(store.recordExecutionResult(
                    completedResult(key, UUID.randomUUID(), null,
                            store.maxExecutionResultPayloadBytes()))));
            assertTrue(failure instanceof ExecutionStoreFailure.NotFound,
                    "expected NotFound, got " + failure);
        }
    }

    @Test
    void purgingAnInstanceTakesItsResultWithIt(@TempDir Path directory) {
        var clock = new MutableClock(START);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (SqliteExecutionStore store = store(directory, clock)) {
            recordInstance(store, key, traversalId);
            await(store.recordExecutionResult(
                    completedResult(key, traversalId, null, store.maxExecutionResultPayloadBytes())));
            assertTrue(await(store.loadExecutionResult(TENANT, traversalId)).isPresent());

            // Only a terminal instance is ever eligible for the inventory purge, so the instance has
            // to reach one: age alone is never evidence that work has finished.
            await(store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                    .expecting(ai.ravenroot.api.persistence.RevisionExpectation.exactly(1))
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                            ProcessInstanceStatus.RUNNING))
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.TraversalTransitioned(
                            traversalId, ai.ravenroot.api.application.TraversalStatus.RUNNING))
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.TraversalTransitioned(
                            traversalId, ai.ravenroot.api.application.TraversalStatus.COMPLETED))
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                            ProcessInstanceStatus.COMPLETED))
                    .build()));

            // The cascade is what keeps a result from naming an instance the inventory has forgotten,
            // and it is why the configuration refuses a result window longer than the instance one.
            clock.advance(store.terminalRetention().plusDays(1));
            assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT)));
            assertTrue(await(store.loadExecutionResult(TENANT, traversalId)).isEmpty());
        }
    }

    @Test
    void aResultWindowLongerThanTheInstanceWindowIsRefusedRatherThanQuietlyNotHonoured() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> SqliteStoreConfig.defaults()
                        .withExecutionResultRetention(Duration.ofDays(30)));
        assertTrue(refused.getMessage().contains("executionResultRetention"), refused.getMessage());
    }

    @Test
    void theStoreDeclaresTheWindowItActuallyApplies(@TempDir Path directory) {
        var clock = new MutableClock(START);
        try (SqliteExecutionStore store = store(directory, clock)) {
            assertEquals(Duration.ofHours(1), store.executionResultRetention());
        }
    }
}
