package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.util.Map;
import java.util.Optional;

/** Environment-backed consumer authority kept separate from query-only IMAP profiles. */
final class EnvironmentImapConsumerPolicyResolver implements ImapConsumerPolicyResolver {
    private static final int LEGACY_FIELDS = 10;
    private static final int FIELDS_WITH_HEADERS = 11;
    private final Map<String, String> environment;

    EnvironmentImapConsumerPolicyResolver() { this(System.getenv()); }
    EnvironmentImapConsumerPolicyResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override public Optional<ImapConsumerPolicy> resolve(String tenant, String profile) {
        if (!safe(tenant) || !safe(profile)) return Optional.empty();
        String raw;
        try { raw = environment.get(variableName(tenant, profile)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
        if (raw == null) return Optional.empty();
        String[] p = raw.split(";", -1);
        if (p.length != LEGACY_FIELDS && p.length != FIELDS_WITH_HEADERS) return Optional.empty();
        try {
            return Optional.of(new ImapConsumerPolicy(tenant, profile, p[0],
                    Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                    Integer.parseInt(p[7]), p[8], Integer.parseInt(p[9]),
                    p.length == LEGACY_FIELDS ? java.util.Set.of()
                            : ImapConsumerPolicy.parseHeaders(p[10])));
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    static String variableName(String tenant, String profile) {
        return "RAVENROOT_IMAP_CONSUMER_" + EnvironmentKeyCodec.hex(tenant) + "_"
                + EnvironmentKeyCodec.hex(profile);
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
}
