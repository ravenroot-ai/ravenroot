package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves tenant-scoped, operator-owned filesystem profiles from the process environment. */
public final class EnvironmentFilesystemProfileResolver implements FilesystemProfileResolver {
    private final Map<String, String> environment;

    public EnvironmentFilesystemProfileResolver() { this(System.getenv()); }
    EnvironmentFilesystemProfileResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }

    @Override public Optional<FilesystemProfile> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        final String key;
        try { key = environmentVariableName(tenant, profile); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] fields = raw.split(";", -1);
        if (fields.length != 7) return Optional.empty();
        try {
            boolean read = bool(fields[1]);
            boolean write = bool(fields[2]);
            Set<String> patterns = fields[3].isEmpty() ? Set.of() : Set.of(fields[3].split(",", -1));
            return Optional.of(new FilesystemProfile(profile, Path.of(fields[0]), read, write, patterns,
                    Long.parseLong(fields[4]), Integer.parseInt(fields[5]),
                    Duration.ofMillis(Long.parseLong(fields[6]))));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_FILESYSTEM_PROFILE_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profile);
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }
}
