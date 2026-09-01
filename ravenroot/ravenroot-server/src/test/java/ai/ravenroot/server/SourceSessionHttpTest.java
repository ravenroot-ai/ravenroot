package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP/OpenAPI-facing lifecycle contract for process-local source sessions. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SourceSessionHttpTest {
    @Test
    void authenticatedTenantsStartObserveAndStopOnlyTheirOwnProcessLocalSession() throws Exception {
        var source = new SourceBehavior();
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.http.source.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(source); }
        };
        BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(), nodePackage);
        try (var engine = new PekkoExecutionEngine("source-session-http")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), null, 8, UnknownBehaviorPolicy.passThrough());
            var httpSecurity = new HttpSecurityConfiguration(
                    new BrowserOriginPolicy(Set.of("https://editor.example")),
                    new SecurityHeadersPolicy(false), java.time.Duration.ofSeconds(30));
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                    new HeaderTenantAuthenticator(), httpSecurity)) {
                server.start();

                HttpResponse<String> unauthenticated = request(
                        server, "GET", "/v1/source-sessions/browser-1", "", "");
                assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());

                HttpResponse<String> started = request(server, "POST", "/v1/source-sessions?id=browser-1",
                        SOURCE_GRAPH, "tenant-a");
                assertEquals(202, started.statusCode(), started.body());
                assertTrue(started.body().contains("\"scope\":\"LOCAL_PROCESS\""), started.body());
                assertTrue(started.body().contains("\"sourceCount\":1"), started.body());
                String listening = pollState(server, "tenant-a", "browser-1", "LISTENING");
                assertTrue(listening.contains("\"diagnostic\":null"), listening);

                HttpResponse<String> preflight = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port()
                                        + "/v1/source-sessions/browser-1"))
                                .header("Origin", "https://editor.example")
                                .header("Access-Control-Request-Method", "DELETE")
                                .header("Access-Control-Request-Headers", "authorization")
                                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(204, preflight.statusCode(), preflight.body());
                assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()
                        .contains("DELETE"));

                HttpResponse<String> hiddenSibling = request(
                        server, "GET", "/v1/source-sessions/browser-1", "", "tenant-b");
                assertEquals(404, hiddenSibling.statusCode(), hiddenSibling.body());
                assertFalse(hiddenSibling.body().contains("tenant-a"), hiddenSibling.body());

                HttpResponse<String> sibling = request(server, "POST", "/v1/source-sessions?id=browser-1",
                        SOURCE_GRAPH, "tenant-b");
                assertEquals(202, sibling.statusCode(), sibling.body());
                pollState(server, "tenant-b", "browser-1", "LISTENING");

                source.contexts.stream()
                        .filter(context -> context.identity().tenantId().equals("tenant-a"))
                        .findFirst().orElseThrow()
                        .reportDegraded("password=hunter2 " + "x".repeat(1_000));
                String degraded = pollState(server, "tenant-a", "browser-1", "DEGRADED");
                assertTrue(degraded.length() < 512, degraded);
                assertFalse(degraded.contains("hunter2"), degraded);
                assertFalse(degraded.contains("password"), degraded);

                HttpResponse<String> stopped = request(
                        server, "DELETE", "/v1/source-sessions/browser-1", "", "tenant-a");
                assertEquals(200, stopped.statusCode(), stopped.body());
                assertTrue(stopped.body().contains("\"state\":\"STOPPED\""), stopped.body());
                String stillListening = pollState(server, "tenant-b", "browser-1", "LISTENING");
                assertTrue(stillListening.contains("\"scope\":\"LOCAL_PROCESS\""), stillListening);

                HttpResponse<String> noInventory = request(
                        server, "GET", "/v1/source-sessions", "", "tenant-a");
                assertEquals(405, noInventory.statusCode(), noInventory.body());
            }
        }
    }

    private static String pollState(RavenrootServer server, String tenant, String id, String state)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        HttpResponse<String> latest;
        do {
            latest = request(server, "GET", "/v1/source-sessions/" + id, "", tenant);
            if (latest.statusCode() == 200 && latest.body().contains("\"state\":\"" + state + "\"")) {
                return latest.body();
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("session did not reach " + state + "; latest=" + latest.body());
    }

    private static HttpResponse<String> request(RavenrootServer server, String method, String path,
                                                String body, String tenant) throws Exception {
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/graphml+xml; charset=utf-8");
        if (!tenant.isBlank()) request.header("X-Test-Tenant", tenant);
        return HttpClient.newHttpClient().send(request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class HeaderTenantAuthenticator implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
            String tenant = headers.getFirst("X-Test-Tenant");
            if (tenant == null || tenant.isBlank()) throw new AuthenticationException("missing tenant");
            return new AuthenticatedPrincipal(tenant, AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", tenant, Set.of(Role.OPERATOR),
                    java.util.Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }

    private static final class SourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final CopyOnWriteArrayList<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.http.source", "HTTP source", "Test", "HTTP source", "actor",
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
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    contexts.add(started);
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<Void> stop() {
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
              <graph id="source-http" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="listener"><data key="kind">BEHAVIOR</data><data key="behavior">test.http.source</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge source="start" target="listener"><data key="outcome">continue</data></edge>
                <edge source="listener" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;
}
