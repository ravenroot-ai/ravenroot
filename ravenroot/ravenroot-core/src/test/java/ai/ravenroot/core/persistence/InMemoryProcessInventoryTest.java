package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.InventoryDisposition;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryPage;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable inventory contract (issue 154) against the reference adapter.
 *
 * <p>The reference adapter is not durable and does not pretend to be, so the reopen half of the
 * issue's acceptance criteria is asserted against SQLite. Everything else — ordering, cursor
 * stability, tenant isolation, disposition precedence, retention and the rejections — is a property of
 * the contract rather than of the medium, and is asserted here too. That is deliberate: an assertion
 * that only ever runs against one adapter is an assertion about that adapter, and the two
 * implementations agreeing is the whole point of having a port.</p>
 */
class InMemoryProcessInventoryTest {

    private static final Instant EPOCH = Instant.parse("2026-03-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(1);
    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "globex";

    private final MutableClock clock = new MutableClock(EPOCH);
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);

    @Test
    void theReferenceAdapterDeclaresTheInventoryCapabilitiesWithoutClaimingDurability() {
        assertTrue(store.supports(StoreCapability.PROCESS_INVENTORY));
        assertTrue(store.supports(StoreCapability.INVENTORY_RETENTION));
        assertFalse(store.supports(StoreCapability.DURABLE),
                "an inventory is not a durability claim, and declaring one to reach the other would "
                        + "invalidate the conformance result");
        assertEquals(100, store.maxInventoryPageSize());
        assertEquals(Duration.ofDays(7), store.terminalRetention());
    }

    @Test
    void listingIsNewestFirstAndPagesDeterministicallyWhileNewWorkArrives() {
        var created = new ArrayList<UUID>();
        for (int index = 0; index < 5; index++) {
            var key = new ExecutionKey(TENANT, UUID.randomUUID());
            running(key, UUID.randomUUID());
            created.add(key.processInstanceId());
            clock.advance(Duration.ofSeconds(1));
        }

        var seen = new ArrayList<UUID>();
        ProcessInventoryQuery query = ProcessInventoryQuery.outstanding(2);
        ProcessInventoryPage page = await(store.listProcessInstances(TENANT, query));
        page.items().forEach(item -> seen.add(item.key().processInstanceId()));

        var late = new ExecutionKey(TENANT, UUID.randomUUID());
        running(late, UUID.randomUUID());
        clock.advance(Duration.ofSeconds(1));

        while (page.nextCursor().isPresent()) {
            page = await(store.listProcessInstances(TENANT, query.after(page.nextCursor().get())));
            page.items().forEach(item -> seen.add(item.key().processInstanceId()));
        }

        var reversed = new ArrayList<>(created);
        java.util.Collections.reverse(reversed);
        assertEquals(reversed, seen, "each row exactly once, newest first, with nothing lost or repeated");
        assertFalse(seen.contains(late.processInstanceId()),
                "work accepted after the scan started sorts before page one, so an in-flight scan does "
                        + "not see it -- which is the price of a cursor that cannot skip a row");
    }

    @Test
    void twoInstancesCreatedAtTheSameInstantAreOrderedByIdSoTheTieCannotMoveBetweenPages() {
        // A tie is the case a naive (createdAt only) cursor gets wrong: without a second component it
        // either repeats the whole group or skips past it.
        var first = new ExecutionKey(TENANT, UUID.randomUUID());
        var second = new ExecutionKey(TENANT, UUID.randomUUID());
        running(first, UUID.randomUUID());
        running(second, UUID.randomUUID());

        var seen = new ArrayList<UUID>();
        ProcessInventoryQuery query = ProcessInventoryQuery.outstanding(1);
        ProcessInventoryPage page = await(store.listProcessInstances(TENANT, query));
        page.items().forEach(item -> seen.add(item.key().processInstanceId()));
        while (page.nextCursor().isPresent()) {
            page = await(store.listProcessInstances(TENANT, query.after(page.nextCursor().get())));
            page.items().forEach(item -> seen.add(item.key().processInstanceId()));
        }
        assertEquals(2, seen.size());
        assertEquals(Set.of(first.processInstanceId(), second.processInstanceId()), Set.copyOf(seen));

        var descendingById = new ArrayList<>(List.of(first.processInstanceId(), second.processInstanceId()));
        descendingById.sort(java.util.Comparator.comparing(UUID::toString).reversed());
        assertEquals(descendingById, seen,
                "the tie is broken by the id as TEXT, which is what a SQL adapter's TEXT column gives");
    }

    @Test
    void anotherTenantsInstanceIsIndistinguishableFromAMissingOne() {
        var mine = new ExecutionKey(TENANT, UUID.randomUUID());
        running(mine, UUID.randomUUID());

        var borrowed = new ExecutionKey(OTHER_TENANT, mine.processInstanceId());
        assertEquals(await(store.findProcessInstance(new ExecutionKey(OTHER_TENANT, UUID.randomUUID()))),
                await(store.findProcessInstance(borrowed)),
                "otherwise the store is a cross-tenant existence oracle and a caller could enumerate "
                        + "another tenant's ids through the difference");
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(store.listTraversals(borrowed))));
        assertEquals(List.of(),
                await(store.listProcessInstances(OTHER_TENANT, ProcessInventoryQuery.everything(10))).items());
    }

    @Test
    void anInstanceWithNoTraversalsReportsNoneRatherThanBeingReportedMissing() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED, Map.of()),
                        new GraphVersionPin("graph-v1")))
                .build()));
        assertEquals(List.of(), await(store.listTraversals(key)));
        assertEquals(0, only(key).traversalCount());
    }

    @Test
    void dispositionTracksTheLeaseWithNoWriteBetweenAndParkedOutranksTerminal() {
        var leased = new ExecutionKey(TENANT, UUID.randomUUID());
        running(leased, UUID.randomUUID());
        await(store.claim(leased, "worker-1", TTL));
        assertEquals(InventoryDisposition.ACTIVE, only(leased).disposition());

        clock.advance(TTL.plusSeconds(1));
        assertEquals(InventoryDisposition.INTERRUPTED, only(leased).disposition(),
                "nothing was written between these two reads; a stored classification would have had "
                        + "no transaction in which to correct itself");
        assertEquals(Optional.empty(), only(leased).ownerWorkerId());
        assertTrue(only(leased).fencingToken() > 0, "the token outlives the lease that issued it");

        var waiting = new ExecutionKey(TENANT, UUID.randomUUID());
        running(waiting, UUID.randomUUID());
        transition(waiting, ProcessInstanceStatus.WAITING);
        assertEquals(InventoryDisposition.WAITING, only(waiting).disposition());

        var parked = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        parkThenFail(parked, traversalId);
        assertEquals(ProcessInstanceStatus.FAILED, only(parked).status());
        assertEquals(InventoryDisposition.PARKED, only(parked).disposition());
        List<TraversalInventoryEntry> traversals = await(store.listTraversals(parked));
        assertEquals(1, traversals.get(0).parkedAttemptCount());
        assertEquals(1, traversals.get(0).invocationCount());
        assertEquals(InventoryDisposition.PARKED, traversals.get(0).disposition());
    }

    @Test
    void aTerminalRowGoesOnlyByAnExplicitPurgeAndTheFloorThenSaysWhereCompletenessEnds() {
        var terminal = new ExecutionKey(TENANT, UUID.randomUUID());
        var live = new ExecutionKey(TENANT, UUID.randomUUID());
        failed(terminal, UUID.randomUUID());
        running(live, UUID.randomUUID());

        Instant deadline = only(terminal).retainedUntil().orElseThrow();
        assertEquals(EPOCH.plus(Duration.ofDays(7)), deadline);
        assertEquals(Optional.empty(), only(live).retainedUntil(),
                "retention has not started for a non-terminal instance, and a sentinel date would read "
                        + "as a real deadline");

        clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertTrue(await(store.findProcessInstance(terminal)).isPresent(),
                "reads have no side effects, so two identical listings return identical pages");
        assertEquals(Instant.MIN, await(store.inventoryRetainedFrom(TENANT)));

        assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT)));
        assertEquals(Optional.empty(), await(store.findProcessInstance(terminal)));
        assertTrue(await(store.findProcessInstance(live)).isPresent(),
                "age is not evidence that work has finished");
        assertEquals(deadline, await(store.inventoryRetainedFrom(TENANT)));
        assertEquals(Instant.MIN, await(store.inventoryRetainedFrom(OTHER_TENANT)),
                "and only the purged tenant's floor moves");

        assertEquals(0L, await(store.purgeExpiredProcessInstances(TENANT)));
        assertEquals(deadline, await(store.inventoryRetainedFrom(TENANT)),
                "a purge that forgot nothing must not move the floor, or a periodic job would report a "
                        + "retention gap on every tick");
        assertEquals(deadline,
                await(store.listProcessInstances(TENANT, ProcessInventoryQuery.everything(10))).retainedFrom());
    }

    @Test
    void lifecycleGenerationCountsAppliedTransitionsAndTheTokenMovesIndependently() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        StoredProcessInstance created = await(store.apply(creation(key, UUID.randomUUID())));
        assertEquals(1L, only(key).lifecycleGeneration());

        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .build()));
        assertEquals(3L, only(key).lifecycleGeneration(),
                "two transitions in one batch are two transitions; an endpoint comparison would have "
                        + "counted none");

        await(store.claim(key, "worker-1", TTL));
        clock.advance(TTL.plusSeconds(1));
        long token = await(store.claim(key, "worker-2", TTL)).fencingToken();
        assertEquals(3L, only(key).lifecycleGeneration(),
                "changing owner does not move the lifecycle, which is why the two identities are kept "
                        + "distinct rather than folded into one counter");
        assertEquals(token, only(key).fencingToken());
    }

    @Test
    void anOriginIsRecordedWhenSuppliedAndNeverErasedByALaterWriteThatDoesNotKnowIt() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted(key, UUID.randomUUID()),
                        new GraphVersionPin("graph-v1")))
                .recordOrigin(ExecutionOrigin.of("dep-7", "nightly", "corr-1"))
                .build()));
        assertEquals(Optional.of("dep-7"), only(key).deploymentId());

        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));
        ProcessInventoryEntry after = only(key);
        assertEquals(Optional.of("dep-7"), after.deploymentId());
        assertEquals(Optional.of("nightly"), after.workloadId());
        assertEquals(Optional.of("corr-1"), after.correlationId());

        assertEquals(1, await(store.listProcessInstances(TENANT, ProcessInventoryQuery.builder()
                .hostedBy("dep-7").limit(10).build())).items().size());
        assertEquals(0, await(store.listProcessInstances(TENANT, ProcessInventoryQuery.builder()
                .hostedBy("dep-8").limit(10).build())).items().size());
    }

    @Test
    void aBadRequestIsRejectedRatherThanAnsweredWithAnEmptyPage() {
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store.listProcessInstances(TENANT, ProcessInventoryQuery.outstanding(0)))));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store.listProcessInstances(TENANT,
                        ProcessInventoryQuery.outstanding(store.maxInventoryPageSize() + 1)))));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store.listProcessInstances(TENANT,
                        ProcessInventoryQuery.builder().status(ProcessInstanceStatus.FAILED).limit(5).build()))));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store.listProcessInstances(TENANT,
                        ProcessInventoryQuery.outstanding(5).after("not-a-cursor")))));
    }

    // ---------------------------------------------------------------- fixtures

    private ProcessInventoryEntry only(ExecutionKey key) {
        return await(store.findProcessInstance(key)).orElseThrow();
    }

    private static ProcessInstance accepted(ExecutionKey key, UUID traversalId) {
        return new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
    }

    private static ExecutionBatch creation(ExecutionKey key, UUID traversalId) {
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted(key, traversalId),
                        new GraphVersionPin("graph-v1")))
                .build();
    }

    private void transition(ExecutionKey key, ProcessInstanceStatus next) {
        StoredProcessInstance current = await(store.load(key));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(current.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(next))
                .build()));
    }

    private void running(ExecutionKey key, UUID traversalId) {
        await(store.apply(creation(key, traversalId)));
        transition(key, ProcessInstanceStatus.RUNNING);
    }

    private void failed(ExecutionKey key, UUID traversalId) {
        await(store.apply(creation(key, traversalId)));
        transition(key, ProcessInstanceStatus.FAILED);
    }

    private void parkThenFail(ExecutionKey key, UUID traversalId) {
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance created = await(store.apply(creation(key, traversalId)));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "mail.send", null, NodeInvocationStatus.SCHEDULED)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        StoredProcessInstance runningNow = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        StoredProcessInstance parked = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(runningNow.revision()))
                .apply(new ExecutionTransition.AttemptParked(traversalId, invocationId, attemptId,
                        "dispatched with unknown outcome"))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(parked.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED))
                .build()));
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
