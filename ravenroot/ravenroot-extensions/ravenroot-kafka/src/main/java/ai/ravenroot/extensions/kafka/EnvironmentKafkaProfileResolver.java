package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EnvironmentKafkaProfileResolver implements KafkaProfileResolver {
    private final Map<String, String> environment;
    private final ReservedNetworkPolicy destinationPolicy;
    public EnvironmentKafkaProfileResolver() { this(System.getenv()); }
    EnvironmentKafkaProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.destinationPolicy = ReservedNetworkPolicy.fromEnvironment(environment);
    }
    @Override public Optional<KafkaProfile> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        final String key;
        try { key = environmentVariableName(tenant, profile); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != 24) return Optional.empty();
        try {
            requireDestinations(p[0], destinationPolicy);
            return Optional.of(new KafkaProfile(tenant, profile, List.of(p[0].split(",", -1)), p[1], bool(p[2]),
                    p[3], p[4], p[5], p[6], p[7], csv(p[8]), csv(p[9]), bool(p[10]), integer(p[11]), bool(p[12]),
                    p[13], p[14], bool(p[15]), integer(p[16]), integer(p[17]), bool(p[18]), integer(p[19]),
                    integer(p[20]), integer(p[21]), integer(p[22]), Long.parseLong(p[23])));
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    /**
     * Derivation only, with no {@link #safe} re-check — the guard is an early exit in {@code resolve}
     * and the seam exists so the derivation properties can reach {@link EnvironmentKeyCodec} past it.
     *
     * <p>Previously, this derivation sat outside any {@code try}, so a malformed identifier would
     * have propagated out of {@code resolve} while the AMQP sites answered empty for the same input.
     * It now fails closed like every other rejection in {@code resolve}.
     */
    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_KAFKA_PROFILE_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profile);
    }
    private static boolean safe(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }
    private static Set<String> csv(String value) { return value.isEmpty() ? Set.of() : Set.of(value.split(",", -1)); }
    static void requireDestinations(String bootstrapServers, ReservedNetworkPolicy policy) {
        for (String authority : bootstrapServers.split(",", -1))
            policy.requireAllowedLiteral(host(authority));
    }
    private static String host(String authority) {
        String value = authority.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close < 0 ? value : value.substring(0, close + 1);
        }
        int first = value.indexOf(':');
        return first < 0 || first != value.lastIndexOf(':') ? value : value.substring(0, first);
    }
}
