package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class StorageRecoveryRepeatabilityTest {
    private static final String TENANT = "tenant-a";
    private static final String LIST_NODE = "list-page";
    private static final String DELETE_NODE = "delete-object";
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test void realRecoverySweepRedispatchesDeclaredListButParksUndeclaredDelete() {
        MovableClock clock = new MovableClock();
        try (var store = new InMemoryExecutionStore(clock)) {
            BehaviorRegistry catalog = NodePackages.register(new BehaviorRegistry(), new StorageNodePackage(),
                    NodePackageServiceRegistry.builder().grant("ai.ravenroot.extensions.storage",
                            new StorageTestSupport.HttpDouble()).build());
            var listDescriptor = catalog.descriptor(ObjectListNodeBehavior.BEHAVIOR).orElseThrow();
            var deleteDescriptor = catalog.descriptor(ObjectDeleteNodeBehavior.BEHAVIOR).orElseThrow();
            assertTrue(RecoveryRepeatabilityProperty.declaredBy(listDescriptor));
            assertTrue(RecoveryRepeatabilityProperty.declaredBy(deleteDescriptor));
            assertEquals("", listDescriptor.properties().stream().filter(property ->
                    RecoveryRepeatabilityProperty.NAME.equals(property.name())).findFirst().orElseThrow().defaultValue());
            assertEquals("", deleteDescriptor.properties().stream().filter(property ->
                    RecoveryRepeatabilityProperty.NAME.equals(property.name())).findFirst().orElseThrow().defaultValue());

            Fixture list = ambiguousAttempt(store, clock, LIST_NODE);
            Fixture delete = ambiguousAttempt(store, clock, DELETE_NODE);
            Map<String, Object> repeat = Map.of(RecoveryRepeatabilityProperty.NAME,
                    RecoveryRepeatabilityProperty.REPEATABLE);
            RepeatabilityDeclarations declarations = RepeatabilityDeclarations.fromGraph(List.of(
                    new GraphNode(LIST_NODE, NodeKind.BEHAVIOR, ObjectListNodeBehavior.BEHAVIOR, repeat),
                    new GraphNode(DELETE_NODE, NodeKind.BEHAVIOR, ObjectDeleteNodeBehavior.BEHAVIOR, Map.of())),
                    catalog::descriptor);
            RecordingDispatcher dispatcher = new RecordingDispatcher();
            Map<ExecutionKey, RecoveryOutcome> outcomes = new HashMap<>();
            for (RecoveryOutcome outcome : new ExecutionRecoveryService(store, List.of(TENANT),
                    "replacement-runtime", 10, TTL, declarations, dispatcher).sweepOnce()) {
                outcomes.put(outcome.key(), outcome);
            }

            assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcomes.get(list.key));
            RecoveryOutcome.Parked parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(delete.key));
            assertEquals(AttemptRepeatability.UNDECLARED, parked.declaration());
            assertEquals(List.of(list.attemptId.toString()), dispatcher.sent);
        }
    }

    private static Fixture ambiguousAttempt(InMemoryExecutionStore store, MovableClock clock, String nodeId) {
        ExecutionKey key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID(), invocationId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        ProcessInstance accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1"))).build()));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId, new NodeInvocation(invocationId, nodeId,
                        Set.of(), NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED))).build()));
        PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-runtime", 10, TTL)).stream()
                .filter(item -> item.key().equals(key)).findFirst().orElseThrow();
        await(store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(scheduled.revision()))
                .fencedBy(claimed.fencingToken()).apply(new ExecutionTransition.AttemptTransitioned(
                        traversalId, invocationId, attemptId, NodeAttemptStatus.RUNNING)).build()));
        clock.advance(TTL.plusSeconds(1));
        return new Fixture(key, attemptId);
    }

    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private record Fixture(ExecutionKey key, UUID attemptId) { }
    private static final class RecordingDispatcher implements RecoveryDispatcher {
        private final List<String> sent = new ArrayList<>();
        @Override public boolean canDispatch(PendingWork item) { return true; }
        @Override public void dispatch(PendingWork item, String idempotencyKey) { sent.add(idempotencyKey); }
    }
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration amount) { now = now.plus(amount); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
