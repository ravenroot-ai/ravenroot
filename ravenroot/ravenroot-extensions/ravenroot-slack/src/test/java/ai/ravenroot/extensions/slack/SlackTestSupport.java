package ai.ravenroot.extensions.slack;

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
import java.nio.charset.StandardCharsets;
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

final class SlackTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "operations";
    static final String TEAM = "T01234567";
    static final String APPLICATION = "A01234567";
    static final String CHANNEL = "C01234567";
    static final String USER = "U01234567";
    static final String SECRET = "fixture-signing-secret";
    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    static SlackConfiguration configuration(Path store) { return configuration(store, 1_048_576, 1_048_576); }
    static SlackConfiguration configuration(Path store, int profileRequestBytes, long authorityRequestBytes) {
        var authority = new IngressAuthorityDeclaration(SlackConfiguration.PACKAGE_ID, "main", "/managed/slack",
                Set.of("slack:callbacks"), 8, 32, authorityRequestBytes, 65_536, Duration.ofMillis(2_800));
        var projection = new IngressRequestProjectionPolicy(SlackConfiguration.PACKAGE_ID,
                Set.of("content-type", "x-slack-signature", "x-slack-request-timestamp",
                        "x-slack-retry-num", "x-slack-retry-reason"), null, 256, 1, 256, 5, 1024, 512);
        var profile = new SlackProfile(TENANT, PROFILE, SlackProfile.PRODUCTION_ORIGIN, TEAM, APPLICATION,
                "slack-bot", "slack-bot-token", "slack-signing-secret", "/events", "/commands",
                Set.of(CHANNEL), Set.of("message", "app_mention", "team_join", "user_change"), Set.of("/deploy"),
                Set.of("chat:write", "commands"), 2_500, profileRequestBytes, 65_536,
                4_000, 2, 20, 2, 300);
        return new SlackConfiguration(authority, projection,
                new SlackConfiguration.StorePolicy(store, 100, 24), Map.of(TENANT + "\u0000" + PROFILE, profile));
    }
    static SlackNodePackage nodePackage(Path store) { return nodePackage(store, 1_048_576); }
    static SlackNodePackage nodePackage(Path store, int maximumRequestBytes) {
        SlackConfiguration config = configuration(store, maximumRequestBytes, 1_048_576);
        return new SlackNodePackage(config, new SqliteSlackDeliveryStore(config.store(), fixedClock()), fixedClock());
    }
    static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    static NodeBehavior behavior(SlackNodePackage nodePackage, String id) {
        return nodePackage.behaviors().stream().filter(value -> value.descriptor().behavior().equals(id)).findFirst().orElseThrow();
    }
    static NodeConfiguration node(String behavior) {
        return new NodeConfiguration("slack", behavior, Map.of("slackProfile", PROFILE));
    }
    static NodeMessage message(Object payload) {
        return new NodeMessage(new SecurityContext("request", TENANT, "operator", PrincipalType.WORKLOAD, "test"),
                UUID.randomUUID(), UUID.randomUUID(), "slack", payload, Map.of());
    }
    static String timestamp() { return Long.toString(NOW.getEpochSecond()); }
    static String signature(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            return "v0=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    static final class HttpHarness implements NodePackageServices {
        final ArrayDeque<OutboundHttpResponse> replies = new ArrayDeque<>();
        final List<OutboundHttpRequest> requests = new ArrayList<>();
        volatile OutboundCall<OutboundHttpResponse> pending;
        HttpHarness reply(int status, Map<String, List<String>> headers, Object body) {
            replies.add(new OutboundHttpResponse(status, headers, SlackValues.jsonBytes(SlackValues.object(body)), 65_536)); return this;
        }
        HttpHarness reply(int status, Object body) {
            return reply(status, Map.of("content-type", List.of("application/json")), body);
        }
        @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() { return NodePackageServices.unavailable().credentials(); }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                synchronized (requests) { requests.add(request); }
                if (pending != null) return pending;
                OutboundHttpResponse reply = replies.poll();
                return reply == null ? OutboundCall.failed(new AssertionError("unexpected Slack call")) : OutboundCall.completed(reply);
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }
    private SlackTestSupport() { }
}
