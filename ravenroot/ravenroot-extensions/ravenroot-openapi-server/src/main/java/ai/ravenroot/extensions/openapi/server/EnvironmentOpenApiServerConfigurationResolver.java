package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict Base64-JSON package configuration; graph data never reaches this resolver. */
public final class EnvironmentOpenApiServerConfigurationResolver implements OpenApiServerConfigurationResolver {
    public static final String VARIABLE = "RAVENROOT_OPENAPI_SERVER_CONFIG";
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    private static final PayloadLimits LIMITS = new PayloadLimits(MAX_CONFIG_BYTES, 48, 1_024, 50_000,
            OpenApiServerProfile.HARD_MAX_SPEC_BYTES * 2, 160);
    private static final Set<String> ROOT_FIELDS = Set.of("authority", "projection", "profiles");
    private static final Set<String> AUTHORITY_FIELDS = Set.of("listenerId", "pathPrefix", "requiredScopes",
            "maxRoutes", "maxConcurrentRequests", "maxRequestBytes", "maxResponseBytes", "requestTimeoutMs");
    private static final Set<String> PROJECTION_FIELDS = Set.of("allowedHeaders", "idempotencyHeader",
            "maxRelativePathBytes", "maxQueryParameters", "maxQueryBytes", "maxHeaderCount",
            "maxHeaderBytes", "maxHeaderValueBytes");
    private static final Set<String> PROFILE_FIELDS = Set.of("specBase64", "specSha256", "routeBase",
            "operations", "principalTypes", "idempotencyHeader", "targetNode", "maxRequestBytes",
            "maxIdempotencyBytes", "deadlineMs", "maxConcurrency");

    private final Map<String, String> environment;

    public EnvironmentOpenApiServerConfigurationResolver() { this(System.getenv()); }

    EnvironmentOpenApiServerConfigurationResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override public Optional<OpenApiServerConfiguration> resolve() {
        try {
            String encoded = environment.get(VARIABLE);
            if (encoded == null || encoded.length() > MAX_CONFIG_BYTES * 2) return Optional.empty();
            byte[] json = strictBase64(encoded, MAX_CONFIG_BYTES);
            Map<String, Object> root = OpenApiValues.object(PayloadJson.read(json, LIMITS).toJava(), "configuration");
            OpenApiValues.exactKeys(root, ROOT_FIELDS, "configuration");
            IngressAuthorityDeclaration authority = authority(root.get("authority"));
            IngressRequestProjectionPolicy projection = projection(root.get("projection"));
            Map<String, Object> rawProfiles = OpenApiValues.object(root.get("profiles"), "profiles");
            if (rawProfiles.isEmpty() || rawProfiles.size() > IngressAuthorityDeclaration.HARD_MAX_ROUTES) throw invalid();
            Map<String, OpenApiServerProfile> profiles = new LinkedHashMap<>();
            rawProfiles.forEach((name, value) -> profiles.put(name, profile(name, value)));
            return Optional.of(new OpenApiServerConfiguration(authority, projection, profiles));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static IngressAuthorityDeclaration authority(Object raw) {
        Map<String, Object> value = OpenApiValues.object(raw, "authority");
        OpenApiValues.exactKeys(value, AUTHORITY_FIELDS, "authority");
        return new IngressAuthorityDeclaration(OpenApiServerConfiguration.PACKAGE_ID,
                OpenApiValues.string(value.get("listenerId"), "listenerId", 160),
                OpenApiValues.string(value.get("pathPrefix"), "pathPrefix", 320),
                strings(value.get("requiredScopes"), "requiredScopes", 32, true),
                integer(value, "maxRoutes", 1, IngressAuthorityDeclaration.HARD_MAX_ROUTES),
                integer(value, "maxConcurrentRequests", 1, IngressAuthorityDeclaration.HARD_MAX_CONCURRENT_REQUESTS),
                integer(value, "maxRequestBytes", 1, (int) IngressAuthorityDeclaration.HARD_MAX_BODY_BYTES),
                integer(value, "maxResponseBytes", 1, (int) IngressAuthorityDeclaration.HARD_MAX_BODY_BYTES),
                Duration.ofMillis(integer(value, "requestTimeoutMs", 1,
                        (int) IngressAuthorityDeclaration.HARD_MAX_TIMEOUT.toMillis())));
    }

    private static IngressRequestProjectionPolicy projection(Object raw) {
        Map<String, Object> value = OpenApiValues.object(raw, "projection");
        OpenApiValues.exactKeys(value, PROJECTION_FIELDS, "projection");
        return new IngressRequestProjectionPolicy(OpenApiServerConfiguration.PACKAGE_ID,
                strings(value.get("allowedHeaders"), "allowedHeaders",
                        IngressRequestProjectionPolicy.HARD_MAX_HEADER_COUNT, true),
                OpenApiValues.optionalString(value.get("idempotencyHeader"), "idempotencyHeader", 64),
                integer(value, "maxRelativePathBytes", 1,
                        IngressRequestProjectionPolicy.HARD_MAX_RELATIVE_PATH_BYTES),
                integer(value, "maxQueryParameters", 1,
                        IngressRequestProjectionPolicy.HARD_MAX_QUERY_PARAMETERS),
                integer(value, "maxQueryBytes", 1, IngressRequestProjectionPolicy.HARD_MAX_QUERY_BYTES),
                integer(value, "maxHeaderCount", 1, IngressRequestProjectionPolicy.HARD_MAX_HEADER_COUNT),
                integer(value, "maxHeaderBytes", 1, IngressRequestProjectionPolicy.HARD_MAX_HEADER_BYTES),
                integer(value, "maxHeaderValueBytes", 1,
                        IngressRequestProjectionPolicy.HARD_MAX_HEADER_VALUE_BYTES));
    }

    private static OpenApiServerProfile profile(String name, Object raw) {
        Map<String, Object> value = OpenApiValues.object(raw, "profile");
        OpenApiValues.exactKeys(value, PROFILE_FIELDS, "profile");
        return new OpenApiServerProfile(name,
                strictBase64(OpenApiValues.string(value.get("specBase64"), "specBase64",
                        OpenApiServerProfile.HARD_MAX_SPEC_BYTES * 2), OpenApiServerProfile.HARD_MAX_SPEC_BYTES),
                OpenApiValues.string(value.get("specSha256"), "specSha256", 64),
                OpenApiValues.string(value.get("routeBase"), "routeBase", 256),
                strings(value.get("operations"), "operations", 128, false),
                strings(value.get("principalTypes"), "principalTypes", 8, false),
                OpenApiValues.string(value.get("idempotencyHeader"), "idempotencyHeader", 64),
                OpenApiValues.optionalString(value.get("targetNode"), "targetNode", 160),
                integer(value, "maxRequestBytes", 1, OpenApiServerProfile.HARD_MAX_REQUEST_BYTES),
                integer(value, "maxIdempotencyBytes", 1, 1024),
                integer(value, "deadlineMs", 1, 300_000), integer(value, "maxConcurrency", 1, 256));
    }

    private static int integer(Map<String, Object> value, String field, int minimum, int maximum) {
        return OpenApiValues.integer(value.get(field), field, minimum, maximum);
    }

    private static Set<String> strings(Object raw, String field, int maximum, boolean emptyAllowed) {
        List<Object> values = OpenApiValues.list(raw, field);
        if ((!emptyAllowed && values.isEmpty()) || values.size() > maximum) throw invalid();
        Set<String> result = values.stream().map(value -> OpenApiValues.string(value, field, 160))
                .collect(Collectors.toUnmodifiableSet());
        if (result.size() != values.size()) throw invalid();
        return result;
    }

    private static byte[] strictBase64(String value, int maximum) {
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length > maximum || !Base64.getEncoder().encodeToString(decoded).equals(value)) throw invalid();
        return decoded;
    }

    private static OpenApiServerException invalid() { return OpenApiValues.invalid(); }
}
