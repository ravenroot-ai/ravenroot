package ai.ravenroot.extensions.amqp091;

import java.util.Set;

/** Immutable operator-owned endpoint, authority and resource ceilings for one tenant. */
public record AmqpProfile(
        String tenant,
        String name,
        String host,
        int port,
        boolean tls,
        String vhost,
        String username,
        String credentialRef,
        String defaultExchange,
        Set<String> exchanges,
        String defaultRoutingKey,
        Set<String> routingKeys,
        Set<String> headers,
        Set<String> replyTo,
        boolean allowPersistent,
        int maxPriority,
        long maxExpirationMs,
        int maxConcurrency,
        int maxPerSecond,
        int timeoutMs,
        int maxBodyBytes,
        int retries) {

    public AmqpProfile {
        exchanges = copy(exchanges);
        routingKeys = copy(routingKeys);
        headers = copy(headers);
        replyTo = copy(replyTo);
        if (!identifier(tenant) || !identifier(name) || host == null || host.isBlank()
                || vhost == null || !AmqpWireLimits.isShortstr(vhost)
                || vhost.codePoints().anyMatch(Character::isISOControl)
                || username == null || username.isBlank() || username.length() > 128
                || !AmqpWireLimits.isShortstr(username) || credentialRef == null || credentialRef.isBlank()
                || credentialRef.length() > 256 || !validName(defaultExchange, 255, true)
                || !validName(defaultRoutingKey, 255, false)
                || port < 1 || port > 65_535 || maxPriority < 0 || maxPriority > 9
                || maxExpirationMs < 0 || maxExpirationMs > 86_400_000L
                || maxConcurrency < 1 || maxConcurrency > 16 || maxPerSecond < 1 || maxPerSecond > 100
                || timeoutMs < 100 || timeoutMs > 30_000 || maxBodyBytes < 1 || maxBodyBytes > 1_048_576
                || retries < 0 || retries > 3 || !validNames(exchanges, 255, true)
                || !validNames(routingKeys, 255, false) || !validHeaders(headers)
                || !validNames(replyTo, 255, false) || !tls && !loopback(host)) {
            throw new IllegalArgumentException("invalid AMQP operator profile");
        }
    }

    boolean allowsExchange(String exchange) {
        return defaultExchange.equals(exchange) || exchanges.contains(exchange);
    }

    boolean allowsRoutingKey(String routingKey) {
        return defaultRoutingKey.equals(routingKey) || routingKeys.contains(routingKey);
    }

    boolean allowsHeader(String header) {
        return headers.contains(header);
    }

    boolean allowsReplyTo(String value) {
        return value == null || replyTo.contains(value);
    }

    private static Set<String> copy(Set<String> values) {
        return Set.copyOf(values == null ? Set.of() : values);
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static boolean validNames(Set<String> values, int maximum, boolean emptyAllowed) {
        return values.size() <= 64 && values.stream().allMatch(value -> value != null
                && (emptyAllowed ? value.isEmpty() || !value.isBlank() : !value.isBlank())
                && validName(value, maximum, emptyAllowed));
    }

    private static boolean validName(String value, int maximum, boolean emptyAllowed) {
        return value != null && (emptyAllowed && value.isEmpty() || !value.isBlank()) && value.length() <= maximum
                && AmqpWireLimits.isShortstr(value)
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validHeaders(Set<String> values) {
        return values.size() <= 32 && values.stream().allMatch(value -> value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") && AmqpWireLimits.isShortstr(value));
    }

    private static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
