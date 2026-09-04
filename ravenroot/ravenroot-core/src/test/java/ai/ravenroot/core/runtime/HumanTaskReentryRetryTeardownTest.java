package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.NodeRetryProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.approval.ToolApprovalSettings;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for retry teardown when human-task re-entry suspends again. */
class HumanTaskReentryRetryTeardownTest {
    private static final String TENANT = "tenant-a";
    private static final String GRAPH_VERSION = "graph-v1";
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration LONG_BACKOFF = Duration.ofMinutes(10);
    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void nestedHumanTaskCancelsRetryBackoffBeforeDiscardingTheReentry(@TempDir Path directory)
            throws Exception {
        var retryResult = new CompletableFuture<NodeResult>();
        var nestedResult = new CompletableFuture<NodeResult>();
        var retryEntries = new AtomicInteger();
        var nestedSignal = new AtomicReference<DurableHumanTaskSuspension>();
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(directory.resolve("nested-human-retry.db"), CLOCK);
             var engine = new JoinTestEngine()) {
            long revision = createAcceptedReentry(store, key, traversalId);
            var tasks = new HumanTaskService(store, CLOCK);
            var behaviors = new BehaviorRegistry()
                    .register("resume", ignored -> CompletableFuture.completedFuture(
                            NodeResult.continueWith(null)))
                    .register("retry", ignored -> {
                        retryEntries.incrementAndGet();
                        return retryResult;
                    })
                    .register("nested-human", message -> {
                        HumanTaskResult created = tasks.suspend(message, humanTaskDefinition());
                        if (created.code() != HumanTaskResult.Code.CREATED) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "nested human task was not created: " + created.code()));
                        }
                        nestedSignal.set(new DurableHumanTaskSuspension(
                                created.task().request().taskId()));
                        return nestedResult;
                    });
            try (var manager = GraphManager.from(fanOutGraph("nested-human", LONG_BACKOFF));
                 var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor(),
                         ExecutionIdentitySource.randomUuids(), new ai.ravenroot.core.persistence.InMemoryJoinStore(),
                         CLOCK);
                 var recorder = ExecutionRecorder.open(store, key, "human-reentry", LEASE_TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                var execution = runner.executeAfterHumanTask(security(), key.processInstanceId(), traversalId,
                        "resume", GRAPH_VERSION, recorder, NodeResult.continueWith(Map.of()),
                        new GraphExecutionBudgetSnapshot(0, 0, 0, 1, 0))
                        .toCompletableFuture();

                await(() -> retryEntries.get() == 1 && nestedSignal.get() != null,
                        "both fan-out branches did not start");
                retryResult.completeExceptionally(new RetryableBlip());
                await(() -> runner.pendingBackoffCount(traversalId) == 1,
                        "the retry did not enter its long backoff");

                nestedResult.completeExceptionally(nestedSignal.get());
                ExecutionException suspended = assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND.toMillis(), TimeUnit.MILLISECONDS));
                assertInstanceOf(DurableHumanTaskSuspension.class, rootCause(suspended));
                assertEquals(0, runner.pendingBackoffCount(traversalId),
                        "discarding the re-entry must end its retry backoff promptly");
                assertEquals(0, runner.admissionGateCount());
                assertEquals(1, retryEntries.get(), "the cancelled retry must never enter again");
                assertEquals(HumanTaskStatus.WAITING,
                        store.listHumanTasks(TENANT,
                                ai.ravenroot.api.persistence.HumanTaskQuery.everything(10))
                                .toCompletableFuture().join()
                                .items().getFirst().status());
                assertEquals(ProcessInstanceStatus.WAITING,
                        store.load(key).toCompletableFuture().join().state().status());
            }
        }
    }

    @Test
    void nestedToolApprovalSealsRetryAtTheReleasedPauseGate(@TempDir Path directory)
            throws Exception {
        var retryResult = new CompletableFuture<NodeResult>();
        var nestedResult = new CompletableFuture<NodeResult>();
        var retryEntries = new AtomicInteger();
        var nestedSignal = new AtomicReference<DurableToolApprovalSuspension>();
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(directory.resolve("nested-tool-retry.db"), CLOCK);
             var engine = new JoinTestEngine()) {
            long revision = createAcceptedReentry(store, key, traversalId);
            var approvals = new ToolApprovalService(store, CLOCK);
            var services = ManagedNodePackageServices.builder("test.retry-teardown",
                            NodePackageEgressPolicy.builder().build(),
                            (packageId, tenantId, reference) -> Optional.empty())
                    .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                    .toolAuthorization(ignored -> new ToolDecision(
                                    ToolDecision.Disposition.REQUIRE_APPROVAL,
                                    "approval required", "policy-v1"),
                            ToolCallAuditSink.discarding())
                    .durableToolApprovals(approvals, new ToolApprovalSettings("policy-v1",
                            Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                    .build();
            var behaviors = new BehaviorRegistry()
                    .register("resume", ignored -> CompletableFuture.completedFuture(
                            NodeResult.continueWith(null)))
                    .register("retry", ignored -> {
                        retryEntries.incrementAndGet();
                        return retryResult;
                    })
                    .register("nested-tool", message -> {
                        RuntimeException signal = services.toolAuthorization()
                                .authorize(message, "filesystem.read",
                                        "{}".getBytes(StandardCharsets.UTF_8))
                                .suspend(1, "bounded-checkpoint".getBytes(StandardCharsets.UTF_8));
                        nestedSignal.set(assertInstanceOf(DurableToolApprovalSuspension.class, signal));
                        return nestedResult;
                    });
            try (var manager = GraphManager.from(fanOutGraph("nested-tool", Duration.ZERO));
                 var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor(),
                         ExecutionIdentitySource.randomUuids(), new ai.ravenroot.core.persistence.InMemoryJoinStore(),
                         CLOCK);
                 var recorder = ExecutionRecorder.open(store, key, "tool-reentry", LEASE_TTL, revision);
                 var binding = approvals.bindLive(key, recorder)) {
                var execution = runner.executeAfterHumanTask(security(), key.processInstanceId(), traversalId,
                        "resume", GRAPH_VERSION, recorder, NodeResult.continueWith(Map.of()),
                        new GraphExecutionBudgetSnapshot(0, 0, 0, 1, 0))
                        .toCompletableFuture();

                await(() -> retryEntries.get() == 1 && nestedSignal.get() != null,
                        "both fan-out branches did not start");
                assertTrue(runner.pauseTraversal(traversalId));
                retryResult.completeExceptionally(new RetryableBlip());
                await(() -> attemptCount(store, key, "retry") == 2,
                        "the scheduled retry did not reach the pause gate");
                assertEquals(0, runner.pendingBackoffCount(traversalId),
                        "zero backoff proves the retry is parked at the pause gate");

                nestedResult.completeExceptionally(nestedSignal.get());
                ExecutionException suspended = assertThrows(ExecutionException.class,
                        () -> execution.get(BOUND.toMillis(), TimeUnit.MILLISECONDS));
                assertInstanceOf(DurableToolApprovalSuspension.class, rootCause(suspended));
                assertEquals(1, retryEntries.get(),
                        "the released gate must refuse the retry before dispatching its effect");
                assertEquals(0, runner.pendingBackoffCount(traversalId));
                assertEquals(0, runner.admissionGateCount());
                assertFalse(runner.resumeTraversal(traversalId),
                        "the discarded traversal must leave no pause gate behind");
                assertEquals(1, store.toolApprovals(key).toCompletableFuture().join().size());
                assertEquals(ToolApprovalStatus.PENDING,
                        store.toolApprovals(key).toCompletableFuture().join().getFirst().status());
                assertEquals(ProcessInstanceStatus.WAITING,
                        store.load(key).toCompletableFuture().join().state().status());
            }
        }
    }

    private static GraphDefinition fanOutGraph(String nestedBehavior, Duration backoff) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("resume", "resume"),
                new GraphNode("retry", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "retry", Map.of(
                        NodeRetryProperty.MAX_ATTEMPTS, "3",
                        NodeRetryProperty.INITIAL_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                        NodeRetryProperty.MAX_BACKOFF, String.valueOf(backoff.toMillis()),
                        NodeRetryProperty.RETRY_ON, RetryableBlip.class.getSimpleName())),
                GraphNode.behavior("nested", nestedBehavior),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "resume"),
                GraphEdge.to("start", "end"),
                GraphEdge.to("resume", "retry"),
                GraphEdge.to("resume", "nested")));
    }

    private static HumanTaskDefinition humanTaskDefinition() {
        return new HumanTaskDefinition(
                new HumanTaskMetadata("Nested approval", "A bounded nested task."),
                new HumanTaskResponseSchema("application/vnd.ravenroot.payload+json",
                        "release.decision", "1", PayloadKind.MAP, 4096),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), Optional.empty(),
                Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"));
    }

    private static long createAcceptedReentry(ExecutionStore store, ExecutionKey key, UUID traversalId) {
        var traversal = new Traversal(traversalId, "resume", TraversalStatus.ACCEPTED, Map.of());
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversalId, traversal)),
                        new GraphVersionPin(GRAPH_VERSION))).build()).toCompletableFuture().join();
        return store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()).toCompletableFuture().join().revision();
    }

    private static int attemptCount(ExecutionStore store, ExecutionKey key, String nodeId) {
        return store.load(key).toCompletableFuture().join().state().traversals().values().stream()
                .flatMap(traversal -> traversal.invocations().values().stream())
                .filter(invocation -> nodeId.equals(invocation.nodeId()))
                .findFirst().map(invocation -> invocation.attempts().size()).orElse(0);
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        throw new AssertionError(failure);
    }

    private static SecurityContext security() {
        return new SecurityContext("request", TENANT, "requester", PrincipalType.USER, "issuer");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && (current instanceof ExecutionException
                || current instanceof java.util.concurrent.CompletionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static final class RetryableBlip extends RuntimeException {
        RetryableBlip() {
            super("retryable blip");
        }
    }
}
