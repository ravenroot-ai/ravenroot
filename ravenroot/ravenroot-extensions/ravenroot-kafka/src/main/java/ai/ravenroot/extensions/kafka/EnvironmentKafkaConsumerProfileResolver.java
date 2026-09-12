package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves opaque consumer profiles; malformed operator configuration fails closed. */
public final class EnvironmentKafkaConsumerProfileResolver implements KafkaConsumerProfileResolver {
    private final Map<String, String> environment;
    private final ReservedNetworkPolicy destinationPolicy;
    public EnvironmentKafkaConsumerProfileResolver() { this(System.getenv()); }
    EnvironmentKafkaConsumerProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.destinationPolicy = ReservedNetworkPolicy.fromEnvironment(environment);
    }
    @Override public Optional<KafkaConsumerProfile> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        final String key;
        try { key = environmentVariableName(tenant, profile); }
        catch (IllegalArgumentException malformed) { return Optional.empty(); }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != 34) return Optional.empty();
        try {
            EnvironmentKafkaProfileResolver.requireDestinations(p[0], destinationPolicy);
            return Optional.of(new KafkaConsumerProfile(tenant, profile, csvList(p[0]), p[1], bool(p[2]),
                    p[3], p[4], p[5], p[6], p[7], p[8], p[9], csv(p[10]), p[11], csv(p[12]),
                    p[13], p[14], p[15], integer(p[16]), integer(p[17]), integer(p[18]), integer(p[19]),
                    integer(p[20]), integer(p[21]), integer(p[22]), integer(p[23]), integer(p[24]),
                    integer(p[25]), integer(p[26]), integer(p[27]), integer(p[28]), integer(p[29]),
                    integer(p[30]), integer(p[31]), p[32], p[33]));
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    /**
     * Derivation only, with no {@link #safe} re-check — see
     * {@link EnvironmentKafkaProfileResolver#environmentVariableName} for why the seam is unguarded
     * and why this derivation no longer sits outside a {@code try}.
     */
    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_KAFKA_CONSUMER_PROFILE_" + EnvironmentKeyCodec.hex(tenant)
                + "_" + EnvironmentKeyCodec.hex(profile);
    }
    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }
    private static Set<String> csv(String value) {
        return value.isEmpty() ? Set.of() : Set.of(value.split(",", -1));
    }
    private static List<String> csvList(String value) {
        return value.isEmpty() ? List.of() : List.of(value.split(",", -1));
    }
}
