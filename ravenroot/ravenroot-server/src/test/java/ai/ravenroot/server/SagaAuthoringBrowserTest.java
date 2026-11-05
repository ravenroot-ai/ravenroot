package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real built editor, HTTP admission, durable execution store and engine; no mocked graph APIs. */
class SagaAuthoringBrowserTest {
    @ParameterizedTest
    @ValueSource(strings = { "New, default Log", "scope authoring selects", "mixed sagas survive", "Log before a real source", "catalog and kind transitions" })
    void ordinaryLogAndExplicitMixedSagaRoundTripThroughTheRealEditorAndServer(String scenario) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.sagaAuthoring.browserTest"),
                "build ravenroot-ui and enable ravenroot.sagaAuthoring.browserTest for browser proof");
        Path ui = Path.of(System.getProperty("user.dir")).resolve("../ravenroot-ui").toAbsolutePath().normalize();
        assertTrue(Files.exists(ui.resolve("dist/index.html")), "build the editor first");
        Path output = Files.createTempDirectory(Path.of(System.getProperty("user.dir"), "target"), "saga-authoring-");
        var registry = ai.ravenroot.core.runtime.NodePackages.register(BehaviorRegistry.standard(), new ai.ravenroot.api.node.NodePackage() {
            @Override public String id() { return "test.saga.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return ai.ravenroot.api.node.NodeSdk.CONTRACT; }
            @Override public List<ai.ravenroot.api.node.NodeBehavior> behaviors() { return List.of(new TestSource()); }
        }).registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("test.saga.pure", "Saga test transform", "Test", "A trusted pure test participant",
                        "actor", false, List.of(), Set.of("saga-pure"));
            }
            @Override public NodeHandler create(GraphNode node) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
        int port;
        try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        try (var store = new SqliteExecutionStore(output.resolve("execution.db"), Clock.systemUTC());
             var engine = new PekkoExecutionEngine("saga-authoring")) {
            var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), registry,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store, 2);
            try (var server = new RavenrootServer(app, new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    ui.resolve("dist"), new DisabledLoopbackAuthenticator())) {
                server.installNodePalettes(ai.ravenroot.server.palette.JdbcNodePaletteStore.sqlite(
                        output.resolve("execution.db"), Clock.systemUTC()));
                server.start();
                var builder = new ProcessBuilder(ui.resolve("node_modules/.bin/playwright").toString(), "test",
                        "--config=playwright.saga-authoring.config.js", "--grep=" + scenario).directory(ui.toFile())
                        .redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
                builder.environment().put("RAVENROOT_SAGA_ORIGIN", "http://127.0.0.1:" + server.port());
                builder.environment().put("RAVENROOT_SAGA_OUTPUT", output.resolve("playwright").toString());
                Process browser = builder.start();
                if (!browser.waitFor(4, TimeUnit.MINUTES)) { browser.destroyForcibly(); fail("browser timed out: " + output); }
                assertEquals(0, browser.exitValue(), () -> {
                    try { return Files.readString(output.resolve("browser.log")); }
                    catch (Exception unreadable) { return "browser evidence: " + output; }
                });
            }
        }
    }

    private static final class TestSource implements ai.ravenroot.api.node.NodeBehavior, ai.ravenroot.api.node.InboundSourceCapable {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.saga.source", "Saga test source", "Test", "A real inbound source", "actor", false,
                    List.of(), Set.of("inbound-source")).withNature(ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE,
                    Set.of(ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE));
        }
        @Override public ai.ravenroot.api.node.NodeAction create(ai.ravenroot.api.node.NodeConfiguration node) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public ai.ravenroot.api.deployment.InboundSource createSource(ai.ravenroot.api.node.NodeConfiguration node,
                ai.ravenroot.api.deployment.InboundSourceContext context) {
            return new ai.ravenroot.api.deployment.InboundSource() {
                @Override public java.util.concurrent.CompletionStage<Void> start(ai.ravenroot.api.deployment.InboundSourceContext started) {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public java.util.concurrent.CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
            };
        }
    }
}
