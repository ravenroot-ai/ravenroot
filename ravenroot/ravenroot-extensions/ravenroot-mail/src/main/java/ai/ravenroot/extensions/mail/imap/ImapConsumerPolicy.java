package ai.ravenroot.extensions.mail.imap;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Operator-owned authority and hard bounds for one long-lived mailbox consumer. */
record ImapConsumerPolicy(String tenant, String profile, String folder, int pollIntervalMs,
                          int batchSize, int scanWindow, int retryBackoffMs,
                          int maxRetryBackoffMs, int poisonAttempts,
                          int maxMessageBytes, String contentMode, int maxPreviewChars,
                          Set<String> allowedHeaders) {
    static final int MIN_POLL_INTERVAL_MS = 100;
    static final int MIN_RETRY_BACKOFF_MS = 100;
    static final int MAX_ALLOWED_HEADERS = 32;
    static final int MAX_HEADER_NAME_BYTES = 64;
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "bcc",
            "received", "return-path", "authentication-results", "dkim-signature",
            "domainkey-signature", "arc-seal", "arc-message-signature",
            "arc-authentication-results");

    ImapConsumerPolicy {
        if (!identifier(tenant) || !identifier(profile) || !folder(folder)
                || pollIntervalMs < MIN_POLL_INTERVAL_MS || pollIntervalMs > 60_000
                || batchSize < 1 || batchSize > 100 || scanWindow < batchSize || scanWindow > 512
                || retryBackoffMs < MIN_RETRY_BACKOFF_MS || retryBackoffMs > 60_000
                || maxRetryBackoffMs < retryBackoffMs || maxRetryBackoffMs > 60_000
                || poisonAttempts < 1 || poisonAttempts > 100
                || maxMessageBytes < 1 || maxMessageBytes > 1_048_576
                || !Set.of("metadata", "preview").contains(contentMode)
                || maxPreviewChars < 0 || maxPreviewChars > 65_536
                || contentMode.equals("metadata") && maxPreviewChars != 0) {
            throw new IllegalArgumentException("invalid IMAP consumer policy");
        }
        allowedHeaders = normalizeHeaders(allowedHeaders);
    }

    ImapConsumerPolicy(String tenant, String profile, String folder, int pollIntervalMs,
                       int batchSize, int scanWindow, int retryBackoffMs,
                       int maxRetryBackoffMs, int poisonAttempts,
                       int maxMessageBytes, String contentMode, int maxPreviewChars) {
        this(tenant, profile, folder, pollIntervalMs, batchSize, scanWindow, retryBackoffMs,
                maxRetryBackoffMs, poisonAttempts, maxMessageBytes, contentMode, maxPreviewChars,
                Set.of());
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    static boolean folder(String value) {
        return value != null && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= 256
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    static Set<String> parseHeaders(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return normalizeHeaders(Set.of(raw.split(",", -1)));
    }

    static boolean sensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Set<String> normalizeHeaders(Set<String> values) {
        if (values == null || values.size() > MAX_ALLOWED_HEADERS)
            throw new IllegalArgumentException("invalid IMAP consumer headers");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String name = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9][a-z0-9-]*")
                    || name.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_NAME_BYTES
                    || sensitiveHeader(name))
                throw new IllegalArgumentException("invalid IMAP consumer headers");
            normalized.add(name);
        }
        if (normalized.size() > MAX_ALLOWED_HEADERS)
            throw new IllegalArgumentException("invalid IMAP consumer headers");
        return Set.copyOf(normalized);
    }
}
