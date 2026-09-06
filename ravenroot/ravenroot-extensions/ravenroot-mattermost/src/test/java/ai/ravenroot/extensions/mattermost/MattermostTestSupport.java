package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MattermostTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "operations";
    static final String TEAM = "aaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String CHANNEL = "bbbbbbbbbbbbbbbbbbbbbbbbbb";
    static final String OTHER_CHANNEL = "cccccccccccccccccccccccccc";
    static final String USER = "dddddddddddddddddddddddddd";
    static final String POST = "eeeeeeeeeeeeeeeeeeeeeeeeee";
    static final String TOKEN = "fixture-outgoing-token";
    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    static MattermostConfiguration configuration(Path store) { return configuration(store, 1_048_576, 100); }
    static MattermostConfiguration configuration(Path store, int requestBytes, int maxDeliveries) {
        return configuration(store, requestBytes, maxDeliveries, 20);
    }
    static MattermostConfiguration configuration(Path store, int requestBytes, int maxDeliveries, int maxPerSecond) {
        var authority = new IngressAuthorityDeclaration(MattermostConfiguration.PACKAGE_ID, "main",
                "/managed/mattermost", Set.of("mattermost:callbacks"), 8, 32, 1_048_576, 65_536,
                Duration.ofMillis(2_800));
        var projection = new IngressRequestProjectionPolicy(MattermostConfiguration.PACKAGE_ID,
                Set.of("content-type"), null, 256, 1, 256, 1, 1024, 512);
        var profile = new MattermostProfile(TENANT, PROFILE, URI.create("https://mattermost.example.test"),
                TEAM, Set.of(CHANNEL), "mattermost-bearer", "mattermost-bot-token",
                "mattermost-outgoing-token", "/outgoing", 4_000, requestBytes, 65_536, 2,
                maxPerSecond, 2_500, 1);
        return new MattermostConfiguration(authority, projection,
                new MattermostConfiguration.StorePolicy(store, maxDeliveries, 24),
                Map.of(TENANT + "\u0000" + PROFILE, profile));
    }
    static MattermostNodePackage nodePackage(Path store) {
        MattermostConfiguration config = configuration(store);
        return new MattermostNodePackage(config,
                new SqliteMattermostDeliveryStore(config.store(), fixedClock()), fixedClock());
    }
    static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    static NodeBehavior behavior(MattermostNodePackage nodePackage, String id) {
        return nodePackage.behaviors().stream().filter(value -> value.descriptor().behavior().equals(id))
                .findFirst().orElseThrow();
    }
    static NodeConfiguration node(String behavior) {
        return new NodeConfiguration("mattermost", behavior, Map.of("mattermostProfile", PROFILE));
    }
    static NodeMessage message(Object payload) {
        return new NodeMessage(new SecurityContext("request", TENANT, "operator", PrincipalType.WORKLOAD, "test"),
                UUID.randomUUID(), UUID.randomUUID(), "mattermost", payload, Map.of());
    }
    static final class HttpHarness implements NodePackageServices {
        final ArrayDeque<OutboundHttpResponse> replies = new ArrayDeque<>();
        final List<OutboundHttpRequest> requests = new ArrayList<>();
        volatile OutboundCall<OutboundHttpResponse> pending;
        HttpHarness reply(int status, Map<String, List<String>> headers, Object body) {
            replies.add(new OutboundHttpResponse(status, headers,
                    MattermostValues.jsonBytes(MattermostValues.object(body)), 65_536)); return this;
        }
        HttpHarness reply(int status, Object body) {
            return reply(status, Map.of("content-type", List.of("application/json")), body);
        }
        @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                synchronized (requests) { requests.add(request); }
                if (pending != null) return pending;
                OutboundHttpResponse response = replies.poll();
                return response == null ? OutboundCall.failed(new AssertionError("unexpected Mattermost call"))
                        : OutboundCall.completed(response);
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }
    private MattermostTestSupport() { }
}
