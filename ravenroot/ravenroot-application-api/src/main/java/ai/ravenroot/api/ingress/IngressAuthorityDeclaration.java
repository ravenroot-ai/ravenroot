package ai.ravenroot.api.ingress;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, operator-approved ingress envelope for one enabled package.
 * @param packageId stable package id for this declaration.
 * @param listenerId stable listener id for this declaration.
 * @param pathPrefix path prefix supplied to this declaration.
 * @param requiredScopes required scopes supplied to this declaration.
 * @param maxRoutes max routes supplied to this declaration.
 * @param maxConcurrentRequests max concurrent requests supplied to this declaration.
 * @param maxRequestBytes max request bytes supplied to this declaration.
 * @param maxResponseBytes max response bytes supplied to this declaration.
 * @param requestTimeout the request timeout constraint applied while processing the request.
 */
public record IngressAuthorityDeclaration(String packageId, String listenerId, String pathPrefix,
                                          Set<String> requiredScopes, int maxRoutes,
                                          int maxConcurrentRequests, long maxRequestBytes,
                                          long maxResponseBytes, Duration requestTimeout) {
    public static final int HARD_MAX_ROUTES = 256;
    public static final int HARD_MAX_CONCURRENT_REQUESTS = 1_024;
    public static final long HARD_MAX_BODY_BYTES = 16L * 1024 * 1024;
    public static final Duration HARD_MAX_TIMEOUT = Duration.ofMinutes(5);
/**
 * Enforces the hard operator safety bounds and snapshots the required scope set.
 */
    public IngressAuthorityDeclaration {
        packageId = text(packageId, "packageId");
        listenerId = text(listenerId, "listenerId");
        pathPrefix = path(pathPrefix);
        requiredScopes = Set.copyOf(Objects.requireNonNull(requiredScopes, "requiredScopes"));
        if (maxRoutes < 1 || maxConcurrentRequests < 1 || maxRequestBytes < 1 || maxResponseBytes < 1) {
            throw new IllegalArgumentException("ingress capacities must be positive");
        }
        if (maxRoutes > HARD_MAX_ROUTES || maxConcurrentRequests > HARD_MAX_CONCURRENT_REQUESTS
                || maxRequestBytes > HARD_MAX_BODY_BYTES || maxResponseBytes > HARD_MAX_BODY_BYTES) {
            throw new IllegalArgumentException("ingress capacity exceeds hard maximum");
        }
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative() || requestTimeout.compareTo(HARD_MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }
    private static String text(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
    private static String path(String value) {
        value = text(value, "pathPrefix");
        if (!value.startsWith("/") || value.contains("//") || value.contains("..") || value.endsWith("/")) {
            throw new IllegalArgumentException("pathPrefix is invalid");
        }
        return value;
    }
}
