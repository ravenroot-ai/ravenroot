package ai.ravenroot.extensions.amqp091;

import java.util.Set;

/** Explicit operator authority and bounds for consuming from one broker-owned queue. */
record AmqpConsumerPolicy(String tenant, String profile, String queue, int prefetch,
                          Set<String> headers, String identityHeader, int maxBodyBytes,
                          int maxHeaderBytes, int retryBackoffMs, int maxRetryBackoffMs,
                          int poisonAttempts, String poisonPolicy, int drainTimeoutMs) {
    static final int MIN_RETRY_BACKOFF_MS = 100;
    static final int MIN_MAX_RETRY_BACKOFF_MS = 1_000;

    AmqpConsumerPolicy {
        headers = Set.copyOf(headers == null ? Set.of() : headers);
        identityHeader = identityHeader == null ? "" : identityHeader.strip();
        if (!identifier(tenant) || !identifier(profile) || !wireName(queue)
                || prefetch < 1 || prefetch > 1_024 || headers.size() > 32
                || headers.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                || !identityHeader.isEmpty() && !headers.contains(identityHeader)
                || !identityHeader.isEmpty() && maxHeaderBytes < 1
                || maxBodyBytes < 1 || maxBodyBytes > 1_048_576
                || maxHeaderBytes < 0 || maxHeaderBytes > 65_536
                || retryBackoffMs < MIN_RETRY_BACKOFF_MS || maxRetryBackoffMs < retryBackoffMs
                || maxRetryBackoffMs < MIN_MAX_RETRY_BACKOFF_MS
                || maxRetryBackoffMs > 60_000 || poisonAttempts < 1 || poisonAttempts > 100
                || !Set.of("reject", "dead-letter").contains(poisonPolicy)
                || drainTimeoutMs < 0 || drainTimeoutMs > 30_000) {
            throw new IllegalArgumentException("invalid AMQP consumer policy");
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static boolean wireName(String value) {
        return value != null && !value.isBlank() && value.length() <= 255
                && AmqpWireLimits.isShortstr(value)
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
