package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
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
import java.nio.charset.StandardCharsets;
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

class MattermostSendBehaviorTest {
    @TempDir Path directory;
    private HttpServer server;
    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void postsExactBoundedJsonUsingOnlyManagedCredentialBindingAndReturnsNoContent() {
        var http = new MattermostTestSupport.HttpHarness().reply(201, Map.of("id", MattermostTestSupport.POST,
                "channel_id", MattermostTestSupport.CHANNEL, "message", "remote private copy"));
        var result = action(http, Map.of()).handle(MattermostTestSupport.message(message("private text")))
                .toCompletableFuture().join();
        Map<?, ?> output = (Map<?, ?>) result.payload();
        assertEquals("mattermost.message.result.v1", output.get("version")); assertEquals("sent", output.get("status"));
        assertFalse(output.toString().contains("private text")); assertFalse(output.toString().contains("remote private copy"));
        OutboundHttpRequest request = http.requests.getFirst();
        assertEquals("https://mattermost.example.test/api/v4/posts", request.destination().toString());
        assertEquals("mattermost-bearer", request.credential().orElseThrow().bindingId());
        assertEquals("mattermost-bot-token", request.credential().orElseThrow().reference());
        assertFalse(request.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("authorization")));
        assertEquals(Map.of("channel_id", MattermostTestSupport.CHANNEL, "message", "private text"),
                MattermostValues.json(request.body()));
    }

    @Test void actualManagedServiceInjectsOperatorBoundBearerForModuleRequest() throws Exception {
        var capture = new MattermostTestSupport.HttpHarness().reply(201,
                Map.of("id", MattermostTestSupport.POST, "channel_id", MattermostTestSupport.CHANNEL));
        action(capture, Map.of()).handle(MattermostTestSupport.message(message("managed")))
                .toCompletableFuture().join();
        OutboundHttpRequest moduleRequest = capture.requests.getFirst();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v4/posts", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = MattermostValues.jsonBytes(Map.of("id", MattermostTestSupport.POST,
                    "channel_id", MattermostTestSupport.CHANNEL));
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(201, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start(); int port = server.getAddress().getPort();
        var origin = new NodePackageEgressPolicy.Origin("http", "localhost", port);
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("POST").allowRequestHeader("accept").allowRequestHeader("content-type")
                .allowRequestHeader("user-agent").allowResponseHeader("content-type")
                .bindCredential("mattermost-bearer", origin, "Authorization", "Bearer ")
                .byteLimits(1_048_576, 65_536, 65_536).build();
        var services = ManagedNodePackageServices.builder(MattermostConfiguration.PACKAGE_ID, policy,
                        (packageId, tenant, reference) -> Optional.of(new SecretValue(
                                (tenant + ":" + reference).toCharArray())))
                .grant(NodePackageCapability.OUTBOUND_HTTP).build();
        OutboundHttpRequest local = new OutboundHttpRequest(URI.create("http://localhost:" + port + "/api/v4/posts"),
                moduleRequest.method(), moduleRequest.headers(), moduleRequest.body(), moduleRequest.deadline(),
                moduleRequest.credential().orElseThrow(), null, moduleRequest.limits(), moduleRequest.representationPolicy());
        OutboundHttpResponse response = services.outboundHttp().execute(MattermostTestSupport.message(Map.of()), local)
                .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(201, response.statusCode());
        assertEquals("Bearer tenant-a:mattermost-bot-token", authorization.get());
    }

    @Test void rejectsUnlistedFieldsAndChannelsBeforeTransport() {
        var http = new MattermostTestSupport.HttpHarness();
        Map<String, Object> unknown = new java.util.LinkedHashMap<>(message("hello")); unknown.put("token", "forged");
        assertFailure(action(http, Map.of()).handle(MattermostTestSupport.message(unknown)),
                MattermostException.Code.INVALID_INPUT);
        Map<String, Object> forbidden = new java.util.LinkedHashMap<>(message("hello"));
        forbidden.put("channelId", MattermostTestSupport.OTHER_CHANNEL);
        assertFailure(action(http, Map.of()).handle(MattermostTestSupport.message(forbidden)),
                MattermostException.Code.FORBIDDEN);
        assertTrue(http.requests.isEmpty());
    }

    @Test void retriesOnlyExplicitRateLimitAndSanitizesFailures() {
        var http = new MattermostTestSupport.HttpHarness()
                .reply(429, Map.of("retry-after", List.of("1"), "content-type", List.of("application/json")),
                        Map.of("message", "private rate detail"))
                .reply(201, Map.of("id", MattermostTestSupport.POST, "channel_id", MattermostTestSupport.CHANNEL));
        var result = action(http, Map.of("retries", "1")).handle(MattermostTestSupport.message(message("hello")))
                .toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status")); assertEquals(2, http.requests.size());
        assertFalse(result.payload().toString().contains("private rate detail"));

        var failed = new MattermostTestSupport.HttpHarness();
        failed.pending = OutboundCall.failed(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertFailure(action(failed, Map.of("retries", "1")).handle(
                MattermostTestSupport.message(message("hello"))), MattermostException.Code.INDETERMINATE);
        assertEquals(1, failed.requests.size());
    }

    @Test void cancellationStopsBackoffReleasesConcurrencyAndPreservesProviderBlock() {
        var http = new MattermostTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("2"), "content-type", List.of("application/json")), Map.of());
        NodeAction action = action(http, Map.of("retries", "1", "maxConcurrency", "1"));
        TestCancellation cancellation = new TestCancellation();
        var first = action.handle(MattermostTestSupport.message(message("hello")), cancellation);
        waitForRequests(http, 1); cancellation.cancel(); assertFailure(first, MattermostException.Code.CANCELLED);
        assertEquals(1, http.requests.size());
        Map<?, ?> after = (Map<?, ?>) action.handle(MattermostTestSupport.message(message("again")))
                .toCompletableFuture().join().payload();
        assertEquals("rate-limited", after.get("status"));
        assertEquals("local-rate-limit", after.get("evidence"));
    }

    private NodeAction action(MattermostTestSupport.HttpHarness services, Map<String, String> extra) {
        MattermostNodePackage nodePackage = MattermostTestSupport.nodePackage(directory.resolve("deliveries.db"));
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("mattermostProfile", MattermostTestSupport.PROFILE); properties.putAll(extra);
        return MattermostTestSupport.behavior(nodePackage, MattermostBehaviorDescriptors.SEND)
                .create(new NodeConfiguration("mattermost", MattermostBehaviorDescriptors.SEND, properties), services);
    }
    private static Map<String, Object> message(String text) {
        return Map.of("version", "mattermost.message.v1", "channelId", MattermostTestSupport.CHANNEL,
                "text", text, "correlationId", "correlation-1");
    }
    private static void assertFailure(java.util.concurrent.CompletionStage<?> stage, MattermostException.Code code) {
        Throwable failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
        assertInstanceOf(MattermostException.class, failure); assertEquals(code, ((MattermostException) failure).code());
        assertFalse(failure.getMessage().contains("hello")); assertFalse(failure.getMessage().contains("mattermost-bot-token"));
    }
    private static void waitForRequests(MattermostTestSupport.HttpHarness http, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (http.requests.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(http.requests.size() >= count);
    }
    private static final class TestCancellation implements CancellationSignal {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>(); private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) { if (cancelled) listener.run(); else listeners.add(listener); }
        void cancel() { cancelled = true; listeners.forEach(Runnable::run); }
    }
}
