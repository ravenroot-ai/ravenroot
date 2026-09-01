package ai.ravenroot.server.ingress;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.server.AuthenticatedPrincipalAttribute;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ManagedIngressRegistryTest {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
    private static final IngressAuthorityDeclaration AUTHORITY = new IngressAuthorityDeclaration("example.oas", "main",
            "/managed/example", Set.of("invoke"), 1, 1, 16, 32, Duration.ofSeconds(1));
    private final Set<HttpServer> servers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeAll static void initializeProductionHeaderGuardBeforeRawJdkServers() throws Exception {
        // RavenrootServer owns this process-wide JDK property. These lower-level tests create raw
        // HttpServer instances, so initialize the production owner before the JDK freezes its value.
        Class.forName("ai.ravenroot.server.RavenrootServer", true,
                ManagedIngressRegistryTest.class.getClassLoader());
    }

    @AfterEach void stopOwnedServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test void rejectsOverlappingAuthorityBeforeServerBindAndRefusesReplicas() {
        assertThrows(IllegalArgumentException.class, () -> ManagedIngressRegistry.prepare(List.of(AUTHORITY,
                new IngressAuthorityDeclaration("other", "main", "/managed/example/v2", Set.of(), 1, 1, 1, 1, Duration.ofSeconds(1))), true));
        assertThrows(IllegalStateException.class, () -> ManagedIngressRegistry.prepare(List.of(AUTHORITY), false));
        for (String reserved : List.of("/", "/v1", "/v1/status", "/health", "/health/detail",
                "/ready", "/ready/detail")) {
            assertThrows(IllegalArgumentException.class, () -> {
                var declaration = new IngressAuthorityDeclaration("reserved", "main", reserved, Set.of(),
                        1, 1, 1, 1, Duration.ofSeconds(1));
                ManagedIngressRegistry.prepare(List.of(declaration), true);
            }, reserved);
        }
    }

    @Test void leaseIsAtomicFencedAndReleaseCannotRemoveReplacement() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry) {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME, new AuthenticatedPrincipal("user", AuthenticatedPrincipal.Type.USER, "issuer", "tenant", Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            var old = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            var lease = registry.authorityFor(old).acquire("orders", "/orders", Set.of("POST"), request -> CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {1})));
            assertEquals(List.of(new ManagedIngressRegistry.IngressRouteInventory(
                    "orders", "example.oas", 1, "ACTIVE")), registry.inventory());
            assertThrows(IllegalStateException.class, () -> registry.authorityFor(old).acquire("other", "/other", Set.of("POST"), request -> CompletableFuture.completedFuture(null)), "capacity winner is atomic");
            registry.retire(old);
            var replacement = registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 2)).acquire("orders2", "/orders", Set.of("POST"), request -> CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {2})));
            lease.release();
            server.start();
            var response = HTTP_CLIENT.send(request(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/managed/example/orders")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, response.statusCode()); assertArrayEquals(new byte[] {2}, response.body());
            replacement.release(); server.stop(0);
        }
    }

    @Test void jdkPrefixContextNeverServesALeaseSubpath() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry) {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME, new AuthenticatedPrincipal("user", AuthenticatedPrincipal.Type.USER, "issuer", "tenant", Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1))
                    .acquire("orders", "/orders", Set.of("POST"), request -> CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0])));
            server.start();
            var response = HTTP_CLIENT.send(request(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/managed/example/orders/extra")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, response.statusCode());
            server.stop(0);
        }
    }

    @Test void exactLeaseCannotBeReinterpretedAsPrefixAndRemainsExactOverHttp() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry) {
            var server = authenticatedServer(registry, "tenant");
            var owner = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            var exact = registry.authorityFor(owner).acquire("orders", "/orders", Set.of("POST"), request ->
                    CompletableFuture.completedFuture(new IngressResponse(
                            202, Map.of(), "exact".getBytes(StandardCharsets.UTF_8))));

            assertThrows(IllegalStateException.class, () -> registry.authorityFor(owner)
                    .acquirePrefix("orders", "/orders", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(500, Map.of(), new byte[0]))));
            assertSame(exact, registry.authorityFor(owner).acquire(
                    "orders", "/orders", Set.of("POST"), request -> null),
                    "same-kind idempotency still returns the original exact lease");
            assertEquals(1, registry.inventory().size());

            server.start();
            var client = HTTP_CLIENT;
            String base = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/example/orders";
            var root = client.send(request(URI.create(base))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, root.statusCode());
            assertEquals("exact", root.body());
            assertEquals(404, post(client, base + "/child", new byte[0]).statusCode(),
                    "the rejected prefix request cannot mutate the existing exact context");
            server.stop(0);
        }
    }

    @Test void prefixLeaseCannotBeReinterpretedAsExactAndStillServesDescendantsOverHttp() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry) {
            var server = authenticatedServer(registry, "tenant");
            var owner = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            var prefix = registry.authorityFor(owner).acquirePrefix(
                    "orders", "/orders", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(
                                    202, Map.of(), request.relativePath().getBytes(StandardCharsets.UTF_8))));

            assertThrows(IllegalStateException.class, () -> registry.authorityFor(owner)
                    .acquire("orders", "/orders", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(500, Map.of(), new byte[0]))));
            assertSame(prefix, registry.authorityFor(owner).acquirePrefix(
                    "orders", "/orders", Set.of("POST"), request -> null),
                    "same-kind idempotency still returns the original prefix lease");
            assertEquals(1, registry.inventory().size());

            server.start();
            var response = HTTP_CLIENT.send(request(URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort()
                                            + "/managed/example/orders/child"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode());
            assertEquals("/child", response.body(),
                    "the rejected exact request cannot mutate the existing prefix context");
            server.stop(0);
        }
    }

    @Test void concurrentMixedRouteKindsHaveOneAtomicWinnerAndOneLiveContext() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = authenticatedServer(registry, "tenant");
            var owner = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            var barrier = new CyclicBarrier(2);
            var winner = new AtomicReference<String>();
            var refusals = new AtomicInteger();
            var exact = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    registry.authorityFor(owner).acquire("orders", "/orders", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(
                                    202, Map.of(), "exact".getBytes(StandardCharsets.UTF_8))));
                    assertTrue(winner.compareAndSet(null, "exact"));
                } catch (IllegalStateException collision) {
                    refusals.incrementAndGet();
                }
                return null;
            });
            var prefix = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    registry.authorityFor(owner).acquirePrefix(
                            "orders", "/orders", Set.of("POST"), request ->
                                    CompletableFuture.completedFuture(new IngressResponse(
                                            202, Map.of(), "prefix".getBytes(StandardCharsets.UTF_8))));
                    assertTrue(winner.compareAndSet(null, "prefix"));
                } catch (IllegalStateException collision) {
                    refusals.incrementAndGet();
                }
                return null;
            });
            exact.get(5, TimeUnit.SECONDS);
            prefix.get(5, TimeUnit.SECONDS);
            assertNotNull(winner.get());
            assertEquals(1, refusals.get());
            assertEquals(1, registry.inventory().size(),
                    "the mixed acquisition race publishes one lease and one JDK context");

            server.start();
            var client = HTTP_CLIENT;
            String base = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/example/orders";
            for (int attempt = 0; attempt < 2; attempt++) {
                var root = client.send(request(URI.create(base))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, root.statusCode());
                assertEquals(winner.get(), root.body());
            }
            int descendantStatus = post(client, base + "/child", new byte[0]).statusCode();
            assertEquals(winner.get().equals("prefix") ? 202 : 404, descendantStatus);
            assertEquals(202, post(client, base, new byte[0]).statusCode(),
                    "the losing acquisition and descendant probe leak no request permit");
            server.stop(0);
        }
    }

    @Test void authenticatedTenantCannotBeForgedByBodyAndHandlerReceivesTrustedPrincipal() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry) {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                String tenant = exchange.getRequestHeaders().getFirst("X-Test-Tenant");
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                        new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer", tenant,
                                Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            var calls = new AtomicInteger();
            var seenTenant = new AtomicReference<String>();
            registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1))
                    .acquire("orders", "/orders", Set.of("POST"), request -> {
                        calls.incrementAndGet();
                        seenTenant.set(request.principal().tenantId());
                        return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0]));
                    });
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/example/orders");
            var forged = HTTP_CLIENT.send(request(uri)
                    .header("X-Test-Tenant", "other")
                    .POST(HttpRequest.BodyPublishers.ofString("tenant=tenant"))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(403, forged.statusCode());
            assertEquals(0, calls.get(), "body content cannot forge the authenticated tenant");

            var authorized = HTTP_CLIENT.send(request(uri)
                    .header("X-Test-Tenant", "tenant")
                    .POST(HttpRequest.BodyPublishers.ofString("tenant=other"))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(202, authorized.statusCode());
            assertEquals("tenant", seenTenant.get());
            server.stop(0);
        }
    }

    @Test void concurrentGraphsHaveOneAtomicWinnerAndUnrelatedRouteRemainsLive() throws Exception {
        var authority = new IngressAuthorityDeclaration("example.oas", "main", "/managed/example",
                Set.of("invoke"), 3, 2, 128, 128, Duration.ofSeconds(1));
        var registry = ManagedIngressRegistry.prepare(List.of(authority), true);
        try (registry; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = httpServer();
            registry.bind(server, handler -> handler);
            registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "stable", "node", 1))
                    .acquire("stable", "/stable", Set.of("GET"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(204, Map.of(), new byte[0])));
            var barrier = new CyclicBarrier(2);
            var wins = new AtomicInteger();
            var losses = new AtomicInteger();
            var tasks = List.of(1, 2).stream().map(generation -> executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    registry.authorityFor(new IngressRouteOwner("example.oas", "tenant",
                                    "contender-" + generation, "node", generation))
                            .acquire("shared", "/shared", Set.of("POST"), request ->
                                    CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0])));
                    wins.incrementAndGet();
                } catch (IllegalStateException collision) {
                    losses.incrementAndGet();
                }
                return null;
            })).toList();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(1, wins.get());
            assertEquals(1, losses.get());
            assertEquals(2, registry.inventory().size(),
                    "the winner and unrelated route survive the losing graph activation");
            server.stop(0);
        }
    }

    @Test void requestResponseAndTimeoutBoundsAreEnforcedBeforeOrAfterPluginCode() throws Exception {
        var bounded = new IngressAuthorityDeclaration("bounded", "main", "/managed/bounded", Set.of("invoke"),
                5, 1, 8, 8, Duration.ofMillis(50));
        var registry = ManagedIngressRegistry.prepare(List.of(bounded), true);
        try (registry) {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                        new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer", "tenant",
                                Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            var owner = new IngressRouteOwner("bounded", "tenant", "deployment", "node", 1);
            var calls = new AtomicInteger();
            registry.authorityFor(owner).acquire("request", "/request", Set.of("POST"), request -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(new IngressResponse(204, Map.of(), new byte[0]));
            });
            registry.authorityFor(owner).acquire("response", "/response", Set.of("POST"), request ->
                    CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[9])));
            registry.authorityFor(owner).acquire("slow", "/slow", Set.of("POST"), request ->
                    new CompletableFuture<>());
            registry.authorityFor(owner).acquire("headers", "/headers", Set.of("POST"), request -> {
                var headers = new java.util.LinkedHashMap<String, String>();
                for (int index = 0; index < 33; index++) headers.put("X-H-" + index, "v");
                return CompletableFuture.completedFuture(new IngressResponse(200, headers, new byte[0]));
            });
            var slowUploadCalls = new AtomicInteger();
            registry.authorityFor(owner).acquire("upload", "/upload", Set.of("POST"), request -> {
                slowUploadCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new IngressResponse(204, Map.of(), new byte[0]));
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/managed/bounded";
            var client = HTTP_CLIENT;
            assertEquals(413, post(client, base + "/request", new byte[9]).statusCode());
            assertEquals(0, calls.get(), "oversized request is rejected before plugin code");
            assertEquals(502, post(client, base + "/response", new byte[0]).statusCode());
            // A stage that never completes is the deadline winning, and it says so. Reporting 502
            // would instead accuse the package of returning something invalid.
            assertEquals(504, post(client, base + "/slow", new byte[0]).statusCode());
            assertEquals(502, post(client, base + "/headers", new byte[0]).statusCode());
            long slowStarted = System.nanoTime();
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                                server.getAddress().getPort()),
                        Math.toIntExact(HTTP_TIMEOUT.toMillis()));
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write(("POST /managed/bounded/upload HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Length: 8\r\nConnection: close\r\n\r\nx")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String status = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.US_ASCII)).readLine();
                // A body that stops arriving is only refused once the absolute deadline has passed,
                // so the client was not "too slow" against some separate budget: the request ran out
                // of the one window it had, and 504 is the only status that says that.
                // JDK HttpServer may still close an incomplete request before flushing it; either
                // wire outcome is a bounded refusal and neither reaches the package.
                assertTrue(status == null || status.contains(" 504 "), String.valueOf(status));
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - slowStarted).compareTo(Duration.ofSeconds(1)) < 0,
                    "slow upload admission must be bounded");
            assertEquals(0, slowUploadCalls.get(), "a slow body is timed out before plugin code");
            server.stop(0);
        }
    }

    @Test void headerAggregateBudgetCountsUtf8BytesAtExactBoundary() {
        var exact = new java.util.LinkedHashMap<String, String>();
        for (int index = 0; index < 16; index++) {
            exact.put("X-" + String.format("%02d", index), "é".repeat(254));
        }
        assertTrue(ManagedIngressRegistry.validHeaders(exact),
                "16 * (4 ASCII key bytes + 508 UTF-8 value bytes) is exactly 8192");

        var oneByteOver = new java.util.LinkedHashMap<>(exact);
        oneByteOver.put("X-00", "é".repeat(253) + "aaa");
        assertFalse(ManagedIngressRegistry.validHeaders(oneByteOver),
                "the aggregate is rejected at 8193 UTF-8 bytes, not Java character count");
    }

    @Test void aNonRespondingIngressProbeFailsWithinItsRequestDeadlineAndReleasesItsServer() throws Exception {
        var handlerEntered = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var server = httpServer();
        server.createContext("/never", exchange -> {
            handlerEntered.countDown();
            try {
                releaseHandler.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        long started = System.nanoTime();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/never");
            assertThrows(HttpTimeoutException.class, () -> HTTP_CLIENT.send(
                    request(uri, Duration.ofMillis(100)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()));
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS),
                    "the deadline must bound an accepted request, not a connection refusal");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0,
                    "a non-responding ingress probe must fail before the test lifecycle timeout");
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test void synchronousBlockingCallbackIsDeadlineBoundedAndCannotStarveReplacement() throws Exception {
        var authority = new IngressAuthorityDeclaration("blocking", "main", "/managed/blocking",
                Set.of("invoke"), 1, 1, 8, 8, Duration.ofMillis(75));
        var registry = ManagedIngressRegistry.prepare(List.of(authority), true);
        var allowOldExit = new AtomicBoolean();
        try (registry) {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                        new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer", "tenant",
                                Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            var oldOwner = new IngressRouteOwner("blocking", "tenant", "deployment", "node", 1);
            var entered = new CountDownLatch(1);
            var calls = new AtomicInteger();
            registry.authorityFor(oldOwner).acquire("old", "/route", Set.of("POST"), request -> {
                calls.incrementAndGet();
                entered.countDown();
                while (!allowOldExit.get()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                    Thread.interrupted(); // deliberately ignore cancellation like hostile package code
                }
                return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[] {1}));
            });
            server.start();
            var client = HTTP_CLIENT;
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/blocking/route");
            long started = System.nanoTime();
            var timedOut = client.send(request(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(504, timedOut.statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(500)) < 0,
                    "synchronous package code is inside the request deadline");
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            var refusedBehindStuckWorker = client.send(request(uri)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            // The discrimination that matters: this one is 502 and the one above is 504, on the same
            // route within a second of each other. Nothing was admitted here — the zero-queue executor
            // refused the work outright — so no deadline expired and blaming one would be a lie.
            assertEquals(502, refusedBehindStuckWorker.statusCode());
            assertEquals(1, calls.get(), "the zero-queue executor never starts work behind a stuck callback");

            long retiring = System.nanoTime();
            registry.retire(oldOwner);
            assertTrue(Duration.ofNanos(System.nanoTime() - retiring).compareTo(Duration.ofMillis(500)) < 0,
                    "cancel-ignoring package work cannot stall retirement");
            registry.authorityFor(new IngressRouteOwner("blocking", "tenant", "deployment", "node", 2))
                    .acquire("replacement", "/route", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {2})));
            var replacement = client.send(request(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, replacement.statusCode());
            assertArrayEquals(new byte[] {2}, replacement.body());
            allowOldExit.set(true);
            server.stop(0);
        } finally {
            allowOldExit.set(true);
        }
    }

    @Test void releaseBetweenPermitAndPublicationClosesRequestAndLinearizesRepeatedCallers() throws Exception {
        var permitAcquired = new CountDownLatch(1);
        var allowPublication = new CountDownLatch(1);
        var removalStarted = new CountDownLatch(1);
        var allowContextRemoval = new CountDownLatch(1);
        var firstAdmission = new AtomicBoolean(true);
        var hooks = new ManagedIngressRegistry.LifecycleHooks() {
            @Override public void afterPermitBeforePublication() {
                if (firstAdmission.compareAndSet(true, false)) {
                    permitAcquired.countDown();
                    awaitLatch(allowPublication);
                }
            }

            @Override public void beforeContextRemoval() {
                removalStarted.countDown();
                awaitLatch(allowContextRemoval);
            }
        };
        var registry = ManagedIngressRegistry.prepareWithLifecycleHooks(List.of(AUTHORITY), true, hooks);
        var initiatingReleases = Executors.newSingleThreadExecutor();
        var repeatedReleases = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                        new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer", "tenant",
                                Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            var oldOwner = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            var calls = new AtomicInteger();
            var oldLease = registry.authorityFor(oldOwner).acquire("old", "/orders", Set.of("POST"), request -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[] {1}));
            });
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/example/orders");
            var client = HTTP_CLIENT;
            var oldResponse = client.sendAsync(request(uri)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(permitAcquired.await(1, TimeUnit.SECONDS));

            // Keep the winner on a platform thread: even if repeated virtual-thread callers pin
            // their scheduler, this test can release the lifecycle hook and clean up deterministically.
            var initiatingRelease = initiatingReleases.submit(oldLease::release);
            assertTrue(removalStarted.await(1, TimeUnit.SECONDS));
            var returned = new AtomicInteger();
            int repeatedCallers = virtualThreadSchedulerParallelism() + 1;
            var repeatedStarted = new CountDownLatch(repeatedCallers);
            var repeated = java.util.stream.IntStream.range(0, repeatedCallers)
                    .mapToObj(index -> repeatedReleases.submit(() -> {
                        repeatedStarted.countDown();
                        oldLease.release();
                        returned.incrementAndGet();
                    })).toList();
            assertTrue(repeatedStarted.await(2, TimeUnit.SECONDS),
                    "all repeated release callers must reach the contested lifecycle");
            repeatedReleases.submit(() -> { }).get(1, TimeUnit.SECONDS);
            assertEquals(0, returned.get(),
                    "idempotent release callers cannot return while the old context still exists, "
                            + "but they must not pin the virtual-thread scheduler while waiting");

            allowContextRemoval.countDown();
            initiatingRelease.get(1, TimeUnit.SECONDS);
            for (var release : repeated) release.get(1, TimeUnit.SECONDS);
            assertEquals(repeatedCallers, returned.get());
            assertTrue(registry.inventory().isEmpty(), "context removal also removes old inventory");

            registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 2))
                    .acquire("replacement", "/orders", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {2})));
            allowPublication.countDown();
            try {
                assertNotEquals(200, oldResponse.get(1, TimeUnit.SECONDS).statusCode());
            } catch (java.util.concurrent.ExecutionException closed) {
                assertInstanceOf(java.io.IOException.class, closed.getCause());
            }
            assertEquals(0, calls.get(), "retirement before publication never invokes package code");
            var replacement = client.send(request(uri).POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, replacement.statusCode());
            assertArrayEquals(new byte[] {2}, replacement.body());
            server.stop(0);
        } finally {
            allowContextRemoval.countDown();
            allowPublication.countDown();
            shutdown(initiatingReleases);
            shutdown(repeatedReleases);
            registry.close();
        }
    }

    @Test void lateCompletionFromRetiredGenerationCannotServeThroughReplacement() throws Exception {
        var registry = ManagedIngressRegistry.prepare(List.of(AUTHORITY), true);
        try (registry) {
            var server = httpServer();
            registry.bind(server, handler -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                        new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer", "tenant",
                                Set.of(), Set.of("invoke")));
                handler.handle(exchange);
            });
            var entered = new CountDownLatch(1);
            var oldCompletion = new CompletableFuture<IngressResponse>();
            var oldOwner = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            registry.authorityFor(oldOwner).acquire("old", "/orders", Set.of("POST"), request -> {
                entered.countDown();
                return oldCompletion;
            });
            server.start();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/example/orders");
            var client = HTTP_CLIENT;
            var oldResponse = client.sendAsync(request(uri)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            registry.retire(oldOwner);
            registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 2))
                    .acquire("replacement", "/orders", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {2})));
            oldCompletion.complete(new IngressResponse(200, Map.of(), new byte[] {1}));

            try {
                assertNotEquals(200, oldResponse.get(2, TimeUnit.SECONDS).statusCode(),
                        "retired completion cannot send its plugin response");
            } catch (java.util.concurrent.ExecutionException closedByRetirement) {
                assertInstanceOf(java.io.IOException.class, closedByRetirement.getCause(),
                        "retirement may fence by closing the accepted exchange");
            }
            var replacement = client.send(request(uri).POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, replacement.statusCode());
            assertArrayEquals(new byte[] {2}, replacement.body());
            server.stop(0);
        }
    }

    @Test void prefixDispatchUsesLongestEligibleRouteAndProjectsOnlyBoundedCanonicalData() throws Exception {
        var authority = new IngressAuthorityDeclaration("projection", "main", "/managed/projection",
                Set.of("invoke"), 3, 2, 128, 128, Duration.ofSeconds(1));
        var policy = new IngressRequestProjectionPolicy("projection",
                Set.of("Content-Type", "X-Idempotency-Key"), "X-Idempotency-Key",
                128, 8, 256, 2, 128, 64);
        var registry = ManagedIngressRegistry.prepare(List.of(authority),
                Map.of("projection", policy), "main", true);
        try (registry) {
            var server = authenticatedServer(registry, "tenant");
            var owner = new IngressRouteOwner("projection", "tenant", "deployment", "node", 1);
            var parentSeen = new AtomicReference<ai.ravenroot.api.ingress.IngressRequest>();
            var childSeen = new AtomicReference<ai.ravenroot.api.ingress.IngressRequest>();
            registry.authorityFor(owner).acquirePrefix("parent", "/api", Set.of("POST"), request -> {
                parentSeen.set(request);
                return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), "parent".getBytes()));
            });
            registry.authorityFor(owner).acquirePrefix("child", "/api/orders", Set.of("POST"), request -> {
                childSeen.set(request);
                return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), "child".getBytes()));
            });
            registry.authorityFor(owner).acquire("exact", "/api/orders/special", Set.of("POST"), request ->
                    CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), "exact".getBytes())));
            server.start();
            var client = HTTP_CLIENT;
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/managed/projection";

            var parent = client.send(request(URI.create(base + "/api/other"))
                    .header("X-Idempotency-Key", "idem-parent")
                    .header("Authorization", "must-not-project")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("parent", parent.body());
            assertEquals("/other", parentSeen.get().relativePath());
            assertEquals(Map.of("x-idempotency-key", "idem-parent"), parentSeen.get().headers());

            var child = client.send(request(URI.create(
                            base + "/api/orders/cafe%CC%81?tag=one&tag=two&name=caf%C3%A9&plus=a+b"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("child", child.body(), "the longest eligible prefix owns the descendant");
            assertEquals("/café", childSeen.get().relativePath());
            assertEquals(Map.of("tag", List.of("one", "two"), "name", List.of("café"),
                            "plus", List.of("a+b")),
                    childSeen.get().query());
            assertEquals(Map.of("content-type", "application/json"), childSeen.get().headers());

            var exact = client.send(request(URI.create(base + "/api/orders/special"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("exact", exact.body(), "an exact route is more specific at its own path");
            assertEquals(404, client.send(request(URI.create(
                            base + "/api/orders/special/descendant"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "a more-specific exact route shadows its descendants fail-closed; no prefix fallback");
            server.stop(0);
        }
    }

    @Test void malformedAndOversizedProjectionIsRefusedBeforeAdmissionWithoutLeak() throws Exception {
        var authority = new IngressAuthorityDeclaration("bounded-projection", "main",
                "/managed/bounded-projection", Set.of("invoke"), 1, 1, 8, 8, Duration.ofSeconds(1));
        var policy = new IngressRequestProjectionPolicy("bounded-projection", Set.of("X-Request-Key"), null,
                16, 2, 16, 1, 20, 12);
        var registry = ManagedIngressRegistry.prepare(List.of(authority),
                Map.of("bounded-projection", policy), "main", true);
        try (registry) {
            var server = authenticatedServer(registry, "tenant");
            var calls = new AtomicInteger();
            registry.authorityFor(new IngressRouteOwner(
                            "bounded-projection", "tenant", "deployment", "node", 1))
                    .acquirePrefix("api", "/api", Set.of("POST"), request -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(new IngressResponse(204, Map.of(), new byte[0]));
                    });
            server.start();
            var client = HTTP_CLIENT;
            String base = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/bounded-projection/api";

            for (String suffix : List.of("/%2e%2e/x", "/%252e%252e", "/a%2Fb", "/a%5Cb",
                    "/ok?x=%252e", "/ok?a=1&b=2&c=3")) {
                assertEquals(400, post(client, base + suffix, new byte[0]).statusCode(), suffix);
            }
            assertEquals(400, rawStatus(server.getAddress().getPort(),
                    "/managed/bounded-projection/api/%GG", List.of()));
            assertEquals(400, rawStatus(server.getAddress().getPort(),
                    "/managed/bounded-projection/api/%C3%28", List.of()));
            assertEquals(400, rawStatus(server.getAddress().getPort(),
                    "/managed/bounded-projection/api/ok",
                    List.of("X-Request-Key: one", "X-Request-Key: two")));
            assertEquals(414, post(client, base + "/" + "a".repeat(16), new byte[0]).statusCode());
            assertEquals(414, post(client, base + "/ok?x=" + "a".repeat(15), new byte[0]).statusCode());
            assertEquals(431, client.send(request(URI.create(base + "/ok"))
                    .header("X-Request-Key", "a".repeat(13))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(431, client.send(request(URI.create(base + "/ok"))
                    .header("X-Request-Key", "a".repeat(8))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "13-byte canonical name plus 8-byte value is aggregate max+1");
            assertEquals(0, calls.get(), "projection refusal must happen before package delivery");

            var recovered = client.send(request(URI.create(
                            base + "/" + "a".repeat(15) + "?x=" + "a".repeat(14)))
                    .header("X-Request-Key", "allowed")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(204, recovered.statusCode());
            assertEquals(1, calls.get(),
                    "path, query and aggregate header exact maxima pass and refusals leak no permit");
            server.stop(0);
        }
    }

    @Test void prefixCollisionIsAtomicAndReleasePreservesFencedReplacement() throws Exception {
        var authority = new IngressAuthorityDeclaration("prefix", "main", "/managed/prefix",
                Set.of("invoke"), 2, 1, 8, 8, Duration.ofSeconds(1));
        var registry = ManagedIngressRegistry.prepare(List.of(authority), true);
        try (registry) {
            var server = authenticatedServer(registry, "tenant");
            var oldOwner = new IngressRouteOwner("prefix", "tenant", "deployment", "node", 1);
            var old = registry.authorityFor(oldOwner).acquirePrefix("old", "/api", Set.of("POST"), request ->
                    CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[] {1})));
            assertThrows(IllegalStateException.class, () -> registry.authorityFor(
                            new IngressRouteOwner("prefix", "tenant", "other", "node", 1))
                    .acquirePrefix("contender", "/api", Set.of("POST"), request -> null));
            registry.retire(oldOwner);
            registry.authorityFor(new IngressRouteOwner("prefix", "tenant", "deployment", "node", 2))
                    .acquirePrefix("replacement", "/api", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {2})));
            old.release();
            server.start();
            var response = post(HTTP_CLIENT, "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/managed/prefix/api/descendant", new byte[0]);
            assertEquals(202, response.statusCode());
            server.stop(0);
        }
    }

    private HttpServer httpServer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servers.add(server);
        return server;
    }

    private static HttpRequest.Builder request(URI uri) {
        return request(uri, HTTP_TIMEOUT);
    }

    private static HttpRequest.Builder request(URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri).timeout(timeout);
    }

    private HttpServer authenticatedServer(ManagedIngressRegistry registry, String tenant)
            throws Exception {
        var server = httpServer();
        registry.bind(server, handler -> exchange -> {
            exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                    new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer", tenant,
                            Set.of(), Set.of("invoke")));
            handler.handle(exchange);
        });
        return server;
    }

    private static int rawStatus(int port, String target, List<String> headers) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    Math.toIntExact(HTTP_TIMEOUT.toMillis()));
            socket.setSoTimeout(2_000);
            String extra = headers.isEmpty() ? "" : String.join("\r\n", headers) + "\r\n";
            socket.getOutputStream().write(("POST " + target + " HTTP/1.1\r\nHost: localhost\r\n"
                    + extra + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
            assertNotNull(status, "server closed without a bounded refusal for " + target);
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static HttpResponse<Void> post(HttpClient client, String uri, byte[] body) throws Exception {
        return client.send(request(URI.create(uri))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("test lifecycle hook timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test lifecycle hook interrupted", interrupted);
        }
    }

    private static int virtualThreadSchedulerParallelism() {
        return Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                Runtime.getRuntime().availableProcessors());
    }

    private static void shutdown(java.util.concurrent.ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS),
                        "test executor must terminate within its cleanup budget");
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new AssertionError("test executor cleanup was interrupted", interrupted);
        }
    }
}
