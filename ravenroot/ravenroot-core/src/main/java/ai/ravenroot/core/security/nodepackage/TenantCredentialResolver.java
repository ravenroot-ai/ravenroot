package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.security.SecretValue;

import java.util.Optional;

/** Trusted composition port; this resolver is never exposed through NodePackageServices. */
@FunctionalInterface
public interface TenantCredentialResolver {
    Optional<SecretValue> resolve(String packageId, String tenantId, String opaqueReference);

    /** Identity-aware HTTP placement. Personal references never resolve through the legacy port. */
    default Optional<SecretValue> resolveHttp(String packageId,
            ai.ravenroot.api.security.SecurityContext executor, java.net.URI destination,
            String opaqueReference) {
        if (opaqueReference != null && opaqueReference.startsWith("personal:")) return Optional.empty();
        return resolve(packageId, executor.tenantId(), opaqueReference);
    }
    /** Revalidates revocable HTTP authority without returning a secret. */
    default void requireHttpActive(String packageId, ai.ravenroot.api.security.SecurityContext executor,
            java.net.URI destination, String reference) {
        if (reference != null && reference.startsWith("personal:"))
            throw new SecurityException("Personal connection unavailable");
    }
}
