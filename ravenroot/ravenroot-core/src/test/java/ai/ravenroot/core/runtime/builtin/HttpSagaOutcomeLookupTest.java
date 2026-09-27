package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.NodeHandler;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpSagaOutcomeLookupTest {
    private static final String TOKEN = "participant-secret";

    @Test
    void lostResponseUsesTheSameCredentialAndAcceptsOnlyTheBoundParticipantReceipt() throws Exception {
        AtomicReference<String> fingerprint = new AtomicReference<>();
        AtomicReference<String> forwardAuthorization = new AtomicReference<>();
        AtomicReference<String> lookupAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/effects", exchange -> {
            exchange.getRequestBody().readAllBytes();
            forwardAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            fingerprint.set(exchange.getRequestHeaders().getFirst("X-Ravenroot-Payload-SHA256"));
            // The participant committed the effect but the response was lost.
            exchange.close();
        });
        server.createContext("/operations", exchange -> {
            lookupAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String operation = exchange.getRequestURI().getPath().substring("/operations/".length());
            byte[] response = ("{\"operationId\":\"" + operation + "\",\"fingerprint\":\""
                    + fingerprint.get() + "\",\"state\":\"APPLIED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String operation = "forward:" + UUID.randomUUID();
            var result = handler(server).handle(message(operation))
                    .toCompletableFuture().join();

            assertEquals("continue", result.outcome());
            assertEquals(Boolean.TRUE, result.attributes().get("saga.reconciled"));
            assertEquals("Bearer " + TOKEN, forwardAuthorization.get());
            assertEquals("Bearer " + TOKEN, lookupAuthorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aLookupReceiptForDifferentPayloadCannotTurnAnUnknownEffectIntoSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/effects", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        server.createContext("/operations", exchange -> {
            String operation = exchange.getRequestURI().getPath().substring("/operations/".length());
            byte[] response = ("{\"operationId\":\"" + operation + "\",\"fingerprint\":\""
                    + "0".repeat(64) + "\",\"state\":\"APPLIED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String operation = "forward:" + UUID.randomUUID();
            assertThrows(CompletionException.class, () -> handler(server)
                    .handle(message(operation)).toCompletableFuture().join());
        } finally {
            server.stop(0);
        }
    }

    private static NodeHandler handler(HttpServer server) {
        int port = server.getAddress().getPort();
        var policy = new OutboundHttpPolicy(Set.of("localhost"), Duration.ofSeconds(2), Set.of(port),
                4096, 4096);
        var factory = new HttpRequestNodeBehaviorFactory(policy,
                reference -> "participant-token".equals(reference)
                        ? Optional.of(new SecretValue(TOKEN.toCharArray())) : Optional.empty(),
                ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""));
        return factory.create(new GraphNode("participant", NodeKind.BEHAVIOR, "http-request", Map.of(
                "url", "http://localhost:" + port + "/effects",
                "method", "POST",
                "body", "{\"orderId\":\"486\"}",
                "credentialRef", "participant-token",
                "saga.participant", "http-idempotency-v1",
                "saga.outcomeLookupUrl", "http://localhost:" + port + "/operations/"
                        + "{{attributes.sagaOperationId}}")));
    }

    private static NodeMessage message(String operation) {
        return new NodeMessage(new SecurityContext("request", "tenant", "operator", PrincipalType.USER,
                "urn:ravenroot:test"), UUID.randomUUID(), UUID.randomUUID(), "participant", Map.of(),
                Map.of("sagaOperationId", operation));
    }
}
