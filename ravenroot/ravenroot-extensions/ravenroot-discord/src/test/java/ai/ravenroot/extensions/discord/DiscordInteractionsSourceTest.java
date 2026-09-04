package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordInteractionsSourceTest {
    @TempDir Path directory;

    @Test void verifiesSignatureAndFreshnessBeforeParseOrDurableEffect() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress, nodePackage());
        byte[] malformed = "not-json".getBytes(StandardCharsets.UTF_8);
        assertEquals(401, invoke(route, malformed, "0".repeat(128), timestamp()).status());
        assertEquals(401, invoke(route, malformed, DiscordTestSupport.signature(Long.toString(
                DiscordTestSupport.NOW.minusSeconds(301).getEpochSecond()), malformed),
                Long.toString(DiscordTestSupport.NOW.minusSeconds(301).getEpochSecond())).status());
        assertEquals(0, ingress.offers.get());
    }

    @Test void signedPingIsImmediateButCommandRequiresDurableCustodyAndStripsToken() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress, nodePackage());
        byte[] ping = DiscordValues.jsonBytes(Map.of("application_id", DiscordTestSupport.APPLICATION, "type", 1L));
        IngressResponse pong = invoke(route, ping, DiscordTestSupport.signature(timestamp(), ping), timestamp());
        assertEquals(200, pong.status()); assertTrue(new String(pong.body(), StandardCharsets.UTF_8).contains("\"type\":1"));
        assertEquals(0, ingress.offers.get());

        byte[] command = DiscordTestSupport.command("523456789012345678", "deploy");
        IngressResponse accepted = invoke(route, command, DiscordTestSupport.signature(timestamp(), command), timestamp());
        assertEquals(200, accepted.status()); assertTrue(new String(accepted.body(), StandardCharsets.UTF_8).contains("Accepted for processing"));
        assertEquals(1, ingress.offers.get());
        assertFalse(DiscordValues.json(DiscordValues.jsonBytes((Map<String, ?>) ingress.payload)).containsKey("token"));
        assertFalse(ingress.payload.toString().contains("must-not-survive"));
    }

    @Test void exactReplayReachesPlatformDedupAcrossSourceRestartButCollisionDoesNot() {
        DurableIngress ingress = new DurableIngress(); DiscordNodePackage nodePackage = nodePackage();
        byte[] command = DiscordTestSupport.command("623456789012345678", "deploy");
        CaptureRoute first = source(ingress, nodePackage);
        assertEquals(200, invoke(first, command, DiscordTestSupport.signature(timestamp(), command), timestamp()).status());
        first.source.stop().toCompletableFuture().join();
        CaptureRoute restarted = source(ingress, nodePackage);
        assertEquals(200, invoke(restarted, command, DiscordTestSupport.signature(timestamp(), command), timestamp()).status());
        assertEquals(2, ingress.offers.get()); assertEquals(1, ingress.keys.size());

        byte[] collision = DiscordTestSupport.command("623456789012345678", "other");
        assertEquals(403, invoke(restarted, collision, DiscordTestSupport.signature(timestamp(), collision), timestamp()).status(),
                "command authority is checked before replay storage");
        byte[] permittedCollision = DiscordValues.jsonBytes(Map.of("id", "623456789012345678",
                "application_id", DiscordTestSupport.APPLICATION, "type", 2L, "guild_id", DiscordTestSupport.GUILD,
                "channel_id", DiscordTestSupport.CHANNEL, "data", Map.of("name", "deploy", "type", 1L,
                "options", java.util.List.of(Map.of("name", "environment", "type", 3L, "value", "production")))));
        assertEquals(409, invoke(restarted, permittedCollision,
                DiscordTestSupport.signature(timestamp(), permittedCollision), timestamp()).status());
        assertEquals(2, ingress.offers.get());
    }

    @Test void refusesUnauthorizedAuthorityAndDoesNotAckVolatileCustody() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress, nodePackage());
        byte[] denied = DiscordTestSupport.command("723456789012345678", "unconfigured");
        assertEquals(403, invoke(route, denied, DiscordTestSupport.signature(timestamp(), denied), timestamp()).status());
        ingress.mode = Mode.VOLATILE;
        byte[] accepted = DiscordTestSupport.command("823456789012345678", "deploy");
        assertEquals(503, invoke(route, accepted, DiscordTestSupport.signature(timestamp(), accepted), timestamp()).status());
    }

    private DiscordNodePackage nodePackage() { return DiscordTestSupport.nodePackage(directory.resolve("deliveries.db")); }
    private static String timestamp() { return Long.toString(DiscordTestSupport.NOW.getEpochSecond()); }
    private static IngressResponse invoke(CaptureRoute route, byte[] body, String signature, String timestamp) {
        return route.handler.handle(new IngressRequest(new IngressPrincipal(DiscordTestSupport.TENANT, "relay", "test", "WORKLOAD"),
                "POST", "", Map.of("x-signature-ed25519", signature, "x-signature-timestamp", timestamp), body))
                .toCompletableFuture().join();
    }
    private static CaptureRoute source(DurableIngress ingress, DiscordNodePackage nodePackage) {
        InboundSourceCapable behavior = (InboundSourceCapable) DiscordTestSupport.behavior(nodePackage,
                DiscordBehaviorDescriptors.INTERACTIONS);
        Context context = new Context(ingress); InboundSource source = behavior.createSource(
                DiscordTestSupport.node(DiscordBehaviorDescriptors.INTERACTIONS), context);
        source.start(context).toCompletableFuture().join(); CaptureRoute capture = new CaptureRoute((ManagedIngressSource) source);
        ((ManagedIngressSource) source).activateManagedIngress(capture).toCompletableFuture().join(); return capture;
    }

    private static final class CaptureRoute implements ai.ravenroot.api.ingress.IngressRouteAuthority {
        final ManagedIngressSource source; IngressRouteHandler handler;
        CaptureRoute(ManagedIngressSource source) { this.source = source; }
        @Override public IngressRouteLease acquire(String id, String path, Set<String> methods, IngressRouteHandler handler) {
            assertEquals("/interactions", path); assertEquals(Set.of("POST"), methods); this.handler = handler;
            return new IngressRouteLease() {
                @Override public String routeId() { return id; }
                @Override public IngressRouteOwner owner() { return new IngressRouteOwner(
                        DiscordConfiguration.PACKAGE_ID, DiscordTestSupport.TENANT, "deployment", "discord", 1); }
                @Override public void release() { }
            };
        }
    }
    private static final class Context implements InboundSourceContext {
        private final DurableIngress ingress;
        private final SecurityContext identity = new SecurityContext("request", DiscordTestSupport.TENANT,
                "relay", PrincipalType.WORKLOAD, "test");
        Context(DurableIngress ingress) { this.ingress = ingress; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment"); }
        @Override public String nodeId() { return "discord"; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { fail(reason); }
        @Override public void reportHealthy() { }
    }
    private enum Mode { DURABLE, VOLATILE }
    private static final class DurableIngress implements TrustedIngress {
        final Set<String> keys = new java.util.HashSet<>(); final AtomicInteger offers = new AtomicInteger();
        volatile Mode mode = Mode.DURABLE; volatile Object payload;
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            throw new AssertionError("volatile offer is forbidden");
        }
        @Override public int bufferCapacity() { return 8; }
        @Override public ai.ravenroot.api.deployment.IngressOverflowPolicy overflowPolicy() {
            return ai.ravenroot.api.deployment.IngressOverflowPolicy.REJECT;
        }
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                                                                  Object payload, String sourceId, String key) {
            offers.incrementAndGet(); this.payload = payload;
            if (mode == Mode.VOLATILE) return new IngressReceipt.VolatileCustody();
            return keys.add(key) ? new IngressReceipt.DurablyCommitted(key) : new IngressReceipt.Duplicate(key);
        }
    }
}
