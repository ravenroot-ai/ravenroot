package ai.ravenroot.core.recovery;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordinator every recovered item passes through, and what it refuses before an adapter is asked.
 *
 * <p>No manifest service is composed here, so every refusal below is reached through the pinned
 * document alone. That is deliberate: it isolates the definition half of the fail-closed contract
 * from the manifest half, which {@code PinnedGraphRestartRecoveryTest} covers with a real
 * resolver. A test that exercised both at once could pass while only one of them worked.</p>
 */
class ExecutionRecoveryCoordinatorTest {

    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);
    private final CountingGraphDefinitionStore definitions =
            new CountingGraphDefinitionStore(new InMemoryGraphDefinitionStore(clock));

    @AfterEach
    void closeStores() {
        store.close();
        definitions.close();
    }

    @Test
    @DisplayName("an execution whose pinned document is gone is never offered to an adapter at all")
    void anInstanceThisDeploymentCannotRebuildNeverReachesAnAdapter() {
        // The pin names a document that was never stored, which is what an execution accepted by a
        // deployment whose retained documents did not come with it looks like after a restore.
        Fixture fixture = scheduleAttempt("00".repeat(32));
        var adapter = new RecordingAdapter(true);
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of(adapter));

        PendingWork claimed = claim();

        RecoveryAdmission admission = coordinator.admits(claimed);

        assertFalse(admission.proceeds());
        assertFalse(admission.repairableByWaiting(),
                "a document that was never stored will not appear by waiting, so this refusal is the "
                        + "bounded kind rather than the kind that resolves on its own");
        assertFalse(admission.detail().isBlank());
        assertTrue(adapter.asked.isEmpty(),
                "the adapter must not even be consulted: asking it spends a durable read to reach a "
                        + "refusal the coordinator had already decided");
        assertEquals(NodeAttemptStatus.SCHEDULED, currentAttempt(fixture).status(),
                "a refusal writes nothing, so the attempt is exactly as it was found");
    }

    @Test
    @DisplayName("a refused item stays claimable, so the durable wait survives the refusal")
    void aRefusedItemIsNeitherParkedNorAcknowledged() {
        scheduleAttempt("11".repeat(32));
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of(new RecordingAdapter(true)));
        var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                coordinator.declarations(), coordinator);

        List<RecoveryOutcome> outcomes = recovery.sweepOnce();

        assertInstanceOf(RecoveryOutcome.Deferred.class, outcomes.get(0));
        // The sweep's own claim holds a lease; a real redelivery waits for it to lapse, so the clock
        // is moved past the TTL rather than the claim being tidied away by the test.
        clock.advance(TTL.plusSeconds(1));
        assertEquals(1, await(store.claimPendingWork(TENANT, "recovery-2", 10, TTL)).size(),
                "unacknowledged work stays claimable, which is how nothing is lost while a "
                        + "deployment cannot rebuild it");
    }

    @Test
    @DisplayName("an item a rebuildable execution owns reaches exactly the adapter that claims it")
    void aRebuildableExecutionReachesItsOwningAdapter() {
        Fixture fixture = storedGraphFixture();
        var owner = new RecordingAdapter(true);
        var stranger = new RecordingAdapter(false);
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of(stranger, owner));

        PendingWork claimed = claim();
        assertTrue(coordinator.canDispatch(claimed));
        coordinator.dispatch(claimed, fixture.attemptId.toString());

        assertEquals(List.of(fixture.attemptId.toString()), owner.dispatched);
        assertTrue(stranger.dispatched.isEmpty());
    }

    @Test
    @DisplayName("two adapters claiming one item is a composition error, not a race the order settles")
    void twoOwnersOfOneItemAreRefused() {
        storedGraphFixture();
        var coordinator = new ExecutionRecoveryCoordinator(authority(),
                List.of(new RecordingAdapter(true), new RecordingAdapter(true)));

        PendingWork claimed = claim();

        assertThrows(IllegalStateException.class, () -> coordinator.canDispatch(claimed),
                "either adapter could perform the effect, so registration order must not choose");
    }

    @Test
    @DisplayName("an empty registry declines everything and still classifies what it discovered")
    void anEmptyRegistryDeclinesWithoutRefusingToDiscover() {
        Fixture fixture = storedGraphFixture();
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of());

        assertFalse(coordinator.canDispatch(claim()),
                "a deployment that composes no continuation service sends nothing");
        List<RecoveryCandidate> classified = coordinator.classify(List.of(entryFor(fixture.key)));
        assertEquals(1, classified.size());
        assertTrue(classified.get(0).rehydratable(),
                "declining to dispatch is not the same claim as being unable to rebuild");
    }

    @Test
    @DisplayName("one unclassifiable instance does not hide the rest of the cohort")
    void aRefusedRowLeavesTheOthersVisible() {
        Fixture rebuildable = storedGraphFixture();
        Fixture broken = scheduleAttempt("22".repeat(32));
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of());

        List<RecoveryCandidate> classified = coordinator.classify(
                List.of(entryFor(broken.key), entryFor(rebuildable.key)));

        assertEquals(2, classified.size(), "a scan that abandoned itself on the bad row would report one");
        var refused = assertInstanceOf(RecoveryClassification.Refused.class,
                classified.get(0).classification());
        assertEquals(RecoveryClassification.Reason.DEFINITION_UNRESOLVED, refused.reason());
        assertFalse(refused.detail().isBlank(), "an operator acting on this needs to be told what failed");
        assertTrue(classified.get(1).rehydratable());
    }

    @Test
    @DisplayName("transient and deployment-hosted work are classified together, each keeping its origin")
    void bothHostingShapesAreClassifiedByTheSameAuthority() {
        Fixture hosted = storedGraphFixture(ExecutionOrigin.of("deployment-a", "workload-7", "req-1"));
        Fixture transientWork = storedGraphFixture(ExecutionOrigin.of(null, null, "req-2"));
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of());

        List<RecoveryCandidate> classified = coordinator.classify(
                List.of(entryFor(hosted.key), entryFor(transientWork.key)));

        assertTrue(classified.get(0).rehydratable());
        assertTrue(classified.get(1).rehydratable());
        assertEquals("deployment-a", classified.get(0).origin().deploymentId().orElseThrow());
        assertEquals("workload-7", classified.get(0).origin().workloadId().orElseThrow());
        assertTrue(classified.get(1).origin().deploymentId().isEmpty(),
                "a transient submission opens no deployment, and the cohort must not invent one for it");
        assertTrue(classified.get(1).origin().workloadId().isEmpty());
    }

    @Test
    @DisplayName("two instances pinned to one document parse it once")
    void oneDocumentIsParsedOncePerContentAddress() {
        Fixture first = storedGraphFixture();
        // Same pin, so the same content address: a second document is never stored.
        Fixture second = scheduleAttempt(first.pin);
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of());

        coordinator.classify(List.of(entryFor(first.key), entryFor(second.key)));

        assertEquals(1, definitions.loads,
                "the cache is keyed by content address, so a second instance on the same document "
                        + "must not re-read and re-parse it");
    }

    @Test
    @DisplayName("an ambiguous attempt this deployment will never rebuild parks once its delivery budget is spent")
    void aDeterministicRefusalIsBoundedAndEndsInAParkNamingTheDeploymentFault() {
        // The reviewer's probe shape: a pin naming a document that was never stored, which classifies
        // DEFINITION_UNRESOLVED and will classify identically on every later sweep.
        Fixture fixture = scheduleAttempt("33".repeat(32));
        driveToRunning(fixture);
        var adapter = new RecordingAdapter(true);
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of(adapter));
        // Two deliveries of budget, and the crash above already spent one: the sweep below is
        // delivery two and is inside the bound, the next is delivery three and is past it. Stated
        // rather than tuned, because a budget that happened to park on the first sweep would pass
        // this test while proving nothing about the grace period.
        var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                coordinator.declarations(), coordinator, 2);

        RecoveryOutcome first = recovery.sweepOnce().get(0);
        assertInstanceOf(RecoveryOutcome.Deferred.class, first,
                "inside the bound the item is withheld, which is the grace an operator gets to "
                        + "correct the deployment");
        assertEquals(NodeAttemptStatus.RUNNING, currentAttempt(fixture).status());

        clock.advance(TTL.plusSeconds(1));
        RecoveryOutcome second = recovery.sweepOnce().get(0);

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class, second,
                "withholding an effect that already happened, forever, is worse than the park it "
                        + "replaced: the park at least puts the decision in front of a human");
        assertEquals(NodeAttemptStatus.PARKED, currentAttempt(fixture).status());
        assertTrue(parked.cause().contains("cannot rebuild the execution"),
                () -> "the cause must name the deployment fault rather than report the attempt as "
                        + "though its author had declared nothing. Got: " + parked.cause());
        assertTrue(parked.cause().contains("no graph definition is stored"),
                () -> "and it must carry the classified reason through. Got: " + parked.cause());
        assertTrue(adapter.asked.isEmpty(), "unsafe dispatch stayed closed throughout");

        // Parked means out of the loop: a conforming adapter excludes it from the claim query.
        clock.advance(TTL.multipliedBy(10));
        assertTrue(await(store.claimPendingWork(TENANT, "recovery-2", 10, TTL)).isEmpty());
    }

    @Test
    @DisplayName("a refusal that waiting could clear consumes no delivery budget, so the effect is still redelivered")
    void aRetryableRefusalIsWithheldWithoutEverConsumingTheDeliveryBudget() {
        // The node declares recovery.repeatable and the catalog declares the property, so an admitted
        // classification can only ever re-dispatch. A park here therefore cannot be the merits branch
        // — it can only be the delivery limit, which is exactly the branch this test is about. The
        // previous version of this test could not tell the two apart and attributed the park to the
        // wrong one.
        Fixture fixture = declaringGraphFixture();
        driveToRunning(fixture);
        definitions.unavailable = true;
        var adapter = new RecordingAdapter(true);
        var coordinator = new ExecutionRecoveryCoordinator(declaringAuthority(), List.of(adapter));
        var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                coordinator.declarations(), coordinator, 2);

        for (int sweep = 0; sweep < 8; sweep++) {
            assertInstanceOf(RecoveryOutcome.Deferred.class, recovery.sweepOnce().get(0),
                    "a store that may answer later must not be treated as a deployment fault");
            clock.advance(TTL.plusSeconds(1));
        }
        assertEquals(NodeAttemptStatus.RUNNING, currentAttempt(fixture).status(),
                "eight deliveries past a budget of two, and still not parked");
        assertTrue(adapter.dispatched.isEmpty(), "and nothing was sent while the store was silent");

        definitions.unavailable = false;
        RecoveryOutcome afterRecovery = recovery.sweepOnce().get(0);

        assertInstanceOf(RecoveryOutcome.ReDispatched.class, afterRecovery,
                () -> "the effect is declared repeatable, so the instant the store answers it must be "
                        + "redelivered. A park here would mean the outage spent the budget and the "
                        + "limit cashed it in, which is the failure the withholding exists to "
                        + "prevent. Got: " + afterRecovery);
        assertEquals(List.of(fixture.attemptId.toString()), adapter.dispatched,
                "and under its original effect identity, so the redelivery dedupes rather than "
                        + "becoming a second effect");
        assertEquals(NodeAttemptStatus.RUNNING, currentAttempt(fixture).status());
    }

    @Test
    @DisplayName("the withheld mark is durable, so an outage that straddles a restart still costs no budget")
    void theWithheldMarkSurvivesAProcessThatDoesNotHoldIt() {
        Fixture fixture = declaringGraphFixture();
        driveToRunning(fixture);
        definitions.unavailable = true;
        var firstProcess = new ExecutionRecoveryCoordinator(declaringAuthority(), List.of(new RecordingAdapter(true)));
        var before = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-before", 10, TTL,
                firstProcess.declarations(), firstProcess, 2);
        for (int sweep = 0; sweep < 5; sweep++) {
            before.sweepOnce();
            clock.advance(TTL.plusSeconds(1));
        }
        assertTrue(currentAttempt(fixture).withheldThroughDelivery() >= 5,
                "the mark is in the aggregate, not in the sweeping process");

        // A second service with a different worker identity and no shared state: everything the
        // restarted process knows about the outage it reads back from the store.
        definitions.unavailable = false;
        var adapter = new RecordingAdapter(true);
        var afterRestart = new ExecutionRecoveryCoordinator(declaringAuthority(), List.of(adapter));
        RecoveryOutcome outcome = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-after",
                10, TTL, afterRestart.declarations(), afterRestart, 2).sweepOnce().get(0);

        assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcome,
                () -> "a process that had never seen the outage must still not charge it to the "
                        + "attempt's budget. Got: " + outcome);
        assertEquals(List.of(fixture.attemptId.toString()), adapter.dispatched);
    }

    @Test
    @DisplayName("a never-started attempt is withheld and never parked, however long the refusal lasts")
    void aNeverStartedAttemptIsNeverParkedForADeploymentFault() {
        Fixture fixture = scheduleAttempt("44".repeat(32));
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of(new RecordingAdapter(true)));
        var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                coordinator.declarations(), coordinator, 2);

        for (int sweep = 0; sweep < 4; sweep++) {
            assertInstanceOf(RecoveryOutcome.Deferred.class, recovery.sweepOnce().get(0));
            clock.advance(TTL.plusSeconds(1));
        }

        assertEquals(NodeAttemptStatus.SCHEDULED, currentAttempt(fixture).status(),
                "parking asks a human to adjudicate an effect, and this attempt provably never "
                        + "produced one; the aggregate refuses SCHEDULED -> PARKED for the same reason");
    }

    @Test
    @DisplayName("a due timer is not withheld by an execution this deployment cannot rebuild")
    void anExpiryTimerClosesItsWaitEvenOnAnUnrebuildableExecution() {
        Fixture fixture = scheduleAttempt("55".repeat(32));
        UUID timerId = UUID.randomUUID();
        StoredProcessInstance current = await(store.load(fixture.key));
        await(store.apply(ExecutionBatch.to(fixture.key)
                .expecting(RevisionExpectation.exactly(current.revision()))
                .scheduleTimer(new ai.ravenroot.api.persistence.TimerSchedule(timerId, clock.instant(),
                        fixture.traversalId, fixture.invocationId,
                        ai.ravenroot.api.persistence.OpaquePayload.empty("application/octet-stream")))
                .build()));
        var adapter = new TimerAdapter();
        var coordinator = new ExecutionRecoveryCoordinator(authority(), List.of(adapter));
        var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                coordinator.declarations(), coordinator);

        List<RecoveryOutcome> outcomes = recovery.sweepOnce();

        assertTrue(outcomes.stream().anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance),
                () -> "closing a durable wait rebuilds nothing -- the dispatcher commits store "
                        + "transitions under this claim's fence -- so gating it on a rebuild that "
                        + "never happens strands a task that can then never expire. Got: " + outcomes);
        assertEquals(List.of(timerId), adapter.dispatched);
    }

    // ------------------------------------------------------------------ fixture

    /** A catalog whose one type declares recovery.repeatable with the canonical shape. */
    private PinnedGraphRecoveryAuthority declaringAuthority() {
        var declaring = new ai.ravenroot.api.catalog.NodeTypeDescriptor("work", "Work", "General", "",
                "actor", false,
                List.of(ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(null)),
                Set.of("side-effect"));
        return new PinnedGraphRecoveryAuthority(store, definitions, null,
                behavior -> "work".equals(behavior) ? java.util.Optional.of(declaring)
                        : java.util.Optional.empty(),
                GraphExecutionLimits.DEFAULTS);
    }

    /** Stores a document whose node the author declared repeatable, and pins an execution to it. */
    private Fixture declaringGraphFixture() {
        CanonicalGraphMl canonical = canonicalDocument(
                ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.REPEATABLE);
        await(definitions.put(TENANT, GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical));
        return scheduleAttempt(canonical.contentId().value(), ExecutionOrigin.none());
    }

    private PinnedGraphRecoveryAuthority authority() {
        // No catalog entry declares anything here: this file is about the fail-closed gate and the
        // registry, and the declaration channel is asserted in PinnedGraphRestartRecoveryTest.
        return new PinnedGraphRecoveryAuthority(store, definitions, null,
                behavior -> java.util.Optional.empty(), GraphExecutionLimits.DEFAULTS);
    }

    private record Fixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID attemptId,
                           String pin) {
    }

    /** Stores a real document and pins an execution to its content address. */
    private Fixture storedGraphFixture() {
        return storedGraphFixture(ExecutionOrigin.none());
    }

    private Fixture storedGraphFixture(ExecutionOrigin origin) {
        CanonicalGraphMl canonical = canonicalDocument();
        await(definitions.put(TENANT, GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical));
        return scheduleAttempt(canonical.contentId().value(), origin);
    }

    private Fixture scheduleAttempt(String pin) {
        return scheduleAttempt(pin, ExecutionOrigin.none());
    }

    private Fixture scheduleAttempt(String pin, ExecutionOrigin origin) {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .recordOrigin(origin)
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin(pin)))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        return new Fixture(key, traversalId, invocationId, attemptId, pin);
    }

    private static CanonicalGraphMl canonicalDocument() {
        return canonicalDocument(null);
    }

    private static CanonicalGraphMl canonicalDocument(String declaration) {
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("work", NodeKind.BEHAVIOR, "work", declaration == null ? Map.of()
                        : Map.of(ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.NAME, declaration)),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
        try (var manager = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            manager.writeGraphMl(output);
            return CanonicalGraphMl.of(output.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ProcessInventoryEntry entryFor(ExecutionKey key) {
        return await(store.listProcessInstances(key.tenantId(), ProcessInventoryQuery.outstanding(50)))
                .items().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the inventory does not hold " + key));
    }

    /** Claims the attempt, writes RUNNING under the fence and abandons it -- a crash mid-dispatch. */
    private void driveToRunning(Fixture fixture) {
        PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-worker", 10, TTL)).get(0);
        StoredProcessInstance current = await(store.load(fixture.key));
        await(store.apply(ExecutionBatch.to(fixture.key)
                .expecting(RevisionExpectation.exactly(current.revision()))
                .fencedBy(claimed.fencingToken())
                .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId,
                        fixture.invocationId, fixture.attemptId, NodeAttemptStatus.RUNNING))
                .build()));
        clock.advance(TTL.plusSeconds(1));
    }

    private PendingWork claim() {
        return await(store.claimPendingWork(TENANT, "recovery-1", 1, TTL)).get(0);
    }

    private NodeAttempt currentAttempt(Fixture fixture) {
        return await(store.load(fixture.key)).state().traversals().get(fixture.traversalId)
                .invocations().get(fixture.invocationId).attempts().getLast();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /** A clock the test moves, so a lease lapses because time passed rather than because it was tidied. */
    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Counts document reads, so the parse cache's bound is asserted rather than asserted about.
     *
     * <p>A delegating decorator rather than a stub: every method it does not count must behave
     * exactly as the real store does, or a test passing against it would be reporting on the
     * decorator.</p>
     */
    private static final class CountingGraphDefinitionStore
            implements ai.ravenroot.api.persistence.GraphDefinitionStore {
        private final ai.ravenroot.api.persistence.GraphDefinitionStore delegate;
        private int loads;
        /** Flipped by a test to make the store answer "ask me later" rather than "never". */
        private boolean unavailable;

        private CountingGraphDefinitionStore(ai.ravenroot.api.persistence.GraphDefinitionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<ai.ravenroot.api.persistence.StoreCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public int maxDefinitionBytes() {
            return delegate.maxDefinitionBytes();
        }

        @Override
        public CompletionStage<ai.ravenroot.api.persistence.StoredGraphDefinition> put(String tenantId,
                GraphDefinitionIdentity identity, CanonicalGraphMl canonical) {
            return delegate.put(tenantId, identity, canonical);
        }

        @Override
        public CompletionStage<ai.ravenroot.api.persistence.StoredGraphDefinition> load(
                ai.ravenroot.api.persistence.GraphDefinitionKey key) {
            loads++;
            if (unavailable) {
                return java.util.concurrent.CompletableFuture.failedStage(
                        new ai.ravenroot.api.persistence.GraphDefinitionStoreException(
                                new ai.ravenroot.api.persistence.GraphDefinitionStoreFailure
                                        .Unavailable("the definition store is unreachable")));
            }
            return delegate.load(key);
        }

        @Override
        public CompletionStage<ai.ravenroot.api.persistence.StoredGraphDefinition> resolve(String tenantId,
                GraphDefinitionIdentity identity) {
            return delegate.resolve(tenantId, identity);
        }

        @Override
        public CompletionStage<Boolean> contains(ai.ravenroot.api.persistence.GraphDefinitionKey key) {
            return delegate.contains(key);
        }

        @Override
        public CompletionStage<Void> remove(ai.ravenroot.api.persistence.GraphDefinitionKey key) {
            return delegate.remove(key);
        }

        @Override
        public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
            return delegate.purgeUnreferencedDefinitions(tenantId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** Claims only timers, so a timer's disposition is observed apart from every other kind. */
    private static final class TimerAdapter implements RecoveryDispatcher {
        private final List<UUID> dispatched = new ArrayList<>();

        @Override
        public boolean canDispatch(PendingWork item) {
            return item instanceof PendingWork.TimerDue;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            dispatched.add(item.workItemId());
        }
    }

    /** Records whether it was consulted at all, which is what the fail-closed ordering is about. */
    private static final class RecordingAdapter implements RecoveryDispatcher {
        private final boolean accepts;
        private final List<UUID> asked = new ArrayList<>();
        private final List<String> dispatched = new ArrayList<>();

        private RecordingAdapter(boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        public boolean canDispatch(PendingWork item) {
            asked.add(item.workItemId());
            return accepts;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            dispatched.add(idempotencyKey);
        }
    }
}
