package ai.ravenroot.core.approval;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ToolApprovalRestartIntegrationTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] ARGUMENTS = "{\"path\":\"/bounded\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECKPOINT = "agent-checkpoint-v1".getBytes(StandardCharsets.UTF_8);

    @Test
    void reopenKeepsWaitingThenDispatchesTheExactStoredContinuationOnce(@TempDir Path directory)
            throws Exception {
        Path database = directory.resolve("resume.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        ToolApprovalRegistration request = request(approvalId, traversalId, invocationId, attemptId);

        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            createRunning(store, key, traversalId, invocationId, attemptId);
            assertEquals(ToolApprovalResult.Code.CREATED,
                    new ToolApprovalService(store, CLOCK).request(key, request, "create").code());
        }

        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            assertEquals(ToolApprovalStatus.PENDING,
                    await(store.loadToolApproval(key, approvalId)).orElseThrow().status());
            assertEquals(0, await(store.claimPendingWork(TENANT, "before-decision", 10,
                    Duration.ofSeconds(30))).stream()
                    .filter(PendingWork.HandlerTrigger.class::isInstance).count(),
                    "restart alone must not resume a pending approval");
            assertEquals(ToolApprovalResult.Code.APPROVED,
                    new ToolApprovalService(store, CLOCK)
                            .approve(approver(), key.processInstanceId(), approvalId).code());
        }

        var executions = new AtomicInteger();
        var observed = new AtomicReference<ToolApprovalContinuation>();
        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            var approvals = new ToolApprovalService(store, CLOCK);
            ToolApprovalContinuationExecutor executor = new ToolApprovalContinuationExecutor() {
                @Override public boolean supports(String nodeId, int version) {
                    return "agent".equals(nodeId) && version == 1;
                }

                @Override public java.util.concurrent.CompletionStage<Boolean> execute(
                        ToolApprovalContinuation continuation) {
                    executions.incrementAndGet();
                    observed.set(continuation);
                    return CompletableFuture.completedFuture(true);
                }
            };
            ToolPolicy unchangedPolicy = invocation -> new ToolDecision(
                    ToolDecision.Disposition.REQUIRE_APPROVAL, "still governed", "policy-v1");
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "approval-recovery",
                    10, Duration.ofSeconds(30), RepeatabilityDeclarations.NONE_DECLARED,
                    new ToolApprovalHandlerDispatcher(store, approvals, unchangedPolicy, executor));
            assertInstanceOf(RecoveryOutcome.HandlerDispatched.class, recovery.sweepOnce().stream()
                    .filter(RecoveryOutcome.HandlerDispatched.class::isInstance).findFirst().orElseThrow());
            assertEquals(1, executions.get());
            assertArrayEquals(ARGUMENTS, observed.get().canonicalArguments());
            assertArrayEquals(CHECKPOINT, observed.get().checkpoint());
            assertEquals(request.requester(), observed.get().requester());
            assertEquals(request.graphVersionPin(), observed.get().graphVersionPin());
            assertEquals(ToolApprovalStatus.SUCCEEDED,
                    await(store.loadToolApproval(key, approvalId)).orElseThrow().status());
            recovery.sweepOnce();
            assertEquals(1, executions.get(), "acknowledged one-time continuation must not replay");
        }
    }

    @Test
    void shutdownPreservesWaitAndConsumedCrashBecomesIndeterminate(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("execution.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        ToolApprovalRegistration request = request(approvalId, traversalId, invocationId, attemptId);

        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            createRunning(store, key, traversalId, invocationId, attemptId);
            assertEquals(ToolApprovalResult.Code.CREATED,
                    new ToolApprovalService(store, CLOCK).request(key, request, "create").code());
        }

        UUID resumeTraversal;
        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            var service = new ToolApprovalService(store, CLOCK);
            var survived = await(store.loadToolApproval(key, approvalId)).orElseThrow();
            assertEquals(ToolApprovalStatus.PENDING, survived.status());
            assertArrayEquals(ARGUMENTS, survived.request().canonicalArguments());
            assertArrayEquals(CHECKPOINT, survived.request().continuation());
            ToolApprovalResult approved = service.approve(approver(), key.processInstanceId(), approvalId);
            assertEquals(ToolApprovalResult.Code.APPROVED, approved.code());
            resumeTraversal = approved.resumeTraversalId();
        }

        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            List<PendingWork> work = await(store.claimPendingWork(TENANT, "recovery-worker", 10,
                    Duration.ofSeconds(30)));
            PendingWork.HandlerTrigger trigger = work.stream()
                    .filter(PendingWork.HandlerTrigger.class::isInstance)
                    .map(PendingWork.HandlerTrigger.class::cast).findFirst().orElseThrow();
            assertEquals(resumeTraversal, trigger.traversalId());

            var service = new ToolApprovalService(store, CLOCK);
            NodeMessage exactOriginalScope = new NodeMessage(requesterIdentity(), key.processInstanceId(),
                    traversalId, invocationId, attemptId, "agent", null, Map.of());
            ToolApprovalResult redeemed = service.redeem(exactOriginalScope, approvalId, "filesystem.read",
                    ARGUMENTS, digest(ARGUMENTS), invocation -> new ToolDecision(
                            ToolDecision.Disposition.REQUIRE_APPROVAL, "still governed", "policy-v1"));
            assertEquals(ToolApprovalResult.Code.CONSUMED, redeemed.code());
            // Simulate process death before the external effect reports a terminal outcome.
        }

        try (ExecutionStore store = new SqliteExecutionStore(database, CLOCK)) {
            var service = new ToolApprovalService(store, CLOCK);
            assertEquals(1, service.markConsumedIndeterminate(key, "restart-recovery"));
            assertEquals(ToolApprovalStatus.INDETERMINATE,
                    await(store.loadToolApproval(key, approvalId)).orElseThrow().status());
            assertEquals(ToolApprovalResult.Code.ALREADY_SETTLED,
                    service.redeem(new NodeMessage(requesterIdentity(), key.processInstanceId(), traversalId,
                                    invocationId, attemptId, "agent", null, Map.of()),
                            approvalId, "filesystem.read", ARGUMENTS, digest(ARGUMENTS),
                            invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", ""))
                            .code(), "an ambiguous consumed grant is never automatically repeated");
            assertEquals(0, service.markConsumedIndeterminate(key, "restart-replay"));
        }
    }

    private static ToolApprovalRegistration request(UUID approvalId, UUID traversalId,
                                                    UUID invocationId, UUID attemptId) throws Exception {
        return new ToolApprovalRegistration(approvalId, traversalId, invocationId, attemptId,
                UUID.randomUUID(), "agent", "filesystem.read", ARGUMENTS, digest(ARGUMENTS),
                requesterIdentity(), new GraphVersionPin("graph-v1"), "policy-v1", NOW.plusSeconds(300),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), false, 1, CHECKPOINT,
                digest(CHECKPOINT));
    }

    private static void createRunning(ExecutionStore store, ExecutionKey key, UUID traversalId,
                                      UUID invocationId, UUID attemptId) {
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "agent", Set.of(), NodeInvocationStatus.RUNNING,
                List.of(attempt));
        var traversal = new Traversal(traversalId, "agent", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("graph-v1")))
                .build()));
    }

    private static RequestContext requester() {
        return new RequestContext("requester", "requester", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static SecurityContext requesterIdentity() { return SecurityContext.of(requester()); }

    private static RequestContext approver() {
        return new RequestContext("approver", "approver", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
