package ai.ravenroot.extensions.github;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class GithubRecoveryRepeatabilityTest {
    private static final String TENANT = "tenant-a";
    private static final String NODE = "watch-exact-commit";
    private static final Duration TTL = Duration.ofSeconds(30);
    @TempDir Path directory;

    @Test void authoredRepeatableWatchIsRedispatchedByTheRealRecoverySweep() {
        var clock = new MovableClock();
        try (var store = new InMemoryExecutionStore(clock)) {
            var nodePackage = GithubTestSupport.nodePackage(directory.resolve("github-operations.db"));
            var services = NodePackageServiceRegistry.builder().grant(GithubConfiguration.PACKAGE_ID,
                    new GithubTestSupport.HttpHarness()).build();
            BehaviorRegistry catalog = NodePackages.register(new BehaviorRegistry(), nodePackage, services);
            var descriptor = catalog.descriptor("github-workflow-watch").orElseThrow();
            assertTrue(RecoveryRepeatabilityProperty.declaredBy(descriptor));
            assertEquals("", descriptor.properties().stream().filter(property ->
                    RecoveryRepeatabilityProperty.NAME.equals(property.name())).findFirst().orElseThrow().defaultValue());

            Map<String, Object> properties = Map.of(RecoveryRepeatabilityProperty.NAME,
                    RecoveryRepeatabilityProperty.REPEATABLE);
            RepeatabilityDeclarations declarations = RepeatabilityDeclarations.fromGraph(List.of(
                    new GraphNode(NODE, NodeKind.BEHAVIOR, "github-workflow-watch", properties)), catalog::descriptor);
            Fixture fixture = ambiguousAttempt(store, clock);
            var dispatcher = new RecordingDispatcher();

            List<RecoveryOutcome> outcomes = new ExecutionRecoveryService(store, List.of(TENANT),
                    "recovery-worker", 10, TTL, declarations, dispatcher).sweepOnce();
            RecoveryOutcome.ReDispatched outcome = assertInstanceOf(RecoveryOutcome.ReDispatched.class,
                    outcomes.stream().filter(value -> value.key().equals(fixture.key)).findFirst().orElseThrow());
            assertEquals(fixture.attemptId, outcome.attemptId());
            assertEquals(List.of(fixture.attemptId.toString()), dispatcher.sent);
        }
    }

    private static Fixture ambiguousAttempt(InMemoryExecutionStore store, MovableClock clock) {
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
                .apply(new ExecutionTransition.InvocationAdded(traversalId, new NodeInvocation(invocationId, NODE,
                        Set.of(), NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED))).build()));
        PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-worker", 10, TTL)).stream()
                .filter(value -> value.key().equals(key)).findFirst().orElseThrow();
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
