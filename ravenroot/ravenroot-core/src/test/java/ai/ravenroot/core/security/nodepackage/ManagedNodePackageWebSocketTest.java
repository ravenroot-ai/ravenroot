package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundWebSocketListener;
import ai.ravenroot.api.node.service.OutboundWebSocketRequest;
import ai.ravenroot.api.node.service.OutboundWebSocketSession;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedNodePackageWebSocketTest {
    private RawWebSocketServer server;

    @AfterEach void closeServer() throws Exception { if (server != null) server.close(); }

    @Test
    void managedSessionUsesOperatorHandshakeBindingAndBoundsOutboundMessages() throws Exception {
        server = RawWebSocketServer.responding();
        int port = server.port();
        var origin = new NodePackageEgressPolicy.Origin("ws", "localhost", port);
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .allowWebSocketSubprotocol("chat")
                .bindCredential("handshake", origin, "Authorization", "Bearer ")
                .byteLimits(16, 16, 4).webSocketLimits(4, 1, Duration.ofSeconds(5), Duration.ofSeconds(2))
                .build();
        var services = services(policy);

        OutboundWebSocketSession session = await(services.outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/events"), Map.of(),
                        List.of("chat"), Duration.ofSeconds(2),
                        new OutboundCredentialBinding("handshake", "socket")),
                new OutboundWebSocketListener() { }));

        assertTrue(server.handshake.await(1, TimeUnit.SECONDS));
        assertEquals("Bearer tenant-a:socket", server.headers.get().get("authorization"));
        assertEquals("chat", server.headers.get().get("sec-websocket-protocol"));
        assertFailure(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                session.sendText("12345"));
        session.close(1000, "done").toCompletableFuture().join();
    }

    @Test
    void plaintextDowngradeAndSensitiveCallerHeadersAreRefusedBeforeHandshake() throws Exception {
        server = RawWebSocketServer.responding();
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("wss", "localhost", port)
                .allowRequestHeader("Authorization").build();
        var services = services(policy);
        URI plaintext = URI.create("ws://localhost:" + port + "/");

        assertReason(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN,
                services.outboundWebSocket().open(message(), new OutboundWebSocketRequest(plaintext,
                        Map.of(), List.of(), Duration.ofSeconds(1), null), null));
        assertEquals(0, server.accepted.get());

        var wsPolicy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .allowRequestHeader("Authorization").build();
        var wsServices = services(wsPolicy);
        assertReason(NodePackageServiceException.Reason.PROTOCOL_REFUSED,
                wsServices.outboundWebSocket().open(message(), new OutboundWebSocketRequest(plaintext,
                        Map.of("Authorization", List.of("graph-value")), List.of(),
                        Duration.ofSeconds(1), null), null));
        assertEquals(0, server.accepted.get());
    }

    @Test
    void cancellingAStalledHandshakeIsTerminalAndDoesNotYieldASession() throws Exception {
        server = RawWebSocketServer.stalled();
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .maximumDeadline(Duration.ofSeconds(5)).build();
        var services = services(policy);
        OutboundCall<OutboundWebSocketSession> opening = services.outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/"), Map.of(),
                        List.of(), Duration.ofSeconds(5), null), null);
        assertTrue(server.acceptedLatch.await(1, TimeUnit.SECONDS));

        assertTrue(opening.cancel());
        assertReason(NodePackageServiceException.Reason.CANCELLED, opening);
    }

    @Test
    void tlsHandshakeAgainstPlaintextPeerFailsWithSanitizedTlsReason() throws Exception {
        server = RawWebSocketServer.closeImmediately();
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("wss", "localhost", port)
                .maximumDeadline(Duration.ofSeconds(2)).build();
        var services = services(policy);

        assertReason(NodePackageServiceException.Reason.TLS_REFUSED,
                services.outboundWebSocket().open(message(), new OutboundWebSocketRequest(
                        URI.create("wss://localhost:" + port + "/"), Map.of(), List.of(),
                        Duration.ofSeconds(2), null), null));
    }

    @Test
    void inboundFragmentAmplificationProducesOneBoundedTerminalFailure() throws Exception {
        server = RawWebSocketServer.fragmenting();
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .webSocketLimits(2, 1, Duration.ofSeconds(5), Duration.ofSeconds(2)).build();
        var services = services(policy);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        OutboundWebSocketSession session = await(services.outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/"), Map.of(),
                        List.of(), Duration.ofSeconds(2), null), new OutboundWebSocketListener() {
                    @Override public void onText(String text) { messages.incrementAndGet(); }
                    @Override public void onFailure(NodePackageServiceException failure) {
                        failures.incrementAndGet(); failed.countDown();
                    }
                }));

        server.sendFrames.countDown();
        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertEquals(1, failures.get());
        assertEquals(0, messages.get(), "an over-fragmented message must never be delivered partially");
        session.cancel();
    }

    @RepeatedTest(10)
    void automaticPongCopiesTheMaximumControlPayloadBeforeTheNextDelivery() throws Exception {
        byte[] payload = new byte[125];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) index;
        server = RawWebSocketServer.pinging(payload);
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .webSocketLimits(4, 1, Duration.ofSeconds(5), Duration.ofSeconds(2)).build();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean pongWasObservedBeforeDelivery = new AtomicBoolean();

        OutboundWebSocketSession session = await(services(policy).outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/"), Map.of(),
                        List.of(), Duration.ofSeconds(2), null), new OutboundWebSocketListener() {
                    @Override public void onText(String text) {
                        pongWasObservedBeforeDelivery.set(server.pong.getCount() == 0);
                        delivered.countDown();
                    }
                }));

        assertTrue(server.pong.await(1, TimeUnit.SECONDS), "server must receive the managed Pong");
        assertArrayEquals(payload, server.pongPayload.get(), "Pong control payload must be byte-identical");
        assertTrue(delivered.await(1, TimeUnit.SECONDS), "next message must be demanded after Pong completion");
        assertTrue(pongWasObservedBeforeDelivery.get());
        session.cancel();
    }

    @Test
    void invalidControlFramesAreRefusedWithoutDeliveringPackageData() throws Exception {
        assertInvalidControlFrame(RawWebSocketServer.oversizedPing());
        assertInvalidControlFrame(RawWebSocketServer.fragmentedPing());
    }

    @Test
    void pendingPongTerminalFirstFencesCloseCancelAndIdleExpiry() throws Exception {
        assertPendingPongTerminalFirst("close", harness -> harness.bridge.onClose(harness.socket, 1000, "done"),
                0, 1, 0);
        assertPendingPongTerminalFirst("cancel", harness -> {
            assertTrue(harness.session.cancel());
            return CompletableFuture.completedFuture(null);
        }, 1, 0, 1);
        assertPendingPongTerminalFirst("idle expiry", harness -> {
            harness.session.forceAbort();
            return CompletableFuture.completedFuture(null);
        }, 1, 0, 1);
    }

    @Test
    void pendingPongCompletionFirstAllowsOneDemandBeforeCloseCancelAndIdleExpiry() throws Exception {
        assertPendingPongCompletionFirst("close", harness -> harness.bridge.onClose(harness.socket, 1000, "done"),
                0, 1, 0);
        assertPendingPongCompletionFirst("cancel", harness -> {
            assertTrue(harness.session.cancel());
            return CompletableFuture.completedFuture(null);
        }, 1, 0, 1);
        assertPendingPongCompletionFirst("idle expiry", harness -> {
            harness.session.forceAbort();
            return CompletableFuture.completedFuture(null);
        }, 1, 0, 1);
    }

    @Test
    void synchronousAndAsynchronousPongSendFailuresSettleOnceWithoutDemand() throws Exception {
        SessionHarness synchronous = sessionHarness();
        synchronous.socket.pongThrow = new IllegalStateException("synchronous send failure");
        assertFailure(NodePackageServiceException.Reason.TRANSPORT_FAILED,
                synchronous.bridge.onPing(synchronous.socket, ByteBuffer.wrap(new byte[]{1})));
        synchronous.settlement.awaitTerminal();
        synchronous.settlement.assertCounts(0, 1, 1);
        assertEquals(1, synchronous.socket.aborts.get());
        assertEquals(0, synchronous.socket.requests.get());

        SessionHarness asynchronous = sessionHarness();
        asynchronous.socket.pongResult = new CompletableFuture<>();
        var pending = asynchronous.bridge.onPing(asynchronous.socket, ByteBuffer.wrap(new byte[]{1}));
        asynchronous.socket.pongResult.completeExceptionally(new IOException("asynchronous send failure"));
        assertFailure(NodePackageServiceException.Reason.TRANSPORT_FAILED, pending);
        asynchronous.settlement.awaitTerminal();
        asynchronous.settlement.assertCounts(0, 1, 1);
        assertEquals(1, asynchronous.socket.aborts.get());
        assertEquals(0, asynchronous.socket.requests.get());
    }

    // --- Per-request WebSocket ceilings --------------------------------------------------------

    @Test
    void perRequestMessageByteCeilingBoundsInboundTextAndBinaryAssembly() throws Exception {
        assertInboundMessageCeiling("text at the ceiling", 0x1, 8L, 8, true, 64);
        assertInboundMessageCeiling("text one byte over", 0x1, 8L, 9, false, 64);
        assertInboundMessageCeiling("binary at the ceiling", 0x2, 8L, 8, true, 64);
        assertInboundMessageCeiling("binary one byte over", 0x2, 8L, 9, false, 64);
    }

    /**
     * A quarter-megabyte message against a one-kilobyte ceiling. The fragment ceiling is raised out
     * of the way so only the byte ceiling can produce the refusal: the JDK delivers this message in
     * many buffers, and each buffer is itself charged as a fragment.
     */
    @Test
    void inboundAssemblyStopsAtTheCeilingRatherThanBufferingTheWholeMessage() throws Exception {
        assertInboundMessageCeiling("binary far over the ceiling", 0x2, 1024L, 256 * 1024, false, 4096);
    }

    @Test
    void perRequestMessageByteCeilingBoundsOutboundTextAndBinarySends() throws Exception {
        assertEffectiveOutboundCeiling("request ceiling narrower than policy", 1024L, 8L, 8);
        assertEffectiveOutboundCeiling("request ceiling wider than policy cannot widen it", 8L, 1024L, 8);
        assertEffectiveOutboundCeiling("absent request ceiling is the policy ceiling", 8L, null, 8);
    }

    @Test
    void perRequestFragmentCeilingBoundsInboundTextAndBinaryAssembly() throws Exception {
        assertInboundFragmentCeiling("text at the fragment ceiling", 0x1, 3, 3, true);
        assertInboundFragmentCeiling("text one fragment over", 0x1, 3, 4, false);
        assertInboundFragmentCeiling("binary at the fragment ceiling", 0x2, 3, 3, true);
        assertInboundFragmentCeiling("binary one fragment over", 0x2, 3, 4, false);
    }

    @Test
    void aWiderRequestFragmentCeilingCannotWidenTheOperatorCeiling() throws Exception {
        // Operator grants 2 fragments, the package asks for 64: the third fragment must still fail.
        assertInboundFragmentCeiling("wider request fragment ceiling", 0x1, 64, 3, false, 2);
    }

    @Test
    void effectiveCeilingsAreTheNarrowerOfPolicyAndRequestAndFailClosedOnInvalidValues() {
        var policy = NodePackageEgressPolicy.builder()
                .byteLimits(1024, 1024, 16).webSocketLimits(8, 1, Duration.ofSeconds(5), Duration.ofSeconds(2))
                .build();

        assertEquals(new ManagedNodePackageServices.WebSocketLimits(16, 8),
                ManagedNodePackageServices.WebSocketLimits.of(policy, OptionalLong.empty(), OptionalInt.empty()),
                "an absent request ceiling must leave the operator ceiling untouched");
        assertEquals(new ManagedNodePackageServices.WebSocketLimits(4, 2),
                ManagedNodePackageServices.WebSocketLimits.of(policy, OptionalLong.of(4), OptionalInt.of(2)),
                "a narrower request ceiling must win");
        assertEquals(new ManagedNodePackageServices.WebSocketLimits(16, 8),
                ManagedNodePackageServices.WebSocketLimits.of(policy, OptionalLong.of(1 << 20), OptionalInt.of(4096)),
                "a wider request ceiling must never widen operator authority");
        assertEquals(ManagedNodePackageServices.WebSocketLimits.of(policy),
                ManagedNodePackageServices.WebSocketLimits.of(policy, OptionalLong.empty(), OptionalInt.empty()),
                "the policy-only derivation and the absent-request derivation must agree");

        for (long invalid : new long[]{0L, -1L, (long) Integer.MAX_VALUE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> ManagedNodePackageServices.WebSocketLimits
                    .of(policy, OptionalLong.of(invalid), OptionalInt.empty()), "bytes " + invalid);
        }
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> ManagedNodePackageServices.WebSocketLimits
                    .of(policy, OptionalLong.empty(), OptionalInt.of(invalid)), "fragments " + invalid);
        }
    }

    @Test
    void invalidPerRequestCeilingsFailClosedBeforeAnyHandshake() throws Exception {
        server = RawWebSocketServer.responding();
        URI destination = URI.create("ws://localhost:" + server.port() + "/");
        for (long invalid : new long[]{0L, -1L, (long) Integer.MAX_VALUE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new OutboundWebSocketRequest(destination,
                    Map.of(), List.of(), Duration.ofSeconds(1), null, invalid, null), "bytes " + invalid);
        }
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new OutboundWebSocketRequest(destination,
                    Map.of(), List.of(), Duration.ofSeconds(1), null, null, invalid), "fragments " + invalid);
        }
        assertEquals(0, server.accepted.get(), "a refused ceiling must never reach transport");
    }

    /**
     * The fragment ceiling terminating a session, raced against close, cancel, idle
     * expiry and an in-flight automatic Pong. Exactly one terminal callback, exactly one admission
     * release, and no frame demand after the terminal transition.
     */
    @RepeatedTest(10)
    void fragmentCeilingTerminationRacesCloseCancelIdleExpiryAndPong() throws Exception {
        assertFragmentOverflowRace("close", harness -> harness.bridge.onClose(harness.socket, 1000, "done"));
        assertFragmentOverflowRace("cancel", harness -> {
            harness.session.cancel();
            return CompletableFuture.completedFuture(null);
        });
        assertFragmentOverflowRace("idle expiry", harness -> {
            harness.session.forceAbort();
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * The deterministic race above proves the release happens; only the real admission controller
     * can prove it happens <em>once</em>. A double release would over-credit the semaphore and let a
     * second concurrent session in past a package maximum of one.
     */
    @Test
    void fragmentCeilingTerminationReleasesAdmissionExactlyOnce() throws Exception {
        RawWebSocketServer overflowing = RawWebSocketServer.scripted(List.of(
                frame(0x1, false, "a".getBytes(StandardCharsets.UTF_8)),
                frame(0x0, true, "b".getBytes(StandardCharsets.UTF_8))));
        RawWebSocketServer reusable = RawWebSocketServer.responding();
        try {
            var policy = NodePackageEgressPolicy.builder()
                    .allowOrigin("ws", "localhost", overflowing.port())
                    .allowOrigin("ws", "localhost", reusable.port())
                    .concurrencyLimits(1, 1)
                    .webSocketLimits(64, 4, Duration.ofSeconds(5), Duration.ofSeconds(2))
                    .build();
            var services = services(policy);
            CountDownLatch failed = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();
            AtomicInteger messages = new AtomicInteger();
            OutboundWebSocketSession first = await(services.outboundWebSocket().open(message(),
                    new OutboundWebSocketRequest(URI.create("ws://localhost:" + overflowing.port() + "/"),
                            Map.of(), List.of(), Duration.ofSeconds(2), null, null, 1),
                    new OutboundWebSocketListener() {
                        @Override public void onText(String text) { messages.incrementAndGet(); }
                        @Override public void onFailure(NodePackageServiceException failure) {
                            failures.incrementAndGet(); failed.countDown();
                        }
                    }));

            overflowing.sendFrames.countDown();
            assertTrue(failed.await(2, TimeUnit.SECONDS), "the fragment ceiling must terminate the session");
            assertEquals(1, failures.get(), "termination must be reported once");
            assertEquals(0, messages.get(), "an over-fragmented message must never be delivered");

            OutboundWebSocketSession second = await(services.outboundWebSocket().open(message(),
                    new OutboundWebSocketRequest(URI.create("ws://localhost:" + reusable.port() + "/"),
                            Map.of(), List.of(), Duration.ofSeconds(2), null, null, null),
                    new OutboundWebSocketListener() { }));
            assertNotNull(second, "the terminated session must have returned its admission lease");
            assertReason(NodePackageServiceException.Reason.ADMISSION_REFUSED,
                    services.outboundWebSocket().open(message(),
                            new OutboundWebSocketRequest(URI.create("ws://localhost:" + reusable.port() + "/"),
                                    Map.of(), List.of(), Duration.ofSeconds(2), null, null, null), null));
            assertEquals(1, reusable.accepted.get(), "a refused admission must never reach transport");
            second.close(1000, "done").toCompletableFuture().join();
            first.cancel();
        } finally {
            overflowing.close();
            reusable.close();
        }
    }

    private void assertFragmentOverflowRace(String name, TerminalAction action) throws Exception {
        SessionHarness harness = sessionHarness(new ManagedNodePackageServices.WebSocketLimits(1024, 1));
        harness.socket.pongResult = new CompletableFuture<>();
        var pong = harness.bridge.onPing(harness.socket, ByteBuffer.wrap(new byte[]{1}));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        Thread.ofVirtual().name("fragment-overflow").start(() -> {
            try {
                start.await();
                harness.bridge.onText(harness.socket, "a", false);
                harness.bridge.onText(harness.socket, "b", false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        Thread.ofVirtual().name("terminal-action").start(() -> {
            try {
                start.await();
                action.terminate(harness).toCompletableFuture().join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        start.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS), name + " race did not finish");
        harness.socket.pongResult.complete(harness.socket);
        pong.toCompletableFuture().join();
        harness.settlement.awaitTerminal();
        harness.settlement.assertSingleSettlement(name);

        int demand = harness.socket.requests.get();
        harness.bridge.onPing(harness.socket, ByteBuffer.wrap(new byte[]{2})).toCompletableFuture().join();
        harness.bridge.onPong(harness.socket, ByteBuffer.wrap(new byte[0])).toCompletableFuture().join();
        harness.bridge.onText(harness.socket, "c", true);
        assertEquals(demand, harness.socket.requests.get(), name + " must not demand frames after terminal");
    }

    private void assertInboundMessageCeiling(String name, int opcode, long requestCeiling, int payloadBytes,
                                             boolean expectDelivery, int policyFragments) throws Exception {
        byte[] payload = new byte[payloadBytes];
        Arrays.fill(payload, (byte) 'a');
        assertInboundCeiling(name, List.of(frame(opcode, true, payload)), requestCeiling, null,
                policyFragments, expectDelivery, payloadBytes);
    }

    private void assertInboundFragmentCeiling(String name, int opcode, int requestCeiling,
                                              int fragments, boolean expectDelivery) throws Exception {
        assertInboundFragmentCeiling(name, opcode, requestCeiling, fragments, expectDelivery, 64);
    }

    private void assertInboundFragmentCeiling(String name, int opcode, int requestCeiling, int fragments,
                                              boolean expectDelivery, int policyFragments) throws Exception {
        List<byte[]> frames = new java.util.ArrayList<>();
        for (int index = 0; index < fragments; index++) {
            frames.add(frame(index == 0 ? opcode : 0x0, index == fragments - 1,
                    new byte[]{(byte) ('a' + index)}));
        }
        assertInboundCeiling(name, List.copyOf(frames), null, requestCeiling, policyFragments,
                expectDelivery, fragments);
    }

    private void assertInboundCeiling(String name, List<byte[]> frames, Long requestBytes,
                                      Integer requestFragments, int policyFragments,
                                      boolean expectDelivery, int expectedBytes) throws Exception {
        server = RawWebSocketServer.scripted(frames);
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .byteLimits(1024, 1024, 1024)
                .webSocketLimits(policyFragments, 4, Duration.ofSeconds(5), Duration.ofSeconds(2))
                .build();
        CountDownLatch settled = new CountDownLatch(1);
        AtomicInteger messages = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger deliveredBytes = new AtomicInteger(-1);
        AtomicReference<NodePackageServiceException> failure = new AtomicReference<>();
        OutboundWebSocketSession session = await(services(policy).outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/"), Map.of(),
                        List.of(), Duration.ofSeconds(2), null, requestBytes, requestFragments),
                new OutboundWebSocketListener() {
                    @Override public void onText(String text) {
                        messages.incrementAndGet();
                        deliveredBytes.set(text.getBytes(StandardCharsets.UTF_8).length);
                        settled.countDown();
                    }
                    @Override public void onBinary(byte[] bytes) {
                        messages.incrementAndGet();
                        deliveredBytes.set(bytes.length);
                        settled.countDown();
                    }
                    @Override public void onFailure(NodePackageServiceException refused) {
                        failures.incrementAndGet(); failure.set(refused); settled.countDown();
                    }
                }));

        server.sendFrames.countDown();
        assertTrue(settled.await(5, TimeUnit.SECONDS), name + " never settled");
        if (expectDelivery) {
            assertEquals(1, messages.get(), name + " must deliver exactly at the ceiling");
            assertEquals(expectedBytes, deliveredBytes.get(), name + " delivered length");
            assertEquals(0, failures.get(), name + " must not fail at the ceiling");
        } else {
            assertEquals(0, messages.get(), name + " must never deliver an over-ceiling message");
            assertEquals(1, failures.get(), name + " must fail exactly once");
            assertEquals(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE, failure.get().reason(), name);
            assertEquals(new NodePackageServiceException(
                            NodePackageServiceException.Reason.RESPONSE_TOO_LARGE).getMessage(),
                    failure.get().getMessage(),
                    name + " must stay sanitized: the reason vocabulary and nothing else");
            assertFalse(failure.get().getMessage().contains("localhost")
                            || failure.get().getMessage().contains(String.valueOf(port)),
                    name + " must not leak host or port");
        }
        session.cancel();
        server.close();
        server = null;
    }

    private void assertEffectiveOutboundCeiling(String name, long policyBytes, Long requestBytes,
                                                int effective) throws Exception {
        server = RawWebSocketServer.responding();
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .byteLimits(1024, 1024, policyBytes)
                .webSocketLimits(64, 4, Duration.ofSeconds(5), Duration.ofSeconds(2)).build();
        OutboundWebSocketSession session = await(services(policy).outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/"), Map.of(),
                        List.of(), Duration.ofSeconds(2), null, requestBytes, null),
                new OutboundWebSocketListener() { }));

        session.sendText("a".repeat(effective)).toCompletableFuture().join();
        assertFailure(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                session.sendText("a".repeat(effective + 1)));
        session.sendBinary(new byte[effective]).toCompletableFuture().join();
        assertFailure(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                session.sendBinary(new byte[effective + 1]));

        session.close(1000, "done").toCompletableFuture().join();
        server.close();
        server = null;
    }

    /** One unmasked server-to-client frame with RFC 6455 extended length encoding. */
    private static byte[] frame(int opcode, boolean fin, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0x00) | opcode);
        int length = payload.length;
        if (length <= 125) {
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(126);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((((long) length) >>> shift) & 0xFF));
            }
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private void assertInvalidControlFrame(RawWebSocketServer value) throws Exception {
        server = value;
        int port = server.port();
        var policy = NodePackageEgressPolicy.builder().allowOrigin("ws", "localhost", port)
                .concurrencyLimits(1, 1).build();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        OutboundCall<OutboundWebSocketSession> opening = services(policy).outboundWebSocket().open(message(),
                new OutboundWebSocketRequest(URI.create("ws://localhost:" + port + "/"), Map.of(),
                        List.of(), Duration.ofSeconds(2), null), new OutboundWebSocketListener() {
                    @Override public void onText(String text) { messages.incrementAndGet(); }
                    @Override public void onFailure(NodePackageServiceException failure) {
                        failures.incrementAndGet(); failed.countDown();
                    }
                });
        assertReason(NodePackageServiceException.Reason.TRANSPORT_FAILED, opening);
        assertTrue(failed.await(1, TimeUnit.SECONDS), "invalid control frame must terminate transport");
        assertEquals(1, failures.get(), "transport rejection must be reported once");
        assertEquals(0, messages.get());
        server.close();
        server = null;
    }

    private void assertPendingPongTerminalFirst(String name, TerminalAction action, int aborts,
                                                int closes, int failures) throws Exception {
        SessionHarness harness = sessionHarness();
        harness.socket.pongResult = new CompletableFuture<>();
        var pong = harness.bridge.onPing(harness.socket, ByteBuffer.wrap(new byte[]{1}));
        action.terminate(harness).toCompletableFuture().join();
        harness.settlement.awaitTerminal();
        harness.socket.pongResult.complete(harness.socket);
        pong.toCompletableFuture().join();
        harness.settlement.assertCounts(closes, failures, 1);
        assertEquals(aborts, harness.socket.aborts.get(), name + " abort count");
        assertEquals(0, harness.socket.requests.get(), name + " must fence post-terminal demand");
    }

    private void assertPendingPongCompletionFirst(String name, TerminalAction action, int aborts,
                                                  int closes, int failures) throws Exception {
        SessionHarness harness = sessionHarness();
        harness.socket.pongResult = new CompletableFuture<>();
        var pong = harness.bridge.onPing(harness.socket, ByteBuffer.wrap(new byte[]{1}));
        harness.socket.pongResult.complete(harness.socket);
        pong.toCompletableFuture().join();
        assertEquals(1, harness.socket.requests.get(), name + " permits exactly one pre-terminal demand");
        action.terminate(harness).toCompletableFuture().join();
        harness.settlement.awaitTerminal();
        harness.settlement.assertCounts(closes, failures, 1);
        assertEquals(aborts, harness.socket.aborts.get(), name + " abort count");
        harness.bridge.onPing(harness.socket, ByteBuffer.wrap(new byte[]{2})).toCompletableFuture().join();
        assertEquals(1, harness.socket.requests.get(), name + " must never re-demand after terminal state");
    }

    private static SessionHarness sessionHarness() {
        Settlement settlement = new Settlement();
        Runnable release = settlement::released;
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder().build();
        var bridge = new ManagedNodePackageServices.WebSocketBridge(settlement.listener(), policy, release);
        FakeWebSocket socket = new FakeWebSocket();
        var session = new ManagedNodePackageServices.ManagedWebSocketSession(socket, bridge, policy, release);
        bridge.attach(session);
        return new SessionHarness(bridge, socket, session, settlement);
    }

    /** Same harness, driven by explicit effective ceilings instead of the operator ceilings. */
    private static SessionHarness sessionHarness(ManagedNodePackageServices.WebSocketLimits limits) {
        Settlement settlement = new Settlement();
        Runnable release = settlement::released;
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder().build();
        var bridge = new ManagedNodePackageServices.WebSocketBridge(settlement.listener(), limits, release);
        FakeWebSocket socket = new FakeWebSocket();
        var session = new ManagedNodePackageServices.ManagedWebSocketSession(socket, bridge, policy,
                limits, release);
        bridge.attach(session);
        return new SessionHarness(bridge, socket, session, settlement);
    }

    @FunctionalInterface
    private interface TerminalAction {
        java.util.concurrent.CompletionStage<?> terminate(SessionHarness harness);
    }

    private record SessionHarness(ManagedNodePackageServices.WebSocketBridge bridge, FakeWebSocket socket,
                                  ManagedNodePackageServices.ManagedWebSocketSession session,
                                  Settlement settlement) { }

    private static final class Settlement {
        private final CountDownLatch callback = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();
        private final AtomicBoolean released = new AtomicBoolean();

        OutboundWebSocketListener listener() {
            return new OutboundWebSocketListener() {
                @Override public void onClosed(int statusCode, String reason) {
                    closes.incrementAndGet(); callback.countDown();
                }
                @Override public void onFailure(NodePackageServiceException failure) {
                    failures.incrementAndGet(); callback.countDown();
                }
            };
        }

        void released() {
            if (released.compareAndSet(false, true)) {
                releases.incrementAndGet();
                release.countDown();
            }
        }

        void awaitTerminal() throws InterruptedException {
            assertTrue(release.await(1, TimeUnit.SECONDS), "admission release did not complete");
            assertTrue(callback.await(1, TimeUnit.SECONDS), "terminal callback did not complete");
        }

        void assertSingleSettlement(String name) {
            assertEquals(1, closes.get() + failures.get(),
                    name + " must produce exactly one terminal callback");
            assertEquals(1, releases.get(), name + " must release admission");
        }

        void assertCounts(int expectedCloses, int expectedFailures, int expectedReleases) {
            assertEquals(expectedCloses, closes.get(), "close callback count");
            assertEquals(expectedFailures, failures.get(), "failure callback count");
            assertEquals(expectedReleases, releases.get(), "admission release count");
        }
    }

    private ManagedNodePackageServices services(NodePackageEgressPolicy policy) {
        return ManagedNodePackageServices.builder("test.websocket", policy,
                        (packageId, tenant, reference) -> java.util.Optional.of(
                                new SecretValue((tenant + ":" + reference).toCharArray())))
                .grant(NodePackageCapability.OUTBOUND_WEBSOCKET).build();
    }

    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", "tenant-a", "subject",
                PrincipalType.USER, "issuer"), id, id, id, id, Set.of(), "node", null, Map.of());
    }

    private static <T> T await(OutboundCall<T> call) {
        return call.completion().toCompletableFuture().join();
    }

    private static void assertReason(NodePackageServiceException.Reason reason, OutboundCall<?> call) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> call.completion().toCompletableFuture().join());
        assertEquals(reason, ((NodePackageServiceException) failure.getCause()).reason());
    }

    private static void assertFailure(NodePackageServiceException.Reason reason,
                                      java.util.concurrent.CompletionStage<?> stage) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertEquals(reason, ((NodePackageServiceException) failure.getCause()).reason());
    }

    private static final class FakeWebSocket implements WebSocket {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger pongs = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        CompletableFuture<WebSocket> pongResult = CompletableFuture.completedFuture(this);
        RuntimeException pongThrow;

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            pongs.incrementAndGet();
            if (pongThrow != null) throw pongThrow;
            return pongResult;
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long count) { requests.addAndGet(Math.toIntExact(count)); }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { aborts.incrementAndGet(); }
    }

    private static final class RawWebSocketServer implements AutoCloseable {
        private enum Mode { RESPOND, FRAGMENT, STALL, CLOSE, PING, OVERSIZED_PING, FRAGMENTED_PING, SCRIPTED }
        private final ServerSocket socket;
        private final Mode mode;
        private final List<byte[]> scriptedFrames;
        private final Thread thread;
        private final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger accepted = new AtomicInteger();
        final CountDownLatch acceptedLatch = new CountDownLatch(1);
        final CountDownLatch handshake = new CountDownLatch(1);
        final CountDownLatch sendFrames = new CountDownLatch(1);
        final CountDownLatch pong = new CountDownLatch(1);
        final AtomicReference<Map<String, String>> headers = new AtomicReference<>(Map.of());
        final AtomicReference<byte[]> pongPayload = new AtomicReference<>();
        private final byte[] pingPayload;

        static RawWebSocketServer responding() throws Exception { return new RawWebSocketServer(Mode.RESPOND); }
        static RawWebSocketServer fragmenting() throws Exception { return new RawWebSocketServer(Mode.FRAGMENT); }
        static RawWebSocketServer stalled() throws Exception { return new RawWebSocketServer(Mode.STALL); }
        static RawWebSocketServer closeImmediately() throws Exception { return new RawWebSocketServer(Mode.CLOSE); }
        static RawWebSocketServer pinging(byte[] payload) throws Exception {
            return new RawWebSocketServer(Mode.PING, payload);
        }
        static RawWebSocketServer oversizedPing() throws Exception {
            return new RawWebSocketServer(Mode.OVERSIZED_PING);
        }
        static RawWebSocketServer fragmentedPing() throws Exception {
            return new RawWebSocketServer(Mode.FRAGMENTED_PING);
        }

        /** Writes caller-supplied raw server-to-client frames once {@link #sendFrames} is released. */
        static RawWebSocketServer scripted(List<byte[]> frames) throws Exception {
            return new RawWebSocketServer(Mode.SCRIPTED, null, frames);
        }

        private RawWebSocketServer(Mode mode) throws Exception {
            this(mode, null, List.of());
        }

        private RawWebSocketServer(Mode mode, byte[] pingPayload) throws Exception {
            this(mode, pingPayload, List.of());
        }

        private RawWebSocketServer(Mode mode, byte[] pingPayload, List<byte[]> frames) throws Exception {
            this.mode = mode;
            this.scriptedFrames = List.copyOf(frames);
            this.pingPayload = pingPayload == null ? null : pingPayload.clone();
            socket = new ServerSocket(0, 16, InetAddress.getByName("localhost"));
            thread = Thread.ofVirtual().name("raw-websocket-fixture").start(this::serve);
        }

        int port() { return socket.getLocalPort(); }

        private void serve() {
            if (mode == Mode.CLOSE) {
                while (!closed.get()) {
                    try (Socket peer = socket.accept()) {
                        accepted.incrementAndGet();
                        acceptedLatch.countDown();
                        peer.getOutputStream().write(
                                "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII));
                        peer.getOutputStream().flush();
                    } catch (Exception ignored) {
                        return;
                    }
                }
                return;
            }
            try (Socket peer = socket.accept()) {
                accepted.incrementAndGet();
                acceptedLatch.countDown();
                if (mode == Mode.STALL) {
                    while (!closed.get() && !peer.isClosed()) Thread.sleep(10);
                    return;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        peer.getInputStream(), StandardCharsets.US_ASCII));
                reader.readLine();
                Map<String, String> parsed = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) parsed.put(line.substring(0, colon).strip().toLowerCase(),
                            line.substring(colon + 1).strip());
                }
                headers.set(Map.copyOf(parsed));
                String key = parsed.get("sec-websocket-key");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
                String protocol = parsed.get("sec-websocket-protocol");
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n"
                        + (protocol == null ? "" : "Sec-WebSocket-Protocol: " + protocol + "\r\n") + "\r\n";
                peer.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().flush();
                handshake.countDown();
                if (mode == Mode.PING) {
                    writeFrame(peer.getOutputStream(), 0x89, pingPayload);
                    ClientFrame responseFrame = readClientFrame(peer.getInputStream());
                    if (responseFrame.opcode == 0xA) {
                        pongPayload.set(responseFrame.payload);
                        pong.countDown();
                    }
                    writeFrame(peer.getOutputStream(), 0x81, "next".getBytes(StandardCharsets.UTF_8));
                }
                if (mode == Mode.OVERSIZED_PING) {
                    peer.getOutputStream().write(new byte[]{(byte) 0x89, 126, 0, 126});
                    peer.getOutputStream().write(new byte[126]);
                    peer.getOutputStream().flush();
                }
                if (mode == Mode.FRAGMENTED_PING) {
                    peer.getOutputStream().write(new byte[]{0x09, 0x01, 'x'});
                    peer.getOutputStream().flush();
                }
                if (mode == Mode.SCRIPTED) {
                    sendFrames.await();
                    for (byte[] scripted : scriptedFrames) peer.getOutputStream().write(scripted);
                    peer.getOutputStream().flush();
                }
                if (mode == Mode.FRAGMENT) {
                    sendFrames.await();
                    peer.getOutputStream().write(new byte[]{0x01, 0x01, 'a'});
                    peer.getOutputStream().write(new byte[]{0x00, 0x01, 'b'});
                    peer.getOutputStream().write(new byte[]{(byte) 0x80, 0x01, 'c'});
                    peer.getOutputStream().flush();
                }
                while (!closed.get() && peer.getInputStream().read() != -1) { }
            } catch (Exception ignored) {
                // Fixture shutdown and cancelled handshakes both end here.
            }
        }

        private static void writeFrame(OutputStream output, int opcode, byte[] payload) throws IOException {
            if (payload.length > 125) throw new IllegalArgumentException("fixture control/data frame too large");
            output.write(opcode);
            output.write(payload.length);
            output.write(payload);
            output.flush();
        }

        private static ClientFrame readClientFrame(InputStream input) throws IOException {
            int first = input.read();
            int second = input.read();
            if (first < 0 || second < 0) throw new IOException("client closed before Pong");
            int encodedLength = second & 0x7F;
            if (encodedLength > 125 || (second & 0x80) == 0) throw new IOException("invalid client control frame");
            byte[] mask = input.readNBytes(4);
            if (mask.length != 4) throw new IOException("truncated client mask");
            byte[] payload = input.readNBytes(encodedLength);
            if (payload.length != encodedLength) throw new IOException("truncated client frame");
            for (int index = 0; index < payload.length; index++) payload[index] ^= mask[index % mask.length];
            return new ClientFrame(first & 0x0F, payload);
        }

        private record ClientFrame(int opcode, byte[] payload) { }

        @Override public void close() throws Exception {
            closed.set(true);
            socket.close();
            thread.join(Duration.ofSeconds(1));
        }
    }
}
