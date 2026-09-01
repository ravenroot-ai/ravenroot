package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.readiness.ReadinessGate;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real server authentication composed with a real graph-owned managed-ingress lease. */
class ManagedIngressProductionIntegrationTest {
    private static final SecurityContext OWNER = new SecurityContext("request", "tenant-a", "alice",
            PrincipalType.USER, "issuer");
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="listener"><data key="kind">behavior</data><data key="behavior">test.managed.http</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="listener"/><edge source="listener" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void realAuthenticationAndGraphLifecycleOwnTheRouteAndReadinessInventory() throws Exception {
        try (var fixture = Fixture.start(request -> CompletableFuture.completedFuture(
                new IngressResponse(202, Map.of(), request.principal().tenantId().getBytes(StandardCharsets.UTF_8))),
                2, Duration.ofSeconds(2))) {
            var client = HttpClient.newHttpClient();
            assertTrue(fixture.handle.ready());
            assertEquals(200, client.send(HttpRequest.newBuilder(fixture.uri("/ready")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals("routes=[test.managed.package:route:1:ACTIVE]", fixture.prepared.readinessDetail());

            var authorized = fixture.post(client, "tenant-a:alice");
            assertEquals(202, authorized.statusCode());
            assertEquals("tenant-a", authorized.body());
            assertEquals(403, fixture.post(client, "tenant-b:mallory").statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(fixture.uri("/managed/production/route"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());

            fixture.deployment.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals("routes=[]", fixture.prepared.readinessDetail());
            assertNotEquals(202, fixture.post(client, "tenant-a:alice").statusCode(),
                    "graph stop retires the real server context");
        }
    }

    @Test void productionGuardsDeriveIdentityBeforeBoundedDescendantProjection() throws Exception {
        var seen = new AtomicReference<ai.ravenroot.api.ingress.IngressRequest>();
        try (var fixture = Fixture.start(request -> {
            seen.set(request);
            return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0]));
        }, 1, Duration.ofSeconds(2))) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            fixture.uri("/managed/production/route/orders/42?tag=one&tag=two"))
                    .header("Authorization", "Bearer tenant-a:alice")
                    .header("X-Idempotency-Key", "receipt-42")
                    .header("X-Unapproved", "hidden")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertEquals(202, response.statusCode());
            assertEquals("tenant-a", seen.get().principal().tenantId());
            assertEquals("alice", seen.get().principal().subject());
            assertEquals("/orders/42", seen.get().relativePath());
            assertEquals(Map.of("tag", List.of("one", "two")), seen.get().query());
            assertEquals(Map.of("x-idempotency-key", "receipt-42"), seen.get().headers(),
                    "authentication and unapproved fields never enter the package projection");
        }
    }

    @Test void realAdapterEnforcesMaximumConcurrencyAfterLifecycleAcquisition() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<IngressResponse>();
        try (var fixture = Fixture.start(request -> {
            entered.countDown();
            return release;
        }, 1, Duration.ofSeconds(2))) {
            var client = HttpClient.newHttpClient();
            var first = client.sendAsync(fixture.request("tenant-a:alice"), HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(429, client.send(fixture.request("tenant-a:bob"),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            release.complete(new IngressResponse(202, Map.of(), new byte[0]));
            assertEquals(202, first.get(2, TimeUnit.SECONDS).statusCode());
        }
    }

    @Test void bodyReadRetirementAndConcurrentServerShutdownCannotStall() throws Exception {
        var fixture = Fixture.start(request -> CompletableFuture.completedFuture(
                new IngressResponse(204, Map.of(), new byte[0])), 1, Duration.ofSeconds(5));
        try (fixture; var socket = new Socket(InetAddress.getLoopbackAddress(), fixture.server.port());
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            socket.getOutputStream().write(("POST /managed/production/route HTTP/1.1\r\n"
                    + "Host: localhost\r\nAuthorization: Bearer tenant-a:alice\r\n"
                    + "Content-Length: 8\r\nConnection: close\r\n\r\nx")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(50);

            long started = System.nanoTime();
            var graphStop = tasks.submit(() -> fixture.deployment.stop().toCompletableFuture().join());
            var serverStop = tasks.submit(() -> { fixture.handle.close(); return null; });
            graphStop.get(2, TimeUnit.SECONDS);
            serverStop.get(2, TimeUnit.SECONDS);
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0,
                    "body read, concurrent retire and server close are bounded");
            assertFalse(fixture.handle.ready());
            assertEquals("routes=[]", fixture.prepared.readinessDetail());
        }
    }

    @Test void cancellationIgnoringCompletionCannotCrossLifecycleReplacement() throws Exception {
        var entered = new CountDownLatch(1);
        var oldCompletion = new CompletableFuture<IngressResponse>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };
        var selected = new AtomicReference<IngressRouteHandler>(request -> {
            entered.countDown();
            return oldCompletion;
        });
        try (var fixture = Fixture.start(request -> selected.get().handle(request),
                1, Duration.ofSeconds(2))) {
            var client = HttpClient.newHttpClient();
            var oldResponse = client.sendAsync(fixture.request("tenant-a:alice"),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            fixture.deployment.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
            selected.set(request -> CompletableFuture.completedFuture(
                    new IngressResponse(202, Map.of(), new byte[] {2})));
            fixture.deployment.restart(OWNER).toCompletableFuture().get(2, TimeUnit.SECONDS);
            oldCompletion.complete(new IngressResponse(200, Map.of(), new byte[] {1}));

            assertFenced(oldResponse);
            var replacement = client.send(fixture.request("tenant-a:alice"),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, replacement.statusCode());
            assertArrayEquals(new byte[] {2}, replacement.body());
            assertEquals("routes=[test.managed.package:route:2:ACTIVE]", fixture.prepared.readinessDetail());
        }
    }

    @Test void synchronousCancelIgnoringCallbackIsBoundedAcrossRealLifecycleRestart() throws Exception {
        var entered = new CountDownLatch(1);
        var allowOldExit = new AtomicBoolean();
        var selected = new AtomicReference<IngressRouteHandler>(request -> {
            entered.countDown();
            while (!allowOldExit.get()) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                Thread.interrupted();
            }
            return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[] {1}));
        });
        try (var fixture = Fixture.start(request -> selected.get().handle(request),
                1, Duration.ofMillis(75))) {
            var client = HttpClient.newHttpClient();
            long started = System.nanoTime();
            assertEquals(504, client.send(fixture.request("tenant-a:alice"),
                    HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "the admitted window ran out; the package neither failed nor was the client slow");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0);

            fixture.deployment.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
            selected.set(request -> CompletableFuture.completedFuture(
                    new IngressResponse(202, Map.of(), new byte[] {2})));
            fixture.deployment.restart(OWNER).toCompletableFuture().get(2, TimeUnit.SECONDS);
            var replacement = client.send(fixture.request("tenant-a:alice"),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, replacement.statusCode());
            assertArrayEquals(new byte[] {2}, replacement.body());
        } finally {
            allowOldExit.set(true);
        }
    }

    private static void assertFenced(CompletableFuture<HttpResponse<byte[]>> response) throws Exception {
        try {
            assertNotEquals(200, response.get(2, TimeUnit.SECONDS).statusCode());
        } catch (java.util.concurrent.ExecutionException closed) {
            assertInstanceOf(java.io.IOException.class, closed.getCause());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PekkoExecutionEngine engine;
        private final DefaultRavenrootApplication application;
        private final DefaultGraphDeployment deployment;
        private final RavenrootServerStartup.Prepared prepared;
        private final RavenrootServerStartup.Handle handle;
        private final RavenrootServer server;

        private Fixture(PekkoExecutionEngine engine, DefaultRavenrootApplication application,
                        DefaultGraphDeployment deployment, RavenrootServerStartup.Prepared prepared,
                        RavenrootServerStartup.Handle handle, RavenrootServer server) {
            this.engine = engine;
            this.application = application;
            this.deployment = deployment;
            this.prepared = prepared;
            this.handle = handle;
            this.server = server;
        }

        private static Fixture start(IngressRouteHandler handler, int maxConcurrent, Duration timeout)
                throws Exception {
            var nodePackage = new ManagedPackage(handler, maxConcurrent, timeout);
            BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(), nodePackage);
            var engine = new PekkoExecutionEngine("managed-ingress-production-" + System.nanoTime());
            var monitor = new ExecutionMonitor();
            var application = new DefaultRavenrootApplication(engine, monitor, behaviors);
            var deployment = new DefaultGraphDeployment(DeploymentId.of("production-deployment"), engine,
                    behaviors, monitor, ExecutionIdentitySource.randomUuids(),
                    GRAPH.getBytes(StandardCharsets.UTF_8),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
            var prepared = RavenrootServerStartup.prepare(List.of(nodePackage), Map.of());
            prepared.installInto(deployment::installManagedIngress);
            var serverRef = new AtomicReference<RavenrootServer>();
            RavenrootServerStartup.Handle handle = prepared.bind(() -> {
                var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
                var server = new RavenrootServer(application, address, null, true, authenticator(),
                        new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                                new SecurityHeadersPolicy(false), Duration.ofSeconds(30)),
                        Clock.systemUTC(), new DefaultAuthorizationService(event -> { }),
                        new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                                event -> { }), event -> { },
                        ReadinessGate.engineOnly(() -> application.status().state()), Duration.ZERO);
                serverRef.set(server);
                return new RavenrootServerStartup.Listener() {
                    @Override public void install(ai.ravenroot.server.ingress.ManagedIngressRegistry ingress) {
                        server.installManagedIngress(ingress);
                    }

                    @Override public void start() { server.start(); }

                    @Override public void close() { server.close(); }
                };
            });
            try {
                handle.start();
                deployment.start(OWNER).toCompletableFuture().get(5, TimeUnit.SECONDS);
                return new Fixture(engine, application, deployment, prepared, handle, serverRef.get());
            } catch (Throwable failure) {
                handle.close();
                application.close();
                engine.close();
                throw failure;
            }
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.port() + path);
        }

        private HttpRequest request(String bearer) {
            return HttpRequest.newBuilder(uri("/managed/production/route"))
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
        }

        private HttpResponse<String> post(HttpClient client, String bearer) throws Exception {
            return client.send(request(bearer), HttpResponse.BodyHandlers.ofString());
        }

        @Override public void close() {
            try {
                deployment.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The test assertion owns any first failure; cleanup remains best effort.
            }
            handle.close();
            application.close();
            engine.close();
        }
    }

    private record ManagedPackage(IngressRouteHandler handler, int maxConcurrent, Duration timeout)
            implements NodePackage, IngressAuthorityContributor {
        @Override public String id() { return "test.managed.package"; }
        @Override public String version() { return "1.0.0"; }
        @Override public String sdkContract() { return NodeSdk.CONTRACT; }
        @Override public List<NodeBehavior> behaviors() { return List.of(new ManagedBehavior(handler)); }
        @Override public List<IngressAuthorityDeclaration> ingressAuthorities() {
            return List.of(new IngressAuthorityDeclaration(id(), "main", "/managed/production",
                    Set.of("invoke"), 1, maxConcurrent, 1_024, 1_024, timeout));
        }
        @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
            return Optional.of(new IngressRequestProjectionPolicy(id(),
                    Set.of("Content-Type", "X-Idempotency-Key"), "X-Idempotency-Key",
                    512, 32, 1_024, 2, 1_024, 512));
        }
    }

    private record ManagedBehavior(IngressRouteHandler handler) implements NodeBehavior, InboundSourceCapable {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.managed.http", "Managed HTTP", "Test", "Managed HTTP source",
                    "actor", false, List.of(), Set.of(), null, Set.of());
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }

        @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new ManagedIngressSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext ignored) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
                    authority.acquirePrefix("route", "/route", Set.of("POST"), handler);
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static RequestAuthenticator authenticator() {
        return headers -> {
            String authorization = headers.getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new AuthenticationException("missing bearer");
            }
            String[] identity = authorization.substring("Bearer ".length()).split(":", 2);
            if (identity.length != 2 || identity[0].isBlank() || identity[1].isBlank()) {
                throw new AuthenticationException("invalid bearer");
            }
            return new AuthenticatedPrincipal(identity[1], AuthenticatedPrincipal.Type.USER, "issuer", identity[0],
                    Set.of(), Set.of("invoke"));
        };
    }
}
