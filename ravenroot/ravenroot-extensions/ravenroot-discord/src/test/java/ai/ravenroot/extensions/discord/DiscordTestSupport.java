package ai.ravenroot.extensions.discord;

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

import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
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

final class DiscordTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "operations";
    static final String APPLICATION = "123456789012345678";
    static final String GUILD = "223456789012345678";
    static final String CHANNEL = "323456789012345678";
    static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String PRIVATE_KEY = "302e020100300506032b65700422042094a9abfcb2d69d1284128380929b5afa1a5ca3ff700d205cac90f34ad11620af";
    private static final String PUBLIC_KEY = "eb6fa3a04b766ee3ef693301641cc4f20870b87b0c9d077665ca1339106585b3";

    static DiscordConfiguration configuration(Path store) {
        var authority = new IngressAuthorityDeclaration(DiscordConfiguration.PACKAGE_ID, "main", "/managed/discord",
                Set.of("discord:interactions"), 8, 32, 1_048_576, 65_536, Duration.ofMillis(2_500));
        var projection = new IngressRequestProjectionPolicy(DiscordConfiguration.PACKAGE_ID,
                Set.of("x-signature-ed25519", "x-signature-timestamp"), null, 256, 1, 256, 2, 512, 256);
        var profile = new DiscordProfile(TENANT, PROFILE, DiscordProfile.PRODUCTION_ORIGIN, APPLICATION,
                java.util.HexFormat.of().parseHex(PUBLIC_KEY), Set.of(GUILD), Map.of(GUILD, Set.of(CHANNEL)),
                Set.of("deploy"), "discord-bot", "discord-bot-token", "/interactions",
                2_000, 1_048_576, 65_536, 2_000, 1_048_576, 4, 2, 20, 2, 300, 30);
        return new DiscordConfiguration(authority, projection,
                new DiscordConfiguration.StorePolicy(store, 100, 24),
                Map.of(TENANT + "\u0000" + PROFILE, profile));
    }

    static DiscordNodePackage nodePackage(Path store) {
        DiscordConfiguration config = configuration(store);
        return new DiscordNodePackage(config, new SqliteDiscordDeliveryStore(config.store(), fixedClock()), fixedClock());
    }
    static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    static NodeBehavior behavior(DiscordNodePackage nodePackage, String id) {
        return nodePackage.behaviors().stream().filter(value -> value.descriptor().behavior().equals(id)).findFirst().orElseThrow();
    }
    static NodeConfiguration node(String behavior) {
        return new NodeConfiguration("discord", behavior, Map.of("discordProfile", PROFILE));
    }
    static NodeMessage message(Object payload) {
        return new NodeMessage(new SecurityContext("request", TENANT, "operator", PrincipalType.WORKLOAD, "test"),
                UUID.randomUUID(), UUID.randomUUID(), "discord", payload, Map.of());
    }
    static byte[] command(String interaction, String command) {
        return DiscordValues.jsonBytes(Map.of("id", interaction, "application_id", APPLICATION, "type", 2L,
                "token", "must-not-survive", "guild_id", GUILD, "channel_id", CHANNEL,
                "data", Map.of("id", "423456789012345678", "name", command, "type", 1L,
                        "options", List.of(Map.of("name", "environment", "type", 3L, "value", "staging")))));
    }
    static String signature(String timestamp, byte[] body) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(java.util.HexFormat.of().parseHex(PRIVATE_KEY)));
            Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key);
            signature.update(timestamp.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); signature.update(body);
            return java.util.HexFormat.of().formatHex(signature.sign());
        } catch (java.security.GeneralSecurityException failure) { throw new AssertionError(failure); }
    }

    static final class HttpHarness implements NodePackageServices {
        final ArrayDeque<OutboundHttpResponse> replies = new ArrayDeque<>();
        final List<OutboundHttpRequest> requests = new ArrayList<>();
        volatile OutboundCall<OutboundHttpResponse> pending;
        HttpHarness reply(int status, Map<String, List<String>> headers, Object body) {
            replies.add(new OutboundHttpResponse(status, headers, DiscordValues.jsonBytes((Map<String, ?>) body), 65_536)); return this;
        }
        HttpHarness reply(int status, Object body) { return reply(status, Map.of("content-type", List.of("application/json")), body); }
        @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() { return NodePackageServices.unavailable().credentials(); }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                synchronized (requests) { requests.add(request); }
                if (pending != null) return pending;
                OutboundHttpResponse reply = replies.poll();
                return reply == null ? OutboundCall.failed(new AssertionError("unexpected Discord call")) : OutboundCall.completed(reply);
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }
    private DiscordTestSupport() { }
}
