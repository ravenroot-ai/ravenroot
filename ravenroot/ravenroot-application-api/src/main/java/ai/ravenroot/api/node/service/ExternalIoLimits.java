package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable, finite limits for one external input/output operation.
 *
 * <p>Callers may only narrow these limits. The managed service intersects them with operator
 * policy before allocating transport resources. An empty media-type set means that the media type
 * is not interpreted by this layer; it does not disable byte, encoding, deadline, or cancellation
 * limits.</p>
 *
 * @param maximumRequestBytes maximum bytes handed to the external operation
 * @param maximumEncodedResponseBytes maximum response bytes read from the transport
 * @param maximumDecodedResponseBytes maximum bytes produced by transport decoding
 * @param maximumOutputBytes maximum bytes produced by caller projection or canonicalization
 * @param maximumDecompressionRatio maximum decoded-to-encoded ratio for compressed data
 * @param maximumDuration maximum lifetime of the external operation
 * @param cancellationBound requested upper bound for cooperative resource teardown; a service that
 *        cannot guarantee it must fail closed or document its narrower cancellation capability
 * @param acceptedMediaTypes accepted lower-case media types, without parameters; empty means opaque
 * @param acceptedContentEncodings accepted lower-case content encodings
 */
public record ExternalIoLimits(
        long maximumRequestBytes,
        long maximumEncodedResponseBytes,
        long maximumDecodedResponseBytes,
        long maximumOutputBytes,
        int maximumDecompressionRatio,
        Duration maximumDuration,
        Duration cancellationBound,
        Set<String> acceptedMediaTypes,
        Set<String> acceptedContentEncodings) {

    /** Finite compatibility limits used by legacy managed HTTP request constructors. */
    public static final ExternalIoLimits MANAGED_HTTP_DEFAULTS = new ExternalIoLimits(
            1024L * 1024, 8L * 1024 * 1024, 8L * 1024 * 1024, 8L * 1024 * 1024, 1,
            Duration.ofSeconds(30), Duration.ofSeconds(2), Set.of(), Set.of("identity"));

    /** Creates validated finite limits. */
    public ExternalIoLimits {
        positiveBytes(maximumRequestBytes, "maximumRequestBytes");
        positiveBytes(maximumEncodedResponseBytes, "maximumEncodedResponseBytes");
        positiveBytes(maximumDecodedResponseBytes, "maximumDecodedResponseBytes");
        positiveBytes(maximumOutputBytes, "maximumOutputBytes");
        if (maximumDecompressionRatio < 1 || maximumDecompressionRatio > 1_000) {
            throw new IllegalArgumentException("maximumDecompressionRatio is out of range");
        }
        maximumDuration = positive(maximumDuration, "maximumDuration");
        cancellationBound = positive(cancellationBound, "cancellationBound");
        acceptedMediaTypes = normalized(acceptedMediaTypes, true);
        acceptedContentEncodings = normalized(acceptedContentEncodings, false);
        if (acceptedContentEncodings.isEmpty()) {
            throw new IllegalArgumentException("acceptedContentEncodings is empty");
        }
    }

    /**
     * Returns the intersection of this caller limit and a trusted upper bound.
     *
     * @param authority trusted operator or runtime ceiling
     * @return a limit no wider than either input
     */
    public ExternalIoLimits intersect(ExternalIoLimits authority) {
        Objects.requireNonNull(authority, "authority");
        Set<String> media = intersectSets(acceptedMediaTypes, authority.acceptedMediaTypes, true);
        if (!acceptedMediaTypes.isEmpty() && !authority.acceptedMediaTypes.isEmpty() && media.isEmpty()) {
            throw new IllegalArgumentException("media type policies do not overlap");
        }
        Set<String> encodings = intersectSets(acceptedContentEncodings,
                authority.acceptedContentEncodings, false);
        if (encodings.isEmpty()) {
            throw new IllegalArgumentException("content encoding policies do not overlap");
        }
        return new ExternalIoLimits(
                Math.min(maximumRequestBytes, authority.maximumRequestBytes),
                Math.min(maximumEncodedResponseBytes, authority.maximumEncodedResponseBytes),
                Math.min(maximumDecodedResponseBytes, authority.maximumDecodedResponseBytes),
                Math.min(maximumOutputBytes, authority.maximumOutputBytes),
                Math.min(maximumDecompressionRatio, authority.maximumDecompressionRatio),
                minimum(maximumDuration, authority.maximumDuration),
                minimum(cancellationBound, authority.cancellationBound), media, encodings);
    }

    /**
     * Creates HTTP limits with identical encoded, decoded, and projected ceilings.
     * @param requestBytes maximum request bytes
     * @param responseBytes maximum response bytes at every representation layer
     * @param duration maximum operation duration
     * @param mediaTypes accepted response media types, or empty for opaque bytes
     * @return finite identity-encoded HTTP limits
     */
    public static ExternalIoLimits http(long requestBytes, long responseBytes, Duration duration,
                                        Set<String> mediaTypes) {
        return new ExternalIoLimits(requestBytes, responseBytes, responseBytes, responseBytes, 1, duration,
                Duration.ofSeconds(2), mediaTypes, Set.of("identity"));
    }

    /**
     * Creates HTTP limits that allow a single gzip layer under a finite expansion ratio.
     * @param requestBytes maximum request bytes
     * @param encodedResponseBytes maximum transport response bytes
     * @param decodedResponseBytes maximum decoded response bytes
     * @param outputBytes maximum projected or canonical output bytes
     * @param ratio maximum gzip decoded-to-encoded ratio
     * @param duration maximum operation duration
     * @param mediaTypes accepted response media types, or empty for opaque bytes
     * @return finite identity-or-gzip HTTP limits
     */
    public static ExternalIoLimits compressedHttp(long requestBytes, long encodedResponseBytes,
                                                   long decodedResponseBytes, long outputBytes,
                                                   int ratio, Duration duration, Set<String> mediaTypes) {
        return new ExternalIoLimits(requestBytes, encodedResponseBytes, decodedResponseBytes,
                outputBytes, ratio, duration, Duration.ofSeconds(2), mediaTypes,
                Set.of("identity", "gzip"));
    }

    /**
     * Refuses a projected or canonical output beyond this operation's final output ceiling.
     * @param bytes projected output size to validate
     */
    public void requireOutputBytes(long bytes) {
        if (bytes < 0 || bytes > maximumOutputBytes) {
            throw new IllegalArgumentException("external output exceeds its limit");
        }
    }

    private static Set<String> intersectSets(Set<String> left, Set<String> right, boolean emptyMeansAny) {
        if (emptyMeansAny && left.isEmpty()) return right;
        if (emptyMeansAny && right.isEmpty()) return left;
        return left.stream().filter(right::contains).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> normalized(Set<String> values, boolean allowEmpty) {
        Objects.requireNonNull(values, "values");
        if (!allowEmpty && values.isEmpty()) return Set.of();
        if (values.size() > 32) throw new IllegalArgumentException("too many protocol values");
        return values.stream().map(value -> {
            if (value == null || value.length() > 128
                    || !value.matches("[A-Za-z0-9!#$&^_.+\\-]+(?:/[A-Za-z0-9!#$&^_.+\\-]+)?")) {
                throw new IllegalArgumentException("invalid protocol value");
            }
            return value.toLowerCase(Locale.ROOT);
        }).collect(Collectors.toUnmodifiableSet());
    }

    private static void positiveBytes(long value, String name) {
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException(name + " is out of range");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration minimum(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
