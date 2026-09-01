package ai.ravenroot.extensions.mail;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable operator policy. Graph data may select this object but never construct it. */
public record MailProfile(String tenant, String name, String host, int port, String securityMode, boolean allowPlaintext,
                          String authUsername, String credentialRef, String defaultFrom,
                          Set<String> allowedFrom, Set<String> allowedReplyTo,
                          Set<String> allowedRecipients, Set<String> allowedHeaders,
                          int maxRecipients, int maxHeaders, int maxHeaderChars, int maxBodyChars,
                          int maxAttachments, int maxAttachmentBytes, int maxTotalAttachmentBytes,
                          int maxEncodedAttachmentBytes, int connectTimeoutMs, int readTimeoutMs,
                          int writeTimeoutMs, int retries, int maxConcurrency) {
    public MailProfile {
        tenant = required(tenant); name = required(name); host = required(host);
        securityMode = required(securityMode).toUpperCase(Locale.ROOT);
        authUsername = authUsername == null ? "" : authUsername;
        credentialRef = credentialRef == null ? "" : credentialRef;
        defaultFrom = defaultFrom == null ? "" : defaultFrom;
        allowedFrom = normalized(allowedFrom); allowedReplyTo = normalized(allowedReplyTo);
        allowedRecipients = normalized(allowedRecipients); allowedHeaders = normalizedHeaders(allowedHeaders);
        if (!Set.of("SMTPS", "STARTTLS", "SMTP").contains(securityMode) || port < 1 || port > 65535
                || maxRecipients < 1 || maxRecipients > 100 || maxHeaders < 0 || maxHeaders > 40 || maxHeaderChars < 1 || maxHeaderChars > 8192 || maxBodyChars < 1 || maxBodyChars > 1_048_576
                || maxAttachments < 0 || maxAttachments > 10 || maxAttachmentBytes < 1 || maxAttachmentBytes > 5_242_880 || maxTotalAttachmentBytes < 1 || maxTotalAttachmentBytes > 10_485_760
                || maxEncodedAttachmentBytes < 1 || maxEncodedAttachmentBytes > 13_981_016 || connectTimeoutMs < 1 || connectTimeoutMs > 120_000 || readTimeoutMs < 1 || readTimeoutMs > 300_000
                || writeTimeoutMs < 1 || retries < 0 || retries > 3 || maxConcurrency < 1 || maxConcurrency > 16
                || (authUsername.isBlank() != credentialRef.isBlank())) {
            throw new IllegalArgumentException("Invalid mail profile");
        }
        if (allowedFrom.isEmpty() || allowedRecipients.isEmpty() || (!defaultFrom.isBlank() && !allowsAddress(allowedFrom, defaultFrom))) throw new IllegalArgumentException("Mail profile requires sender and recipient policy");
    }
    private static String required(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Blank profile value"); return value; }
    private static Set<String> normalized(Set<String> values) { return values == null ? Set.of() : values.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private static Set<String> normalizedHeaders(Set<String> values) { return values == null ? Set.of() : values.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    boolean allowsAddress(Set<String> policy, String value) { return policy.contains("*") || policy.contains(value.toLowerCase(Locale.ROOT)); }
    boolean allowsHeader(String name) { return allowedHeaders.contains("*") || allowedHeaders.contains(name.toLowerCase(Locale.ROOT)); }
}
