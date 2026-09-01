package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelegramActionNodeBehaviorIntegrationTest {
    @Test void invokesEachExplicitBotApiMethodWithItsVersionedContract() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":7}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            String tenant = tenant();

            Map<String, Object> callback = new LinkedHashMap<>(Map.of(
                    "version", "telegram.answer.callback.v1", "callbackId", "callback-1",
                    "text", "Done", "showAlert", true, "cacheTime", 3,
                    "url", "https://example.test/game", "correlationId", "c-1"));
            assertEquals("ANSWERED", output(action(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK,
                    tenant, telegram, profile(tenant, allMethods(), Set.of("*"), 2, 30))
                    .handle(TelegramTestSupport.message(tenant, callback)).toCompletableFuture().join()).get("status"));

            Map<String, Object> edit = new LinkedHashMap<>(Map.of(
                    "version", "telegram.edit.message.v1", "editType", "text", "chatId", "-100123",
                    "messageId", 7, "text", "Updated", "parseMode", "HTML"));
            assertEquals("EDITED", output(action(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE,
                    tenant, telegram, profile(tenant, allMethods(), Set.of("*"), 2, 30))
                    .handle(TelegramTestSupport.message(tenant, edit)).toCompletableFuture().join()).get("status"));

            Map<String, Object> delete = Map.of("version", "telegram.delete.message.v1",
                    "chatId", "-100123", "messageId", 7);
            assertEquals("DELETED", output(action(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE,
                    tenant, telegram, profile(tenant, allMethods(), Set.of("*"), 2, 30))
                    .handle(TelegramTestSupport.message(tenant, delete)).toCompletableFuture().join()).get("status"));

            assertEquals(3, telegram.requests().size());
            assertTrue(telegram.requests().get(0).path().endsWith("/answerCallbackQuery"));
            assertTrue(telegram.requests().get(0).body().contains("\"callback_query_id\":\"callback-1\""));
            assertTrue(telegram.requests().get(1).path().endsWith("/editMessageText"));
            assertTrue(telegram.requests().get(1).body().contains("\"text\":\"Updated\""));
            assertTrue(telegram.requests().get(2).path().endsWith("/deleteMessage"));
        }
    }

    @Test void supportsCaptionAndMarkupEditsIncludingInlineAddressingAndKeyboardRemoval() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            String tenant = tenant();
            NodeAction edit = action(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE, tenant, telegram,
                    profile(tenant, allMethods(), Set.of("-100123"), 0, 30));
            edit.handle(TelegramTestSupport.message(tenant, Map.of(
                    "version", "telegram.edit.message.v1", "editType", "caption",
                    "chatId", "-100123", "messageId", 8, "text", "caption")))
                    .toCompletableFuture().join();
            edit.handle(TelegramTestSupport.message(tenant, Map.of(
                    "version", "telegram.edit.message.v1", "editType", "markup",
                    "inlineMessageId", "inline-1", "inlineKeyboard", java.util.List.of())))
                    .toCompletableFuture().join();
            assertTrue(telegram.requests().get(0).path().endsWith("/editMessageCaption"));
            assertTrue(telegram.requests().get(0).body().contains("\"caption\":\"caption\""));
            assertTrue(telegram.requests().get(1).path().endsWith("/editMessageReplyMarkup"));
            assertTrue(telegram.requests().get(1).body().contains("\"inline_message_id\":\"inline-1\""));
            assertTrue(telegram.requests().get(1).body().contains("\"inline_keyboard\":[]"));
        }
    }

    @Test void rejectsDuplicateCallbackLocallyAndClassifiesExpiredCallbacks() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            String tenant = tenant();
            NodeAction answer = action(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK, tenant, telegram,
                    profile(tenant, allMethods(), Set.of("*"), 0, 30));
            Map<String, Object> inboundFixture = Map.of("callback_query", Map.of("id", "normalized-callback"));
            String callbackId = (String) ((Map<?, ?>) inboundFixture.get("callback_query")).get("id");
            Map<String, Object> payload = Map.of("version", "telegram.answer.callback.v1",
                    "callbackId", callbackId);
            assertEquals("ANSWERED", output(answer.handle(TelegramTestSupport.message(tenant, payload))
                    .toCompletableFuture().join()).get("status"));
            Map<String, Object> duplicate = output(answer.handle(TelegramTestSupport.message(tenant, payload))
                    .toCompletableFuture().join());
            assertEquals("REJECTED", duplicate.get("status"));
            assertEquals("DUPLICATE_CALLBACK", duplicate.get("message"));
            assertEquals(1, telegram.requests().size());
        }
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(400, "{\"ok\":false,\"error_code\":400,\"description\":"
                    + "\"Bad Request: query is too old and response timeout expired or query ID is invalid\"}");
            String tenant = tenant();
            Map<String, Object> result = output(action(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK,
                    tenant, telegram, profile(tenant, allMethods(), Set.of("*"), 0, 30))
                    .handle(TelegramTestSupport.message(tenant, Map.of(
                            "version", "telegram.answer.callback.v1", "callbackId", "old")))
                    .toCompletableFuture().join());
            assertEquals("EXPIRED", result.get("status"));
            assertEquals("CALLBACK_EXPIRED", result.get("message"));
        }
    }

    @Test void retriesAnExpiredCallbackReservationButNeverSendsAPreExpiryDuplicate() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            String tenant = tenant();
            AtomicLong ticker = new AtomicLong();
            var controls = new TelegramRuntimeControls(ticker::get, Runnable::run, 32, 16, 4_096, 4_096);
            NodeAction answer = action(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK, tenant, telegram,
                    profile(tenant, allMethods(), Set.of("*"), 0, 30), controls);
            Map<String, Object> payload = Map.of("version", "telegram.answer.callback.v1", "callbackId", "retry");

            assertEquals("ANSWERED", output(answer.handle(TelegramTestSupport.message(tenant, payload))
                    .toCompletableFuture().join()).get("status"));
            assertEquals("REJECTED", output(answer.handle(TelegramTestSupport.message(tenant, payload))
                    .toCompletableFuture().join()).get("status"));
            assertEquals(1, telegram.requests().size());

            ticker.set(java.time.Duration.ofHours(1).toNanos());
            assertEquals("ANSWERED", output(answer.handle(TelegramTestSupport.message(tenant, payload))
                    .toCompletableFuture().join()).get("status"));
            assertEquals(2, telegram.requests().size());
            assertTrue(telegram.requests().stream().allMatch(request ->
                    request.path().endsWith("/answerCallbackQuery")));
        }
    }

    @Test void classifiesKnownEditDeleteAndAuthorityFailuresWithoutLeakingDescriptions() throws Exception {
        assertRemote(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE, 400,
                "message is not modified: " + TelegramTestSupport.TOKEN, "REJECTED", "ALREADY_NOT_MODIFIED");
        assertRemote(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE, 400,
                "message to edit not found", "REJECTED", "NOT_FOUND");
        assertRemote(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE, 400,
                "message can't be deleted", "REJECTED", "PERMISSION_DENIED");
        assertRemote(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE, 403,
                "forbidden " + TelegramTestSupport.TOKEN, "REJECTED", "PERMISSION_DENIED");
        assertRemote(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE, 500,
                "message to edit not found", "TEMPORARY_FAILURE", "REMOTE_TEMPORARY_FAILURE");
    }

    @Test void bounds429RetryAndTreatsServerMalformedOversizedAndDisconnectOutcomesSafely() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":0}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            String tenant = tenant();
            Map<String, Object> result = delete(telegram, tenant, 1);
            assertEquals("DELETED", result.get("status"));
            assertEquals(2, result.get("attempt"));
            assertEquals(2, telegram.requests().size());
        }
        assertDeleteOutcome(500, "application/json", "{\"ok\":false,\"error_code\":500}",
                "TEMPORARY_FAILURE");
        assertDeleteOutcome(200, "application/json", "not-json", "AMBIGUOUS");
        assertDeleteOutcome(200, "application/json", " ".repeat(65_537), "AMBIGUOUS");
        try (var telegram = new FakeTelegramHttps()) {
            telegram.disconnect();
            assertEquals("AMBIGUOUS", delete(telegram, tenant(), 0).get("status"));
        }
    }

    @Test void payloadAndProfileAuthorityFailClosedBeforeCredentialsOrNetwork() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            AtomicInteger resolutions = new AtomicInteger();
            String tenant = tenant();
            TelegramProfile profile = profile(tenant, Set.of("editMessageText"), Set.of("-100123"), 0, 30);
            var behavior = new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE,
                    ignored -> { resolutions.incrementAndGet(); return Optional.of(secret()); },
                    (requested, name) -> Optional.of(profile), telegram.client(), telegram.origin());
            NodeAction edit = behavior.create(configuration(behavior));
            assertInvalid(edit, tenant, Map.of("version", "telegram.edit.message.v1", "editType", "text",
                    "chatId", "-999", "messageId", 1, "text", "blocked"));
            assertInvalid(edit, tenant, Map.of("version", "telegram.edit.message.v1", "editType", "markup",
                    "chatId", "-100123", "messageId", 1, "inlineKeyboard", java.util.List.of()));
            assertEquals(0, resolutions.get());
            assertTrue(telegram.requests().isEmpty());
        }
        try (var telegram = new FakeTelegramHttps()) {
            String tenant = tenant();
            TelegramProfile profile = profile(tenant, allMethods(), Set.of("*"), 0, 30);
            NodeAction answer = action(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK, tenant, telegram, profile);
            assertInvalid(answer, tenant, Map.of("version", "telegram.answer.callback.v1", "callbackId", "x",
                    "url", "https://evil.test/game"));
            assertTrue(telegram.requests().isEmpty());
        }
    }

    @Test void requiresMethodSpecificSuccessShapesAndRejectsNullMarkup() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"unexpected\":true}}");
            String tenant = tenant();
            Map<String, Object> callback = output(action(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK,
                    tenant, telegram, profile(tenant, allMethods(), Set.of("*"), 0, 30)).handle(
                    TelegramTestSupport.message(tenant, Map.of("version", "telegram.answer.callback.v1",
                            "callbackId", "shape"))).toCompletableFuture().join());
            assertEquals("AMBIGUOUS", callback.get("status"));
        }
        try (var telegram = new FakeTelegramHttps()) {
            String tenant = tenant();
            NodeAction edit = action(TelegramActionNodeBehavior.Kind.EDIT_MESSAGE, tenant, telegram,
                    profile(tenant, allMethods(), Set.of("*"), 0, 30));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", "telegram.edit.message.v1");
            payload.put("editType", "markup");
            payload.put("chatId", "-100123");
            payload.put("messageId", 1);
            payload.put("inlineKeyboard", null);
            assertInvalid(edit, tenant, payload);
            assertTrue(telegram.requests().isEmpty());
        }
    }

    @Test void credentialRotationRevocationAndClearingApplyToEveryInvocation() throws Exception {
        String replacement = "987654:zyxwvutsrqponmlkjihgfedcbaZYXWVU";
        AtomicReference<String> current = new AtomicReference<>(TelegramTestSupport.TOKEN);
        java.util.List<SecretValue> issued = new java.util.ArrayList<>();
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            String tenant = tenant();
            TelegramProfile profile = profile(tenant, allMethods(), Set.of("*"), 0, 30);
            var behavior = new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE,
                    ignored -> {
                        String value = current.get();
                        if (value == null) return Optional.empty();
                        SecretValue secret = new SecretValue(value.toCharArray());
                        issued.add(secret);
                        return Optional.of(secret);
                    }, (requested, name) -> Optional.of(profile), telegram.client(), telegram.origin());
            NodeAction action = behavior.create(configuration(behavior));
            Map<String, Object> payload = Map.of("version", "telegram.delete.message.v1",
                    "chatId", "-100123", "messageId", 1);
            action.handle(TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join();
            current.set(replacement);
            action.handle(TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join();
            assertTrue(telegram.requests().get(0).path().contains(TelegramTestSupport.TOKEN));
            assertTrue(telegram.requests().get(1).path().contains(replacement));
            assertTrue(issued.stream().flatMapToInt(value -> new String(value.copy()).chars())
                    .allMatch(character -> character == 0));
            current.set(null);
            CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                    TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join());
            assertEquals(TelegramSendException.Code.CREDENTIAL_UNAVAILABLE,
                    ((TelegramSendException) failure.getCause()).code());
            assertEquals(2, telegram.requests().size());
        }
    }

    @Test void sharedRateAuthoritySpansSendAndDestructiveActions() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1}}");
            String tenant = tenant();
            TelegramProfile profile = profile(tenant, allMethods(), Set.of("*"), 0, 1);
            var controls = new TelegramSendNodeBehavior.RuntimeControls(System::nanoTime,
                    Runnable::run, 4, 4, 64);
            var credentials = (ai.ravenroot.api.security.CredentialResolver)
                    ignored -> Optional.of(secret());
            TelegramProfileResolver profiles = (requested, name) -> Optional.of(profile);
            NodeAction send = new TelegramSendNodeBehavior(credentials, profiles, telegram.client(),
                    telegram.origin(), controls).create(new NodeConfiguration("send", "telegram.send",
                    Map.of("botProfile", TelegramTestSupport.PROFILE)));
            var deleteBehavior = new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE,
                    credentials, profiles, telegram.client(), telegram.origin(), controls);
            NodeAction delete = deleteBehavior.create(configuration(deleteBehavior));
            assertEquals("SENT", output(send.handle(TelegramTestSupport.message(tenant,
                    TelegramTestSupport.textPayload("one"))).toCompletableFuture().join()).get("status"));
            assertEquals("RATE_LIMITED", output(delete.handle(TelegramTestSupport.message(tenant, Map.of(
                    "version", "telegram.delete.message.v1", "chatId", "-100123", "messageId", 1)))
                    .toCompletableFuture().join()).get("status"));
            assertEquals(1, telegram.requests().size());
        }
    }

    private static Map<String, Object> delete(FakeTelegramHttps telegram, String tenant, int retries) throws Exception {
        return output(action(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE, tenant, telegram,
                profile(tenant, allMethods(), Set.of("*"), retries, 30)).handle(
                TelegramTestSupport.message(tenant, Map.of("version", "telegram.delete.message.v1",
                        "chatId", "-100123", "messageId", 1))).toCompletableFuture().join());
    }

    private static void assertDeleteOutcome(int status, String contentType, String body, String expected) throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(status, contentType, body);
            assertEquals(expected, delete(telegram, tenant(), 0).get("status"));
            assertEquals(1, telegram.requests().size());
        }
    }

    private static void assertRemote(TelegramActionNodeBehavior.Kind kind, int status, String description,
                                     String expectedStatus, String expectedMessage) throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(status, "{\"ok\":false,\"error_code\":" + status
                    + ",\"description\":\"" + description.replace("\"", "") + "\"}");
            String tenant = tenant();
            Map<String, Object> payload = kind == TelegramActionNodeBehavior.Kind.DELETE_MESSAGE
                    ? Map.of("version", "telegram.delete.message.v1", "chatId", "-100123", "messageId", 1)
                    : Map.of("version", "telegram.edit.message.v1", "editType", "text",
                            "chatId", "-100123", "messageId", 1, "text", "edited");
            Map<String, Object> result = output(action(kind, tenant, telegram,
                    profile(tenant, allMethods(), Set.of("*"), 0, 30)).handle(
                    TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join());
            assertEquals(expectedStatus, result.get("status"));
            assertEquals(expectedMessage, result.get("message"));
            assertFalse(result.toString().contains(TelegramTestSupport.TOKEN));
        }
    }

    private static void assertInvalid(NodeAction action, String tenant, Map<String, Object> payload) {
        CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join());
        assertEquals(TelegramSendException.Code.INVALID_INPUT,
                ((TelegramSendException) failure.getCause()).code());
    }

    private static NodeAction action(TelegramActionNodeBehavior.Kind kind, String tenant,
                                     FakeTelegramHttps telegram, TelegramProfile profile) throws Exception {
        return action(kind, tenant, telegram, profile, null);
    }

    private static NodeAction action(TelegramActionNodeBehavior.Kind kind, String tenant,
                                     FakeTelegramHttps telegram, TelegramProfile profile,
                                     TelegramRuntimeControls controls) throws Exception {
        var behavior = new TelegramActionNodeBehavior(kind, ignored -> Optional.of(secret()),
                (requested, name) -> requested.equals(tenant) ? Optional.of(profile) : Optional.empty(),
                telegram.client(), telegram.origin(), controls == null
                        ? new TelegramRuntimeControls(System::nanoTime, task -> Thread.startVirtualThread(task),
                        32, 16, 4_096, 4_096) : controls);
        return behavior.create(configuration(behavior));
    }

    private static NodeConfiguration configuration(TelegramActionNodeBehavior behavior) {
        return new NodeConfiguration("telegram", behavior.descriptor().behavior(),
                Map.of("botProfile", TelegramTestSupport.PROFILE));
    }

    private static TelegramProfile profile(String tenant, Set<String> methods, Set<String> chats,
                                           int retries, int rate) {
        return new TelegramProfile(tenant, TelegramTestSupport.PROFILE, "telegram-bot", chats, methods,
                Set.of("example.test"), false, 4, rate, 1_000, 2_000,
                4_096, 10_000_000, 20, retries);
    }

    private static Set<String> allMethods() {
        return Set.of("sendMessage", "sendPhoto", "answerCallbackQuery", "editMessageText",
                "editMessageCaption", "editMessageReplyMarkup", "deleteMessage");
    }

    private static SecretValue secret() { return new SecretValue(TelegramTestSupport.TOKEN.toCharArray()); }
    private static String tenant() { return "tenant-action-" + UUID.randomUUID(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> output(NodeResult result) {
        return (Map<String, Object>) result.payload();
    }
}
