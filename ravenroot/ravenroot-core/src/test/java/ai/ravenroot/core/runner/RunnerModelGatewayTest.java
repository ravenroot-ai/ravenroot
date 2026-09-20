package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP protocol conformance only; the mock endpoint is deliberately not model acceptance evidence. */
class RunnerModelGatewayTest {
    private static final SecretProvider NO_SECRETS = new SecretProvider() {
        public String id() { return "fixture"; }
        public Optional<SecretValue> get(String reference) { fail("loopback fixture must never resolve credentials"); return Optional.empty(); }
    };
    private static RunnerAssignment assignment() {
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(1), 64_000_000, 1, 1_000_000, 4096, 4096, 4096));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "reviewer", 1), "Review",
                "runtime", "approved-model", Map.of("review", new AgentCommand("review", true, policy, Set.of("answered"))),
                Set.of(), Set.of(), policy, Duration.ZERO, "object");
        var now = Instant.now();
        var identity = new RunnerJobIdentity(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var job = RunnerJob.accept(identity, definition, "review", policy,
                new RunnerRegistration(1, "tenant", "worker", "local-container-v1", Set.of(), policy),
                OpaquePayload.of("{}".getBytes(), "application/json"), now, now.plusSeconds(60));
        return new RunnerAssignment(1, UUID.randomUUID(), job);
    }
    private static Map<String, Object> request() { return Map.of("messages", List.of(Map.of("role", "user", "content", "review")),
            "tools", List.of(), "model", "untrusted-model", "endpoint", "http://untrusted.invalid/"); }
    private static RunnerModelGateway gateway(HttpServer server, int bytes) {
        return new RunnerModelGateway(Map.of("approved-model", new RunnerModelGateway.Profile(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"), "operator-model", null, 1, 4096, bytes)),
                NO_SECRETS, Duration.ofSeconds(1));
    }
    @Test void endpointAndModelAreOperatorSelectedAndUsageIsRequired() throws Exception {
        var received = new AtomicReference<Map<String, Object>>();
        var body = new AtomicReference<>("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"reviewed\"}}],\"usage\":{\"total_tokens\":19}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            try (exchange) {
                received.set(RunnerJson.read(exchange.getRequestBody().readAllBytes()));
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = body.get().getBytes(); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var gateway = gateway(server, 1024);
            assertEquals(19L, gateway.complete(assignment(), request(), 37, Duration.ofSeconds(2)).get("tokens"));
            assertEquals("operator-model", received.get().get("model"));
            assertEquals(37L, ((Number) received.get().get("max_tokens")).longValue());
            assertFalse(received.get().containsKey("endpoint"));
            body.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"unaccounted\"}}]}");
            assertThrows(IllegalArgumentException.class, () -> gateway.complete(assignment(), request(), 37, Duration.ofSeconds(2)));
            body.set("x".repeat(1025));
            assertThrows(java.io.IOException.class, () -> gateway.complete(assignment(), request(), 37, Duration.ofSeconds(2)));
        } finally { server.stop(0); }
    }
    @Test void bodyDeadlineAndProfileCapacityRemainEffectiveAfterHeadersArrive() throws Exception {
        var headersSent = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); headersSent.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var gateway = gateway(server, 1024);
            var pending = tasks.submit(() -> gateway.complete(assignment(), request(), 37, Duration.ofSeconds(1)));
            assertTrue(headersSent.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> gateway.complete(assignment(), request(), 37, Duration.ofSeconds(2)));
            assertInstanceOf(java.net.http.HttpTimeoutException.class,
                    assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS)).getCause());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void credentialsCannotTravelOverPlaintextOrGraphSelectedUrls() {
        for (String endpoint : List.of("http://public.example/chat", "https://user:password@example.test/chat", "https://example.test/chat?token=value"))
            assertThrows(IllegalArgumentException.class, () -> new RunnerModelGateway.Profile(URI.create(endpoint), "model", null, 1, 1024, 1024));
        assertThrows(IllegalArgumentException.class, () -> new RunnerModelGateway.Profile(URI.create("http://127.0.0.1/chat"), "model", "model-key", 1, 1024, 1024));
    }
    @Test void cancellationInterruptsPendingTransportAndFencesJobAndWorkspaceDispatch() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            try (exchange) {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                byte[] bytes = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":1}}".getBytes();
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var gateway = gateway(server, 1024); var job = assignment();
            var pending = tasks.submit(() -> gateway.complete(job, request(), 37, Duration.ofSeconds(30)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            gateway.cancel(job);
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
                    () -> pending.get(2, TimeUnit.SECONDS)).getCause());
            assertThrows(CancellationException.class, () -> gateway.complete(job, request(), 37, Duration.ofSeconds(2)));
            gateway.cancelWorkspace(job);
            var later = new RunnerAssignment(1, job.workspaceId(), assignment().job());
            assertThrows(CancellationException.class, () -> gateway.complete(later, request(), 37, Duration.ofSeconds(2)));
            assertEquals(1, calls.get(), "neither a later turn nor a different Agent in the stopped Workspace may dispatch");
            release.countDown();
            assertEquals(1L, gateway.complete(assignment(), request(), 37, Duration.ofSeconds(2)).get("tokens"),
                    "a different Workspace remains eligible");
        } finally { release.countDown(); server.stop(0); }
    }
}
