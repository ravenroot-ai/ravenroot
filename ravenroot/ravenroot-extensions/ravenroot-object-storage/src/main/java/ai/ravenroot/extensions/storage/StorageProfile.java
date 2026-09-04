package ai.ravenroot.extensions.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable operator authority for one S3-compatible bucket. */
public record StorageProfile(
        String name, URI origin, String region, String bucket, String keyPrefix,
        AddressingStyle addressingStyle, String signingBindingId, Set<Operation> allowedOperations,
        Set<String> allowedContentTypes, boolean allowIfMatch, boolean allowIfNoneMatch,
        int maxObjectBytes, int timeoutMs, int maxConcurrency, int maxRequestsPerSecond) {

    public enum AddressingStyle { PATH, VIRTUAL_HOSTED }
    public enum Operation { GET, PUT, LIST, DELETE, DELETE_VERSION }
    public static final int HARD_MAX_OBJECT_BYTES = 16 * 1024 * 1024;

    public StorageProfile {
        name = token(name, "name", 64);
        origin = exactHttpsOrigin(origin);
        region = awsComponent(region, "region");
        bucket = bucket(bucket);
        keyPrefix = StorageUri.validatePrefix(keyPrefix == null ? "" : keyPrefix);
        addressingStyle = Objects.requireNonNull(addressingStyle, "addressingStyle");
        signingBindingId = token(signingBindingId, "signingBindingId", 256);
        allowedOperations = Set.copyOf(Objects.requireNonNull(allowedOperations, "allowedOperations"));
        if (allowedOperations.isEmpty()) throw new IllegalArgumentException("allowedOperations is empty");
        if (allowedOperations.contains(Operation.DELETE_VERSION)
                && !allowedOperations.contains(Operation.DELETE)) {
            throw new IllegalArgumentException("version delete requires delete authority");
        }
        if (allowedContentTypes == null || allowedContentTypes.size() > 32) {
            throw new IllegalArgumentException("allowedContentTypes is invalid");
        }
        allowedContentTypes = allowedContentTypes.stream().map(StorageProfile::contentType)
                .collect(Collectors.toUnmodifiableSet());
        if (maxObjectBytes < 1 || maxObjectBytes > HARD_MAX_OBJECT_BYTES
                || timeoutMs < 1 || timeoutMs > Duration.ofMinutes(5).toMillis()
                || maxConcurrency < 1 || maxConcurrency > 256
                || maxRequestsPerSecond < 1 || maxRequestsPerSecond > 10_000) {
            throw new IllegalArgumentException("profile limit is invalid");
        }
        if (addressingStyle == AddressingStyle.VIRTUAL_HOSTED
                && !(origin.getHost().equals(bucket) || origin.getHost().startsWith(bucket + "."))) {
            throw new IllegalArgumentException("virtual-hosted origin must already be bucket scoped");
        }
    }

    private static URI exactHttpsOrigin(URI value) {
        Objects.requireNonNull(value, "origin");
        if (!"https".equals(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || !(value.getPath().isEmpty() || "/".equals(value.getPath()))) {
            throw new IllegalArgumentException("origin must be an exact HTTPS authority");
        }
        return URI.create("https://" + value.getHost().toLowerCase(Locale.ROOT)
                + (value.getPort() == -1 || value.getPort() == 443 ? "" : ":" + value.getPort()));
    }

    private static String bucket(String value) {
        String safe = token(value, "bucket", 63).toLowerCase(Locale.ROOT);
        if (safe.length() < 3 || safe.contains("..") || !safe.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]")
                || safe.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("bucket is invalid");
        }
        return safe;
    }

    private static String contentType(String value) {
        if (value == null || value.length() > 128 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || !value.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
            throw new IllegalArgumentException("content type is invalid");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static String token(String value, String field, int max) {
        if (value == null || value.length() > max || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String awsComponent(String value, String field) {
        String safe = token(value, field, 63);
        if (!safe.matches("[A-Za-z0-9][A-Za-z0-9-]*")) throw new IllegalArgumentException(field + " is invalid");
        return safe;
    }
}
