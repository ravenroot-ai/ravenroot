package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Consumer authority is separate so every pre-existing AMQP profile remains publish-only. */
final class EnvironmentAmqpConsumerPolicyResolver implements AmqpConsumerPolicyResolver {
    private final Map<String, String> environment;

    EnvironmentAmqpConsumerPolicyResolver() { this(System.getenv()); }
    EnvironmentAmqpConsumerPolicyResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override public Optional<AmqpConsumerPolicy> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        final String key;
        try { key = variableName(tenant, profile); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != 11) return Optional.empty();
        try {
            return Optional.of(new AmqpConsumerPolicy(tenant, profile, p[0], Integer.parseInt(p[1]),
                    p[2].isEmpty() ? Set.of() : Set.of(p[2].split(",", -1)), p[3],
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                    Integer.parseInt(p[7]), Integer.parseInt(p[8]), p[9], Integer.parseInt(p[10])));
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    /**
     * Derivation only — the guard lives at an early exit in {@code resolve}.
     *
     * <p>It used to live here as the second of two collocations. One
     * collocation for all six sites, before anything is derived; and a derivation seam that does not
     * re-guard is the only way to exercise {@link EnvironmentKeyCodec}'s strictness at all, since
     * every input that could trip it is an input the ASCII guard rejects first.
     */
    static String variableName(String tenant, String profile) {
        return "RAVENROOT_AMQP091_CONSUMER_" + EnvironmentKeyCodec.hex(tenant) + "_"
                + EnvironmentKeyCodec.hex(profile);
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
}
