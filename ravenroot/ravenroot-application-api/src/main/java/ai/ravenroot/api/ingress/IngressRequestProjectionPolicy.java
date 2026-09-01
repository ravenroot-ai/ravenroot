package ai.ravenroot.api.ingress;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Operator-owned bounds for the request data projected into one ingress package.
 *
 * <p>Header names are canonical lower-case HTTP field names. Credential, routing and hop-by-hop
 * fields are never eligible, even when named by a package. The optional idempotency header must be
 * part of the allowlist and is data only: it grants no replay, acknowledgement or authentication
 * semantics in core.</p>
 * @param packageId stable package id for this declaration.
 * @param allowedHeaders whether allowed headers is enabled for this operation.
 * @param idempotencyHeader idempotency header supplied to this declaration.
 * @param maxRelativePathBytes max relative path bytes supplied to this declaration.
 * @param maxQueryParameters max query parameters supplied to this declaration.
 * @param maxQueryBytes max query bytes supplied to this declaration.
 * @param maxHeaderCount the max header count constraint applied while processing the request.
 * @param maxHeaderBytes max header bytes supplied to this declaration.
 * @param maxHeaderValueBytes max header value bytes supplied to this declaration.
 */
public record IngressRequestProjectionPolicy(
        String packageId,
        Set<String> allowedHeaders,
        String idempotencyHeader,
        int maxRelativePathBytes,
        int maxQueryParameters,
        int maxQueryBytes,
        int maxHeaderCount,
        int maxHeaderBytes,
        int maxHeaderValueBytes) {

    public static final int HARD_MAX_RELATIVE_PATH_BYTES = 8 * 1024;
    public static final int HARD_MAX_QUERY_PARAMETERS = 256;
    public static final int HARD_MAX_QUERY_BYTES = 16 * 1024;
    public static final int HARD_MAX_HEADER_COUNT = 32;
    public static final int HARD_MAX_HEADER_BYTES = 8 * 1024;
    public static final int HARD_MAX_HEADER_VALUE_BYTES = 2 * 1024;

    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "proxy-authorization", "proxy-authenticate", "cookie", "set-cookie",
            "host", "connection", "proxy-connection", "content-length", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "www-authenticate", "authentication-info",
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip",
            "x-api-key", "api-key", "x-auth-token", "x-access-token");

/**
 * Canonicalizes the allowlist and rejects credential, routing, or over-limit request projection.
 */
    public IngressRequestProjectionPolicy {
        packageId = token(packageId, "packageId");
        Objects.requireNonNull(allowedHeaders, "allowedHeaders");
        var canonical = new LinkedHashSet<String>();
        for (String header : allowedHeaders) {
            String normalized = header(header);
            if (!canonical.add(normalized)) {
                throw new IllegalArgumentException("allowedHeaders contains a canonical duplicate");
            }
        }
        allowedHeaders = Set.copyOf(canonical);
        if (idempotencyHeader != null) {
            idempotencyHeader = header(idempotencyHeader);
            if (!allowedHeaders.contains(idempotencyHeader)) {
                throw new IllegalArgumentException("idempotencyHeader must be explicitly allowlisted");
            }
        }
        positiveAtMost(maxRelativePathBytes, HARD_MAX_RELATIVE_PATH_BYTES, "maxRelativePathBytes");
        positiveAtMost(maxQueryParameters, HARD_MAX_QUERY_PARAMETERS, "maxQueryParameters");
        positiveAtMost(maxQueryBytes, HARD_MAX_QUERY_BYTES, "maxQueryBytes");
        positiveAtMost(maxHeaderCount, HARD_MAX_HEADER_COUNT, "maxHeaderCount");
        positiveAtMost(maxHeaderBytes, HARD_MAX_HEADER_BYTES, "maxHeaderBytes");
        positiveAtMost(maxHeaderValueBytes, HARD_MAX_HEADER_VALUE_BYTES, "maxHeaderValueBytes");
        if (allowedHeaders.size() > maxHeaderCount) {
            throw new IllegalArgumentException("allowedHeaders exceeds maxHeaderCount");
        }
    }

/**
 * Safe projection defaults used for packages compiled before this policy existed.
 * @param packageId enabled package for which to create the conservative projection.
 * @return policy with no exposed headers and bounded default request dimensions.
 */
    public static IngressRequestProjectionPolicy defaults(String packageId) {
        return new IngressRequestProjectionPolicy(packageId, Set.of(), null,
                2 * 1024, 64, 4 * 1024, 16, 4 * 1024, 512);
    }

    private static String token(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String header(String value) {
        if (value == null || !value.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")) {
            throw new IllegalArgumentException("header name is invalid");
        }
        String canonical = value.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_HEADERS.contains(canonical)) {
            throw new IllegalArgumentException("credential, routing and hop-by-hop headers are forbidden");
        }
        return canonical;
    }

    private static void positiveAtMost(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(field + " is outside the supported bounds");
        }
    }
}
