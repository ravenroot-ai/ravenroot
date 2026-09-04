package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.NodeRetryProperty;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.AgentChildResourceRequest;
import ai.ravenroot.api.node.service.AgentModelReservation;
import ai.ravenroot.api.node.service.AgentResourceRequest;
import ai.ravenroot.api.node.service.AgentResourceService;
import ai.ravenroot.api.node.service.AgentResourceSession;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.api.persistence.AgentReservationState;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.core.runtime.GraphExecutionResult;
import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetPolicy;
import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService;
import ai.ravenroot.core.security.nodepackage.AgentBudgetTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentBudgetRetryIntegrationTest {
    private static final Duration BOUND = Duration.ofSeconds(10);

    @Test
    void preDispatchFailureReleasesFirstAttemptAndRetryUsesANewOperation() throws Exception {
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        try (var harness = new Harness(http, policy(1_000))) {
            http.resources(firstPermitHasNoTime(harness.budgets));
            GraphExecutionResult result = harness.run(false);

            assertEquals("done", result.payload());
            assertEquals(1, http.calls(), "the locally refused attempt must perform no provider egress");
            var budget = harness.budget();
            assertEquals(2, budget.reservations().size());
            assertEquals(1, budget.reservations().values().stream()
                    .filter(value -> value.state() == AgentReservationState.RELEASED).count());
            assertEquals(1, budget.reservations().values().stream()
                    .filter(value -> value.state() == AgentReservationState.SETTLED).count());
            var keys = budget.reservations().values().stream().map(value -> value.operationKey()).toList();
            assertNotEquals(keys.get(0), keys.get(1), "attempt identity must be part of the operation key");
            assertEquals(1, budget.spent().turns());
            assertEquals(1, budget.spent().teamCumulative());
        }
    }

    @Test
    void indeterminateUsageIsCumulativeAndRetryCannotResetCombinedTokenExhaustion() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .thenStatus(503, "{}")
                .then(AiTestSupport.answers("must not be reached"));
        try (var harness = new Harness(http, policy(120))) {
            http.resources(harness.budgets);
            assertThrows(ExecutionException.class, () -> harness.run(true));

            assertEquals(1, http.calls(), "the exhausted retry must be refused before provider dispatch");
            var budget = harness.budget();
            assertEquals(1, budget.reservations().size());
            assertEquals(AgentReservationState.INDETERMINATE,
                    budget.reservations().values().iterator().next().state());
            assertEquals(120, budget.spent().inputTokens() + budget.spent().outputTokens());
            assertEquals(1, budget.spent().turns());
            assertEquals(1, budget.spent().teamCumulative(),
                    "retry remains one logical team member and never gets a fresh grant");
            assertEquals(0, budget.reserved().teamActive(),
                    "final retry exhaustion releases the logical invocation slot");
        }
    }

    private static AgentResourceService firstPermitHasNoTime(AgentAuthorityBudgetService delegate) {
        var first = new AtomicBoolean(true);
        return new AgentResourceService() {
            @Override public AgentResourceSession admit(NodeMessage message, AgentResourceRequest request) {
                return wrap(delegate.admit(message, request));
            }

            @Override public AgentResourceSession resume(
                    ai.ravenroot.api.node.ToolCallContinuationInput continuation,
                    AgentResourceRequest request) {
                return wrap(delegate.resume(continuation, request));
            }

            private AgentResourceSession wrap(AgentResourceSession session) {
                return new AgentResourceSession() {
                    @Override public AgentModelReservation reserveModelTurn(long ordinal) {
                        AgentModelReservation permit = session.reserveModelTurn(ordinal);
                        if (!first.compareAndSet(true, false)) return permit;
                        return new AgentModelReservation() {
                            @Override public long maximumOutputTokens() {
                                return permit.maximumOutputTokens();
                            }

                            @Override public Duration maximumDuration() {
                                return Duration.ZERO;
                            }

                            @Override public void dispatch() { permit.dispatch(); }
                            @Override public void release() { permit.release(); }
                            @Override public void settle(Optional<Long> input, Optional<Long> output) {
                                permit.settle(input, output);
                            }
                            @Override public void indeterminate() { permit.indeterminate(); }
                        };
                    }

                    @Override public AgentResourceSession createChild(AgentChildResourceRequest request) {
                        return session.createChild(request);
                    }

                    @Override public void complete() { session.complete(); }
                    @Override public void cancel() { session.cancel(); }
                    @Override public void failAttempt() { session.failAttempt(); }
                    @Override public void suspend() { session.suspend(); }
                };
            }
        };
    }

    private static AgentAuthorityBudgetPolicy policy(long totalTokens) {
        return new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1),
                new AgentBudgetVector(4, totalTokens, totalTokens, 60_000, 100_000, 4, 2, 2, 2),
                100, 20, 1, 1, Set.of(), Set.of());
    }

    private static final class Harness implements AutoCloseable {
        private final InMemoryExecutionStore store = new InMemoryExecutionStore();
        private final DirectEngine engine = new DirectEngine();
        private final ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        private final UUID traversalId = UUID.randomUUID();
        private final SecurityContext security = new SecurityContext("request", "tenant-a", "user",
                PrincipalType.USER, "issuer");
        private final AgentAuthorityBudgetService budgets;
        private final ExecutionRecorder recorder;
        private final AutoCloseable binding;
        private final GraphManager manager;
        private final GraphRunner runner;

        private Harness(AiTestSupport.ScriptedHttp http, AgentAuthorityBudgetPolicy policy) {
            var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                    Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("v1")))
                    .build()).toCompletableFuture().join().revision();
            revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(revision))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .build()).toCompletableFuture().join().revision();
            recorder = ExecutionRecorder.open(store, key, "retry-test", Duration.ofSeconds(30), revision);
            budgets = new AgentAuthorityBudgetService(store, Clock.systemUTC(), policy,
                    AgentBudgetTelemetry.discarding());
            binding = budgets.bindLive(key, recorder);
            NodeAction action = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(
                    "https://model.example/v1"))).create(AiTestSupport.agentConfiguration(Map.of(
                    "provider", "local", "instructions", "be terse", "objective", "answer",
                    "maxTurns", 1, "maxTotalTokens", policy.rootMaxima().inputTokens(),
                    "maxTokens", 20, "timeoutMs", 10_000)), http);
            var behaviors = new BehaviorRegistry().register("agent", action::handle);
            manager = GraphManager.from(graph());
            runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor(),
                    ExecutionIdentitySource.randomUuids(), new InMemoryJoinStore(), Clock.systemUTC());
        }

        private GraphExecutionResult run(boolean expectFailure) throws Exception {
            CompletionStage<GraphExecutionResult> stage = runner.execute(
                    security, key.processInstanceId(), traversalId,
                    "payload", "v1", null, null, recorder);
            try {
                GraphExecutionResult result = stage.toCompletableFuture()
                        .get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
                if (expectFailure) throw new AssertionError("execution unexpectedly succeeded");
                budgets.finishProcess(key, true);
                return result;
            } catch (ExecutionException failure) {
                budgets.finishProcess(key, false);
                throw failure;
            }
        }

        private ai.ravenroot.api.persistence.DurableAgentAuthorityBudget budget() {
            return store.loadAgentAuthorityBudget(key).toCompletableFuture().join().orElseThrow();
        }

        private static GraphDefinition graph() {
            return new GraphDefinition(List.of(
                    GraphNode.start("start"),
                    new GraphNode("agent", NodeKind.BEHAVIOR, "agent", Map.of(
                            NodeRetryProperty.MAX_ATTEMPTS, "2",
                            NodeRetryProperty.INITIAL_BACKOFF, "0",
                            NodeRetryProperty.BACKOFF_MULTIPLIER, "1.0",
                            NodeRetryProperty.MAX_BACKOFF, "0",
                            NodeRetryProperty.RETRY_ON, AgentException.class.getSimpleName())),
                    GraphNode.error("error"), GraphNode.end("end")), List.of(
                    GraphEdge.to("start", "agent"), GraphEdge.to("agent", "end")));
        }

        @Override public void close() throws Exception {
            runner.close();
            manager.close();
            binding.close();
            recorder.close();
            engine.close();
            store.close();
        }
    }

    private static final class DirectEngine implements ai.ravenroot.api.execution.ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
            @Override public boolean cancelled() { return false; }
            @Override public void onCancel(Runnable listener) { }
        };

        @Override public String id() { return "direct"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() {
            return (delay, task) -> {
                task.run();
                return () -> false;
            };
        }
        @Override public NodeRef spawn(String name, RavenNode node) {
            NodeRef ref = new NodeRef(name + '-' + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }
        @Override public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            return nodes.get(target).onMessage(message, new NodeContext() {
                @Override public NodeRef self() { return target; }
                @Override public Scheduler scheduler() { return DirectEngine.this.scheduler(); }
                @Override public Mailbox mailbox() { return () -> 0; }
                @Override public CancellationSignal cancellation() { return NEVER_CANCELLED; }
            });
        }
        @Override public Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target)
                    ? Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0))
                    : Optional.empty();
        }
        @Override public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> cancel(NodeRef target) { return stop(target); }
        @Override public CompletionStage<Void> drain() { return CompletableFuture.completedFuture(null); }
        @Override public EngineState state() { return EngineState.RUNNING; }
        @Override public void close() { nodes.clear(); }
    }
}
