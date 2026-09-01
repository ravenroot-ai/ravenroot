package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The tool boundary receives the caller's identity (SEC-07).
 *
 * <p>Before this, {@link ToolInvocation} carried only an execution id, a node id and arguments, so a
 * {@link ToolPolicy} was structurally incapable of a tenant-aware decision: an allowlist could only be
 * global, and a tool permitted for one tenant was permitted for every tenant. These tests assert both
 * that the identity now arrives and that a policy can act on it.</p>
 */
class ToolAuthorizationIdentityTest {

    private static final SecurityContext TENANT_A = identity("tenant-a", "alice");
    private static final SecurityContext TENANT_B = identity("tenant-b", "mallory");

    @Test
    void presentsTheDeliveredIdentityToThePolicy() {
        var seen = new ArrayList<ToolInvocation>();
        ToolPolicy capturing = invocation -> {
            seen.add(invocation);
            return new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");
        };

        ToolAuthorization.requireAllowed(capturing, message(TENANT_A), "http.request",
                Map.of("host", "example.test"));

        assertEquals(1, seen.size());
        assertSame(TENANT_A, seen.getFirst().security(),
                "the policy must see the identity the message carried, not a reconstruction");
        assertEquals("tenant-a", seen.getFirst().tenantId());
        assertEquals("alice", seen.getFirst().security().subject());
        assertEquals("http.request", seen.getFirst().tool());
    }

    @Test
    void allowsAPolicyToDecidePerTenantWhichWasPreviouslyImpossible() {
        ToolPolicy onlyTenantA = invocation -> "tenant-a".equals(invocation.tenantId())
                ? new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "")
                : new ToolDecision(ToolDecision.Disposition.DENY, "tool is not enabled for this tenant", "");

        ToolAuthorization.requireAllowed(onlyTenantA, message(TENANT_A), "http.request", Map.of());

        var denied = assertThrows(SecurityException.class, () ->
                ToolAuthorization.requireAllowed(onlyTenantA, message(TENANT_B), "http.request", Map.of()));
        assertEquals("tool is not enabled for this tenant", denied.getMessage());
    }

    @Test
    void aNodeCannotPresentADifferentIdentityThroughItsAttributes() {
        var seen = new ArrayList<ToolInvocation>();
        ToolPolicy capturing = invocation -> {
            seen.add(invocation);
            return new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");
        };
        var hostileAttributes = Map.<String, Object>of(
                "ravenroot.security.tenantId", "tenant-b",
                "tenantId", "tenant-b",
                "subject", "mallory");

        ToolAuthorization.requireAllowed(capturing,
                new NodeMessage(TENANT_A, UUID.randomUUID(), UUID.randomUUID(), "node-1", "payload",
                        hostileAttributes),
                "http.request", Map.of());

        assertEquals("tenant-a", seen.getFirst().tenantId(),
                "attributes are node-controlled data and must not reach the tool decision as identity");
    }

    private static NodeMessage message(SecurityContext security) {
        return new NodeMessage(security, UUID.randomUUID(), UUID.randomUUID(), "node-1", "payload", Map.of());
    }

    private static SecurityContext identity(String tenantId, String subject) {
        return new SecurityContext("request-" + tenantId, tenantId, subject, PrincipalType.USER,
                "urn:ravenroot:test");
    }
}
