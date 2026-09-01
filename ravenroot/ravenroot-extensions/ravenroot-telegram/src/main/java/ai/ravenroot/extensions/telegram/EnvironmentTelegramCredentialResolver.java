package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.SecretValue;

import java.util.Map;
import java.util.Optional;

public final class EnvironmentTelegramCredentialResolver implements CredentialResolver {
    private final Map<String, String> environment;
    public EnvironmentTelegramCredentialResolver() { this(System.getenv()); }
    EnvironmentTelegramCredentialResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }
    @Override public Optional<SecretValue> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        final String key;
        try { key = environmentVariableName(reference); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String value = environment.get(key);
        return value == null ? Optional.empty() : Optional.of(new SecretValue(value.toCharArray()));
    }

    /** Package-private so the derivation properties exercise the strict codec directly. */
    static String environmentVariableName(String reference) {
        if (reference == null || reference.isBlank())
            throw new IllegalArgumentException("blank Telegram credential reference");
        return "RAVENROOT_TELEGRAM_CREDENTIAL_" + EnvironmentKeyCodec.hex(reference);
    }
}
