package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Environment-backed operator profile resolver; graph content sees only the opaque profile name. */
public final class EnvironmentOcrProfileResolver implements OcrProfileResolver {
    private final Map<String, String> environment;

    public EnvironmentOcrProfileResolver() { this(System.getenv()); }

    EnvironmentOcrProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Optional<OcrProfile> resolve(String tenantId, String profileName) {
        if (!OcrProfile.safeIdentifier(tenantId) || !OcrProfile.safeIdentifier(profileName)) return Optional.empty();
        String raw = environment.get(variableName(tenantId, profileName));
        if (raw == null) return Optional.empty();
        String[] fields = raw.split(";", -1);
        if (fields.length != 9) return Optional.empty();
        try {
            Set<String> languages = fields[2].isEmpty() ? Set.of() : Set.of(fields[2].split(",", -1));
            return Optional.of(new OcrProfile(tenantId, profileName, Path.of(fields[0]), Path.of(fields[1]),
                    languages, Path.of(fields[3]), Duration.ofMillis(Long.parseLong(fields[4])),
                    Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                    Duration.ofMillis(Long.parseLong(fields[8]))));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    static String variableName(String tenantId, String profileName) {
        return "RAVENROOT_OCR_PROFILE_" + EnvironmentKeyCodec.hex(tenantId)
                + "_" + EnvironmentKeyCodec.hex(profileName);
    }
}
