package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict environment resolver for operator-owned System One profiles. */
public final class EnvironmentSystemOneProfileResolver implements SystemOneProfileResolver {
    public static final String VARIABLE_PREFIX = "RAVENROOT_SYSTEM_ONE_PROFILE_";
    private static final int MAX_PROFILE_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of("endpoint", "model", "credentialBindingId",
            "credentialReference", "timeoutMs", "maxRequestBytes", "maxResponseBytes",
            "maxConcurrency", "maxStateBytes", "maxQuestions", "maxInstructionBytes",
            "maxChoiceOptions", "maxScoreLevels", "supportedTypes", "probabilityTolerance");
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private final Map<String, String> environment;
    private final ReservedNetworkPolicy destinationPolicy;

    public EnvironmentSystemOneProfileResolver() { this(System.getenv()); }

    EnvironmentSystemOneProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        this.destinationPolicy = ReservedNetworkPolicy.fromEnvironment(environment);
    }

    public static String environmentVariableName(String profileName) {
        return VARIABLE_PREFIX + EnvironmentKeyCodec.hex(profileName);
    }

    @Override
    public Optional<SystemOneProfile> resolve(String profileName) {
        if (profileName == null || !profileName.matches(NAME_PATTERN)) return Optional.empty();
        try {
            String encoded = environment.get(environmentVariableName(profileName));
            if (encoded == null || encoded.isBlank() || encoded.length() > MAX_PROFILE_BYTES * 2L) {
                return Optional.empty();
            }
            byte[] json = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(json).equals(encoded)) return Optional.empty();
            Object decoded = PayloadJson.read(json, new PayloadLimits(MAX_PROFILE_BYTES, 8, 64,
                    512, MAX_PROFILE_BYTES, 64)).toJava();
            if (!(decoded instanceof Map<?, ?> raw)) return Optional.empty();
            Map<String, Object> root = cast(raw);
            if (!FIELDS.containsAll(root.keySet())) return Optional.empty();

            String bindingId = text(root.get("credentialBindingId"), "");
            String reference = text(root.get("credentialReference"), "");
            if (bindingId.isEmpty() != reference.isEmpty()) return Optional.empty();
            Optional<OutboundCredentialBinding> credential = bindingId.isEmpty() ? Optional.empty()
                    : Optional.of(new OutboundCredentialBinding(bindingId, reference));
            URI endpoint = new URI(text(root.get("endpoint"), null));
            int endpointPort = endpoint.getPort() == -1
                    ? ("https".equals(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort();
            ReservedNetworkPolicy.PlaintextAuthorization plaintext = credential.isPresent()
                    && "http".equals(endpoint.getScheme())
                    ? destinationPolicy.authorizePlaintext("http", SystemOneNodePackage.ID,
                            endpoint.getHost(), endpointPort) : null;

            return Optional.of(new SystemOneProfile(profileName, endpoint,
                    text(root.get("model"), null), credential,
                    integer(root.get("timeoutMs"), 60_000),
                    integer(root.get("maxRequestBytes"), 1024 * 1024),
                    integer(root.get("maxResponseBytes"), 1024 * 1024),
                    integer(root.get("maxConcurrency"), 4),
                    integer(root.get("maxStateBytes"), 256 * 1024),
                    integer(root.get("maxQuestions"), 32),
                    integer(root.get("maxInstructionBytes"), 8 * 1024),
                    integer(root.get("maxChoiceOptions"), 255),
                    integer(root.get("maxScoreLevels"), 10),
                    stringSet(root.get("supportedTypes"), SystemOneProfile.PORTABLE_TYPES),
                    decimal(root.get("probabilityTolerance"), 0.001), plaintext));
        } catch (RuntimeException | java.net.URISyntaxException invalid) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) { return (Map<String, Object>) raw; }

    private static String text(Object value, String fallback) {
        if (value == null) {
            if (fallback == null) throw new IllegalArgumentException("missing text");
            return fallback;
        }
        if (!(value instanceof String text)) throw new IllegalArgumentException("text");
        return text.strip();
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Long number) || number < 1 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer");
        }
        return number.intValue();
    }

    private static double decimal(Object value, double fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("decimal");
        double result = number.doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("decimal");
        return result;
    }

    private static Set<String> stringSet(Object value, Set<String> fallback) {
        if (value == null) return fallback;
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("types");
        var result = new LinkedHashSet<String>();
        for (Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) throw new IllegalArgumentException("types");
            result.add(text.strip());
        }
        return Set.copyOf(result);
    }
}
