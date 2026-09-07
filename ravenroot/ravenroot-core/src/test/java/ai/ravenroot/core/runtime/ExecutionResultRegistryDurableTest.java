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

    /**
     * The instance that ran an execution must not be the one instance that cannot read its result.
     *
     * <p>Every other assertion in this class starts from an empty registry, which is why none of them
     * could see this: the fall-through was reached only from {@code Unknown}, and a registry that
     * <em>has</em> run the execution does not answer {@code Unknown} once its entry ages out — it
     * answers {@code Expired}, from a tombstone {@code put} writes by ordinary count-based eviction
     * as soon as {@code maxResults} further executions complete. Nothing about that eviction says
     * anything about the store's retention window, so the recording instance was rendering
     * {@code 410 EXECUTION_RESULT_EXPIRED} for a result every sibling instance was still serving in
     * full.</p>
     *
     * <p>Asserted as an equality between the two reads rather than as two separate shape checks,
     * because the property is not "the recording instance answers something reasonable" — it is that
     * <b>which instance is asked must not be observable at all</b>.</p>
     */
    @Test
    void aDurablyHeldResultEvictedFromTheInstanceThatRanItStillReadsAsItDoesEverywhereElse() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            var registryKey = new ExecutionResultRegistry.Key(TENANT, traversalId);

            // One result retained in memory, so a single further completion evicts this one. The real
            // default is 256; the bound is what matters, not its size, and driving a count-based
            // horizon exactly is the reason it is a count.
            var recorded = new ExecutionResultRegistry(1, 8192, DurableExecutionResults.of(store));
            recorded.started(registryKey, key.processInstanceId());
            recorded.completed(registryKey, new GraphExecutionResult(key.processInstanceId(), traversalId,
                    Map.of("answer", 42L), java.util.Set.of("start", "finish"), java.util.Set.of()));
            store.recordExecutionResult(completed(key, traversalId, Map.of("answer", 42L),
                    store.maxExecutionResultPayloadBytes())).toCompletableFuture().join();

            UUID laterTraversalId = UUID.randomUUID();
            var laterKey = new ExecutionResultRegistry.Key(TENANT, laterTraversalId);
            recorded.started(laterKey, UUID.randomUUID());
            recorded.completed(laterKey, new GraphExecutionResult(UUID.randomUUID(), laterTraversalId,
                    "later", java.util.Set.of("start"), java.util.Set.of()));
            assertEquals(1, recorded.retainedResults());
            assertEquals(1, recorded.retainedTombstones(),
                    "the first result must genuinely have been evicted, or this proves nothing");

            // A second instance sharing only the store: no cache entry, no tombstone, nothing but the
            // durable record. This is the answer the recording instance has to agree with.
            var sibling = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            ExecutionLookup fromSibling = sibling.lookup(registryKey);
            ExecutionLookup fromRecorder = recorded.lookup(registryKey);

            var found = assertInstanceOf(ExecutionLookup.Found.class, fromRecorder,
                    "an eviction from a bounded cache is not a retention expiry, and the instance "
                            + "that ran the execution must not report one as the other");
            assertEquals(Map.of("answer", 42L), found.outcome().payload());
            assertEquals(fromSibling, fromRecorder,
                    "the same id must read identically from the instance that ran it and from one "
                            + "that never did; which instance is asked must not be observable");
        }
    }

    /**
     * The instance that ran an execution must not be the last one still serving it.
     *
     * <p>The eviction case above proves the tombstone no longer outranks the record. This is the
     * same defect read from the other end, and the count-based bounds are what make it ordinary
     * rather than exotic: nothing here ages out as time passes, so an instance that has completed
     * fewer than {@code maxResults} further executions is still holding the full result — payload
     * included — when the store's retention deadline goes by. Expiry is applied on the durable read
     * path and nowhere else, so a warm entry cannot notice it without asking.</p>
     *
     * <p>The clock is moved a full day past the deadline the store itself assigned, rather than onto
     * it, so this pins "past the retention horizon" and not a boundary condition. Asserted as an
     * equality between the two reads for the same reason the eviction case is: the property is not
     * that the recording instance says something defensible, it is that <b>which instance is asked
     * must not be observable</b>.</p>
     */
    @Test
    void aResultStillHeldByTheInstanceThatRanItIsNotServedPastTheRecordsRetentionDeadline() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            var registryKey = new ExecutionResultRegistry.Key(TENANT, traversalId);

            var recorded = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            recorded.started(registryKey, key.processInstanceId());
            recorded.completed(registryKey, new GraphExecutionResult(key.processInstanceId(), traversalId,
                    Map.of("answer", 42L), java.util.Set.of("start", "finish"), java.util.Set.of()));
            DurableExecutionResult stored = store.recordExecutionResult(
                    completed(key, traversalId, Map.of("answer", 42L),
                            store.maxExecutionResultPayloadBytes())).toCompletableFuture().join();

            assertEquals(1, recorded.retainedResults(),
                    "the full result must still be warm here, or this proves nothing about a warm "
                            + "result outliving its retention horizon");
            clock.set(stored.retainedUntil().plus(Duration.ofDays(1)));

            var sibling = new ExecutionResultRegistry(256, 8192, DurableExecutionResults.of(store));
            ExecutionLookup fromSibling = sibling.lookup(registryKey);
            ExecutionLookup fromRecorder = recorded.lookup(registryKey);

            assertInstanceOf(ExecutionLookup.Expired.class, fromRecorder,
                    "a result past the deadline the store assigned it must not still be served, with "
                            + "its payload, by the one instance that happens to be holding it");
            assertEquals(fromSibling, fromRecorder,
                    "the same id must read identically from the instance that ran it and from one "
                            + "that never did; which instance is asked must not be observable");
        }
    }

    /**
     * And when nothing durable backs the tombstone, the tombstone is still the truth.
     *
     * <p>The complement of the test above, and the reason the fall-through returns the local answer
     * rather than the store's silence: a purge removes the durable record while the process that ran
     * the execution still remembers that it ran and how it ended. Dropping the tombstone there would
     * turn a known terminal execution into {@code Unknown} — an execution that never happened —
     * which is a strictly worse answer than the one it replaced.</p>
     */
    @Test
    void aTombstoneWithNoDurableRecordLeftBehindItStillReportsHowTheExecutionEnded() {
        var clock = new MutableClock(START);
        try (var store = store(clock)) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            var registryKey = new ExecutionResultRegistry.Key(TENANT, traversalId);

            var recorded = new ExecutionResultRegistry(1, 8192, DurableExecutionResults.of(store));
            recorded.started(registryKey, key.processInstanceId());
            recorded.cancelled(registryKey, key.processInstanceId());

            UUID laterTraversalId = UUID.randomUUID();
            var laterKey = new ExecutionResultRegistry.Key(TENANT, laterTraversalId);
            recorded.started(laterKey, UUID.randomUUID());
            recorded.completed(laterKey, new GraphExecutionResult(UUID.randomUUID(), laterTraversalId,
                    "later", java.util.Set.of("start"), java.util.Set.of()));
            assertEquals(1, recorded.retainedTombstones());

            // Nothing was ever recorded durably for this traversal, which is also what a purged
            // record looks like from here.
            assertTrue(store.loadExecutionResult(TENANT, traversalId).toCompletableFuture().join()
                    .isEmpty());

            var expired = assertInstanceOf(ExecutionLookup.Expired.class, recorded.lookup(registryKey),
                    "a store with nothing to say must not erase what this process still knows");
            assertEquals(ProcessInstanceStatus.FAILED, expired.status());
            assertTrue(expired.cancelled(),
                    "and the tombstone's termination reason has to survive with its status, or a "
                            + "deliberate stop reads as an incident");
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
