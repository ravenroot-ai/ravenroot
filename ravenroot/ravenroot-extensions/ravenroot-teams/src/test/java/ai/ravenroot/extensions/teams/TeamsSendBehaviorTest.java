package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TeamsSendBehaviorTest {
    @TempDir Path directory;
    private HttpServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void postsBoundedWorkflowJsonThroughManagedCredentialWithoutCallerAuthorization() {
        var http = new TeamsTestSupport.HttpHarness().reply(202);
        NodeAction action = action(http, Map.of());
        var result = action.handle(TeamsTestSupport.message(message("hello"))).toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status"));
        assertFalse(result.payload().toString().contains("hello"));
        var request = http.requests.getFirst();
        assertEquals(TeamsTestSupport.WORKFLOW, request.destination());
        assertEquals("teams-workflow", request.credential().orElseThrow().bindingId());
        assertEquals("teams-workflow-token", request.credential().orElseThrow().reference());
        assertFalse(request.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("authorization")));
        Map<String, Object> body = TeamsValues.json(request.body());
        assertEquals("teams.message.v1", body.get("version"));
        assertEquals(TeamsTestSupport.MICROSOFT_TENANT, body.get("microsoftTenantId"));
        assertEquals(TeamsTestSupport.TEAM, body.get("teamId"));
        assertEquals(TeamsTestSupport.CHANNEL, body.get("channelId"));
        assertEquals("hello", body.get("text"));
    }

    @Test void actualManagedServiceInjectsOnlyOperatorBoundBearerForModuleRequest() throws Exception {
        var capture = new TeamsTestSupport.HttpHarness().reply(202);
        action(capture, Map.of()).handle(TeamsTestSupport.message(message("managed")))
                .toCompletableFuture().join();
        var moduleRequest = capture.requests.getFirst();
        assertFalse(moduleRequest.headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("authorization")));

        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/workflow", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(202, -1); exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        var origin = new NodePackageEgressPolicy.Origin("http", "localhost", port);
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("POST").allowRequestHeader("accept").allowRequestHeader("content-type")
                .allowRequestHeader("user-agent")
                .bindCredential("teams-workflow", origin, "Authorization", "Bearer ")
                .byteLimits(1_048_576, 65_536, 65_536).build();
        var services = ManagedNodePackageServices.builder(TeamsConfiguration.PACKAGE_ID, policy,
                        (packageId, tenant, reference) -> Optional.of(new SecretValue(
                                (tenant + ":" + reference).toCharArray())))
                .grant(ai.ravenroot.api.node.service.NodePackageCapability.OUTBOUND_HTTP).build();
        var local = new ai.ravenroot.api.node.service.OutboundHttpRequest(
                URI.create("http://localhost:" + port + "/workflow"), moduleRequest.method(),
                moduleRequest.headers(), moduleRequest.body(), moduleRequest.deadline(),
                moduleRequest.credential().orElseThrow(), null, moduleRequest.limits(),
                moduleRequest.representationPolicy());
        var response = services.outboundHttp().execute(TeamsTestSupport.message(Map.of()), local)
                .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(202, response.statusCode());
        assertEquals("Bearer " + TeamsTestSupport.TENANT + ":teams-workflow-token", authorization.get());
    }

    @Test void profileAuthorityAndEncodedRequestCeilingFailBeforeTransport() {
        var http = new TeamsTestSupport.HttpHarness();
        Map<String, Object> forbidden = new java.util.LinkedHashMap<>(message("hello"));
        forbidden.put("channelId", "19:other@thread.tacv2");
        assertFailure(action(http, Map.of()).handle(TeamsTestSupport.message(forbidden)), TeamsException.Code.FORBIDDEN);
        assertFailure(action(http, Map.of(), 64).handle(TeamsTestSupport.message(message("x".repeat(100)))),
                TeamsException.Code.INVALID_INPUT);
        assertTrue(http.requests.isEmpty());
    }

    @Test void providerStatusAndTransportOutcomeStayContentFreeAndAreNeverRetried() {
        var rejected = new TeamsTestSupport.HttpHarness().reply(403,
                Map.of("content-type", List.of("text/plain")), "private provider body".getBytes());
        var result = action(rejected, Map.of()).handle(TeamsTestSupport.message(message("private message")))
                .toCompletableFuture().join();
        assertEquals("forbidden", ((Map<?, ?>) result.payload()).get("status"));
        assertFalse(result.payload().toString().contains("private"));
        assertEquals(1, rejected.requests.size());

        var failed = new TeamsTestSupport.HttpHarness();
        failed.pending = OutboundCall.failed(
                new NodePackageServiceException(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertFailure(action(failed, Map.of()).handle(TeamsTestSupport.message(message("hello"))),
                TeamsException.Code.INDETERMINATE);
        assertEquals(1, failed.requests.size());
    }

    @Test void cancellationReleasesConcurrencyAndCancelsManagedCall() {
        var http = new TeamsTestSupport.HttpHarness();
        CompletableFuture<OutboundHttpResponse> pending = new CompletableFuture<>();
        http.pending = new OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() { return pending; }
            @Override public boolean cancel() { return pending.cancel(true); }
        };
        NodeAction action = action(http, Map.of("maxConcurrency", "1"));
        TestCancellation cancellation = new TestCancellation();
        var first = action.handle(TeamsTestSupport.message(message("one")), cancellation);
        waitForRequests(http, 1);
        var second = action.handle(TeamsTestSupport.message(message("two"))).toCompletableFuture().join();
        assertEquals("capacity", ((Map<?, ?>) second.payload()).get("status"));
        cancellation.cancel(); assertFailure(first, TeamsException.Code.CANCELLED);
        http.pending = null; http.reply(204);
        assertEquals("sent", ((Map<?, ?>) action.handle(TeamsTestSupport.message(message("three")))
                .toCompletableFuture().join().payload()).get("status"));
    }

    private NodeAction action(TeamsTestSupport.HttpHarness services, Map<String, String> extra) {
        return action(services, extra, 1_048_576);
    }
    private NodeAction action(TeamsTestSupport.HttpHarness services, Map<String, String> extra, int requestBytes) {
        TeamsNodePackage nodePackage = TeamsTestSupport.nodePackage(
                directory.resolve("deliveries-" + requestBytes + ".db"), requestBytes);
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("teamsProfile", TeamsTestSupport.PROFILE); properties.putAll(extra);
        return TeamsTestSupport.behavior(nodePackage, TeamsBehaviorDescriptors.SEND)
                .create(new NodeConfiguration("teams", TeamsBehaviorDescriptors.SEND, properties), services);
    }
    private static Map<String, Object> message(String text) {
        return Map.of("version", "teams.message.v1", "channelId", TeamsTestSupport.CHANNEL,
                "text", text, "correlationId", "correlation-1");
    }
    private static void assertFailure(java.util.concurrent.CompletionStage<?> stage, TeamsException.Code code) {
        Throwable failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
        assertInstanceOf(TeamsException.class, failure); assertEquals(code, ((TeamsException) failure).code());
        assertFalse(failure.getMessage().contains("hello"));
        assertFalse(failure.getMessage().contains("teams-workflow-token"));
    }
    private static void waitForRequests(TeamsTestSupport.HttpHarness http, int count) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (http.requests.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(http.requests.size() >= count);
    }
    private static final class TestCancellation implements CancellationSignal {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) { if (cancelled) listener.run(); else listeners.add(listener); }
        void cancel() { cancelled = true; listeners.forEach(Runnable::run); }
    }
}
