package ai.ravenroot.api.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAuthorizationServiceTest {
    @Test
    void enforcesTheCompleteRoleAndScopeMatrix() {
        var events = new ArrayList<AuthorizationAuditEvent>();
        var service = new DefaultAuthorizationService(events::add);

        for (AuthorizationAction action : AuthorizationAction.values()) {
            if (!action.available()) {
                var unavailable = service.decide(context("tenant-a", Set.of(Role.PLATFORM_ADMIN),
                                Set.of(action.requiredScope())), action,
                        ProtectedResource.owned("test", action.name(), "tenant-a"));
                assertFalse(unavailable.allowed(), action.name());
                assertEquals("action is unavailable", unavailable.reason());
                continue;
            }
            Role role = permittedRole(action);
            var allowed = service.decide(contextFor(action, "tenant-a", Set.of(role),
                            Set.of(action.requiredScope())),
                    action, ProtectedResource.owned("test", action.name(), "tenant-a"));
            assertTrue(allowed.allowed(), action.name());

            var wrongRole = service.decide(contextFor(action, "tenant-a", Set.of(Role.VIEWER),
                            Set.of(action.requiredScope())), action,
                    ProtectedResource.owned("test", action.name(), "tenant-a"));
            if (role == Role.PLATFORM_ADMIN || !isViewerAction(action)) {
                assertFalse(wrongRole.allowed(), action.name());
            }

            var missingScope = service.decide(contextFor(action, "tenant-a", Set.of(role), Set.of()), action,
                    ProtectedResource.owned("test", action.name(), "tenant-a"));
            assertFalse(missingScope.allowed(), action.name());
        }
        long available = java.util.Arrays.stream(AuthorizationAction.values())
                .filter(AuthorizationAction::available).count();
        assertEquals(available * 3 + AuthorizationAction.values().length - available, events.size());
    }

    /**
     * API-02: the availability flag is asserted explicitly rather
     * than relying only on {@link #enforcesTheCompleteRoleAndScopeMatrix}'s generic loop, even though
     * that loop already exercises the same three cases (allowed, wrong role, missing scope) for every
     * available action including this one now.
     */
    @Test
    void executionControlIsNowAvailableAndScopeGated() {
        assertTrue(AuthorizationAction.EXECUTION_CONTROL.available(),
                "cancel and drain require EXECUTION_CONTROL to be available");
        var events = new ArrayList<AuthorizationAuditEvent>();
        var service = new DefaultAuthorizationService(events::add);

        var operator = context("tenant-a", Set.of(Role.OPERATOR), Set.of("ravenroot.execution.control"));
        assertTrue(service.decide(operator, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("execution", "e-1", "tenant-a")).allowed());

        var wrongRole = context("tenant-a", Set.of(Role.VIEWER), Set.of("ravenroot.execution.control"));
        assertFalse(service.decide(wrongRole, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("execution", "e-1", "tenant-a")).allowed());

        var missingScope = context("tenant-a", Set.of(Role.OPERATOR), Set.of());
        assertFalse(service.decide(missingScope, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("execution", "e-1", "tenant-a")).allowed());
    }

    @Test
    void deniesCrossTenantUnknownOwnershipAndRoleEscalationButAllowsExplicitPlatformAdministration() {
        var events = new ArrayList<AuthorizationAuditEvent>();
        var service = new DefaultAuthorizationService(events::add);
        var operator = context("tenant-a", Set.of(Role.OPERATOR), Set.of("ravenroot.execute"));

        assertFalse(service.decide(operator, AuthorizationAction.EXECUTION_START,
                ProtectedResource.owned("execution", "e-1", "tenant-b")).allowed());
        assertFalse(service.decide(operator, AuthorizationAction.EXECUTION_START,
                ProtectedResource.unknownOwnership("execution", "legacy")).allowed());
        assertFalse(service.decide(context("tenant-a", Set.of(Role.DEVELOPER),
                        Set.of("ravenroot.artifact.approve")), AuthorizationAction.ARTIFACT_APPROVE,
                ProtectedResource.owned("artifact", "a-1", "tenant-a")).allowed());

        var platform = context("platform", Set.of(Role.PLATFORM_ADMIN), Set.of("ravenroot.execute"));
        assertTrue(service.decide(platform, AuthorizationAction.EXECUTION_START,
                ProtectedResource.owned("execution", "e-1", "tenant-b")).allowed());
        assertEquals(4, events.size());
    }

    @Test
    void failsClosedWhenAuditEvidenceCannotBeWritten() {
        var service = new DefaultAuthorizationService(event -> {
            throw new IllegalStateException("offline");
        });
        assertThrows(AuthorizationDeniedException.class, () -> service.requireAllowed(
                context("tenant-a", Set.of(Role.VIEWER), Set.of("ravenroot.read")),
                AuthorizationAction.STATUS_READ, ProtectedResource.collection("service", "tenant-a")));
    }

    private static RequestContext context(String tenant, Set<Role> roles, Set<String> scopes) {
        return new RequestContext("request-1", "alice", PrincipalType.USER, "issuer", tenant, roles, scopes);
    }

    private static RequestContext contextFor(AuthorizationAction action, String tenant,
                                             Set<Role> roles, Set<String> scopes) {
        return new RequestContext("request-1", "alice",
                action == AuthorizationAction.EMBED_SESSION_CREATE
                        ? PrincipalType.WORKLOAD : PrincipalType.USER,
                "issuer", tenant, roles, scopes);
    }

    private static Role permittedRole(AuthorizationAction action) {
        return switch (action) {
            case STATUS_READ, CATALOG_READ, ARTIFACT_LIST, EMBED_GRAPH_READ,
                    EMBED_SESSION_CREATE -> Role.VIEWER;
            // EMBED_REGISTRATION_ADMIN is deliberately not in the VIEWER arm above, unlike the
            // two embed actions beside it. Deciding which snapshot an embed may expose is operations.
            case GRAPH_READ, EXECUTION_START, EXECUTION_READ, EXECUTION_CONTROL,
                    EMBED_REGISTRATION_ADMIN -> Role.OPERATOR;
            case ARTIFACT_CREATE, ARTIFACT_VALIDATE, ARTIFACT_TEST -> Role.DEVELOPER;
            case ARTIFACT_APPROVE, ARTIFACT_ACTIVATE, ARTIFACT_RETIRE -> Role.APPROVER;
            case RUNTIME_OBSERVE, AUDIT_ADMIN -> Role.PLATFORM_ADMIN;
            case AUDIT_READ, AUDIT_EXPORT -> Role.TENANT_ADMIN;
            // EXECUTION_CONTROL moved out of this reserved arm the same commit it became
            // available -- see enforcesTheCompleteRoleAndScopeMatrix, which now exercises it through
            // the positive branch above (allowed with role+scope, denied with wrong role, denied with
            // missing scope) exactly like every other available action. Do not add it back here: the
            // other three reserved actions (GRAPH_WRITE, TOOL_INVOKE, ADMIN) stay refused, unchanged.
            case GRAPH_WRITE, TOOL_INVOKE, ADMIN ->
                    throw new IllegalArgumentException("unavailable action");
        };
    }

    private static boolean isViewerAction(AuthorizationAction action) {
        return action == AuthorizationAction.STATUS_READ || action == AuthorizationAction.CATALOG_READ
                || action == AuthorizationAction.ARTIFACT_LIST
                || action == AuthorizationAction.EMBED_GRAPH_READ
                || action == AuthorizationAction.EMBED_SESSION_CREATE;
    }
}
