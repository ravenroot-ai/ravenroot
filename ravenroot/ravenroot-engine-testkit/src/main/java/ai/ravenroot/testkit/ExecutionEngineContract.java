package ai.ravenroot.testkit;

import ai.ravenroot.api.ai.ModelProvider;
import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.ai.ModelResponse;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeCancelledException;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeNotAcceptingException;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.NodeTerminationReason;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.core.runtime.JoinFailureException;
import ai.ravenroot.core.runtime.JoinSpec;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests every adapter against the mandatory Ravenroot engine semantics. */
public abstract class ExecutionEngineContract {
    private static final String REBINDING_BEHAVIOR = "application-bound-adapter";
    private static final String REBINDING_PROVIDER = "application-provider";
    private static final String REBINDING_NODE = "adapter";
    private static final String REBINDING_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="provider" for="node" attr.name="provider" attr.type="string"/>
              <key id="prompt" for="node" attr.name="prompt" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="application-adapter-rebinding" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="adapter">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">application-bound-adapter</data>
                  <data key="provider">application-provider</data>
                  <data key="prompt">Process the payload</data>
                </node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-adapter" source="start" target="adapter">
                  <data key="outcome">continue</data>
                </edge>
                <edge id="adapter-end" source="adapter" target="end">
                  <data key="outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;
    /**
     * One behaviour node whose handler is supplied per test, so a node can be held open (or made to
     * fail) under the test's own control -- used to observe a traversal while it is genuinely
     * mid-node rather than before start or after it ends.
     */
    private static final String BLOCKING_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="blocking-work" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="work">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">blocking-work</data>
                </node>
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-work" source="start" target="work">
                  <data key="outcome">continue</data>
                </edge>
                <edge id="work-end" source="work" target="end">
                  <data key="outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;
    /**
     * The identity every conformance message and traversal runs as. An engine adapter is not a
     * security boundary — it transports {@code NodeMessage} opaquely — so one fixed context is enough
     * here. SEC-07 propagation itself is asserted in core and at the application layer, where the
     * identity is actually established.
     */
    protected static final ai.ravenroot.api.security.SecurityContext TCK_IDENTITY =
            new ai.ravenroot.api.security.SecurityContext("tck-request", "tck-tenant", "tck-subject",
                    ai.ravenroot.api.security.PrincipalType.WORKLOAD, "urn:ravenroot:tck");

    private ExecutionEngine engine;

    protected abstract ExecutionEngine createEngine(String systemName);

    protected final ExecutionEngine engine() {
        if (engine == null) {
            engine = createEngine("ravenroot-tck-" + UUID.randomUUID());
        }
        return engine;
    }

    /**
     * ADR 0023: Test is structural evidence, not output simulation. The engine still spawns
     * and traverses every graph node, while the runner's command wrapper must intercept delivery before
     * deployment-trusted factories (and therefore secret, tool or adapter boundaries) are touched.
     */
    @Test
    void testPassthroughTraversesActorsWithoutConstructingOrInvokingProductionBehaviors() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("secret", "production-secret"),
                GraphNode.behavior("tool", "production-tool"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "secret"),
                GraphEdge.to("secret", "tool"),
                GraphEdge.to("tool", "end")));
        var factoryCalls = new AtomicInteger();
        var handlerCalls = new AtomicInteger();
        var registry = new BehaviorRegistry()
                .registerFactory(new SideEffectProbeFactory("production-secret", factoryCalls, handlerCalls))
                .registerFactory(new SideEffectProbeFactory("production-tool", factoryCalls, handlerCalls));
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            var result = runner.execute(TCK_IDENTITY, "original").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("original", result.payload(), "Test pass-through must preserve the submitted payload");
            assertEquals(Set.of("start", "secret", "tool", "end"), result.visitedNodes(),
                    "Test must preserve structural actor traversal");
            assertTrue(result.defaultedNodes().isEmpty(),
                    "an intentional bypass is not an unknown-behavior fallback");
            assertEquals(Set.of("start", "secret", "tool", "end"), result.bypassedNodes());
            assertEquals(0, factoryCalls.get(), "Test crossed the production adapter construction boundary");
            assertEquals(0, handlerCalls.get(), "Test invoked a production behavior");
        }
    }

    @Test
    void standardExecutionRemainsDistinctAndInvokesRegisteredBehavior() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"), GraphNode.behavior("work", "production-tool"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
        var factoryCalls = new AtomicInteger();
        var handlerCalls = new AtomicInteger();
        var registry = new BehaviorRegistry().registerFactory(
                new SideEffectProbeFactory("production-tool", factoryCalls, handlerCalls));
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), ExecutionPolicy.STANDARD)) {
            var result = runner.execute(TCK_IDENTITY, "original").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("production-output", result.payload());
            assertEquals(1, factoryCalls.get());
            assertEquals(1, handlerCalls.get());
            assertTrue(result.defaultedNodes().isEmpty());
        }
    }

    /** A new application submission resolves adapters again without changing an admitted run. */
    @Test
    final void aLaterApplicationSubmissionResolvesANewlyRegisteredAdapter() throws Exception {
        var providers = new ModelProviderRegistry();
        var providerInvocations = new AtomicInteger();
        var registry = new BehaviorRegistry().registerFactory(new RebindingFactory(providers));
        var monitor = new ExecutionMonitor();
        UUID firstTraversalId = UUID.randomUUID();
        UUID secondTraversalId = UUID.randomUUID();
        var firstTerminal = new CompletableFuture<ExecutionEvent>();
        var secondTerminal = new CompletableFuture<ExecutionEvent>();

        try (var subscription = monitor.subscribe(event -> {
                 if (event.traversalId().equals(firstTraversalId)
                         && event.type() == ExecutionEventType.EXECUTION_FAILED) {
                     firstTerminal.complete(event);
                 }
                 if (event.traversalId().equals(secondTraversalId)
                         && event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                     secondTerminal.complete(event);
                 }
             });
             var application = new DefaultRavenrootApplication(engine(), monitor, registry)) {
            var first = application.startGraphMl(TCK_IDENTITY, firstTraversalId, rebindingGraph(), "first");
            ExecutionEvent firstFailure = firstTerminal.get(10, TimeUnit.SECONDS);
            ExecutionOutcome firstOutcome = awaitTerminalOutcome(application, firstTraversalId);

            assertEquals(first.processInstanceId(), firstFailure.processInstanceId());
            assertEquals(first.traversalId(), firstFailure.traversalId());
            assertEquals(first.traversalId(), first.executionId());
            assertEquals(ProcessInstanceStatus.FAILED, firstOutcome.status());
            assertEquals(first.processInstanceId(), firstOutcome.processInstanceId());
            assertEquals(first.traversalId(), firstOutcome.traversalId());
            assertNull(firstOutcome.payload(), "a refused execution must retain no result payload");
            assertEquals(0, providerInvocations.get(),
                    "the unavailable adapter must refuse without invoking provider capability");
            assertEquals(1, eventCount(monitor, firstTraversalId, ExecutionEventType.NODE_STARTED,
                    REBINDING_NODE), "the first submission must reach the bound node");
            assertEquals(1, eventCount(monitor, firstTraversalId, ExecutionEventType.NODE_FAILED,
                    REBINDING_NODE), "the reached node must refuse the unavailable adapter");
            assertEquals(0, eventCount(monitor, firstTraversalId, ExecutionEventType.NODE_COMPLETED,
                    REBINDING_NODE), "a refusal must not manufacture a NodeResult");
            assertEquals(0, eventCount(monitor, firstTraversalId, ExecutionEventType.NODE_DEFAULTED,
                    REBINDING_NODE), "a refusal must not degrade into an unresolved-node default");

            providers.register(countingProvider(providerInvocations));
            var second = application.startGraphMl(TCK_IDENTITY, secondTraversalId, rebindingGraph(), "second");
            ExecutionEvent secondSuccess = secondTerminal.get(10, TimeUnit.SECONDS);
            ExecutionOutcome secondOutcome = awaitTerminalOutcome(application, secondTraversalId);

            assertEquals(second.processInstanceId(), secondSuccess.processInstanceId());
            assertEquals(second.traversalId(), secondSuccess.traversalId());
            assertEquals(second.traversalId(), second.executionId());
            assertEquals(ProcessInstanceStatus.COMPLETED, secondOutcome.status());
            assertEquals(second.processInstanceId(), secondOutcome.processInstanceId());
            assertEquals(second.traversalId(), secondOutcome.traversalId());
            assertEquals("resolved-second", secondOutcome.payload(),
                    "the application must retain the newly resolved provider's exact result");
            assertEquals(first.graphVersion(), second.graphVersion(),
                    "both submissions must execute the identical graph document");
            assertFalse(first.graphVersion().isBlank(), "the application must pin a graph version");
            assertNotEquals(first.processInstanceId(), second.processInstanceId(),
                    "separate submissions must create separate process instances");
            assertNotEquals(first.traversalId(), second.traversalId(),
                    "separate submissions must create separate traversals");
            assertEquals(1, providerInvocations.get(),
                    "only the later submission may invoke the newly registered provider");
            assertEquals(1, eventCount(monitor, secondTraversalId, ExecutionEventType.NODE_COMPLETED,
                    REBINDING_NODE), "the later submission must run the resolved adapter");
            assertEquals(0, eventCount(monitor, secondTraversalId, ExecutionEventType.NODE_FAILED,
                    REBINDING_NODE), "the resolved adapter must not retain the earlier refusal");
            assertEquals(0, eventCount(monitor, secondTraversalId, ExecutionEventType.NODE_DEFAULTED,
                    REBINDING_NODE), "the resolved adapter must run rather than default");

            ExecutionEvent firstStarted = onlyEvent(monitor, firstTraversalId,
                    ExecutionEventType.NODE_STARTED, REBINDING_NODE);
            ExecutionEvent firstFailed = onlyEvent(monitor, firstTraversalId,
                    ExecutionEventType.NODE_FAILED, REBINDING_NODE);
            ExecutionEvent secondStarted = onlyEvent(monitor, secondTraversalId,
                    ExecutionEventType.NODE_STARTED, REBINDING_NODE);
            ExecutionEvent secondCompleted = onlyEvent(monitor, secondTraversalId,
                    ExecutionEventType.NODE_COMPLETED, REBINDING_NODE);
            assertEquals(first.processInstanceId(), firstStarted.processInstanceId());
            assertEquals(first.processInstanceId(), firstFailed.processInstanceId());
            assertEquals(second.processInstanceId(), secondStarted.processInstanceId());
            assertEquals(second.processInstanceId(), secondCompleted.processInstanceId());
            assertNotNull(firstStarted.invocationId());
            assertNotNull(secondStarted.invocationId());
            assertEquals(firstStarted.invocationId(), firstFailed.invocationId(),
                    "the first node failure must settle the invocation that started");
            assertEquals(secondStarted.invocationId(), secondCompleted.invocationId(),
                    "the second node completion must settle the invocation that started");
            assertNotEquals(firstStarted.invocationId(), secondStarted.invocationId(),
                    "the two reached-node attempts must have distinct invocation identities");
        }
    }

    /**
     * A traversal cancelled mid-node reaches a terminal outcome through {@link #awaitTerminalOutcome}
     * -- the exact poll every caller of {@code GET /v1/executions/{id}} rides, which gates only on
     * {@code found.outcome().status().terminal()} (see {@link #pollTerminalOutcome}). Cancellation
     * keeps the status {@code FAILED} rather than introducing a new {@link ProcessInstanceStatus}
     * member precisely so this gate needs no change; had the design gone the other way, this poll
     * would never see a cancelled run finish and would spin until the test's own timeout. The
     * cancellation is also compared against an ordinary in-node failure reaching the identical gate,
     * so a poll that simply never distinguishes terminal reasons could not pass this test by
     * accident.
     *
     * <p>{@code GraphRunner.cancelTraversal} does not preempt a node computation already dispatched
     * -- it only refuses the <em>next</em> hop and releases a pause gate or a retry backoff, per its
     * own javadoc ("the node named here is the first hop that did <em>not</em> run"). So this
     * fixture completes the blocked node's own future itself, after cancelling, to simulate the
     * effect actually finishing; the cancellation then surfaces on the hop that would have followed
     * it, which is the one that never runs.</p>
     */
    @Test
    final void aCancelledTraversalReachesATerminalOutcomeThroughTheSamePollThatGatesOnStatusTerminal()
            throws Exception {
        var entered = new CountDownLatch(1);
        var blocked = new CompletableFuture<NodeResult>();
        var cancelledRegistry = new BehaviorRegistry().register("blocking-work", message -> {
            entered.countDown();
            return blocked;
        });
        var monitor = new ExecutionMonitor();
        UUID cancelledTraversalId = UUID.randomUUID();
        try (var application = new DefaultRavenrootApplication(engine(), monitor, cancelledRegistry)) {
            application.startGraphMl(TCK_IDENTITY, cancelledTraversalId, blockingGraph(), "payload");
            assertTrue(entered.await(10, TimeUnit.SECONDS),
                    "the node must have been entered before it can be cancelled mid-flight");

            assertTrue(application.cancelTraversal(cancelledTraversalId));
            // The node's own effect already ran (entered fired) and is let to finish; only the next
            // hop -- "end" -- is refused by the cancellation now in effect.
            blocked.complete(NodeResult.continueWith("payload"));

            ExecutionOutcome cancelledOutcome = awaitTerminalOutcome(application, cancelledTraversalId);
            assertTrue(cancelledOutcome.status().terminal(),
                    "the poll's own gate must have been satisfied for this test to reach here at all");
            assertEquals(ProcessInstanceStatus.FAILED, cancelledOutcome.status());
            assertTrue(cancelledOutcome.cancelled(),
                    "a cancellation observed through the polling gate must still be a cancellation, "
                            + "not merely a run that happened to stop");
            assertNull(cancelledOutcome.payload(), "a cancelled run reaches no end node");
        }

        // The contrast: an ordinary in-node failure reaches the identical gate and must NOT read as
        // cancelled, so the assertions above cannot be satisfied by a poll -- or an outcome -- that
        // conflates every terminal FAILED run.
        var failingRegistry = new BehaviorRegistry().register("blocking-work",
                message -> CompletableFuture.failedFuture(new IllegalStateException("node broke")));
        UUID failedTraversalId = UUID.randomUUID();
        try (var application = new DefaultRavenrootApplication(engine(), new ExecutionMonitor(), failingRegistry)) {
            application.startGraphMl(TCK_IDENTITY, failedTraversalId, blockingGraph(), "payload");
            ExecutionOutcome failedOutcome = awaitTerminalOutcome(application, failedTraversalId);
            assertEquals(ProcessInstanceStatus.FAILED, failedOutcome.status());
            assertFalse(failedOutcome.cancelled(),
                    "an ordinary node failure reaching the same terminal gate must not be misread as "
                            + "a cancellation");
        }
    }

    /**
     * Issue #104's central claim, exercised through the engine's own contract rather than through a
     * hand-built {@code DurableExecutionResult} or a fresh {@code ExecutionResultRegistry}: a
     * terminal outcome reached by a real traversal, on a real engine, must be durably readable by an
     * application instance that never ran it and holds no process-local cache entry for it at all --
     * exactly what a second instance sharing the store looks like, and exactly what a restart looks
     * like once the process-local registry is empty (see {@code ExecutionResultRegistry}'s own
     * javadoc on that equivalence). {@code DefaultRavenrootApplication.close()} releases no store
     * state, so the same {@link ai.ravenroot.core.persistence.InMemoryExecutionStore} instance can be
     * handed from one application to the next the way a restart hands the same database file on.
     */
    @Test
    final void aTerminalOutcomeIsDurablyReadableByAnApplicationInstanceThatNeverRanIt() throws Exception {
        var executionStore = new ai.ravenroot.core.persistence.InMemoryExecutionStore();
        try {
            UUID traversalId = UUID.randomUUID();
            var firstRegistry = new BehaviorRegistry().register("blocking-work",
                    message -> CompletableFuture.completedFuture(NodeResult.continueWith("durable-output")));
            ExecutionOutcome outcome;
            try (var first = new DefaultRavenrootApplication(engine(), new ExecutionMonitor(), firstRegistry,
                    new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                    new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), executionStore)) {
                first.startGraphMl(TCK_IDENTITY, traversalId, blockingGraph(), "payload");
                outcome = awaitTerminalOutcome(first, traversalId);
            }
            assertEquals(ProcessInstanceStatus.COMPLETED, outcome.status());

            // A second application instance, sharing nothing with the first but the durable store: no
            // registry entry, no process-local cache, exactly like a restarted process or a second one
            // reading the same database.
            try (var another = new DefaultRavenrootApplication(engine(), new ExecutionMonitor(),
                    new BehaviorRegistry(), new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                    new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), executionStore)) {
                var lookup = another.executionResult(TCK_IDENTITY.tenantId(), traversalId);
                var found = assertInstanceOf(ExecutionLookup.Found.class, lookup,
                        "a terminal outcome recorded through the store must be readable by an "
                                + "application instance that never ran it, not merely by the one that did");
                assertEquals(ProcessInstanceStatus.COMPLETED, found.outcome().status());
                assertEquals("durable-output", found.outcome().payload(),
                        "the durably-read payload must be the one the traversal actually produced");
            }
        } finally {
            executionStore.close();
        }
    }

    private static ByteArrayInputStream blockingGraph() {
        return new ByteArrayInputStream(BLOCKING_GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    private record RebindingFactory(ModelProviderRegistry providers) implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(REBINDING_BEHAVIOR, "Application-bound adapter", "TCK",
                    "Adapter resolution across application submissions.", "actor", false, List.of(
                    NodePropertyDescriptor.adapterId("provider", "Provider", NodePropertyType.STRING,
                            "Required provider adapter."),
                    NodePropertyDescriptor.required("prompt", "Prompt", NodePropertyType.TEXT,
                            "Deterministic test prompt.")), Set.of("external-provider"))
                    .withOutcomes(NodeOutcomeDescriptor.literal("continue", "The adapter answered."));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            String providerId = NodePropertyDescriptor.adapterIdOf(node.properties().get("provider"));
            var resolved = providers.find(providerId);
            if (resolved.isEmpty()) {
                return message -> CompletableFuture.failedFuture(
                        new IllegalStateException("No provider configured for node " + node.id()));
            }
            ModelProvider provider = resolved.get();
            return message -> provider.generate(new ModelRequest(message.executionId(), node.id(),
                            node.properties().get("prompt").toString(), message.payload(), "", "", Map.of()))
                    .thenApply(response -> new NodeResult("continue", response.payload(), message.attributes()));
        }
    }

    private static ModelProvider countingProvider(AtomicInteger invocations) {
        return new ModelProvider() {
            @Override
            public String id() {
                return REBINDING_PROVIDER;
            }

            @Override
            public CompletionStage<ModelResponse> generate(ModelRequest request) {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new ModelResponse("resolved-" + request.payload(), id(), request.model(), Map.of()));
            }
        };
    }

    private static ByteArrayInputStream rebindingGraph() {
        return new ByteArrayInputStream(REBINDING_GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    private static long eventCount(ExecutionMonitor monitor, UUID traversalId,
                                   ExecutionEventType type, String nodeId) {
        return monitor.eventsAfter(0).stream().filter(event -> event.traversalId().equals(traversalId)
                && event.type() == type && nodeId.equals(event.nodeId())).count();
    }

    private static ExecutionEvent onlyEvent(ExecutionMonitor monitor, UUID traversalId,
                                            ExecutionEventType type, String nodeId) {
        var matches = monitor.eventsAfter(0).stream().filter(event -> event.traversalId().equals(traversalId)
                && event.type() == type && nodeId.equals(event.nodeId())).toList();
        assertEquals(1, matches.size(),
                () -> "expected exactly one " + type + " event for " + nodeId + " in " + traversalId);
        return matches.getFirst();
    }

    private static ExecutionOutcome awaitTerminalOutcome(DefaultRavenrootApplication application,
                                                          UUID traversalId) throws Exception {
        var terminal = new CompletableFuture<ExecutionOutcome>();
        pollTerminalOutcome(application, traversalId, terminal);
        return terminal.orTimeout(10, TimeUnit.SECONDS).get();
    }

    private static void pollTerminalOutcome(DefaultRavenrootApplication application, UUID traversalId,
                                            CompletableFuture<ExecutionOutcome> terminal) {
        if (terminal.isDone()) {
            return;
        }
        try {
            if (application.executionResult(TCK_IDENTITY.tenantId(), traversalId)
                    instanceof ExecutionLookup.Found found
                    && found.outcome().status().terminal()) {
                terminal.complete(found.outcome());
                return;
            }
            CompletableFuture.delayedExecutor(1, TimeUnit.MILLISECONDS)
                    .execute(() -> pollTerminalOutcome(application, traversalId, terminal));
        } catch (RuntimeException failure) {
            terminal.completeExceptionally(failure);
        }
    }

    private record SideEffectProbeFactory(String behavior, AtomicInteger factoryCalls,
                                          AtomicInteger handlerCalls) implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(behavior, behavior, "TCK", "production side-effect probe",
                    "actor", false, List.of(), Set.of("network", "credential-reference", "side-effect"));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            factoryCalls.incrementAndGet();
            return message -> {
                handlerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(NodeResult.continueWith("production-output"));
            };
        }
    }

    @AfterEach
    final void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    final void executesLifecycleExactlyOnce() throws Exception {
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var node = new RavenNode() {
            @Override
            public void onStart(NodeContext context) {
                starts.incrementAndGet();
            }

            @Override
            public java.util.concurrent.CompletionStage<NodeResult> onMessage(
                    NodeMessage message, NodeContext context) {
                return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        };
        var ref = engine().spawn("lifecycle", node);

        assertEquals("payload", send(ref, "payload").payload());
        engine().stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }

    @Test
    final void serializesNodeProcessingUntilAsyncCompletion() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var firstResult = new CompletableFuture<NodeResult>();
        var invocations = new AtomicInteger();
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        var ref = engine().spawn("ordered", (message, context) -> {
            int invocation = invocations.incrementAndGet();
            order.add("start-" + invocation);
            if (invocation == 1) {
                firstStarted.countDown();
                return firstResult.whenComplete((ignored, error) -> order.add("end-1"));
            }
            order.add("end-2");
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });

        var first = engine().send(ref, message("first"));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        var second = engine().send(ref, message("second"));
        Thread.sleep(100);
        assertEquals(1, invocations.get(), "a second invocation must wait for the first completion");

        firstResult.complete(NodeResult.continueWith("first"));
        assertEquals("first", first.toCompletableFuture().get(5, TimeUnit.SECONDS).payload());
        assertEquals("second", second.toCompletableFuture().get(5, TimeUnit.SECONDS).payload());
        assertEquals(List.of("start-1", "end-1", "start-2", "end-2"), order);
    }

    @Test
    final void propagatesFailureAndContinuesProcessing() throws Exception {
        var invocation = new AtomicInteger();
        var ref = engine().spawn("failure", (message, context) -> invocation.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("expected"))
                : CompletableFuture.completedFuture(NodeResult.continueWith("recovered")));

        var failure = assertThrows(Exception.class,
                () -> engine().send(ref, message("first")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(rootCause(failure) instanceof IllegalStateException);
        // Resume is the mandatory supervision decision, so a failed invocation must not have moved
        // the node's own lifecycle. Asserting the state, not just that a later message works, is what
        // stops an adapter from quietly restarting the node behind a passing functional test.
        assertEquals(NodeLifecycleState.RUNNING, status(ref).state());
        assertEquals(0, status(ref).acceptedMessages());
        assertEquals("recovered", send(ref, "second").payload());
    }

    /**
     * An {@code Error} is not an {@code Exception}, and the adapters' catch clauses only ever said
     * {@code RuntimeException}.
     *
     * <p>So an {@code AssertionError} — from an assertion, a test double, a library compiled against
     * a different version — went straight past the adapter into the actor runtime, which supervised it
     * by stopping the actor. Three guarantees fell at once: the caller's reply was registered at
     * admission but never settled, so it waited forever; the lifecycle was stranded in a non-terminal
     * state that neither {@code stop} nor {@code cancel} could move, because the only thing that could
     * complete it was the actor that had just died; and resume, the mandatory supervision decision,
     * silently became stop-on-failure for one category of failure.</p>
     *
     * <p>Measured on the unfixed adapter: this send never completes, {@code stop} never completes, and
     * {@code close()} takes 20s — two full termination bounds — and still leaves the node
     * {@code CANCELLING}.</p>
     */
    @Test
    final void resumesAfterANodeThrowsAnErrorRatherThanAnException() throws Exception {
        var invocation = new AtomicInteger();
        var stops = new AtomicInteger();
        var ref = engine().spawn("error", new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                if (invocation.incrementAndGet() == 1) {
                    throw new AssertionError("thrown by the node");
                }
                return CompletableFuture.completedFuture(NodeResult.continueWith("recovered"));
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        });

        var failure = assertThrows(ExecutionException.class,
                () -> engine().send(ref, message("first")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(AssertionError.class, rootCause(failure),
                "the Error must reach the caller, not the actor runtime");

        // Identical to what propagatesFailureAndContinuesProcessing asserts for an Exception. The
        // whole point is that the two categories are indistinguishable from outside the node.
        assertEquals(NodeLifecycleState.RUNNING, status(ref).state());
        assertEquals(0, status(ref).acceptedMessages());
        assertEquals("recovered", send(ref, "second").payload());

        engine().stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        var terminal = status(ref);
        assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
        assertEquals(NodeTerminationReason.STOPPED, terminal.reason());
        assertEquals(1, stops.get());
    }

    /**
     * The reported defect end to end: {@code GraphRunner.close()} must return even when a node dies.
     *
     * <p>This is the shape the defect was actually found in — a graph, a behaviour that throws, and a
     * try-with-resources that never left its block. The core suite pins the runner's shutdown policy
     * against a stub; this pins that a real adapter never puts the runner in the position of needing
     * it, because the node terminates properly instead.</p>
     */
    @Test
    final void closesAGraphRunnerWhoseNodeThrewAnError() throws Exception {
        var registry = new BehaviorRegistry().register("explode", message -> {
            throw new AssertionError("thrown by the behaviour");
        });
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("boom", "explode"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "boom"),
                GraphEdge.to("boom", "end")));

        try (var manager = GraphManager.from(graph)) {
            var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor());

            var execution = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture();
            assertInstanceOf(AssertionError.class, rootCause(assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS))), "the traversal must fail, not hang");

            // Unbounded before: allOf(stops).join() on a stop stage nothing could complete.
            assertTimeoutPreemptively(Duration.ofSeconds(30), runner::close);
        }
    }

    @Test
    final void schedulesTasksAndCancelsThem() throws Exception {
        var executed = new CountDownLatch(1);
        engine().scheduler().schedule(Duration.ofMillis(20), executed::countDown);
        assertTrue(executed.await(5, TimeUnit.SECONDS));

        var cancelledExecution = new AtomicInteger();
        var task = engine().scheduler().schedule(Duration.ofSeconds(1), cancelledExecution::incrementAndGet);
        assertTrue(task.cancel());
        Thread.sleep(100);
        assertEquals(0, cancelledExecution.get());
    }

    @Test
    final void rejectsMessagesAfterStop() throws Exception {
        var ref = engine().spawn("stopped", (message, context) ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
        engine().stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);

        var result = engine().send(ref, message("late")).toCompletableFuture();
        assertTrue(result.isCompletedExceptionally());
        var failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        var refusal = assertInstanceOf(NodeNotAcceptingException.class, failure.getCause());
        assertEquals(ref, refusal.node());
        assertEquals(NodeLifecycleState.TERMINATED, refusal.state());
    }

    @Test
    final void distinguishesARefusedNodeFromANodeItNeverIssued() {
        var unknown = new NodeRef("never-spawned-" + UUID.randomUUID());

        var failure = assertThrows(ExecutionException.class,
                () -> engine().send(unknown, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS));

        // Losing a race against a stop is an ordinary outcome a caller may retry; addressing a
        // reference the engine never issued is a defect. One exception type for both forced callers
        // to parse a message string to tell them apart.
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(failure.getCause() instanceof NodeNotAcceptingException);
        assertTrue(engine().status(unknown).isEmpty());
    }

    @Test
    final void drainsMessagesAcceptedBeforeTheStop() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var stops = new AtomicInteger();
        var ref = engine().spawn("draining", new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                started.countDown();
                return CompletableFuture.supplyAsync(() -> {
                    await(release);
                    return NodeResult.continueWith(message.payload());
                });
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        });

        var first = engine().send(ref, message("first")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var second = engine().send(ref, message("second")).toCompletableFuture();
        assertEquals(2, status(ref).acceptedMessages());

        var stopped = engine().stop(ref).toCompletableFuture();
        assertEquals(NodeLifecycleState.DRAINING, status(ref).state());
        assertFalse(stopped.isDone(), "a drain cannot finish while it still owes accepted messages");
        // The drain refuses new work from the moment it starts; that is what separates it from a
        // quiesce, and it is the only reason the outstanding count can reach zero.
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine().send(ref, message("third")).toCompletableFuture().get(5, TimeUnit.SECONDS))));

        release.countDown();

        assertEquals("first", first.get(5, TimeUnit.SECONDS).payload());
        assertEquals("second", second.get(5, TimeUnit.SECONDS).payload());
        stopped.get(5, TimeUnit.SECONDS);
        var terminal = status(ref);
        assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
        assertEquals(NodeTerminationReason.STOPPED, terminal.reason());
        assertEquals(0, terminal.acceptedMessages());
        assertEquals(1, stops.get());
    }

    @Test
    final void cancelReleasesCallersWithoutWaitingForTheNodeComputation() throws Exception {
        var started = new CountDownLatch(1);
        var neverCompletes = new CompletableFuture<NodeResult>();
        var observedSignal = new AtomicBoolean();
        var listenerFired = new CountDownLatch(1);
        var stops = new AtomicInteger();
        var ref = engine().spawn("cancelled", new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                context.cancellation().onCancel(listenerFired::countDown);
                started.countDown();
                return neverCompletes.thenApply(result -> {
                    observedSignal.set(context.cancellation().cancelled());
                    return result;
                });
            }

            @Override
            public void onStop(NodeContext context) {
                stops.incrementAndGet();
            }
        });

        var inFlight = engine().send(ref, message("in-flight")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = engine().send(ref, message("queued")).toCompletableFuture();
        assertEquals(2, status(ref).acceptedMessages());

        // The bound is the whole point: this must return even though the node's own computation has
        // not completed and never will on its own.
        engine().cancel(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> inFlight.get(5, TimeUnit.SECONDS))));
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS))));
        assertTrue(listenerFired.await(5, TimeUnit.SECONDS), "the cooperative signal must reach the node");

        var terminal = status(ref);
        assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
        assertEquals(NodeTerminationReason.CANCELLED, terminal.reason());
        assertEquals(0, terminal.acceptedMessages());
        assertEquals(1, stops.get());

        if (engine().capabilities().contains(EngineCapability.PREEMPTIVE_CANCELLATION)) {
            assertTrue(neverCompletes.isCompletedExceptionally(),
                    "an engine declaring preemptive cancellation must abort the computation itself");
        } else {
            // The honest cooperative guarantee: the computation is untouched, so the late result is
            // discarded rather than delivered, and the node can see why through its signal.
            assertFalse(neverCompletes.isDone());
            neverCompletes.complete(NodeResult.continueWith("too late"));
            Thread.sleep(100);
            assertTrue(observedSignal.get(), "a node resuming after a cancellation must observe it");
            assertInstanceOf(NodeCancelledException.class, rootCause(
                    assertThrows(ExecutionException.class, () -> inFlight.get(5, TimeUnit.SECONDS))));
        }
    }

    @Test
    final void aLateFailureDoesNotRewriteAnObservedCancellation() throws Exception {
        var started = new CountDownLatch(1);
        var controlled = new CompletableFuture<NodeResult>();
        var ref = engine().spawn("late-failure", (message, context) -> {
            started.countDown();
            return controlled;
        });

        var reply = engine().send(ref, message("payload")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        engine().cancel(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS))));

        controlled.completeExceptionally(new IllegalStateException("arrived after the cancellation"));
        Thread.sleep(100);

        // First settlement wins. A caller told its message was cancelled must never later be told the
        // same message failed instead, because it may already have acted on the cancellation.
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS))));
    }

    @Test
    final void escalatesADrainIntoACancellationAndCompletesBothRequests() throws Exception {
        var started = new CountDownLatch(1);
        var neverCompletes = new CompletableFuture<NodeResult>();
        var ref = engine().spawn("escalated", (message, context) -> {
            started.countDown();
            return neverCompletes;
        });

        var reply = engine().send(ref, message("stuck")).toCompletableFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var stopped = engine().stop(ref).toCompletableFuture();
        assertEquals(NodeLifecycleState.DRAINING, status(ref).state());
        assertFalse(stopped.isDone(), "a node that never completes never drains, by design");

        var cancelled = engine().cancel(ref).toCompletableFuture();

        cancelled.get(5, TimeUnit.SECONDS);
        // Both requests describe the same node, so both must be answered — a caller that asked for a
        // graceful stop is not left waiting because someone else escalated.
        stopped.get(5, TimeUnit.SECONDS);
        assertInstanceOf(NodeCancelledException.class, rootCause(
                assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS))));
        assertEquals(NodeTerminationReason.CANCELLED, status(ref).reason(),
                "a node whose work was abandoned must not report that it drained cleanly");
    }

    @Test
    final void runsOnStopExactlyOnceWhenStopAndCancelRaceEachOther() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 50; round++) {
                var stops = new AtomicInteger();
                var ref = engine().spawn("both", new RavenNode() {
                    @Override
                    public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                    }

                    @Override
                    public void onStop(NodeContext context) {
                        stops.incrementAndGet();
                    }
                });
                engine().send(ref, message("warm")).toCompletableFuture().get(5, TimeUnit.SECONDS);

                var barrier = new CyclicBarrier(2);
                Future<CompletionStage<Void>> stop = pool.submit(() -> {
                    barrier.await();
                    return engine().stop(ref);
                });
                Future<CompletionStage<Void>> cancel = pool.submit(() -> {
                    barrier.await();
                    return engine().cancel(ref);
                });

                stop.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
                cancel.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);

                // Which of the two wins is genuinely non-deterministic and is deliberately not
                // asserted. What must hold either way is that the node stopped once and reached a
                // terminal state with some reason.
                assertEquals(1, stops.get(), "onStop must run exactly once whichever request wins");
                var terminal = status(ref);
                assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
                assertNotNull(terminal.reason());
                assertEquals(0, terminal.acceptedMessages());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The window this samples is a few instructions wide, so the sampling is deliberately heavy.
     *
     * <p>The defect it exists for is a {@code send} that passes the admission test, is descheduled,
     * and hands its message to a node that has stopped in the meantime: the message is dropped and its
     * caller waits forever. Measured against a {@code NodeLifecycle.accept} whose admission test and
     * hand-off were deliberately split into two lock acquisitions, three senders over 150 rounds
     * detected it in 3 runs out of 8 — a test that reports a defect less than half the time it is
     * present is not evidence of anything. At the configuration this test actually ships with
     * (rounds = 16384, senders = 8, each jittered so the participants do not all leave the barrier in
     * lockstep), detection was measured at 8 runs out of 8 trials on the checkout used for this
     * measurement; ADR 0012 separately recorded 7 out of 8 at the same configuration on a different
     * run. Both figures support the same conclusion — the shipped configuration reliably reproduces
     * the defect — but detection is inherently environment-sensitive because the race window is a few
     * instructions wide, so treat neither number as exactly reproducible and do not assume a lower
     * round count preserves it: only 150 rounds and 16384 rounds have actually been measured, at
     * 3 out of 8 and (8 or 7) out of 8 respectively.</p>
     *
     * <p>Statistical sampling is still sampling. The interleaving itself is pinned deterministically
     * by {@code NodeLifecycleTest}, which holds a message open mid-enqueue and asserts that no
     * transition can overtake it; this test is what proves the adapters actually route through that
     * guarantee.</p>
     */
    @Test
    final void settlesEverySendThatRacesAStop() throws Exception {
        int rounds = 16_384;
        int senders = 8;
        var pool = Executors.newFixedThreadPool(senders + 1);
        var engine = engine();
        try {
            for (int round = 0; round < rounds; round++) {
                var ref = engine.spawn("racing", (message, context) ->
                        CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
                var barrier = new CyclicBarrier(senders + 1);
                var sends = new ArrayList<Future<CompletionStage<NodeResult>>>();
                for (int sender = 0; sender < senders; sender++) {
                    sends.add(pool.submit(() -> {
                        barrier.await();
                        jitter();
                        return engine.send(ref, message("racer"));
                    }));
                }
                Future<CompletionStage<Void>> stop = pool.submit(() -> {
                    barrier.await();
                    jitter();
                    return engine.stop(ref);
                });

                for (Future<CompletionStage<NodeResult>> sent : sends) {
                    var reply = sent.get(5, TimeUnit.SECONDS).toCompletableFuture();
                    // The winner is not asserted: whether a send lands before the stop is real
                    // non-determinism and any test claiming to know is a test that will lie. The
                    // invariant is that the caller is answered at all, and answered with one of the
                    // two outcomes the contract allows. A send admitted and then dropped because the
                    // node stopped between the admission test and the enqueue would hang here.
                    try {
                        assertNotNull(reply.get(5, TimeUnit.SECONDS));
                    } catch (ExecutionException failure) {
                        assertInstanceOf(NodeNotAcceptingException.class, failure.getCause(),
                                "a send that loses the race must be refused, not failed some other way");
                    }
                }
                stop.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var terminal = status(ref);
                assertEquals(NodeLifecycleState.TERMINATED, terminal.state());
                assertEquals(0, terminal.acceptedMessages(),
                        "a terminated node cannot still owe an answer to anyone");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    final void settlesEveryAcceptedMessageWhenCancellationRacesCompletion() throws Exception {
        int rounds = 60;
        int messages = 4;
        var pool = Executors.newFixedThreadPool(2);
        var engine = engine();
        try {
            for (int round = 0; round < rounds; round++) {
                var ref = engine.spawn("cancel-race", (message, context) ->
                        CompletableFuture.supplyAsync(() -> NodeResult.continueWith(message.payload())));
                var replies = new ArrayList<CompletableFuture<NodeResult>>();
                for (int i = 0; i < messages; i++) {
                    replies.add(engine.send(ref, message("payload")).toCompletableFuture());
                }
                var cancelled = pool.submit(() -> engine.cancel(ref));

                cancelled.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
                for (CompletableFuture<NodeResult> reply : replies) {
                    try {
                        // An accepted message either completed before the cancellation reached it or
                        // was abandoned by it. Both are correct; a third outcome, or none, is not.
                        assertEquals("payload", reply.get(5, TimeUnit.SECONDS).payload());
                    } catch (ExecutionException failure) {
                        assertInstanceOf(NodeCancelledException.class, failure.getCause());
                    }
                }
                assertEquals(0, status(ref).acceptedMessages());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    final void drainsEveryNodeAndRefusesFurtherSpawns() throws Exception {
        var first = engine().spawn("first", passthrough());
        var second = engine().spawn("second", passthrough());
        assertEquals(EngineState.RUNNING, engine().state());
        assertEquals("a", send(first, "a").payload());

        engine().drain().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(EngineState.DRAINING, engine().state());
        assertEquals(NodeTerminationReason.STOPPED, status(first).reason());
        assertEquals(NodeTerminationReason.STOPPED, status(second).reason());
        assertThrows(IllegalStateException.class, () -> engine().spawn("late", passthrough()));
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine().send(first, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));
    }

    @Test
    final void reportsClosedAndReleasesTheNodeStateItRetained() throws Exception {
        var own = createEngine("ravenroot-tck-close-" + UUID.randomUUID());
        NodeRef ref = own.spawn("closing", passthrough());
        assertEquals(NodeLifecycleState.RUNNING, own.status(ref).orElseThrow().state());
        own.stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // While the engine is up, the terminal status outlives the node on purpose: without it a
        // caller cannot distinguish a node that terminated from a reference the engine never issued.
        assertEquals(NodeLifecycleState.TERMINATED, own.status(ref).orElseThrow().state());
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> own.send(ref, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));

        own.close();

        assertEquals(EngineState.CLOSED, own.state());
        // A closed engine owes nobody that distinction any more, and keeping it would leave an engine
        // an application has finished with as large as everything it ever ran.
        assertTrue(own.status(ref).isEmpty(), "close() must release the node state it retained");
        var afterClose = assertThrows(ExecutionException.class,
                () -> own.send(ref, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS));
        // Reporting "unknown node" here would accuse the caller of a defect the engine caused by
        // forgetting, so a closed engine says so about itself instead.
        assertInstanceOf(IllegalStateException.class, afterClose.getCause());
        assertFalse(afterClose.getCause() instanceof NodeNotAcceptingException);
        assertFalse(afterClose.getCause() instanceof IllegalArgumentException);
        assertThrows(IllegalStateException.class, () -> own.spawn("later", passthrough()));
        own.close();
        assertEquals(EngineState.CLOSED, own.state());
    }

    /**
     * Retaining a terminal status is required; retaining every node ever spawned is a leak.
     *
     * <p>The two are easy to conflate, and conflating them is what this asserts against. The engine's
     * registry is the mechanism that makes {@code status} answer at all, so an engine that only ever
     * adds to it grows for as long as the process runs. It is not a slow leak either: the application
     * layer builds a new {@code GraphRunner} per graph execution over one shared engine, and every
     * runner spawns one node per graph vertex.</p>
     *
     * <p>The bound itself is a policy and is pinned where the policy lives; what an engine has to
     * satisfy is only that the bound exists. Measured here: 4096 spawn/send/stop cycles retain 1024
     * references on both adapters. Before this was fixed they retained all 4096, and 4096 more after
     * {@code close()}.</p>
     */
    @Test
    final void boundsTheNodeStateItRetainsAsNodesTerminate() throws Exception {
        int cycles = 4096;
        var engine = engine();
        var refs = new ArrayList<NodeRef>(cycles);
        for (int cycle = 0; cycle < cycles; cycle++) {
            NodeRef ref = engine.spawn("retention", passthrough());
            refs.add(ref);
            assertEquals("payload", send(ref, "payload").payload());
            engine.stop(ref).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        long retained = refs.stream().filter(ref -> engine.status(ref).isPresent()).count();

        assertTrue(retained <= cycles / 2,
                "an engine must not retain a node for every node it ever spawned, but " + retained
                        + " of " + cycles + " terminated nodes are still held");
        // The distinction the retention exists for still holds for what is retained, and the most
        // recently terminated node is the one a caller is most likely to still be holding.
        var newest = refs.get(cycles - 1);
        assertEquals(NodeLifecycleState.TERMINATED, engine.status(newest).orElseThrow().state());
        assertInstanceOf(NodeNotAcceptingException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine.send(newest, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));
        // And the honest cost of bounding it: past the bound a reference really is forgotten, and a
        // forgotten node is indistinguishable from one that never existed. That is a degradation, not
        // a free lunch, so it is asserted rather than left to be discovered.
        var oldest = refs.getFirst();
        assertTrue(engine.status(oldest).isEmpty());
        assertInstanceOf(IllegalArgumentException.class, rootCause(assertThrows(ExecutionException.class,
                () -> engine.send(oldest, message("late")).toCompletableFuture().get(5, TimeUnit.SECONDS))));
    }

    /**
     * The same bound on the path Ravenroot actually takes: a new {@code GraphRunner} per execution.
     *
     * <p>A raw spawn/stop loop is a fair model of the mechanism but not of the deployment. This runs
     * the shape the application layer runs — one long-lived engine, one runner and one set of nodes
     * per execution — because that is where "retention is bounded by spawn calls" stops being a
     * reassuring sentence and becomes growth proportional to traffic.</p>
     */
    @Test
    final void boundsTheNodeStateItRetainsAcrossManyGraphRunnerLifecycles() throws Exception {
        int executions = 600;
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.passthrough("middle"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "middle"),
                GraphEdge.to("middle", "end")));
        var engine = engine();
        var probes = new ArrayList<NodeRef>(executions);

        try (var manager = GraphManager.from(graph)) {
            for (int execution = 0; execution < executions; execution++) {
                // One directly held reference per execution, so the engine's retention stays
                // observable even though a runner does not publish the references it spawned.
                NodeRef probe = engine.spawn("probe", passthrough());
                probes.add(probe);
                try (var runner = new GraphRunner(manager, engine, new BehaviorRegistry(), new ExecutionMonitor())) {
                    runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                engine.stop(probe).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }

        long retained = probes.stream().filter(ref -> engine.status(ref).isPresent()).count();

        assertTrue(retained < executions,
                "retention must not grow one-for-one with executions, but " + retained + " of "
                        + executions + " probes from " + executions + " executions are still held");
        assertTrue(engine.status(probes.getFirst()).isEmpty(),
                "the first execution's node must not still be held after " + executions + " executions");
        assertEquals(NodeLifecycleState.TERMINATED, engine.status(probes.get(executions - 1)).orElseThrow().state(),
                "the most recent execution's node must still be distinguishable from one never issued");
    }

    @Test
    final void declaresAnImmutableAndStableCapabilitySet() {
        var first = engine().capabilities();
        var second = engine().capabilities();

        assertEquals(first, second, "capabilities must not change between calls");
        assertThrows(UnsupportedOperationException.class,
                () -> first.add(EngineCapability.TELEMETRY),
                "a caller must not be able to grant itself a capability the engine does not have");
    }

    @Test
    final void executesGraphMlFanOutFanInAndFallback() throws Exception {
        var registry = new BehaviorRegistry().register("append-a", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload() + "-a")));

        try (var manager = graphMlFixture();
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor())) {
            var definition = manager.definition();
            assertEquals("retained-by-core", definition.node("future").properties().get("customAttribute"));
            assertTrue(definition.edges().stream()
                    .anyMatch(edge -> "branch-a".equals(edge.properties().get("routeLabel"))));

            var result = runner.execute(TCK_IDENTITY, "root").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(5, result.visitedNodes().size());
            assertTrue(result.defaultedNodes().contains("future"));
            assertTrue(result.payload() instanceof List<?>);
            assertEquals(2, ((List<?>) result.payload()).size());
        }
    }

    @Test
    final void executesOnlyTheFirstArrivalAtAnAnyJoin() throws Exception {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("decision", "choose-left"),
                GraphNode.passthrough("left"),
                GraphNode.passthrough("right"),
                GraphNode.error("error"), new GraphNode("end", NodeKind.END, null, Map.of("joinPolicy", "any"))), List.of(
                GraphEdge.to("start", "decision"),
                new GraphEdge("decision", "left", "left"),
                new GraphEdge("decision", "right", "right"),
                GraphEdge.to("left", "end"),
                GraphEdge.to("right", "end")));
        var registry = new BehaviorRegistry().register("choose-left", message ->
                CompletableFuture.completedFuture(new NodeResult("left", message.payload(), message.attributes())));

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor())) {
            var result = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(result.visitedNodes().contains("end"));
            assertTrue(result.visitedNodes().contains("left"));
            assertTrue(!result.visitedNodes().contains("right"));
            assertEquals("payload", result.payload());
        }
    }

    // ------------------------------------------------------------------ CORE-03 fan-in fault matrix

    /**
     * Every combination of join policy and branch fault an adapter must produce the same answer for.
     *
     * <p>A matrix rather than a test per case, because the point is that the <em>same</em> topology
     * under a <em>different</em> fault yields a different, named outcome. Written once here so an
     * adapter cannot pass fan-in conformance on the happy path alone, and so Pekko and Akka cannot
     * drift: a framework-specific copy of these cases is exactly the duplication the TCK exists to
     * prevent.</p>
     */
    @TestFactory
    final Stream<DynamicTest> honoursTheFanInFaultMatrix() {
        return Stream.of(
                faultCase("all: every branch arrives", 3, Map.of(), Set.of(), false,
                        outcome -> assertEquals(List.of("b0", "b1", "b2"), outcome.payload)),
                faultCase("all: one branch fails", 3, Map.of(), Set.of("b1"), false,
                        outcome -> assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE,
                                outcome.joinFailure().reason())),
                faultCase("quorum 2 of 3: one branch fails", 3, quorum(2), Set.of("b1"), false,
                        outcome -> assertEquals(List.of("b0", "b2"), outcome.payload)),
                faultCase("quorum 2 of 3: two branches fail", 3, quorum(2), Set.of("b0", "b1"), false,
                        outcome -> assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE,
                                outcome.joinFailure().reason())),
                faultCase("quorum 1 of 3: two branches fail", 3, quorum(1), Set.of("b0", "b1"), false,
                        outcome -> assertEquals("b2", outcome.payload)),
                faultCase("quorum 2 of 3: one branch never returns", 3, quorum(2), Set.of(), true,
                        outcome -> assertEquals(List.of("b0", "b1"), outcome.payload)),
                faultCase("all: one branch never returns, deadline passes", 3,
                        Map.of(JoinSpec.QUORUM_PROPERTY, "3", JoinSpec.TIMEOUT_PROPERTY, "PT0.3S"),
                        Set.of(), true,
                        outcome -> assertEquals(JoinFailureException.Reason.TIMEOUT,
                                outcome.joinFailure().reason())),
                faultCase("quorum 1 of 3: a superseded branch arrives late", 3, quorum(1), Set.of(), true,
                        outcome -> assertTrue(outcome.payload instanceof String,
                                "one branch satisfied the quorum, so its payload is not wrapped")));
    }

    /** A refused model branch is observable without preventing an independent quorum-one success. */
    @Test
    final void absorbsAnUnconfiguredModelBranchWhenQuorumIsStillMet() throws Exception {
        RefusedModelQuorumFixture.assertQuorumOneCompletes(engine());
    }

    /** The same refusal fails the traversal when the remaining branch cannot meet quorum. */
    @Test
    final void failsWhenAnUnconfiguredModelBranchMakesQuorumUnreachable() throws Exception {
        RefusedModelQuorumFixture.assertUnmetQuorumFails(engine());
    }

    private DynamicTest faultCase(String name, int branches, Map<String, Object> joinProperties,
                                  Set<String> failing, boolean oneBranchBlocks,
                                  java.util.function.Consumer<FanInOutcome> expectation) {
        return DynamicTest.dynamicTest(name, () -> {
            var released = new CompletableFuture<NodeResult>();
            var registry = new BehaviorRegistry();
            String blocked = "b" + (branches - 1);
            for (int index = 0; index < branches; index++) {
                String branch = "b" + index;
                registry.register(branch, message -> {
                    if (failing.contains(branch)) {
                        return CompletableFuture.failedFuture(new IllegalStateException("branch " + branch));
                    }
                    if (oneBranchBlocks && branch.equals(blocked)) {
                        return released;
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(branch));
                });
            }
            var monitor = new ExecutionMonitor();
            // The blocked branch is released by the join's own verdict, never by a timer. That is
            // what the blocking cases are asserting: the join decided without this branch. The
            // traversal still waits for it afterwards, because PERS-01 forbids a completed traversal
            // holding a live invocation and nothing may cancel one until CORE-04.
            try (var subscription = monitor.subscribe(event -> {
                if (event.type() == ExecutionEventType.JOIN_SATISFIED
                        || event.type() == ExecutionEventType.JOIN_FAILED) {
                    released.complete(NodeResult.continueWith(blocked));
                }
            });
                 var manager = GraphManager.from(fanInGraph(branches, joinProperties));
                 var runner = new GraphRunner(manager, engine(), registry, monitor)) {
                var execution = runner.execute(TCK_IDENTITY, "in").toCompletableFuture();
                var outcome = new FanInOutcome();
                try {
                    outcome.payload = execution.get(10, TimeUnit.SECONDS).payload();
                } catch (java.util.concurrent.ExecutionException failure) {
                    outcome.error = failure.getCause();
                } finally {
                    // Belt and braces so the engine is never torn down with a node mid-message.
                    released.complete(NodeResult.continueWith(blocked));
                }
                expectation.accept(outcome);
            }
        });
    }

    private static Map<String, Object> quorum(int quorum) {
        return Map.of(JoinSpec.QUORUM_PROPERTY, String.valueOf(quorum));
    }


    // ---------------------------------------------------------------- Execution domains
    //
    // Mandatory single-pod semantics, so they live in the suite every adapter must pass rather than
    // behind a capability. What an adapter achieves UNDERNEATH -- a real supervision subtree, a
    // dedicated dispatcher -- is native and is asserted by that adapter's own tests; what every
    // adapter owes is the guarantee below.

    /**
     * The whole contract in one sentence: closing a domain terminates exactly its own nodes.
     *
     * <p>Both halves are asserted because both are failure modes. A close that missed its own node
     * would leave a deployment unstoppable; a close that reached beyond would let one deployment's
     * shutdown take another's nodes with it, which is the failure segregation exists to prevent.
     */
    @Test
    final void closingADomainTerminatesExactlyItsOwnNodes() throws Exception {
        ExecutionDomain first = engine().openDomain("first");
        ExecutionDomain second = engine().openDomain("second");
        NodeRef inFirst = first.spawn("in-first", passthrough());
        NodeRef alsoFirst = first.spawn("also-first", passthrough());
        NodeRef inSecond = second.spawn("in-second", passthrough());
        NodeRef outside = engine().spawn("outside", passthrough());

        first.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        assertTrue(status(inFirst).state().terminal(), "a domain's own node must be terminated by its close");
        assertTrue(status(alsoFirst).state().terminal(), "every node of the domain, not merely the first");
        assertEquals(NodeLifecycleState.RUNNING, status(inSecond).state(),
                "another domain's node must survive: closing one deployment must not stop another");
        assertEquals(NodeLifecycleState.RUNNING, status(outside).state(),
                "a node spawned outside any domain must survive");
        assertEquals("still here", send(inSecond, "still here").payload());
        assertEquals("still here", send(outside, "still here").payload());
    }

    /** Membership is observable, and it is exactly what was spawned into the domain. */
    @Test
    final void reportsExactlyTheNodesSpawnedIntoTheDomain() {
        ExecutionDomain domain = engine().openDomain("membership");
        NodeRef first = domain.spawn("first", passthrough());
        NodeRef second = domain.spawn("second", passthrough());
        NodeRef outside = engine().spawn("outside", passthrough());

        assertEquals(Set.of(first, second), domain.nodes());
        assertFalse(domain.nodes().contains(outside));
    }

    /**
     * Membership is <em>live</em> membership: a terminated node is no longer a member.
     *
     * <p>Previously, no adapter honoured "currently belonging", and while membership was fixed at
     * deployment startup nothing could tell. ADR 0024 creates one
     * {@code WORKER} actor per invocation, so a set that never shrinks grows with every invocation a
     * deployment ever runs -- and {@link ExecutionDomain#close()} would then settle every reference
     * ever issued, making shutdown scale with lifetime invocations instead of live work.
     *
     * <p>Asserted through {@code stop} rather than through a node that finishes on its own, because
     * stop is the one termination every adapter must support identically.
     */
    @Test
    final void aTerminatedNodeLeavesItsDomainMembership() throws Exception {
        ExecutionDomain domain = engine().openDomain("live-membership");
        NodeRef transient1 = domain.spawn("transient", passthrough());
        NodeRef resident = domain.spawn("resident", passthrough());
        assertEquals(Set.of(transient1, resident), domain.nodes());

        engine().stop(transient1).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(Set.of(resident), domain.nodes(),
                "a terminated node must leave its domain's membership. Without this the set grows "
                        + "with every node the domain ever spawned, which is unbounded once a worker "
                        + "actor exists per invocation rather than per graph node");
    }

    /**
     * The consequence that matters operationally: after everything terminates, membership is empty.
     *
     * <p>This is the assertion the zero-orphan guarantee is actually made with, so it is stated as
     * its own case rather than inferred from the one above. An adapter that removed a member only on
     * {@code cancel}, or only for the most recently spawned node, passes that one and fails this.
     */
    @Test
    final void membershipIsEmptyOnceEveryNodeHasTerminated() throws Exception {
        ExecutionDomain domain = engine().openDomain("drained-membership");
        var spawned = new ArrayList<NodeRef>();
        for (int index = 0; index < 8; index++) {
            spawned.add(domain.spawn("worker-" + index, passthrough()));
        }
        assertEquals(8, domain.nodes().size());

        for (NodeRef ref : spawned) {
            engine().stop(ref).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        assertTrue(domain.nodes().isEmpty(),
                "every node terminated, so nothing of this domain is alive and membership must say "
                        + "so. It reported: " + domain.nodes());
    }

    /** A domain's nodes are ordinary nodes: the engine's node contract is unchanged inside one. */
    @Test
    final void aNodeInADomainIsAnOrdinaryNode() throws Exception {
        ExecutionDomain domain = engine().openDomain("ordinary");
        NodeRef ref = domain.spawn("worker", passthrough());

        assertEquals("payload", send(ref, "payload").payload());
        assertEquals(NodeLifecycleState.RUNNING, status(ref).state());

        engine().stop(ref).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(NodeTerminationReason.STOPPED, status(ref).reason());
    }

    /** Idempotent, and every caller observes the same settled result. */
    @Test
    final void closingADomainTwiceIsIdempotent() throws Exception {
        ExecutionDomain domain = engine().openDomain("twice");
        NodeRef ref = domain.spawn("worker", passthrough());

        domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);
        domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        assertTrue(status(ref).state().terminal());
    }

    /** A closed domain admits nothing further; a deployment that stopped stays stopped. */
    @Test
    final void aClosedDomainRefusesFurtherSpawns() throws Exception {
        ExecutionDomain domain = engine().openDomain("refusing");
        domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        var refusal = assertThrows(IllegalStateException.class, () -> domain.spawn("late", passthrough()));
        assertTrue(refusal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("clos"),
                "a closed domain must refuse promptly and say so. Without asserting the reason this "
                        + "passes against an adapter that dropped its own check and merely timed out "
                        + "talking to a dead guardian -- a mutation this test's earlier shape once "
                        + "let through. It reported: " + refusal.getMessage());
    }

    /**
     * The property this engine's contribution to the shutdown budget depends on: domains close
     * CONCURRENTLY, not in series. <b>This is an engine-level property only.</b> Whether it holds at
     * the pod level -- across the deployments actually running on one pod -- is a separate question
     * answered by how the pod's own shutdown sequence invokes {@code close()} on each deployment's
     * domain, not by anything this test can see; see
     * {@code ai.ravenroot.server.deployment.DeploymentCapConfiguration}'s Javadoc for that answer as it
     * stands today (currently: it does not hold at the pod level -- deployments are closed one at a
     * time -- and the shutdown budget is sized accordingly, not assumed independent of {@code M}).
     *
     * <p><b>Proved by a barrier, not by a stopwatch.</b> An elapsed-time-ratio design -- comparing how
     * long eight concurrent closes take against one -- is a known-bad shape for this property: nodes
     * that stop instantly make eight serial closes as fast as one, so a mutation that makes closing
     * strictly serial can survive such a comparison outright; it is a control that could not fail. Here
     * every domain's node blocks inside its stop until all eight have arrived; if closes were
     * serialised the second would never start, the barrier would never trip and this test would time
     * out. No two elapsed times are ever compared against each other, so no machine speed can make it
     * pass or fail for the wrong reason.
     *
     * <p><b>Diagnosis: why an earlier barrier-based shape of this test still passed under a mutation
     * that serialises closing, and what the actual cause was — corrected after being disproved by
     * measurement.</b> That shape issued all eight {@code close()} calls from a single calling thread,
     * evaluated one at a time inside a single stream expression, and gave each blocked node's
     * {@code onStop} a private 30-second timeout on {@code release}. A mutation that makes
     * {@code close()} block its own caller until the domain has fully settled — indistinguishable from
     * "domains close in series" when observed from outside — survived it, passing after 160.5 seconds
     * instead of failing. <b>The private per-node timeout was NOT the cause: it was measured, not
     * assumed.</b> Tripling it from 30s to 90s left the elapsed time exactly unchanged (160.5s either
     * way), which a 30-second private escape firing eight times in sequence cannot produce. The actual
     * escape is {@code SubtreeDomain.close()}'s own two-phase bound, unrelated to the test's node
     * fixture: {@code settleAll(stop).orTimeout(10, SECONDS)} then, on that timeout,
     * {@code settleAll(cancel).orTimeout(10, SECONDS)} -- {@code .orTimeout} resolves the domain's
     * {@code close()} stage on its own clock regardless of whether the underlying node has actually
     * settled, so a caller blocked inside one domain's {@code close()} is released by the ENGINE after
     * about 20 seconds no matter how long the node fixture's own private wait is set to, as long as
     * that private wait is not shorter. Eight of those in series, one after another because a single
     * calling thread could only reach the next {@code close()} once the previous one returned, is
     * 8 x ~20s = ~160s -- exactly what was measured. This is D, the same per-domain close bound this
     * class's own {@link #closesDomainsConcurrentlyRatherThanInSeries()} measures and
     * {@code DeploymentCapConfiguration} cites — a legitimate, load-bearing production safety bound
     * (it is what keeps one wedged node from hanging its domain's close forever), not a test artifact,
     * and not something this test should or can remove from the scenario while still using a node that
     * blocks long enough to make arrival observable.
     *
     * <p>The fix is therefore singular, not twofold: <b>independent callers.</b> Each domain's
     * {@code close()} is now invoked from its own thread (a fixed pool sized to the domain count,
     * released together off a {@link CyclicBarrier} so they are issued together), so a mutation that
     * makes {@code close()} block its caller can only block that one thread — there is no shared
     * calling thread left for it to serialise the other seven onto. {@link #blockingOnStop}'s own
     * private timeout on {@code release} is unchanged from before this fix and is not what makes this
     * test correct; it is a hygiene safety net (see its own Javadoc), and the {@code finally} block
     * below that always fires {@code release.countDown()} — whether the assertion passed or failed — is
     * a faster, deterministic version of that same hygiene, not a detection mechanism.
     *
     * <p><b>The bound also had to be tightened, not just the issuance.</b> Independent callers alone
     * defeats a caller-blocking mutation, but a mutation that serialises the engine's OWN dispatch of
     * domain closes — e.g. routing every domain's close body through one shared, width-{@code W}
     * thread pool inside the adapter — is a different, more direct violation of "domains close
     * concurrently", and is only caught if the wait below is short enough to notice it. With {@code W}
     * threads serving {@code width} domains, batches of {@code W} close concurrently but each batch
     * still pays the engine's own ~20-second (D) bound, so the total time for every domain to at least
     * BEGIN closing is {@code (ceil(width / W) - 1) * D}. A wait of 30 seconds only ever detects
     * {@code W <= 3} (2 batches, ({@literal 3-1}) x 20 = 40s > 30s already borderline; {@code W = 4}
     * gives ({@literal 2-1}) x 20 = 20s, comfortably under 30s, and passes -- verified: a real mutation
     * routing this engine's domain closes through a shared {@code newFixedThreadPool(4)} passes this
     * test in ~20.4s at a 30-second wait). A 10-second wait detects every {@code W} from 1 up to
     * {@code width - 1} (the narrowest escapable case, one extra batch, costs exactly D = ~20s, which
     * is comfortably above 10s), while staying well above the sub-second time a genuinely concurrent
     * close takes even under heavy machine load. {@code width} itself is fixed at 8 to match
     * {@code DeploymentCapConfiguration.DEFAULT_MAX_ACTIVE_DEPLOYMENTS} — a shared pool sized to accept
     * all 8 at once is indistinguishable from true per-domain concurrency for a pod actually running 8
     * deployments, which is not a gap in this test, it is the operational bound this test exists to
     * protect.
     */
    @Test
    final void closesDomainsConcurrentlyRatherThanInSeries() throws Exception {
        int width = 8;
        var arrived = new CountDownLatch(width);
        var release = new CountDownLatch(1);
        List<ExecutionDomain> domains = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            ExecutionDomain domain = engine().openDomain("concurrent-" + i);
            domain.spawn("blocker", blockingOnStop(arrived, release));
            domains.add(domain);
        }

        // Every close() is issued from its own thread, released together off a barrier, so a mutation
        // that makes close() block its caller cannot serialise issuance -- there is no single calling
        // thread left for it to serialise onto.
        var ready = new CyclicBarrier(width);
        var pool = Executors.newFixedThreadPool(width);
        try {
            List<CompletableFuture<Void>> closes = new ArrayList<>();
            for (ExecutionDomain domain : domains) {
                closes.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        ready.await(30, TimeUnit.SECONDS);
                    } catch (Exception barrierFailure) {
                        throw new RuntimeException("domain " + domain.name()
                                + " never reached the issuance barrier", barrierFailure);
                    }
                    return domain.close();
                }, pool).thenCompose(java.util.function.Function.identity()).toCompletableFuture());
            }

            try {
                // 10s, not 30s: short enough that even one extra serialised batch (~20s, this
                // engine's own per-domain close bound D) is caught, long enough that a genuinely
                // concurrent close -- sub-second, even under heavy machine load -- never comes close.
                // See this method's own Javadoc for the discrimination-boundary arithmetic and the
                // mutation that a 30-second wait let through.
                assertTrue(arrived.await(10, TimeUnit.SECONDS),
                        "only " + (width - arrived.getCount()) + " of " + width + " domains began "
                                + "closing within 10 seconds. Domains are closing in series (or in "
                                + "batches narrower than " + width + "), so a pod's shutdown budget "
                                + "grows with the number of deployments closing concurrently on it, "
                                + "which DeploymentCapConfiguration's shutdown-budget arithmetic must "
                                + "account for");
            } finally {
                // Not required for detection (see this method's Javadoc): a faster, deterministic
                // version of the hygiene blockingOnStop's own private timeout already provides.
                release.countDown();
            }

            CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * A node whose stop blocks until every peer has also begun stopping.
     *
     * <p>The private 30-second timeout on {@code release} is hygiene only -- a safety net so a bug in
     * this test's own control flow cannot leak a thread blocked forever -- and is not what makes
     * {@link #closesDomainsConcurrentlyRatherThanInSeries()} correct. That was tested directly: tripling
     * it to 90 seconds changes nothing about whether or how fast that test catches a serialising
     * mutation, because the actual escape a serialised close relies on is the production engine's own
     * per-domain close bound (~20s), not this fixture's timeout, as long as this timeout is not shorter
     * than that. See that method's own Javadoc for the full, measurement-corrected diagnosis.
     */
    private static RavenNode blockingOnStop(java.util.concurrent.CountDownLatch arrived,
                                            java.util.concurrent.CountDownLatch release) {
        return new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                return CompletableFuture.completedFuture(
                        new NodeResult("continue", message.payload(), Map.of()));
            }

            @Override
            public void onStop(NodeContext context) {
                arrived.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private static GraphDefinition fanInGraph(int branches, Map<String, Object> joinProperties) {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(GraphNode.start("start"));
        for (int index = 0; index < branches; index++) {
            String branch = "b" + index;
            nodes.add(GraphNode.behavior(branch, branch));
            edges.add(GraphEdge.to("start", branch));
            edges.add(GraphEdge.to(branch, "join"));
        }
        nodes.add(new GraphNode("join", NodeKind.PASSTHROUGH, null, joinProperties));
        nodes.add(GraphNode.error("error"));
        nodes.add(GraphNode.end("end"));
        edges.add(GraphEdge.to("join", "end"));
        return new GraphDefinition(nodes, edges);
    }

    /** One matrix cell's result: a payload or an error, never both. */
    private static final class FanInOutcome {
        private Object payload;
        private Throwable error;

        private JoinFailureException joinFailure() {
            Throwable current = error;
            while (current != null) {
                if (current instanceof JoinFailureException failure) {
                    return failure;
                }
                current = current.getCause();
            }
            throw new AssertionError("expected a join failure, got payload=" + payload, error);
        }
    }

    // ------------------------------------------------------- ARC-02 pinned-execution integrity
    //
    // These three cases belong in the shared conformance contract rather than in core, because what
    // they assert is a property of an *engine adapter's* execution: that a traversal already in
    // flight keeps running against the topology it started with, whichever actor runtime is
    // underneath. An adapter that re-resolved routing against live shared state would pass core's
    // own tests and fail here, which is the entire reason the case is stated once, for every engine.
    //
    // They deliberately do NOT assert anything about GraphManager's public API. Manager immutability
    // is ARC-05's concern and is covered in GraphManagerReadOnlyQueryTest; conflating the two is what
    // produced contradictory requirements on the same method.

    /**
     * A mutation that reaches the manager after the runner exists does not change what the runner
     * executes.
     *
     * <p>The mutation is applied through the {@code getGraph()} escape, not through {@code query()}.
     * Under ARC-05, {@code query()} carries {@code ReadOnlyStrategy} and would refuse the
     * mutating step, so routing it that way would assert ARC-05's refusal and prove nothing about
     * isolation. Going through the escape means the manager's graph <em>really is</em> mutated — the
     * node is genuinely gone — which is the only version of this test that distinguishes an isolated
     * runner from a refused write.</p>
     */
    @Test
    final void runnerUsesItsPinnedTopologyAfterManagerMutationAttempt() throws Exception {
        try (var manager = GraphManager.from(linearGraph("passthrough"));
             var runner = new GraphRunner(manager, engine(), passthroughRegistry(), new ExecutionMonitor())) {

            dropThroughEscape(manager, "worker");
            // Precondition: the manager really lost the node. Without this the test would still pass
            // if the mutation had silently failed to apply.
            assertEquals(3, manager.nodeCount(),
                    "the escape must actually mutate the manager (four nodes including the error terminal, minus the one just dropped)");

            var result = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "worker", "end"), result.visitedNodes());
            assertEquals("payload", result.payload());
        }
    }

    /**
     * The failure this exists to prevent: a mid-run mutation turning a traversal into a
     * <em>successful</em> execution that stopped early.
     *
     * <p>A truncated success is worse than a failure. A caller that receives an error retries; a
     * caller that receives success with a short {@code visitedNodes} has no way to tell it apart from
     * a traversal that legitimately ended there, so the loss is silent and permanent.</p>
     */
    @Test
    final void midRunManagerMutationAttemptCannotProduceTruncatedSuccess() throws Exception {
        var workerStarted = new CountDownLatch(1);
        var workerResult = new CompletableFuture<NodeResult>();
        var registry = new BehaviorRegistry().register("controlled-worker", message -> {
            workerStarted.countDown();
            return workerResult;
        });

        try (var manager = GraphManager.from(linearGraph("controlled-worker"));
             var runner = new GraphRunner(manager, engine(), registry, new ExecutionMonitor())) {
            var execution = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture();
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker never started");

            // The traversal is parked on the worker. Delete the node it is about to route to.
            dropThroughEscape(manager, "end");
            assertEquals(3, manager.nodeCount(),
                    "the escape must actually mutate the manager (four nodes including the error terminal, minus the one just dropped)");

            workerResult.complete(NodeResult.continueWith("completed"));

            var result = execution.get(5, TimeUnit.SECONDS);
            assertEquals(Set.of("start", "worker", "end"), result.visitedNodes());
            assertEquals("completed", result.payload());
        }
    }

    /**
     * The control case, and the only one that separates "the runner is isolated" from
     * "{@code query()} is isolated".
     *
     * <p>The other two tests remove one node, so a runner that still consulted the manager could in
     * principle produce the expected answer for some unrelated reason. This one empties the manager's
     * graph completely — <em>including the start node</em> — and then closes it. There is no topology
     * left to read: a runner that still resolved {@code start()} per traversal, or {@code next()} per
     * dispatch, could not complete at all. Completing therefore proves the runner holds no live
     * reference to the manager, which is what makes the isolation structural rather than defended:
     * no mutation path, present or future, and including any that bypasses {@code query()} entirely,
     * can truncate a run.</p>
     *
     * <p><b>Note on {@code close()}, measured rather than assumed.</b> This test was first written to
     * rely on closing the manager alone, on the assumption that a closed graph cannot be traversed.
     * That assumption is false here: {@code TinkerGraph#close()} on an in-memory graph with no
     * persistence configured is effectively a no-op, and {@code nodeCount()} still answers afterwards.
     * Closing is therefore kept for realism but is explicitly <em>not</em> what this test rests on —
     * emptying the topology is. A control whose premise does not hold proves nothing, and this one
     * would have passed while proving nothing.</p>
     */
    @Test
    final void runnerCompletesAfterItsGraphManagerIsEmptiedAndClosed() throws Exception {
        var manager = GraphManager.from(linearGraph("passthrough"));
        try (var runner = new GraphRunner(manager, engine(), passthroughRegistry(), new ExecutionMonitor())) {
            emptyThroughEscape(manager);
            // Guard the guard: if the graph were not actually empty this test would pass for a
            // reason that has nothing to do with isolation.
            assertEquals(0, manager.nodeCount(), "the manager's topology must really be gone");
            assertEquals(0, manager.edgeCount(), "the manager's topology must really be gone");
            manager.close();

            var result = runner.execute(TCK_IDENTITY, "payload").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "worker", "end"), result.visitedNodes());
            assertEquals("payload", result.payload());
        }
    }

    /**
     * Deletes {@code nodeId} from the manager's own graph through {@code GraphTraversalSource#getGraph()},
     * the escape ARC-05's {@code ReadOnlyStrategy} does not close. Asserts that the strategy would have
     * refused the same step, so these tests cannot start passing because the strategy quietly stopped
     * working, and so closing the escape one day surfaces here rather than silently.
     */
    private static void dropThroughEscape(GraphManager manager, String nodeId) {
        assertThrows(
                org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException.class,
                () -> manager.query(traversal -> {
                    traversal.V(nodeId).drop().iterate();
                    return null;
                }),
                "ReadOnlyStrategy must still refuse the step form of this mutation");
        manager.query(traversal -> {
            traversal.getGraph().traversal().V(nodeId).drop().iterate();
            return null;
        });
    }

    /** Removes every vertex, and with it every edge, through the same unguarded escape. */
    private static void emptyThroughEscape(GraphManager manager) {
        manager.query(traversal -> {
            traversal.getGraph().traversal().V().drop().iterate();
            return null;
        });
    }

    private static BehaviorRegistry passthroughRegistry() {
        return new BehaviorRegistry().register("passthrough", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
    }

    private static GraphDefinition linearGraph(String behavior) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("worker", behavior),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "worker"),
                GraphEdge.to("worker", "end")));
    }

    private static GraphManager graphMlFixture() {
        try (var input = ExecutionEngineContract.class.getResourceAsStream(
                "/fixtures/engine-conformance.graphml")) {
            if (input == null) {
                throw new IllegalStateException("Missing engine-conformance.graphml test fixture");
            }
            return GraphManager.readGraphMl(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot close engine conformance fixture", exception);
        }
    }

    private static RavenNode passthrough() {
        return (message, context) -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload()));
    }

    private NodeStatus status(NodeRef ref) {
        return engine().status(ref)
                .orElseThrow(() -> new AssertionError("The engine forgot a node it issued: " + ref.value()));
    }

    private NodeResult send(ai.ravenroot.api.execution.NodeRef ref, Object payload) throws Exception {
        return engine().send(ref, message(payload)).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static NodeMessage message(Object payload) {
        return new NodeMessage(TCK_IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "tck-node", payload, Map.of());
    }

    /**
     * A short randomised spin, so barrier participants do not all resume in lockstep.
     *
     * <p>A {@link CyclicBarrier} releases every thread at once, which samples one interleaving very
     * hard and the neighbouring ones hardly at all. The window that matters here is a few instructions
     * wide and sits <em>inside</em> the operations, not at their start.</p>
     */
    private static void jitter() {
        int spins = java.util.concurrent.ThreadLocalRandom.current().nextInt(64);
        for (int spin = 0; spin < spins; spin++) {
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Conformance latch was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
