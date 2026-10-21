package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Same executed cross-traversal persistence protocol for every shipped adapter. */
@Timeout(120)
public abstract class CorrelatedJoinContract {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final BusinessScope SCOPE = new BusinessScope("tenant", "deployment", "version");
    private static final SecurityContext SECURITY = new SecurityContext("request", "tenant", "actor", PrincipalType.WORKLOAD, "test");
    protected abstract ExecutionStore createStore(String id, Clock clock);
    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    @Test void sixPartitionsFromOneSourceReleaseOnceAndRetainOriginalCausality() throws Exception {
        try (var store = createStore(id(), clock())) {
            var events = contributions(store, "operation", "0", 6);
            var key = new CorrelatedJoinKey(SCOPE, "operation", "combine", "0");
            var record = CorrelatedJoinRecord.open(key, Set.of(), 6, true, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(record));
            for (int i = 0; i < 5; i++) {
                record = record.contribute(events.get(i), Set.of(), null, false, NOW, store.businessHistoryPolicy());
                await(store.compareAndSetCorrelatedJoin(record));
            }
            assertEquals(CorrelatedJoinRecord.Phase.OPEN, record.phase());
            assertEquals(record, record.contribute(events.getFirst(), Set.of(), null, false, NOW, store.businessHistoryPolicy()));
            record = record.contribute(events.getLast(), Set.of(), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            assertEquals(CorrelatedJoinRecord.Phase.READY, record.phase());
            assertEquals(6, record.contributions().size());
            assertEquals(6, record.contributions().values().stream().map(JoinContribution::traversalId).distinct().count());
            assertTrue(record.contributions().values().stream().noneMatch(c -> c.traversalId().equals(key.continuationTraversalId())));
            var page = await(store.correlatedJoins(SCOPE, "operation", Set.of(), Optional.empty(), 10));
            assertEquals(1, page.items().size());
            assertEquals(6, page.items().getFirst().receivedContributions());
            assertEquals(CorrelatedJoinRecord.Phase.READY, page.items().getFirst().phase());
        }
    }

    @Test void concurrentDuplicateDeliveriesCannotCreateMultipleReleaseTransitions() throws Exception {
        try (var store = createStore(id(), clock()); var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var events = contributions(store, "concurrent", "0", 6);
            var key = new CorrelatedJoinKey(SCOPE, "concurrent", "combine", "0");
            await(store.compareAndSetCorrelatedJoin(CorrelatedJoinRecord.open(key, Set.of(), 6, true,
                    JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60))));
            var releases = new java.util.concurrent.atomic.AtomicInteger();
            var jobs = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 24; i++) {
                var event = events.get(i % 6);
                jobs.add(CompletableFuture.runAsync(() -> {
                    for (int retry = 0; retry < 100; retry++) {
                        var prior = awaitUnchecked(store.correlatedJoin(key)).orElseThrow();
                        var next = prior.contribute(event, Set.of(), null, false, NOW, store.businessHistoryPolicy());
                        if (next == prior) return;
                        try {
                            awaitUnchecked(store.compareAndSetCorrelatedJoin(next));
                            if (next.phase() == CorrelatedJoinRecord.Phase.READY) releases.incrementAndGet();
                            return;
                        } catch (CompletionException conflict) {
                            if (!(conflict.getCause() instanceof BusinessHistoryException e)
                                    || e.reason() != BusinessHistoryException.Reason.REVISION_CONFLICT) throw conflict;
                        }
                    }
                    fail("bounded concurrent CAS failed to converge");
                }, clients));
            }
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            assertEquals(1, releases.get());
            assertEquals(6, await(store.correlatedJoin(key)).orElseThrow().contributions().size());
        }
    }

    @Test void fixedManifestRejectsUnknownItemsAndPayloadReuseConflicts() throws Exception {
        try (var store = createStore(id(), clock())) {
            var events = contributions(store, "manifest", "0", 3);
            var key = new CorrelatedJoinKey(SCOPE, "manifest", "combine", "0");
            var record = CorrelatedJoinRecord.open(key, Set.of("part-1", "part-2"), null, true,
                    JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(record));
            record = record.contribute(events.getFirst(), Set.of(), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            var current = record;
            assertThrows(BusinessHistoryException.class, () -> current.contribute(events.getLast(), Set.of(), null, false, NOW, store.businessHistoryPolicy()));
            var first = events.getFirst(); var changed = OpaquePayload.of(new byte[]{9}, "application/octet-stream");
            var conflict = new JoinContribution(first.contributionId(), first.kind(), first.processInstanceId(),
                    first.traversalId(), first.invocationId(), first.attemptId(), first.security(), changed,
                    JoinContribution.digest(changed.bytes()), NOW);
            assertThrows(BusinessHistoryException.class, () -> current.contribute(conflict, Set.of(), null, false, NOW, store.businessHistoryPolicy()));
            assertEquals(1, await(store.correlatedJoin(key)).orElseThrow().contributions().size());
        }
    }

    @Test void dynamicManifestNeedsExplicitSealAndZeroIsAnExplicitClosure() throws Exception {
        try (var store = createStore(id(), clock())) {
            var events = contributions(store, "dynamic", "0", 2);
            var key = new CorrelatedJoinKey(SCOPE, "dynamic", "combine", "0");
            var record = CorrelatedJoinRecord.open(key, Set.of(), null, false, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
            record = record.contribute(events.getFirst(), Set.of("part-1", "part-2"), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(new CorrelatedJoinRecord(record.key(), 1, record.phase(), record.expectedIds(),
                    record.expectedCount(), record.sealed(), record.contributions(), record.openedBy(), record.openedAt(), record.deadlineAt(), record.updatedAt())));
            record = await(store.correlatedJoin(key)).orElseThrow();
            record = record.contribute(events.getLast(), Set.of(), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            assertEquals(CorrelatedJoinRecord.Phase.OPEN, record.phase(), "all known items cannot imply production closure");
            record = record.contribute(null, Set.of(), null, true, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            assertEquals(CorrelatedJoinRecord.Phase.READY, record.phase());
            var emptyKey = new CorrelatedJoinKey(SCOPE, "dynamic", "empty", "0");
            var empty = CorrelatedJoinRecord.open(emptyKey, Set.of(), 0, false, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(empty));
            assertEquals(CorrelatedJoinRecord.Phase.OPEN, empty.phase());
            await(store.compareAndSetCorrelatedJoin(empty.contribute(null, Set.of(), null, true, NOW, store.businessHistoryPolicy())));
            assertEquals(CorrelatedJoinRecord.Phase.READY, await(store.correlatedJoin(emptyKey)).orElseThrow().phase());
        }
    }

    @Test void cancelledGenerationCannotReopenAndANewRoundHasIndependentContributions() throws Exception {
        try (var store = createStore(id(), clock())) {
            var events = contributions(store, "rounds", "first", 1);
            var key = new CorrelatedJoinKey(SCOPE, "rounds", "combine", "first");
            var record = CorrelatedJoinRecord.open(key, Set.of(), 2, true, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(record));
            record = record.terminate(CorrelatedJoinRecord.Phase.CANCELLED, NOW);
            await(store.compareAndSetCorrelatedJoin(record));
            var cancelled = record;
            assertThrows(BusinessHistoryException.class, () -> cancelled.contribute(events.getFirst(), Set.of(), null, false, NOW, store.businessHistoryPolicy()));
            var next = contributions(store, "rounds", "second", 1).getFirst();
            var second = CorrelatedJoinRecord.open(new CorrelatedJoinKey(SCOPE, "rounds", "combine", "second"), Set.of(), 1,
                    true, JoinCause.of(next), NOW, NOW.plusSeconds(60)).contribute(next, Set.of(), null, false, NOW, store.businessHistoryPolicy());
            var firstWrite = new CorrelatedJoinRecord(second.key(), 1, second.phase(), second.expectedIds(), second.expectedCount(),
                    second.sealed(), second.contributions(), second.openedBy(), second.openedAt(), second.deadlineAt(), second.updatedAt());
            await(store.compareAndSetCorrelatedJoin(firstWrite));
            assertNotEquals(key.continuationTraversalId(), second.key().continuationTraversalId());
        }
    }

    @Test void readyOutboxAndItsOriginalCausalDataSurviveReopen() throws Exception {
        String id = id(); CorrelatedJoinRecord saved;
        try (var store = createStore(id, clock())) {
            assumeTrue(store.supports(StoreCapability.DURABLE));
            var event = contributions(store, "recover", "0", 1).getFirst();
            var key = new CorrelatedJoinKey(SCOPE, "recover", "combine", "0");
            var ready = CorrelatedJoinRecord.open(key, Set.of(), 1, true, JoinCause.of(event), NOW, NOW.plusSeconds(60))
                    .contribute(event, Set.of(), null, false, NOW, store.businessHistoryPolicy());
            saved = new CorrelatedJoinRecord(ready.key(), 1, ready.phase(), ready.expectedIds(), ready.expectedCount(), ready.sealed(),
                    ready.contributions(), ready.openedBy(), ready.openedAt(), ready.deadlineAt(), ready.updatedAt());
            await(store.compareAndSetCorrelatedJoin(saved));
        }
        try (var store = createStore(id, clock())) {
            assertEquals(saved, await(store.correlatedJoin(saved.key())).orElseThrow());
            var page = await(store.correlatedJoins(SCOPE, null, Set.of(CorrelatedJoinRecord.Phase.READY), Optional.empty(), 1));
            assertEquals(saved.key().continuationProcessId(), page.items().getFirst().key().continuationProcessId());
            assertEquals(saved.contributions().get("part-1").traversalId(),
                    await(store.correlatedJoin(saved.key())).orElseThrow().contributions().get("part-1").traversalId());
        }
    }
    private static List<JoinContribution> contributions(ExecutionStore store, String operation, String round, int count) throws Exception {
        var result = new ArrayList<JoinContribution>();
        for (int i = 1; i <= count; i++) {
            UUID process = UUID.randomUUID(), traversal = UUID.randomUUID(); String id = "part-" + i;
            var context = new ApplicationCorrelationContext(operation, Optional.of(id), Optional.of("partition"), round, Optional.empty());
            await(store.recordCorrelatedTraversal(new CorrelatedTraversal(SCOPE, context, process, traversal, "one-source",
                    CorrelatedTraversal.Cause.SOURCE_EVENT, TraversalStatus.RUNNING, null, NOW, NOW, null), CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            var payload = OpaquePayload.of(new byte[]{(byte) i}, "application/octet-stream");
            result.add(new JoinContribution(id, "partition", process, traversal, UUID.randomUUID(), UUID.randomUUID(), SECURITY,
                    payload, JoinContribution.digest(payload.bytes()), NOW));
        }
        return result;
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(60, TimeUnit.SECONDS); }
    private static <T> T awaitUnchecked(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
