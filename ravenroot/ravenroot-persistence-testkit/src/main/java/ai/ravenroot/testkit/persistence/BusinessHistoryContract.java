package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executed reference contract for scoped durable correlation, paging, closure and START admission. */
@Timeout(120)
public abstract class BusinessHistoryContract {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final BusinessScope SCOPE = new BusinessScope("tenant", "deployment", "version");
    /** Each ID selects an isolated physical store and may be reopened when DURABLE is declared. */
    protected abstract ExecutionStore createStore(String id, Clock clock);

    @Test void sourceFirstHistoryWaitsForExternalWorkAndNeverInfersCompletionFromSilence() throws Exception {
        try (var store = createStore(id(), clock())) {
            assertTrue(store.supports(StoreCapability.BUSINESS_HISTORIES));
            var bronze = arrival(SCOPE, "ingestion-42", "bronze", "orders.csv");
            var created = await(store.recordCorrelatedTraversal(bronze, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(BusinessHistory.State.RUNNING, created.state());
            await(store.settleCorrelatedTraversal(SCOPE.tenantId(), bronze.processInstanceId(), bronze.traversalId(),
                    TraversalStatus.COMPLETED, null));
            var waiting = await(store.businessHistory(SCOPE, "ingestion-42")).orElseThrow();
            assertEquals(BusinessHistory.State.AWAITING_EXTERNAL_EVENT, waiting.state());
            assertEquals(BusinessHistory.Lifecycle.OPEN, waiting.lifecycle());
            var silver = arrival(SCOPE, "ingestion-42", "silver", "orders.csv");
            await(store.recordCorrelatedTraversal(silver, CorrelationMapping.Mode.REQUIRE_EXISTING));
            var all = await(store.correlatedTraversals(SCOPE, "ingestion-42", Optional.empty(), 10));
            assertEquals(Set.of(bronze.traversalId(), silver.traversalId()), all.items().stream()
                    .map(CorrelatedTraversal::traversalId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(bronze.processInstanceId(), silver.processInstanceId()), all.items().stream()
                    .map(CorrelatedTraversal::processInstanceId).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void exactTenantDeploymentAndVersionScopesNeverCollide() throws Exception {
        try (var store = createStore(id(), clock())) {
            var scopes = List.of(SCOPE, new BusinessScope("tenant-2", "deployment", "version"),
                    new BusinessScope("tenant", "deployment-2", "version"), new BusinessScope("tenant", "deployment", "version-2"));
            for (var scope : scopes) await(store.recordCorrelatedTraversal(arrival(scope, "same", "1", "same.csv"),
                    CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            for (var scope : scopes) {
                assertEquals(1, await(store.businessHistories(scope, BusinessHistoryQuery.first(10))).items().size());
                assertEquals(1, await(store.correlatedTraversals(scope, "same", Optional.empty(), 10)).items().size());
            }
            assertTrue(await(store.businessHistory(new BusinessScope("foreign", "deployment", "version"), "same")).isEmpty());
            assertRefusal(BusinessHistoryException.Reason.UNKNOWN_HISTORY, store.recordCorrelatedTraversal(
                    arrival(SCOPE, "unknown", "1", "same.csv"), CorrelationMapping.Mode.REQUIRE_EXISTING));
            assertTrue(await(store.businessHistory(SCOPE, "unknown")).isEmpty());
        }
    }

    @Test void duplicatesAreIdempotentAndConflictingContextCannotOverwriteAnOriginalRelation() throws Exception {
        try (var store = createStore(id(), clock())) {
            var event = arrival(SCOPE, "operation", "partition-1", "file.csv");
            await(store.recordCorrelatedTraversal(event, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            var jobs = new ArrayList<CompletableFuture<BusinessHistory>>();
            try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 24; i++) jobs.add(CompletableFuture.supplyAsync(() -> awaitUnchecked(
                        store.recordCorrelatedTraversal(event, CorrelationMapping.Mode.ACCEPT_OR_CREATE)), clients));
                CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            }
            assertEquals(1, await(store.businessHistory(SCOPE, "operation")).orElseThrow().runningTraversals());
            var changed = new CorrelatedTraversal(SCOPE, context("different", "partition-1", "file.csv"),
                    event.processInstanceId(), event.traversalId(), event.entryNodeId(), event.cause(),
                    event.status(), null, EPOCH, EPOCH, null);
            assertRefusal(BusinessHistoryException.Reason.CONTEXT_CONFLICT,
                    store.recordCorrelatedTraversal(changed, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(event.context(), await(store.correlatedTraversal(SCOPE.tenantId(), event.processInstanceId(),
                    event.traversalId())).orElseThrow().context());
        }
    }

    @Test void completionIsExplicitFailuresSurviveAndLateArrivalsCannotReopen() throws Exception {
        try (var store = createStore(id(), clock())) {
            var first = arrival(SCOPE, "closed", "1", "closed.csv");
            await(store.recordCorrelatedTraversal(first, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            await(store.settleCorrelatedTraversal(SCOPE.tenantId(), first.processInstanceId(), first.traversalId(),
                    TraversalStatus.COMPLETED, null));
            var open = await(store.businessHistory(SCOPE, "closed")).orElseThrow();
            var closed = await(store.closeBusinessHistory(SCOPE, "closed", open.revision(), BusinessHistory.Lifecycle.COMPLETED));
            assertEquals(BusinessHistory.State.COMPLETED, closed.state());
            assertRefusal(BusinessHistoryException.Reason.CLOSED_HISTORY, store.recordCorrelatedTraversal(
                    arrival(SCOPE, "closed", "2", "closed.csv"), CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            assertEquals(closed, await(store.businessHistory(SCOPE, "closed")).orElseThrow());
            var failed = arrival(SCOPE, "mixed", "bad", "mixed.csv");
            var succeeded = arrival(SCOPE, "mixed", "good", "mixed.csv");
            await(store.recordCorrelatedTraversal(failed, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            await(store.recordCorrelatedTraversal(succeeded, CorrelationMapping.Mode.REQUIRE_EXISTING));
            await(store.settleCorrelatedTraversal(SCOPE.tenantId(), failed.processInstanceId(), failed.traversalId(), TraversalStatus.FAILED, null));
            await(store.settleCorrelatedTraversal(SCOPE.tenantId(), succeeded.processInstanceId(), succeeded.traversalId(), TraversalStatus.COMPLETED, null));
            var mixed = await(store.businessHistory(SCOPE, "mixed")).orElseThrow();
            assertEquals(BusinessHistory.State.PARTIAL, mixed.state());
            assertEquals(1, mixed.failedTraversals());
            assertRefusal(BusinessHistoryException.Reason.REVISION_CONFLICT,
                    store.closeBusinessHistory(SCOPE, "mixed", mixed.revision(), BusinessHistory.Lifecycle.COMPLETED));
        }
    }

    @Test void thousandInterleavedHistoriesUseBoundedServerPagesAndExactReferenceSearch() throws Exception {
        try (var store = createStore(id(), clock())) {
            for (int i = 0; i < 1000; i++) {
                var event = arrival(SCOPE, String.format(java.util.Locale.ROOT, "ingestion-%04d", i), "bronze", "file-" + (i % 10) + ".csv");
                await(store.recordCorrelatedTraversal(event, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            }
            var seen = new HashSet<String>(); Optional<String> cursor = Optional.empty(); int pages = 0;
            do {
                var page = await(store.businessHistories(SCOPE, new BusinessHistoryQuery(Optional.empty(), Optional.empty(), Set.of(), cursor, 37)));
                assertTrue(page.items().size() <= 37); pages++;
                for (var group : page.items()) assertTrue(seen.add(group.applicationCorrelationId()), "stable cursor returned a duplicate");
                cursor = page.nextCursor();
            } while (cursor.isPresent());
            assertEquals(1000, seen.size()); assertEquals(28, pages);
            var exact = await(store.businessHistories(SCOPE, new BusinessHistoryQuery(Optional.of("ingestion-0042"),
                    Optional.empty(), Set.of(), Optional.empty(), 10)));
            assertEquals(List.of("ingestion-0042"), exact.items().stream().map(BusinessHistory::applicationCorrelationId).toList());
            var references = await(store.businessHistories(SCOPE, new BusinessHistoryQuery(Optional.empty(),
                    Optional.of("file-2.csv"), Set.of(), Optional.empty(), 10)));
            assertEquals(10, references.items().size()); assertTrue(references.nextCursor().isPresent());
            assertTrue(references.items().stream().allMatch(group -> group.publicReference().equals(Optional.of("file-2.csv"))));
            String foreignCursor = references.nextCursor().orElseThrow();
            assertRefusal(BusinessHistoryException.Reason.INVALID_REQUEST, store.businessHistories(
                    new BusinessScope("foreign", "deployment", "version"), new BusinessHistoryQuery(Optional.empty(),
                            Optional.of("file-2.csv"), Set.of(), Optional.of(foreignCursor), 10)));
        }
    }

    @Test void durableReopenPreservesWaitingHistoryContextAndPermanentStartAdmission() throws Exception {
        String storeId = id(); CorrelatedTraversal event = arrival(SCOPE, "restart", "bronze", "restart.csv");
        InitialStartRecord original;
        try (var store = createStore(storeId, clock())) {
            assumeTrue(store.supports(StoreCapability.DURABLE), "reference store deliberately loses state on close");
            await(store.recordCorrelatedTraversal(event, CorrelationMapping.Mode.ACCEPT_OR_CREATE));
            await(store.settleCorrelatedTraversal(SCOPE.tenantId(), event.processInstanceId(), event.traversalId(), TraversalStatus.COMPLETED, null));
            original = await(store.acceptInitialStart(new InitialStartRecord(SCOPE.deploymentId(), SCOPE.graphVersion(),
                    identity(), OpaquePayload.of("\"original\"".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json"), EPOCH)));
        }
        try (var store = createStore(storeId, clock())) {
            assertEquals(BusinessHistory.State.AWAITING_EXTERNAL_EVENT, await(store.businessHistory(SCOPE, "restart")).orElseThrow().state());
            assertEquals(event.context(), await(store.correlatedTraversal(SCOPE.tenantId(), event.processInstanceId(), event.traversalId())).orElseThrow().context());
            var retried = await(store.acceptInitialStart(new InitialStartRecord(SCOPE.deploymentId(), "another-version", identity(),
                    OpaquePayload.empty("application/json"), EPOCH)));
            assertEquals(original, retried);
        }
    }

    private static CorrelatedTraversal arrival(BusinessScope scope, String id, String contribution, String reference) {
        return new CorrelatedTraversal(scope, context(id, contribution, reference), UUID.randomUUID(), UUID.randomUUID(),
                "source", CorrelatedTraversal.Cause.SOURCE_EVENT, TraversalStatus.ACCEPTED, null, EPOCH, EPOCH, null);
    }
    private static ApplicationCorrelationContext context(String id, String contribution, String reference) {
        return new ApplicationCorrelationContext(id, Optional.of(contribution), Optional.empty(), "0", Optional.of(reference));
    }
    private static SecurityContext identity() { return new SecurityContext("request", "tenant", "subject", PrincipalType.USER, "urn:test"); }
    private static Clock clock() { return Clock.fixed(EPOCH, ZoneOffset.UTC); }
    private static String id() { return "business-" + UUID.randomUUID(); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(30, TimeUnit.SECONDS); }
    private static <T> T awaitUnchecked(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private static void assertRefusal(BusinessHistoryException.Reason expected, CompletionStage<?> stage) {
        var failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertInstanceOf(BusinessHistoryException.class, failure.getCause());
        assertEquals(expected, ((BusinessHistoryException) failure.getCause()).reason());
    }
}
