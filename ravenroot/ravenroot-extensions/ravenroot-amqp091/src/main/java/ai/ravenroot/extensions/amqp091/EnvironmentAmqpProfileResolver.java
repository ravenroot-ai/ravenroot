package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves tenant-scoped operator profiles; graph content cannot supply endpoints or credentials. */
public final class EnvironmentAmqpProfileResolver implements AmqpProfileResolver {
    private final Map<String, String> environment;
    private final ReservedNetworkPolicy destinationPolicy;

    public EnvironmentAmqpProfileResolver() {
        this(System.getenv());
    }

    EnvironmentAmqpProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.destinationPolicy = ReservedNetworkPolicy.fromEnvironment(environment);
    }

    @Override
    public Optional<AmqpProfile> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        final String key;
        try {
            key = environmentVariableName(tenant, profile);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        String raw = environment.get(key);
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != 20) return Optional.empty();
        try {
            destinationPolicy.requireAllowedLiteral(p[0]);
            return Optional.of(new AmqpProfile(tenant, profile, p[0], integer(p[1]), strictBoolean(p[2]), p[3],
                    p[4], p[5], p[6], csv(p[7]), p[8], csv(p[9]), csv(p[10]), csv(p[11]),
                    strictBoolean(p[12]), integer(p[13]), Long.parseLong(p[14]), integer(p[15]), integer(p[16]),
                    integer(p[17]), integer(p[18]), integer(p[19])));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /**
     * Derivation only — the {@link #safe} guard is <em>not</em> re-applied here.
     *
     * <p>The guard is admission policy and lives at one collocation for all six derivation sites: an
     * early exit in {@code resolve}, before anything is derived. Repeating it inside the derivation
     * would put it in two places and, worse, would make the encoding's strictness untestable — every
     * input that could exercise {@link EnvironmentKeyCodec}'s reporting behaviour is exactly an input
     * the ASCII guard rejects first, so a seam that re-guards can only ever prove the guard works.
     * This method is package-private and unreachable from outside the connector, so dropping the
     * guard here removes no protection from any production path.
     */
    static String environmentVariableName(String tenant, String profile) {
        return "RAVENROOT_AMQP091_PROFILE_" + EnvironmentKeyCodec.hex(tenant) + "_"
                + EnvironmentKeyCodec.hex(profile);
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static Set<String> csv(String value) {
        return value.isEmpty() ? Set.of() : Set.of(value.split(",", -1));
    }
}
