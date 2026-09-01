package ai.ravenroot.core.security;

import ai.ravenroot.api.security.CredentialResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Records a known, deliberately unclosed cross-tenant gap (SEC-07), so that it is discoverable rather
 * than silently inherited.
 *
 * <p>{@code credentialRef} on an HTTP node comes from GraphML node properties, and
 * {@link CredentialResolver#resolve(String)} takes no tenant. A tenant-A graph can therefore name a
 * reference that resolves to another tenant's secret. Tool authorization became tenant-aware in
 * SEC-07; credential resolution did not.</p>
 *
 * <p>It is out of scope here because closing it requires a per-tenant reference naming contract — how
 * a reference is namespaced, what an existing unqualified reference means, what happens on migration —
 * which is a product decision rather than plumbing.</p>
 *
 * <p><strong>This test pins the current gap.</strong> Tenant-aware resolution must replace this
 * expectation with an isolation assertion.</p>
 */
class CredentialResolverTenantScopeTest {

    @Test
    void resolutionIsStillTenantUnscopedAndThisIsAKnownGap() throws Exception {
        Method resolve = CredentialResolver.class.getMethod("resolve", String.class);

        assertEquals(1, resolve.getParameterCount(),
                "resolve() has gained a parameter — if that parameter is a tenant, the SEC-07 credential "
                        + "gap is closed and this test must be replaced by a cross-tenant isolation assertion");
        assertFalse(java.util.Arrays.stream(resolve.getParameterTypes())
                        .anyMatch(type -> type == ai.ravenroot.api.security.SecurityContext.class),
                "resolve() now takes a SecurityContext; replace this gap record with a real isolation test");
    }

    @Test
    void theEnvironmentResolverAnswersTheSameReferenceForEveryTenant() {
        // Routed through ProviderCredentialResolver (SEC-06): CredentialResolver is the contract node
        // behaviors actually call, and the gap this test records lives at that boundary, not inside
        // whichever SecretProvider happens to be plugged in behind it. The env var name is computed
        // via the resolver's own encoding (SEC-26) rather than hardcoded, so this SEC-07 gap record
        // doesn't silently rot the next time the encoding scheme changes — exactly what happened here
        // when SEC-26 replaced the lossy normalization a literal used to spell out by hand.
        CredentialResolver resolver = new ProviderCredentialResolver(new EnvironmentCredentialResolver(
                java.util.Map.of(EnvironmentCredentialResolver.environmentVariableName("shared-token"),
                        "one-secret-for-everyone")));

        // There is no tenant to vary, which is precisely the defect: two tenants naming the same
        // opaque reference in their own graphs receive the same secret, and neither the resolver nor
        // its caller has the information needed to tell them apart.
        try (var first = resolver.resolve("shared-token").orElseThrow();
             var second = resolver.resolve("shared-token").orElseThrow()) {
            assertEquals("one-secret-for-everyone", new String(first.copy()));
            assertEquals(new String(first.copy()), new String(second.copy()));
        }
    }
}
