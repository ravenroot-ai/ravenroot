package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionResultPayload;
import ai.ravenroot.api.persistence.ResultPayloadState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention contract behind {@code GET /v1/executions/{id}}.
 *
 * <p>Every test here pins a claim that {@link ExecutionLookup}'s Javadoc makes to a caller. The
 * interesting ones are the two horizons: a result that ages out must become {@code Expired} rather
 * than vanish, and only past the second, much wider bound may it become {@code Unknown}.</p>
 */
class ExecutionResultRegistryTest {

    private static final String TENANT = "tenant-a";

    private static void complete(ExecutionResultRegistry registry, ExecutionResultRegistry.Key key,
                                 GraphExecutionResult result) {
        registry.completed(key, result, ai.ravenroot.api.persistence.DurableExecutionResult.project(
                result.payload(), ai.ravenroot.api.payload.PayloadLimits.DEFAULTS.maxEncodedBytes()));
    }

    private static ExecutionResultRegistry.Key key(UUID id) {
        return new ExecutionResultRegistry.Key(TENANT, id);
    }

    private static GraphExecutionResult result(UUID processInstanceId, UUID traversalId, Object payload,
                                               Set<String> visited, Set<String> defaulted) {
        return new GraphExecutionResult(processInstanceId, traversalId, payload, visited, defaulted);
    }

    @Test
    @DisplayName("a completed execution reports its payload, visited nodes and defaulted nodes")
    void aCompletedExecutionReportsItsPayloadAndNodeSets() {
        var registry = new ExecutionResultRegistry();
        UUID instance = UUID.randomUUID();
        UUID traversal = UUID.randomUUID();
        registry.started(key(traversal), instance);
        complete(registry, key(traversal), result(instance, traversal, "the-payload",
                Set.of("start", "worker", "end"), Set.of("worker")));

        var found = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        assertEquals(ProcessInstanceStatus.COMPLETED, found.outcome().status());
        assertEquals("the-payload", found.outcome().payload());
        assertEquals(Set.of("start", "worker", "end"), found.outcome().visitedNodes());
        assertEquals(Set.of("worker"), found.outcome().defaultedNodes());
        assertEquals(instance, found.outcome().processInstanceId());
        assertEquals(traversal, found.outcome().executionId());
    }

    @Test
    void aCompletedExecutionRetainsOnlyCanonicalBytesAndDecodesAFreshForEveryRead() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        var mutable = new java.util.LinkedHashMap<String, Object>();
        mutable.put("answer", 42L);
        GraphExecutionResult result = result(UUID.randomUUID(), traversal, mutable, Set.of("end"), Set.of());
        ExecutionResultPayload admitted = DurableExecutionResult.project(result.payload(), 1024);

        registry.completed(key(traversal), result, admitted);
        mutable.put("answer", 99L);
        var first = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPayload = (Map<String, Object>) first.outcome().payload();
        firstPayload.put("answer", -1L);
        var second = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));

        assertEquals(Map.of("answer", 42L), second.outcome().payload(),
                "neither the engine object nor a prior reader may mutate the retained result");
    }

    @Test
    void aCanonicalRefusalIsTheLocalTerminalAnswerRatherThanAnEmptySuccess() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        GraphExecutionResult result = result(UUID.randomUUID(), traversal, "not retained", Set.of("end"), Set.of());

        registry.completed(key(traversal), result,
                ExecutionResultPayload.withheld(12, DurableExecutionResult.PAYLOAD_CONTENT_TYPE));

        var redacted = assertInstanceOf(ExecutionLookup.Redacted.class, registry.lookup(key(traversal)));
        assertEquals(ProcessInstanceStatus.COMPLETED, redacted.status());
        assertEquals(ResultPayloadState.WITHHELD, redacted.payloadState());
    }

    @Test
    void concurrentCompletionsCannotPairOneOutcomeWithAnotherPayload() throws Exception {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        var expected = new java.util.concurrent.ConcurrentHashMap<UUID, Long>();
        var ready = new CountDownLatch(32);
        var release = new CountDownLatch(1);
        var threads = new java.util.ArrayList<Thread>();
        for (long value = 0; value < 32; value++) {
            UUID process = UUID.randomUUID();
            expected.put(process, value);
            long candidate = value;
            threads.add(Thread.startVirtualThread(() -> {
                ready.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                GraphExecutionResult result = result(process, traversal, candidate, Set.of("end"), Set.of());
                registry.completed(key(traversal), result, DurableExecutionResult.project(candidate, 1024));
            }));
        }
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        release.countDown();
        for (Thread thread : threads) thread.join();

        var found = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        assertEquals(expected.get(found.outcome().processInstanceId()), found.outcome().payload(),
                "the status facts and admitted bytes must be installed in one synchronized operation");
    }

    /**
     * The reporting half of silent defaulting. A run that silently defaulted a node returns
     * {@code COMPLETED} — plain success — so {@code defaultedNodes} is the only thing that
     * distinguishes it from a clean run, and {@code degraded()} is what makes that difference
     * impossible to overlook.
     */
    @Test
    @DisplayName("a defaulted node makes an otherwise successful run report as degraded")
    void aDefaultedNodeMakesASuccessfulRunReportDegraded() {
        var registry = new ExecutionResultRegistry();
        UUID clean = UUID.randomUUID();
        UUID degraded = UUID.randomUUID();
        complete(registry, key(clean), result(UUID.randomUUID(), clean, "p", Set.of("start", "end"), Set.of()));
        complete(registry, key(degraded), result(UUID.randomUUID(), degraded, "p",
                Set.of("start", "future", "end"), Set.of("future")));

        var cleanFound = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(clean)));
        var degradedFound = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(degraded)));

        assertEquals(cleanFound.outcome().status(), degradedFound.outcome().status(),
                "the statuses must be identical -- that is exactly why defaultedNodes has to carry the "
                        + "difference; if these ever diverge this test is asserting the wrong thing");
        assertTrue(degradedFound.outcome().degraded());
        assertEquals(Set.of("future"), degradedFound.outcome().defaultedNodes());
        assertTrue(cleanFound.outcome().defaultedNodes().isEmpty());
        assertTrue(!cleanFound.outcome().degraded());
    }

    /**
     * The boundary obligation this projection previously failed. The engine records a
     * handled failure on {@link GraphExecutionResult}, but that record never leaves the JVM: {@code
     * ExecutionOutcome} is what an adapter, the HTTP read and every out-of-process caller actually
     * hold. A component dropped in {@link ExecutionResultRegistry#completed} therefore makes a run
     * that suffered a real fault byte-identical to a clean one for every caller outside the process —
     * which is the same defect {@code defaultedNodes} previously had, one level up.
     *
     * <p>Written as two runs projected through the same call, differing only in the failure, so it
     * fails on a projection that drops the set and on one that fabricates it. The status assertion is
     * load-bearing: if these two ever stop being identical, the annotation is no longer what carries
     * the difference and this test is asserting the wrong thing.</p>
     */
    @Test
    @DisplayName("the projection into ExecutionOutcome does not lose the handled-failure annotation")
    void aHandledFailureSurvivesTheProjectionIntoTheOutcome() {
        var registry = new ExecutionResultRegistry();
        UUID clean = UUID.randomUUID();
        UUID handled = UUID.randomUUID();
        complete(registry, key(clean), new GraphExecutionResult(UUID.randomUUID(), clean, "p",
                Set.of("start", "boom", "end"), Set.of(), Set.of(), Set.of()));
        complete(registry, key(handled), new GraphExecutionResult(UUID.randomUUID(), handled, "p",
                Set.of("start", "boom", "handler", "end"), Set.of(), Set.of(), Set.of("boom")));

        var cleanFound = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(clean)));
        var handledFound = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(handled)));

        assertEquals(cleanFound.outcome().status(), handledFound.outcome().status(),
                "both runs completed -- that is exactly why handledFailureNodes has to carry the "
                        + "difference; if these ever diverge this test is asserting the wrong thing");
        assertEquals(Set.of("boom"), handledFound.outcome().handledFailureNodes(),
                "the projection must name the node that failed, not merely that something did");
        assertTrue(handledFound.outcome().handledFailure());
        assertTrue(cleanFound.outcome().handledFailureNodes().isEmpty());
        assertTrue(!cleanFound.outcome().handledFailure(),
                "a run in which nothing failed must not claim a handled failure");
    }

    @Test
    void canonicalCompletionKeepsDefaultBypassAndHandledFailureFactsTogether() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        GraphExecutionResult result = new GraphExecutionResult(UUID.randomUUID(), traversal, "bounded",
                Set.of("start", "defaulted", "bypassed", "handled", "end"), Set.of("defaulted"),
                Set.of("bypassed"), Set.of("handled"), Set.of("bypassed->alternate [outcome=alternate]"));

        registry.completed(key(traversal), result, DurableExecutionResult.project(result.payload(), 1024));

        var found = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        assertEquals(Set.of("defaulted"), found.outcome().defaultedNodes());
        assertEquals(Set.of("bypassed"), found.outcome().bypassedNodes());
        assertEquals(Set.of("handled"), found.outcome().handledFailureNodes());
        assertEquals(Set.of("bypassed->alternate [outcome=alternate]"), found.outcome().untakenEdges());
        assertEquals("bounded", found.outcome().payload());
    }

    /**
     * A failed traversal produces no {@link GraphExecutionResult} at all, so there is nothing to
     * annotate and nothing to project: the failure is the status. Pinned because the opposite reading
     * — that {@code handledFailureNodes} is where any failure shows up — is the natural one, and it
     * would make {@code ExecutionOutcome.handledFailure()} mean two different things.
     */
    @Test
    @DisplayName("a terminal failure is reported as FAILED, never as a handled failure")
    void aTerminalFailureIsNotAHandledFailure() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        registry.failed(key(traversal), UUID.randomUUID());

        var found = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        assertEquals(ProcessInstanceStatus.FAILED, found.outcome().status());
        assertTrue(!found.outcome().handledFailure());
    }

    @Test
    @DisplayName("an in-flight execution is readable as RUNNING, not as unknown")
    void anInFlightExecutionIsReadableAsRunning() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        registry.started(key(traversal), UUID.randomUUID());

        var found = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        assertEquals(ProcessInstanceStatus.RUNNING, found.outcome().status());
        assertEquals(null, found.outcome().payload());
    }

    @Test
    @DisplayName("an id this process never saw is Unknown")
    void anIdNeverSeenIsUnknown() {
        assertInstanceOf(ExecutionLookup.Unknown.class,
                new ExecutionResultRegistry().lookup(key(UUID.randomUUID())));
    }

    /**
     * The horizon that matters. Past the result bound the execution must still be provably known to
     * have run: the whole reason {@code Expired} exists is that a caller reading a moment too late
     * must not be told its run never happened.
     */
    @Test
    @DisplayName("a result evicted past the result bound becomes Expired, keeping its terminal status")
    void anEvictedResultBecomesExpiredRatherThanUnknown() {
        var registry = new ExecutionResultRegistry(2, 64);
        UUID first = UUID.randomUUID();
        complete(registry, key(first), result(UUID.randomUUID(), first, "gone-later", Set.of("end"), Set.of()));
        for (int i = 0; i < 2; i++) {
            UUID later = UUID.randomUUID();
            complete(registry, key(later), result(UUID.randomUUID(), later, "p", Set.of("end"), Set.of()));
        }

        var expired = assertInstanceOf(ExecutionLookup.Expired.class, registry.lookup(key(first)),
                "past the result bound the execution must still be known to have run");
        assertEquals(ProcessInstanceStatus.COMPLETED, expired.status());
        assertEquals(first, expired.executionId());
        assertEquals(2, registry.retainedResults());
    }

    @Test
    @DisplayName("only past the far wider tombstone bound does an execution become Unknown")
    void onlyPastTheTombstoneBoundDoesAnExecutionBecomeUnknown() {
        var registry = new ExecutionResultRegistry(1, 2);
        UUID oldest = UUID.randomUUID();
        complete(registry, key(oldest), result(UUID.randomUUID(), oldest, "p", Set.of("end"), Set.of()));
        for (int i = 0; i < 3; i++) {
            UUID later = UUID.randomUUID();
            complete(registry, key(later), result(UUID.randomUUID(), later, "p", Set.of("end"), Set.of()));
        }

        assertInstanceOf(ExecutionLookup.Unknown.class, registry.lookup(key(oldest)));
        assertEquals(2, registry.retainedTombstones(), "the tombstone bound must be enforced too");
    }

    /**
     * Tenant scoping is the key, not a filter. This asserts the mechanism the HTTP layer relies on:
     * the same id under a different tenant is not a denial and not a redacted hit, it is a miss.
     */
    @Test
    @DisplayName("the same execution id under another tenant is Unknown, never Found")
    void theSameIdUnderAnotherTenantIsUnknown() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        complete(registry, new ExecutionResultRegistry.Key("tenant-a", traversal),
                result(UUID.randomUUID(), traversal, "tenant-a-secret", Set.of("end"), Set.of()));

        assertInstanceOf(ExecutionLookup.Unknown.class,
                registry.lookup(new ExecutionResultRegistry.Key("tenant-b", traversal)));
        assertInstanceOf(ExecutionLookup.Found.class,
                registry.lookup(new ExecutionResultRegistry.Key("tenant-a", traversal)));
    }

    @Test
    @DisplayName("a failed execution is retained as FAILED rather than erased")
    void aFailedExecutionIsRetainedAsFailed() {
        var registry = new ExecutionResultRegistry();
        UUID traversal = UUID.randomUUID();
        registry.started(key(traversal), UUID.randomUUID());
        registry.failed(key(traversal), UUID.randomUUID());

        var found = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(traversal)));
        assertEquals(ProcessInstanceStatus.FAILED, found.outcome().status());
    }

    /**
     * The distinction the status cannot carry, on a live result.
     *
     * <p>Both entries report {@code FAILED} and that is deliberate: the durable status vocabulary was
     * not widened, because a new status name would be unreadable to every reader that predates it.
     * So the assertion pair here is the contract — a caller branching on {@code status()} alone sees
     * two identical answers, and only {@code cancelled()} separates a run somebody stopped from a run
     * that broke.</p>
     */
    @Test
    @DisplayName("a cancelled execution is FAILED like a failure, and only its reason separates them")
    void aCancelledExecutionIsDistinguishableFromAFailureWhileTheResultIsRetained() {
        var registry = new ExecutionResultRegistry();
        UUID cancelled = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        registry.started(key(cancelled), UUID.randomUUID());
        registry.started(key(failed), UUID.randomUUID());
        registry.cancelled(key(cancelled), UUID.randomUUID());
        registry.failed(key(failed), UUID.randomUUID());

        var stopped = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(cancelled)));
        var broke = assertInstanceOf(ExecutionLookup.Found.class, registry.lookup(key(failed)));
        assertEquals(ProcessInstanceStatus.FAILED, stopped.outcome().status());
        assertEquals(ProcessInstanceStatus.FAILED, broke.outcome().status(),
                "the two statuses are identical on purpose; the status is not the answer here");
        assertTrue(stopped.outcome().cancelled());
        assertEquals(ExecutionTerminationReason.CANCELLED, stopped.outcome().terminationReason());
        assertFalse(broke.outcome().cancelled());
        assertNull(broke.outcome().terminationReason());
    }

    /**
     * And it survives eviction, which is the read where a wrong answer cannot be checked.
     *
     * <p>A tombstone that kept only the status would report a deliberate stop as an incident, with
     * the confidence of a record and nothing left to contradict it. That is strictly worse than the
     * {@code Unknown} the tombstone was introduced to replace: a caller can act on a wrong answer.
     * The cost of not lying is one enum reference per tombstone.</p>
     */
    @Test
    @DisplayName("an evicted cancelled result still reports cancellation, not a bare failure")
    void anEvictedCancelledResultStillReportsCancellation() {
        var registry = new ExecutionResultRegistry(1, 64);
        UUID cancelled = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        registry.cancelled(key(cancelled), UUID.randomUUID());
        registry.failed(key(failed), UUID.randomUUID());
        // The second terminal result pushes the first past the result bound and into a tombstone.
        complete(registry, key(UUID.randomUUID()),
                result(UUID.randomUUID(), UUID.randomUUID(), "p", Set.of("start"), Set.of()));

        var stopped = assertInstanceOf(ExecutionLookup.Expired.class, registry.lookup(key(cancelled)),
                "the full result is gone; the tombstone is what still answers");
        assertEquals(ProcessInstanceStatus.FAILED, stopped.status());
        assertTrue(stopped.cancelled(),
                "past the retention horizon there is nothing left to check this against, so it has "
                        + "to be right the first time");
        assertEquals(ExecutionTerminationReason.CANCELLED, stopped.terminationReason());

        var broke = assertInstanceOf(ExecutionLookup.Expired.class, registry.lookup(key(failed)));
        assertEquals(ProcessInstanceStatus.FAILED, broke.status());
        assertFalse(broke.cancelled());
        assertNull(broke.terminationReason());
    }

    @Test
    @DisplayName("a lookup cannot be expressed without a tenant")
    void aLookupCannotBeExpressedWithoutATenant() {
        assertThrows(NullPointerException.class,
                () -> new ExecutionResultRegistry.Key(null, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionResultRegistry.Key("  ", UUID.randomUUID()));
    }
}
