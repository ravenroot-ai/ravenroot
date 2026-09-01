package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class TelegramTestSupport {
    static final String PROFILE = "primary";
    static final String TOKEN = "123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef";

    private TelegramTestSupport() { }

    static TelegramProfile profile(String tenant, String credentialRef, int retries) {
        return new TelegramProfile(tenant, PROFILE, credentialRef, Set.of("-100123", "@channel_name"),
                Set.of("sendMessage", "sendPhoto", "answerCallbackQuery", "editMessageText",
                        "editMessageCaption", "editMessageReplyMarkup", "deleteMessage"),
                Set.of("example.test"), false,
                4, 30, 1_000, 2_000, 4_096, 10_000_000, 20, retries);
    }

    static NodeAction action(String tenant, CredentialResolver credentials, HttpClient client, URI origin, int retries) {
        TelegramProfileResolver profiles = (requestedTenant, name) -> requestedTenant.equals(tenant) && name.equals(PROFILE)
                ? Optional.of(profile(tenant, "telegram-bot", retries)) : Optional.empty();
        return new TelegramSendNodeBehavior(credentials, profiles, client, origin)
                .create(new NodeConfiguration("telegram", "telegram.send", Map.of("botProfile", PROFILE)));
    }

    static NodeMessage message(String tenant, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("realm", tenant, "subject", PrincipalType.USER, "issuer"),
                id, id, id, id, Set.of(), "telegram", payload, Map.of());
    }

    static Map<String, Object> textPayload(String text) {
        return Map.of("version", "telegram.send.v1", "chatId", "-100123", "text", text,
                "correlationId", "correlation-1");
    }
}
