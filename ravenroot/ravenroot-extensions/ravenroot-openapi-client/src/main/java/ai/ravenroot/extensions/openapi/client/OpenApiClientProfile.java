package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Operator-owned authority and resource ceilings for one compiled OpenAPI document. */
public record OpenApiClientProfile(
        String name, URI origin, byte[] specification, String specificationSha256,
        Set<String> allowedOperations, Map<String, List<String>> fixedHeaders,
        Set<String> allowedInputHeaders, Set<String> projectedResponseHeaders,
        String credentialBindingId, String credentialReference,
        int maxRequestBytes, int maxResponseBytes, int timeoutMs, int maxConcurrency) {

    public static final int HARD_MAX_SPEC_BYTES = 2 * 1024 * 1024;
    public static final int HARD_MAX_BODY_BYTES = 16 * 1024 * 1024;

    public OpenApiClientProfile {
        name = token(name, "name", 64);
        origin = exactHttpsOrigin(origin);
        specification = Objects.requireNonNull(specification, "specification").clone();
        if (specification.length == 0 || specification.length > HARD_MAX_SPEC_BYTES) {
            throw new IllegalArgumentException("specification size is invalid");
        }
        specificationSha256 = token(specificationSha256, "specificationSha256", 64).toLowerCase(Locale.ROOT);
        if (!specificationSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("digest is invalid");
        allowedOperations = Set.copyOf(Objects.requireNonNull(allowedOperations, "allowedOperations"));
        if (allowedOperations.isEmpty() || allowedOperations.size() > 128
                || allowedOperations.stream().anyMatch(value -> !value.matches("[A-Za-z0-9._-]{1,128}"))) {
            throw new IllegalArgumentException("allowedOperations is invalid");
        }
        fixedHeaders = immutableHeaders(fixedHeaders);
        allowedInputHeaders = headerNames(allowedInputHeaders);
        projectedResponseHeaders = headerNames(projectedResponseHeaders);
        credentialBindingId = optionalToken(credentialBindingId);
        credentialReference = optionalToken(credentialReference);
        if ((credentialBindingId == null) != (credentialReference == null)) {
            throw new IllegalArgumentException("credential binding is incomplete");
        }
        if (maxRequestBytes < 1 || maxRequestBytes > HARD_MAX_BODY_BYTES
                || maxResponseBytes < 1 || maxResponseBytes > HARD_MAX_BODY_BYTES
                || timeoutMs < 1 || timeoutMs > Duration.ofMinutes(5).toMillis()
                || maxConcurrency < 1 || maxConcurrency > 256) {
            throw new IllegalArgumentException("profile limit is invalid");
        }
    }

    @Override public byte[] specification() { return specification.clone(); }

    Optional<OutboundCredentialBinding> credential() {
        return credentialBindingId == null ? Optional.empty()
                : Optional.of(new OutboundCredentialBinding(credentialBindingId, credentialReference));
    }

    private static URI exactHttpsOrigin(URI value) {
        Objects.requireNonNull(value, "origin");
        if (!"https".equals(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null || value.getQuery() != null
                || value.getHost().contains(":") || !(value.getPath().isEmpty() || "/".equals(value.getPath()))) {
            throw new IllegalArgumentException("origin must be an exact HTTPS authority");
        }
        return URI.create("https://" + value.getHost().toLowerCase(Locale.ROOT)
                + (value.getPort() == -1 || value.getPort() == 443 ? "" : ":" + value.getPort()));
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> values) {
        if (values == null || values.size() > 32) throw new IllegalArgumentException("fixed headers are invalid");
        Map<String, List<String>> out = new TreeMap<>();
        values.forEach((name, entries) -> {
            String safe = header(name);
            if (forbiddenHeader(safe) || entries == null || entries.isEmpty() || entries.size() > 8) {
                throw new IllegalArgumentException("fixed headers are invalid");
            }
            List<String> copied = entries.stream().map(OpenApiClientProfile::headerValue).toList();
            if (out.putIfAbsent(safe, copied) != null) throw new IllegalArgumentException("duplicate header");
        });
        return Map.copyOf(out);
    }

    private static Set<String> headerNames(Set<String> names) {
        if (names == null || names.size() > 32) throw new IllegalArgumentException("header names are invalid");
        return names.stream().map(OpenApiClientProfile::header).peek(name -> {
            if (forbiddenHeader(name)) throw new IllegalArgumentException("header is forbidden");
        }).collect(Collectors.toUnmodifiableSet());
    }

    static String header(String value) {
        String safe = token(value, "header", 64).toLowerCase(Locale.ROOT);
        if (!safe.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw new IllegalArgumentException("header is invalid");
        return safe;
    }

    private static boolean forbiddenHeader(String name) {
        return Set.of("authorization", "cookie", "proxy-authorization", "host", "content-length", "connection",
                "transfer-encoding", "upgrade").contains(name) || name.startsWith("sec-");
    }

    private static String headerValue(String value) {
        if (value == null || value.length() > 512 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("header value is invalid");
        }
        return value;
    }

    private static String optionalToken(String value) {
        return value == null || value.isBlank() ? null : token(value, "credential", 256);
    }

    private static String token(String value, String field, int max) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0," + (max - 1) + "}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
