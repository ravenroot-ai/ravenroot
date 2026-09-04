package ai.ravenroot.core.approval;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.ToolCallContinuationAction;
import ai.ravenroot.api.node.ToolCallContinuationInput;
import ai.ravenroot.api.node.ToolCallContinuationResult;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedGraphToolApprovalPreflightTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] ARGUMENTS = "{\"path\":\"/bounded\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHECKPOINT = "checkpoint".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="agent"><data key="kind">BEHAVIOR</data><data key="behavior">probe</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="agent"/>
                <edge id="e2" source="agent" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void absentPinnedDefinitionDefersWithoutConsumingOrAcknowledging(@TempDir Path directory) throws Exception {
        assertDeferred(directory, new InMemoryGraphDefinitionStore(Clock.systemUTC()),
                new BehaviorRegistry(), "absent-pin", 1);
    }

    @Test
    void absentTrustedContinuationActionDefersWithoutConsumingOrAcknowledging(@TempDir Path directory)
            throws Exception {
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC());
        String pin = storeGraph(definitions);
        var behaviors = new BehaviorRegistry().register("probe",
                message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
        assertDeferred(directory, definitions, behaviors, pin, 1);
    }

    @Test
    void unsupportedCheckpointVersionDefersWithoutConsumingOrAcknowledging(@TempDir Path directory)
            throws Exception {
        var definitions = new InMemoryGraphDefinitionStore(Clock.systemUTC());
        String pin = storeGraph(definitions);
        BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(), new ProbePackage());
        assertDeferred(directory, definitions, behaviors, pin, 2);
    }

    @Test
    void laterApprovalCannotRelabelSucceededRedeemedEffect(@TempDir Path directory) throws Exception {
        assertLaterApprovalPreservesFirstOutcome(directory, true);
    }

    @Test
    void laterApprovalCannotRelabelFailedRedeemedEffect(@TempDir Path directory) throws Exception {
        assertLaterApprovalPreservesFirstOutcome(directory, false);
    }

    private static void assertLaterApprovalPreservesFirstOutcome(Path directory, boolean effectSucceeded)
            throws Exception {
        var clock = new MutableClock(NOW);
        var definitions = new InMemoryGraphDefinitionStore(clock);
        String pin = storeGraph(definitions);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        UUID invocation = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID firstApprovalId = UUID.randomUUID();
        try (ExecutionStore store = new SqliteExecutionStore(directory.resolve("chained.db"), clock);
             var engine = new InlineExecutionEngine()) {
            createRunning(store, key, traversal, invocation, attempt, pin);
            var approvals = new ToolApprovalService(store, clock);
            approvals.request(key, request(firstApprovalId, traversal, invocation, attempt, pin, 1),
                    "create");
            approvals.approve(approver(), key.processInstanceId(), firstApprovalId);
            var services = ManagedNodePackageServices.builder("test.chained-approval",
                            NodePackageEgressPolicy.builder().build(),
                            (packageId, tenantId, reference) -> java.util.Optional.empty())
                    .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                    .toolAuthorization(ignored -> new ToolDecision(
                                    ToolDecision.Disposition.REQUIRE_APPROVAL,
                                    "approval required", "policy-v1"),
                            ToolCallAuditSink.discarding())
                    .durableToolApprovals(approvals, new ToolApprovalSettings("policy-v1",
                            Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                    .build();
            BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(),
                    new ChainedApprovalPackage(effectSucceeded), NodePackageServiceRegistry.builder()
                            .grant("test.chained-approval", services).build());
            var executor = new PinnedGraphToolApprovalContinuationExecutor(definitions, store, approvals,
                    engine, behaviors, new ExecutionMonitor(),
                    ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                    "worker", Duration.ofSeconds(30));
            var dispatcher = new ToolApprovalHandlerDispatcher(store, approvals,
                    ignored -> new ToolDecision(ToolDecision.Disposition.REQUIRE_APPROVAL,
                            "unchanged", "policy-v1"), executor);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "worker", 10,
                    Duration.ofSeconds(30), RepeatabilityDeclarations.NONE_DECLARED,
                    dispatcher);

            List<RecoveryOutcome> outcomes = recovery.sweepOnce();
            assertTrue(outcomes.stream().anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance),
                    outcomes::toString);
            assertEquals(effectSucceeded ? ToolApprovalStatus.SUCCEEDED : ToolApprovalStatus.FAILED,
                    store.loadToolApproval(key, firstApprovalId).toCompletableFuture().join()
                            .orElseThrow().status());
            List<ai.ravenroot.api.persistence.DurableToolApproval> approvalsAfter =
                    store.toolApprovals(key).toCompletableFuture().join();
            assertEquals(2, approvalsAfter.size());
            var second = approvalsAfter.stream()
                    .filter(candidate -> !candidate.request().approvalId().equals(firstApprovalId))
                    .findFirst().orElseThrow();
            assertEquals(ToolApprovalStatus.PENDING, second.status());
            assertTrue(store.claimPendingWork(TENANT, "probe", 10, Duration.ofSeconds(30))
                    .toCompletableFuture().join().isEmpty(),
                    "the first trigger is acknowledged and a pending second approval has no trigger yet");
            assertTrue(store.leases(TENANT).toCompletableFuture().join().isEmpty(),
                    "the continuation lease is released only after recovery acknowledges its trigger");

            approvals.approve(approver(), key.processInstanceId(), second.request().approvalId());
            List<PendingWork> triggered = store.claimPendingWork(
                    TENANT, "probe", 10, Duration.ofSeconds(30)).toCompletableFuture().join();
            assertEquals(1, triggered.size());
            assertEquals(second.request().approvalId(), triggered.getFirst().workItemId(),
                    "only the newly resolved second approval becomes triggerable");
        }
    }

    private static void assertDeferred(Path directory, InMemoryGraphDefinitionStore definitions,
                                       BehaviorRegistry behaviors, String pin, int checkpointVersion)
            throws Exception {
        var clock = new MutableClock(NOW);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        UUID invocation = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        try (ExecutionStore store = new SqliteExecutionStore(directory.resolve("approval.db"), clock)) {
            createRunning(store, key, traversal, invocation, attempt, pin);
            var approvals = new ToolApprovalService(store, clock);
            approvals.request(key, request(approvalId, traversal, invocation, attempt,
                    pin, checkpointVersion), "create");
            approvals.approve(approver(), key.processInstanceId(), approvalId);
            var executor = new PinnedGraphToolApprovalContinuationExecutor(definitions, store, approvals,
                    unusedEngine(),
                    behaviors, new ExecutionMonitor(),
                    ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                    "worker", Duration.ofSeconds(30));
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "worker", 10,
                    Duration.ofSeconds(30), RepeatabilityDeclarations.NONE_DECLARED,
                    new ToolApprovalHandlerDispatcher(store, approvals,
                            ignored -> new ToolDecision(ToolDecision.Disposition.REQUIRE_APPROVAL,
                                    "unchanged", "policy-v1"), executor));

            assertTrue(recovery.sweepOnce().stream().anyMatch(RecoveryOutcome.Deferred.class::isInstance));
            assertEquals(ToolApprovalStatus.APPROVED,
                    store.loadToolApproval(key, approvalId).toCompletableFuture().join().orElseThrow().status());
            clock.now = NOW.plusSeconds(31);
            assertTrue(store.claimPendingWork(TENANT, "other-worker", 10, Duration.ofSeconds(30))
                    .toCompletableFuture().join().stream()
                    .anyMatch(PendingWork.HandlerTrigger.class::isInstance),
                    "failed preflight must leave the approval trigger unacknowledged");
        }
    }

    private static ai.ravenroot.api.execution.ExecutionEngine unusedEngine() {
        return (ai.ravenroot.api.execution.ExecutionEngine) Proxy.newProxyInstance(
                PinnedGraphToolApprovalPreflightTest.class.getClassLoader(),
                new Class<?>[] {ai.ravenroot.api.execution.ExecutionEngine.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("preflight must not invoke the execution engine");
                });
    }

    private static String storeGraph(InMemoryGraphDefinitionStore definitions) {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPH);
        return definitions.put(TENANT, GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                .toCompletableFuture().join().key().contentId().value();
    }

    private static ToolApprovalRegistration request(UUID approvalId, UUID traversal, UUID invocation,
                                                     UUID attempt, String pin, int checkpointVersion) {
        return new ToolApprovalRegistration(approvalId, traversal, invocation, attempt, UUID.randomUUID(),
                "agent", "filesystem.read", ARGUMENTS, ToolApprovalRegistration.digest(ARGUMENTS),
                requesterIdentity(), new GraphVersionPin(pin), "policy-v1", NOW.plusSeconds(300),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), false, checkpointVersion,
                CHECKPOINT, ToolApprovalRegistration.digest(CHECKPOINT));
    }

    private static void createRunning(ExecutionStore store, ExecutionKey key, UUID traversalId,
                                      UUID invocationId, UUID attemptId, String pin) {
        var attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        var invocation = new NodeInvocation(invocationId, "agent", Set.of(), NodeInvocationStatus.RUNNING,
                List.of(attempt));
        var traversal = new Traversal(traversalId, "agent", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin(pin))).build()).toCompletableFuture().join();
    }

    private static SecurityContext requesterIdentity() {
        return SecurityContext.of(new RequestContext("requester", "requester", PrincipalType.USER,
                "issuer", TENANT, Set.of(Role.APPROVER), Set.of()));
    }

    private static RequestContext approver() {
        return new RequestContext("approver", "approver", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static final class ProbePackage implements NodePackage {
        @Override public String id() { return "test.approval-probe"; }
        @Override public String version() { return "1"; }
        @Override public String sdkContract() { return NodeSdk.CONTRACT; }
        @Override public List<NodeBehavior> behaviors() { return List.of(new ProbeBehavior()); }
    }

    private record ChainedApprovalPackage(boolean effectSucceeded) implements NodePackage {
        @Override public String id() { return "test.chained-approval"; }
        @Override public String version() { return "1"; }
        @Override public String sdkContract() { return NodeSdk.CONTRACT; }
        @Override public List<NodeBehavior> behaviors() {
            return List.of(new ChainedApprovalBehavior(effectSucceeded));
        }
    }

    private record ChainedApprovalBehavior(boolean effectSucceeded) implements NodeBehavior {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("probe", "Probe", "Test", "", "actor", false,
                    List.of(), Set.of());
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public java.util.Optional<ToolCallContinuationAction> createToolCallContinuation(
                NodeConfiguration configuration, NodePackageServices services) {
            return java.util.Optional.of(new ToolCallContinuationAction() {
                @Override public void validate(ToolCallContinuationInput input) {
                    if (input.version() != 1) throw new IllegalArgumentException("unsupported checkpoint");
                }
                @Override public CompletionStage<ToolCallContinuationResult> resume(
                        ToolCallContinuationInput input) {
                    var continuation = new CompletableFuture<NodeResult>();
                    var exactEffect = new CompletableFuture<ToolCallContinuationResult>();
                    CompletableFuture.runAsync(() -> {
                        exactEffect.complete(new ToolCallContinuationResult(
                                continuation, effectSucceeded));
                        try {
                            RuntimeException suspended = services.toolAuthorization()
                                    .authorize(input.message(), "filesystem.read", ARGUMENTS)
                                    .suspend(1, CHECKPOINT);
                            continuation.completeExceptionally(suspended);
                        } catch (RuntimeException failure) {
                            continuation.completeExceptionally(failure);
                        }
                    });
                    return exactEffect;
                }
            });
        }
    }

    private static final class ProbeBehavior implements NodeBehavior {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("probe", "Probe", "Test", "", "actor", false,
                    List.of(), Set.of());
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public java.util.Optional<ToolCallContinuationAction> createToolCallContinuation(
                NodeConfiguration configuration, NodePackageServices services) {
            return java.util.Optional.of(new ToolCallContinuationAction() {
                @Override public void validate(ToolCallContinuationInput input) {
                    if (input.version() != 1) throw new IllegalArgumentException("unsupported checkpoint");
                }
                @Override public java.util.concurrent.CompletionStage<ToolCallContinuationResult> resume(
                        ToolCallContinuationInput input) {
                    return CompletableFuture.completedFuture(
                            new ToolCallContinuationResult(CompletableFuture.completedFuture(
                                    NodeResult.continueWith(null)), true));
                }
            });
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class InlineExecutionEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        @Override public String id() { return "inline"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() { return (delay, task) -> () -> true; }
        @Override public EngineState state() { return EngineState.RUNNING; }
        @Override public NodeRef spawn(String logicalName, RavenNode node) {
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }
        @Override public CompletionStage<NodeResult> send(NodeRef target,
                                                          ai.ravenroot.api.execution.NodeMessage message) {
            RavenNode node = nodes.get(target);
            return node == null
                    ? CompletableFuture.failedFuture(new IllegalArgumentException("unknown node"))
                    : node.onMessage(message, context(target));
        }
        @Override public java.util.Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target)
                    ? java.util.Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0))
                    : java.util.Optional.empty();
        }
        @Override public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> cancel(NodeRef target) { return stop(target); }
        @Override public CompletionStage<Void> drain() {
            nodes.clear();
            return CompletableFuture.completedFuture(null);
        }
        @Override public void close() { nodes.clear(); }
        private NodeContext context(NodeRef self) {
            return new NodeContext() {
                @Override public NodeRef self() { return self; }
                @Override public Scheduler scheduler() { return InlineExecutionEngine.this.scheduler(); }
                @Override public Mailbox mailbox() { return () -> 0; }
                @Override public CancellationSignal cancellation() {
                    return new CancellationSignal() {
                        @Override public boolean cancelled() { return false; }
                        @Override public void onCancel(Runnable listener) { }
                    };
                }
            };
        }
    }
}
