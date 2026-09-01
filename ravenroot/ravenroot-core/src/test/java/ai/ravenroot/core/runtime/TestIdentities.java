package ai.ravenroot.core.runtime;

import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

/**
 * Fixed ingress identities for core tests.
 *
 * <p>Deliberately a test fixture rather than a factory on {@link SecurityContext}. Production code
 * must have exactly one way to obtain an identity — {@link SecurityContext#of} from an authenticated
 * {@code RequestContext} — so any convenience that fabricates one belongs in test scope, where it
 * cannot be reached by a caller that should have authenticated instead.</p>
 */
final class TestIdentities {
    static final SecurityContext TENANT_A = of("tenant-a", "alice");
    static final SecurityContext TENANT_B = of("tenant-b", "mallory");

    private TestIdentities() {
    }

    static SecurityContext of(String tenantId, String subject) {
        return new SecurityContext("request-" + tenantId + "-" + subject, tenantId, subject,
                PrincipalType.USER, "urn:ravenroot:test");
    }
}
