package ai.ravenroot.api.security;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PersonalExecutionPrivacyTest {
    @Test void everyAvailableActionRejectsCrossOwnerPersonalPartitionEvenForPerimeterPlatformAdmin() {
        var authorization = new DefaultAuthorizationService(event -> { });
        var scopes = java.util.Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet());
        var admin = new RequestContext("request", "operator", PrincipalType.USER, "https://oidc.example", "local", Set.of(Role.PLATFORM_ADMIN), scopes);
        for (var action : AuthorizationAction.values()) {
            if (action.available()) assertFalse(authorization.decide(admin, action,
                    ProtectedResource.owned("downstream", "id", "personal-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).allowed(), action.toString());
        }
        assertTrue(authorization.decide(admin, AuthorizationAction.EXECUTION_READ,
                ProtectedResource.owned("execution", "id", "personal-project")).allowed(), "ordinary existing tenant names retain platform-admin semantics");
    }
    @Test void inheritedPlatformAdminCannotReadAnotherPersonalPartitionOrItsArtifacts() {
        var authorization = new DefaultAuthorizationService(event -> { });
        for (String type : Set.of("execution", "artifact", "human-task")) {
            var resource = ProtectedResource.owned(type, "id", "personal-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            var scopes = Set.of(AuthorizationAction.EXECUTION_READ.requiredScope());
            var anonymous = new RequestContext("request", "anonymous-loopback", PrincipalType.USER,
                    "urn:ravenroot:disabled-loopback", "local", Set.of(Role.PLATFORM_ADMIN), scopes);
            var other = new RequestContext("request", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", PrincipalType.USER,
                    "urn:ravenroot:personal:v1", "personal-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Set.of(Role.PLATFORM_ADMIN), scopes);
            var forgedTenant = new RequestContext("request", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", PrincipalType.USER,
                    "https://oidc.example", "personal-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Set.of(Role.PLATFORM_ADMIN), scopes);
            var owner = new RequestContext("request", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", PrincipalType.USER,
                    "urn:ravenroot:personal:v1", "personal-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Set.of(Role.TENANT_ADMIN), scopes);
            assertFalse(authorization.decide(anonymous, AuthorizationAction.EXECUTION_READ, resource).allowed());
            assertFalse(authorization.decide(other, AuthorizationAction.EXECUTION_READ, resource).allowed());
            var workload = new RequestContext("request", owner.subject(), PrincipalType.WORKLOAD, owner.issuer(), owner.tenantId(), Set.of(Role.PLATFORM_ADMIN), scopes);
            assertFalse(authorization.decide(workload, AuthorizationAction.EXECUTION_READ, resource).allowed());
            assertFalse(authorization.decide(forgedTenant, AuthorizationAction.EXECUTION_READ, resource).allowed());
            assertTrue(authorization.decide(owner, AuthorizationAction.EXECUTION_READ, resource).allowed());
        }
    }
}
