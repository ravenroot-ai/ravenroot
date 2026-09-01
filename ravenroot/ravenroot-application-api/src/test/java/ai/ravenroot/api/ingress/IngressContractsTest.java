package ai.ravenroot.api.ingress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IngressContractsTest {
    @Test void requestAndResponseDefensivelyOwnTheirBodies() {
        byte[] requestBytes = {1};
        var request = new IngressRequest(new IngressPrincipal("tenant", "subject", "issuer", "USER"),
                "POST", "/orders", Map.of(), requestBytes);
        requestBytes[0] = 2;
        assertArrayEquals(new byte[] {1}, request.body());
        request.body()[0] = 3;
        assertArrayEquals(new byte[] {1}, request.body());

        byte[] responseBytes = {4};
        var response = new IngressResponse(202, Map.of(), responseBytes);
        responseBytes[0] = 5;
        assertArrayEquals(new byte[] {4}, response.body());
        response.body()[0] = 6;
        assertArrayEquals(new byte[] {4}, response.body());
    }

    @Test void requestDeeplyOwnsOrderedQueryAndRetainsLegacyConstructor() throws Exception {
        var values = new java.util.ArrayList<>(List.of("one"));
        var query = new java.util.LinkedHashMap<String, List<String>>();
        query.put("tag", values);
        var request = new IngressRequest(new IngressPrincipal("tenant", "subject", "issuer", "USER"),
                "GET", "/orders", query, Map.of("x-idempotency-key", "abc"), new byte[0]);
        values.add("two");
        query.put("other", List.of("value"));

        assertEquals(Map.of("tag", List.of("one")), request.query());
        assertThrows(UnsupportedOperationException.class,
                () -> request.query().get("tag").add("three"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.query().put("other", List.of("value")));

        var legacy = new IngressRequest(request.principal(), "GET", "/orders", Map.of(), new byte[0]);
        assertEquals(Map.of(), legacy.query());
        assertNotNull(IngressRequest.class.getConstructor(IngressPrincipal.class, String.class, String.class,
                Map.class, byte[].class), "the original constructor descriptor remains public");
    }

    @Test void projectionPolicyCanonicalizesAllowlistAndFailsClosedAtEveryBoundary() {
        var policy = new IngressRequestProjectionPolicy("example.oas",
                Set.of("Content-Type", "X-Idempotency-Key"), "x-IDEMPOTENCY-key",
                32, 4, 64, 2, 64, 32);
        assertEquals(Set.of("content-type", "x-idempotency-key"), policy.allowedHeaders());
        assertEquals("x-idempotency-key", policy.idempotencyHeader());

        for (String forbidden : List.of("Authorization", "Cookie", "Host", "Connection",
                "Transfer-Encoding", "X-Api-Key")) {
            assertThrows(IllegalArgumentException.class, () -> new IngressRequestProjectionPolicy(
                    "example.oas", Set.of(forbidden), null, 32, 4, 64, 1, 64, 32), forbidden);
        }
        assertThrows(IllegalArgumentException.class, () -> new IngressRequestProjectionPolicy(
                "example.oas", Set.of("X-Idempotency-Key"), "X-Other",
                32, 4, 64, 1, 64, 32));
        assertThrows(IllegalArgumentException.class, () -> new IngressRequestProjectionPolicy(
                "example.oas", Set.of("X-One", "x-one"), null,
                32, 4, 64, 2, 64, 32));
        assertThrows(IllegalArgumentException.class, () -> new IngressRequestProjectionPolicy(
                "example.oas", Set.of(), null,
                IngressRequestProjectionPolicy.HARD_MAX_RELATIVE_PATH_BYTES + 1,
                4, 64, 1, 64, 32));
    }

    @Test void legacyRouteAuthorityRemainsFunctionalAndPrefixDefaultFailsClosed() {
        IngressRouteAuthority legacy = (routeId, relativePath, methods, handler) -> null;
        assertThrows(UnsupportedOperationException.class, () -> legacy.acquirePrefix(
                "route", "/route", Set.of("GET"), request -> null));
        assertTrue(IngressRouteAuthority.class.isAnnotationPresent(FunctionalInterface.class)
                        || java.util.Arrays.stream(IngressRouteAuthority.class.getMethods())
                        .filter(method -> java.lang.reflect.Modifier.isAbstract(method.getModifiers()))
                        .count() == 1,
                "adding prefix dispatch must retain the legacy SAM linkage shape");
    }

    @Test void addingTheRequestContextKeepsTheHandlerALambdaAndCarriesEveryLegacyImplementation()
            throws Exception {
        // The whole ergonomic value of this interface is that a package writes `request -> …`. If the
        // context had been a second abstract method, every such package would have stopped compiling.
        IngressRouteHandler lambda = request -> java.util.concurrent.CompletableFuture
                .completedFuture(new IngressResponse(200, Map.of(), new byte[] {1}));
        assertEquals(1, java.util.Arrays.stream(IngressRouteHandler.class.getMethods())
                .filter(method -> java.lang.reflect.Modifier.isAbstract(method.getModifiers()))
                .count(), "the SAM must stay handle(IngressRequest)");

        var request = new IngressRequest(new IngressPrincipal("tenant", "subject", "issuer", "USER"),
                "POST", "/orders", Map.of(), new byte[0]);
        var context = new IngressRequestContext(java.time.Instant.now().plusSeconds(30), neverCancelled());
        assertArrayEquals(new byte[] {1}, lambda.handle(request, context).toCompletableFuture()
                .get(2, java.util.concurrent.TimeUnit.SECONDS).body(),
                "the delegating default carries a context-blind handler unchanged");
    }

    @Test void theRequestContextIsAWholeWindowOrNothing() {
        assertThrows(NullPointerException.class,
                () -> new IngressRequestContext(null, neverCancelled()));
        assertThrows(NullPointerException.class,
                () -> new IngressRequestContext(java.time.Instant.now(), null));

        var expired = new IngressRequestContext(java.time.Instant.now().minusSeconds(1), neverCancelled());
        assertEquals(Duration.ZERO, expired.remaining(), "a spent budget floors at zero, never negative");
        assertFalse(expired.live());

        var open = new IngressRequestContext(java.time.Instant.now().plusSeconds(30), neverCancelled());
        assertTrue(open.live());
        assertFalse(open.remaining().isZero());
        assertTrue(open.remaining().compareTo(Duration.ofSeconds(30)) <= 0);

        var cancelled = new IngressRequestContext(java.time.Instant.now().plusSeconds(30),
                new ai.ravenroot.api.execution.CancellationSignal() {
                    @Override public boolean cancelled() { return true; }
                    @Override public void onCancel(Runnable listener) { listener.run(); }
                });
        assertFalse(cancelled.live(), "budget remaining is not the same question as still wanted");
    }

    private static ai.ravenroot.api.execution.CancellationSignal neverCancelled() {
        return new ai.ravenroot.api.execution.CancellationSignal() {
            @Override public boolean cancelled() { return false; }
            @Override public void onCancel(Runnable listener) { }
        };
    }

    @Test void ownerGenerationAndDeclarationHardCeilingsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new IngressRouteOwner("package", "tenant", "deployment", "node", 0));
        assertThrows(IllegalArgumentException.class, () -> new IngressAuthorityDeclaration(
                "package", "main", "/managed", Set.of(),
                IngressAuthorityDeclaration.HARD_MAX_ROUTES + 1, 1, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new IngressAuthorityDeclaration(
                "package", "main", "/managed", Set.of(), 1, 1, 1, 1,
                IngressAuthorityDeclaration.HARD_MAX_TIMEOUT.plusMillis(1)));
    }
}
