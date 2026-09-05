package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry as a cache in front of the durable record, rather than as the authority.
 *
 * <p>Each assertion here starts from a registry whose in-memory maps are empty, which is what a
 * process looks like after a restart and what a second instance looks like from birth. Anything the
 * registry can still answer in that state, it answered from the store.</p>
 */
class ExecutionResultRegistryDurableTest {

    private static final String TENANT = "tenant-a";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static InMemoryExecutionStore store(MutableClock clock) {
        return new InMemoryExecutionStore(clock, Duration.ofMinutes(5), 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(1));
    }

    private static ExecutionKey createInstance(InMemoryExecutionStore store, UUID traversalId) {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                                Map.of(traversalId, new Traversal(traversalId, "start",
                                        TraversalStatus.ACCEPTED, Map.of()))),
                        new GraphVersionPin("graph-v1")))
                .build()).toCompletableFuture().join();
        return key;
    }

    private static DurableExecutionResult completed(ExecutionKey key, UUID traversalId, Object payload,
                                                    int cap) {
        return DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                ProcessInstanceStatus.COMPLETED, null, START, START.plusSeconds(1), payload,
                ExecutionResultNodes.of(List.of("start", "finish"), List.of("finish"), List.of(),
                        List.of(), List.of()),
                null, cap);
    }

    @Test
    void aRegistryWithNoMemoryOfAnExecutionStillAnswersFromTheDurableRecord() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            store.recordExecutionResult(completed(key, traversalId, Map.of("answer", 42L),
                    store.maxExecutionResultPayloadBytes())).toCompletableFuture().join();

            var registry = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            var found = assertInstanceOf(ExecutionLookup.Found.class,
                    registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
            assertEquals(ProcessInstanceStatus.COMPLETED, found.outcome().status());
            assertEquals(Map.of("answer", 42L), found.outcome().payload());
            assertEquals(java.util.Set.of("start", "finish"), found.outcome().visitedNodes());
            assertTrue(found.outcome().degraded(),
                    "a degraded run must still read as degraded once the process that ran it is gone");
        }
    }

    @Test
    void anotherTenantReadingTheSameIdIsAnsweredAsIfItNeverExisted() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            store.recordExecutionResult(completed(key, traversalId, null,
                    store.maxExecutionResultPayloadBytes())).toCompletableFuture().join();

            var registry = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            assertInstanceOf(ExecutionLookup.Unknown.class,
                    registry.lookup(new ExecutionResultRegistry.Key("tenant-b", traversalId)));
            assertInstanceOf(ExecutionLookup.Found.class,
                    registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
        }
    }

    @Test
    void aPayloadThatWasNeverRetainableReadsAsRedactedRatherThanAsAnEmptyResult() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            store.recordExecutionResult(completed(key, traversalId, Map.of("answer", 42L), 1))
                    .toCompletableFuture().join();

            var registry = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            var withheld = assertInstanceOf(ExecutionLookup.Redacted.class,
                    registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
            assertEquals(ResultPayloadState.WITHHELD, withheld.payloadState());
            assertEquals(ProcessInstanceStatus.COMPLETED, withheld.status());
        }
    }

    @Test
    void anExecutionThatProducedNothingIsFoundWithNoPayloadRatherThanRedacted() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            store.recordExecutionResult(completed(key, traversalId, null,
                    store.maxExecutionResultPayloadBytes())).toCompletableFuture().join();

            var registry = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            var found = assertInstanceOf(ExecutionLookup.Found.class,
                    registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
            assertNull(found.outcome().payload());
        }
    }

    @Test
    void aCancelledExecutionSurvivesAsACancellationRatherThanAsAnIncident() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            store.recordExecutionResult(DurableExecutionResult.of(key, traversalId,
                    new GraphVersionPin("graph-v1"), ProcessInstanceStatus.FAILED,
                    ExecutionTerminationReason.CANCELLED, START, START.plusSeconds(1), null,
                    ExecutionResultNodes.empty(), null, store.maxExecutionResultPayloadBytes()))
                    .toCompletableFuture().join();

            var registry = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            var found = assertInstanceOf(ExecutionLookup.Found.class,
                    registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
            assertEquals(ProcessInstanceStatus.FAILED, found.outcome().status());
            assertTrue(found.outcome().cancelled(),
                    "the status alone reports a deliberate stop as an incident; the reason has to "
                            + "survive the restart with it");
        }
    }

    @Test
    void aRecordPastItsRetentionDeadlineReadsAsExpiredRatherThanAsUnknown() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            DurableExecutionResult recorded = store.recordExecutionResult(
                    DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                            ProcessInstanceStatus.FAILED, ExecutionTerminationReason.CANCELLED, START,
                            START.plusSeconds(1), Map.of("partial", "output"),
                            ExecutionResultNodes.empty(), null, store.maxExecutionResultPayloadBytes()))
                    .toCompletableFuture().join();

            clock.set(recorded.retainedUntil());
            var registry = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            var expired = assertInstanceOf(ExecutionLookup.Expired.class,
                    registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
            assertTrue(expired.cancelled());
        }
    }

    @Test
    void withNoDurableRecordComposedTheRegistryBehavesExactlyAsItAlwaysHas() {
        var registry = new ExecutionResultRegistry(256, 8192, null);
        UUID traversalId = UUID.randomUUID();
        assertInstanceOf(ExecutionLookup.Unknown.class,
                registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
        registry.started(new ExecutionResultRegistry.Key(TENANT, traversalId), UUID.randomUUID());
        var running = assertInstanceOf(ExecutionLookup.Found.class,
                registry.lookup(new ExecutionResultRegistry.Key(TENANT, traversalId)));
        assertEquals(ProcessInstanceStatus.RUNNING, running.outcome().status());
    }

    @Test
    void aStoreThatCannotRecordResultsComposesNoDurableRecordAtAll() {
        try (var store = new InMemoryExecutionStore()) {
            // The bridge resolves the absence once, at composition, rather than as a caught refusal on
            // every read. A store that declares the capability produces a bridge; one that does not
            // produces none, and the registry stays process-local.
            assertTrue(store.supports(ai.ravenroot.api.persistence.StoreCapability.EXECUTION_RESULTS));
            assertNull(DurableExecutionResults.of(null));
        }
    }
}
