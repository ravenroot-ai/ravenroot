package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Package bridge to the shipped managed-ingress/server composition; no alternate product routes. */
public final class LakehouseExampleHarness implements AutoCloseable {
    public final DefaultRavenrootApplication application;
    public final SqliteExecutionStore store;
    private final PekkoExecutionEngine engine;
    private final RavenrootServerStartup.Handle handle;
    private final RavenrootServer server;
    private final SecurityContext identity = new SecurityContext("example", "local", "operator", PrincipalType.USER, "fixture");
    public LakehouseExampleHarness(NodePackage nodePackage, byte[] graph, Path output, Path ui, int externalPort) throws Exception {
        engine = new PekkoExecutionEngine("lakehouse-example"); store = new SqliteExecutionStore(output.resolve("history.db"), Clock.systemUTC());
        var environment = new BehaviorEnvironment(null, null, null, null, null, ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "fixture", ""),
                new OutboundHttpPolicy(Set.of("localhost"), Duration.ofSeconds(10), Set.of(externalPort), 8192, 8192));
        var registry = NodePackages.registerAll(BehaviorRegistry.standard(environment), List.of(nodePackage));
        application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), registry, new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store, 8, UnknownBehaviorPolicy.refuse());
        var prepared = RavenrootServerStartup.prepare(List.of(nodePackage), Map.of()); prepared.installInto(application::installManagedIngress);
        int port; try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        server = new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), port), Files.isDirectory(ui.resolve("dist")) ? ui.resolve("dist") : null, new DisabledLoopbackAuthenticator());
        handle = prepared.bind(() -> new RavenrootServerStartup.Listener() {
            @Override public void install(ai.ravenroot.server.ingress.ManagedIngressRegistry ingress) { server.installManagedIngress(ingress); }
            @Override public void start() { server.start(); }
            @Override public void close() { server.close(); }
        });
        try {
            handle.start();
            application.registerLocalDeployment(identity, "lakehouse-example", new java.io.ByteArrayInputStream(graph));
            var started = application.startLocalDeployment(identity, "lakehouse-example").toCompletableFuture().get(15, TimeUnit.SECONDS).orElseThrow();
            if (started.state() != LocalDeploymentState.READY) throw new IllegalStateException("source startup=" + started);
        } catch (GraphAdmissionException refused) { close(); throw new IllegalStateException(refused.findings().toString(), refused); }
        catch (Exception failure) { close(); throw failure; }
    }
    public URI uri(String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }
    public String version() { return application.localDeploymentView("local", "lakehouse-example").orElseThrow().source().graphVersion(); }
    public void reconnectSources() throws Exception {
        application.stopLocalDeployment(identity.tenantId(), "lakehouse-example").toCompletableFuture().get(15, TimeUnit.SECONDS);
        application.startLocalDeployment(identity, "lakehouse-example").toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
    @Override public void close() { handle.close(); application.close(); store.close(); engine.close(); }
}
