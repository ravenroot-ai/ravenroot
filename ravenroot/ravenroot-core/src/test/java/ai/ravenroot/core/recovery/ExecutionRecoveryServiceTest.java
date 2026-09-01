package ai.ravenroot.core.recovery;

import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery loop's disposition of every case it can meet (PERS-04, ADR 0022).
 *
 * <p>The declaration source is injected rather than read from a graph, because the graph-borne
 * channel is not open: {@code ReservedGraphProperties} refuses every {@code ravenroot.*} key found in
 * graph content. The reader's contract is nonetheless the one that ships, and the wired production
 * source answers {@code UNDECLARED} for everything, which is the parking branch below.</p>
 */
class ExecutionRecoveryServiceTest {

    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void aScheduledAttemptIsProvablyEffectFreeSoItIsDispatchedWithoutParking() {
        Fixture fixture = scheduleAttempt();
        var dispatcher = new RecordingDispatcher(true);
        List<RecoveryOutcome> outcomes = serviceWith(RepeatabilityDeclarations.NONE_DECLARED, dispatcher)
                .sweepOnce();

        assertEquals(1, outcomes.size());
        assertInstanceOf(RecoveryOutcome.Dispatched.class, outcomes.get(0));
        assertEquals(1, dispatcher.sent.size());
        assertEquals(fixture.attemptId.toString(), dispatcher.sent.get(0).idempotencyKey,
                "the effect key is the attempt id: the unit of idempotency is the attempt");

        // The write-ordering invariant, asserted as an ordering and not merely as a final state:
        // the RUNNING transition must be durable before the dispatcher is ever called.
        assertEquals(NodeAttemptStatus.RUNNING, dispatcher.sent.get(0).statusWhenSent,
                "RUNNING must be committed before the send, or a crash during the send would be "
                        + "indistinguishable from work that never started");
        assertEquals(NodeAttemptStatus.RUNNING, currentAttempt(fixture).status());
    }

    @Test
    void recoveryDispatchPreservesThePersistedNamedCommand() {
        NodeCommand command = NodeCommand.application("correggi");
        Fixture fixture = scheduleAttempt(command);
        var dispatcher = new RecordingDispatcher(true);

        List<RecoveryOutcome> outcomes = serviceWith(RepeatabilityDeclarations.NONE_DECLARED, dispatcher)
                .sweepOnce();

        assertInstanceOf(RecoveryOutcome.Dispatched.class, outcomes.getFirst());
        assertEquals(command, dispatcher.sent.getFirst().command(),
                "recovery must deliver the command stored with the invocation, not reconstruct PROCESS");
    }

    @Test
    void anUndeclaredAmbiguousAttemptIsParkedAndLeavesTheClaimLoop() {
        Fixture fixture = scheduleAttempt();
        driveToRunning(fixture);

        List<RecoveryOutcome> outcomes =
                serviceWith(RepeatabilityDeclarations.NONE_DECLARED, new RecordingDispatcher(true)).sweepOnce();

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0));
        assertEquals(AttemptRepeatability.UNDECLARED, parked.declaration());
        NodeAttempt attempt = currentAttempt(fixture);
        assertEquals(NodeAttemptStatus.PARKED, attempt.status());
        assertFalse(attempt.parkCause().isBlank(), "the human resolving this needs a reason");

        // Acknowledged in the same sweep, so it is out of the loop and stays out.
        clock.advance(TTL.multipliedBy(10));
        assertTrue(await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL)).isEmpty());
    }

    @Test
    void aDeclaredNotRepeatableAmbiguousAttemptParksAndIsNeverSentAgain() {
        Fixture fixture = scheduleAttempt();
        driveToRunning(fixture);
        var dispatcher = new RecordingDispatcher(true);

        List<RecoveryOutcome> outcomes = serviceWith(declaredInCatalog("not-repeatable"), dispatcher)
                .sweepOnce();

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0));
        assertEquals(AttemptRepeatability.NOT_REPEATABLE, parked.declaration());
        assertTrue(dispatcher.sent.isEmpty(), "a not-repeatable effect is never repeated automatically");
    }

    /**
     * The structural guarantee, end to end: a graph that carries the well-known key on a node whose
     * <em>descriptor never declared it</em> parks anyway.
     *
     * <p>A raw string lookup on node data would answer {@code repeatable} here and repeat an effect
     * the catalog never sanctioned as repeatable — and it would look entirely reasonable while doing
     * it, which is why the accessor takes the descriptor rather than trusting a convention.</p>
     */
    @Test
    void aRepeatableValueOnANodeWhoseDescriptorNeverDeclaredItStillParks() {
        Fixture fixture = scheduleAttempt();
        driveToRunning(fixture);
        var dispatcher = new RecordingDispatcher(true);

        var undeclaring = new NodeTypeDescriptor("work", "Work", "General", "", "actor", false,
                List.of(NodePropertyDescriptor.optional("url", "URL", NodePropertyType.STRING, "", "")),
                Set.of());
        var node = new GraphNode("work", NodeKind.BEHAVIOR, "work",
                Map.of(RecoveryRepeatabilityProperty.NAME, "repeatable"));
        RepeatabilityDeclarations declarations =
                RepeatabilityDeclarations.fromGraph(List.of(node), behavior -> Optional.of(undeclaring));

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class,
                serviceWith(declarations, dispatcher).sweepOnce().get(0));
        assertEquals(AttemptRepeatability.UNDECLARED, parked.declaration());
        assertTrue(dispatcher.sent.isEmpty());
    }

    @Test
    void aDeclaredRepeatableAmbiguousAttemptIsSentAgainUnderItsOwnAttemptIdAndStaysRunning() {
        Fixture fixture = scheduleAttempt();
        driveToRunning(fixture);
        var dispatcher = new RecordingDispatcher(true);

        List<RecoveryOutcome> outcomes = serviceWith(declaredInCatalog("repeatable"), dispatcher)
                .sweepOnce();

        assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcomes.get(0));
        assertEquals(fixture.attemptId.toString(), dispatcher.sent.get(0).idempotencyKey,
                "the same attempt redelivered presents the same key, so the store deduplicates it");
        assertEquals(NodeAttemptStatus.RUNNING, currentAttempt(fixture).status(),
                "a redelivery is visible in the delivery counter, not in the aggregate");
        assertNull(currentAttempt(fixture).parkCause());
    }

    /**
     * The deviation from ADR 0022's written detection rule, pinned so it cannot be quietly undone.
     *
     * <p>The ADR phrases ambiguity as a <em>redelivery</em> of a RUNNING attempt. That is not
     * exhaustive: the primary submission path does not claim its work, so an attempt it drove to
     * RUNNING before a crash is claimed here for the first time, with {@code deliveryAttempt == 1},
     * and is every bit as ambiguous. Keying on the counter would re-dispatch exactly those.</p>
     */
    @Test
    void aRunningAttemptOnItsFirstDeliveryIsAmbiguousToo() {
        Fixture fixture = scheduleAttempt();
        // No claim precedes this: the RUNNING transition is written directly, as the primary path does.
        StoredProcessInstance running = await(store.apply(ExecutionBatch.to(fixture.key)
                .expecting(RevisionExpectation.exactly(fixture.stored.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId, fixture.invocationId,
                        fixture.attemptId, NodeAttemptStatus.RUNNING))
                .build()));
        assertEquals(NodeAttemptStatus.RUNNING, running.state().traversals().get(fixture.traversalId)
                .invocations().get(fixture.invocationId).attempts().getLast().status());

        var dispatcher = new RecordingDispatcher(true);
        List<RecoveryOutcome> outcomes =
                serviceWith(RepeatabilityDeclarations.NONE_DECLARED, dispatcher).sweepOnce();

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0),
                "a first-delivery RUNNING attempt was dispatched by somebody; it must not be repeated");
        assertTrue(parked.cause().contains("delivery 1"));
        assertTrue(dispatcher.sent.isEmpty());
    }

    @Test
    void withNoDispatcherNothingIsWrittenNothingIsAcknowledgedAndNothingIsLost() {
        Fixture fixture = scheduleAttempt();

        List<RecoveryOutcome> outcomes =
                serviceWith(RepeatabilityDeclarations.NONE_DECLARED, RecoveryDispatcher.NONE).sweepOnce();

        assertInstanceOf(RecoveryOutcome.Deferred.class, outcomes.get(0));
        assertEquals(NodeAttemptStatus.SCHEDULED, currentAttempt(fixture).status(),
                "writing RUNNING for work that could not be sent would manufacture the ambiguity this "
                        + "loop exists to rule out, and the next sweep would park work that never started");

        clock.advance(TTL.plusSeconds(1));
        assertEquals(1, await(store.claimPendingWork(TENANT, "recovery-1", 10, TTL)).size(),
                "unacknowledged work stays claimable, which is how at-least-once loses nothing");
    }

    @Test
    void aDeclarationSourceThatThrowsIsTreatedAsUndeclaredRatherThanAbortingTheSweep() {
        Fixture fixture = scheduleAttempt();
        driveToRunning(fixture);
        RepeatabilityDeclarations broken = nodeId -> {
            throw new IllegalStateException("catalog unavailable");
        };

        List<RecoveryOutcome> outcomes = serviceWith(broken, new RecordingDispatcher(true)).sweepOnce();

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(0));
        assertEquals(AttemptRepeatability.UNDECLARED, parked.declaration());
        assertEquals(NodeAttemptStatus.PARKED, currentAttempt(fixture).status());
    }

    @Test
    void onlyTheConfiguredTenantsAreSweptAndThePortGainsNoTenantEnumeration() {
        scheduleAttemptFor(new ExecutionKey("other-tenant", UUID.randomUUID()));

        List<RecoveryOutcome> outcomes = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1",
                10, TTL, RepeatabilityDeclarations.NONE_DECLARED, new RecordingDispatcher(true)).sweepOnce();

        assertTrue(outcomes.isEmpty(), "a tenant absent from configuration is not swept");
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A catalog-resolved snapshot: the node's type declares the property canonically and the instance
     * carries {@code value}. This is the wired route — descriptor first, instance value second.
     */
    private RepeatabilityDeclarations declaredInCatalog(String value) {
        var declaring = new NodeTypeDescriptor("work", "Work", "General", "", "actor", false,
                List.of(RecoveryRepeatabilityProperty.declaration(null)), Set.of("side-effect"));
        var node = new GraphNode("work", NodeKind.BEHAVIOR, "work",
                Map.of(RecoveryRepeatabilityProperty.NAME, value));
        return RepeatabilityDeclarations.fromGraph(List.of(node), behavior -> Optional.of(declaring));
    }

    private ExecutionRecoveryService serviceWith(RepeatabilityDeclarations declarations,
                                                 RecoveryDispatcher dispatcher) {
        return new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                declarations, dispatcher);
    }

    private record Fixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID attemptId,
                           StoredProcessInstance stored) {
    }

    private Fixture scheduleAttempt() {
        return scheduleAttempt(NodeCommand.PROCESS);
    }

    private Fixture scheduleAttempt(NodeCommand command) {
        return scheduleAttemptFor(new ExecutionKey(TENANT, UUID.randomUUID()), command);
    }

    private Fixture scheduleAttemptFor(ExecutionKey key) {
        return scheduleAttemptFor(key, NodeCommand.PROCESS);
    }

    private Fixture scheduleAttemptFor(ExecutionKey key, NodeCommand command) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1")))
                .build()));
        StoredProcessInstance stored = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), command)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        return new Fixture(key, traversalId, invocationId, attemptId, stored);
    }

    /** Claims the attempt, writes RUNNING under the fence and abandons it — a crash mid-dispatch. */
    private void driveToRunning(Fixture fixture) {
        PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-worker", 10, TTL)).get(0);
        await(store.apply(ExecutionBatch.to(fixture.key)
                .expecting(RevisionExpectation.exactly(fixture.stored.revision()))
                .fencedBy(claimed.fencingToken())
                .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId, fixture.invocationId,
                        fixture.attemptId, NodeAttemptStatus.RUNNING))
                .build()));
        clock.advance(TTL.plusSeconds(1));
    }

    private NodeAttempt currentAttempt(Fixture fixture) {
        return await(store.load(fixture.key)).state().traversals().get(fixture.traversalId)
                .invocations().get(fixture.invocationId).attempts().getLast();
    }

    private <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /** Records what it was asked to send, and what the stored status was at the moment of the send. */
    private final class RecordingDispatcher implements RecoveryDispatcher {
        private final boolean accepts;
        private final List<Sent> sent = new ArrayList<>();

        private RecordingDispatcher(boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        public boolean canDispatch(PendingWork item) {
            return accepts;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            var attemptItem = (PendingWork.AttemptDispatch) item;
            NodeAttemptStatus statusNow = await(store.load(item.key())).state()
                    .traversals().get(attemptItem.traversalId())
                    .invocations().get(attemptItem.invocationId())
                    .attempts().getLast().status();
            sent.add(new Sent(idempotencyKey, statusNow, attemptItem.command()));
        }
    }

    private record Sent(String idempotencyKey, NodeAttemptStatus statusWhenSent, NodeCommand command) {
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
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
}
