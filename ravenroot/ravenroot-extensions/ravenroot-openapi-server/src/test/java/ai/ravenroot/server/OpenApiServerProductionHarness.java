package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.readiness.ReadinessGate;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Package-local bridge that drives the real server composition seam from an extension-owned test. */
public final class OpenApiServerProductionHarness implements AutoCloseable {
    private final NodePackage nodePackage;
    private final byte[] graph;
    private final SecurityContext owner;
    private final Path directory;
    private final BehaviorRegistry behaviors;
    private final PekkoExecutionEngine engine;
    private final SqliteExecutionStore store;
    private final DefaultRavenrootApplication application;
    private final DefaultGraphDeployment deployment;
    private final RavenrootServerStartup.Prepared prepared;
    private final RavenrootServerStartup.Handle handle;
    private final RavenrootServer server;
    private final List<Collision> collisions = new ArrayList<>();

    public static OpenApiServerProductionHarness start(NodePackage nodePackage, byte[] graph,
                                                       SecurityContext owner, Path directory) throws Exception {
        return new OpenApiServerProductionHarness(nodePackage, graph, owner, directory);
    }

    private OpenApiServerProductionHarness(NodePackage nodePackage, byte[] graph,
                                           SecurityContext owner, Path directory) throws Exception {
        this.nodePackage = nodePackage;
        this.graph = graph.clone();
        this.owner = owner;
        this.directory = directory;
        Files.createDirectories(directory);
        this.behaviors = NodePackages.register(new BehaviorRegistry(), nodePackage);
        this.engine = new PekkoExecutionEngine("openapi-production-" + System.nanoTime());
        var monitor = new ExecutionMonitor();
        this.store = new SqliteExecutionStore(directory.resolve("primary.db"), Clock.systemUTC());
        this.application = new DefaultRavenrootApplication(engine, monitor, behaviors);
        this.deployment = deployment("openapi-primary", engine, store, monitor);
        this.prepared = RavenrootServerStartup.prepare(List.of(nodePackage), Map.of());
        prepared.installInto(deployment::installManagedIngress);
        var serverRef = new AtomicReference<RavenrootServer>();
        this.handle = prepared.bind(() -> {
            var created = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true, authenticator(),
                    new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                            new SecurityHeadersPolicy(false), Duration.ofSeconds(30)),
                    Clock.systemUTC(), new DefaultAuthorizationService(event -> { }),
                    new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                            event -> { }), event -> { },
                    ReadinessGate.engineOnly(() -> application.status().state()), Duration.ZERO);
            serverRef.set(created);
            return new RavenrootServerStartup.Listener() {
                @Override public void install(ai.ravenroot.server.ingress.ManagedIngressRegistry ingress) {
                    created.installManagedIngress(ingress);
                }
                @Override public void start() { created.start(); }
                @Override public void close() { created.close(); }
            };
        });
        try {
            handle.start();
            this.server = serverRef.get();
        } catch (Throwable failure) {
            handle.close(); application.close(); store.close(); engine.close();
            throw failure;
        }
    }

    public boolean ready() { return handle.ready(); }
    public String readinessDetail() { return prepared.readinessDetail(); }
    public URI uri(String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }

    public void startGraph() throws Exception {
        deployment.start(owner).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    public void restartGraph() throws Exception {
        deployment.restart(owner).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    public void stopGraph() throws Exception {
        deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    public HttpResponse<String> post(String bearer, String contentType, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(
                        "/managed/openapi/api/orders/42?verbose=true"))
                .header("X-Trace", "production")
                .header("Idempotency-Key", key);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (contentType != null) request.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString("{\"amount\":2}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public Collision collision(String id) {
        Collision collision = new Collision(id);
        collisions.add(collision);
        return collision;
    }

    @Override public void close() {
        collisions.forEach(Collision::close);
        try { deployment.stop().toCompletableFuture().get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
        handle.close(); application.close(); store.close(); engine.close();
    }

    private DefaultGraphDeployment deployment(String id, PekkoExecutionEngine selectedEngine,
                                              SqliteExecutionStore selectedStore, ExecutionMonitor monitor) {
        return new DefaultGraphDeployment(DeploymentId.of(id), selectedEngine, behaviors, monitor,
                ExecutionIdentitySource.randomUuids(), graph, DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY,
                selectedStore, Duration.ofHours(24));
    }

    public final class Collision implements AutoCloseable {
        private final PekkoExecutionEngine collisionEngine;
        private final SqliteExecutionStore collisionStore;
        private final DefaultGraphDeployment collisionDeployment;

        private Collision(String id) {
            collisionEngine = new PekkoExecutionEngine("openapi-collision-" + System.nanoTime());
            collisionStore = new SqliteExecutionStore(directory.resolve(id + ".db"), Clock.systemUTC());
            collisionDeployment = deployment(id, collisionEngine, collisionStore, new ExecutionMonitor());
            prepared.installInto(collisionDeployment::installManagedIngress);
        }

        public Throwable startFailure() {
            try {
                collisionDeployment.start(owner).toCompletableFuture().join();
                return null;
            } catch (java.util.concurrent.CompletionException failure) {
                return failure.getCause();
            }
        }

        public DeploymentState state() { return collisionDeployment.status().state(); }

        @Override public void close() {
            try { collisionDeployment.stop().toCompletableFuture().join(); } catch (RuntimeException ignored) { }
            collisionStore.close(); collisionEngine.close();
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
                    Set.of(), Set.of("graph:execute"));
        };
    }
}
