package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Trusted composition port; this resolver is never exposed through NodePackageServices. */
@FunctionalInterface
public interface TenantCredentialResolver {
    Optional<SecretValue> resolve(String packageId, String tenantId, String opaqueReference);

    /** Identity-aware HTTP placement. Personal references never resolve through the legacy port. */
    default Optional<SecretValue> resolveHttp(String packageId,
            SecurityContext executor, URI destination, String opaqueReference) {
        if (opaqueReference != null && opaqueReference.startsWith("personal:")) return Optional.empty();
        return resolve(packageId, executor.tenantId(), opaqueReference);
    }

    /**
     * Checks revocable HTTP authority and hands a current credential to the trusted transport
     * within the same revocation boundary. The handoff places the credential and submits an
     * asynchronous request; client initialization and response waits belong outside it.
     */
    default <T> CompletableFuture<T> dispatchHttp(String packageId, SecurityContext executor,
            URI destination, String reference, Function<SecretValue, CompletableFuture<T>> handoff) {
        throw new SecurityException("Personal connection unavailable");
    }

    /** Revalidates revocable HTTP authority without returning a secret. */
    default void requireHttpActive(String packageId, SecurityContext executor, URI destination, String reference) {
        if (reference != null && reference.startsWith("personal:"))
            throw new SecurityException("Personal connection unavailable");
    }
}
