package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.GraphAdmissionPhase;
import ai.ravenroot.api.application.DiagnosticIdentifier;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentStartupException;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.SourceStartException;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGraphDeploymentStartupFailureTest {
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant", "subject",
            PrincipalType.WORKLOAD, "issuer");

    @Test
    void declaredFailureIsIdenticalOnCompletionAndStatusAndTheTrustedSinkSeesTheCauseOnce() throws Exception {
        RuntimeException original = new RuntimeException("password=hunter2 host=private.example profile=prod");
        try (var fixture = fixture(Set.of("imap-folder-not-authorized"), "imap-folder-not-authorized", original)) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<Throwable> recorded = new AtomicReference<>();
            AtomicReference<java.util.Optional<String>> recordedNode = new AtomicReference<>();
            fixture.deployment.installStartupFailureSink((id, failure, sourceNode, throwable) -> {
                calls.incrementAndGet();
                recorded.set(throwable);
                recordedNode.set(sourceNode);
            });

            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> fixture.deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));
            var publicException = (DeploymentStartupException) thrown.getCause();
            var statusFailure = fixture.deployment.status().failure().orElseThrow();

            assertEquals(publicException.failure(), statusFailure);
            assertEquals("imap-folder-not-authorized", statusFailure.reason());
            assertEquals(GraphAdmissionPhase.SOURCE_START, statusFailure.phase());
            assertEquals("listener", statusFailure.nodeId().orElseThrow());
            assertTrue(statusFailure.nodeRef().orElseThrow().matches("sha256:[0-9a-f]{32}"));
            assertEquals(1, calls.get());
            assertEquals(java.util.Optional.of("listener"), recordedNode.get());
            assertNotNull(recorded.get());
            assertSame(original, recorded.get().getCause().getCause());
            assertFalse(publicException.toString().contains("hunter2"));
            assertFalse(statusFailure.toString().contains("private.example"));
        }
    }

    @Test
    void undeclaredFailureCodeFallsBackToGenericWithoutAProjectedNode() throws Exception {
        try (var fixture = fixture(Set.of("declared-code"), "undeclared-secret-code",
                new RuntimeException("secret-marker"))) {
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> fixture.deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));
            var failure = ((DeploymentStartupException) thrown.getCause()).failure();

            assertEquals("STARTUP_FAILED", failure.reason());
            assertEquals(GraphAdmissionPhase.SOURCE_START, failure.phase());
            assertTrue(failure.nodeId().isEmpty());
            assertFalse(failure.toString().contains("undeclared-secret-code"));
            assertEquals(failure, fixture.deployment.status().failure().orElseThrow());
        }
    }

    @Test
    void malformedDeclarationsRefusePackageRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> fixture(Set.of("NOT_A_PUBLIC_CODE"), "valid-code", new RuntimeException("x")));
    }

    @Test
    void startupFailureStaysOutOfExecutionEventsAndRuntimeMetrics() throws Exception {
        String rawNode = "listener;host=private.example;profile=prod;"
                + "url=https://operator:pw@inside.example/path;password=hunter2";
        String graph = GRAPH.replace("listener", rawNode);
        try (var fixture = fixture(Set.of("imap-folder-not-authorized"),
                "imap-folder-not-authorized", new RuntimeException("secret-marker"), graph)) {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> fixture.deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));

            var failure = fixture.deployment.status().failure().orElseThrow();
            assertEquals(DiagnosticIdentifier.reference(rawNode), failure.nodeRef().orElseThrow());
            assertFalse(failure.toString().contains("private.example"));
            assertFalse(failure.toString().contains("inside.example"));
            assertFalse(failure.toString().contains("profile=prod"));
            assertEquals(0, fixture.monitor.snapshot().activeExecutions());
            assertTrue(fixture.monitor.snapshot().activeNodeInstances().isEmpty());
            assertTrue(fixture.monitor.eventsAfter(0).isEmpty(),
                    "startup diagnostics are deployment state, not execution events");
        }
    }

    private static Fixture fixture(Set<String> declared, String emitted, RuntimeException original) {
        return fixture(declared, emitted, original, GRAPH);
    }

    private static Fixture fixture(Set<String> declared, String emitted, RuntimeException original,
                                   String graph) {
        var behavior = new FailingSourceBehavior(declared, emitted, original);
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.failure.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage);
        var engine = new SpawnRecordingEngine();
        var monitor = new ExecutionMonitor();
        var deployment = new DefaultGraphDeployment(DeploymentId.of("failure-test"), engine, registry,
                monitor, ExecutionIdentitySource.randomUuids(),
                graph.getBytes(StandardCharsets.UTF_8), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
        return new Fixture(engine, deployment, monitor);
    }

    private record Fixture(SpawnRecordingEngine engine, DefaultGraphDeployment deployment,
                           ExecutionMonitor monitor) implements AutoCloseable {
        @Override public void close() throws Exception {
            deployment.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
            engine.close();
        }
    }

    private record FailingSourceBehavior(Set<String> declared, String emitted, RuntimeException original)
            implements NodeBehavior, InboundSourceCapable {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.failure", "Failure source", "Test", "Failure source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override public Set<String> sourceStartFailureCodes() { return declared; }

        @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext ignored) {
                    return CompletableFuture.failedFuture(
                            new SourceStartException(() -> emitted, original));
                }
                @Override public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="listener"><data key="kind">BEHAVIOR</data><data key="behavior">test.failure</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge source="start" target="listener"/><edge source="listener" target="end"/>
              </graph>
            </graphml>
            """;
}
