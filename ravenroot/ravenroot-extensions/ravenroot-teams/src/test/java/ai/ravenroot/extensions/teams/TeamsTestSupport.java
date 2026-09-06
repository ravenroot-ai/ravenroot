package ai.ravenroot.extensions.teams;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TeamsTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "operations";
    static final String MICROSOFT_TENANT = "4c7b3e37-289e-4cc3-98ea-e865f40d45bb";
    static final String TEAM = "19:team-id@thread.tacv2";
    static final String CHANNEL = "19:channel-id@thread.tacv2";
    static final String USER = "29:user-id";
    static final byte[] SECRET_BYTES = "fixture-signing-key-32-bytes!!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static final String SECRET = Base64.getEncoder().encodeToString(SECRET_BYTES);
    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    static final URI WORKFLOW = URI.create("https://example.logic.azure.com/workflows/operations/triggers/manual/paths/invoke");

    static TeamsConfiguration configuration(Path store) { return configuration(store, 1_048_576, 1_048_576); }
    static TeamsConfiguration configuration(Path store, int profileRequestBytes, long authorityRequestBytes) {
        var authority = new IngressAuthorityDeclaration(TeamsConfiguration.PACKAGE_ID, "main", "/managed/teams",
                Set.of("teams:callbacks"), 8, 32, authorityRequestBytes, 65_536, Duration.ofMillis(4_500));
        var projection = new IngressRequestProjectionPolicy(TeamsConfiguration.PACKAGE_ID,
                Set.of("content-type", "x-ravenroot-teams-signature"), null,
                256, 1, 256, 2, 1024, 512);
        var profile = new TeamsProfile(TENANT, PROFILE, WORKFLOW, MICROSOFT_TENANT, TEAM,
                Set.of(CHANNEL), "teams-workflow", "teams-workflow-token", "teams-signing-secret",
                "/outgoing", 2_500, profileRequestBytes, 65_536, 4_000, 2, 20, 4_000, 300);
        return new TeamsConfiguration(authority, projection,
                new TeamsConfiguration.StorePolicy(store, 100, 24), Map.of(TENANT + "\u0000" + PROFILE, profile));
    }

    static TeamsNodePackage nodePackage(Path store) { return nodePackage(store, 1_048_576); }
    static TeamsNodePackage nodePackage(Path store, int maximumRequestBytes) {
        TeamsConfiguration config = configuration(store, maximumRequestBytes, 1_048_576);
        return new TeamsNodePackage(config, new SqliteTeamsDeliveryStore(config.store(), fixedClock()), fixedClock());
    }
    static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    static NodeBehavior behavior(TeamsNodePackage nodePackage, String id) {
        return nodePackage.behaviors().stream().filter(value -> value.descriptor().behavior().equals(id))
                .findFirst().orElseThrow();
    }
    static NodeConfiguration node(String behavior) {
        return new NodeConfiguration("teams", behavior, Map.of("teamsProfile", PROFILE));
    }
    static NodeMessage message(Object payload) {
        return new NodeMessage(new SecurityContext("request", TENANT, "operator", PrincipalType.WORKLOAD, "test"),
                UUID.randomUUID(), UUID.randomUUID(), "teams", payload, Map.of());
    }
    static String signature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_BYTES, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    static final class HttpHarness implements NodePackageServices {
        final ArrayDeque<OutboundHttpResponse> replies = new ArrayDeque<>();
        final List<OutboundHttpRequest> requests = new ArrayList<>();
        volatile OutboundCall<OutboundHttpResponse> pending;

        HttpHarness reply(int status, Map<String, List<String>> headers, byte[] body) {
            replies.add(new OutboundHttpResponse(status, headers, body, 65_536)); return this;
        }
        HttpHarness reply(int status) {
            return reply(status, Map.of("content-type", List.of("application/json")), new byte[0]);
        }
        @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                synchronized (requests) { requests.add(request); }
                if (pending != null) return pending;
                OutboundHttpResponse reply = replies.poll();
                return reply == null ? OutboundCall.failed(new AssertionError("unexpected Teams call"))
                        : OutboundCall.completed(reply);
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }

    private TeamsTestSupport() { }
}
