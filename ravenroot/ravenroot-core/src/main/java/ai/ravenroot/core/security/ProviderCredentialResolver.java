package ai.ravenroot.core.security;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretProvider;
import ai.ravenroot.api.security.SecretValue;

import java.util.Objects;
import java.util.Optional;

/**
 * Adapts a {@link SecretProvider} to the narrow {@link CredentialResolver} contract node behaviors
 * call (SEC-06).
 *
 * <p>This is the seam between the two roles the SPI split deliberately keeps separate: callers such
 * as the HTTP request node behavior depend only on {@link CredentialResolver} and are unaffected by
 * which {@link SecretProvider} answers the reference. Caching, TTL and revocation are not
 * cross-cutting concerns this class — or anything else in core — applies on a provider's behalf; they
 * are the responsibility of whichever {@code SecretProvider} is configured, typically by relying on
 * its backing infrastructure's own SDK. Core reserves no decorator position here for them.
 */
public final class ProviderCredentialResolver implements CredentialResolver {
    private final SecretProvider provider;

    public ProviderCredentialResolver(SecretProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public Optional<SecretValue> resolve(String reference) {
        return provider.get(reference);
    }
}
