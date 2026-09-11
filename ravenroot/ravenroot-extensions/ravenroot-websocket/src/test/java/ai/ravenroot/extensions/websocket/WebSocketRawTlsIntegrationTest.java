package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import ai.ravenroot.core.security.nodepackage.WebSocketManagedServicesHarness;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.message;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketRawTlsIntegrationTest {
    @Test
    void realManagedHandshakeFixesAuthorityHeadersSubprotocolAndRotatesCredential() throws Exception {
        try (var server = new DeterministicTlsWebSocketServer(
                DeterministicTlsWebSocketServer.Script.CAPTURE_SEND, 2)) {
            AtomicInteger generations = new AtomicInteger();
            NodePackageServices services = services(server, generations);
            WebSocketProfile profile = profile(server.port(), 2, 4);
            NodeAction action = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile),
                    new WebSocketAdmissionRegistry()).create(WebSocketTestSupport.configuration(), services);

            NodeResult first = action.handle(message("tenant-a", text("first"))).toCompletableFuture().join();
            NodeResult second = action.handle(message("tenant-a", text("second"))).toCompletableFuture().join();
            server.awaitConnections();

            assertEquals(List.of("/events", "/events"), server.requestTargets);
            assertEquals("localhost:" + server.port(), server.handshakes.get(0).get("host"));
            assertEquals("ravenroot", server.handshakes.get(0).get("x-client"));
            assertEquals("events.v1", server.handshakes.get(0).get("sec-websocket-protocol"));
            assertEquals("Bearer token-1", server.handshakes.get(0).get("authorization"));
            assertEquals("Bearer token-2", server.handshakes.get(1).get("authorization"));
            assertEquals(List.of("first", "second"), server.clientMessages.stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList());
            String observable = first.payload() + " " + second.payload();
            assertFalse(observable.contains("token-1"));
            assertFalse(observable.contains("token-2"));
            assertTrue(server.handshakes.toString().contains("token-1"),
                    "the authorized peer is expected to receive the credential");
        }
    }

    @Test
    void realManagedPolicyRefusalIsTypedAndCredentialIsRedactedFromFailureSurfaces() throws Exception {
        try (var server = DeterministicTlsWebSocketServer.rejectingHandshake()) {
            NodePackageServices services = services(server, new AtomicInteger());
            WebSocketProfile profile = profile(server.port(), 1, 2);
            NodeAction action = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile),
                    new WebSocketAdmissionRegistry()).create(WebSocketTestSupport.configuration(), services);

            WebSocketException refused = WebSocketTestSupport.failure(
                    action.handle(message("tenant-a", text("refused"))));
            server.awaitConnections();

            assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, refused.code());
            assertTrue(server.handshakes.getFirst().get("authorization").contains("token-1"));
            String observableFailure = refused.code() + " " + refused.getMessage();
            assertFalse(observableFailure.contains("token-1"));
            assertFalse(observableFailure.contains("socket-secret"));
        }
    }

    @Test
    void realManagedTextAndBinaryByteAndFragmentCeilingsAcceptExactAndRefusePlusOne() throws Exception {
        assertWireLimit("text-byte-exact", List.of(frame(0x81, repeated(128))), true, "text");
        assertWireLimit("text-byte-plus-one", List.of(frame(0x81, repeated(129))), false, "text");
        assertWireLimit("binary-byte-exact", List.of(frame(0x82, repeated(128))), true, "base64");
        assertWireLimit("binary-byte-plus-one", List.of(frame(0x82, repeated(129))), false, "base64");
        assertWireLimit("text-fragment-exact", fragments(0x1, 4), true, "text");
        assertWireLimit("text-fragment-plus-one", fragments(0x1, 5), false, "text");
        assertWireLimit("binary-fragment-exact", fragments(0x2, 4), true, "base64");
        assertWireLimit("binary-fragment-plus-one", fragments(0x2, 5), false, "base64");
    }

    @Test
    void realManagedReconnectUsesExplicitBackoffAndStopFencesScheduledGeneration() throws Exception {
        try (var server = DeterministicTlsWebSocketServer.scripted(List.of(
                List.of(frame(0x81, "first".getBytes(StandardCharsets.UTF_8))),
                List.of(frame(0x81, "second".getBytes(StandardCharsets.UTF_8)))))) {
            WebSocketProfile profile = profile(server.port(), 1, 4);
            var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
            var ingress = new WebSocketTestSupport.FakeIngress();
            SecurityContext identity = sourceIdentity("tenant-a");
            var context = new WebSocketTestSupport.FakeContext(identity, ingress);
            var scope = sourceServices(server, new AtomicInteger(), profile, context, identity);
            var source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                    profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()), scope.services(),
                    new WebSocketAdmissionRegistry(), scheduler);

            try {
                source.start(context).toCompletableFuture().join();
                server.releaseScripts();
                await(() -> ingress.calls.get() == 1 && scheduler.pending() == 1);
                scheduler.triggerAll();
                await(() -> ingress.calls.get() == 2);
                server.awaitConnections();
                assertEquals(List.of("first", "second"), ingress.payloads.stream().map(raw -> {
                    @SuppressWarnings("unchecked") Map<String, Object> event = (Map<String, Object>) raw;
                    return event.get("data");
                }).toList());
                await(() -> scheduler.pending() == 1);
            } finally {
                scope.close();
                source.stop().toCompletableFuture().join();
            }
            scheduler.triggerAll();
            assertEquals(2, server.handshakes.size(), "stopped generations cannot reconnect");
        }
    }

    @Test
    void realManagedBackpressureRevokesBeforeCloseAndCannotReconnect() throws Exception {
        try (var server = DeterministicTlsWebSocketServer.scripted(List.of(List.of(
                frame(0x81, "one".getBytes(StandardCharsets.UTF_8)),
                frame(0x81, "two".getBytes(StandardCharsets.UTF_8)),
                frame(0x81, "three".getBytes(StandardCharsets.UTF_8)))))) {
            WebSocketProfile profile = profile(server.port(), 1, 2);
            var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
            var ingress = new WebSocketTestSupport.FakeIngress();
            ingress.entered = new java.util.concurrent.CountDownLatch(1);
            ingress.release = new java.util.concurrent.CountDownLatch(1);
            SecurityContext identity = sourceIdentity("tenant-a");
            var context = new WebSocketTestSupport.FakeContext(identity, ingress);
            var scope = sourceServices(server, new AtomicInteger(), profile, context, identity);
            var source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                    profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()), scope.services(),
                    new WebSocketAdmissionRegistry(), scheduler);

            try {
                source.start(context).toCompletableFuture().join();
                server.releaseScripts();
                assertTrue(ingress.entered.await(2, TimeUnit.SECONDS));
                await(() -> context.degraded.contains("websocket-ingress-backpressure"));
                ingress.release.countDown();
                server.awaitConnections();
                scheduler.triggerAll();
                assertEquals(1, server.handshakes.size());
                assertFalse((context.degraded + " " + ingress.payloads).contains("token-1"));
            } finally {
                ingress.release.countDown();
                scope.close();
                source.stop().toCompletableFuture().join();
            }
        }
    }

    @Test
    void realManagedReceiveReassemblesTextAndBinaryAndAnswersMaximumPong() throws Exception {
        try (var server = new DeterministicTlsWebSocketServer(
                DeterministicTlsWebSocketServer.Script.RECEIVE_FRAGMENTS_AND_PING, 1)) {
            WebSocketProfile profile = profile(server.port(), 1, 4);
            WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
            SecurityContext identity = sourceIdentity("tenant-a");
            var ingress = new WebSocketTestSupport.FakeIngress();
            var context = new WebSocketTestSupport.FakeContext(identity, ingress);
            var scope = sourceServices(server, new AtomicInteger(), profile, context, identity);
            WebSocketReceiveSource source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                    profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()),
                    scope.services(), admission);

            try {
                source.start(context).toCompletableFuture().join();
                await(() -> ingress.calls.get() == 2);
                assertTrue(server.pong.await(2, TimeUnit.SECONDS));
                server.awaitConnections();

                assertEquals(List.of("hello", "AQID"), ingress.payloads.stream().map(raw -> {
                    @SuppressWarnings("unchecked") Map<String, Object> event = (Map<String, Object>) raw;
                    return (String) event.get("data");
                }).toList());
                @SuppressWarnings("unchecked") Map<String, Object> textEvent =
                        (Map<String, Object>) ingress.payloads.get(0);
                @SuppressWarnings("unchecked") Map<String, Object> binaryEvent =
                        (Map<String, Object>) ingress.payloads.get(1);
                assertEquals("text", textEvent.get("encoding"));
                assertEquals("base64", binaryEvent.get("encoding"));
                byte[] expectedPong = new byte[125];
                for (int index = 0; index < expectedPong.length; index++) expectedPong[index] = (byte) index;
                assertArrayEquals(expectedPong, server.pongPayload);
            } finally {
                scope.close();
                source.stop().toCompletableFuture().join();
            }
            assertEquals(0, admission.size());
        }
    }

    @Test
    void unregisteredLookalikeSourceContextIsRefusedBeforeCredentialOrTlsWork() throws Exception {
        try (var server = new DeterministicTlsWebSocketServer(
                DeterministicTlsWebSocketServer.Script.CAPTURE_SEND, 1)) {
            AtomicInteger credentialQueries = new AtomicInteger();
            WebSocketProfile profile = profile(server.port(), 1, 2);
            var ingress = new WebSocketTestSupport.FakeIngress();
            SecurityContext identity = sourceIdentity("tenant-a");
            var registered = new WebSocketTestSupport.FakeContext(identity, ingress);
            var scope = sourceServices(server, credentialQueries, profile, registered, identity);
            InboundSourceContext lookalike = lookalike(registered);
            WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
            var source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                    profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()),
                    scope.services(), admission);
            try {
                var failure = assertThrows(java.util.concurrent.CompletionException.class,
                        () -> source.start(lookalike).toCompletableFuture().join());
                WebSocketException refusal = assertInstanceOf(WebSocketException.class, failure.getCause());
                assertEquals(WebSocketException.Code.CAPACITY_UNAVAILABLE, refusal.code());
                assertEquals(0, credentialQueries.get());
                assertEquals(0, server.handshakes.size());
            } finally {
                scope.close();
                source.stop().toCompletableFuture().join();
            }
            await(() -> admission.size() == 0);
        }
    }

    @Test
    void retiringTheRegisteredSourceScopeClosesALiveSessionBeforeSourceCleanup() throws Exception {
        try (var server = new DeterministicTlsWebSocketServer(
                DeterministicTlsWebSocketServer.Script.HOLD_UNTIL_CLIENT_CLOSE, 1)) {
            AtomicInteger credentialQueries = new AtomicInteger();
            WebSocketProfile profile = profile(server.port(), 1, 2);
            var ingress = new WebSocketTestSupport.FakeIngress();
            SecurityContext identity = sourceIdentity("tenant-a");
            var context = new WebSocketTestSupport.FakeContext(identity, ingress);
            var scope = sourceServices(server, credentialQueries, profile, context, identity);
            var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
            WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
            var source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                    profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()),
                    scope.services(), admission, scheduler);
            try {
                source.start(context).toCompletableFuture().join();
                assertEquals(1, credentialQueries.get());
                scope.close();
                server.awaitConnections();
            } finally {
                scope.close();
                source.stop().toCompletableFuture().join();
            }
            scheduler.triggerAll();
            assertEquals(1, server.handshakes.size(), "retired source scope cannot reconnect");
            assertEquals(0, admission.size(), "source cleanup releases the receive admission lease");
        }
    }

    private static NodePackageServices services(DeterministicTlsWebSocketServer server,
                                                AtomicInteger generations) throws Exception {
        return WebSocketManagedServicesHarness.services(policy(server), credentials(generations),
                clients(server));
    }

    private static WebSocketManagedServicesHarness.SourceServices sourceServices(
            DeterministicTlsWebSocketServer server, AtomicInteger generations, WebSocketProfile profile,
            InboundSourceContext context, SecurityContext identity) throws Exception {
        return WebSocketManagedServicesHarness.sourceServices(
                new WebSocketNodePackage(WebSocketTestSupport.resolver(profile)), policy(server),
                credentials(generations), clients(server), context, DeploymentId.of("deployment"),
                "socket", identity);
    }

    private static NodePackageEgressPolicy policy(DeterministicTlsWebSocketServer server) {
        NodePackageEgressPolicy.Origin origin = new NodePackageEgressPolicy.Origin(
                "wss", "localhost", server.port());
        return NodePackageEgressPolicy.builder()
                .allowOrigin("wss", "localhost", server.port())
                .allowRequestHeader("X-Client")
                .allowWebSocketSubprotocol("events.v1")
                .bindCredential("handshake", origin, "Authorization", "Bearer ")
                .byteLimits(1024, 1024, 128)
                .concurrencyLimits(4, 4)
                .webSocketLimits(4, 2, Duration.ofSeconds(10), Duration.ofSeconds(5))
                .maximumDeadline(Duration.ofSeconds(5))
                .build();
    }

    private static ai.ravenroot.core.security.nodepackage.TenantCredentialResolver credentials(
            AtomicInteger generations) {
        return (packageId, tenant, reference) -> Optional.of(new SecretValue(
                ("token-" + generations.incrementAndGet()).toCharArray()));
    }

    private static java.util.function.Supplier<HttpClient> clients(DeterministicTlsWebSocketServer server) {
        return () -> HttpClient.newBuilder().sslContext(unchecked(server)).connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static void assertWireLimit(String name,
                                        List<DeterministicTlsWebSocketServer.ServerFrame> frames,
                                        boolean accepted, String expectedEncoding) throws Exception {
        try (var server = DeterministicTlsWebSocketServer.scripted(List.of(frames))) {
            WebSocketProfile profile = profile(server.port(), 1, 4);
            var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
            var ingress = new WebSocketTestSupport.FakeIngress();
            SecurityContext identity = sourceIdentity("tenant-a");
            var context = new WebSocketTestSupport.FakeContext(identity, ingress);
            var scope = sourceServices(server, new AtomicInteger(), profile, context, identity);
            WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
            var source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                    profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()), scope.services(),
                    admission, scheduler);

            try {
                source.start(context).toCompletableFuture().join();
                server.releaseScripts();
                server.awaitConnections();
                if (accepted) {
                    await(() -> ingress.calls.get() == 1);
                    @SuppressWarnings("unchecked") Map<String, Object> event =
                            (Map<String, Object>) ingress.payloads.getFirst();
                    assertEquals(expectedEncoding, event.get("encoding"), name);
                } else {
                    await(() -> !context.degraded.isEmpty());
                    assertEquals(0, ingress.calls.get(), name);
                }
            } finally {
                scope.close();
                source.stop().toCompletableFuture().join();
            }
            scheduler.triggerAll();
            assertEquals(1, server.handshakes.size(), name + " must not reconnect after stop");
            assertEquals(0, admission.size(), name + " must release source admission");
        }
    }

    private static SecurityContext sourceIdentity(String tenant) {
        return new SecurityContext("source", tenant, "operator", PrincipalType.WORKLOAD, "test");
    }

    private static InboundSourceContext lookalike(InboundSourceContext registered) {
        return new InboundSourceContext() {
            @Override public DeploymentId deploymentId() { return registered.deploymentId(); }
            @Override public String nodeId() { return registered.nodeId(); }
            @Override public SecurityContext identity() { return registered.identity(); }
            @Override public ai.ravenroot.api.deployment.TrustedIngress ingress() {
                return registered.ingress();
            }
            @Override public ai.ravenroot.api.deployment.RequestReplyIngress requestReply() {
                return registered.requestReply();
            }
            @Override public java.util.Optional<ai.ravenroot.api.ingress.IngressRouteAuthority> ingressRoutes() {
                return registered.ingressRoutes();
            }
            @Override public void reportDegraded(String sanitizedReason) { }
            @Override public void reportHealthy() { }
        };
    }

    private static DeterministicTlsWebSocketServer.ServerFrame frame(int firstByte, byte[] payload) {
        return new DeterministicTlsWebSocketServer.ServerFrame(firstByte, payload);
    }

    private static List<DeterministicTlsWebSocketServer.ServerFrame> fragments(int opcode, int count) {
        java.util.ArrayList<DeterministicTlsWebSocketServer.ServerFrame> frames = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            int firstByte = index == 0 ? opcode : 0;
            if (index == count - 1) firstByte |= 0x80;
            frames.add(frame(firstByte, new byte[]{(byte) ('a' + index)}));
        }
        return List.copyOf(frames);
    }

    private static byte[] repeated(int size) {
        byte[] value = new byte[size];
        java.util.Arrays.fill(value, (byte) 'a');
        return value;
    }

    private static javax.net.ssl.SSLContext unchecked(DeterministicTlsWebSocketServer server) {
        try { return server.trustedClientContext(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static WebSocketProfile profile(int port, int concurrency, int buffered) {
        return new WebSocketProfile("events", URI.create("wss://localhost:" + port + "/events"),
                Map.of("X-Client", List.of("ravenroot")), List.of("events.v1"), "handshake", "socket-secret",
                128, 4, 5_000, 300_000, concurrency, buffered);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }
}
