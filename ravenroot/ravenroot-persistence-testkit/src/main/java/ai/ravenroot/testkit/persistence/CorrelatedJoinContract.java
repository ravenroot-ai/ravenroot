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
    protected ExecutionStore createStore(String id, Clock clock) {
        return createStore(id, clock, BusinessHistoryPolicy.DEFAULTS);
    }
    protected abstract ExecutionStore createStore(String id, Clock clock, BusinessHistoryPolicy policy);
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

    @Test void memberMetadataSeparatesUnannouncedManifestMissingItemsAndOriginalArrivalCoordinates() throws Exception {
        try (var store = createStore(id(), clock())) {
            var events = contributions(store, "members", "round", 2);
            var key = new CorrelatedJoinKey(SCOPE, "members", "combine", "round");
            var record = CorrelatedJoinRecord.open(key, Set.of(), null, false, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(record));
            assertEquals(CorrelatedJoinSummary.Expectation.UNANNOUNCED,
                    await(store.correlatedJoins(SCOPE, "members", Set.of(), Optional.empty(), 1)).items().getFirst().expectation());
            assertTrue(await(store.correlatedJoinMembers(key, JoinMemberFilter.ALL, Optional.empty(), 1)).items().isEmpty());
            record = record.contribute(events.getFirst(), Set.of(), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            var original = await(store.correlatedJoinMembers(key, JoinMemberFilter.RECEIVED, Optional.empty(), 1)).items().getFirst();
            assertEquals(JoinMemberSummary.of(events.getFirst(), false), original);
            assertTrue(await(store.correlatedJoinMembers(key, JoinMemberFilter.MISSING, Optional.empty(), 1)).items().isEmpty());
            record = record.contribute(null, Set.of("part-1", "part-2"), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            var summary = await(store.correlatedJoins(SCOPE, "members", Set.of(), Optional.empty(), 1)).items().getFirst();
            assertEquals(CorrelatedJoinSummary.Expectation.MANIFEST, summary.expectation()); assertFalse(summary.sealed());
            var missing = await(store.correlatedJoinMembers(key, JoinMemberFilter.MISSING, Optional.empty(), 1)).items().getFirst();
            assertEquals("part-2", missing.contributionId()); assertNull(missing.processInstanceId()); assertFalse(missing.received());
            assertEquals(JoinMemberSummary.of(events.getFirst(), true),
                    await(store.correlatedJoinMembers(key, JoinMemberFilter.RECEIVED, Optional.empty(), 1)).items().getFirst());
            var repeated = new JoinContribution(events.getFirst().contributionId(), events.getFirst().kind(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), SECURITY, events.getFirst().payload(), events.getFirst().payloadDigest(), NOW.plusSeconds(1));
            assertSame(record, record.contribute(repeated, Set.of(), null, false, NOW, store.businessHistoryPolicy()));
            assertEquals(events.getFirst().attemptId(), await(store.correlatedJoinMembers(key, JoinMemberFilter.RECEIVED, Optional.empty(), 1)).items().getFirst().attemptId());
        }
    }

    @Test void largeManifestPagesMetadataBeforeProjectionAndBindsEverySelection() throws Exception {
        try (var store = createStore(id(), clock())) {
            var event = contributions(store, "large-members", "round", 1).getFirst();
            var key = new CorrelatedJoinKey(SCOPE, "large-members", "combine", "round");
            var ids = new TreeSet<String>(); for (int i = 0; i < 1024; i++) ids.add("item-" + String.format(java.util.Locale.ROOT, "%04d", i));
            await(store.compareAndSetCorrelatedJoin(CorrelatedJoinRecord.open(key, ids, null, false, JoinCause.of(event), NOW, NOW.plusSeconds(60))));
            Optional<String> cursor = Optional.empty(); var observed = new HashSet<String>(); int pages = 0;
            do {
                var page = await(store.correlatedJoinMembers(key, JoinMemberFilter.MISSING, cursor, 37));
                assertTrue(page.items().size() <= 37); page.items().forEach(row -> assertTrue(observed.add(row.contributionId())));
                cursor = page.nextCursor(); pages++;
            } while (cursor.isPresent());
            assertEquals(ids, observed); assertEquals(28, pages);
            var first = await(store.correlatedJoinMembers(key, JoinMemberFilter.MISSING, Optional.empty(), 1));
            assertThrows(ExecutionException.class, () -> await(store.correlatedJoinMembers(key, JoinMemberFilter.ALL, first.nextCursor(), 1)));
            assertThrows(ExecutionException.class, () -> await(store.correlatedJoinMembers(
                    new CorrelatedJoinKey(SCOPE, "large-members", "combine", "another-round"), JoinMemberFilter.MISSING, first.nextCursor(), 1)));
            assertThrows(ExecutionException.class, () -> await(store.correlatedJoinMembers(key, JoinMemberFilter.MISSING, Optional.empty(), store.businessHistoryPolicy().maxPageSize() + 1)));
            assertEquals(ai.ravenroot.api.history.HistoryPage.Availability.NOT_FOUND, await(store.correlatedJoinMembers(
                    new CorrelatedJoinKey(new BusinessScope("foreign", SCOPE.deploymentId(), SCOPE.graphVersion()), key.applicationCorrelationId(), key.nodeId(), key.roundId()),
                    JoinMemberFilter.ALL, Optional.empty(), 1)).availability());
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

    @Test void historyExpiryReclaimsJoinPayloadButRetainsAnObservableNonReopeningTombstone() throws Exception {
        var current = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        try (var store = createStore(id(), clock)) {
            var event = contributions(store, "retention", "0", 1).getFirst();
            var key = new CorrelatedJoinKey(SCOPE, "retention", "combine", "0");
            var record = CorrelatedJoinRecord.open(key, Set.of(), 2, true, JoinCause.of(event), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(record));
            record = record.contribute(event, Set.of(), null, false, NOW, store.businessHistoryPolicy());
            await(store.compareAndSetCorrelatedJoin(record));
            assertTrue(await(store.correlatedJoins(SCOPE, "retention", Set.of(), Optional.empty(), 1))
                    .items().getFirst().retainedPayloadBytes() > 0);
            current.set(NOW.plus(store.businessHistoryPolicy().historyRetention()).plusMillis(1));
            assertEquals(BusinessHistory.Lifecycle.EXPIRED, await(store.businessHistory(SCOPE, "retention")).orElseThrow().lifecycle());
            var tombstone = await(store.correlatedJoins(SCOPE, "retention", Set.of(), Optional.empty(), 1)).items().getFirst();
            assertEquals(CorrelatedJoinRecord.Phase.EXPIRED, tombstone.phase());
            assertEquals(1, tombstone.receivedContributions());
            assertEquals(0, tombstone.retainedPayloadBytes());
            assertEquals(CorrelatedJoinSummary.Expectation.COUNT, tombstone.expectation());
            assertEquals(JoinMemberSummary.of(event, false),
                    await(store.correlatedJoinMembers(key, JoinMemberFilter.RECEIVED, Optional.empty(), 1)).items().getFirst());
            var unavailable = assertThrows(ExecutionException.class, () -> await(store.correlatedJoin(key)));
            assertEquals(BusinessHistoryException.Reason.EXPIRED_HISTORY, ((BusinessHistoryException) unavailable.getCause()).reason());
            var late = assertThrows(ExecutionException.class, () -> contributions(store, "retention", "0", 1));
            assertEquals(BusinessHistoryException.Reason.EXPIRED_HISTORY, ((BusinessHistoryException) late.getCause()).reason());
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
            assertEquals(JoinMemberSummary.of(saved.contributions().get("part-1"), false),
                    await(store.correlatedJoinMembers(saved.key(), JoinMemberFilter.RECEIVED, Optional.empty(), 1)).items().getFirst());
            var page = await(store.correlatedJoins(SCOPE, null, Set.of(CorrelatedJoinRecord.Phase.READY), Optional.empty(), 1));
            assertEquals(saved.key().continuationProcessId(), page.items().getFirst().key().continuationProcessId());
            assertEquals(saved.contributions().get("part-1").traversalId(),
                    await(store.correlatedJoin(saved.key())).orElseThrow().contributions().get("part-1").traversalId());
        }
    }
    @Test void oversizedManifestEncodingRollsBackBothCreationAndRevision() throws Exception {
        var defaults = BusinessHistoryPolicy.DEFAULTS;
        var policy = new BusinessHistoryPolicy(defaults.maxHistoriesPerScope(), defaults.maxOpenJoinsPerHistory(),
                defaults.maxRetainedJoinsPerHistory(), 10000, defaults.maxJoinPayloadBytes(), defaults.maxPageSize(),
                defaults.historyRetention(), defaults.joinDeadline(), new CorrelationLimits(4096, 1024, 32, 10000,
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS));
        String storeId = id();
        try (var store = createStore(storeId, clock(), policy)) {
            var event = contributions(store, "encoding-bound", "round", 1).getFirst();
            var key = new CorrelatedJoinKey(SCOPE, "encoding-bound", "combine", "round");
            var originalHistory = await(store.businessHistory(SCOPE, "encoding-bound"));
            var ids = new TreeSet<String>(); var prefix = "\u20ac".repeat(4090);
            for (int i = 0; i < 5500; i++) ids.add(prefix + "-" + i);
            var oversized = CorrelatedJoinRecord.open(key, ids, null, false, JoinCause.of(event), NOW, NOW.plusSeconds(60));
            var refused = assertThrows(Exception.class, () -> await(store.compareAndSetCorrelatedJoin(oversized)));
            var reason = refused instanceof ExecutionException ? refused.getCause() : refused;
            assertInstanceOf(IllegalArgumentException.class, reason);
            assertEquals("join encoding exceeds limit", reason.getMessage());
            assertTrue(await(store.correlatedJoin(key)).isEmpty());
            assertTrue(await(store.correlatedJoins(SCOPE, "encoding-bound", Set.of(), Optional.empty(), 1)).items().isEmpty());
            assertEquals(originalHistory, await(store.businessHistory(SCOPE, "encoding-bound")));

            var valid = CorrelatedJoinRecord.open(key, Set.of("part-1"), null, false, JoinCause.of(event), NOW, NOW.plusSeconds(60));
            await(store.compareAndSetCorrelatedJoin(valid));
            var originalMembers = await(store.correlatedJoinMembers(key, JoinMemberFilter.ALL, Optional.empty(), 10));
            var revision = valid.contribute(null, ids, null, false, NOW, policy);
            refused = assertThrows(Exception.class, () -> await(store.compareAndSetCorrelatedJoin(revision)));
            reason = refused instanceof ExecutionException ? refused.getCause() : refused;
            assertInstanceOf(IllegalArgumentException.class, reason);
            assertEquals("join encoding exceeds limit", reason.getMessage());
            assertEquals(valid, await(store.correlatedJoin(key)).orElseThrow());
            assertEquals(originalMembers, await(store.correlatedJoinMembers(key, JoinMemberFilter.ALL, Optional.empty(), 10)));
            assertEquals(originalHistory, await(store.businessHistory(SCOPE, "encoding-bound")));
            var ready = valid.contribute(event, Set.of(), null, true, NOW, policy);
            assertEquals(CorrelatedJoinRecord.Phase.READY, await(store.compareAndSetCorrelatedJoin(ready)).phase());
            assertEquals(ready, await(store.correlatedJoin(key)).orElseThrow());
        }
    }

    @Test void successfulCasPreservesSettledFourOfSixManifest() throws Exception { successfulCloseMustRefuse(4, 6); }
    @Test void successfulCasPreservesSettledReadySixOfSixOutbox() throws Exception { successfulCloseMustRefuse(6, 6); }
    @Test void sealedZeroWorkStillRequiresItsReadyContinuation() throws Exception { successfulCloseMustRefuse(0, 0); }

    @Test void terminalUnsuccessfulJoinsCannotBeReclassifiedAsBusinessSuccess() throws Exception {
        try (var store = createStore(id(), clock())) {
            for (var phase : List.of(CorrelatedJoinRecord.Phase.FAILED, CorrelatedJoinRecord.Phase.EXPIRED, CorrelatedJoinRecord.Phase.CANCELLED)) {
                var join = collected(store, SCOPE, "terminal-" + phase, 4, 6).terminate(phase, NOW);
                await(store.compareAndSetCorrelatedJoin(join));
                var history = await(store.businessHistory(SCOPE, join.key().applicationCorrelationId())).orElseThrow();
                assertEquals(phase == CorrelatedJoinRecord.Phase.CANCELLED ? BusinessHistory.Lifecycle.OPEN : BusinessHistory.Lifecycle.FAILED, history.lifecycle());
                assertEquals(phase != CorrelatedJoinRecord.Phase.CANCELLED, history.incompleteHistory());
                refuseSuccessUnchanged(store, join);
            }
        }
    }

    @Test void negativeCasRetainsMemberFactsAndRefusesLateArrivals() throws Exception {
        try (var store = createStore(id(), clock())) {
            for (var outcome : List.of(BusinessHistory.Lifecycle.FAILED, BusinessHistory.Lifecycle.CANCELLED)) {
                for (int received : List.of(4, 6)) {
                    String app = "negative-" + outcome + "-" + received;
                    var join = collected(store, SCOPE, app, received, 6);
                    var members = await(store.correlatedJoinMembers(join.key(), JoinMemberFilter.ALL, Optional.empty(), 10));
                    var history = await(store.businessHistory(SCOPE, app)).orElseThrow();
                    var closed = await(store.closeBusinessHistory(SCOPE, app, history.revision(), outcome));
                    assertEquals(outcome, closed.lifecycle()); assertEquals(history.revision() + 1, closed.revision());
                    var cancelled = await(store.correlatedJoin(join.key())).orElseThrow();
                    assertEquals(CorrelatedJoinRecord.Phase.CANCELLED, cancelled.phase());
                    assertEquals(join.contributions(), cancelled.contributions()); assertEquals(join.openedBy(), cancelled.openedBy());
                    assertEquals(members, await(store.correlatedJoinMembers(join.key(), JoinMemberFilter.ALL, Optional.empty(), 10)));
                    var refusal = assertThrows(ExecutionException.class, () -> contributions(store, app, "late", 1));
                    assertEquals(BusinessHistoryException.Reason.CLOSED_HISTORY, ((BusinessHistoryException) refusal.getCause()).reason());
                    assertEquals(closed, await(store.businessHistory(SCOPE, app)).orElseThrow());
                }
            }
        }
    }

    @Test void realContinuationMustBeAcknowledgedAndSettledBeforeSuccessfulCas() throws Exception {
        try (var store = createStore(id(), clock())) {
            for (int expected : List.of(0, 6)) {
                var ready = collected(store, SCOPE, "complete-" + expected, expected, expected);
                var continuation = continuation(ready);
                await(store.recordCorrelatedTraversal(continuation, CorrelationMapping.Mode.REQUIRE_EXISTING));
                var dispatched = await(store.compareAndSetCorrelatedJoin(ready.dispatched(NOW)));
                refuseSuccessUnchanged(store, dispatched);
                settle(store, continuation);
                var prior = await(store.businessHistory(SCOPE, ready.key().applicationCorrelationId())).orElseThrow();
                var closed = await(store.closeBusinessHistory(SCOPE, ready.key().applicationCorrelationId(), prior.revision(), BusinessHistory.Lifecycle.COMPLETED));
                assertEquals(BusinessHistory.State.COMPLETED, closed.state()); assertFalse(closed.incompleteHistory());
                assertEquals(dispatched, await(store.correlatedJoin(ready.key())).orElseThrow());
                assertEquals(expected, dispatched.contributions().size());
                assertEquals(expected == 0 ? 2 : 7, closed.completedTraversals());
            }
        }
    }

    @Test void unrelatedScopesAndJoinsDoNotBlockAValidNoJoinHistory() throws Exception {
        try (var store = createStore(id(), clock())) {
            var scopes = List.of(new BusinessScope("other", SCOPE.deploymentId(), SCOPE.graphVersion()),
                    new BusinessScope(SCOPE.tenantId(), "other", SCOPE.graphVersion()), new BusinessScope(SCOPE.tenantId(), SCOPE.deploymentId(), "other"));
            for (var scope : scopes) collected(store, scope, "scope-cas", 4, 6);
            collected(store, SCOPE, "other-business", 4, 6);
            var arrival = contributions(store, "scope-cas", "round", 1).getFirst();
            await(store.settleCorrelatedTraversal(SCOPE.tenantId(), arrival.processInstanceId(), arrival.traversalId(), TraversalStatus.COMPLETED, null));
            var history = await(store.businessHistory(SCOPE, "scope-cas")).orElseThrow();
            assertRefusal(store.closeBusinessHistory(SCOPE, "scope-cas", history.revision() - 1, BusinessHistory.Lifecycle.COMPLETED));
            assertEquals(history, await(store.businessHistory(SCOPE, "scope-cas")).orElseThrow());
            assertEquals(BusinessHistory.Lifecycle.COMPLETED, await(store.closeBusinessHistory(SCOPE, "scope-cas", history.revision(), BusinessHistory.Lifecycle.COMPLETED)).lifecycle());
            for (var scope : scopes) assertEquals(CorrelatedJoinRecord.Phase.OPEN,
                    await(store.correlatedJoin(new CorrelatedJoinKey(scope, "scope-cas", "combine", "round"))).orElseThrow().phase());
        }
    }

    @Test void concurrentFinalContributionAndClosePreserveTheOneReadyOutbox() throws Exception {
        String storeId = id();
        try (var store = createStore(storeId, clock()); var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = store.supports(StoreCapability.DURABLE) ? createStore(storeId, clock()) : store;
            try {
                for (int round = 0; round < 4; round++) {
                    String app = "final-close-" + round;
                    var events = contributions(store, app, "round", 6);
                    var join = CorrelatedJoinRecord.open(new CorrelatedJoinKey(SCOPE, app, "combine", "round"), Set.of(), 6, true, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
                    await(store.compareAndSetCorrelatedJoin(join));
                    for (int i = 0; i < 5; i++) { join = join.contribute(events.get(i), Set.of(), null, false, NOW, store.businessHistoryPolicy()); await(store.compareAndSetCorrelatedJoin(join)); }
                    for (var event : events) await(store.settleCorrelatedTraversal(SCOPE.tenantId(), event.processInstanceId(), event.traversalId(), TraversalStatus.COMPLETED, null));
                    var history = await(store.businessHistory(SCOPE, app)).orElseThrow();
                    var ready = join.contribute(events.getLast(), Set.of(), null, false, NOW, store.businessHistoryPolicy());
                    var start = new CountDownLatch(1);
                    var arrival = CompletableFuture.runAsync(() -> { gate(start); awaitUnchecked(peer.compareAndSetCorrelatedJoin(ready)); }, clients);
                    var closing = CompletableFuture.runAsync(() -> { gate(start); assertRefusal(store.closeBusinessHistory(SCOPE, app, history.revision(), BusinessHistory.Lifecycle.COMPLETED)); }, clients);
                    start.countDown(); CompletableFuture.allOf(arrival, closing).get(30, TimeUnit.SECONDS);
                    assertEquals(history, await(store.businessHistory(SCOPE, app)).orElseThrow());
                    assertEquals(ready, await(store.correlatedJoin(ready.key())).orElseThrow());
                    assertEquals(6, await(store.correlatedJoinMembers(ready.key(), JoinMemberFilter.RECEIVED, Optional.empty(), 10)).items().size());
                }
            } finally { if (peer != store) peer.close(); }
        }
    }

    @Test void concurrentAcknowledgementAndCloseRequireDurableRelease() throws Exception {
        String storeId = id();
        try (var store = createStore(storeId, clock()); var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = store.supports(StoreCapability.DURABLE) ? createStore(storeId, clock()) : store;
            try {
                for (int round = 0; round < 4; round++) {
                    String app = "ack-close-" + round;
                    var ready = collected(store, SCOPE, app, 6, 6); var continuation = continuation(ready);
                    await(store.recordCorrelatedTraversal(continuation, CorrelationMapping.Mode.REQUIRE_EXISTING)); settle(store, continuation);
                    var before = await(store.businessHistory(SCOPE, app)).orElseThrow(); var start = new CountDownLatch(1);
                    var ack = CompletableFuture.runAsync(() -> { gate(start); awaitUnchecked(peer.compareAndSetCorrelatedJoin(ready.dispatched(NOW))); }, clients);
                    var close = CompletableFuture.supplyAsync(() -> { gate(start); try { return awaitUnchecked(store.closeBusinessHistory(SCOPE, app, before.revision(), BusinessHistory.Lifecycle.COMPLETED)); }
                        catch (CompletionException failure) { assertInstanceOf(BusinessHistoryException.class, failure.getCause()); assertEquals(BusinessHistoryException.Reason.REVISION_CONFLICT, ((BusinessHistoryException) failure.getCause()).reason()); return null; } }, clients);
                    start.countDown(); CompletableFuture.allOf(ack, close).get(30, TimeUnit.SECONDS);
                    assertEquals(ready.dispatched(NOW), await(store.correlatedJoin(ready.key())).orElseThrow());
                    var current = await(store.businessHistory(SCOPE, app)).orElseThrow();
                    if (close.get() == null) { assertEquals(before, current); current = await(store.closeBusinessHistory(SCOPE, app, current.revision(), BusinessHistory.Lifecycle.COMPLETED)); }
                    assertEquals(BusinessHistory.State.COMPLETED, current.state()); assertEquals(7, current.completedTraversals());
                }
            } finally { if (peer != store) peer.close(); }
        }
    }

    @Test void refusedSuccessAndReadyOutboxRemainIntactAfterReopen() throws Exception {
        String storeId = id(); ClosureSnapshot saved, missing;
        try (var store = createStore(storeId, clock())) {
            assumeTrue(store.supports(StoreCapability.DURABLE));
            var open = collected(store, SCOPE, "reopen-missing", 4, 6); refuseSuccessUnchanged(store, open); missing = snapshot(store, open.key());
            var ready = collected(store, SCOPE, "reopen-close", 6, 6); refuseSuccessUnchanged(store, ready); saved = snapshot(store, ready.key());
        }
        try (var reopened = createStore(storeId, clock())) {
            assertEquals(missing, snapshot(reopened, missing.join().key())); refuseSuccessUnchanged(reopened, missing.join());
            assertEquals(saved, snapshot(reopened, saved.join().key())); refuseSuccessUnchanged(reopened, saved.join());
            var continuation = continuation(saved.join());
            await(reopened.recordCorrelatedTraversal(continuation, CorrelationMapping.Mode.REQUIRE_EXISTING));
            await(reopened.compareAndSetCorrelatedJoin(saved.join().dispatched(NOW))); settle(reopened, continuation);
            var current = await(reopened.businessHistory(SCOPE, "reopen-close")).orElseThrow();
            assertEquals(BusinessHistory.State.COMPLETED, await(reopened.closeBusinessHistory(SCOPE, "reopen-close", current.revision(), BusinessHistory.Lifecycle.COMPLETED)).state());
        }
        try (var reopened = createStore(storeId, clock())) {
            assertEquals(missing, snapshot(reopened, missing.join().key()));
            assertEquals(BusinessHistory.State.COMPLETED, await(reopened.businessHistory(SCOPE, "reopen-close")).orElseThrow().state());
            assertEquals(CorrelatedJoinRecord.Phase.DISPATCHED, await(reopened.correlatedJoin(saved.join().key())).orElseThrow().phase());
        }
    }

    @Test void expiryBeforeSuccessfulCloseRemainsObservableAndDoesNotReopenCollection() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        try (var store = createStore(id(), clock)) {
            var join = collected(store, SCOPE, "expire-close", 4, 6);
            var history = await(store.businessHistory(SCOPE, "expire-close")).orElseThrow();
            time.set(NOW.plus(store.businessHistoryPolicy().historyRetention()).plusMillis(1));
            assertRefusal(store.closeBusinessHistory(SCOPE, "expire-close", history.revision(), BusinessHistory.Lifecycle.COMPLETED));
            // Memory can publish expiry before refusal; transactional stores may roll it back.
            // An authoritative read applies the existing expiry contract in either adapter.
            var expired = await(store.businessHistory(SCOPE, "expire-close")).orElseThrow();
            assertEquals(BusinessHistory.Lifecycle.EXPIRED, expired.lifecycle()); assertTrue(expired.incompleteHistory());
            assertEquals(history.revision() + 1, expired.revision());
            var summary = await(store.correlatedJoins(SCOPE, "expire-close", Set.of(), Optional.empty(), 1)).items().getFirst();
            assertEquals(CorrelatedJoinRecord.Phase.EXPIRED, summary.phase()); assertEquals(4, summary.receivedContributions());
            assertEquals(2, await(store.correlatedJoinMembers(join.key(), JoinMemberFilter.MISSING, Optional.empty(), 10)).items().size());
        }
    }

    private static void gate(CountDownLatch start) { try { assertTrue(start.await(10, TimeUnit.SECONDS)); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
    private static void settle(ExecutionStore store, CorrelatedTraversal traversal) throws Exception {
        await(store.settleCorrelatedTraversal(traversal.scope().tenantId(), traversal.processInstanceId(), traversal.traversalId(), TraversalStatus.COMPLETED, null));
    }
    private static CorrelatedTraversal continuation(CorrelatedJoinRecord ready) {
        return new CorrelatedTraversal(ready.key().scope(), new ApplicationCorrelationContext(ready.key().applicationCorrelationId(), Optional.empty(), Optional.empty(), ready.key().roundId(), Optional.empty()),
                ready.key().continuationProcessId(), ready.key().continuationTraversalId(), ready.key().nodeId(), CorrelatedTraversal.Cause.JOIN_CONTINUATION, TraversalStatus.ACCEPTED, null, NOW, NOW, null);
    }

    private void successfulCloseMustRefuse(int received, int expected) throws Exception {
        try (var store = createStore(id(), clock())) {
            var join = collected(store, SCOPE, "successful-cas", received, expected);
            assertEquals(expected == received ? CorrelatedJoinRecord.Phase.READY : CorrelatedJoinRecord.Phase.OPEN, join.phase());
            assertEquals(expected - received, await(store.correlatedJoinMembers(join.key(), JoinMemberFilter.MISSING, Optional.empty(), 10)).items().size());
            refuseSuccessUnchanged(store, join);
        }
    }
    private record ClosureSnapshot(BusinessHistory history, CorrelatedJoinRecord join, CorrelatedJoinPage joins,
            ai.ravenroot.api.history.HistoryPage<JoinMemberSummary> members, CorrelatedTraversalPage traversals) { }
    private static ClosureSnapshot snapshot(ExecutionStore store, CorrelatedJoinKey key) throws Exception {
        return new ClosureSnapshot(await(store.businessHistory(key.scope(), key.applicationCorrelationId())).orElseThrow(),
                await(store.correlatedJoin(key)).orElseThrow(), await(store.correlatedJoins(key.scope(), key.applicationCorrelationId(), Set.of(), Optional.empty(), 10)),
                await(store.correlatedJoinMembers(key, JoinMemberFilter.ALL, Optional.empty(), 10)),
                await(store.correlatedTraversals(key.scope(), key.applicationCorrelationId(), Optional.empty(), 10)));
    }
    private static void refuseSuccessUnchanged(ExecutionStore store, CorrelatedJoinRecord join) throws Exception {
        var before = snapshot(store, join.key());
        assertRefusal(store.closeBusinessHistory(join.key().scope(), join.key().applicationCorrelationId(), before.history().revision(), BusinessHistory.Lifecycle.COMPLETED));
        assertEquals(before, snapshot(store, join.key()));
    }
    private static void assertRefusal(CompletionStage<?> result) {
        var failure = assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
        assertInstanceOf(BusinessHistoryException.class, failure.getCause());
        assertEquals(BusinessHistoryException.Reason.REVISION_CONFLICT, ((BusinessHistoryException) failure.getCause()).reason());
    }
    private static CorrelatedJoinRecord collected(ExecutionStore store, BusinessScope scope, String app, int received, int expected) throws Exception {
        var events = contributions(store, scope, app, "round", Math.max(1, received));
        var ids = new TreeSet<String>(); for (int i = 1; i <= expected; i++) ids.add("part-" + i);
        var join = CorrelatedJoinRecord.open(new CorrelatedJoinKey(scope, app, "combine", "round"), ids, null, true, JoinCause.of(events.getFirst()), NOW, NOW.plusSeconds(60));
        await(store.compareAndSetCorrelatedJoin(join));
        for (int i = 0; i < received; i++) { join = join.contribute(events.get(i), Set.of(), null, false, NOW, store.businessHistoryPolicy()); await(store.compareAndSetCorrelatedJoin(join)); }
        for (var event : events) await(store.settleCorrelatedTraversal(scope.tenantId(), event.processInstanceId(), event.traversalId(), TraversalStatus.COMPLETED, null));
        return join;
    }

    private static List<JoinContribution> contributions(ExecutionStore store, String operation, String round, int count) throws Exception {
        return contributions(store, SCOPE, operation, round, count);
    }
    private static List<JoinContribution> contributions(ExecutionStore store, BusinessScope scope, String operation, String round, int count) throws Exception {
        var result = new ArrayList<JoinContribution>();
        var security = new SecurityContext("request", scope.tenantId(), "actor", PrincipalType.WORKLOAD, "test");
        for (int i = 1; i <= count; i++) {
            UUID process = UUID.randomUUID(), traversal = UUID.randomUUID(); String id = "part-" + i;
            var context = new ApplicationCorrelationContext(operation, Optional.of(id), Optional.of("partition"), round, Optional.empty());
            await(store.recordCorrelatedTraversal(new CorrelatedTraversal(scope, context, process, traversal, "one-source",
                    CorrelatedTraversal.Cause.SOURCE_EVENT, TraversalStatus.RUNNING, null, NOW, NOW, null), CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            var payload = OpaquePayload.of(new byte[]{(byte) i}, "application/octet-stream");
            result.add(new JoinContribution(id, "partition", process, traversal, UUID.randomUUID(), UUID.randomUUID(), security,
                    payload, JoinContribution.digest(payload.bytes()), NOW));
        }
        return result;
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(60, TimeUnit.SECONDS); }
    private static <T> T awaitUnchecked(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
