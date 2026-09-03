package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads strict Base64 JSON profiles from the process environment. */
public final class EnvironmentGitWorkspaceProfileResolver implements GitWorkspaceProfileResolver {
    private static final PayloadLimits LIMITS = new PayloadLimits(32 * 1024, 4, 64, 4096, 8192, 128);
    private static final Set<String> REQUIRED = Set.of("root", "remote", "baseRef", "issueRefPrefix",
            "gitExecutable", "objectFormat", "deadlineMs", "maxConcurrency", "maxOutputBytes",
            "historyScanLimit");
    private final Map<String, String> environment;

    public EnvironmentGitWorkspaceProfileResolver() {
        this(System.getenv());
    }

    EnvironmentGitWorkspaceProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Optional<GitWorkspaceProfile> resolve(String tenant, String profile) {
        if (!identifier(tenant) || !identifier(profile)) return Optional.empty();
        try {
            String encoded = environment.get(variable(tenant, profile));
            if (encoded == null || encoded.length() > 48_000) return Optional.empty();
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) return Optional.empty();
            PayloadValue parsed = PayloadJson.read(bytes, LIMITS);
            if (!(parsed instanceof PayloadValue.MapValue object)) return Optional.empty();
            Map<String, PayloadValue> values = object.entries();
            boolean credentialled = values.containsKey("credentialRef") || values.containsKey("credentialUsername");
            Set<String> expected = new HashSet<>(REQUIRED);
            if (credentialled) expected.addAll(Set.of("credentialRef", "credentialUsername"));
            if (!values.keySet().equals(expected)) return Optional.empty();
            return Optional.of(new GitWorkspaceProfile(tenant, profile, Path.of(text(values, "root")),
                    text(values, "remote"), text(values, "baseRef"), text(values, "issueRefPrefix"),
                    Path.of(text(values, "gitExecutable")), text(values, "objectFormat"),
                    credentialled ? text(values, "credentialRef") : null,
                    credentialled ? text(values, "credentialUsername") : null,
                    Duration.ofMillis(integer(values, "deadlineMs")), integer(values, "maxConcurrency"),
                    integer(values, "maxOutputBytes"), integer(values, "historyScanLimit")));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    static String variable(String tenant, String profile) {
        return "RAVENROOT_GIT_WORKSPACE_PROFILE_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profile);
    }

    private static String text(Map<String, PayloadValue> values, String key) {
        if (!(values.get(key) instanceof PayloadValue.TextValue text)) throw new IllegalArgumentException();
        return text.value();
    }

    private static int integer(Map<String, PayloadValue> values, String key) {
        if (!(values.get(key) instanceof PayloadValue.IntegerValue integer)) throw new IllegalArgumentException();
        return Math.toIntExact(integer.value());
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
}
