package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.SecretValue;
import java.util.Map;
import java.util.Optional;

public final class EnvironmentKafkaCredentialResolver implements CredentialResolver {
    private final Map<String, String> environment;
    public EnvironmentKafkaCredentialResolver() { this(System.getenv()); }
    EnvironmentKafkaCredentialResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }
    @Override public Optional<SecretValue> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        final String key;
        try { key = environmentVariableName(reference); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String value = environment.get(key);
        return value == null ? Optional.empty() : Optional.of(new SecretValue(value.toCharArray()));
    }
    static String environmentVariableName(String reference) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("blank Kafka credential reference");
        return "RAVENROOT_KAFKA_CREDENTIAL_" + EnvironmentKeyCodec.hex(reference);
    }
}
