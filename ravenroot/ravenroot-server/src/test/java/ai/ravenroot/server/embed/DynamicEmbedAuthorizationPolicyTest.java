package ai.ravenroot.server.embed;

import ai.ravenroot.api.application.DeploymentViewerView;
import ai.ravenroot.api.application.LocalDeploymentState;
import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicEmbedAuthorizationPolicyTest {
    @Test
    void policyIsDefaultOffAndRestrictedModeRequiresExactOrigins() {
        var viewer = new EmbedViewerOrigin("https://viewer.example");
        assertFalse(DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(), viewer).enabled());
        var restricted = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "restricted",
                DynamicEmbedAuthorizationPolicy.ORIGINS_VARIABLE,
                "https://app.example,http://127.0.0.1:8444"), viewer);
        assertEquals("https://app.example", restricted.requireAllowed("https://app.example", viewer));
        assertEquals("http://127.0.0.1:8444",
                restricted.requireAllowed("http://127.0.0.1:8444", viewer));
        assertThrows(IllegalArgumentException.class,
                () -> restricted.requireAllowed("https://other.example", viewer));
        var reordered = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "restricted",
                DynamicEmbedAuthorizationPolicy.ORIGINS_VARIABLE,
                "https://app.example,http://127.0.0.1:8444"), viewer);
        var sameOriginsDifferentOrder = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "restricted",
                DynamicEmbedAuthorizationPolicy.ORIGINS_VARIABLE,
                "http://127.0.0.1:8444,https://app.example"), viewer);
        assertEquals(reordered.revision(), sameOriginsDifferentOrder.revision(),
                "policy currency must be canonical rather than configuration-order dependent");
        var expanded = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "restricted",
                DynamicEmbedAuthorizationPolicy.ORIGINS_VARIABLE,
                "https://app.example,http://127.0.0.1:8444,https://second.example"), viewer);
        assertFalse(restricted.revision().equals(expanded.revision()),
                "changing the allowed-origin set must revoke grants under a new digest");
        assertThrows(IllegalArgumentException.class,
                () -> DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                        DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "restricted"), viewer));
    }

    @Test
    void authenticatedModeAllowsCanonicalSecureAndLoopbackOriginsOnly() {
        var viewer = new EmbedViewerOrigin("https://viewer.example");
        var policy = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "authenticated"), viewer);
        assertEquals("https://any.example:8443", policy.requireAllowed("https://any.example:8443", viewer));
        assertEquals("http://localhost:5173", policy.requireAllowed("http://localhost:5173", viewer));
        assertEquals("http://[::1]", policy.requireAllowed("http://[::1]", viewer));
        assertEquals("http://[0:0:0:0:0:0:0:1]:5173",
                policy.requireAllowed("http://[0:0:0:0:0:0:0:1]:5173", viewer));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed("http://public.example", viewer));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed("http://127.0.0.2:5173", viewer));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed("http://[2001:db8::1]:5173", viewer));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed("https://viewer.example", viewer));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed("https://any.example/path", viewer));
    }

    @Test
    void dynamicGrantIsFixedReadOnlyRevocableAndExpires() {
        var clock = new MutableClock(Instant.parse("2026-09-24T00:00:00Z"));
        var viewer = new EmbedViewerOrigin("https://viewer.example");
        var policy = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "authenticated"), viewer);
        var authority = new DynamicEmbedGrantAuthority(policy, viewer, clock, Duration.ofMinutes(2), 2);
        var context = new RequestContext("request", "host", PrincipalType.WORKLOAD, "issuer", "tenant",
                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
        var issued = authority.issue(context, "http://localhost:5173", view());
        assertFalse(issued.authorization().authorityId().equals(issued.value()),
                "only the grant digest may be retained by the authority");
        assertTrue(authority.isCurrent(issued.authorization()));
        assertEquals(Set.of(EmbedCapability.GRAPH_READ, EmbedCapability.DEPLOYMENT_OBSERVE,
                EmbedCapability.DEPLOYMENT_RUN_READ), issued.authorization().capabilities());
        assertFalse(issued.authorization().capabilities().contains(EmbedCapability.DEPLOYMENT_EXECUTE));
        assertNull(authority.resolve(new RequestContext("other", "other", PrincipalType.WORKLOAD,
                "issuer", "tenant", Set.of(Role.VIEWER), Set.of()), issued.value()));
        assertTrue(authority.revoke(context, issued.value()));
        assertFalse(authority.isCurrent(issued.authorization()));

        var expiring = authority.issue(context, "https://app.example", view());
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertFalse(authority.isCurrent(expiring.authorization()));
    }

    @Test
    void dynamicGrantPreBindsTheIncarnationAndCapsEveryCredentialLifetime() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-09-24T00:00:00Z"));
        var viewer = new EmbedViewerOrigin("https://viewer.example");
        var policy = DynamicEmbedAuthorizationPolicy.fromEnvironment(Map.of(
                DynamicEmbedAuthorizationPolicy.MODE_VARIABLE, "authenticated"), viewer);
        var authority = new DynamicEmbedGrantAuthority(policy, viewer, clock, Duration.ofSeconds(30), 2);
        var context = new RequestContext("request", "host", PrincipalType.WORKLOAD, "issuer", "tenant",
                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
        var grant = authority.issue(context, "https://app.example", view()).authorization();

        var tickets = new EmbedLaunchTicketAuthority(clock, Duration.ofMinutes(5), 2,
                () -> "t".repeat(43));
        assertEquals(grant.expiresAtInstant(), tickets.issue(grant).expiresAt());

        var values = new ArrayDeque<>(List.of("a".repeat(43), "b".repeat(43), "c".repeat(43),
                "d".repeat(43), "e".repeat(43), "f".repeat(43)));
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofMinutes(5),
                Duration.ofMinutes(5), 2, values::remove);
        var bootstrap = sessions.begin(grant);
        assertEquals(grant.expiresAtInstant(), bootstrap.expiresAt());
        assertTrue(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                "correlation", grant, authority::isCurrent, () -> { }));
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var bearer = sessions.activate(bootstrap.exchangeId(),
                sessions.acknowledged(bootstrap.exchangeId(), authority::isCurrent),
                (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic(),
                authority::isCurrent);
        assertEquals(grant.expiresAtInstant(), bearer.expiresAt());
        var active = sessions.resolve(bearer.bearer(), authority::isCurrent);
        assertEquals(view(), sessions.resolveBinding(active));
        var replacement = new DeploymentViewerView(DeploymentViewerView.CURRENT_SOURCE_VERSION,
                DeploymentViewerView.Source.deployment("orders", "replacement", "version"),
                LocalDeploymentState.READY, "digest", view().projection());
        assertFalse(sessions.bind(active, replacement));
    }

    private static DeploymentViewerView view() {
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                "orders", "version", "digest", List.of(), List.of());
        return new DeploymentViewerView(DeploymentViewerView.CURRENT_SOURCE_VERSION,
                DeploymentViewerView.Source.deployment("orders", "incarnation", "version"),
                LocalDeploymentState.READY, "digest", projection);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
