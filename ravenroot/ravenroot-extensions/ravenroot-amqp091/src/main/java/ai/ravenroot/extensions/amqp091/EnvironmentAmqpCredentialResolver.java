package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.SecretValue;

import java.util.Map;
import java.util.Optional;

/** Environment-backed implementation of the opaque {@link CredentialResolver} contract. */
public final class EnvironmentAmqpCredentialResolver implements CredentialResolver {
    private final Map<String, String> environment;

    public EnvironmentAmqpCredentialResolver() {
        this(System.getenv());
    }

    EnvironmentAmqpCredentialResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Optional<SecretValue> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        final String key;
        try {
            key = environmentVariableName(reference);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        String value = environment.get(key);
        return value == null ? Optional.empty() : Optional.of(new SecretValue(value.toCharArray()));
    }

    static String environmentVariableName(String reference) {
        if (reference == null || reference.isBlank())
            throw new IllegalArgumentException("AMQP credential reference must be non-blank");
        return "RAVENROOT_AMQP091_CREDENTIAL_" + EnvironmentKeyCodec.hex(reference);
    }
}
