package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.ToolCallContinuationInput;
import ai.ravenroot.api.node.service.AgentChildResourceRequest;
import ai.ravenroot.api.node.service.AgentResourceRequest;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.persistence.AgentAuthorityState;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.api.persistence.AgentGrantState;
import ai.ravenroot.api.persistence.AgentReservationState;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.approval.ToolApprovalSettings;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuthorityBudgetServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void modelUsageIsReservedBeforeDispatchAndMissingUsageSettlesFull() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var session = fixture.budgets.admit(fixture.message, resources());
            var reservation = session.reserveModelTurn(1);
            var held = fixture.budget().reservations().values().iterator().next();
            assertEquals(AgentReservationState.DISPATCHED, held.state());
            assertEquals(new AgentBudgetVector(1, 100, 20, 1_000, 160, 0, 0, 0, 0),
                    held.requested());

            reservation.settle(Optional.empty(), Optional.empty());

            var settled = fixture.budget().reservations().get(held.reservationId());
            assertEquals(AgentReservationState.SETTLED, settled.state());
            assertEquals(held.requested(), settled.actual());
            session.complete();
            assertEquals(AgentGrantState.EXHAUSTED,
                    fixture.budget().grants().values().iterator().next().state());
            assertEquals(0, fixture.budget().reserved().teamActive());
            assertEquals(1, fixture.budget().spent().teamCumulative());
        }
    }

    @Test
    void childPortRequiresDelegationAndStrictAttenuation() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var parent = fixture.budgets.admit(fixture.message, resources());
            NodeMessage child = fixture.addChildMessage();
            assertThrows(NodePackageServiceException.class, () -> parent.createChild(
                    new AgentChildResourceRequest(child, Set.of("data-a", "data-c"),
                            Set.of("runtime:delegate", "tool:use"), resources())));

            var childSession = parent.createChild(new AgentChildResourceRequest(child, Set.of("data-a"),
                    Set.of(), new AgentResourceRequest(3, 90, 10, Duration.ofMillis(900))));
            assertEquals(2, fixture.budget().spent().teamCumulative());
            assertEquals(2, fixture.budget().reserved().teamActive());
            childSession.complete();
            childSession.complete();
            assertEquals(2, fixture.budget().spent().teamCumulative());
            assertEquals(1, fixture.budget().reserved().teamActive());
        }
    }

    @Test
    void runtimeWithoutDelegationScopeStillAdmitsButRefusesChildren() throws Exception {
        AgentAuthorityBudgetPolicy noDelegation = new AgentAuthorityBudgetPolicy("runtime-a", 1,
                "policy-v1", "rate-v1", "USD", Duration.ofHours(1), maxima(), 100, 20, 1, 3,
                Set.of("data-a"), Set.of());
        try (Fixture fixture = new Fixture(noDelegation, AgentBudgetTelemetry.discarding())) {
            var parent = fixture.budgets.admit(fixture.message, resources());
            NodeMessage child = fixture.addChildMessage();
            assertThrows(NodePackageServiceException.class, () -> parent.createChild(
                    new AgentChildResourceRequest(child, Set.of(), Set.of(), resources())));
        }
    }

    @Test
    void approvalReentryDispatchesAndReusesTheExactHeldToolReservation() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var approvalService = new ToolApprovalService(fixture.store, CLOCK, fixture.budgets);
            try (var approvalBinding = approvalService.bindLive(fixture.key, fixture.recorder)) {
                var session = fixture.budgets.admit(fixture.message, resources());
                var managed = ManagedNodePackageServices.builder("test.package",
                                NodePackageEgressPolicy.builder().build(),
                                (packageId, tenantId, reference) -> Optional.empty())
                        .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                        .grant(NodePackageCapability.AGENT_RESOURCES)
                        .agentAuthorityBudgets(fixture.budgets)
                        .toolAuthorization(invocation -> new ToolDecision(
                                        ToolDecision.Disposition.REQUIRE_APPROVAL, "review", "policy-v1"),
                                ToolCallAuditSink.discarding())
                        .durableToolApprovals(approvalService, new ToolApprovalSettings("policy-v1",
                                Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                        .build();
                byte[] checkpoint = "checkpoint".getBytes(StandardCharsets.UTF_8);
                var authorization = managed.toolAuthorization().authorize(fixture.message, "alpha__search",
                        "{}".getBytes(StandardCharsets.UTF_8));
                var signal = (DurableToolApprovalSuspension) authorization.suspend(1, checkpoint);
                session.suspend();
                var held = fixture.budget().reservations().values().iterator().next();
                assertEquals(AgentReservationState.HELD, held.state());

                approvalService.approve(approver(), fixture.key.processInstanceId(), signal.approvalId());
                var approved = fixture.store.loadToolApproval(fixture.key, signal.approvalId())
                        .toCompletableFuture().join().orElseThrow();
                var consumed = approvalService.redeemStored(approved,
                        invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "allow", "policy-v1"),
                        "recovery");
                assertEquals(ToolApprovalStatus.CONSUMED, consumed.approval().status());
                var dispatched = fixture.budget().reservations().get(held.reservationId());
                assertEquals(held.reservationId(), dispatched.reservationId());
                assertEquals(AgentReservationState.DISPATCHED, dispatched.state());
                assertEquals(1, fixture.budget().spent().toolCalls());

                var request = consumed.approval().request();
                NodeMessage reentry = fixture.messageWithInvocation(UUID.randomUUID());
                var input = new ToolCallContinuationInput(reentry, request.approvalId(), request.traversalId(),
                        request.invocationId(), request.attemptId(), request.tool(), request.canonicalArguments(),
                        request.argumentsDigest(), ToolCallContinuationInput.Decision.APPROVED,
                        request.continuationVersion(), checkpoint, request.continuationDigest());
                var resumed = fixture.budgets.resume(input, resources());
                assertEquals(held.reservationId(), fixture.budget().reservations().values().iterator().next()
                        .reservationId(), "re-entry must never mint a second tool reservation");
                resumed.suspend();
            }
        }
    }

    @Test
    void tripResetAndRestartCannotReviveDetachedApprovalPermit() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var approvalService = new ToolApprovalService(fixture.store, CLOCK, fixture.budgets);
            UUID approvalId;
            try (var approvalBinding = approvalService.bindLive(fixture.key, fixture.recorder)) {
                var session = fixture.budgets.admit(fixture.message, resources());
                var managed = ManagedNodePackageServices.builder("test.package",
                                NodePackageEgressPolicy.builder().build(),
                                (packageId, tenantId, reference) -> Optional.empty())
                        .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                        .grant(NodePackageCapability.AGENT_RESOURCES)
                        .agentAuthorityBudgets(fixture.budgets)
                        .toolAuthorization(invocation -> new ToolDecision(
                                        ToolDecision.Disposition.REQUIRE_APPROVAL, "review", "policy-v1"),
                                ToolCallAuditSink.discarding())
                        .durableToolApprovals(approvalService, new ToolApprovalSettings("policy-v1",
                                Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                        .build();
                var authorization = managed.toolAuthorization().authorize(fixture.message, "alpha__search",
                        "{}".getBytes(StandardCharsets.UTF_8));
                RuntimeException signal = authorization.suspend(
                        1, "checkpoint".getBytes(StandardCharsets.UTF_8));
                assertTrue(signal.getClass().getSimpleName().contains("Suspension"));
                approvalId = ((DurableToolApprovalSuspension) signal).approvalId();
                session.suspend();
                assertEquals(AgentReservationState.HELD,
                        fixture.budget().reservations().values().iterator().next().state());
                assertEquals(AgentGrantState.ACTIVE,
                        fixture.budget().grants().values().iterator().next().state());
            }
            fixture.detachRecorder();
            fixture.budgets.trip(operator());
            assertEquals(AgentAuthorityState.KILLED, fixture.budget().state());
            assertEquals(AgentGrantState.CANCELLED,
                    fixture.budget().grants().values().iterator().next().state());
            assertEquals(AgentReservationState.RELEASED,
                    fixture.budget().reservations().values().iterator().next().state());
            fixture.budgets.reset(operator());
            assertEquals(AgentAuthorityState.KILLED, fixture.budget().state(),
                    "reset authorizes new admission but never revives an old root or grant");
            assertEquals(2, fixture.budget().controlEpoch(),
                    "trip and reset each advance the durable control epoch");

            var approval = fixture.store.loadToolApproval(fixture.key, approvalId)
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(ToolApprovalStatus.PENDING, approval.status());
            assertEquals(ai.ravenroot.core.approval.ToolApprovalResult.Code.APPROVED,
                    approvalService.approve(approver(), fixture.key.processInstanceId(), approvalId).code());
            approval = fixture.store.loadToolApproval(fixture.key, approvalId)
                    .toCompletableFuture().join().orElseThrow();
            var approved = approval;
            var restarted = new AgentAuthorityBudgetService(fixture.store, CLOCK, policy(2),
                    AgentBudgetTelemetry.discarding());
            long revision = fixture.store.load(fixture.key).toCompletableFuture().join().revision();
            fixture.recorder = ExecutionRecorder.open(fixture.store, fixture.key, "restart",
                    Duration.ofSeconds(30), revision);
            fixture.binding = restarted.bindLive(fixture.key, fixture.recorder);
            byte[] checkpoint = "checkpoint".getBytes(StandardCharsets.UTF_8);
            NodeMessage reentry = fixture.messageWithInvocation(UUID.randomUUID());
            var input = new ToolCallContinuationInput(reentry, approvalId,
                    approval.request().traversalId(), approval.request().invocationId(),
                    approval.request().attemptId(), approval.request().tool(),
                    approval.request().canonicalArguments(), approval.request().argumentsDigest(),
                    ToolCallContinuationInput.Decision.APPROVED, approval.request().continuationVersion(),
                    checkpoint, approval.request().continuationDigest());
            assertThrows(NodePackageServiceException.class, () -> restarted.resume(input, resources()),
                    "a new boot must not attach to the old detached approval grant");
            var restartedApprovals = new ToolApprovalService(fixture.store, CLOCK, restarted);
            assertThrows(NodePackageServiceException.class, () -> restartedApprovals.redeemStored(
                    approved, invocation -> new ToolDecision(
                            ToolDecision.Disposition.ALLOW, "allow", "policy-v1"),
                    "recovery"));
            assertEquals(ToolApprovalStatus.APPROVED, fixture.store.loadToolApproval(fixture.key, approvalId)
                    .toCompletableFuture().join().orElseThrow().status());
            assertEquals(AgentAuthorityState.KILLED, fixture.budget().state());

            restarted.reset(operator());
            var fresh = restarted.admit(fixture.addFreshTraversalMessage(), resources());
            assertEquals(AgentAuthorityState.ACTIVE, fixture.budget().state());
            assertEquals(2, fixture.budget().root().bootEpoch());
            assertEquals(1, fixture.budget().grants().values().stream()
                    .filter(grant -> grant.state() == AgentGrantState.ACTIVE).count());
            fresh.complete();
        }
    }

    @Test
    void newBootRefusesOldActiveApprovalAndRetiresItsPermitBeforeFreshAdmission() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var oldApprovals = new ToolApprovalService(fixture.store, CLOCK, fixture.budgets);
            UUID approvalId;
            try (var approvalBinding = oldApprovals.bindLive(fixture.key, fixture.recorder)) {
                var session = fixture.budgets.admit(fixture.message, resources());
                var managed = ManagedNodePackageServices.builder("test.package",
                                NodePackageEgressPolicy.builder().build(),
                                (packageId, tenantId, reference) -> Optional.empty())
                        .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                        .grant(NodePackageCapability.AGENT_RESOURCES)
                        .agentAuthorityBudgets(fixture.budgets)
                        .toolAuthorization(invocation -> new ToolDecision(
                                        ToolDecision.Disposition.REQUIRE_APPROVAL, "review", "policy-v1"),
                                ToolCallAuditSink.discarding())
                        .durableToolApprovals(oldApprovals, new ToolApprovalSettings("policy-v1",
                                Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                        .build();
                var authorization = managed.toolAuthorization().authorize(fixture.message, "alpha__search",
                        "{}".getBytes(StandardCharsets.UTF_8));
                var signal = (DurableToolApprovalSuspension) authorization.suspend(
                        1, "checkpoint".getBytes(StandardCharsets.UTF_8));
                approvalId = signal.approvalId();
                session.suspend();
            }
            fixture.detachRecorder();
            oldApprovals.approve(approver(), fixture.key.processInstanceId(), approvalId);
            var approved = fixture.store.loadToolApproval(fixture.key, approvalId)
                    .toCompletableFuture().join().orElseThrow();
            var restarted = new AgentAuthorityBudgetService(fixture.store, CLOCK, policy(2),
                    AgentBudgetTelemetry.discarding());
            long revision = fixture.store.load(fixture.key).toCompletableFuture().join().revision();
            fixture.recorder = ExecutionRecorder.open(fixture.store, fixture.key, "restart-active",
                    Duration.ofSeconds(30), revision);
            fixture.binding = restarted.bindLive(fixture.key, fixture.recorder);
            var restartedApprovals = new ToolApprovalService(fixture.store, CLOCK, restarted);
            assertThrows(NodePackageServiceException.class, () -> restartedApprovals.redeemStored(
                    approved, invocation -> new ToolDecision(
                            ToolDecision.Disposition.ALLOW, "allow", "policy-v1"), "recovery"));

            byte[] checkpoint = approved.request().continuation();
            var request = approved.request();
            var input = new ToolCallContinuationInput(fixture.messageWithInvocation(UUID.randomUUID()),
                    approvalId, request.traversalId(), request.invocationId(), request.attemptId(), request.tool(),
                    request.canonicalArguments(), request.argumentsDigest(),
                    ToolCallContinuationInput.Decision.APPROVED, request.continuationVersion(), checkpoint,
                    request.continuationDigest());
            assertThrows(NodePackageServiceException.class, () -> restarted.resume(input, resources()));

            var fresh = restarted.admit(fixture.addFreshTraversalMessage(), resources());
            assertEquals(2, fixture.budget().root().bootEpoch());
            assertEquals(AgentReservationState.RELEASED,
                    fixture.budget().reservations().values().iterator().next().state());
            assertEquals(1, fixture.budget().grants().values().stream()
                    .filter(grant -> grant.state() == AgentGrantState.ACTIVE).count());
            fresh.complete();
        }
    }

    @Test
    void telemetrySurfaceCarriesOnlyFixedDimensionsOutcomesAndNumbers() throws Exception {
        var seen = new ArrayList<String>();
        AgentBudgetTelemetry telemetry = (dimension, outcome, amount) -> seen.add(
                dimension.name() + ':' + outcome.name() + ':' + amount);
        try (Fixture fixture = new Fixture(policy(1), telemetry)) {
            var session = fixture.budgets.admit(fixture.message, resources());
            session.reserveModelTurn(1).settle(Optional.of(2L), Optional.of(3L));
            session.complete();
        }
        assertTrue(seen.stream().allMatch(value -> value.matches("[A-Z_]+:[A-Z_]+:[0-9]+")));
        assertTrue(seen.stream().noneMatch(value -> value.contains("tenant-a")
                || value.contains("agent") || value.contains("data-a")));
    }

    @Test
    void killRequiresBothPlatformRoleAndScopeAndIsRuntimeWide() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            fixture.budgets.admit(fixture.message, resources());
            RequestContext nonAdmin = new RequestContext("control", "user", PrincipalType.USER, "issuer",
                    "tenant-a", Set.of(), Set.of("agent:kill"));
            RequestContext missingScope = new RequestContext("control", "operator", PrincipalType.USER, "issuer",
                    "tenant-a", Set.of(Role.PLATFORM_ADMIN), Set.of());
            assertThrows(SecurityException.class, () -> fixture.budgets.trip(nonAdmin));
            assertThrows(SecurityException.class, () -> fixture.budgets.trip(missingScope));
            assertEquals(AgentAuthorityState.ACTIVE, fixture.budget().state());
            assertEquals(0, fixture.budget().controlEpoch());

            RequestContext runtimeAdmin = new RequestContext("control", "operator", PrincipalType.USER,
                    "issuer", "operator-tenant", Set.of(Role.PLATFORM_ADMIN), Set.of("agent:kill"));
            assertEquals(1, fixture.budgets.trip(runtimeAdmin));
            assertEquals(AgentAuthorityState.KILLED, fixture.budget().state());
            assertEquals(1, fixture.budget().controlEpoch());
        }
    }

    private static AgentAuthorityBudgetPolicy policy(long bootEpoch) {
        return new AgentAuthorityBudgetPolicy("runtime-a", bootEpoch, "policy-v1", "rate-v1",
                "USD", Duration.ofHours(1), maxima(), 100, 20, 1, 3,
                Set.of("data-a", "data-b"), Set.of("runtime:delegate", "tool:use"));
    }

    private static AgentBudgetVector maxima() {
        return new AgentBudgetVector(10, 1_000, 1_000, 10_000, 10_000, 20, 4, 4, 4);
    }

    private static AgentResourceRequest resources() {
        return new AgentResourceRequest(4, 100, 20, Duration.ofSeconds(1));
    }

    private static RequestContext operator() {
        return new RequestContext("control", "operator", PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.PLATFORM_ADMIN), Set.of("agent:kill"));
    }

    private static RequestContext approver() {
        return new RequestContext("approval", "approver", PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.APPROVER), Set.of());
    }

    private static final class Fixture implements AutoCloseable {
        private final InMemoryExecutionStore store = new InMemoryExecutionStore(CLOCK);
        private final ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        private final UUID traversalId = UUID.randomUUID();
        private final UUID invocationId = UUID.randomUUID();
        private final UUID attemptId = UUID.randomUUID();
        private final UUID childInvocationId = UUID.randomUUID();
        private final UUID childAttemptId = UUID.randomUUID();
        private final SecurityContext security = new SecurityContext("request", "tenant-a", "user",
                PrincipalType.USER, "issuer");
        private final NodeMessage message = messageWithInvocation(invocationId);
        private final AgentAuthorityBudgetService budgets;
        private ExecutionRecorder recorder;
        private AutoCloseable binding;

        private Fixture(AgentAuthorityBudgetPolicy policy, AgentBudgetTelemetry telemetry) {
            var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
            var invocation = new NodeInvocation(invocationId, "agent", Set.of(),
                    NodeInvocationStatus.RUNNING, List.of(attempt));
            var traversal = new Traversal(traversalId, "agent", TraversalStatus.RUNNING,
                    Map.of(invocationId, invocation));
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                            ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                            new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join().revision();
            recorder = ExecutionRecorder.open(store, key, "budget-test", Duration.ofSeconds(30), revision);
            budgets = new AgentAuthorityBudgetService(store, CLOCK, policy, telemetry);
            binding = budgets.bindLive(key, recorder);
        }

        private NodeMessage messageWithInvocation(UUID id) {
            return new NodeMessage(security, key.processInstanceId(), traversalId, id, attemptId,
                    Set.of(), "agent", null, Map.of());
        }

        private NodeMessage addChildMessage() {
            var childAttempt = new NodeAttempt(childAttemptId, 1, NodeAttemptStatus.RUNNING);
            var childInvocation = new NodeInvocation(childInvocationId, "child", Set.of(invocationId),
                    NodeInvocationStatus.RUNNING, List.of(childAttempt));
            recorder.record(List.of(new ExecutionTransition.InvocationAdded(traversalId, childInvocation)),
                    List.of());
            return new NodeMessage(security, key.processInstanceId(), traversalId, childInvocationId,
                    childAttemptId, Set.of(invocationId), "child", null, Map.of());
        }

        private NodeMessage addFreshTraversalMessage() {
            UUID freshTraversalId = UUID.randomUUID();
            UUID freshInvocationId = UUID.randomUUID();
            UUID freshAttemptId = UUID.randomUUID();
            var attempt = new NodeAttempt(freshAttemptId, 1, NodeAttemptStatus.RUNNING);
            var invocation = new NodeInvocation(freshInvocationId, "fresh", Set.of(),
                    NodeInvocationStatus.RUNNING, List.of(attempt));
            recorder.record(List.of(new ExecutionTransition.TraversalAdded(new Traversal(
                    freshTraversalId, "fresh", TraversalStatus.RUNNING,
                    Map.of(freshInvocationId, invocation)))), List.of());
            return new NodeMessage(security, key.processInstanceId(), freshTraversalId, freshInvocationId,
                    freshAttemptId, Set.of(), "fresh", null, Map.of());
        }

        private ai.ravenroot.api.persistence.DurableAgentAuthorityBudget budget() {
            return store.loadAgentAuthorityBudget(key).toCompletableFuture().join().orElseThrow();
        }

        private void detachRecorder() throws Exception {
            if (binding != null) {
                binding.close();
                binding = null;
            }
            if (recorder != null) {
                recorder.close();
                recorder = null;
            }
        }

        @Override public void close() throws Exception {
            detachRecorder();
            store.close();
        }
    }
}
