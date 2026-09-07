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
import ai.ravenroot.api.node.service.AgentResourceRequest;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.persistence.AgentAuthorityControlState;
import ai.ravenroot.api.persistence.AgentAuthorityState;
import ai.ravenroot.api.persistence.AgentBudgetVector;
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
import ai.ravenroot.core.runtime.ExecutionRecorder;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuthorityBudgetSqliteIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void suspendedApprovalSurvivesStoreAndServiceRestartWithItsExactReservation() throws Exception {
        Path database = directory.resolve("approval-restart.db");
        Scenario scenario;
        UUID approvalId;
        UUID reservationId;
        byte[] checkpoint = "checkpoint-v1".getBytes(StandardCharsets.UTF_8);
        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            scenario = createScenario(store);
            var budgets = service(store, 1);
            try (var recorder = recorder(store, scenario.key(), "first");
                 var budgetBinding = budgets.bindLive(scenario.key(), recorder)) {
                var approvals = new ToolApprovalService(store, CLOCK, budgets);
                try (var approvalBinding = approvals.bindLive(scenario.key(), recorder)) {
                    var session = budgets.admit(scenario.message(), resources());
                    var managed = managed(budgets, approvals);
                    var authorization = managed.toolAuthorization().authorize(
                            scenario.message(), "alpha__search", new byte[]{'{', '}'});
                    var suspension = (DurableToolApprovalSuspension) authorization.suspend(1, checkpoint);
                    approvalId = suspension.approvalId();
                    session.suspend();
                    var budget = store.loadAgentAuthorityBudget(scenario.key())
                            .toCompletableFuture().join().orElseThrow();
                    reservationId = budget.reservations().values().iterator().next().reservationId();
                    assertEquals(AgentReservationState.HELD,
                            budget.reservations().get(reservationId).state());
                }
            }
        }

        try (var reopened = new SqliteExecutionStore(database, CLOCK)) {
            var restarted = service(reopened, 2);
            try (var recorder = recorder(reopened, scenario.key(), "restarted");
                 var budgetBinding = restarted.bindLive(scenario.key(), recorder)) {
                var approvals = new ToolApprovalService(reopened, CLOCK, restarted);
                try (var approvalBinding = approvals.bindLive(scenario.key(), recorder)) {
                    assertEquals(ToolApprovalStatus.PENDING, reopened.loadToolApproval(scenario.key(), approvalId)
                            .toCompletableFuture().join().orElseThrow().status());
                    approvals.approve(approver(), scenario.key().processInstanceId(), approvalId);
                    var approved = reopened.loadToolApproval(scenario.key(), approvalId)
                            .toCompletableFuture().join().orElseThrow();
                    var redemption = approvals.redeemStored(approved,
                            invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW,
                                    "allow", "policy-v1"), "recovery");
                    assertEquals(ToolApprovalStatus.CONSUMED, redemption.approval().status());
                    var request = approved.request();
                    var continuation = new ToolCallContinuationInput(
                            scenario.reentryMessage(), approvalId, request.traversalId(),
                            request.invocationId(), request.attemptId(), request.tool(),
                            request.canonicalArguments(), request.argumentsDigest(),
                            ToolCallContinuationInput.Decision.APPROVED, request.continuationVersion(),
                            checkpoint, request.continuationDigest());
                    var resumed = restarted.resume(continuation, resources());
                    var budget = reopened.loadAgentAuthorityBudget(scenario.key())
                            .toCompletableFuture().join().orElseThrow();
                    assertEquals(1, budget.reservations().size());
                    assertEquals(AgentReservationState.DISPATCHED,
                            budget.reservations().get(reservationId).state());
                    assertEquals(1, budget.root().bootEpoch(),
                            "ordinary restart must preserve the durable root rather than rewrite it");
                    resumed.suspend();
                }
            }
        }
    }

    @Test
    void twoStoreInstancesShareIdempotentKillEpochAndResetNeverRevivesOldWork() throws Exception {
        Path database = directory.resolve("global-control.db");
        try (var first = new SqliteExecutionStore(database, CLOCK);
             var second = new SqliteExecutionStore(database, CLOCK)) {
            Scenario original = createScenario(first);
            var serviceA = service(first, 1);
            var serviceB = service(second, 99);
            try (var recorder = recorder(first, original.key(), "original");
                 var ignored = serviceA.bindLive(original.key(), recorder)) {
                var session = serviceA.admit(original.message(), resources());
                var held = session.reserveModelTurn(1);

                var tripA = CompletableFuture.supplyAsync(() -> serviceA.trip(operator()));
                var tripB = CompletableFuture.supplyAsync(() -> serviceB.trip(operator()));
                assertEquals(1, tripA.get(10, TimeUnit.SECONDS));
                assertEquals(1, tripB.get(10, TimeUnit.SECONDS));
                assertEquals(AgentAuthorityControlState.KILLED,
                        first.loadAgentAuthorityControl().toCompletableFuture().join().state());
                assertEquals(1, first.loadAgentAuthorityControl().toCompletableFuture().join()
                        .teamActiveReleased());
                var killed = first.loadAgentAuthorityBudget(original.key())
                        .toCompletableFuture().join().orElseThrow();
                assertEquals(AgentAuthorityState.KILLED, killed.state());
                assertEquals(AgentReservationState.RELEASED,
                        killed.reservations().values().iterator().next().state());
                assertThrows(NodePackageServiceException.class, held::dispatch);

                var resetA = CompletableFuture.supplyAsync(() -> serviceA.reset(operator()));
                var resetB = CompletableFuture.supplyAsync(() -> serviceB.reset(operator()));
                assertEquals(2, resetA.get(10, TimeUnit.SECONDS));
                assertEquals(2, resetB.get(10, TimeUnit.SECONDS));
                assertThrows(NodePackageServiceException.class,
                        () -> serviceA.admit(original.message(), resources()));
            }

            Scenario fresh = createScenario(first);
            try (var recorder = recorder(first, fresh.key(), "fresh");
                 var ignored = serviceA.bindLive(fresh.key(), recorder)) {
                var admitted = serviceA.admit(fresh.message(), resources());
                assertEquals(2, first.loadAgentAuthorityBudget(fresh.key())
                        .toCompletableFuture().join().orElseThrow().controlEpoch());
                admitted.complete();
            }
        }

        try (var reopened = new SqliteExecutionStore(database, CLOCK)) {
            assertEquals(AgentAuthorityControlState.ACTIVE,
                    reopened.loadAgentAuthorityControl().toCompletableFuture().join().state());
            assertEquals(2, reopened.loadAgentAuthorityControl().toCompletableFuture().join().epoch());
            assertEquals(1, reopened.loadAgentAuthorityControl().toCompletableFuture().join()
                    .teamActiveReleased());
        }
    }

    @Test
    void tripRacingDispatchHasOneDurableTransactionOrder() throws Exception {
        Path database = directory.resolve("dispatch-race.db");
        try (var first = new SqliteExecutionStore(database, CLOCK);
             var second = new SqliteExecutionStore(database, CLOCK)) {
            Scenario scenario = createScenario(first);
            var serviceA = service(first, 1);
            var serviceB = service(second, 2);
            try (var recorder = recorder(first, scenario.key(), "race");
                 var ignored = serviceA.bindLive(scenario.key(), recorder)) {
                var session = serviceA.admit(scenario.message(), resources());
                var permit = session.reserveModelTurn(1);
                var gate = new CountDownLatch(1);
                var dispatch = CompletableFuture.runAsync(() -> {
                    await(gate);
                    try {
                        permit.dispatch();
                    } catch (RuntimeException refused) {
                        // Kill linearized first.
                    }
                });
                var kill = CompletableFuture.runAsync(() -> {
                    await(gate);
                    serviceB.trip(operator());
                });
                gate.countDown();
                CompletableFuture.allOf(dispatch, kill).get(10, TimeUnit.SECONDS);

                var budget = first.loadAgentAuthorityBudget(scenario.key())
                        .toCompletableFuture().join().orElseThrow();
                assertEquals(AgentAuthorityState.KILLED, budget.state());
                var state = budget.reservations().values().iterator().next().state();
                assertTrue(state == AgentReservationState.RELEASED
                                || state == AgentReservationState.INDETERMINATE,
                        "kill-first releases held work; dispatch-first is conservatively indeterminate");
            }
        }
    }

    @Test
    void changedPolicyRefusesConsumedBeforeApprovalReservationRevisionOrEffectChanges() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            Path database = directory.resolve("strict-" + legacy + ".db");
            Scenario scenario; UUID approvalId;
            byte[] checkpoint = "checkpoint-v1".getBytes(StandardCharsets.UTF_8);
            try (var store = new SqliteExecutionStore(database, CLOCK)) {
                scenario = createScenario(store); var original = service(store, 1);
                try (var recorder = recorder(store, scenario.key(), "original");
                     var binding = original.bindLive(scenario.key(), recorder)) {
                    var approvals = new ToolApprovalService(store, CLOCK, original);
                    try (var approvalBinding = approvals.bindLive(scenario.key(), recorder)) {
                        var session = original.admit(scenario.message(), resources());
                        var authorization = managed(original, approvals).toolAuthorization().authorize(
                                scenario.message(), "alpha__search", new byte[]{'{', '}'});
                        approvalId = ((DurableToolApprovalSuspension) authorization.suspend(1, checkpoint)).approvalId();
                        session.suspend();
                    }
                }
            }
            if (legacy) removePinsForLegacyFixture(database, scenario.key());
            try (var store = new SqliteExecutionStore(database, CLOCK)) {
                var changed = AgentAuthorityBudgetFingerprintTest.copy(policy(99), 9, 2L);
                var strict = new AgentAuthorityBudgetService(store, CLOCK, changed, AgentBudgetTelemetry.discarding());
                var approvals = new ToolApprovalService(store, CLOCK, strict);
                approvals.approve(approver(), scenario.key().processInstanceId(), approvalId);
                var approved = store.loadToolApproval(scenario.key(), approvalId).toCompletableFuture().join().orElseThrow();
                assertEquals(ToolApprovalStatus.APPROVED, approved.status());
                try (var recorder = recorder(store, scenario.key(), "strict");
                     var binding = strict.bindLive(scenario.key(), recorder)) {
                    var before = store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow();
                    long revision = store.load(scenario.key()).toCompletableFuture().join().revision();
                    int journal = store.readJournal(scenario.key().tenantId(), 0, 100).toCompletableFuture().join().size();
                    var effects = new java.util.concurrent.atomic.AtomicInteger();
                    var failure = assertThrows(NodePackageServiceException.class, () -> {
                        approvals.redeemStored(approved, invocation -> new ToolDecision(
                                ToolDecision.Disposition.ALLOW, "allow", "policy-v1"), "strict");
                        effects.incrementAndGet();
                    });
                    assertEquals(NodePackageServiceException.Reason.ADMISSION_REFUSED, failure.reason());
                    assertEquals(null, failure.getCause());
                    var request = approved.request();
                    assertThrows(NodePackageServiceException.class, () -> strict.hold(scenario.key(), request));
                    assertEquals(0, effects.get());
                    var unchanged = store.loadToolApproval(scenario.key(), approvalId).toCompletableFuture().join().orElseThrow();
                    assertEquals(approved.status(), unchanged.status());
                    assertEquals(approved.revision(), unchanged.revision());
                    assertEquals(approved.actor(), unchanged.actor());
                    assertEquals(approved.request().approvalId(), unchanged.request().approvalId());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(approved.request().canonicalArguments(), unchanged.request().canonicalArguments());
                    org.junit.jupiter.api.Assertions.assertArrayEquals(approved.request().continuation(), unchanged.request().continuation());
                    assertEquals(before, store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow());
                    assertEquals(revision, store.load(scenario.key()).toCompletableFuture().join().revision());
                    assertEquals(journal, store.readJournal(scenario.key().tenantId(), 0, 100).toCompletableFuture().join().size());
                    var release = strict.release(approved).orElseThrow();
                    recorder.record(List.of(), List.of(), List.of(release));
                    var cleaned = store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow();
                    assertEquals(before.pinnedRoot(), cleaned.pinnedRoot());
                    assertEquals(AgentReservationState.RELEASED, cleaned.budget().reservations().values().iterator().next().state());
                    strict.finishProcess(scenario.key(), false);
                    assertEquals(before.pinnedRoot(), store.loadAgentAuthorityBudgetSnapshot(scenario.key())
                            .toCompletableFuture().join().orElseThrow().pinnedRoot());

                }
            }
            try (var reopened = new SqliteExecutionStore(database, CLOCK)) {
                assertEquals(!legacy, reopened.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture()
                        .join().orElseThrow().pinnedRoot().isPresent());
            }
        }
    }

    @Test
    void driftAndLegacyKeepStoredSettlementIndeterminateAndGlobalCleanupAvailable() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            Path database = directory.resolve("cleanup-" + legacy + ".db");
            Scenario scenario;
            try (var store = new SqliteExecutionStore(database, CLOCK)) {
                scenario = createScenario(store); var original = service(store, 1);
                try (var recorder = recorder(store, scenario.key(), "original");
                     var binding = original.bindLive(scenario.key(), recorder)) {
                    var session = original.admit(scenario.message(), resources());
                    session.reserveModelTurn(1).dispatch(); session.suspend();
                }
            }
            if (legacy) removePinsForLegacyFixture(database, scenario.key());
            try (var store = new SqliteExecutionStore(database, CLOCK)) {
                var changed = AgentAuthorityBudgetFingerprintTest.copy(policy(2), 10, 300L);
                var strict = new AgentAuthorityBudgetService(store, CLOCK, changed, AgentBudgetTelemetry.discarding());
                var before = store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow();
                try (var recorder = recorder(store, scenario.key(), "cleanup");
                     var binding = strict.bindLive(scenario.key(), recorder)) {
                    assertThrows(NodePackageServiceException.class, () -> strict.admit(scenario.message(), resources()));
                    var reservations = List.copyOf(before.budget().reservations().values());
                    recorder.record(List.of(), List.of(), List.of(
                            new ai.ravenroot.api.persistence.AgentBudgetOperation.Settle(reservations.get(0).reservationId(),
                                    reservations.get(0).requested())));
                    var cleaned = store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow();
                    assertEquals(before.pinnedRoot(), cleaned.pinnedRoot());
                    assertEquals(160, cleaned.budget().spent().costMicros(), "accepted costs must not be repriced at new rate300");
                    strict.trip(operator()); strict.reset(operator());
                    assertEquals(before.pinnedRoot(), store.loadAgentAuthorityBudgetSnapshot(scenario.key())
                            .toCompletableFuture().join().orElseThrow().pinnedRoot());
                }
            }
        }
    }

    @Test
    void consumedContinuationRejectsDriftWithoutAliasAndItsCleanupHooksRemainUsable() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            for (boolean indeterminate : List.of(false, true)) {
                Path database = directory.resolve("consumed-" + legacy + "-" + indeterminate + ".db");
                Scenario scenario; UUID approvalId; byte[] checkpoint = new byte[]{1};
                try (var store = new SqliteExecutionStore(database, CLOCK)) {
                    scenario = createScenario(store); var original = service(store, 1);
                    var approvals = new ToolApprovalService(store, CLOCK, original);
                    try (var recorder = recorder(store, scenario.key(), "original");
                         var binding = original.bindLive(scenario.key(), recorder);
                         var approvalBinding = approvals.bindLive(scenario.key(), recorder)) {
                        var session = original.admit(scenario.message(), resources());
                        var authorization = managed(original, approvals).toolAuthorization().authorize(
                                scenario.message(), "alpha__search", new byte[]{'{', '}'});
                        approvalId = ((DurableToolApprovalSuspension) authorization.suspend(1, checkpoint)).approvalId();
                        session.suspend();
                    }
                    approvals.approve(approver(), scenario.key().processInstanceId(), approvalId);
                    var approved = store.loadToolApproval(scenario.key(), approvalId).toCompletableFuture().join().orElseThrow();
                    approvals.redeemStored(approved, invocation -> new ToolDecision(
                            ToolDecision.Disposition.ALLOW, "allow", "policy-v1"), "initial");
                }
                if (legacy) removePinsForLegacyFixture(database, scenario.key());
                try (var store = new SqliteExecutionStore(database, CLOCK)) {
                    var strict = new AgentAuthorityBudgetService(store, CLOCK,
                            AgentAuthorityBudgetFingerprintTest.copy(policy(2), 9, 2L), AgentBudgetTelemetry.discarding());
                    var approval = store.loadToolApproval(scenario.key(), approvalId).toCompletableFuture().join().orElseThrow();
                    assertEquals(ToolApprovalStatus.CONSUMED, approval.status());
                    var r = approval.request();
                    var continuation = new ToolCallContinuationInput(scenario.reentryMessage(), approvalId,
                            r.traversalId(), r.invocationId(), r.attemptId(), r.tool(), r.canonicalArguments(),
                            r.argumentsDigest(), ToolCallContinuationInput.Decision.APPROVED, r.continuationVersion(),
                            checkpoint, r.continuationDigest());
                    try (var recorder = recorder(store, scenario.key(), "cleanup");
                         var binding = strict.bindLive(scenario.key(), recorder)) {
                        var before = store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow();
                        long revision = recorder.revision();
                        assertThrows(NodePackageServiceException.class, () -> strict.resume(continuation, resources()));
                        for (String name : List.of("aliases", "sessions")) {
                            var field = AgentAuthorityBudgetService.class.getDeclaredField(name); field.setAccessible(true);
                            assertTrue(((Map<?, ?>) field.get(strict)).isEmpty(), "refused resume must not publish " + name);
                        }
                        assertEquals(revision, recorder.revision());
                        assertEquals(before, store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow());
                        var operation = (indeterminate ? strict.indeterminate(approval) : strict.settle(approval)).orElseThrow();
                        recorder.record(List.of(), List.of(), List.of(operation));
                        var after = store.loadAgentAuthorityBudgetSnapshot(scenario.key()).toCompletableFuture().join().orElseThrow();
                        assertEquals(before.pinnedRoot(), after.pinnedRoot());
                        assertEquals(indeterminate ? AgentReservationState.INDETERMINATE : AgentReservationState.SETTLED,
                                after.budget().reservations().values().iterator().next().state());
                        assertEquals(1, after.budget().spent().toolCalls());
                    }
                }
            }
        }
    }

    private static void removePinsForLegacyFixture(Path database, ExecutionKey key) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var read = connection.prepareStatement("SELECT aggregate FROM agent_authority_budget WHERE tenant_id=? AND process_instance_id=?")) {
            read.setString(1, key.tenantId()); read.setString(2, key.processInstanceId().toString());
            byte[] bytes;
            try (var rows = read.executeQuery()) { assertTrue(rows.next()); bytes = rows.getBytes(1); }
            byte[] marker = policy(1).policyFingerprint().getBytes(StandardCharsets.US_ASCII);
            int start = -1;
            for (int i = 4; i <= bytes.length - marker.length; i++) {
                if (java.util.Arrays.equals(marker, java.util.Arrays.copyOfRange(bytes, i, i + marker.length))) { start = i - 4; break; }
            }
            assertTrue(start > 4); assertEquals(2, java.nio.ByteBuffer.wrap(bytes).getInt());
            byte[] legacy = new byte[bytes.length - 136];
            System.arraycopy(bytes, 0, legacy, 0, start);
            System.arraycopy(bytes, start + 136, legacy, start, bytes.length - start - 136);
            java.nio.ByteBuffer.wrap(legacy).putInt(1);
            try (var update = connection.prepareStatement("UPDATE agent_authority_budget SET aggregate=? WHERE tenant_id=? AND process_instance_id=?")) {
                update.setBytes(1, legacy); update.setString(2, key.tenantId()); update.setString(3, key.processInstanceId().toString());
                assertEquals(1, update.executeUpdate());
            }
        }
    }

    private static Scenario createScenario(SqliteExecutionStore store) {
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        SecurityContext security = new SecurityContext("request", "tenant-a", "user",
                PrincipalType.USER, "issuer");
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "agent", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(attempt));
        var traversal = new Traversal(traversalId, "agent", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                                ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join();
        NodeMessage message = new NodeMessage(security, key.processInstanceId(), traversalId,
                invocationId, attemptId, Set.of(), "agent", null, Map.of());
        NodeMessage reentry = new NodeMessage(security, key.processInstanceId(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Set.of(), "agent", null, Map.of());
        return new Scenario(key, message, reentry);
    }

    private static ExecutionRecorder recorder(SqliteExecutionStore store, ExecutionKey key, String worker) {
        long revision = store.load(key).toCompletableFuture().join().revision();
        return ExecutionRecorder.open(store, key, worker, Duration.ofSeconds(30), revision);
    }

    private static AgentAuthorityBudgetService service(SqliteExecutionStore store, long bootEpoch) {
        return new AgentAuthorityBudgetService(store, CLOCK, policy(bootEpoch), AgentBudgetTelemetry.discarding());
    }

    private static ManagedNodePackageServices managed(AgentAuthorityBudgetService budgets,
                                                       ToolApprovalService approvals) {
        return ManagedNodePackageServices.builder("test.package", NodePackageEgressPolicy.builder().build(),
                        (packageId, tenantId, reference) -> Optional.empty())
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .grant(NodePackageCapability.AGENT_RESOURCES)
                .agentAuthorityBudgets(budgets)
                .toolAuthorization(invocation -> new ToolDecision(
                                ToolDecision.Disposition.REQUIRE_APPROVAL, "review", "policy-v1"),
                        ToolCallAuditSink.discarding())
                .durableToolApprovals(approvals, new ToolApprovalSettings("policy-v1", Duration.ofMinutes(5),
                        HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                .build();
    }

    private static AgentAuthorityBudgetPolicy policy(long bootEpoch) {
        return new AgentAuthorityBudgetPolicy("runtime-a", bootEpoch, "policy-v1", "rate-v1",
                "USD", Duration.ofHours(1),
                new AgentBudgetVector(10, 1_000, 1_000, 10_000, 10_000, 20, 4, 4, 4),
                100, 20, 1, 3, Set.of("data-a"), Set.of("runtime:delegate", "tool:use"));
    }

    private static AgentResourceRequest resources() {
        return new AgentResourceRequest(4, 1_000, 20, Duration.ofSeconds(1));
    }

    private static RequestContext operator() {
        return new RequestContext("control", "operator", PrincipalType.USER, "issuer", "platform",
                Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.agent.authority.control"));
    }

    private static RequestContext approver() {
        return new RequestContext("approval", "approver", PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.APPROVER), Set.of());
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("race did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record Scenario(ExecutionKey key, NodeMessage message, NodeMessage reentryMessage) {
    }
}
