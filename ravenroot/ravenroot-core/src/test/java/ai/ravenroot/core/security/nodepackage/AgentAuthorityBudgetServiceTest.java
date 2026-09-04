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
import ai.ravenroot.api.node.service.AgentResourceSession;
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
import ai.ravenroot.api.security.ToolCallAuditEvent;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
            assertEquals(AgentReservationState.HELD, held.state());
            assertEquals(new AgentBudgetVector(1, 100, 20, 1_000, 160, 0, 0, 0, 0),
                    held.requested());
            reservation.dispatch();
            held = fixture.budget().reservations().get(held.reservationId());
            assertEquals(AgentReservationState.DISPATCHED, held.state());

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
    void consecutiveModelTurnsReserveOnlyDurableRemainingTimeAndTokens() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding(), clock)) {
            var bounded = new AgentResourceRequest(4, 240, 20, Duration.ofSeconds(1));
            var session = fixture.budgets.admit(fixture.message, bounded);
            var first = session.reserveModelTurn(1);
            var firstStored = fixture.budget().reservations().values().iterator().next();
            assertEquals(new AgentBudgetVector(1, 100, 20, 1_000, 160, 0, 0, 0, 0),
                    firstStored.requested());
            first.dispatch();
            clock.advance(Duration.ofMillis(100));
            first.settle(Optional.of(100L), Optional.of(20L));
            session.suspend();

            var restarted = new AgentAuthorityBudgetService(fixture.store, clock, policy(1),
                    AgentBudgetTelemetry.discarding());
            try (var binding = restarted.bindLive(fixture.key, fixture.recorder)) {
                var retry = restarted.admit(fixture.message, bounded);
                var second = retry.reserveModelTurn(2);
                var secondStored = fixture.budget().reservations().values().stream()
                        .filter(reservation -> reservation.state() == AgentReservationState.HELD)
                        .findFirst().orElseThrow();
                assertEquals(new AgentBudgetVector(1, 100, 20, 900, 160, 0, 0, 0, 0),
                        secondStored.requested());
                assertEquals(120, secondStored.requested().inputTokens()
                        + secondStored.requested().outputTokens(),
                        "retry must reserve exactly the durable combined-token remainder");
                assertEquals(secondStored.requested().outputTokens(), second.maximumOutputTokens());
                assertEquals(secondStored.requested().elapsedMillis(), second.maximumDuration().toMillis());
                second.dispatch();
                clock.advance(Duration.ofMillis(200));
                second.settle(Optional.of(10L), Optional.of(5L));

                AgentBudgetVector spent = fixture.budget().spent();
                assertEquals(2, spent.turns());
                assertEquals(110, spent.inputTokens());
                assertEquals(25, spent.outputTokens());
                assertEquals(300, spent.elapsedMillis());
                assertEquals(185, spent.costMicros());
                var finalRemainder = retry.reserveModelTurn(3);
                var finalStored = fixture.budget().reservations().values().stream()
                        .filter(reservation -> reservation.state() == AgentReservationState.HELD)
                        .findFirst().orElseThrow();
                assertEquals(new AgentBudgetVector(1, 100, 5, 700, 115, 0, 0, 0, 0),
                        finalStored.requested());
                assertEquals(5, finalRemainder.maximumOutputTokens(),
                        "only an outbound-enforced output cap may consume the combined remainder");
                finalRemainder.release();
                clock.advance(Duration.ofMillis(700));
                assertThrows(NodePackageServiceException.class, () -> retry.reserveModelTurn(4));
                assertEquals(3, fixture.budget().reservations().size(),
                        "an expired deadline must not dispatch a zero-duration reservation");
                retry.complete();
            }
        }
    }

    @Test
    void localPreparationFailureReleasesHeldModelEconomicsWithoutDispatchSpend() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var session = fixture.budgets.admit(fixture.message, resources());
            var permit = session.reserveModelTurn(1);
            UUID reservationId = fixture.budget().reservations().keySet().iterator().next();

            permit.release();

            assertEquals(AgentReservationState.RELEASED,
                    fixture.budget().reservations().get(reservationId).state());
            AgentBudgetVector spent = fixture.budget().spent();
            assertEquals(0, spent.turns());
            assertEquals(0, spent.inputTokens());
            assertEquals(0, spent.outputTokens());
            assertEquals(0, spent.elapsedMillis());
            assertEquals(0, spent.costMicros());
            session.cancel();
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
    void concurrentRepeatedChildCreationRegistersAndReportsOneLogicalMember() throws Exception {
        var seen = new ArrayList<String>();
        AgentBudgetTelemetry telemetry = (dimension, outcome, amount) -> seen.add(
                dimension.name() + ':' + outcome.name() + ':' + amount);
        try (Fixture fixture = new Fixture(policy(1), telemetry)) {
            var parent = fixture.budgets.admit(fixture.message, resources());
            NodeMessage child = fixture.addChildMessage();
            var request = new AgentChildResourceRequest(child, Set.of("data-a"), Set.of(),
                    new AgentResourceRequest(3, 90, 10, Duration.ofMillis(900)));
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> createChild(parent, request, ready, start));
            var second = CompletableFuture.supplyAsync(() -> createChild(parent, request, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            AgentResourceSession firstSession = first.get(5, TimeUnit.SECONDS);
            AgentResourceSession secondSession = second.get(5, TimeUnit.SECONDS);

            assertSame(firstSession, secondSession);
            assertEquals(2, fixture.budget().grants().size());
            assertEquals(2, fixture.budget().spent().teamCumulative());
            assertEquals(2, fixture.budget().reserved().teamActive());
            assertEquals(2, seen.stream().filter("TEAM_CUMULATIVE:USED:1"::equals).count());
            assertEquals(2, seen.stream().filter("TEAM_ACTIVE:RESERVED:1"::equals).count());
            firstSession.complete();
            parent.complete();
        }
    }

    @Test
    void concurrentRepeatedRootAdmissionRegistersAndReportsOneLogicalMember() throws Exception {
        var seen = new ArrayList<String>();
        AgentBudgetTelemetry telemetry = (dimension, outcome, amount) -> seen.add(
                dimension.name() + ':' + outcome.name() + ':' + amount);
        try (Fixture fixture = new Fixture(policy(1), telemetry)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> admit(fixture, ready, start));
            var second = CompletableFuture.supplyAsync(() -> admit(fixture, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            AgentResourceSession firstSession = first.get(5, TimeUnit.SECONDS);
            AgentResourceSession secondSession = second.get(5, TimeUnit.SECONDS);
            assertSame(firstSession, secondSession);
            assertEquals(1, fixture.budget().grants().size());
            assertEquals(1, seen.stream().filter("TEAM_CUMULATIVE:USED:1"::equals).count());
            assertEquals(1, seen.stream().filter("TEAM_ACTIVE:RESERVED:1"::equals).count());
            firstSession.complete();
        }
    }

    @Test
    void completedParentPreservesCausalAttenuationWhileCancelledParentCannotResetAuthority() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            AgentResourceSession parent = fixture.budgets.admit(fixture.message, resources());
            parent.complete();
            NodeMessage child = fixture.addChildMessage();

            AgentResourceSession childSession = fixture.budgets.admit(child,
                    new AgentResourceRequest(3, 90, 10, Duration.ofMillis(900)));
            var budget = fixture.budget();
            assertEquals(2, budget.grants().size());
            assertEquals(2, budget.spent().teamCumulative());
            assertEquals(1, budget.reserved().teamActive());
            assertEquals(2, budget.grants().values().stream()
                    .mapToLong(grant -> grant.registration().depth()).max().orElseThrow());
            childSession.complete();
        }

        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            AgentResourceSession parent = fixture.budgets.admit(fixture.message, resources());
            parent.cancel();
            NodeMessage child = fixture.addChildMessage();
            assertThrows(NodePackageServiceException.class,
                    () -> fixture.budgets.admit(child, resources()));
            assertEquals(1, fixture.budget().grants().size(),
                    "a cancelled parent cannot turn a causal child into a fresh root grant");
        }
    }

    @Test
    void deterministicGrantReuseRequiresExactImmutableBindingAndSecurity() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            AgentResourceSession original = fixture.budgets.admit(fixture.message, resources());
            original.suspend();
            NodeMessage changedNode = new NodeMessage(fixture.security, fixture.key.processInstanceId(),
                    fixture.traversalId, fixture.invocationId, UUID.randomUUID(), Set.of(), "other", null,
                    Map.of());
            assertThrows(NodePackageServiceException.class,
                    () -> fixture.budgets.admit(changedNode, resources()));
            NodeMessage changedLineage = new NodeMessage(fixture.security, fixture.key.processInstanceId(),
                    fixture.traversalId, fixture.invocationId, UUID.randomUUID(), Set.of(UUID.randomUUID()),
                    "agent", null, Map.of());
            assertThrows(NodePackageServiceException.class,
                    () -> fixture.budgets.admit(changedLineage, resources()));
            SecurityContext changedRequest = new SecurityContext("different-request", "tenant-a", "user",
                    PrincipalType.USER, "issuer");
            NodeMessage changedSecurity = new NodeMessage(changedRequest, fixture.key.processInstanceId(),
                    fixture.traversalId, fixture.invocationId, UUID.randomUUID(), Set.of(), "agent", null,
                    Map.of());
            assertThrows(NodePackageServiceException.class,
                    () -> fixture.budgets.admit(changedSecurity, resources()));
            assertEquals(1, fixture.budget().grants().size());
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
            assertEquals(1, fixture.budget().controlEpoch(),
                    "reset must not rewrite or revive the killed root epoch");
            assertEquals(2, fixture.store.loadAgentAuthorityControl().toCompletableFuture().join().epoch(),
                    "trip and reset each advance the store-global control epoch");

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

            assertThrows(NodePackageServiceException.class,
                    () -> restarted.admit(fixture.addFreshTraversalMessage(), resources()),
                    "reset must not revive the killed process root");
        }
    }

    @Test
    void ordinaryRestartPreservesActiveApprovalAndExactHeldPermit() throws Exception {
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
            var redeemed = restartedApprovals.redeemStored(approved, invocation -> new ToolDecision(
                    ToolDecision.Disposition.ALLOW, "allow", "policy-v1"), "recovery");
            assertEquals(ToolApprovalStatus.CONSUMED, redeemed.approval().status());

            byte[] checkpoint = approved.request().continuation();
            var request = approved.request();
            var input = new ToolCallContinuationInput(fixture.messageWithInvocation(UUID.randomUUID()),
                    approvalId, request.traversalId(), request.invocationId(), request.attemptId(), request.tool(),
                    request.canonicalArguments(), request.argumentsDigest(),
                    ToolCallContinuationInput.Decision.APPROVED, request.continuationVersion(), checkpoint,
                    request.continuationDigest());
            var resumed = restarted.resume(input, resources());
            assertEquals(1, fixture.budget().root().bootEpoch(),
                    "boot identity is diagnostic and must not revoke durable authority");
            assertEquals(AgentReservationState.DISPATCHED,
                    fixture.budget().reservations().values().iterator().next().state());
            assertEquals(1, fixture.budget().grants().values().stream()
                    .filter(grant -> grant.state() == AgentGrantState.ACTIVE).count());
            resumed.suspend();
        }
    }

    @Test
    void retryAttemptsUseDistinctOperationsAndChargeOneLogicalGrantCumulatively() throws Exception {
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            var first = fixture.budgets.admit(fixture.message, resources());
            var firstPermit = first.reserveModelTurn(1);
            UUID firstReservation = fixture.budget().reservations().keySet().iterator().next();
            firstPermit.dispatch();
            firstPermit.settle(Optional.of(10L), Optional.of(5L));
            first.suspend();

            UUID retryAttempt = UUID.randomUUID();
            var retryMessage = fixture.messageWithAttempt(retryAttempt);
            var retry = fixture.budgets.admit(retryMessage, resources());
            var retryPermit = retry.reserveModelTurn(1);
            var reservations = fixture.budget().reservations();
            assertEquals(2, reservations.size());
            UUID retryReservation = reservations.keySet().stream()
                    .filter(id -> !id.equals(firstReservation)).findFirst().orElseThrow();
            assertTrue(!retryReservation.equals(firstReservation),
                    "attempt identity must distinguish the same ordinal on a retry");
            assertEquals(1, fixture.budget().grants().size(),
                    "retry must reuse the logical invocation grant");
            assertEquals(1, fixture.budget().spent().teamCumulative());
            assertEquals(1, fixture.budget().reserved().teamActive());
            retryPermit.release();
            retry.cancel();
        }
    }

    @Test
    void reportedUsageAboveReservationIsPersistedAsBreachAndRefusesFurtherEffects() throws Exception {
        var seen = new ArrayList<String>();
        AgentBudgetTelemetry telemetry = (dimension, outcome, amount) -> seen.add(
                dimension.name() + ':' + outcome.name() + ':' + amount);
        try (Fixture fixture = new Fixture(policy(1), telemetry)) {
            var session = fixture.budgets.admit(fixture.message, resources());
            var permit = session.reserveModelTurn(1);
            UUID reservationId = fixture.budget().reservations().keySet().iterator().next();
            permit.dispatch();
            NodePackageServiceException exhausted = assertThrows(NodePackageServiceException.class,
                    () -> permit.settle(Optional.of(Long.MAX_VALUE), Optional.of(20L)));
            assertEquals(NodePackageServiceException.Reason.BUDGET_EXHAUSTED, exhausted.reason());

            var budget = fixture.budget();
            var breached = budget.reservations().get(reservationId);
            assertEquals(AgentReservationState.BREACHED, breached.state());
            assertEquals(Long.MAX_VALUE, breached.actual().inputTokens(),
                    "the durable record must retain the provider-reported overage");
            assertEquals(Long.MAX_VALUE, breached.actual().costMicros(),
                    "overflowing reported cost must saturate visibly rather than normalize downward");
            assertEquals(Long.MAX_VALUE, budget.spent().inputTokens(),
                    "aggregate accounting must retain provider-authoritative overage");
            assertEquals(20, budget.spent().outputTokens());
            assertEquals(Long.MAX_VALUE, budget.spent().costMicros());
            assertEquals(AgentAuthorityState.CANCELLED, budget.state());
            assertEquals(AgentGrantState.EXHAUSTED,
                    budget.grants().values().iterator().next().state());
            assertEquals(0, budget.reserved().teamActive());
            assertThrows(NodePackageServiceException.class, () -> session.reserveModelTurn(2));
            assertTrue(seen.contains("TURNS:BREACHED:1"));
            assertTrue(seen.contains("INPUT_TOKENS:BREACHED:" + Long.MAX_VALUE));
            assertTrue(seen.contains("OUTPUT_TOKENS:BREACHED:20"));
            assertTrue(seen.contains("COST_MICROS:BREACHED:" + Long.MAX_VALUE));
            assertEquals(0, seen.stream().filter(value -> value.startsWith("INPUT_TOKENS:USED:")
                    || value.startsWith("OUTPUT_TOKENS:USED:")
                    || value.startsWith("COST_MICROS:USED:")).count(),
                    "one breached reservation must not also publish duplicate economic usage");
            assertEquals(1, seen.stream().filter("TEAM_ACTIVE:RELEASED:1"::equals).count());
        }
    }

    @Test
    void telemetrySurfaceCarriesOnlyFixedDimensionsOutcomesAndNumbers() throws Exception {
        var seen = new ArrayList<String>();
        AgentBudgetTelemetry telemetry = (dimension, outcome, amount) -> seen.add(
                dimension.name() + ':' + outcome.name() + ':' + amount);
        try (Fixture fixture = new Fixture(policy(1), telemetry)) {
            var session = fixture.budgets.admit(fixture.message, resources());
            var reservation = session.reserveModelTurn(1);
            reservation.dispatch();
            reservation.settle(Optional.of(2L), Optional.of(3L));
            session.complete();
        }
        assertTrue(seen.stream().allMatch(value -> value.matches("[A-Z_]+:[A-Z_]+:[0-9]+")));
        assertTrue(seen.stream().noneMatch(value -> value.contains("tenant-a")
                || value.contains("agent") || value.contains("data-a")));
    }

    @Test
    void truthfulEffectAuditSurvivesAccountingFailureAndCannotBeCompletedTwice() throws Exception {
        var events = new ArrayList<ToolCallAuditEvent>();
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            fixture.budgets.admit(fixture.message, resources());
            var managed = managedTools(fixture, ToolDecision.Disposition.ALLOW, events);
            var authorization = managed.toolAuthorization().authorize(fixture.message, "alpha__search",
                    "{}".getBytes(StandardCharsets.UTF_8));
            fixture.detachRecorder();

            NodePackageServiceException failure = assertThrows(NodePackageServiceException.class,
                    () -> authorization.complete(
                            ai.ravenroot.api.node.service.ToolCallAuthorization.Outcome.SUCCEEDED));
            assertEquals(NodePackageServiceException.Reason.EFFECT_OUTCOME_INDETERMINATE, failure.reason());
            authorization.complete(ai.ravenroot.api.node.service.ToolCallAuthorization.Outcome.FAILED);
            assertEquals(List.of(ToolCallAuditEvent.Disposition.ATTEMPT,
                            ToolCallAuditEvent.Disposition.SUCCEEDED),
                    events.stream().map(ToolCallAuditEvent::disposition).toList());
        }
    }

    @Test
    void refusalAuditSurvivesDeniedProposalAccountingFailure() throws Exception {
        var events = new ArrayList<ToolCallAuditEvent>();
        try (Fixture fixture = new Fixture(policy(1), AgentBudgetTelemetry.discarding())) {
            fixture.budgets.admit(fixture.message, resources());
            var managed = managedTools(fixture, ToolDecision.Disposition.DENY, events);
            fixture.detachRecorder();

            NodePackageServiceException failure = assertThrows(NodePackageServiceException.class,
                    () -> managed.toolAuthorization().authorize(fixture.message, "alpha__search",
                            "{}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE, failure.reason());
            assertEquals(List.of(ToolCallAuditEvent.Disposition.DENIED),
                    events.stream().map(ToolCallAuditEvent::disposition).toList());
        }
    }

    @Test
    void killRequiresBothPlatformRoleAndScopeAndIsRuntimeWide() throws Exception {
        var seen = new ArrayList<String>();
        AgentBudgetTelemetry telemetry = (dimension, outcome, amount) -> seen.add(
                dimension.name() + ':' + outcome.name() + ':' + amount);
        try (Fixture fixture = new Fixture(policy(1), telemetry)) {
            fixture.budgets.admit(fixture.message, resources());
            RequestContext nonAdmin = new RequestContext("control", "user", PrincipalType.USER, "issuer",
                    "tenant-a", Set.of(), Set.of("ravenroot.agent.authority.control"));
            RequestContext missingScope = new RequestContext("control", "operator", PrincipalType.USER, "issuer",
                    "tenant-a", Set.of(Role.PLATFORM_ADMIN), Set.of());
            assertThrows(SecurityException.class, () -> fixture.budgets.trip(nonAdmin));
            assertThrows(SecurityException.class, () -> fixture.budgets.trip(missingScope));
            assertEquals(AgentAuthorityState.ACTIVE, fixture.budget().state());
            assertEquals(0, fixture.budget().controlEpoch());

            RequestContext runtimeAdmin = new RequestContext("control", "operator", PrincipalType.USER,
                    "issuer", "operator-tenant", Set.of(Role.PLATFORM_ADMIN),
                    Set.of("ravenroot.agent.authority.control"));
            assertEquals(1, fixture.budgets.trip(runtimeAdmin));
            assertEquals(AgentAuthorityState.KILLED, fixture.budget().state());
            assertEquals(1, fixture.budget().controlEpoch());
            assertEquals(0, fixture.budget().reserved().teamActive());
            assertEquals(1, seen.stream().filter("TEAM_ACTIVE:RELEASED:1"::equals).count());
            assertEquals(1, fixture.store.loadAgentAuthorityControl().toCompletableFuture().join()
                    .teamActiveReleased());
        }
    }

    private static AgentResourceSession createChild(AgentResourceSession parent,
                                                     AgentChildResourceRequest request,
                                                     CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("child race did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return parent.createChild(request);
    }

    private static AgentResourceSession admit(Fixture fixture, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("admission race did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return fixture.budgets.admit(fixture.message, resources());
    }

    private static ai.ravenroot.api.node.service.NodePackageServices managedTools(
            Fixture fixture, ToolDecision.Disposition disposition, List<ToolCallAuditEvent> events) {
        return ManagedNodePackageServices.builder("test.package", NodePackageEgressPolicy.builder().build(),
                        (packageId, tenantId, reference) -> Optional.empty())
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .grant(NodePackageCapability.AGENT_RESOURCES)
                .agentAuthorityBudgets(fixture.budgets)
                .toolAuthorization(invocation -> new ToolDecision(disposition, "test", "policy-v1"),
                        events::add)
                .build();
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
        return new AgentResourceRequest(4, 1_000, 20, Duration.ofSeconds(1));
    }

    private static RequestContext operator() {
        return new RequestContext("control", "operator", PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.agent.authority.control"));
    }

    private static RequestContext approver() {
        return new RequestContext("approval", "approver", PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.APPROVER), Set.of());
    }

    private static final class Fixture implements AutoCloseable {
        private final InMemoryExecutionStore store;
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
            this(policy, telemetry, CLOCK);
        }

        private Fixture(AgentAuthorityBudgetPolicy policy, AgentBudgetTelemetry telemetry, Clock clock) {
            store = new InMemoryExecutionStore(clock);
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
            budgets = new AgentAuthorityBudgetService(store, clock, policy, telemetry);
            binding = budgets.bindLive(key, recorder);
        }

        private NodeMessage messageWithInvocation(UUID id) {
            return new NodeMessage(security, key.processInstanceId(), traversalId, id, attemptId,
                    Set.of(), "agent", null, Map.of());
        }

        private NodeMessage messageWithAttempt(UUID id) {
            return new NodeMessage(security, key.processInstanceId(), traversalId, invocationId, id,
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

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        private void advance(Duration amount) { now = now.plus(amount); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
