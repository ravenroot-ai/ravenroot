package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRequestScopedLiteralPolicyTest {
    private static final ToolPolicy ALLOW_ALL =
            invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");

    private final ReservedNetworkPolicy original = EgressAddressGuard.policy();

    @AfterEach
    void restore() {
        EgressAddressGuard.configure(original);
    }

    @Test
    void rawScopeAliasIsRefusedBeforeTheNodeBuildsOrSendsTheOriginalUri() {
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                "[fe80::1%252]:LINK_LOCAL"));
        var policy = new OutboundHttpPolicy(Set.of("[fe80::1%252]"), Duration.ofSeconds(1));
        var handler = new HttpRequestNodeBehaviorFactory(policy, reference -> {
            throw new AssertionError("scope gate was bypassed before credential resolution");
        }, ALLOW_ALL).create(new GraphNode("http", NodeKind.BEHAVIOR, "http-request", Map.of(
                "url", "http://[fe80::1%252]/private",
                "credentialRef", "must-not-resolve")));

        SecurityException refused = assertThrows(SecurityException.class,
                () -> handler.handle(message()));
        assertFalse(refused.getMessage().contains("fe80"));
        assertFalse(refused.getMessage().contains("252"));
    }

    private static NodeMessage message() {
        return new NodeMessage(
                new SecurityContext("request-535", "tenant", "operator", PrincipalType.USER,
                        "urn:ravenroot:test"),
                UUID.randomUUID(), UUID.randomUUID(), "node-535", "payload", Map.of());
    }
}
