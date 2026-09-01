package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.SecretValue;

import java.util.Map;
import java.util.Optional;

/** Resolves an opaque mail credential reference from the operator environment. */
public final class EnvironmentMailCredentialResolver implements CredentialResolver {
    private final Map<String, String> environment;

    public EnvironmentMailCredentialResolver() { this(System.getenv()); }

    EnvironmentMailCredentialResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }

    @Override public Optional<SecretValue> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        final String key;
        try { key = environmentVariableName(reference); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String value = environment.get(key);
        return value == null ? Optional.empty() : Optional.of(new SecretValue(value.toCharArray()));
    }

    /**
     * Package-private so property tests exercise the strict production derivation directly.
     *
     * <p>The encoder used to be inlined here — the fourth of six copies. It is
     * byte-for-byte the behaviour {@link EnvironmentKeyCodec#hex} now provides for all of them.
     */
    static String environmentVariableName(String reference) {
        if (reference == null || reference.isBlank())
            throw new IllegalArgumentException("Mail credential reference must be non-blank");
        return "RAVENROOT_MAIL_CREDENTIAL_" + EnvironmentKeyCodec.hex(reference);
    }
}
