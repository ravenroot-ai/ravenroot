package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpCorrelationHeaderProtocolTest {
    private static final ApplicationCorrelationContext CONTEXT = new ApplicationCorrelationContext("business-42", Optional.of("part-2"), Optional.empty(), "round-3", Optional.empty());
    private static final ToolPolicy ALLOW = ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "fixture", "");
    @Test void actualHttpWireCarriesBusinessHeadersSeparatelyFromCredentialRequestAndSagaIdentity() throws Exception {
        try (var fixture = new Fixture()) {
            var handler = fixture.factory(ALLOW).create(fixture.node(Map.of("credentialRef", "fixture-token", "credentialHeader", "X-Api-Key",
                    "header.X-Request-ID", "transport-request", "saga.participant", "http-idempotency-v1", "saga.adapter", "ravenroot.http-idempotency.v1")));
            assertEquals("continue", handler.handle(message(CONTEXT)).toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            var headers = fixture.headers.get();
            assertEquals("business-42", headers.get("x-business-operation")); assertEquals("part-2", headers.get("x-business-part"));
            assertEquals("round-3", headers.get("x-business-round")); assertEquals("transport-request", headers.get("x-request-id"));
            assertEquals("transport-saga-operation", headers.get("idempotency-key")); assertEquals("Bearer fixture-secret", headers.get("x-api-key"));
            assertNotEquals(headers.get("idempotency-key"), headers.get("x-business-operation"));
            var selection = CorrelationMappingProperties.read(Map.of("correlation.mode", "REQUIRE_EXISTING", "correlation.selector", "/headers/x-business-operation",
                    "correlation.contributionSelector", "/headers/x-business-part", "correlation.roundSelector", "/headers/x-business-round"), CorrelationLimits.DEFAULTS);
            assertEquals(CONTEXT, selection.extract(PayloadValue.fromJava(Map.of("headers", headers), PayloadLimits.DEFAULTS), () -> { throw new AssertionError("no generated fallback"); }));
            assertEquals(1, fixture.requests.get());
        }
    }
    @Test void missingContextConflictingAndCaseDuplicateHeadersRefuseBeforeHttpOrCredentialIo() throws Exception {
        try (var fixture = new Fixture()) {
            for (var headers : List.of(Map.<String, Object>of(), Map.<String, Object>of("header.x-business-operation", "different"),
                    Map.<String, Object>of("header.X-Case", "one", "header.x-case", "two"))) {
                var properties = new LinkedHashMap<String, Object>(headers); properties.put("credentialRef", "fixture-token");
                var handler = fixture.factory(ALLOW).create(fixture.node(properties));
                var delivered = message(headers.isEmpty() ? null : CONTEXT);
                assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, () -> handler.handle(delivered).toCompletableFuture().join()).getCause());
            }
            assertEquals(0, fixture.requests.get()); assertEquals(0, fixture.credentials.get());
        }
    }
    @Test void configuredCredentialAndProtocolTargetsRefuseAndMappingCannotGrantToolAuthority() throws Exception {
        try (var fixture = new Fixture()) {
            for (String target : List.of("x-API-key", "X-Ravenroot-Payload-SHA256", "X-Correlation-ID", "X-Request-ID", "Idempotency-Key")) {
                assertThrows(IllegalArgumentException.class, () -> fixture.factory(ALLOW).create(fixture.node(Map.of(
                        CorrelationHeaderMapping.APPLICATION, target, "credentialHeader", "X-Api-Key"))));
            }
            var denied = fixture.factory(ignored -> new ToolDecision(ToolDecision.Disposition.DENY, "fixture", "")).create(fixture.node(Map.of()));
            assertThrows(SecurityException.class, () -> denied.handle(message(CONTEXT)));
            assertEquals(0, fixture.requests.get()); assertEquals(0, fixture.credentials.get());
        }
    }
    @Test void mappedIdentityCannotBeSilentlyTrimmedByTheActualHttpProtocol() throws Exception {
        try (var fixture = new Fixture()) {
            var handler = fixture.factory(ALLOW).create(fixture.node(Map.of("credentialRef", "fixture-token")));
            for (String value : List.of(" business-42", "business-42 ")) {
                var context = new ApplicationCorrelationContext(value, CONTEXT.contributionId(), CONTEXT.contributionKind(),
                        CONTEXT.roundId(), CONTEXT.publicReference());
                assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class,
                        () -> handler.handle(message(context)).toCompletableFuture().join()).getCause());
            }
            assertEquals(0, fixture.requests.get()); assertEquals(0, fixture.credentials.get());
            var spaced = new ApplicationCorrelationContext("business operation", Optional.of("part two"),
                    Optional.empty(), "round three", Optional.empty());
            assertEquals("continue", handler.handle(message(spaced)).toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            assertEquals("business operation", fixture.headers.get().get("x-business-operation"));
            assertEquals("part two", fixture.headers.get().get("x-business-part"));
            assertEquals("round three", fixture.headers.get().get("x-business-round"));
        }
    }
    private static NodeMessage message(ApplicationCorrelationContext context) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("transport-request", "tenant", "operator", PrincipalType.USER, "fixture"), id, id, id, id, Set.of(), "http",
                Map.of("applicationCorrelationId", "payload-spoof"), Map.of("applicationCorrelationId", "attribute-spoof", "sagaOperationId", "transport-saga-operation"), NodeCommand.PROCESS, context);
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger(), credentials = new AtomicInteger();
        final AtomicReference<Map<String, String>> headers = new AtomicReference<>(); final HttpServer server;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/receive", exchange -> { requests.incrementAndGet(); var received = new LinkedHashMap<String, String>();
                exchange.getRequestHeaders().forEach((name, values) -> received.put(name.toLowerCase(Locale.ROOT), values.getFirst())); headers.set(received);
                exchange.getRequestBody().readAllBytes(); byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); }); server.start();
        }
        HttpRequestNodeBehaviorFactory factory(ToolPolicy policy) { return new HttpRequestNodeBehaviorFactory(
                new OutboundHttpPolicy(Set.of("localhost"), Duration.ofSeconds(2), Set.of(server.getAddress().getPort()), 4096, 4096),
                reference -> { credentials.incrementAndGet(); return Optional.of(new SecretValue("fixture-secret".toCharArray())); }, policy); }
        GraphNode node(Map<String, Object> overrides) {
            var properties = new LinkedHashMap<String, Object>(Map.of("url", "http://localhost:" + server.getAddress().getPort() + "/receive", "method", "POST", "body", "wire body",
                    CorrelationHeaderMapping.APPLICATION, "X-Business-Operation", CorrelationHeaderMapping.CONTRIBUTION, "X-Business-Part", CorrelationHeaderMapping.ROUND, "X-Business-Round"));
            properties.putAll(overrides); return new GraphNode("http", NodeKind.BEHAVIOR, "http-request", properties);
        }
        @Override public void close() { server.stop(0); }
    }
}
