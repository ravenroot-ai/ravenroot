package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionOperationalPolicyAuthorityTest {

    @Test
    void coreHttpUsesPinnedCapacityAcrossChangedDefaultsAndRefusesMissingAuthority() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String body = "x".repeat(2048);
            GraphNode node = new GraphNode("http", NodeKind.BEHAVIOR, "http-request", Map.of(
                    "url", "http://localhost:" + port + "/", "method", "POST", "body", body));

            var widerCurrent = coreRegistry(port, 4096);
            var narrowKey = new ExecutionKey("tenant-a", UUID.randomUUID());
            UUID narrowTraversal = UUID.randomUUID();
            widerCurrent.bindOperationalPolicy(narrowKey, narrowTraversal, policy(1024));
            var narrowHandler = widerCurrent.create(node).orElseThrow();
            assertThrows(SecurityException.class,
                    () -> narrowHandler.handle(message(narrowKey, narrowTraversal)));
            assertEquals(0, hits.get());

            widerCurrent.releaseOperationalPolicy(narrowTraversal);
            assertThrows(NodePackageServiceException.class,
                    () -> narrowHandler.handle(message(narrowKey, narrowTraversal)));

            var narrowerCurrent = coreRegistry(port, 1024);
            var wideKey = new ExecutionKey("tenant-a", UUID.randomUUID());
            UUID wideTraversal = UUID.randomUUID();
            narrowerCurrent.bindOperationalPolicy(wideKey, wideTraversal, policy(4096));
            var result = narrowerCurrent.create(node).orElseThrow()
                    .handle(message(wideKey, wideTraversal)).toCompletableFuture().join();
            assertEquals("continue", result.outcome());
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPinnedCapacityHigherThanTheCurrentDefaultIsUsedWithoutWeakeningLiveDestinationPolicy()
            throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var registry = new BehaviorRegistry();
            var startup = NodePackageEgressPolicy.builder()
                    .allowOrigin("http", "localhost", port).allowHttpMethod("POST")
                    .byteLimits(1024, 4096, 4096).build();
            var services = ManagedNodePackageServices.builder("test.package", startup,
                            (packageId, tenant, reference) -> java.util.Optional.empty())
                    .grant(NodePackageCapability.OUTBOUND_HTTP).build();
            services.bindExecutionPolicyResolver(registry::operationalPolicyFor);
            UUID process = UUID.randomUUID();
            UUID traversal = UUID.randomUUID();
            var key = new ExecutionKey("tenant-a", process);
            registry.bindOperationalPolicy(key, traversal, policy(4096));
            UUID lowerTraversal = UUID.randomUUID();
            var lowerKey = new ExecutionKey("tenant-a", UUID.randomUUID());
            registry.bindOperationalPolicy(lowerKey, lowerTraversal, policy(1024));
            var request = new OutboundHttpRequest(URI.create("http://localhost:" + port + "/"),
                    "POST", Map.of(), new byte[2048], Duration.ofSeconds(1), null);

            assertReason(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                    () -> services.outboundHttp().execute(message(lowerKey, lowerTraversal), request)
                            .completion().toCompletableFuture().join());

            var response = services.outboundHttp().execute(message(key, traversal), request)
                    .completion().toCompletableFuture().join();

            assertEquals(200, response.statusCode());
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callerConstructedMessagesCannotReplaceOrDropRuntimePinnedCapacity() {
        var registry = new BehaviorRegistry();
        var startup = NodePackageEgressPolicy.builder()
                .allowOrigin("http", "localhost", 1).allowHttpMethod("POST")
                .byteLimits(4096, 4096, 4096).build();
        var services = ManagedNodePackageServices.builder("test.package", startup,
                        (packageId, tenant, reference) -> java.util.Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP).build();
        services.bindExecutionPolicyResolver(registry::operationalPolicyFor);

        UUID process = UUID.randomUUID();
        UUID traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        registry.bindOperationalPolicy(key, traversal, policy(1024));
        NodeMessage forged = message(key, traversal);
        var request = new OutboundHttpRequest(URI.create("http://localhost:1/"), "POST", Map.of(),
                new byte[2048], Duration.ofSeconds(1), null);

        assertReason(NodePackageServiceException.Reason.REQUEST_TOO_LARGE,
                () -> services.outboundHttp().execute(forged, request).completion()
                        .toCompletableFuture().join());

        registry.releaseOperationalPolicy(traversal);
        assertReason(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE,
                () -> services.outboundHttp().execute(forged, request).completion()
                        .toCompletableFuture().join());

        registry.bindOperationalPolicy(key, traversal, null);
        assertReason(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE,
                () -> services.outboundHttp().execute(forged, request).completion()
                        .toCompletableFuture().join());
    }

    private static NodeMessage message(ExecutionKey key, UUID traversal) {
        SecurityContext security = new SecurityContext("request", key.tenantId(), "subject",
                PrincipalType.USER, "issuer");
        UUID invocation = UUID.randomUUID();
        return new NodeMessage(security, key.processInstanceId(), traversal, invocation, invocation,
                Set.of(), "node", null, Map.of());
    }

    private static ResolvedOperationalPolicy policy(long requestBytes) {
        var graph = new ResolvedOperationalPolicy.GraphLimits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        var capacity = NodePackageEgressCapacityProfile.bounded(requestBytes, 4096, 4096,
                4, 4, 4, 4, Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(1));
        return new ResolvedOperationalPolicy(graph,
                new ResolvedOperationalPolicy.ResultLimits(false, 4096),
                Optional.of(new ResolvedOperationalPolicy.BuiltInHttpCapacity(
                        requestBytes, 4096, Duration.ofSeconds(1))),
                List.of(new ResolvedOperationalPolicy.PackageCapacity("test.package", capacity)));
    }

    private static BehaviorRegistry coreRegistry(int port, long requestBytes) {
        var outbound = new OutboundHttpPolicy(Set.of("localhost"), Duration.ofSeconds(10),
                Set.of(port), 4096, requestBytes);
        return BehaviorRegistry.standard(new BehaviorEnvironment(null, null, null, null,
                ignored -> Optional.empty(), invocation -> new ToolDecision(
                        ToolDecision.Disposition.ALLOW, "allowed", ""), outbound));
    }

    private static void assertReason(NodePackageServiceException.Reason reason, Runnable operation) {
        CompletionException failure = assertThrows(CompletionException.class, operation::run);
        assertEquals(reason, ((NodePackageServiceException) failure.getCause()).reason());
    }
}
