package ai.ravenroot.server.interaction;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.support.ForwardingRavenrootApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionWebSocketWireTest {
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="interaction-wire" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="end"/>
              </graph>
            </graphml>
            """;
    @Test
    void negotiatesVersionAuthenticatesFromFirstFrameAndRejectsUnknownTaskWithoutDisclosure(
            @TempDir java.nio.file.Path directory) throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        try (var store = new SqliteExecutionStore(directory.resolve("events.db"), clock);
             var engine = new PekkoExecutionEngine("interaction-wire")) {
            var delegate = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), BehaviorRegistry.standard(),
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            var authorized = new AuthorizedRavenrootApplication(delegate,
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            var principal = new AuthenticatedPrincipal("operator", AuthenticatedPrincipal.Type.USER, "issuer",
                    "tenant", Set.of(Role.PLATFORM_ADMIN), Arrays.stream(AuthorizationAction.values())
                    .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
            var context = new RequestContext("server-test-request", principal.subject(), PrincipalType.USER,
                    principal.issuer(), principal.tenantId(), principal.roles(), principal.scopes());
            var humanTask = createHumanTask(store, clock, context);
            var limiter = new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                    ignored -> { });
            var configuration = configuration(port);
            var observedAuthentication = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
            try (var interactions = new InteractionWebSocketServer(configuration, authorized,
                    headers -> {
                        var snapshot = new com.sun.net.httpserver.Headers();
                        headers.forEach((name, values) -> snapshot.put(name, java.util.List.copyOf(values)));
                        observedAuthentication.set(snapshot);
                        if (!"Bearer valid-token".equals(headers.getFirst("Authorization"))) {
                            throw new ai.ravenroot.server.security.AuthenticationException("invalid");
                        }
                        return principal;
                    }, new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                    limiter, humanTask.service(), ignored -> { }, clock)) {
                interactions.start();
                var rejected = HttpClient.newHttpClient().newWebSocketBuilder()
                        .subprotocols(InteractionWebSocketConfiguration.SUBPROTOCOL)
                        .header("Authorization", "Bearer valid-token")
                        .buildAsync(URI.create("ws://127.0.0.1:" + port
                                + InteractionWebSocketConfiguration.PATH), new RecordingListener());
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> rejected.get(5, TimeUnit.SECONDS));
                var listener = new RecordingListener();
                WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                        .subprotocols(InteractionWebSocketConfiguration.SUBPROTOCOL)
                        .header("Cookie", "unrelated=canary")
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + InteractionWebSocketConfiguration.PATH),
                                listener).get(5, TimeUnit.SECONDS);

                socket.sendText("{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"valid-token\"}", true)
                        .get(5, TimeUnit.SECONDS);
                assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"authenticated\""));
                assertEquals(Set.of("Authorization"), observedAuthentication.get().keySet());
                socket.sendText("{\"version\":1,\"type\":\"command\",\"messageId\":\"client-1\","
                        + "\"command\":\"human-task.cancel\",\"taskId\":\""
                        + humanTask.taskId() + "\",\"generation\":1}", true).get(5, TimeUnit.SECONDS);
                String result = listener.messages.poll(5, TimeUnit.SECONDS);
                assertTrue(result.contains("\"outcome\":\"cancelled\""));
                assertTrue(result.contains("\"generation\":2"));
                var existing = awaitEvents(authorized, context);
                long lastOffset = existing.getLast().journalOffset();
                java.util.UUID lastEventId = existing.getLast().eventId();
                socket.sendText("{\"version\":1,\"type\":\"command\",\"messageId\":\"client-2\","
                        + "\"command\":\"human-task.cancel\",\"taskId\":\""
                        + java.util.UUID.randomUUID() + "\",\"generation\":1}", true).get(5, TimeUnit.SECONDS);
                result = listener.messages.poll(5, TimeUnit.SECONDS);
                assertTrue(result.contains("\"type\":\"error\""));
                assertTrue(result.contains("\"code\":\"RESOURCE_REFUSED\""));
                assertTrue(!result.contains("canary"));
                socket.sendText("{\"version\":1,\"type\":\"command\",\"messageId\":\"client-3\","
                        + "\"command\":\"human-task.resolve\",\"taskId\":\""
                        + java.util.UUID.randomUUID() + "\",\"generation\":1,\"payloadBase64\":\"\"}", true)
                        .get(5, TimeUnit.SECONDS);
                result = listener.messages.poll(5, TimeUnit.SECONDS);
                assertTrue(result.contains("\"inReplyTo\":\"client-3\""));
                assertTrue(result.contains("\"code\":\"RESOURCE_REFUSED\""));
                socket.sendText("{\"version\":1,\"type\":\"resume\",\"afterJournalOffset\":"
                        + (lastOffset - 1) + "}", true).get(5, TimeUnit.SECONDS);
                String event = listener.messages.poll(5, TimeUnit.SECONDS);
                assertTrue(event.contains("\"type\":\"execution.event\""));
                assertTrue(event.contains("\"journalOffset\":" + lastOffset));
                assertTrue(event.contains(lastEventId.toString()));
                String acknowledgement = "{\"version\":1,\"type\":\"ack\",\"journalOffset\":"
                        + lastOffset + ",\"eventId\":\"" + lastEventId + "\"}";
                socket.sendText(acknowledgement, true).get(5, TimeUnit.SECONDS);
                socket.sendText(acknowledgement, true).get(5, TimeUnit.SECONDS);
                socket.sendText("{\"version\":1,\"type\":\"command\",\"messageId\":\"after-duplicate\","
                        + "\"command\":\"human-task.cancel\",\"taskId\":\""
                        + java.util.UUID.randomUUID() + "\",\"generation\":1}", true).get(5, TimeUnit.SECONDS);
                assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"inReplyTo\":\"after-duplicate\""));
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

                var resumedListener = new RecordingListener();
                WebSocket resumed = connect(port, resumedListener);
                authenticate(resumed, resumedListener);
                resumed.sendText("{\"version\":1,\"type\":\"resume\",\"afterJournalOffset\":"
                        + lastOffset + "}", true).get(5, TimeUnit.SECONDS);
                authorized.startGraphMl(context,
                        new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "after-reconnect");
                String newEvent = resumedListener.messages.poll(5, TimeUnit.SECONDS);
                assertTrue(newEvent.contains("\"type\":\"execution.event\""));
                assertTrue(!newEvent.contains("\"journalOffset\":" + lastOffset + ","));
                resumed.sendText("{\"version\":1,\"type\":\"ack\",\"journalOffset\":"
                        + extractLong(newEvent, "journalOffset") + ",\"eventId\":\""
                        + java.util.UUID.randomUUID() + "\"}", true).get(5, TimeUnit.SECONDS);
                assertEquals(1002, resumedListener.closeCode.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void closesOnHardAuthenticationExpiry(@TempDir java.nio.file.Path directory) throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("interaction-expiry");
             var store = new SqliteExecutionStore(directory.resolve("expiry.db"), clock)) {
            var authorized = application(engine, store);
            var principal = principal(clock.instant().plusMillis(300));
            try (var interactions = server(port, authorized, store, clock, headers -> principal)) {
                interactions.start();
                var listener = new RecordingListener();
                WebSocket socket = connect(port, listener);
                authenticate(socket, listener);
                assertEquals(1008, listener.closeCode.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void identityDriftDuringRevalidationStopsTheSession(@TempDir java.nio.file.Path directory)
            throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("interaction-drift");
             var store = new SqliteExecutionStore(directory.resolve("drift.db"), clock)) {
            var authorized = new AuthorizedRavenrootApplication(application(engine, store),
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            var original = principal(java.time.Instant.MAX);
            var drifted = new AuthenticatedPrincipal("different", original.type(), original.issuer(),
                    original.tenantId(), original.roles(), original.scopes(), original.expiresAt());
            var authenticator = new ai.ravenroot.server.security.RequestAuthenticator() {
                @Override public AuthenticatedPrincipal authenticate(com.sun.net.httpserver.Headers headers) {
                    return original;
                }
                @Override public AuthenticatedPrincipal revalidate(com.sun.net.httpserver.Headers headers) {
                    return drifted;
                }
            };
            try (var interactions = new InteractionWebSocketServer(configuration(port), authorized, authenticator,
                    new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofMillis(100),
                    new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                            ignored -> { }), new HumanTaskService(store, clock), ignored -> { }, clock)) {
                interactions.start();
                var listener = new RecordingListener();
                WebSocket socket = connect(port, listener);
                authenticate(socket, listener);
                assertEquals(1008, listener.closeCode.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void malformedOversizedAndBinaryFramesUseBoundedCloseCodes(@TempDir java.nio.file.Path directory)
            throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("interaction-protocol-errors");
             var store = new SqliteExecutionStore(directory.resolve("protocol-errors.db"), clock)) {
            var authorized = application(engine, store);
            try (var interactions = server(port, authorized, store, clock,
                    headers -> principal(java.time.Instant.MAX))) {
                interactions.start();
                assertTrue(rawHandshake(port, "Origin: https://console.example\r\n").contains(" 101 "));
                String extensionOffer = rawHandshake(port, "Origin: https://console.example\r\n"
                        + "Sec-WebSocket-Extensions: permessage-deflate\r\n");
                assertTrue(extensionOffer.contains(" 101 "));
                assertTrue(!extensionOffer.toLowerCase(java.util.Locale.ROOT)
                        .contains("sec-websocket-extensions:"));
                assertTrue(rawHandshake(port, "Origin: https://evil.example\r\n").contains(" 403 "));
                assertTrue(rawHandshake(port, "Origin: https://console.example\r\nOrigin: https://evil.example\r\n")
                        .contains(" 400 "));
                var malformed = new RecordingListener();
                connect(port, malformed).sendText("not-json", true).get(5, TimeUnit.SECONDS);
                assertEquals(1002, malformed.closeCode.get(5, TimeUnit.SECONDS));

                var binary = new RecordingListener();
                connect(port, binary).sendBinary(java.nio.ByteBuffer.wrap(new byte[] {1}), true)
                        .get(5, TimeUnit.SECONDS);
                assertEquals(1003, binary.closeCode.get(5, TimeUnit.SECONDS));

                var oversized = new RecordingListener();
                connect(port, oversized).sendText("x".repeat(1025), true).get(5, TimeUnit.SECONDS);
                assertEquals(1009, oversized.closeCode.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void blockedAuthenticationCannotStarveDeadlinesOrRetainPendingSlots(
            @TempDir java.nio.file.Path directory) throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var engine = new PekkoExecutionEngine("interaction-blocked-auth");
             var store = new SqliteExecutionStore(directory.resolve("blocked-auth.db"), clock)) {
            var authorized = new AuthorizedRavenrootApplication(application(engine, store),
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            var constrained = configuration(port, 3, Duration.ofSeconds(1));
            try (var interactions = new InteractionWebSocketServer(constrained, authorized, headers -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ai.ravenroot.server.security.AuthenticationException("interrupted", interrupted);
                }
                return principal(java.time.Instant.MAX);
            }, new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                    new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                            ignored -> { }), new HumanTaskService(store, clock), ignored -> { }, clock)) {
                interactions.start();
                var first = new RecordingListener();
                var second = new RecordingListener();
                var third = new RecordingListener();
                WebSocket firstSocket = connect(port, first);
                WebSocket secondSocket = connect(port, second);
                WebSocket thirdSocket = connect(port, third);
                firstSocket.sendText("{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"one\"}", true);
                secondSocket.sendText("{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"two\"}", true);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                CompletableFuture.allOf(first.closeCode, second.closeCode, third.closeCode)
                        .get(3, TimeUnit.SECONDS);
                assertEquals(1008, first.closeCode.join());
                assertEquals(1008, second.closeCode.join());
                assertEquals(1008, third.closeCode.join());
                assertTrue(thirdSocket.isOutputClosed());

                var replacements = new java.util.ArrayList<WebSocket>();
                for (int index = 0; index < 3; index++) {
                    replacements.add(connect(port, new RecordingListener()));
                }
                for (WebSocket replacement : replacements) {
                    replacement.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void disconnectedBlockedAuthenticationRemainsGloballyBounded(
            @TempDir java.nio.file.Path directory) throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        var started = new CountDownLatch(2);
        var replacementStarted = new CountDownLatch(1);
        var oneReturned = new CountDownLatch(1);
        var releases = new Semaphore(0);
        var invocations = new AtomicInteger();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        try (var engine = new PekkoExecutionEngine("interaction-backend-bound");
             var store = new SqliteExecutionStore(directory.resolve("backend-bound.db"), clock)) {
            var authorized = new AuthorizedRavenrootApplication(application(engine, store),
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            var constrained = configuration(port, 8, 2, Duration.ofSeconds(1));
            try (var interactions = new InteractionWebSocketServer(constrained, authorized, headers -> {
                int invocation = invocations.incrementAndGet();
                if (invocation == 3) replacementStarted.countDown();
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                started.countDown();
                try {
                    releases.acquire();
                    return principal(java.time.Instant.MAX);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ai.ravenroot.server.security.AuthenticationException("interrupted", interrupted);
                } finally {
                    active.decrementAndGet();
                    oneReturned.countDown();
                }
            }, new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                    new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                            ignored -> { }), new HumanTaskService(store, clock), ignored -> { }, clock)) {
                interactions.start();
                var blocked = new java.util.ArrayList<RecordingListener>();
                for (int index = 0; index < 2; index++) {
                    var listener = new RecordingListener();
                    blocked.add(listener);
                    connect(port, listener).sendText(
                            "{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"blocked\"}", true);
                }
                assertTrue(started.await(5, TimeUnit.SECONDS));
                CompletableFuture.allOf(blocked.get(0).closeCode, blocked.get(1).closeCode)
                        .get(3, TimeUnit.SECONDS);

                for (int index = 0; index < 5; index++) {
                    var refused = new RecordingListener();
                    connect(port, refused).sendText(
                            "{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"extra\"}", true);
                    assertEquals(1013, refused.closeCode.get(5, TimeUnit.SECONDS));
                }
                assertEquals(2, invocations.get());
                assertEquals(2, peak.get());
                assertEquals(0, interactions.availableBackendOperations());

                releases.release();
                assertTrue(oneReturned.await(5, TimeUnit.SECONDS));
                awaitBackendCapacity(interactions, 1);
                var replacement = new RecordingListener();
                connect(port, replacement).sendText(
                        "{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"replacement\"}", true);
                assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));
                assertEquals(3, invocations.get());
                assertEquals(2, peak.get());
            } finally {
                releases.release(10);
            }
        }
    }

    @Test
    void disconnectedBlockedReplayRetainsBackendPermitUntilStoreReturns(
            @TempDir java.nio.file.Path directory) throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        var storeStarted = new CountDownLatch(1);
        var storeReturned = new CountDownLatch(1);
        var releaseStore = new CountDownLatch(1);
        try (var engine = new PekkoExecutionEngine("interaction-backend-replay-bound");
             var store = new SqliteExecutionStore(directory.resolve("backend-replay-bound.db"), clock)) {
            var blockedStore = new ForwardingRavenrootApplication(application(engine, store)) {
                @Override public java.util.List<ai.ravenroot.api.application.DurableExecutionEvent>
                        durableEventsAfter(String tenantId, long afterOffset, int limit) {
                    storeStarted.countDown();
                    try {
                        releaseStore.await();
                        return java.util.List.of();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } finally {
                        storeReturned.countDown();
                    }
                }
            };
            var authorized = new AuthorizedRavenrootApplication(blockedStore,
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            try (var interactions = new InteractionWebSocketServer(
                    configuration(port, 8, 1, Duration.ofSeconds(5)), authorized,
                    headers -> principal(java.time.Instant.MAX),
                    new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                    new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                            ignored -> { }), new HumanTaskService(store, clock), ignored -> { }, clock)) {
                interactions.start();
                var blocked = new RecordingListener();
                WebSocket blockedSocket = connect(port, blocked);
                authenticate(blockedSocket, blocked);
                blockedSocket.sendText("{\"version\":1,\"type\":\"resume\",\"afterJournalOffset\":0}", true);
                assertTrue(storeStarted.await(5, TimeUnit.SECONDS));
                assertEquals(0, interactions.availableBackendOperations());
                blockedSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                assertEquals(WebSocket.NORMAL_CLOSURE, blocked.closeCode.get(5, TimeUnit.SECONDS));

                for (int index = 0; index < 3; index++) {
                    var refused = new RecordingListener();
                    connect(port, refused).sendText(
                            "{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"extra\"}", true);
                    assertEquals(1013, refused.closeCode.get(5, TimeUnit.SECONDS));
                }

                releaseStore.countDown();
                assertTrue(storeReturned.await(5, TimeUnit.SECONDS));
                awaitBackendCapacity(interactions, 1);
                var replacement = new RecordingListener();
                authenticate(connect(port, replacement), replacement);
            } finally {
                releaseStore.countDown();
            }
        }
    }

    @Test
    void repeatedValidDuplicateAcknowledgementsConsumeIdentityRateBudget(
            @TempDir java.nio.file.Path directory) throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("interaction-ack-rate");
             var store = new SqliteExecutionStore(directory.resolve("ack-rate.db"), clock)) {
            var authorized = new AuthorizedRavenrootApplication(application(engine, store),
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            AuthenticatedPrincipal principal = principal(java.time.Instant.MAX);
            RequestContext context = new RequestContext("ack-rate-seed", principal.subject(), PrincipalType.USER,
                    principal.issuer(), principal.tenantId(), principal.roles(), principal.scopes());
            createHumanTask(store, clock, context);
            var events = awaitEvents(authorized, context);
            var last = events.getLast();
            try (var interactions = new InteractionWebSocketServer(configuration(port), authorized,
                    headers -> principal,
                    new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                    rateLimiterWithPrincipalBurst(4), new HumanTaskService(store, clock), ignored -> { }, clock)) {
                interactions.start();
                var listener = new RecordingListener();
                WebSocket socket = connect(port, listener);
                authenticate(socket, listener);
                socket.sendText("{\"version\":1,\"type\":\"resume\",\"afterJournalOffset\":"
                        + (last.journalOffset() - 1) + "}", true).get(5, TimeUnit.SECONDS);
                assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains(last.eventId().toString()));
                String acknowledgement = "{\"version\":1,\"type\":\"ack\",\"journalOffset\":"
                        + last.journalOffset() + ",\"eventId\":\"" + last.eventId() + "\"}";
                socket.sendText(acknowledgement, true).get(5, TimeUnit.SECONDS);
                socket.sendText(acknowledgement, true).get(5, TimeUnit.SECONDS);
                socket.sendText(acknowledgement, true).get(5, TimeUnit.SECONDS);
                assertEquals(1013, listener.closeCode.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void purgedReplayCursorReportsCanonicalFloorBeforeClosing(@TempDir java.nio.file.Path directory)
            throws Exception {
        int port = freePort();
        Clock clock = Clock.systemUTC();
        try (var engine = new PekkoExecutionEngine("interaction-truncated");
             var store = new SqliteExecutionStore(directory.resolve("truncated.db"), clock)) {
            var truncated = new ForwardingRavenrootApplication(application(engine, store)) {
                @Override public java.util.List<ai.ravenroot.api.application.DurableExecutionEvent>
                        durableEventsAfter(String tenantId, long afterOffset, int limit) {
                    throw new ai.ravenroot.api.persistence.ExecutionStoreException(
                            new ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated(
                                    tenantId, afterOffset, 7));
                }
            };
            var authorized = new AuthorizedRavenrootApplication(truncated,
                    new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
            try (var interactions = new InteractionWebSocketServer(configuration(port), authorized,
                    headers -> principal(java.time.Instant.MAX),
                    new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                    new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                            ignored -> { }), new HumanTaskService(store, clock), ignored -> { }, clock)) {
                interactions.start();
                var listener = new RecordingListener();
                WebSocket socket = connect(port, listener);
                authenticate(socket, listener);
                socket.sendText("{\"version\":1,\"type\":\"resume\",\"afterJournalOffset\":0}", true)
                        .get(5, TimeUnit.SECONDS);
                assertEquals("{\"version\":1,\"type\":\"stream.truncated\","
                        + "\"code\":\"STREAM_RETENTION_EXCEEDED\",\"retainedFrom\":7,\"resumeFrom\":6}",
                        listener.messages.poll(5, TimeUnit.SECONDS));
                assertEquals(1008, listener.closeCode.get(5, TimeUnit.SECONDS));
            }
        }
    }

    private static String rawHandshake(int port, String extraHeaders) throws Exception {
        try (var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5_000);
            String request = "GET " + InteractionWebSocketConfiguration.PATH + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Connection: Upgrade\r\nUpgrade: websocket\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: AAECAwQFBgcICQoLDA0ODw==\r\n"
                    + "Sec-WebSocket-Protocol: " + InteractionWebSocketConfiguration.SUBPROTOCOL + "\r\n"
                    + extraHeaders + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = new StringBuilder();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                response.append(line).append('\n');
            }
            return response.toString();
        }
    }

    private static RateLimiter rateLimiterWithPrincipalBurst(int principalBurst) {
        var defaults = RateLimitConfiguration.DEFAULTS;
        var configuration = new RateLimitConfiguration(
                defaults.addressRequestsPerSecond(), defaults.addressBurst(),
                defaults.tenantRequestsPerSecond(), defaults.tenantBurst(),
                1, principalBurst,
                defaults.submissionsPerSecond(), defaults.submissionBurst(),
                defaults.tenantConcurrentSubmissions(), defaults.globalActiveExecutions(),
                defaults.tenantConcurrentStreams(), defaults.principalConcurrentStreams(),
                defaults.streamQueueCapacity(), defaults.maxQueryBytes(), defaults.maxQueryParameters(),
                defaults.maxHeaderCount(), defaults.maxHeaderBytes(), defaults.maxHeaderValueBytes(),
                defaults.maxTrackedClients(), defaults.maxTrackedTenants(), defaults.maxTrackedPrincipals(),
                defaults.idleEntryTtl(), defaults.executionMaxAge());
        return new RateLimiter(configuration, TrustedProxyConfiguration.direct(), ignored -> { }, () -> 0L);
    }

    private static DefaultRavenrootApplication application(PekkoExecutionEngine engine,
                                                            SqliteExecutionStore store) {
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(), BehaviorRegistry.standard(),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store);
    }

    private static AuthenticatedPrincipal principal(java.time.Instant expiresAt) {
        return new AuthenticatedPrincipal("operator", AuthenticatedPrincipal.Type.USER, "issuer", "tenant",
                Set.of(Role.PLATFORM_ADMIN), Arrays.stream(AuthorizationAction.values())
                .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()), expiresAt);
    }

    private static InteractionWebSocketServer server(int port, DefaultRavenrootApplication delegate,
                                                      SqliteExecutionStore store, Clock clock,
                                                      ai.ravenroot.server.security.RequestAuthenticator authenticator) {
        var authorized = new AuthorizedRavenrootApplication(delegate,
                new DefaultAuthorizationService(ignored -> { }), ignored -> { }, false);
        return new InteractionWebSocketServer(configuration(port), authorized, authenticator,
                new BrowserOriginPolicy(Set.of("https://console.example")), Duration.ofSeconds(30),
                new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(), ignored -> { }),
                new HumanTaskService(store, clock), ignored -> { }, clock);
    }

    private static TaskFixture createHumanTask(SqliteExecutionStore store, Clock clock,
                                               RequestContext requester) {
        var key = new ai.ravenroot.api.persistence.ExecutionKey(requester.tenantId(), java.util.UUID.randomUUID());
        var traversalId = java.util.UUID.randomUUID();
        var invocationId = java.util.UUID.randomUUID();
        var attemptId = java.util.UUID.randomUUID();
        var attempt = new ai.ravenroot.api.application.NodeAttempt(attemptId, 1,
                ai.ravenroot.api.application.NodeAttemptStatus.RUNNING);
        var invocation = new ai.ravenroot.api.application.NodeInvocation(invocationId, "review", Set.of(),
                ai.ravenroot.api.application.NodeInvocationStatus.RUNNING, java.util.List.of(attempt));
        var traversal = new ai.ravenroot.api.application.Traversal(traversalId, "review",
                ai.ravenroot.api.application.TraversalStatus.RUNNING, java.util.Map.of(invocationId, invocation));
        long revision = store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                .expecting(ai.ravenroot.api.persistence.RevisionExpectation.notPresent())
                .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated(
                        new ai.ravenroot.api.application.ProcessInstance(key.processInstanceId(),
                                ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING,
                                java.util.Map.of(traversalId, traversal)),
                        new ai.ravenroot.api.persistence.GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join().revision();
        var service = new HumanTaskService(store, clock);
        var security = ai.ravenroot.api.security.SecurityContext.of(requester);
        var message = new ai.ravenroot.api.execution.NodeMessage(security, key.processInstanceId(), traversalId,
                invocationId, attemptId, "review", java.util.Map.of(), java.util.Map.of());
        var policy = ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS;
        var definition = new ai.ravenroot.core.humantask.HumanTaskDefinition(
                new ai.ravenroot.api.persistence.HumanTaskMetadata("Review", "Cancel this task."),
                new ai.ravenroot.api.persistence.HumanTaskResponseSchema("application/octet-stream",
                        "wire-test", "1", ai.ravenroot.api.payload.PayloadKind.SCALAR, 4096),
                ai.ravenroot.api.persistence.HandlerAuthorization.ofRoles(Role.PLATFORM_ADMIN.name()),
                java.util.Optional.empty(), Duration.ofHours(1),
                new ai.ravenroot.api.persistence.HumanTaskReentryMapping(
                        "resolved", "denied", "expired", "cancelled"), policy.executionLimits(4096));
        ai.ravenroot.core.humantask.HumanTaskResult created;
        try (var recorder = ai.ravenroot.core.runtime.ExecutionRecorder.open(store, key, "wire-task",
                Duration.ofSeconds(30), revision); var ignored = service.bindLive(key, recorder)) {
            created = service.suspend(message, definition);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertEquals(ai.ravenroot.core.humantask.HumanTaskResult.Code.CREATED, created.code());
        return new TaskFixture(service, created.task().request().taskId());
    }

    private record TaskFixture(HumanTaskService service, java.util.UUID taskId) { }

    private static java.util.List<ai.ravenroot.api.application.DurableExecutionEvent> awaitEvents(
            AuthorizedRavenrootApplication application, RequestContext context) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            var events = application.durableEventsAfter(context, 0, 128);
            if (!events.isEmpty()) return events;
            Thread.sleep(50);
        }
        throw new AssertionError("seed execution produced no durable event");
    }

    private static WebSocket connect(int port, RecordingListener listener) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols(InteractionWebSocketConfiguration.SUBPROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + InteractionWebSocketConfiguration.PATH), listener)
                .get(5, TimeUnit.SECONDS);
    }

    private static void authenticate(WebSocket socket, RecordingListener listener) throws Exception {
        socket.sendText("{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"valid-token\"}", true)
                .get(5, TimeUnit.SECONDS);
        assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"authenticated\""));
    }

    private static long extractLong(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), json);
        return Long.parseLong(matcher.group(1));
    }

    private static void awaitBackendCapacity(InteractionWebSocketServer server, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.availableBackendOperations() != expected && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertEquals(expected, server.availableBackendOperations());
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static InteractionWebSocketConfiguration configuration(int port) {
        return configuration(port, 8, Duration.ofSeconds(5));
    }

    private static InteractionWebSocketConfiguration configuration(int port, int pendingAuthentication,
                                                                   Duration authenticationDeadline) {
        return configuration(port, pendingAuthentication, 128, authenticationDeadline);
    }

    private static InteractionWebSocketConfiguration configuration(int port, int pendingAuthentication,
                                                                   int backendOperations,
                                                                   Duration authenticationDeadline) {
        return new InteractionWebSocketConfiguration(true,
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port),
                32, pendingAuthentication, pendingAuthentication, backendOperations, authenticationDeadline,
                512 * 1024, 16, 8, 1024 * 1024,
                1024,
                32, 1024 * 1024, 8, Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofSeconds(2));
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder current = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                                                                        CharSequence data,
                                                                        boolean last) {
            current.append(data);
            if (last) {
                messages.add(current.toString());
                current.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int statusCode,
                                                                         String reason) {
            closeCode.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }
    }
}
