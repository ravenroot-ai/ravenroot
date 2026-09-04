package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.manifest.ExecutionManifestResolver;
import ai.ravenroot.core.manifest.ExecutionManifestService;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryCoordinator;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.PinnedGraphRecoveryAuthority;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An author's repeatability declaration survives the process that accepted the execution.
 *
 * <h2>Why this needs a real file and a real reopen</h2>
 * <p>The claim is that the declaration is recovered from <em>durable</em> state and from nothing
 * else. An in-memory double cannot distinguish "read back from a stored document" from "still in a
 * field of an object the test is holding": both halves would share the same live graph and the test
 * would pass without the document store being consulted at all. Everything the first half writes
 * goes through a real {@link SqliteExecutionStore} and a real {@link SqliteGraphDefinitionStore} to
 * a real file; both are then closed, and the second half opens new stores on the same path with no
 * shared state and, crucially, <strong>no graph in memory anywhere</strong>. The only channel
 * between the halves is the file.</p>
 *
 * <h2>What "the crash" is here</h2>
 * <p>The attempt is claimed, driven to {@code RUNNING} under the fence, and then abandoned: no
 * acknowledgement, no terminal transition, and the lease left to lapse on the store's own clock
 * rather than released. That is the ambiguous case — the effect was dispatched and its outcome was
 * never learned — which is the only case an author's declaration is consulted for.</p>
 */
class PinnedGraphRestartRecoveryTest {

    private static final String TENANT = "tenant-a";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("an ambiguous effect a node declared repeatable is redelivered under the same effect identity after a restart")
    void aRepeatableDeclarationIsReadBackFromTheStoredDocumentAndAuthorisesRedelivery(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.REPEATABLE, clock);

        try (ExecutionStore reopened = new SqliteExecutionStore(dir.resolve("restart.db"), clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(
                     dir.resolve("restart.db"), clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            assertEquals(NodeAttemptStatus.RUNNING, attemptIn(reopened, fixture).status(),
                    "the ambiguity must have survived: a restart that found this SCHEDULED or "
                            + "terminal would be testing a different case entirely");
            var dispatcher = new RecordingDispatcher();
            List<RecoveryOutcome> outcomes = sweep(reopened, documents, null, dispatcher, clock);

            var redispatched = assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcomes.get(0),
                    "the declaration is in the stored document and nowhere else; parking here would "
                            + "mean the document was never read");
            assertEquals(fixture.attemptId, redispatched.attemptId());
            assertEquals(List.of(fixture.attemptId.toString()), dispatcher.effectKeys,
                    "the effect identity is the attempt id, so the redelivery dedupes against the "
                            + "first delivery rather than becoming a second effect");
            assertEquals(NodeAttemptStatus.RUNNING, attemptIn(reopened, fixture).status(),
                    "a redelivery is visible in the delivery counter, not as a new attempt");
        }
    }

    @Test
    @DisplayName("an ambiguous effect a node declared not-repeatable parks after a restart and is never sent")
    void aNotRepeatableDeclarationParksTheSameAmbiguousAttempt(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.NOT_REPEATABLE, clock);

        try (ExecutionStore reopened = new SqliteExecutionStore(dir.resolve("restart.db"), clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(
                     dir.resolve("restart.db"), clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            var dispatcher = new RecordingDispatcher();
            List<RecoveryOutcome> outcomes = sweep(reopened, documents, null, dispatcher, clock);

            var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0));
            assertEquals(AttemptRepeatability.NOT_REPEATABLE, parked.declaration(),
                    "the refusal must be reported as the author's considered decision, not as silence");
            assertTrue(dispatcher.effectKeys.isEmpty());
            assertEquals(NodeAttemptStatus.PARKED, attemptIn(reopened, fixture).status());
        }
    }

    @Test
    @DisplayName("a repeatable value on a node whose type never declared the property still parks after a restart")
    void theCatalogRemainsTheOnlyRouteInAcrossTheDocumentRoundTrip(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        // The document carries recovery.repeatable=repeatable, and the catalog below is asked for a
        // type that declares nothing. A reader that looked the key up as a string would authorise
        // repeating an effect the catalog never sanctioned, and would look entirely reasonable.
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.REPEATABLE, clock);

        try (ExecutionStore reopened = new SqliteExecutionStore(dir.resolve("restart.db"), clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(
                     dir.resolve("restart.db"), clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            var dispatcher = new RecordingDispatcher();
            var authority = new PinnedGraphRecoveryAuthority(reopened, documents, null,
                    behavior -> Optional.empty(), GraphExecutionLimits.DEFAULTS);
            List<RecoveryOutcome> outcomes = sweepWith(reopened, authority, dispatcher, clock);

            var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0));
            assertEquals(AttemptRepeatability.UNDECLARED, parked.declaration());
            assertTrue(dispatcher.effectKeys.isEmpty());
            assertEquals(NodeAttemptStatus.PARKED, attemptIn(reopened, fixture).status());
        }
    }

    @Test
    @DisplayName("a manifest this deployment cannot reproduce withholds the declaration and dispatches nothing")
    void anIncompatibleManifestRefusesInsteadOfAuthorisingARepeat(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.REPEATABLE, clock);

        try (ExecutionStore reopened = new SqliteExecutionStore(dir.resolve("restart.db"), clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(
                     dir.resolve("restart.db"), clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE);
             var manifestStore = new InMemoryExecutionManifestStore(clock);
             var engine = new SameThreadExecutionEngine()) {
            // Pinned under the shipped limits, then verified by a deployment whose limits differ:
            // exactly one dimension disagrees, which is the shape a redeployed operator change has.
            var pinning = new ExecutionManifestService(manifestStore,
                    ExecutionManifestResolver.from(engine, reopened.capabilities(), sharedRegistry(),
                            UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS, null),
                    clock);
            pinning.pin(fixture.key, canonical(RecoveryRepeatabilityProperty.REPEATABLE).contentId(),
                    GraphDefinitionIdentity.forSubmission(
                            canonical(RecoveryRepeatabilityProperty.REPEATABLE).contentId()),
                    ExecutionPolicy.STANDARD);
            var verifying = new ExecutionManifestService(manifestStore,
                    ExecutionManifestResolver.from(engine, reopened.capabilities(), sharedRegistry(),
                            UnknownBehaviorPolicy.passThrough(), narrowedLimits(), null),
                    clock);

            var dispatcher = new RecordingDispatcher();
            List<RecoveryOutcome> outcomes = sweep(reopened, documents, verifying, dispatcher, clock);

            assertInstanceOf(RecoveryOutcome.Deferred.class, outcomes.get(0),
                    "an execution this deployment cannot reproduce is withheld, not parked: parking "
                            + "would spend a human decision on a deployment mistake");
            assertTrue(dispatcher.effectKeys.isEmpty());
            assertEquals(NodeAttemptStatus.RUNNING, attemptIn(reopened, fixture).status(),
                    "a refusal writes nothing at all");
        }
    }

    @Test
    @DisplayName("a completed effect is not repeated, even when the crash fell between the result commit and the acknowledgement")
    void anEffectThatFinishedIsNeverSentAgain(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.REPEATABLE, clock);
        Path database = dir.resolve("restart.db");

        // The effect finished and its result committed under the fence; the process then died before
        // acknowledging the claim, so the claim row is still outstanding.
        try (ExecutionStore committing = new SqliteExecutionStore(database, clock)) {
            StoredProcessInstance current = await(committing.load(fixture.key));
            await(committing.apply(ExecutionBatch.to(fixture.key)
                    .expecting(RevisionExpectation.exactly(current.revision()))
                    .fencedBy(fixture.fencingToken)
                    .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId,
                            fixture.invocationId, fixture.attemptId, NodeAttemptStatus.COMPLETED))
                    .build()));
        }

        try (ExecutionStore reopened = new SqliteExecutionStore(database, clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(database, clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            var dispatcher = new RecordingDispatcher();
            List<RecoveryOutcome> outcomes = sweep(reopened, documents, null, dispatcher, clock);

            assertTrue(dispatcher.effectKeys.isEmpty(),
                    "the declaration says the effect may be repeated; it must still not be, because "
                            + "this effect is not ambiguous — it is known to have completed");
            assertTrue(outcomes.stream().noneMatch(RecoveryOutcome.ReDispatched.class::isInstance));
            assertEquals(NodeAttemptStatus.COMPLETED, attemptIn(reopened, fixture).status());
        }
    }

    @Test
    @DisplayName("a pre-crash owner cannot commit after a recovery worker has taken the instance over")
    void aStaleOwnerIsFencedOutByTheWorkerThatTookOver(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.REPEATABLE, clock);
        Path database = dir.resolve("restart.db");

        try (ExecutionStore reopened = new SqliteExecutionStore(database, clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(database, clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            // Two workers sweep the same tenant. The first claims and advances; the second finds
            // nothing outstanding, because a claim is handed to one claimant.
            var first = new RecordingDispatcher();
            List<RecoveryOutcome> firstPass = sweep(reopened, documents, null, first, clock, "worker-a");
            var second = new RecordingDispatcher();
            List<RecoveryOutcome> secondPass = sweep(reopened, documents, null, second, clock, "worker-b");

            assertEquals(1, firstPass.size());
            assertTrue(secondPass.isEmpty(),
                    "two recovery workers must not both advance one process: the store hands the "
                            + "claim to exactly one of them");
            assertEquals(1, first.effectKeys.size());
            assertTrue(second.effectKeys.isEmpty());

            // The owner that died still holds the fencing token it was given before the crash. Its
            // write must be refused now that a later claim has advanced the fence.
            StoredProcessInstance current = await(reopened.load(fixture.key));
            ExecutionStoreException refused = assertThrows(ExecutionStoreException.class,
                    () -> await(reopened.apply(ExecutionBatch.to(fixture.key)
                            .expecting(RevisionExpectation.exactly(current.revision()))
                            .fencedBy(fixture.fencingToken)
                            .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId,
                                    fixture.invocationId, fixture.attemptId, NodeAttemptStatus.COMPLETED))
                            .build())),
                    "a stale owner that could still commit would let the crashed process complete an "
                            + "attempt the new owner is already re-driving");
            assertFalse(refused.failure().describe().isBlank());
        }
    }

    @Test
    @DisplayName("the withheld mark is written to the database and read back by a process that never saw the outage")
    void theWithheldMarkRoundTripsThroughARealCloseAndReopen(@TempDir Path dir) {
        var clock = new MovableClock(EPOCH);
        Fixture fixture = crashMidEffect(dir, RecoveryRepeatabilityProperty.REPEATABLE, clock);
        Path database = dir.resolve("restart.db");

        // First process: the document store is unreachable, so every sweep withholds and marks.
        try (ExecutionStore store = new SqliteExecutionStore(database, clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(database, clock,
                     ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            var unreachable = new UnreachableDefinitions(documents);
            var coordinator = new ExecutionRecoveryCoordinator(new PinnedGraphRecoveryAuthority(
                    store, unreachable, null, declaringCatalog(), GraphExecutionLimits.DEFAULTS),
                    List.of(new RecordingDispatcher()));
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "worker-before", 10, TTL,
                    coordinator.declarations(), coordinator, 2);
            for (int sweep = 0; sweep < 5; sweep++) {
                assertInstanceOf(RecoveryOutcome.Deferred.class, recovery.sweepOnce().get(0));
                clock.advance(TTL.plusSeconds(1));
            }
        }

        // Second process on the same file: nothing but the database connects the two halves, so the
        // mark it reads was written to the column rather than remembered.
        try (ExecutionStore reopened = new SqliteExecutionStore(database, clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(database, clock,
                     ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            assertTrue(attemptIn(reopened, fixture).withheldThroughDelivery() >= 5,
                    "the mark must have survived the process that wrote it, because the outage and "
                            + "the recovery routinely straddle a restart");
            var dispatcher = new RecordingDispatcher();
            var coordinator = new ExecutionRecoveryCoordinator(new PinnedGraphRecoveryAuthority(
                    reopened, documents, null, declaringCatalog(), GraphExecutionLimits.DEFAULTS),
                    List.of(dispatcher));
            RecoveryOutcome outcome = new ExecutionRecoveryService(reopened, List.of(TENANT),
                    "worker-after", 10, TTL, coordinator.declarations(), coordinator, 2)
                    .sweepOnce().get(0);

            assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcome,
                    () -> "a restarted process must not charge the outage to the attempt's budget. "
                            + "Got: " + outcome);
            assertEquals(List.of(fixture.attemptId.toString()), dispatcher.effectKeys);
        }
    }

    /** Delegates everything but the document read, which answers "ask me later". */
    private record UnreachableDefinitions(GraphDefinitionStore delegate) implements GraphDefinitionStore {
        @Override public java.util.Set<ai.ravenroot.api.persistence.StoreCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override public int maxDefinitionBytes() {
            return delegate.maxDefinitionBytes();
        }

        @Override public CompletionStage<ai.ravenroot.api.persistence.StoredGraphDefinition> put(
                String tenantId, GraphDefinitionIdentity identity, CanonicalGraphMl canonical) {
            return delegate.put(tenantId, identity, canonical);
        }

        @Override public CompletionStage<ai.ravenroot.api.persistence.StoredGraphDefinition> load(
                ai.ravenroot.api.persistence.GraphDefinitionKey key) {
            return java.util.concurrent.CompletableFuture.failedStage(
                    new ai.ravenroot.api.persistence.GraphDefinitionStoreException(
                            new ai.ravenroot.api.persistence.GraphDefinitionStoreFailure.Unavailable(
                                    "the definition store is unreachable")));
        }

        @Override public CompletionStage<ai.ravenroot.api.persistence.StoredGraphDefinition> resolve(
                String tenantId, GraphDefinitionIdentity identity) {
            return delegate.resolve(tenantId, identity);
        }

        @Override public CompletionStage<Boolean> contains(
                ai.ravenroot.api.persistence.GraphDefinitionKey key) {
            return delegate.contains(key);
        }

        @Override public CompletionStage<Void> remove(
                ai.ravenroot.api.persistence.GraphDefinitionKey key) {
            return delegate.remove(key);
        }

        @Override public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
            return delegate.purgeUnreferencedDefinitions(tenantId);
        }

        @Override public void close() {
            delegate.close();
        }
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID attemptId,
                           long fencingToken) {
    }

    /**
     * Writes the document, accepts an execution pinned to it, drives one attempt to {@code RUNNING}
     * under a claim's fence, and abandons everything — then closes both stores.
     *
     * <p>Nothing here is tidied on the way out beyond closing the stores, and that is the fixture
     * rather than an oversight: releasing the lease or ending the traversal would produce an orderly
     * shutdown, which is a strictly easier case with no ambiguity left to resolve.</p>
     */
    private Fixture crashMidEffect(Path dir, String declaration, MovableClock clock) {
        Path database = dir.resolve("restart.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        CanonicalGraphMl document = canonical(declaration);
        long fencingToken;
        try (ExecutionStore store = new SqliteExecutionStore(database, clock);
             GraphDefinitionStore documents = new SqliteGraphDefinitionStore(database, clock, ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE)) {
            await(documents.put(TENANT, GraphDefinitionIdentity.forSubmission(document.contentId()),
                    document));
            var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                    Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED,
                            Map.of())));
            StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(accepted,
                            new GraphVersionPin(document.contentId().value())))
                    .build()));
            StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationAdded(traversalId,
                            new NodeInvocation(invocationId, "work", Set.of(),
                                    NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                    .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                            NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                            new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                    .build()));
            PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-worker", 10, TTL)).get(0);
            fencingToken = claimed.fencingToken();
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(scheduled.revision()))
                    .fencedBy(fencingToken)
                    .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId,
                            attemptId, NodeAttemptStatus.RUNNING))
                    .build()));
        }
        // The dead worker's lease is left to lapse on the store's clock, as a crash leaves it.
        clock.advance(TTL.plusSeconds(1));
        return new Fixture(key, traversalId, invocationId, attemptId, fencingToken);
    }

    /** One node the catalog declares the property for, carrying the author's chosen value. */
    private static CanonicalGraphMl canonical(String declaration) {
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("work", NodeKind.BEHAVIOR, "work",
                        Map.of(RecoveryRepeatabilityProperty.NAME, declaration)),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
        try (var manager = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            manager.writeGraphMl(output);
            return CanonicalGraphMl.of(output.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A catalog whose one type declares {@code recovery.repeatable} with the canonical shape. */
    private static java.util.function.Function<String, Optional<NodeTypeDescriptor>> declaringCatalog() {
        var declaring = new NodeTypeDescriptor("work", "Work", "General", "", "actor", false,
                List.of(RecoveryRepeatabilityProperty.declaration(null)), Set.of("side-effect"));
        return behavior -> "work".equals(behavior) ? Optional.of(declaring) : Optional.empty();
    }

    /**
     * The registry the manifest resolver digests. Empty and shared by both resolvers below, so the
     * behaviour catalog is identical on the pinning and the verifying side and the single dimension
     * that disagrees is the one this test moves.
     */
    private static BehaviorRegistry sharedRegistry() {
        return new BehaviorRegistry();
    }

    /** The shipped limits with one bound moved, so exactly one manifest dimension disagrees. */
    private static GraphExecutionLimits narrowedLimits() {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        return new GraphExecutionLimits(defaults.graphMl(), defaults.payload(),
                defaults.maxFanOut() - 1, defaults.maxResidentActors(),
                defaults.maxLiveActorsPerTraversal(), defaults.maxInFlightHopsPerTraversal(),
                defaults.maxQueuedAdmissionsPerNode(), defaults.maxTraversalSteps(),
                defaults.maxAmplifiedDeliveries(), defaults.maxCumulativePayloadBytes(),
                defaults.maxRecoveryDeliveriesPerAttempt());
    }

    private List<RecoveryOutcome> sweep(ExecutionStore store, GraphDefinitionStore documents,
                                        ExecutionManifestService manifests,
                                        RecoveryDispatcher dispatcher, Clock clock) {
        return sweep(store, documents, manifests, dispatcher, clock, "recovery-after-restart");
    }

    private List<RecoveryOutcome> sweep(ExecutionStore store, GraphDefinitionStore documents,
                                        ExecutionManifestService manifests,
                                        RecoveryDispatcher dispatcher, Clock clock, String workerId) {
        var authority = new PinnedGraphRecoveryAuthority(store, documents, manifests,
                declaringCatalog(), GraphExecutionLimits.DEFAULTS);
        return sweepWith(store, authority, dispatcher, clock, workerId);
    }

    private List<RecoveryOutcome> sweepWith(ExecutionStore store, PinnedGraphRecoveryAuthority authority,
                                            RecoveryDispatcher dispatcher, Clock clock) {
        return sweepWith(store, authority, dispatcher, clock, "recovery-after-restart");
    }

    private List<RecoveryOutcome> sweepWith(ExecutionStore store, PinnedGraphRecoveryAuthority authority,
                                            RecoveryDispatcher dispatcher, Clock clock, String workerId) {
        var coordinator = new ExecutionRecoveryCoordinator(authority, List.of(dispatcher));
        return new ExecutionRecoveryService(store, List.of(TENANT), workerId, 10, TTL,
                coordinator.declarations(), coordinator).sweepOnce();
    }

    private static NodeAttempt attemptIn(ExecutionStore store, Fixture fixture) {
        return await(store.load(fixture.key)).state().traversals().get(fixture.traversalId)
                .invocations().get(fixture.invocationId).attempts().getLast();
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            if (failure != null) throw failure;
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            throw wrapped;
        }
    }

    /** Accepts every attempt and records the effect identity it was asked to present. */
    private static final class RecordingDispatcher implements RecoveryDispatcher {
        private final List<String> effectKeys = new ArrayList<>();

        @Override
        public boolean canDispatch(PendingWork item) {
            return item instanceof PendingWork.AttemptDispatch;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            effectKeys.add(idempotencyKey);
        }
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
            return java.time.ZoneOffset.UTC;
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
}
