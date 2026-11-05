package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;

import java.net.http.HttpHeaders;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Rate-limit classification and bounded retry delay shared by both source providers. */
final class AuthoringRetry {
    static boolean limited(int status, HttpHeaders headers) {
        return status == 429
                || status == 403
                        && (headers.firstValue("Retry-After").isPresent()
                                || headers.firstValue("X-RateLimit-Remaining")
                                        .filter("0"::equals)
                                        .isPresent()
                                || headers.firstValue("RateLimit-Remaining")
                                        .filter("0"::equals)
                                        .isPresent());
    }

    static boolean retryable(int status, HttpHeaders headers) {
        return limited(status, headers) || status == 502 || status == 503 || status == 504;
    }

    static void pause(HttpHeaders headers, int attempt, Duration timeout) {
        long millis = Math.min(1000L, 100L << attempt);
        String value = headers.firstValue("Retry-After").orElse(null);
        try {
            if (value != null)
                millis = Math.max(0, Math.multiplyExact(Long.parseLong(value), 1000L));
        } catch (NumberFormatException failure) {
            try {
                millis =
                        Math.max(
                                0,
                                Duration.between(
                                                Instant.now(),
                                                ZonedDateTime.parse(
                                                                value,
                                                                DateTimeFormatter
                                                                        .RFC_1123_DATE_TIME)
                                                        .toInstant())
                                        .toMillis());
            } catch (RuntimeException invalid) {
                throw unavailable();
            }
        } catch (ArithmeticException invalid) {
            throw unavailable();
        }
        if (value == null) {
            String reset = headers.firstValue("X-RateLimit-Reset").orElse(null);
            if (reset != null)
                try {
                    millis =
                            Math.max(
                                    millis,
                                    Math.max(
                                            0,
                                            Math.multiplyExact(Long.parseLong(reset), 1000L)
                                                    - System.currentTimeMillis()));
                } catch (RuntimeException invalid) {
                    throw unavailable();
                }
        }
        // Do not retry earlier than the provider permits. Excessive delays fail promptly, never
        // sleep unboundedly.
        if (millis > Math.min(30_000L, timeout.toMillis())) throw unavailable();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static GraphAuthoringException unavailable() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
    }
}
