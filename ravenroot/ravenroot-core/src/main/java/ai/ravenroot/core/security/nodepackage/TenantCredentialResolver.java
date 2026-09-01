package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.security.SecretValue;

import java.util.Optional;

/** Trusted composition port; this resolver is never exposed through NodePackageServices. */
@FunctionalInterface
public interface TenantCredentialResolver {
    Optional<SecretValue> resolve(String packageId, String tenantId, String opaqueReference);
}
