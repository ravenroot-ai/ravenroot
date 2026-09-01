package ai.ravenroot.api.security;

import java.util.Optional;

/**
 * Pluggable secret backend behind {@link CredentialResolver} (SEC-06).
 *
 * <p>{@link CredentialResolver} is the narrow contract node behaviors call at execution time and is
 * unchanged by this SPI. A {@code SecretProvider} is what actually answers a reference: an
 * environment-variable mapping, a local secure store, or a reference infrastructural adapter (Vault,
 * a cloud secrets manager, a KMS). Exactly one implementation is wired at a time;
 * routing across several is out of scope here.
 *
 * <p>Implementations own least-privilege access to their backing store, and they also own caching,
 * TTL and revocation for that store: this SPI applies none of them uniformly, and core reserves no
 * decorator position that would. A provider fronting an infrastructural secret manager — for example
 * the Kubernetes secret operator on the deployment target assumed here — is expected to get
 * caching, TTL and revocation from that backend's own SDK, which already implements them correctly;
 * re-deriving the same cross-cutting behavior once per provider inside core is exactly the shape this
 * SPI declines to offer, not an oversight in it.
 *
 * <p>Rotation is likewise provider-internal: a provider is free to have its backing secret change
 * between calls (for example because an external secret manager rotated it). This SPI has no
 * {@code rotate()} method or notification contract; a caller only ever observes rotation as a
 * different value on a later {@link #get(String)}, and how promptly that happens is entirely a
 * property of the configured provider, not of anything sitting in front of it.
 */
public interface SecretProvider {
/**
 * Stable identifier for this provider, for example {@code "environment"}.
 * @return stable non-secret provider identifier
 */
    String id();

    /**
     * Resolves {@code reference} to a secret, or {@link Optional#empty()} if this provider has
     * nothing for it. Providers must fail closed: an unresolved reference is empty, never a
     * substitute value from a different reference or a different provider.
 * @param reference declared reference name, never secret material
 * @return a caller-owned secret when this provider resolves it, otherwise an empty optional
     */
    Optional<SecretValue> get(String reference);
}
