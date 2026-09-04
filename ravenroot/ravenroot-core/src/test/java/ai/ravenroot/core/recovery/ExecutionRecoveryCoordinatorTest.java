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

        assertFalse(coordinator.canDispatch(claimed));
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

    // ------------------------------------------------------------------ fixture

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
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("work", NodeKind.BEHAVIOR, "work", Map.of()),
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
