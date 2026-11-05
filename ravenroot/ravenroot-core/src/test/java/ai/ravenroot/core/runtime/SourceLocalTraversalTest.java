package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real deployment/engine/store proof for source-local entry and graph-edge boundaries. */
class SourceLocalTraversalTest {
    @TempDir Path directory;
    private static final ai.ravenroot.api.security.SecurityContext IDENTITY = TestIdentities.TENANT_A;
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="source-local" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="prepare"><data key="kind">behavior</data><data key="behavior">probe</data></node>
                <node id="a"><data key="kind">behavior</data><data key="behavior">source</data></node>
                <node id="process-a"><data key="kind">behavior</data><data key="behavior">probe</data></node>
                <node id="b"><data key="kind">behavior</data><data key="behavior">source</data></node>
                <node id="process-b"><data key="kind">behavior</data><data key="behavior">probe</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="prepare"/>
                <edge source="prepare" target="a"/>
                <edge source="a" target="process-a"/>
                <edge source="process-a" target="b"/>
                <edge source="b" target="process-b"/>
                <edge source="process-b" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void deploymentActivatesBothSourcesWithoutStartAndEachEventUsesOnlyItsOwnPath() throws Exception {
        try (var engine = new SameThreadExecutionEngine()) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var deployment = deployment(engine, null, contexts, effects, monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Set.of("a", "b"), contexts.keySet());
            assertTrue(effects.isEmpty(), "deployment alone must not execute START");
            assertEquals(IngressDisposition.ACCEPTED, contexts.get("a").ingress()
                    .offer(IDENTITY, IngressTarget.start(), "event-a"));
            assertEquals(List.of("process-a:event-a"), effects);
            assertTrue(monitor.eventsAfter(0).stream().anyMatch(e -> e.type() == ExecutionEventType.SOURCE_BOUNDARY
                    && "b".equals(e.nodeId())));
            effects.clear();
            assertEquals(IngressDisposition.ACCEPTED, contexts.get("b").ingress()
                    .offer(IDENTITY, IngressTarget.node("b"), "event-b"));
            assertEquals(List.of("process-b:event-b"), effects);
            assertThrows(IllegalArgumentException.class, () -> contexts.get("a").ingress()
                    .offer(IDENTITY, IngressTarget.node("b"), "foreign"));
            assertThrows(IllegalArgumentException.class, () -> deployment.ingress()
                    .offer(IDENTITY, IngressTarget.node("prepare"), "worker"));
            assertThrows(IllegalArgumentException.class, () -> deployment.ingress()
                    .offer(IDENTITY, IngressTarget.node("absent"), "unknown"));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(IngressDisposition.REJECTED_ADMISSION_CLOSED, contexts.get("a").ingress()
                    .offer(IDENTITY, IngressTarget.start(), "retired"));
        }
    }

    @Test void startStopsAtSourceAndHasNoGhostTraversalOrDownstreamEffect() throws Exception {
        try (var engine = new SameThreadExecutionEngine()) {
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var deployment = deployment(engine, null, new ConcurrentHashMap<>(), effects, monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(IngressDisposition.ACCEPTED, deployment.ingress().offer(IDENTITY,
                    IngressTarget.start(), "initial"));
            assertEquals(List.of("prepare:initial"), effects);
            assertTrue(monitor.eventsAfter(0).stream().anyMatch(e -> e.type() == ExecutionEventType.SOURCE_BOUNDARY
                    && "a".equals(e.nodeId())));
            assertTrue(monitor.eventsAfter(0).stream().anyMatch(e -> e.type() == ExecutionEventType.EXECUTION_COMPLETED));
            assertEquals(DeploymentState.READY, deployment.status().state());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void sourceCycleStopsAtSameSourceWithOneAdmissionSlot() throws Exception {
        try (var engine = new SameThreadExecutionEngine()) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            String loop = GRAPH.replace("<graph id=", "<key id=\"concurrency\" for=\"node\" "
                    + "attr.name=\"runtime.maxConcurrency\" attr.type=\"int\"/><graph id=")
                    .replace("<node id=\"a\"><data key=\"kind\">behavior</data>",
                            "<node id=\"a\"><data key=\"concurrency\">1</data><data key=\"kind\">behavior</data>")
                    .replace("<edge source=\"process-a\" target=\"b\"/>", "<edge source=\"process-a\" target=\"a\"/>");
            var deployment = deployment(engine, null, contexts, effects, monitor, loop);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var finished = new CountDownLatch(1);
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) finished.countDown(); });
            assertEquals(IngressDisposition.ACCEPTED, contexts.get("a").ingress()
                    .offer(IDENTITY, IngressTarget.start(), "cycle"));
            assertTrue(finished.await(5, TimeUnit.SECONDS), "source reentry must not wait behind its own permit");
            assertEquals(List.of("process-a:cycle"), effects);
            assertEquals(1, monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.SOURCE_BOUNDARY).count());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void durableAdmissionPinsActualEntryAndRedeliveryDoesNotRunStart() throws Exception {
        try (var engine = new SameThreadExecutionEngine();
             var store = new SqliteExecutionStore(directory.resolve("entry.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var completed = new CountDownLatch(1);
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) completed.countDown(); });
            var deployment = deployment(engine, store, contexts, effects, monitor);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, contexts.get("a").ingress()
                    .offerDurably(IDENTITY, IngressTarget.start(), "durable", "a", "event-1"));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("process-a:durable"), effects);
            ExecutionEvent event = monitor.eventsAfter(0).stream()
                    .filter(e -> e.type() == ExecutionEventType.EXECUTION_STARTED).findFirst().orElseThrow();
            var stored = store.load(new ExecutionKey(IDENTITY.tenantId(), event.processInstanceId()))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("a", stored.state().traversals().get(event.traversalId()).ingressNodeId());
            assertInstanceOf(IngressReceipt.Duplicate.class, contexts.get("a").ingress()
                    .offerDurably(IDENTITY, IngressTarget.start(), "durable", "a", "event-1"));
            assertEquals(1, effects.size());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void correlationIsExtractedBeforeEffectsAndSeparateEventsKeepTheirOriginalIdentities() throws Exception {
        try (var engine = new SameThreadExecutionEngine();
             var store = new SqliteExecutionStore(directory.resolve("correlation.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var completed = new CountDownLatch(2);
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) completed.countDown(); });
            String mapped = GRAPH.replace("<graph id=", """
                    <key id="cm" for="node" attr.name="correlation.mode" attr.type="string"/>
                    <key id="cs" for="node" attr.name="correlation.selector" attr.type="string"/>
                    <key id="cc" for="node" attr.name="correlation.contributionSelector" attr.type="string"/>
                    <graph id=""")
                    .replace("<node id=\"a\"><data key=\"kind\">behavior</data>",
                            "<node id=\"a\"><data key=\"cm\">ACCEPT_OR_CREATE</data>"
                                    + "<data key=\"cs\">/metadata/ingestionId</data><data key=\"cc\">/partition</data>"
                                    + "<data key=\"kind\">behavior</data>");
            var deployment = deployment(engine, store, contexts, effects, monitor, mapped);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            for (int i = 1; i <= 2; i++) {
                var input = Map.of("metadata", Map.of("ingestionId", "ingestion-42"), "partition", "part-" + i);
                assertInstanceOf(IngressReceipt.DurablyCommitted.class, contexts.get("a").ingress()
                        .offerDurably(IDENTITY, IngressTarget.node("a"), input, "a", "event-" + i));
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            var starts = monitor.eventsAfter(0).stream().filter(e -> e.type() == ExecutionEventType.EXECUTION_STARTED).toList();
            assertEquals(2, starts.size());
            assertEquals(2, starts.stream().map(ExecutionEvent::processInstanceId).distinct().count());
            assertEquals(2, starts.stream().map(ExecutionEvent::traversalId).distinct().count());
            assertTrue(starts.stream().allMatch(e -> e.applicationCorrelation().applicationCorrelationId().equals("ingestion-42")));
            var scope = new ai.ravenroot.api.correlation.BusinessScope(IDENTITY.tenantId(), "source-local", starts.getFirst().graphVersion());
            var history = store.businessHistory(scope, "ingestion-42").toCompletableFuture().get().orElseThrow();
            assertEquals(2, history.completedTraversals());
            assertEquals(0, history.runningTraversals());
            assertEquals(ai.ravenroot.api.correlation.BusinessHistory.State.AWAITING_EXTERNAL_EVENT, history.state());
            var traversals = store.correlatedTraversals(scope, "ingestion-42", Optional.empty(), 10).toCompletableFuture().get();
            assertEquals(Set.of("part-1", "part-2"), traversals.items().stream()
                    .map(t -> t.context().contributionId().orElseThrow()).collect(java.util.stream.Collectors.toSet()));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void sixPartitionsFromOneSourceReleaseOneNewTraversalAcrossDeploymentRestart() throws Exception {
        try (var engine = new SameThreadExecutionEngine();
             var store = new SqliteExecutionStore(directory.resolve("join-runtime.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var firstFive = new CountDownLatch(5);
            var allSeven = new CountDownLatch(7);
            monitor.subscribe(event -> {
                if (event.type().isTraversalTerminal()) { firstFive.countDown(); allSeven.countDown(); }
            });
            String joined = GRAPH.replace("<graph id=", """
                    <key id="js" for="graph" attr.name="join.semantics" attr.type="string"/>
                    <key id="cm" for="node" attr.name="correlation.mode" attr.type="string"/>
                    <key id="cs" for="node" attr.name="correlation.selector" attr.type="string"/>
                    <key id="cc" for="node" attr.name="correlation.contributionSelector" attr.type="string"/>
                    <key id="jm" for="node" attr.name="correlatedJoin.mode" attr.type="string"/>
                    <key id="jc" for="node" attr.name="correlatedJoin.expectedCount" attr.type="int"/>
                    <graph id=""")
                    .replace("edgedefault=\"directed\">", "edgedefault=\"directed\"><data key=\"js\">declared</data>")
                    .replace("<node id=\"a\"><data key=\"kind\">behavior</data>",
                            "<node id=\"a\"><data key=\"cm\">ACCEPT_OR_CREATE</data>"
                                    + "<data key=\"cs\">/operation</data><data key=\"cc\">/partition</data>"
                                    + "<data key=\"kind\">behavior</data>")
                    .replace("<node id=\"process-a\"><data key=\"kind\">behavior</data>",
                            "<node id=\"process-a\"><data key=\"jm\">FIXED_COUNT</data><data key=\"jc\">6</data>"
                                    + "<data key=\"kind\">behavior</data>")
                    .replace("<edge source=\"start\" target=\"prepare\"/>", "<edge source=\"start\" target=\"end\"/>")
                    .replace("<edge source=\"prepare\" target=\"a\"/>", "")
                    .replace("<edge source=\"process-a\" target=\"b\"/>", "<edge source=\"process-a\" target=\"end\"/>");
            var deployment = deployment(engine, store, contexts, effects, monitor, joined);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            for (int i = 1; i <= 5; i++) assertInstanceOf(IngressReceipt.DurablyCommitted.class,
                    contexts.get("a").ingress().offerDurably(IDENTITY, IngressTarget.node("a"),
                            Map.of("operation", "six-partitions", "partition", "part-" + i), "a", "part-" + i));
            assertTrue(firstFive.await(5, TimeUnit.SECONDS));
            assertTrue(effects.isEmpty(), "one predecessor is not one business contribution");
            var firstStart = monitor.eventsAfter(0).stream().filter(e -> e.type() == ExecutionEventType.EXECUTION_STARTED).findFirst().orElseThrow();
            var scope = new ai.ravenroot.api.correlation.BusinessScope(IDENTITY.tenantId(), "source-local", firstStart.graphVersion());
            var key = new ai.ravenroot.api.correlation.CorrelatedJoinKey(scope, "six-partitions", "process-a", "0");
            assertEquals(5, store.correlatedJoin(key).toCompletableFuture().get().orElseThrow().contributions().size(),
                    () -> monitor.eventsAfter(0).stream().filter(e -> e.type() == ExecutionEventType.EXECUTION_FAILED)
                            .map(ExecutionEvent::detail).toList().toString());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            deployment = deployment(engine, store, contexts, effects, monitor, joined);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, contexts.get("a").ingress().offerDurably(IDENTITY,
                    IngressTarget.node("a"), Map.of("operation", "six-partitions", "partition", "part-6"), "a", "part-6"));
            assertTrue(allSeven.await(10, TimeUnit.SECONDS));
            assertEquals(1, effects.size());
            var record = store.correlatedJoin(key).toCompletableFuture().get().orElseThrow();
            assertEquals(6, record.contributions().size());
            var continuation = store.load(new ExecutionKey(IDENTITY.tenantId(), key.continuationProcessId())).toCompletableFuture().get();
            var traversal = continuation.state().traversals().get(key.continuationTraversalId());
            assertEquals(TraversalStatus.COMPLETED, traversal.status(), () -> monitor.eventsAfter(0).stream()
                    .filter(e -> e.processInstanceId().equals(key.continuationProcessId())).map(e -> e.type() + ":" + e.detail()).toList().toString());
            assertTrue(traversal.invocations().values().stream().filter(v -> v.nodeId().equals("process-a"))
                    .allMatch(v -> v.parentInvocationIds().isEmpty()), "cross-process causality has its own retained relation");
            assertEquals(ai.ravenroot.api.correlation.CorrelatedTraversal.Cause.JOIN_CONTINUATION,
                    store.correlatedTraversal(IDENTITY.tenantId(), key.continuationProcessId(), key.continuationTraversalId())
                            .toCompletableFuture().get().orElseThrow().cause());
            var history = store.businessHistory(scope, "six-partitions").toCompletableFuture().get().orElseThrow();
            assertEquals(7, history.completedTraversals());
            assertEquals(0, history.runningTraversals());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void invalidSourceMappingReturnsSafeSelectorReasonBeforeAnyEffect() throws Exception {
        try (var engine = new SameThreadExecutionEngine();
             var store = new SqliteExecutionStore(directory.resolve("invalid-correlation.db"), Clock.systemUTC())) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var effects = new CopyOnWriteArrayList<String>();
            var monitor = new ExecutionMonitor();
            var deployment = deployment(engine, store, contexts, effects, monitor, CorrelatedJoinRuntimeRecoveryTest.GRAPH);
            deployment.start(IDENTITY).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var refusal = assertInstanceOf(IngressReceipt.Refused.class, contexts.get("a").ingress().offerDurably(
                    IDENTITY, IngressTarget.node("a"), Map.of("operation", Map.of("secret", "do-not-echo"), "partition", "part-1"),
                    "a", "invalid-input"));
            assertTrue(refusal.reason().contains("/operation")); assertTrue(refusal.reason().contains("NOT_SCALAR_TEXT"));
            assertFalse(refusal.reason().contains("do-not-echo")); assertTrue(effects.isEmpty());
            assertTrue(monitor.eventsAfter(0).stream().noneMatch(event -> event.type() == ExecutionEventType.EXECUTION_STARTED));
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store,
            Map<String, InboundSourceContext> contexts, List<String> effects, ExecutionMonitor monitor) {
        return deployment(engine, store, contexts, effects, monitor, GRAPH);
    }

    static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store,
            Map<String, InboundSourceContext> contexts, List<String> effects, ExecutionMonitor monitor, String graph) {
        return deployment(engine, store, contexts, effects, monitor, graph, Clock.systemUTC());
    }
    static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store,
            Map<String, InboundSourceContext> contexts, List<String> effects, ExecutionMonitor monitor, String graph, Clock clock) {
        return deployment(engine, store, contexts, effects, monitor, graph, clock, registry -> registry);
    }
    static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, ExecutionStore store,
            Map<String, InboundSourceContext> contexts, List<String> effects, ExecutionMonitor monitor, String graph, Clock clock,
            java.util.function.UnaryOperator<BehaviorRegistry> customize) {
        var source = new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("source", "Source", "Test", "Source", "actor", false,
                        List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                throw new AssertionError("SOURCE ordinary handler must never be composed");
            }
        };
        class Source implements NodeBehavior, InboundSourceCapable {
            @Override public NodeTypeDescriptor descriptor() { return source.descriptor(); }
            @Override public NodeAction create(NodeConfiguration configuration) { return source.create(configuration); }
            @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
                return new InboundSource() {
                    @Override public CompletionStage<Void> start(InboundSourceContext active) {
                        contexts.put(active.nodeId(), active);
                        return CompletableFuture.completedFuture(null);
                    }
                    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
                };
            }
        }
        var nodePackage = new NodePackage() {
            @Override public String id() { return "test.source-local"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(new Source()); }
        };
        var registry = NodePackages.register(new BehaviorRegistry(), nodePackage)
                .register("probe", message -> {
                    effects.add(message.nodeId() + ":" + message.payload());
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
        return new DefaultGraphDeployment(DeploymentId.of("source-local"), engine, customize.apply(registry), monitor,
                ExecutionIdentitySource.randomUuids(), graph.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "source-local-test", Duration.ofSeconds(30),
                RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY), clock);
    }
}
