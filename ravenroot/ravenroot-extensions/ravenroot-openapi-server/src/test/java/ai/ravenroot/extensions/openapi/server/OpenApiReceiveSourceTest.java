package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiReceiveSourceTest {
    @Test void routeExistsOnlyAfterManagedActivationAndStopsGenerationLease() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder"));
        fixture.source.start(fixture.context).toCompletableFuture().join();
        assertNull(fixture.authority.handler);
        fixture.source.activateManagedIngress(fixture.authority).toCompletableFuture().join();
        assertEquals("/api", fixture.authority.path);
        assertEquals(java.util.Set.of("POST"), fixture.authority.methods);
        fixture.source.stop().toCompletableFuture().join();
        fixture.source.stop().toCompletableFuture().join();
        assertEquals(1, fixture.authority.releases.get());
    }

    @Test void rollbackAndRestartFenceTheOldHandlerAndAcquireFreshLease() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder"));
        fixture.activate();
        ai.ravenroot.api.ingress.IngressRouteHandler oldHandler = fixture.authority.handler;
        fixture.source.rollback().toCompletableFuture().join();
        assertEquals(1, fixture.authority.releases.get());
        assertEquals(503, oldHandler.handle(OpenApiServerTestSupport.request("/orders/42", Map.of(),
                Map.of("idempotency-key", "key", "x-trace", "trace"),
                "{\"amount\":2}".getBytes(StandardCharsets.UTF_8))).toCompletableFuture().join().status());
        fixture.source.start(fixture.context).toCompletableFuture().join();
        fixture.source.activateManagedIngress(fixture.authority).toCompletableFuture().join();
        assertEquals(2, fixture.authority.acquisitions.get());
        assertEquals(503, oldHandler.handle(OpenApiServerTestSupport.request("/orders/42", Map.of(),
                Map.of("idempotency-key", "old", "x-trace", "trace"),
                "{\"amount\":2}".getBytes(StandardCharsets.UTF_8))).toCompletableFuture().join().status());
        assertEquals(0, fixture.ingress.calls.get());
        assertEquals(202, fixture.invoke().status());
    }

    @Test void committedAndDuplicateAreTheOnlySuccessReceipts() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder"));
        fixture.activate();
        assertEquals(202, fixture.invoke().status());
        assertEquals("createOrder:key-1", fixture.ingress.key.get());
        fixture.ingress.receipt = new IngressReceipt.Duplicate("stored");
        assertEquals(202, fixture.invoke().status());
        fixture.ingress.receipt = new IngressReceipt.VolatileCustody();
        assertEquals(503, fixture.invoke().status());
        fixture.ingress.receipt = new IngressReceipt.Ambiguous("stored", "unknown");
        assertEquals(503, fixture.invoke().status());
        fixture.ingress.receipt = new IngressReceipt.Refused("buffer full");
        assertEquals(429, fixture.invoke().status());
        fixture.ingress.receipt = new IngressReceipt.Refused("not ready");
        assertEquals(503, fixture.invoke().status());
    }

    @Test void payloadPreservesServerIdentityAndValidatedProjectionStructurally() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder"));
        fixture.activate(); fixture.invoke();
        Map<?, ?> payload = assertInstanceOf(Map.class, fixture.ingress.payload.get());
        assertEquals("openapi.request.v1", payload.get("version"));
        assertEquals("createOrder", payload.get("operationId"));
        Map<?, ?> principal = assertInstanceOf(Map.class, payload.get("principal"));
        assertEquals("tenant-a", principal.get("tenantId"));
        assertEquals("alice", principal.get("subject"));
        assertEquals(Map.of("id", "42"), payload.get("path"));
        assertEquals(Map.of("verbose", true), payload.get("query"));
        assertEquals(Map.of("x-trace", "trace"), payload.get("headers"));
    }

    @Test void tenantAndPrincipalTypeAreNeverTakenFromBodyOrGraph() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder")); fixture.activate();
        IngressRequest wrongTenant = new IngressRequest(new IngressPrincipal("tenant-b", "alice", "issuer", "USER"),
                "POST", "/orders/42", Map.of("verbose", List.of("true")),
                Map.of("idempotency-key", "key-1", "x-trace", "trace"), "{\"amount\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, fixture.authority.invoke(wrongTenant).status());
        IngressRequest wrongType = new IngressRequest(new IngressPrincipal("tenant-a", "alice", "issuer", "WORKLOAD"),
                "POST", "/orders/42", Map.of(), Map.of("idempotency-key", "key-1", "x-trace", "trace"),
                "{\"amount\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, fixture.authority.invoke(wrongType).status());
        assertNull(fixture.ingress.payload.get());
    }

    @Test void invalidRequestsNeverReachDurableAdmission() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder",
                "maxRequestBytes", "15", "maxIdempotencyBytes", "5")); fixture.activate();
        assertEquals(400, fixture.authority.invoke(OpenApiServerTestSupport.request("/orders/42", Map.of(),
                Map.of("idempotency-key", "too-long", "x-trace", "trace"),
                "{\"amount\":2}".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(413, fixture.authority.invoke(OpenApiServerTestSupport.request("/orders/42", Map.of(),
                Map.of("idempotency-key", "key", "x-trace", "trace"), new byte[16])).status());
        assertEquals(404, fixture.authority.invoke(OpenApiServerTestSupport.request("/other", Map.of(),
                Map.of("idempotency-key", "key"), new byte[0])).status());
        assertNull(fixture.ingress.payload.get());
    }

    @Test void profileAndGraphConcurrencyBoundsDoNotLeakPermits() throws Exception {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder",
                "maxConcurrency", "1")); fixture.activate();
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        fixture.ingress.onOffer = () -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(e); } };
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> fixture.invoke().status());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(429, fixture.invoke().status());
        release.countDown(); assertEquals(202, first.get(2, TimeUnit.SECONDS));
        assertEquals(202, fixture.invoke().status());
    }

    @Test void deadlineAfterDurableHandoffIsNotReportedAsSuccess() {
        Fixture fixture = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder", "deadlineMs", "1"));
        fixture.activate(); fixture.ingress.onOffer = () -> {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        assertEquals(503, fixture.invoke().status());
        assertFalse(fixture.ingress.key.get().isBlank());
    }

    @Test void compileAndCollisionFailuresPublishNoRouteAndRemainSanitized() {
        byte[] badSpec = "{}".getBytes(StandardCharsets.UTF_8);
        OpenApiServerProfile bad = new OpenApiServerProfile("bad", badSpec, OpenApiServerTestSupport.sha256(badSpec),
                "/bad", java.util.Set.of("x"), java.util.Set.of("USER"), "idempotency-key", null, 100, 20, 100, 1);
        OpenApiServerConfiguration config = new OpenApiServerConfiguration(OpenApiServerTestSupport.configuration().authority(),
                OpenApiServerTestSupport.configuration().projection(), Map.of("bad", bad));
        OpenApiReceiveNodeBehavior behavior = new OpenApiReceiveNodeBehavior(() -> config);
        var source = (ai.ravenroot.api.node.ManagedIngressSource) behavior.createSource(
                new NodeConfiguration("node", OpenApiReceiveNodeBehavior.BEHAVIOR, Map.of("apiProfile", "bad")),
                new OpenApiServerTestSupport.FakeContext(new OpenApiServerTestSupport.FakeIngress()));
        Throwable compile = assertThrows(java.util.concurrent.CompletionException.class,
                () -> source.start(new OpenApiServerTestSupport.FakeContext(new OpenApiServerTestSupport.FakeIngress()))
                        .toCompletableFuture().join()).getCause();
        assertEquals(OpenApiServerException.Code.CONFIGURATION_INVALID,
                assertInstanceOf(OpenApiServerException.class, compile).code());

        Fixture collision = new Fixture(Map.of("apiProfile", "orders", "operations", "createOrder"));
        collision.source.start(collision.context).toCompletableFuture().join(); collision.authority.refuse = true;
        Throwable rejected = assertThrows(java.util.concurrent.CompletionException.class,
                () -> collision.source.activateManagedIngress(collision.authority).toCompletableFuture().join()).getCause();
        assertEquals(OpenApiServerException.Code.ROUTE_UNAVAILABLE,
                assertInstanceOf(OpenApiServerException.class, rejected).code());
        assertEquals(0, collision.authority.acquisitions.get());
    }

    private static final class Fixture {
        final OpenApiServerTestSupport.FakeIngress ingress = new OpenApiServerTestSupport.FakeIngress();
        final OpenApiServerTestSupport.FakeContext context = new OpenApiServerTestSupport.FakeContext(ingress);
        final OpenApiServerTestSupport.FakeAuthority authority = new OpenApiServerTestSupport.FakeAuthority();
        final ai.ravenroot.api.node.ManagedIngressSource source;
        Fixture(Map<String, Object> properties) {
            OpenApiReceiveNodeBehavior behavior = new OpenApiReceiveNodeBehavior(OpenApiServerTestSupport::configuration);
            source = (ai.ravenroot.api.node.ManagedIngressSource) ((InboundSourceCapable) behavior).createSource(
                    new NodeConfiguration("openapi-node", OpenApiReceiveNodeBehavior.BEHAVIOR, properties), context);
        }
        void activate() { source.start(context).toCompletableFuture().join(); source.activateManagedIngress(authority).toCompletableFuture().join(); }
        ai.ravenroot.api.ingress.IngressResponse invoke() {
            return authority.invoke(OpenApiServerTestSupport.request("/orders/42", Map.of("verbose", List.of("true")),
                    Map.of("idempotency-key", "key-1", "x-trace", "trace"),
                    "{\"amount\":2}".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
