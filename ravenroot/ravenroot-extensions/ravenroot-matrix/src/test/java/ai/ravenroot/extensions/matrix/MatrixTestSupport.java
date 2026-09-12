package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class MatrixTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "operations";
    static final String USER = "@ravenroot:example.org";
    static final String ROOM = "!operations:example.org";
    static final URI ORIGIN = URI.create("https://matrix.example.org/");
    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    static MatrixConfiguration configuration(Path store) {
        return configuration(store, MatrixProfile.InitialSyncMode.DELIVER_BOUNDED, "seed-token");
    }
    static MatrixConfiguration configuration(Path store, MatrixProfile.InitialSyncMode mode, String initialSince) {
        return configuration(store, mode, initialSince, 2, 20);
    }
    static MatrixConfiguration configuration(Path store, MatrixProfile.InitialSyncMode mode, String initialSince,
                                             int concurrency, int perSecond) {
        MatrixProfile profile = new MatrixProfile(TENANT, PROFILE, ORIGIN, USER, Set.of(ROOM),
                Set.of("m.room.message"), "matrix-bearer", "matrix-access-token", 5_000,
                1_048_576, 1_048_576, 4_000, concurrency, perSecond, 1_000, 100, 10, mode, initialSince);
        return new MatrixConfiguration(new MatrixConfiguration.StorePolicy(store, 100, 24, 100),
                Map.of(TENANT + "\u0000" + PROFILE, profile));
    }
    static MatrixNodePackage nodePackage(Path store) {
        MatrixConfiguration configuration = configuration(store);
        return new MatrixNodePackage(configuration, new SqliteMatrixSyncStore(configuration.store(), fixedClock()),
                fixedClock());
    }
    static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    static NodeBehavior behavior(MatrixNodePackage nodePackage, String id) {
        return nodePackage.behaviors().stream().filter(value -> value.descriptor().behavior().equals(id))
                .findFirst().orElseThrow();
    }
    static NodeConfiguration node(String behavior) {
        return new NodeConfiguration("matrix", behavior, Map.of("matrixProfile", PROFILE));
    }
    static NodeMessage message(Object payload) {
        return new NodeMessage(new SecurityContext("request", TENANT, "operator", PrincipalType.WORKLOAD, "test"),
                UUID.randomUUID(), UUID.randomUUID(), "matrix", payload, Map.of());
    }

    static final class HttpHarness implements NodePackageServices {
        final ArrayDeque<OutboundHttpResponse> replies = new ArrayDeque<>();
        final List<OutboundHttpRequest> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile OutboundCall<OutboundHttpResponse> pending;
        HttpHarness reply(int status, Object body) {
            replies.add(new OutboundHttpResponse(status, Map.of("content-type", List.of("application/json")),
                    MatrixValues.jsonBytes(MatrixValues.object(body)), 1_048_576)); return this;
        }
        @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return new ai.ravenroot.api.node.service.OutboundHttpService() {
                @Override public OutboundCall<OutboundHttpResponse> execute(NodeMessage message, OutboundHttpRequest request) {
                    return take(request);
                }
                @Override public OutboundCall<OutboundHttpResponse> execute(InboundSourceContext context,
                                                                            OutboundHttpRequest request) {
                    return take(request);
                }
            };
        }
        private OutboundCall<OutboundHttpResponse> take(OutboundHttpRequest request) {
            requests.add(request);
            OutboundHttpResponse response = replies.poll();
            if (response != null) return OutboundCall.completed(response);
            if (pending != null) return pending;
            CompletableFuture<OutboundHttpResponse> waiting = new CompletableFuture<>();
            pending = new OutboundCall<>() {
                @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() { return waiting; }
                @Override public boolean cancel() { return waiting.cancel(true); }
            };
            return pending;
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }
    private MatrixTestSupport() { }
}
