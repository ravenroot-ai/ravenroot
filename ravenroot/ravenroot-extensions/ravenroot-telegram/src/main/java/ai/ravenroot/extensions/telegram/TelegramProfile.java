package ai.ravenroot.extensions.telegram;

import java.util.Set;

public record TelegramProfile(String tenant, String name, String credentialRef, Set<String> allowedChats,
                              Set<String> allowedMethods, Set<String> allowedUrlHosts, boolean allowBusiness,
                              int maxConcurrency, int maxPerSecond, int connectTimeoutMs, int requestTimeoutMs,
                              int maxTextChars, int maxMediaBytes, int maxButtons, int retries) {
    public TelegramProfile {
        if (tenant == null || tenant.isBlank() || name == null || name.isBlank() || credentialRef == null || credentialRef.isBlank())
            throw new IllegalArgumentException("Telegram profile identity and credential reference are required");
        allowedChats = Set.copyOf(allowedChats == null ? Set.of() : allowedChats);
        allowedMethods = Set.copyOf(allowedMethods == null ? Set.of() : allowedMethods);
        allowedUrlHosts = (allowedUrlHosts == null ? Set.<String>of() : allowedUrlHosts).stream()
                .map(host -> host.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maxConcurrency < 1 || maxConcurrency > 16 || maxPerSecond < 1 || maxPerSecond > 30
                || connectTimeoutMs < 100 || connectTimeoutMs > 10_000 || requestTimeoutMs < 100 || requestTimeoutMs > 30_000
                || maxTextChars < 1 || maxTextChars > 4_096 || maxMediaBytes < 1 || maxMediaBytes > 10_000_000
                || maxButtons < 0 || maxButtons > 100 || retries < 0 || retries > 3)
            throw new IllegalArgumentException("Telegram profile limits are outside supported bounds");
        if (!Set.of("sendMessage", "sendPhoto", "answerCallbackQuery", "editMessageText",
                "editMessageCaption", "editMessageReplyMarkup", "deleteMessage").containsAll(allowedMethods))
            throw new IllegalArgumentException("Telegram profile enables an unsupported method");
    }
    boolean allowsChat(String chat) { return allowedChats.contains("*") || allowedChats.contains(chat); }
    boolean allowsMethod(String method) { return allowedMethods.contains(method); }
    boolean allowsUrlHost(String host) { return allowedUrlHosts.contains("*") || allowedUrlHosts.contains(host.toLowerCase(java.util.Locale.ROOT)); }
}
