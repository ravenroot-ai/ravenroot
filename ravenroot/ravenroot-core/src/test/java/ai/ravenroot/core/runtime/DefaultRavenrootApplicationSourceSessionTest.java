package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.SourceSessionException;
import ai.ravenroot.api.application.SourceSessionState;
import ai.ravenroot.api.application.SourceSessionStatus;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end core contract for the editor's process-local inbound-source sessions. */
class DefaultRavenrootApplicationSourceSessionTest {
    private static final SecurityContext TENANT_A = identity("tenant-a");
    private static final SecurityContext TENANT_B = identity("tenant-b");

    @Test
    void startIsTenantScopedAndIdempotentAndCreatesNoTraversalUntilEachExternalEvent() throws Exception {
        var engine = new SameThreadExecutionEngine();
        var monitor = new ExecutionMonitor();
        var behavior = new RecordingSourceBehavior();
        var application = application(engine, monitor, behavior);
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var completed = new CountDownLatch(2);
        try (var ignored = monitor.subscribe(event -> {
            events.add(event);
            if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) completed.countDown();
        })) {
            SourceSessionStatus accepted = application.startSourceSession(
                    TENANT_A, "editor-session", graph(SOURCE_GRAPH));
            assertTrue(accepted.state() == SourceSessionState.STARTING
                    || accepted.state() == SourceSessionState.LISTENING);
            SourceSessionStatus listening = awaitState(application, TENANT_A.tenantId(),
                    "editor-session", SourceSessionState.LISTENING);
            assertEquals(1, listening.sourceCount());
            assertEquals("LOCAL_PROCESS", SourceSessionStatus.SCOPE);
            assertTrue(events.isEmpty(), "starting listeners must not fabricate a traversal or payload");

            SourceSessionStatus rejoined = application.startSourceSession(
                    TENANT_A, "editor-session", graph(SOURCE_GRAPH));
            assertEquals(SourceSessionState.LISTENING, rejoined.state());
            assertEquals(1, behavior.starts.get(), "idempotent start must not open a second listener");

            var conflict = assertThrows(SourceSessionException.class, () -> application.startSourceSession(
                    TENANT_A, "editor-session", graph(SOURCE_GRAPH.replace("id=\"g\"", "id=\"g2\""))));
            assertEquals(SourceSessionException.Reason.GRAPH_CONFLICT, conflict.reason());

            application.startSourceSession(TENANT_B, "editor-session", graph(SOURCE_GRAPH));
            awaitState(application, TENANT_B.tenantId(), "editor-session", SourceSessionState.LISTENING);
            assertEquals(2, behavior.starts.get(), "the same browser id in another tenant is a sibling session");

            InboundSourceContext tenantAContext = behavior.contexts.stream()
                    .filter(context -> context.identity().tenantId().equals(TENANT_A.tenantId()))
                    .findFirst().orElseThrow();
            assertEquals(IngressDisposition.ACCEPTED, tenantAContext.ingress().offer(
                    tenantAContext.identity(), IngressTarget.start(), "first-event"));
            assertEquals(IngressDisposition.ACCEPTED, tenantAContext.ingress().offer(
                    tenantAContext.identity(), IngressTarget.start(), "second-event"));
            assertTrue(completed.await(10, TimeUnit.SECONDS), "both external events must finish their traversals");
            assertEquals(List.of("first-event", "second-event"), behavior.payloads);
            assertEquals(2, events.stream().map(ExecutionEvent::traversalId).distinct().count(),
                    "each inbound event must own a distinct traversal");

            application.stopSourceSession(TENANT_A.tenantId(), "editor-session")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(SourceSessionState.STOPPED,
                    application.sourceSession(TENANT_A.tenantId(), "editor-session").orElseThrow().state());
            assertEquals(SourceSessionState.LISTENING,
                    application.sourceSession(TENANT_B.tenantId(), "editor-session").orElseThrow().state(),
                    "stopping one tenant must leave its sibling and the shared engine running");
            assertEquals(1, behavior.stopsByTenant.get(TENANT_A.tenantId()).get());
            assertFalse(behavior.stopsByTenant.containsKey(TENANT_B.tenantId()));
        } finally {
            application.close();
        }
    }

    @Test
    void inspectionUsesEffectiveNatureAndRejectsGraphsWithNoEffectiveSource() throws Exception {
        var behavior = new RecordingSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            var ordinary = assertThrows(SourceSessionException.class,
                    () -> application.startSourceSession(TENANT_A, "ordinary", graph(NO_SOURCE_GRAPH)));
            assertEquals(SourceSessionException.Reason.NO_EFFECTIVE_SOURCE, ordinary.reason());

            var explicitlyWorker = assertThrows(SourceSessionException.class,
                    () -> application.startSourceSession(TENANT_A, "worker-choice", graph(WORKER_GRAPH)));
            assertEquals(SourceSessionException.Reason.NO_EFFECTIVE_SOURCE, explicitlyWorker.reason(),
                    "source capability alone must not override an effective WORKER selection");
            assertTrue(application.sourceSession(TENANT_A.tenantId(), "ordinary").isEmpty());
            assertTrue(application.sourceSession(TENANT_A.tenantId(), "worker-choice").isEmpty());

            application.activateDeployment(TENANT_A, DeploymentId.of("worker-deployment"), graph(WORKER_GRAPH))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(0, behavior.starts.get(),
                    "deployment discovery must not open an inbound resource for an effective WORKER node");
            application.deployment(DeploymentId.of("worker-deployment")).orElseThrow().stop()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            application.close();
        }
    }

    @Test
    void healthDiagnosticsAreFixedBoundedAndDoNotEchoSourceText() throws Exception {
        var behavior = new RecordingSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            application.startSourceSession(TENANT_A, "health", graph(SOURCE_GRAPH));
            awaitState(application, TENANT_A.tenantId(), "health", SourceSessionState.LISTENING);
            String secret = "password=hunter2 " + "x".repeat(1_000);
            behavior.contexts.getFirst().reportDegraded(secret);

            SourceSessionStatus status = awaitState(
                    application, TENANT_A.tenantId(), "health", SourceSessionState.DEGRADED);
            String diagnostic = status.diagnostic().orElseThrow();
            assertTrue(diagnostic.length() <= SourceSessionStatus.MAX_DIAGNOSTIC_CHARACTERS);
            assertFalse(diagnostic.contains("hunter2"));
            assertFalse(diagnostic.contains("password"));
            assertNotEquals(secret, diagnostic);
        } finally {
            application.close();
        }
    }

    @Test
    void multiSourcePartialStartupFailsAndRollsBackEarlierSourcesInNodeIdOrder() throws Exception {
        var behavior = new FailingSecondSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            SourceSessionStatus accepted = application.startSourceSession(
                    TENANT_A, "partial", graph(TWO_SOURCE_GRAPH));
            assertEquals(2, accepted.sourceCount());

            SourceSessionStatus failed = awaitState(
                    application, TENANT_A.tenantId(), "partial", SourceSessionState.FAILED);
            assertEquals("source session startup failed in this process", failed.diagnostic().orElseThrow());
            assertEquals(List.of("start:a-source", "start:b-source", "rollback:a-source"),
                    behavior.lifecycle,
                    "source startup and partial rollback must be deterministic by node id");
        } finally {
            application.close();
        }
    }

    private static DefaultRavenrootApplication application(SameThreadExecutionEngine engine,
                                                            ExecutionMonitor monitor,
                                                            NodeBehavior behavior) {
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage);
        return new DefaultRavenrootApplication(engine, monitor, registry, new InMemoryArtifactRegistry(),
                new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 8,
                UnknownBehaviorPolicy.passThrough());
    }

    private static final class FailingSecondSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final CopyOnWriteArrayList<String> lifecycle = new CopyOnWriteArrayList<>();

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Test source", "Test", "Test source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    lifecycle.add("start:" + context.nodeId());
                    if (context.nodeId().equals("b-source")) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("password=hunter2 startup detail"));
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    lifecycle.add("stop:" + context.nodeId());
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> rollback() {
                    lifecycle.add("rollback:" + context.nodeId());
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static SourceSessionStatus awaitState(DefaultRavenrootApplication application, String tenantId,
                                                  String sessionId, SourceSessionState expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        SourceSessionStatus latest = null;
        while (System.nanoTime() < deadline) {
            latest = application.sourceSession(tenantId, sessionId).orElseThrow();
            if (latest.state() == expected) return latest;
            Thread.sleep(10);
        }
        throw new AssertionError("session did not reach " + expected + "; latest=" + latest);
    }

    private static ByteArrayInputStream graph(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static SecurityContext identity(String tenant) {
        return new SecurityContext("request-" + tenant, tenant, "subject", PrincipalType.USER,
                "urn:ravenroot:test");
    }

    private static final class RecordingSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final AtomicInteger starts = new AtomicInteger();
        private final CopyOnWriteArrayList<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> payloads = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> stopsByTenant = new ConcurrentHashMap<>();

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Test source", "Test", "Test source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE,
                    Set.of(NodeRuntimeNature.SOURCE, NodeRuntimeNature.WORKER));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> {
                payloads.add(String.valueOf(message.payload()));
                return CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
            };
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    contexts.add(started);
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    stopsByTenant.computeIfAbsent(context.identity().tenantId(), ignored -> new AtomicInteger())
                            .incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static final String SOURCE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="listener"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="listener"><data key="outcome">continue</data></edge>
                <edge source="listener" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static final String WORKER_GRAPH = SOURCE_GRAPH
            .replace("<key id=\"outcome\"", "<key id=\"nature\" for=\"node\" attr.name=\"runtime.nature\" attr.type=\"string\"/>\n  <key id=\"outcome\"")
            .replace("<data key=\"behavior\">test.source</data></node>",
                    "<data key=\"behavior\">test.source</data><data key=\"nature\">WORKER</data></node>");

    private static final String TWO_SOURCE_GRAPH = SOURCE_GRAPH
            .replace("id=\"listener\"", "id=\"b-source\"")
            .replace("target=\"listener\"", "target=\"a-source\"")
            .replace("source=\"listener\"", "source=\"b-source\"")
            .replace("<node id=\"b-source\">",
                    "<node id=\"a-source\"><data key=\"kind\">behavior</data>"
                            + "<data key=\"behavior\">test.source</data></node>\n"
                            + "    <node id=\"b-source\">")
            .replace("<edge source=\"b-source\" target=\"end\"",
                    "<edge source=\"a-source\" target=\"b-source\"><data key=\"outcome\">continue</data></edge>\n"
                            + "    <edge source=\"b-source\" target=\"end\"");

    private static final String NO_SOURCE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;
}
