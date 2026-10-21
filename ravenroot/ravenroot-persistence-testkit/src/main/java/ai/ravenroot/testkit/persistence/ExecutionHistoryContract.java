package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.persistence.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executed metadata pagination, original traversal/visit/attempt identity and retention contract. */
@Timeout(120)
public abstract class ExecutionHistoryContract {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    protected abstract ExecutionStore createStore(String id, Clock clock);

    @Test void processWithTwoTraversalsExposesActualIdentitiesWithoutInventingBusinessContext() throws Exception {
        try (var store = createStore(id(), clock())) {
            var seeded = seed(store, 2, 3);
            var first = await(store.executionTraversals(seeded.key, Optional.empty(), 1));
            assertEquals(1, first.items().size()); assertTrue(first.nextCursor().isPresent());
            var second = await(store.executionTraversals(seeded.key, first.nextCursor(), 1));
            assertEquals(1, second.items().size()); assertTrue(second.nextCursor().isEmpty());
            assertNotEquals(first.items().getFirst().traversalId(), second.items().getFirst().traversalId());
            assertEquals(seeded.key.processInstanceId(), first.items().getFirst().processInstanceId());
            assertEquals(3, first.items().getFirst().visitCount());
            assertNull(first.items().getFirst().applicationCorrelation());
            assertNull(first.items().getFirst().cause());
            assertEquals(first.items().getFirst(), await(store.executionTraversal(seeded.key, first.items().getFirst().traversalId())).orElseThrow());
            assertEquals(HistoryPage.Availability.NOT_FOUND, await(store.executionTraversals(
                    new ExecutionKey("foreign", seeded.key.processInstanceId()), Optional.empty(), 1)).availability());
        }
    }

    @Test void thousandLoopVisitsAreLimitedBeforeProjectionAndCursorsCannotCrossQueries() throws Exception {
        try (var store = createStore(id(), clock())) {
            var seeded = seed(store, 1, 1000); UUID traversal = seeded.traversals.getFirst();
            var ids = new HashSet<UUID>(); Optional<String> cursor = Optional.empty(); int pages = 0;
            do {
                var page = await(store.executionVisits(seeded.key, traversal, Optional.of("loop"), cursor, 37));
                assertTrue(page.items().size() <= 37);
                for (var item : page.items()) {
                    assertTrue(ids.add(item.invocationId())); assertEquals(traversal, item.traversalId());
                    assertEquals(2, item.attemptCount()); assertEquals("loop", item.nodeId());
                    assertNull(item.startedAt()); assertNull(item.settledAt()); assertNull(item.outcome());
                    assertFalse(item.eventHistoryAvailable(), "seeded metadata cannot fabricate journal timing/output");
                }
                if (pages == 0) {
                    assertTrue(page.nextCursor().isPresent());
                    assertThrows(ExecutionException.class, () -> await(store.executionVisits(seeded.key, traversal,
                            Optional.of("other-node"), page.nextCursor(), 37)));
                    assertThrows(ExecutionException.class, () -> await(store.executionVisits(seeded.key, traversal,
                            Optional.of("loop"), page.nextCursor(), 38)));
                    assertThrows(ExecutionException.class, () -> await(store.executionVisits(
                            new ExecutionKey("foreign", seeded.key.processInstanceId()), traversal,
                            Optional.of("loop"), page.nextCursor(), 37)));
                }
                cursor = page.nextCursor(); pages++;
            } while (cursor.isPresent());
            assertEquals(1000, ids.size()); assertEquals(28, pages);
            var bindings = await(store.executionInvocationNodeIds(seeded.key, ids));
            assertEquals(1000, bindings.size()); assertTrue(bindings.values().stream().allMatch("loop"::equals));
            assertTrue(await(store.executionInvocationNodeIds(new ExecutionKey("foreign", seeded.key.processInstanceId()), ids)).isEmpty());
            assertTrue(await(store.executionInvocationNodeIds(seeded.key, Set.of(UUID.randomUUID()))).isEmpty());
            var oversized = new HashSet<>(ids); oversized.add(UUID.randomUUID());
            assertThrows(IllegalArgumentException.class, () -> store.executionInvocationNodeIds(seeded.key, oversized));
            assertThrows(ExecutionException.class, () -> await(store.executionVisits(seeded.key, traversal,
                    Optional.empty(), Optional.empty(), store.businessHistoryPolicy().maxPageSize() + 1)));
        }
    }

    @Test void attemptAndParentPagesKeepRetriesAndReentryCausalitySeparate() throws Exception {
        try (var store = createStore(id(), clock())) {
            var seeded = seed(store, 2, 4); UUID traversal = seeded.traversals.get(1);
            var visits = await(store.executionVisits(seeded.key, traversal, Optional.empty(), Optional.empty(), 10));
            var first = visits.items().getFirst(); assertEquals(1, first.parentCount());
            var parents = await(store.executionVisitParents(seeded.key, traversal, first.invocationId(), Optional.empty(), 1));
            assertEquals(1, parents.items().size());
            assertEquals(seeded.traversals.getFirst(), parents.items().getFirst().traversalId());
            assertEquals(seeded.key.processInstanceId(), parents.items().getFirst().processInstanceId());
            var attempt1 = await(store.executionVisitAttempts(seeded.key, traversal, first.invocationId(), Optional.empty(), 1));
            var attempt2 = await(store.executionVisitAttempts(seeded.key, traversal, first.invocationId(), attempt1.nextCursor(), 1));
            assertEquals(NodeAttemptStatus.FAILED, attempt1.items().getFirst().status());
            assertEquals(1, attempt1.items().getFirst().ordinal());
            assertEquals(NodeAttemptCompletion.SUCCEEDED, attempt2.items().getFirst().completion());
            assertEquals(2, attempt2.items().getFirst().ordinal());
            assertTrue(attempt2.nextCursor().isEmpty());
            assertThrows(ExecutionException.class, () -> await(store.executionVisitAttempts(seeded.key, traversal,
                    visits.items().get(1).invocationId(), attempt1.nextCursor(), 1)));
            assertEquals(HistoryPage.Availability.NOT_FOUND, await(store.executionVisitParents(seeded.key,
                    seeded.traversals.getFirst(), first.invocationId(), Optional.empty(), 1)).availability());
        }
    }

    @Test void journalTimingAndSafeOutcomeDisappearHonestlyAfterCompaction() throws Exception {
        var current = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        try (var store = createStore(id(), clock)) {
            var key = new ExecutionKey("tenant", UUID.randomUUID()); UUID traversal = UUID.randomUUID();
            UUID invocation = UUID.randomUUID(), attempt = UUID.randomUUID();
            var visit = new NodeInvocation(invocation, "loop", Set.of(), NodeInvocationStatus.COMPLETED,
                    List.of(new NodeAttempt(attempt, 1, NodeAttemptStatus.COMPLETED, NodeAttemptCompletion.SUCCEEDED, null)));
            var state = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.COMPLETED, Map.of(traversal,
                    new Traversal(traversal, "loop", TraversalStatus.COMPLETED, Map.of(invocation, visit))));
            UUID accepted = UUID.randomUUID(), started = UUID.randomUUID(), completed = UUID.randomUUID();
            var batch = ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(state, new GraphVersionPin("version")))
                    .publish(EventEnvelope.of(accepted, key.tenantId(), "EXECUTION_STARTED", key.processInstanceId(), traversal,
                            null, null, null, "request", "version", NOW, OpaquePayload.empty("application/json")))
                    .publish(EventEnvelope.of(started, key.tenantId(), "NODE_STARTED", key.processInstanceId(), traversal,
                            invocation, attempt, accepted, "request", "version", NOW.plusSeconds(1), OpaquePayload.empty("application/json")))
                    .publish(EventEnvelope.of(completed, key.tenantId(), "NODE_COMPLETED", key.processInstanceId(), traversal,
                            invocation, attempt, started, "request", "version", NOW.plusSeconds(2), NodeOutcomeEventData.of("route.approved")));
            await(store.apply(batch.build()));
            var observed = await(store.executionVisits(key, traversal, Optional.empty(), Optional.empty(), 1)).items().getFirst();
            assertEquals(NOW.plusSeconds(1), observed.startedAt()); assertEquals(NOW.plusSeconds(2), observed.settledAt());
            assertEquals("route.approved", observed.outcome()); assertTrue(observed.eventHistoryAvailable());
            var records = await(store.readJournal(key.tenantId(), 0, 10));
            await(store.advanceOutboxCursor(await(store.outboxCursor(key.tenantId(), "history-reader")),
                    records.getLast().journalOffset()));
            current.set(NOW.plus(store.journalRetention()).plusSeconds(10));
            assertEquals(3, await(store.compactJournal(key.tenantId())));
            var retained = await(store.executionVisits(key, traversal, Optional.empty(), Optional.empty(), 1)).items().getFirst();
            assertEquals(NodeInvocationStatus.COMPLETED, retained.status());
            assertNull(retained.startedAt()); assertNull(retained.settledAt()); assertNull(retained.outcome());
            assertFalse(retained.eventHistoryAvailable());
        }
    }

    @Test void metadataPageAndCursorSurviveDurableReopen() throws Exception {
        String id = id(); Seeded seed; HistoryPage<NodeVisitSummary> before;
        try (var store = createStore(id, clock())) {
            assumeTrue(store.supports(StoreCapability.DURABLE)); seed = seed(store, 1, 12);
            before = await(store.executionVisits(seed.key, seed.traversals.getFirst(), Optional.empty(), Optional.empty(), 5));
        }
        try (var store = createStore(id, clock())) {
            var after = await(store.executionVisits(seed.key, seed.traversals.getFirst(), Optional.empty(), before.nextCursor(), 5));
            assertEquals(5, after.items().size());
            assertTrue(after.items().stream().noneMatch(item -> before.items().stream().anyMatch(old -> old.invocationId().equals(item.invocationId()))));
        }
    }
    @Test void legacySelectorUsesExactDeploymentVersionAndDoesNotInventApplicationContext() throws Exception {
        try (var store = createStore(id(), clock())) {
            var scope = new ai.ravenroot.api.correlation.BusinessScope("tenant", "document", "version");
            var old = seed(store, 3, 1, ExecutionOrigin.of("document", "prior-incarnation", null, "transport"), "version");
            seed(store, 1, 1, ExecutionOrigin.of("other-document", null, null), "version");
            seed(store, 1, 1, ExecutionOrigin.of("document", null, null), "other-version");
            var context = new ai.ravenroot.api.correlation.ApplicationCorrelationContext("explicit-business", Optional.empty(), Optional.empty(), "round", Optional.empty());
            await(store.recordCorrelatedTraversal(new ai.ravenroot.api.correlation.CorrelatedTraversal(scope, context,
                    old.key.processInstanceId(), old.traversals.getFirst(), "loop", ai.ravenroot.api.correlation.CorrelatedTraversal.Cause.SOURCE_EVENT,
                    TraversalStatus.COMPLETED, null, NOW, NOW, NOW), ai.ravenroot.api.correlation.CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            var first = await(store.uncorrelatedDeploymentTraversals(scope, Optional.empty(), 1));
            assertEquals(1, first.items().size()); assertNull(first.items().getFirst().applicationCorrelation());
            assertTrue(first.nextCursor().isPresent());
            var second = await(store.uncorrelatedDeploymentTraversals(scope, first.nextCursor(), 1));
            assertEquals(1, second.items().size()); assertTrue(second.nextCursor().isEmpty());
            assertEquals(Set.copyOf(old.traversals.subList(1, 3)), Set.of(first.items().getFirst().traversalId(), second.items().getFirst().traversalId()));
            assertThrows(ExecutionException.class, () -> await(store.uncorrelatedDeploymentTraversals(
                    new ai.ravenroot.api.correlation.BusinessScope("tenant", "other-document", "version"), first.nextCursor(), 1)));
        }
    }
    private record Seeded(ExecutionKey key, List<UUID> traversals) { }
    private static Seeded seed(ExecutionStore store, int count, int visits) throws Exception {
        return seed(store, count, visits, ExecutionOrigin.none(), "version");
    }
    private static Seeded seed(ExecutionStore store, int count, int visits, ExecutionOrigin origin, String version) throws Exception {
        var key = new ExecutionKey("tenant", UUID.randomUUID()); var traversals = new LinkedHashMap<UUID, Traversal>();
        UUID previous = null;
        for (int t = 0; t < count; t++) {
            UUID traversal = UUID.randomUUID(); var invocations = new LinkedHashMap<UUID, NodeInvocation>();
            for (int v = 0; v < visits; v++) {
                UUID invocation = UUID.randomUUID();
                var attempts = List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.FAILED, null, null),
                        new NodeAttempt(UUID.randomUUID(), 2, NodeAttemptStatus.COMPLETED, NodeAttemptCompletion.SUCCEEDED, null));
                invocations.put(invocation, new NodeInvocation(invocation, "loop", previous == null ? Set.of() : Set.of(previous),
                        NodeInvocationStatus.COMPLETED, attempts, NodeCommand.PROCESS));
                previous = invocation;
            }
            traversals.put(traversal, new Traversal(traversal, "loop", TraversalStatus.COMPLETED, invocations));
        }
        await(store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .recordOrigin(origin).apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.COMPLETED,
                        traversals), new GraphVersionPin(version))).build()));
        return new Seeded(key, List.copyOf(traversals.keySet()));
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(60, TimeUnit.SECONDS); }
}
