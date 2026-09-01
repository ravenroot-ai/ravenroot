package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict Base64-JSON operator profile. No graph-controlled path or URL is dereferenced. */
public final class EnvironmentOpenApiClientProfileResolver implements OpenApiClientProfileResolver {
    private static final int MAX_PROFILE_BYTES = 3 * 1024 * 1024;
    private static final PayloadLimits LIMITS = new PayloadLimits(MAX_PROFILE_BYTES, 32, 512, 20_000,
            OpenApiClientProfile.HARD_MAX_SPEC_BYTES * 2, 128);
    private static final Set<String> FIELDS = Set.of("origin", "specBase64", "specSha256", "operations",
            "fixedHeaders", "inputHeaders", "responseHeaders", "credentialBindingId", "credentialReference",
            "maxRequestBytes", "maxResponseBytes", "timeoutMs", "maxConcurrency");
    private final Map<String, String> environment;

    public EnvironmentOpenApiClientProfileResolver() { this(System.getenv()); }
    EnvironmentOpenApiClientProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override public Optional<OpenApiClientProfile> resolve(String profileName) {
        if (profileName == null || !profileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) return Optional.empty();
        try {
            String encoded = environment.get(environmentVariableName(profileName));
            if (encoded == null || encoded.length() > MAX_PROFILE_BYTES * 2) return Optional.empty();
            byte[] json = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(json).equals(encoded)) return Optional.empty();
            Map<String, Object> root = OpenApiValues.object(PayloadJson.read(json, LIMITS).toJava(), "profile");
            OpenApiValues.exactKeys(root, FIELDS, "profile");
            byte[] spec = strictBase64(OpenApiValues.string(root.get("specBase64"), "specBase64",
                    OpenApiClientProfile.HARD_MAX_SPEC_BYTES * 2));
            return Optional.of(new OpenApiClientProfile(profileName,
                    URI.create(OpenApiValues.string(root.get("origin"), "origin", 512)), spec,
                    OpenApiValues.string(root.get("specSha256"), "specSha256", 64),
                    strings(root.get("operations"), "operations", 128, false), headers(root.get("fixedHeaders")),
                    strings(root.get("inputHeaders"), "inputHeaders", 32, true),
                    strings(root.get("responseHeaders"), "responseHeaders", 32, true),
                    OpenApiValues.optionalString(root.get("credentialBindingId"), "credentialBindingId", 256),
                    OpenApiValues.optionalString(root.get("credentialReference"), "credentialReference", 256),
                    OpenApiValues.integer(root.get("maxRequestBytes"), "maxRequestBytes", 1,
                            OpenApiClientProfile.HARD_MAX_BODY_BYTES),
                    OpenApiValues.integer(root.get("maxResponseBytes"), "maxResponseBytes", 1,
                            OpenApiClientProfile.HARD_MAX_BODY_BYTES),
                    OpenApiValues.integer(root.get("timeoutMs"), "timeoutMs", 1, 300_000),
                    OpenApiValues.integer(root.get("maxConcurrency"), "maxConcurrency", 1, 256)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    static String environmentVariableName(String profileName) {
        return "RAVENROOT_OPENAPI_CLIENT_PROFILE_" + EnvironmentKeyCodec.hex(profileName);
    }

    private static byte[] strictBase64(String value) {
        byte[] decoded = Base64.getDecoder().decode(value);
        if (!Base64.getEncoder().encodeToString(decoded).equals(value)) throw OpenApiValues.invalid("base64");
        return decoded;
    }

    private static Set<String> strings(Object value, String field, int max, boolean emptyAllowed) {
        List<Object> values = OpenApiValues.list(value, field);
        if ((!emptyAllowed && values.isEmpty()) || values.size() > max) throw OpenApiValues.invalid(field);
        return values.stream().map(entry -> OpenApiValues.string(entry, field, 128))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, List<String>> headers(Object value) {
        Map<String, Object> source = OpenApiValues.optionalObject(value, "fixedHeaders");
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, raw) -> result.put(name, OpenApiValues.list(raw, "fixedHeaders").stream()
                .map(entry -> OpenApiValues.string(entry, "fixedHeaders", 512)).toList()));
        return Map.copyOf(result);
    }
}
