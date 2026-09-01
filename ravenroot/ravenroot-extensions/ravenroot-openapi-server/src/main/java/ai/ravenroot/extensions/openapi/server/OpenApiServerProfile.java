package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.deployment.IngressTarget;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Operator-owned immutable OpenAPI document, route and request envelope for one profile. */
public record OpenApiServerProfile(
        String name, byte[] specification, String specificationSha256, String routeBase,
        Set<String> allowedOperations, Set<String> allowedPrincipalTypes, String idempotencyHeader,
        String targetNode, int maxRequestBytes, int maxIdempotencyBytes, int deadlineMs,
        int maxConcurrency) {

    public static final int HARD_MAX_SPEC_BYTES = 2 * 1024 * 1024;
    public static final int HARD_MAX_REQUEST_BYTES = 16 * 1024 * 1024;

    public OpenApiServerProfile {
        name = token(name, "name", 64);
        specification = Objects.requireNonNull(specification, "specification").clone();
        if (specification.length == 0 || specification.length > HARD_MAX_SPEC_BYTES) throw invalid();
        specificationSha256 = token(specificationSha256, "specificationSha256", 64).toLowerCase(Locale.ROOT);
        if (!specificationSha256.matches("[0-9a-f]{64}")) throw invalid();
        routeBase = relativePath(routeBase);
        allowedOperations = Set.copyOf(Objects.requireNonNull(allowedOperations, "allowedOperations"));
        if (allowedOperations.isEmpty() || allowedOperations.size() > 128
                || allowedOperations.stream().anyMatch(value -> !value.matches("[A-Za-z0-9._-]{1,128}"))) {
            throw invalid();
        }
        allowedPrincipalTypes = Set.copyOf(Objects.requireNonNull(allowedPrincipalTypes, "allowedPrincipalTypes"));
        if (allowedPrincipalTypes.isEmpty() || allowedPrincipalTypes.size() > 2
                || !Set.of("USER", "WORKLOAD").containsAll(allowedPrincipalTypes)) {
            throw invalid();
        }
        idempotencyHeader = header(idempotencyHeader);
        // Named durable ingress targets are not yet supported by DefaultGraphDeployment.
        if (targetNode != null) throw invalid();
        if (maxRequestBytes < 1 || maxRequestBytes > HARD_MAX_REQUEST_BYTES
                || maxIdempotencyBytes < 1 || maxIdempotencyBytes > 1024
                || deadlineMs < 1 || deadlineMs > Duration.ofMinutes(5).toMillis()
                || maxConcurrency < 1 || maxConcurrency > 256) throw invalid();
    }

    @Override public byte[] specification() { return specification.clone(); }

    IngressTarget target() { return IngressTarget.start(); }

    static String header(String value) {
        String normalized = token(value, "header", 64).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw invalid();
        return normalized;
    }

    private static String relativePath(String value) {
        if (value == null || value.length() < 2 || value.length() > 256 || !value.startsWith("/")
                || value.endsWith("/") || value.contains("//")) throw invalid();
        for (String segment : value.substring(1).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..") || !segment.matches("[A-Za-z0-9._~-]+")) throw invalid();
        }
        return value;
    }

    private static String token(String value, String field, int maximum) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maximum
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) throw invalid();
        return value;
    }

    private static OpenApiServerException invalid() { return OpenApiValues.invalid(); }
}
