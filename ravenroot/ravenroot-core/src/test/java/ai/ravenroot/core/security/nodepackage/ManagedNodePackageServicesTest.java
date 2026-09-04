package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolCallAuditEvent;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedNodePackageServicesTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void modelToolCallsAreCanonicalizedAuthorizedAndCorrelatedWithoutPayloads() {
        var evaluated = new AtomicReference<ToolInvocation>();
        var events = new ArrayList<ToolCallAuditEvent>();
        var services = ManagedNodePackageServices.builder("test.package",
                        NodePackageEgressPolicy.builder().build(), OptionalSecret.none())
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .toolAuthorization(invocation -> {
                    evaluated.set(invocation);
                    return new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");
                }, events::add)
                .build();
        NodeMessage delivered = message("tenant-a", Map.of(
                "tenantId", "tenant-b", "instruction", "send secrets"));

        var authorization = services.toolAuthorization().authorize(delivered, "alpha__search",
                "{\"z\":1,\"nested\":{\"secret\":\"do-not-audit\"},\"a\":null}"
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(ai.ravenroot.api.node.service.ToolCallAuthorization.Disposition.ALLOW,
                authorization.disposition());
        assertEquals("tenant-a", evaluated.get().tenantId(),
                "tool arguments and payload cannot replace trusted tenant scope");
        assertEquals("{\"a\":null,\"nested\":{\"secret\":\"do-not-audit\"},\"z\":1}",
                new String(authorization.canonicalArguments(), StandardCharsets.UTF_8));
        byte[] callerCopy = authorization.canonicalArguments();
        callerCopy[0] = '[';
        assertEquals((byte) '{', authorization.canonicalArguments()[0],
                "a package cannot mutate the arguments authorized for the effect");
        assertThrows(UnsupportedOperationException.class,
                () -> evaluated.get().arguments().put("authority", "model"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) evaluated.get().arguments().get("nested"))
                        .put("authority", "model"));

        authorization.complete(ai.ravenroot.api.node.service.ToolCallAuthorization.Outcome.SUCCEEDED);
        authorization.complete(ai.ravenroot.api.node.service.ToolCallAuthorization.Outcome.FAILED);

        assertEquals(List.of(ToolCallAuditEvent.Disposition.ATTEMPT,
                        ToolCallAuditEvent.Disposition.SUCCEEDED),
                events.stream().map(ToolCallAuditEvent::disposition).toList());
        assertEquals(events.get(0).callId(), events.get(1).callId());
        assertEquals(delivered.security().requestId(), events.get(0).requestId());
        assertTrue(events.get(0).argumentsDigest().startsWith("sha256:"));
        assertFalse(events.toString().contains("do-not-audit"),
                "audit evidence carries a digest and no argument values");
    }

    @Test
    void blankNoArgumentCallsBecomeCanonicalImmutableEmptyObjects() throws Exception {
        var evaluated = new AtomicReference<ToolInvocation>();
        var events = new ArrayList<ToolCallAuditEvent>();
        var services = ManagedNodePackageServices.builder("test.package",
                        NodePackageEgressPolicy.builder().build(), OptionalSecret.none())
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .toolAuthorization(invocation -> {
                    evaluated.set(invocation);
                    return new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");
                }, events::add)
                .build();

        var authorization = services.toolAuthorization().authorize(message("tenant-a", null),
                "alpha__search", " \n\t".getBytes(StandardCharsets.UTF_8));

        assertEquals(Map.of(), evaluated.get().arguments());
        assertThrows(UnsupportedOperationException.class,
                () -> evaluated.get().arguments().put("authority", "model"));
        assertEquals("{}", new String(authorization.canonicalArguments(), StandardCharsets.UTF_8));
        authorization.complete(ai.ravenroot.api.node.service.ToolCallAuthorization.Outcome.SUCCEEDED);
        assertEquals(List.of(ToolCallAuditEvent.Disposition.ATTEMPT,
                        ToolCallAuditEvent.Disposition.SUCCEEDED),
                events.stream().map(ToolCallAuditEvent::disposition).toList());
        assertEquals(events.get(0).callId(), events.get(1).callId());
        String emptyObjectDigest = "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(emptyObjectDigest, authorization.argumentsDigest());
        assertEquals(List.of(emptyObjectDigest, emptyObjectDigest),
                events.stream().map(ToolCallAuditEvent::argumentsDigest).toList());
    }

    @Test
    void invalidOrUnconfiguredModelToolCallsFailClosedBeforePolicy() {
        var policyCalls = new AtomicInteger();
        var events = new ArrayList<ToolCallAuditEvent>();
        var configured = ManagedNodePackageServices.builder("test.package",
                        NodePackageEgressPolicy.builder().build(), OptionalSecret.none())
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .toolAuthorization(invocation -> {
                    policyCalls.incrementAndGet();
                    return new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");
                }, events::add)
                .build();

        assertEquals(ai.ravenroot.api.node.service.ToolCallAuthorization.Disposition.DENY,
                configured.toolAuthorization().authorize(message("tenant-a", null), "alpha__search",
                        "not-json".getBytes(StandardCharsets.UTF_8)).disposition());
        assertEquals(ai.ravenroot.api.node.service.ToolCallAuthorization.Disposition.DENY,
                NodePackageServices.unavailable().toolAuthorization().authorize(
                        message("tenant-a", null), "alpha__search", "{}".getBytes(StandardCharsets.UTF_8))
                        .disposition());
        assertEquals(0, policyCalls.get());
        assertEquals(ToolCallAuditEvent.Disposition.DENIED, events.get(0).disposition());
    }

    @Test
    void exactOriginTenantCredentialBindingAndResponseProjectionAreEnforced() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server = server(exchange -> {
            hits.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("X-Result", "kept");
            exchange.getResponseHeaders().add("X-Secret-Metadata", "dropped");
            respond(exchange, 200, "ok");
        });
        int port = server.getAddress().getPort();
        var origin = new NodePackageEgressPolicy.Origin("http", "localhost", port);
        var policy = NodePackageEgressPolicy.builder()
                .allowOrigin("http", "localhost", port)
                .allowHttpMethod("POST")
                .allowRequestHeader("X-Safe")
                .allowResponseHeader("X-Result")
                .bindCredential("bearer", origin, "Authorization", "Bearer ")
                .byteLimits(1024, 17, 1024)
                .build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP),
                (packageId, tenant, reference) -> OptionalSecret.of(tenant + ":" + reference)
                        .resolve(packageId, tenant, reference));

        OutboundHttpResponse response = await(services.outboundHttp().execute(message("tenant-a", Map.of(
                        "ravenroot.security.tenantId", "tenant-b")),
                new OutboundHttpRequest(uri(port, "/"), "POST", Map.of("X-Safe", List.of("yes")),
                        "payload".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2),
                        new OutboundCredentialBinding("bearer", "api"))));

        assertEquals(1, hits.get());
        assertEquals("Bearer tenant-a:api", authorization.get(),
                "tenant comes only from the delivered SecurityContext, never payload/attributes");
        assertEquals(List.of("kept"), response.headers().get("x-result"));
        assertFalse(response.headers().containsKey("x-secret-metadata"));
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), response.body());
        assertEquals(17, response.effectiveMaximumOutputBytes(),
                "the response carries the output ceiling after operator intersection");
    }

    @Test
    void redirectIsReturnedAndNeverFollowed() throws Exception {
        AtomicInteger redirectedHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/second");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/second", exchange -> {
            redirectedHits.incrementAndGet();
            respond(exchange, 200, "wrong");
        });
        server.start();
        int port = server.getAddress().getPort();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("GET").build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP), OptionalSecret.none());

        OutboundHttpResponse response = await(services.outboundHttp().execute(message("tenant-a", null),
                new OutboundHttpRequest(uri(port, "/redirect"), "GET", Map.of(), null,
                        Duration.ofSeconds(2), null)));

        assertEquals(302, response.statusCode());
        assertEquals(0, redirectedHits.get());
    }

    @Test
    void forbiddenOriginPortLiteralAndSensitiveHeaderSendNothing() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = server(exchange -> { hits.incrementAndGet(); respond(exchange, 200, "ok"); });
        int port = server.getAddress().getPort();
        var policy = NodePackageEgressPolicy.builder()
                .allowOrigin("http", "localhost", port).allowHttpMethod("GET")
                .allowRequestHeader("Authorization").build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP), OptionalSecret.none());

        assertReason(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(
                        URI.create("https://localhost:" + port + "/"), "GET", Map.of(), null,
                        Duration.ofSeconds(1), null)));
        assertReason(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(
                        URI.create("http://localhost:" + (port + 1) + "/"), "GET", Map.of(), null,
                        Duration.ofSeconds(1), null)));

        var literalPolicy = NodePackageEgressPolicy.builder()
                .allowOrigin("http", "127.0.0.1", port).allowHttpMethod("GET").build();
        var literalServices = services(literalPolicy, Set.of(NodePackageCapability.OUTBOUND_HTTP),
                OptionalSecret.none());
        assertReason(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN,
                literalServices.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(
                        URI.create("http://127.0.0.1:" + port + "/"), "GET", Map.of(), null,
                        Duration.ofSeconds(1), null)));
        assertReason(NodePackageServiceException.Reason.PROTOCOL_REFUSED,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(uri(port, "/"),
                        "GET", Map.of("Authorization", List.of("graph-secret")), null,
                        Duration.ofSeconds(1), null)));
        assertEquals(0, hits.get());
    }

    @Test
    void requestAndStreamingResponseCeilingsFailWithStableReasons() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = server(exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "0123456789");
        });
        int port = server.getAddress().getPort();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("POST").byteLimits(4, 4, 4).build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP), OptionalSecret.none());

        assertReason(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(uri(port, "/"),
                        "POST", Map.of(), new byte[5], Duration.ofSeconds(1), null)));
        assertEquals(0, hits.get(), "oversize request must be refused before transport");
        assertReason(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(uri(port, "/"),
                        "POST", Map.of(), new byte[4], Duration.ofSeconds(1), null)));
        assertEquals(1, hits.get());
    }

    @Test
    void requestSpecificLimitsOnlyNarrowOperatorAuthorityBeforeTransportAndProjection() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server = server(exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            respond(exchange, 200, "0123456789");
        });
        int port = server.getAddress().getPort();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("POST").byteLimits(128, 128, 128).build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP), OptionalSecret.none());

        ExternalIoLimits narrow = ExternalIoLimits.http(4, 6, Duration.ofSeconds(1), Set.of("text/plain"));
        assertReason(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(uri(port, "/"),
                        "POST", Map.of(), new byte[5], Duration.ofSeconds(2), null, null, narrow)));
        assertEquals(0, hits.get());
        assertReason(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(uri(port, "/"),
                        "POST", Map.of(), new byte[4], Duration.ofSeconds(2), null, null, narrow)));
        assertEquals(1, hits.get());

        ExternalIoLimits wrongMedia = ExternalIoLimits.http(4, 128, Duration.ofSeconds(1),
                Set.of("application/json"));
        assertReason(NodePackageServiceException.Reason.PROTOCOL_REFUSED,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(uri(port, "/"),
                        "POST", Map.of(), new byte[4], Duration.ofSeconds(2), null, null, wrongMedia)));
        assertEquals(2, hits.get());
    }

    @Test
    void cancellingASlowHttpReadStopsTheCallAndReturnsItsPermitAfterTransportCleanup() throws Exception {
        CountDownLatch firstByteSent = new CountDownLatch(1);
        CountDownLatch releaseSlowResponse = new CountDownLatch(1);
        server = server(exchange -> {
            if ("/slow".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                firstByteSent.countDown();
                releaseSlowResponse.await();
                exchange.close();
                return;
            }
            respond(exchange, 200, "ok");
        });
        int port = server.getAddress().getPort();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("GET").concurrencyLimits(1, 1)
                .maximumDeadline(Duration.ofSeconds(2)).build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP), OptionalSecret.none());

        OutboundCall<OutboundHttpResponse> slow = services.outboundHttp().execute(message("tenant-a", null),
                new OutboundHttpRequest(uri(port, "/slow"), "GET", Map.of(), null,
                        Duration.ofSeconds(2), null));
        assertTrue(firstByteSent.await(1, TimeUnit.SECONDS));
        assertReason(NodePackageServiceException.Reason.ADMISSION_REFUSED,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(
                        uri(port, "/fast"), "GET", Map.of(), null, Duration.ofSeconds(1), null)));
        assertTrue(slow.cancel());
        assertReason(NodePackageServiceException.Reason.CANCELLED, slow);
        releaseSlowResponse.countDown();

        OutboundHttpResponse recovered = null;
        long recoveryDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (recovered == null && System.nanoTime() < recoveryDeadline) {
            try {
                recovered = await(services.outboundHttp().execute(message("tenant-a", null),
                        new OutboundHttpRequest(uri(port, "/fast"), "GET", Map.of(), null,
                                Duration.ofSeconds(1), null)));
            } catch (CompletionException refusal) {
                if (!(refusal.getCause() instanceof NodePackageServiceException typed)
                        || typed.reason() != NodePackageServiceException.Reason.ADMISSION_REFUSED) throw refusal;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        assertEquals("ok", new String(java.util.Objects.requireNonNull(recovered).body(),
                StandardCharsets.UTF_8));
    }

    @Test
    void allowlistedResponseHeadersRemainBoundedBeforeTheyReachThePackage() throws Exception {
        server = server(exchange -> {
            exchange.getResponseHeaders().add("X-Result", "x".repeat(17_000));
            respond(exchange, 200, "ok");
        });
        int port = server.getAddress().getPort();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("http", "localhost", port)
                .allowHttpMethod("GET").allowResponseHeader("X-Result").build();
        var services = services(policy, Set.of(NodePackageCapability.OUTBOUND_HTTP), OptionalSecret.none());

        assertReason(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE,
                services.outboundHttp().execute(message("tenant-a", null), new OutboundHttpRequest(
                        uri(port, "/"), "GET", Map.of(), null, Duration.ofSeconds(2), null)));
    }

    @Test
    void cancellationAndTotalDeadlineReleaseAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var policy = NodePackageEgressPolicy.builder().concurrencyLimits(1, 1)
                .maximumDeadline(Duration.ofSeconds(2)).build();
        var services = services(policy, Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                (packageId, tenant, reference) -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); return java.util.Optional.empty();
                    }
                    return OptionalSecret.of("secret").resolve(packageId, tenant, reference);
                });

        OutboundCall<CredentialLease> first = services.credentials().resolve(message("tenant-a", null), "one",
                Duration.ofSeconds(2));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertReason(NodePackageServiceException.Reason.ADMISSION_REFUSED,
                services.credentials().resolve(message("tenant-a", null), "two", Duration.ofSeconds(1)));
        assertTrue(first.cancel());
        assertReason(NodePackageServiceException.Reason.CANCELLED, first);
        release.countDown();

        CredentialLease next = null;
        for (int attempt = 0; attempt < 100 && next == null; attempt++) {
            try {
                next = await(services.credentials().resolve(message("tenant-a", null), "three",
                        Duration.ofSeconds(1)));
            } catch (CompletionException refusal) {
                if (!(refusal.getCause() instanceof NodePackageServiceException typed)
                        || typed.reason() != NodePackageServiceException.Reason.ADMISSION_REFUSED) throw refusal;
                Thread.sleep(5);
            }
        }
        assertTrue(next != null, "cancelled resolver must eventually unwind and release admission");
        next.close();

        var timeoutServices = services(policy, Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                (packageId, tenant, reference) -> {
                    try { Thread.sleep(5_000); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return java.util.Optional.empty();
                });
        assertReason(NodePackageServiceException.Reason.DEADLINE_EXCEEDED,
                timeoutServices.credentials().resolve(message("tenant-a", null), "slow",
                        Duration.ofMillis(25)));
    }

    @Test
    void packageAndTenantAdmissionRemainIsolatedUnderConcurrency() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var policy = NodePackageEgressPolicy.builder().concurrencyLimits(2, 1).build();
        var services = services(policy, Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                (packageId, tenant, reference) -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return OptionalSecret.of(packageId + ":" + tenant).resolve(packageId, tenant, reference);
                });
        OutboundCall<CredentialLease> tenantA = services.credentials().resolve(message("tenant-a", null), "x",
                Duration.ofSeconds(2));
        OutboundCall<CredentialLease> tenantB = services.credentials().resolve(message("tenant-b", null), "x",
                Duration.ofSeconds(2));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        assertReason(NodePackageServiceException.Reason.ADMISSION_REFUSED,
                services.credentials().resolve(message("tenant-a", null), "x", Duration.ofSeconds(1)));
        assertReason(NodePackageServiceException.Reason.ADMISSION_REFUSED,
                services.credentials().resolve(message("tenant-c", null), "x", Duration.ofSeconds(1)));
        release.countDown();
        try (CredentialLease a = await(tenantA); CredentialLease b = await(tenantB)) {
            assertNotSame(a, b);
        }
    }

    @Test
    void dtoBodiesAndHeadersAreDefensivelyCopied() {
        byte[] body = {1, 2};
        var values = new java.util.ArrayList<>(List.of("before"));
        var headers = new java.util.HashMap<String, List<String>>();
        headers.put("X-Test", values);
        OutboundHttpRequest request = new OutboundHttpRequest(URI.create("https://example.test"), "POST",
                headers, body, Duration.ofSeconds(1), null);
        body[0] = 9;
        values.set(0, "after");
        headers.clear();

        assertArrayEquals(new byte[]{1, 2}, request.body());
        assertEquals(List.of("before"), request.headers().get("X-Test"));
        byte[] exposed = request.body(); exposed[0] = 7;
        assertArrayEquals(new byte[]{1, 2}, request.body());
    }

    @Test
    void rawCredentialLeaseCanBeCopiedOnlyOnce() {
        var policy = NodePackageEgressPolicy.builder().build();
        var services = services(policy, Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                OptionalSecret.of("secret"));
        try (CredentialLease lease = await(services.credentials().resolve(message("tenant-a", null), "ref",
                Duration.ofSeconds(1)))) {
            char[] first = lease.copy();
            java.util.Arrays.fill(first, '\0');
            assertThrows(IllegalStateException.class, lease::copy);
        }
    }

    @Test
    void sourceOperationsDeriveTenantOnlyFromTheDeliveredContextIdentity() {
        AtomicReference<String> resolvedTenant = new AtomicReference<>();
        var services = services(NodePackageEgressPolicy.builder().build(),
                Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION), (packageId, tenant, reference) -> {
                    resolvedTenant.set(tenant);
                    return java.util.Optional.of(new SecretValue("secret".toCharArray()));
                });

        try (CredentialLease ignored = await(services.credentials().resolve(sourceContext("tenant-source"),
                "ref", Duration.ofSeconds(1)))) {
            assertEquals("tenant-source", resolvedTenant.get());
        }
    }

    @Test
    void operatorPolicyRejectsUnrepresentableBuffersAndDurationsAtComposition() {
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .byteLimits((long) Integer.MAX_VALUE + 1, 1, 1).build());
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressPolicy.builder()
                .maximumDeadline(Duration.ofSeconds(Long.MAX_VALUE)).build());
    }

    @Test
    void serviceCompositionCreatesNoTransportClient() {
        AtomicInteger clientCreations = new AtomicInteger();
        ManagedNodePackageServices.builder("test.lazy", NodePackageEgressPolicy.builder().build(),
                        OptionalSecret.none())
                .clientFactory(() -> {
                    clientCreations.incrementAndGet();
                    return java.net.http.HttpClient.newHttpClient();
                })
                .grant(NodePackageCapability.OUTBOUND_HTTP)
                .build();

        assertEquals(0, clientCreations.get(), "composition must not start transport/provider state");
    }

    private ManagedNodePackageServices services(NodePackageEgressPolicy policy,
                                                Set<NodePackageCapability> capabilities,
                                                TenantCredentialResolver resolver) {
        var builder = ManagedNodePackageServices.builder("test.package", policy, resolver);
        capabilities.forEach(builder::grant);
        return builder.build();
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        created.createContext("/", exchange -> {
            try { handler.handle(exchange); } catch (Exception failure) { exchange.close(); }
        });
        created.start();
        return created;
    }

    private static URI uri(int port, String path) { return URI.create("http://localhost:" + port + path); }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static NodeMessage message(String tenant, Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", tenant, "subject", PrincipalType.USER, "issuer"),
                id, id, id, id, Set.of(), "node", payload, Map.of());
    }

    private static InboundSourceContext sourceContext(String tenant) {
        SecurityContext identity = new SecurityContext("request", tenant, "subject",
                PrincipalType.USER, "issuer");
        return new InboundSourceContext() {
            @Override public DeploymentId deploymentId() { return DeploymentId.of("source-services"); }
            @Override public String nodeId() { return "source"; }
            @Override public SecurityContext identity() { return identity; }
            @Override public TrustedIngress ingress() { throw new UnsupportedOperationException(); }
            @Override public void reportDegraded(String sanitizedReason) { }
            @Override public void reportHealthy() { }
        };
    }

    private static <T> T await(OutboundCall<T> call) {
        return call.completion().toCompletableFuture().join();
    }

    private static void assertReason(NodePackageServiceException.Reason reason, OutboundCall<?> call) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> call.completion().toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof NodePackageServiceException, String.valueOf(failure.getCause()));
        assertEquals(reason, ((NodePackageServiceException) failure.getCause()).reason());
    }

    @FunctionalInterface
    private interface ThrowingHandler { void handle(HttpExchange exchange) throws Exception; }

    private static final class OptionalSecret {
        static TenantCredentialResolver of(String value) {
            return (packageId, tenant, reference) -> java.util.Optional.of(new SecretValue(value.toCharArray()));
        }
        static TenantCredentialResolver none() {
            return (packageId, tenant, reference) -> java.util.Optional.empty();
        }
    }
}
