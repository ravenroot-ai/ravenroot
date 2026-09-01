package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EnvironmentTelegramProfileResolver implements TelegramProfileResolver {
    private final Map<String, String> environment;
    public EnvironmentTelegramProfileResolver() { this(System.getenv()); }
    EnvironmentTelegramProfileResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }
    @Override public Optional<TelegramProfile> resolve(String tenant, String profile) {
        if (!safeId(tenant) || !safeId(profile)) return Optional.empty();
        final String key;
        try { key = environmentVariableName(tenant, profile); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != 13) return Optional.empty();
        try { return Optional.of(new TelegramProfile(tenant, profile, p[0], csv(p[1]), csv(p[2]), lowerCsv(p[3]),
                bool(p[4]), integer(p[5]), integer(p[6]), integer(p[7]), integer(p[8]),
                integer(p[9]), integer(p[10]), integer(p[11]), integer(p[12]))); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }
    /**
     * Derivation only, with no {@link #safeId} re-check — see
     * {@link ai.ravenroot.api.security.EnvironmentKeyCodec} for the single severity and the single
     * failure posture, and why this derivation sits inside a {@code try}.
     */
    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_TELEGRAM_PROFILE_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profile);
    }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }
    private static boolean safeId(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"); }
    private static Set<String> csv(String value) { return value.isBlank() ? Set.of() : Set.of(value.split(",")); }
    private static Set<String> lowerCsv(String value) { return csv(value).stream().map(v -> v.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
}
