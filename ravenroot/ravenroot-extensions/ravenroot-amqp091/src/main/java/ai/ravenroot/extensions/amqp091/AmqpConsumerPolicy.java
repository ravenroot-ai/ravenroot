package ai.ravenroot.extensions.amqp091;

import java.util.Set;

/** Explicit operator authority and bounds for consuming from a queue or ephemeral topic subscription. */
record AmqpConsumerPolicy(String tenant, String profile, String queue, int prefetch,
                          Set<String> headers, String identityHeader, int maxBodyBytes,
                          int maxHeaderBytes, int retryBackoffMs, int maxRetryBackoffMs,
                          int poisonAttempts, String poisonPolicy, int drainTimeoutMs,
                          Set<String> topicExchanges, Set<String> bindingPatterns,
                          int maxEphemeralSubscriptions) {
    static final int MIN_RETRY_BACKOFF_MS = 100;
    static final int MIN_MAX_RETRY_BACKOFF_MS = 1_000;

    AmqpConsumerPolicy {
        headers = Set.copyOf(headers == null ? Set.of() : headers);
        topicExchanges = Set.copyOf(topicExchanges == null ? Set.of() : topicExchanges);
        bindingPatterns = Set.copyOf(bindingPatterns == null ? Set.of() : bindingPatterns);
        identityHeader = identityHeader == null ? "" : identityHeader.strip();
        if (!identifier(tenant) || !identifier(profile)
                || queue == null || (!queue.isEmpty() && !wireName(queue))
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
                || drainTimeoutMs < 0 || drainTimeoutMs > 30_000
                || topicExchanges.stream().anyMatch(value -> !wireName(value))
                || bindingPatterns.stream().anyMatch(value -> !wireName(value))
                || maxEphemeralSubscriptions < 0 || maxEphemeralSubscriptions > 16
                || maxEphemeralSubscriptions == 0 && (!topicExchanges.isEmpty() || !bindingPatterns.isEmpty())
                || maxEphemeralSubscriptions > 0 && (topicExchanges.isEmpty() || bindingPatterns.isEmpty())) {
            throw new IllegalArgumentException("invalid AMQP consumer policy");
        }
        if (queue.isEmpty() && maxEphemeralSubscriptions == 0) {
            throw new IllegalArgumentException("invalid AMQP consumer policy");
        }
    }

    /** Compatibility shape for queue-only consumer policies. */
    AmqpConsumerPolicy(String tenant, String profile, String queue, int prefetch,
                       Set<String> headers, String identityHeader, int maxBodyBytes,
                       int maxHeaderBytes, int retryBackoffMs, int maxRetryBackoffMs,
                       int poisonAttempts, String poisonPolicy, int drainTimeoutMs) {
        this(tenant, profile, queue, prefetch, headers, identityHeader, maxBodyBytes, maxHeaderBytes,
                retryBackoffMs, maxRetryBackoffMs, poisonAttempts, poisonPolicy, drainTimeoutMs,
                Set.of(), Set.of(), 0);
    }

    boolean allowsSubscription(String exchange, String bindingPattern) {
        return maxEphemeralSubscriptions > 0
                && topicExchanges.contains(exchange) && bindingPatterns.contains(bindingPattern);
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
