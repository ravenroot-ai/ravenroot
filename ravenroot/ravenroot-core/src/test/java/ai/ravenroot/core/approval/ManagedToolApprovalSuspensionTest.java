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
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ManagedToolApprovalSuspensionTest {
    @Test
    void managedApprovalSuspendsThroughTheLiveFenceAndReturnsOnlyTheCoreSignal() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (var store = new InMemoryExecutionStore(clock)) {
            var key = new ExecutionKey("tenant-a", UUID.randomUUID());
            UUID traversalId = UUID.randomUUID();
            UUID invocationId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
            var invocation = new NodeInvocation(invocationId, "agent", Set.of(),
                    NodeInvocationStatus.RUNNING, List.of(attempt));
            var traversal = new Traversal(traversalId, "agent", TraversalStatus.RUNNING,
                    Map.of(invocationId, invocation));
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                            ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                            new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join().revision();
            var approvalService = new ToolApprovalService(store, clock);
            var policyExecutionId = new AtomicReference<UUID>();
            var services = ManagedNodePackageServices.builder("test.package",
                            NodePackageEgressPolicy.builder().build(),
                            (packageId, tenantId, reference) -> java.util.Optional.empty())
                    .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                    .toolAuthorization(invocationRequest -> {
                                policyExecutionId.set(invocationRequest.executionId());
                                return new ToolDecision(ToolDecision.Disposition.REQUIRE_APPROVAL,
                                        "approval required", "policy-v1");
                            },
                            ToolCallAuditSink.discarding())
                    .durableToolApprovals(approvalService, new ToolApprovalSettings("policy-v1",
                            Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                    .build();
            var security = new SecurityContext("request", key.tenantId(), "requester",
                    PrincipalType.USER, "issuer");
            var message = new NodeMessage(security, key.processInstanceId(), traversalId, invocationId,
                    attemptId, Set.of(), "agent", Map.of(), Map.of());
            byte[] rawArguments = "{\"b\":2,\"a\":1}".getBytes(StandardCharsets.UTF_8);
            byte[] checkpoint = "bounded-checkpoint".getBytes(StandardCharsets.UTF_8);

            try (var recorder = ExecutionRecorder.open(store, key, "live", Duration.ofSeconds(30), revision);
                 var ignored = approvalService.bindLive(key, recorder)) {
                var authorization = services.toolAuthorization().authorize(message, "filesystem.read",
                        rawArguments);
                RuntimeException signal = authorization.suspend(1, checkpoint);

                DurableToolApprovalSuspension suspended = assertInstanceOf(
                        DurableToolApprovalSuspension.class, signal);
                var approval = store.loadToolApproval(key, suspended.approvalId())
                        .toCompletableFuture().join().orElseThrow();
                assertEquals(ToolApprovalStatus.PENDING, approval.status());
                assertEquals(ProcessInstanceStatus.WAITING,
                        store.load(key).toCompletableFuture().join().state().status());
                assertEquals(security, approval.request().requester());
                assertEquals(new GraphVersionPin("graph-v1"), approval.request().graphVersionPin());
                assertEquals(ToolApprovalRegistration.digest(checkpoint),
                        approval.request().continuationDigest());
                assertArrayEquals("{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8),
                        approval.request().canonicalArguments());
                assertEquals(key.processInstanceId(), policyExecutionId.get(),
                        "initial managed policy must receive the process instance, not traversal id");
            }
        }
    }
}
