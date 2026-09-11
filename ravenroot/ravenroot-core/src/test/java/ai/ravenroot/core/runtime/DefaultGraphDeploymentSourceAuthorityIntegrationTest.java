package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundWebSocketListener;
import ai.ravenroot.api.node.service.OutboundWebSocketRequest;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServicesTestFactory;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Production deployment wiring for source identities and their managed transport lifetime. */
class DefaultGraphDeploymentSourceAuthorityIntegrationTest {
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant-a", "subject",
            ai.ravenroot.api.security.PrincipalType.WORKLOAD, "issuer");

    @Test
    void noOpSourceStopRevokesTheFullManagedSessionAcrossStopRestartAndUndeploy() throws Exception {
        var client = new FakeHttpClient();
        var fixture = fixture(SourceMode.OPEN_SOCKET, client, ONE_SOURCE_GRAPH);
        try {
            assertEquals(DeploymentState.READY, fixture.deployment.start(IDENTITY)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            InboundSourceContext firstContext = fixture.behavior.contexts.getFirst();
            FakeWebSocket firstSocket = client.sockets.getFirst();

            fixture.deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, firstSocket.aborts.get(),
                    "revocation must cancel a handed-off WebSocket even when source.stop is a no-op");
            assertSourceRefused(fixture.services, firstContext);

            assertEquals(DeploymentState.READY, fixture.deployment.restart(IDENTITY)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            assertEquals(2, fixture.behavior.contexts.size());
            assertEquals(2, client.sockets.size());
            assertSourceRefused(fixture.services, firstContext);

            InboundSourceContext secondContext = fixture.behavior.contexts.getLast();
            FakeWebSocket secondSocket = client.sockets.getLast();
            fixture.deployment.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, secondSocket.aborts.get(), "undeploy must revoke the restarted source generation");
            assertSourceRefused(fixture.services, secondContext);
            assertEquals(2, fixture.behavior.noOpStops.get(),
                    "both generations reach the package's no-op callback after core revocation");
        } finally {
            fixture.close();
        }
    }

    @Test
    void createThrowAndNullReturnRevokeTheirProvisionalAuthorities() throws Exception {
        for (SourceMode mode : List.of(SourceMode.CREATE_THROW, SourceMode.CREATE_NULL)) {
            var fixture = fixture(mode, new FakeHttpClient(), ONE_SOURCE_GRAPH);
            try {
                assertThrows(Exception.class,
                        () -> fixture.deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertEquals(DeploymentState.FAILED, fixture.deployment.status().state());
                assertEquals(1, fixture.behavior.contexts.size());
                assertSourceRefused(fixture.services, fixture.behavior.contexts.getFirst());
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    void secondSourceStartFailureRevokesItAndRollsBackTheReadySibling() throws Exception {
        var fixture = fixture(SourceMode.FAIL_SECOND_START, new FakeHttpClient(), TWO_SOURCE_GRAPH);
        try {
            assertThrows(Exception.class,
                    () -> fixture.deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(DeploymentState.FAILED, fixture.deployment.status().state());
            assertEquals(List.of("start:a-source", "start:b-source", "rollback:b-source", "rollback:a-source"),
                    fixture.behavior.lifecycle);
            assertEquals(2, fixture.behavior.contexts.size());
            fixture.behavior.contexts.forEach(context -> assertSourceRefused(fixture.services, context));
        } finally {
            fixture.close();
        }
    }

    private static Fixture fixture(SourceMode mode, FakeHttpClient client, String graph) {
        var policy = NodePackageEgressPolicy.builder()
                .allowOrigin("ws", "localhost", 80)
                .webSocketLimits(8, 2, Duration.ofSeconds(30), Duration.ofSeconds(30))
                .build();
        var services = ManagedNodePackageServicesTestFactory.create("test.source.package", policy,
                (packageId, tenant, reference) -> Optional.of(new SecretValue("secret".toCharArray())), client,
                NodePackageCapability.CREDENTIAL_RESOLUTION, NodePackageCapability.OUTBOUND_WEBSOCKET);
        var behavior = new ManagedSourceBehavior(mode);
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        var serviceRegistry = NodePackageServiceRegistry.builder()
                .grant("test.source.package", services).build();
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage, serviceRegistry);
        var engine = new ImmediateEngine();
        var deployment = new DefaultGraphDeployment(DeploymentId.of("source-authority"), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                graph.getBytes(StandardCharsets.UTF_8), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
        return new Fixture(deployment, engine, behavior, services);
    }

    private static void assertSourceRefused(ManagedNodePackageServices services, InboundSourceContext context) {
        CompletionException failure = assertThrows(CompletionException.class, () -> services.credentials()
                .resolve(context, "credential", Duration.ofSeconds(1)).completion().toCompletableFuture().join());
        assertEquals(ai.ravenroot.api.node.service.NodePackageServiceException.Reason.SERVICE_UNAVAILABLE,
                ((ai.ravenroot.api.node.service.NodePackageServiceException) failure.getCause()).reason());
    }

    private enum SourceMode { OPEN_SOCKET, CREATE_THROW, CREATE_NULL, FAIL_SECOND_START }

    private static final class ManagedSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final SourceMode mode;
        private final List<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();
        private final List<String> lifecycle = new CopyOnWriteArrayList<>();
        private final AtomicInteger noOpStops = new AtomicInteger();

        private ManagedSourceBehavior(SourceMode mode) { this.mode = mode; }

        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Source", "Test", "Managed source authority test",
                    "actor", false, List.of(), Set.of(), null, Set.of());
        }

        @Override public Set<NodePackageCapability> requiredServices() {
            return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION, NodePackageCapability.OUTBOUND_WEBSOCKET);
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }

        @Override public InboundSource createSource(NodeConfiguration configuration,
                                                     InboundSourceContext context) {
            throw new AssertionError("service-aware source construction must supply package services");
        }

        @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                     NodePackageServices services) {
            contexts.add(context);
            if (mode == SourceMode.CREATE_THROW) throw new IllegalStateException("synthetic create failure");
            if (mode == SourceMode.CREATE_NULL) return null;
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    lifecycle.add("start:" + context.nodeId());
                    if (mode == SourceMode.FAIL_SECOND_START && context.nodeId().equals("b-source")) {
                        return CompletableFuture.failedFuture(new IllegalStateException("synthetic start failure"));
                    }
                    if (mode == SourceMode.OPEN_SOCKET) {
                        services.outboundWebSocket().open(context, new OutboundWebSocketRequest(
                                URI.create("ws://localhost/stream"), Map.of(), List.of(),
                                Duration.ofSeconds(2), (OutboundCredentialBinding) null),
                                new OutboundWebSocketListener() { })
                                .completion().toCompletableFuture().join();
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> stop() {
                    noOpStops.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> shutdown() {
                    noOpStops.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> rollback() {
                    lifecycle.add("rollback:" + context.nodeId());
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private record Fixture(DefaultGraphDeployment deployment, ImmediateEngine engine,
                           ManagedSourceBehavior behavior, ManagedNodePackageServices services)
            implements AutoCloseable {
        @Override public void close() {
            try { deployment.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            catch (Exception ignored) { }
            engine.close();
        }
    }

    private static final class FakeHttpClient extends HttpClient {
        private final List<FakeWebSocket> sockets = new CopyOnWriteArrayList<>();

        @Override public WebSocket.Builder newWebSocketBuilder() {
            return new WebSocket.Builder() {
                @Override public WebSocket.Builder header(String name, String value) { return this; }
                @Override public WebSocket.Builder connectTimeout(Duration timeout) { return this; }
                @Override public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
                    return this;
                }
                @Override public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                    var socket = new FakeWebSocket();
                    sockets.add(socket);
                    listener.onOpen(socket);
                    return CompletableFuture.completedFuture(socket);
                }
            };
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); }
            catch (Exception failure) { throw new IllegalStateException(failure); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private final AtomicInteger aborts = new AtomicInteger();
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long count) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { aborts.incrementAndGet(); }
    }

    private static final class ImmediateEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        private volatile EngineState state = EngineState.RUNNING;
        @Override public String id() { return "source-authority-test"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() { return (delay, task) -> () -> true; }
        @Override public NodeRef spawn(String logicalName, RavenNode node) {
            NodeRef ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }
        @Override public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            return node == null ? CompletableFuture.failedFuture(new IllegalArgumentException("unknown node"))
                    : node.onMessage(message, context(target));
        }
        @Override public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> cancel(NodeRef target) { return stop(target); }
        @Override public EngineState state() { return state; }
        @Override public Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target)
                    ? Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0))
                    : Optional.empty();
        }
        @Override public CompletionStage<Void> drain() {
            state = EngineState.DRAINING;
            return CompletableFuture.completedFuture(null);
        }
        @Override public void close() { state = EngineState.CLOSED; nodes.clear(); }
        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override public NodeRef self() { return ref; }
                @Override public Scheduler scheduler() { return ImmediateEngine.this.scheduler(); }
                @Override public Mailbox mailbox() { return () -> 0; }
                @Override public CancellationSignal cancellation() { return new CancellationSignal() {
                    @Override public boolean cancelled() { return false; }
                    @Override public void onCancel(Runnable listener) { }
                }; }
            };
        }
    }

    private static final String ONE_SOURCE_GRAPH = graphNodes("""
            <node id="listener"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
            """, "listener", "listener");

    private static final String TWO_SOURCE_GRAPH = graphNodes("""
            <node id="a-source"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
            <node id="b-source"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
            """, "a-source", "b-source").replace(
                    "<edge source=\"b-source\" target=\"end\"/>",
                    "<edge source=\"a-source\" target=\"b-source\"/><edge source=\"b-source\" target=\"end\"/>");

    private static String graphNodes(String nodes, String first, String last) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="start"><data key="kind">start</data></node>
                """ + nodes + """
                    <node id="end"><data key="kind">end</data></node>
                    <node id="error"><data key="kind">error</data></node>
                    <edge source="start" target="%s"/>
                    <edge source="%s" target="end"/>
                  </graph>
                </graphml>
                """.formatted(first, last);
    }
}
