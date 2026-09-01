package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit ordinary callback acknowledgement, message edit and message deletion actions. */
final class TelegramActionNodeBehavior implements NodeBehavior {
    enum Kind {
        ANSWER_CALLBACK("telegram.answer.callback", "Answer Telegram callback",
                "Acknowledges one callback query through an operator-owned bot profile."),
        EDIT_MESSAGE("telegram.edit.message", "Edit Telegram message",
                "Explicitly edits text, caption, or inline markup without sending a new message."),
        DELETE_MESSAGE("telegram.delete.message", "Delete Telegram message",
                "Explicitly requests deletion of one operator-authorized Telegram message.");

        final String behavior;
        final String displayName;
        final String description;
        Kind(String behavior, String displayName, String description) {
            this.behavior = behavior;
            this.displayName = displayName;
            this.description = description;
        }
    }

    private final Kind kind;
    private final TelegramProfileResolver profiles;
    private final TelegramBotApiClient botApi;
    private final TelegramRuntimeControls controls;

    TelegramActionNodeBehavior(Kind kind) {
        this(kind, new EnvironmentTelegramCredentialResolver(), new EnvironmentTelegramProfileResolver());
    }

    TelegramActionNodeBehavior(Kind kind, CredentialResolver credentials, TelegramProfileResolver profiles) {
        this(kind, profiles, new TelegramBotApiClient(credentials), TelegramRuntimeControls.PRODUCTION);
    }

    TelegramActionNodeBehavior(Kind kind, CredentialResolver credentials, TelegramProfileResolver profiles,
                               HttpClient client, URI origin) {
        this(kind, profiles, new TelegramBotApiClient(credentials, client, origin),
                new TelegramRuntimeControls(System::nanoTime, task -> Thread.startVirtualThread(task),
                        32, 16, 4_096, 4_096));
    }

    TelegramActionNodeBehavior(Kind kind, CredentialResolver credentials, TelegramProfileResolver profiles,
                               HttpClient client, URI origin, TelegramRuntimeControls controls) {
        this(kind, profiles, new TelegramBotApiClient(credentials, client, origin), controls);
    }

    private TelegramActionNodeBehavior(Kind kind, TelegramProfileResolver profiles,
                                       TelegramBotApiClient botApi, TelegramRuntimeControls controls) {
        this.kind = java.util.Objects.requireNonNull(kind);
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.botApi = java.util.Objects.requireNonNull(botApi);
        this.controls = java.util.Objects.requireNonNull(controls);
    }

    @Override public NodeTypeDescriptor descriptor() {
        var properties = new java.util.ArrayList<NodePropertyDescriptor>();
        properties.add(NodePropertyDescriptor.required("botProfile", "Bot profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile."));
        properties.add(optionalInt("requestTimeoutMs", "Request timeout",
                "May only tighten the operator profile."));
        if (kind != Kind.DELETE_MESSAGE) properties.add(optionalInt("maxTextChars", "Text limit",
                "May only tighten the operator profile."));
        if (kind == Kind.EDIT_MESSAGE) properties.add(optionalInt("maxButtons", "Button limit",
                "May only tighten the operator profile."));
        properties.add(optionalInt("maxConcurrency", "Concurrency",
                "May only tighten the operator profile (1–16)."));
        properties.add(optionalInt("retries", "Pre-accept retries",
                "May only tighten the operator profile (0–3)."));
        // PERS-04, ADR 0022. These three nodes declare the contract and telegram.send does
        // not, and the split is a property of the remote operation rather than a judgement call: an
        // acknowledgement, an edit and a deletion all CONVERGE when replayed -- the second call is
        // refused with a description this transport already classifies (CALLBACK_EXPIRED,
        // NOT_MODIFIED, NOT_FOUND) and the final state of the chat is the one the first call
        // produced -- whereas a send ACCUMULATES, leaving a second message. Convergence also covers
        // the overlap case AttemptRepeatability insists on: two concurrent identical edits, deletes
        // or acknowledgements settle on the same state whichever wins.
        //
        // Still author-declared and never defaulted, because convergence is a claim about the
        // conversation and not only about the API: an edit replayed after somebody else edited the
        // same message overwrites their change. The author knows whether that can happen here; the
        // node does not, and an instance that says nothing parks.
        properties.add(ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(repeatabilityNote()));
        return new NodeTypeDescriptor(kind.behavior, kind.displayName, "Telegram", kind.description,
                "actor", false, List.copyOf(properties),
                Set.of("network", "credential-reference", "side-effect"));
    }

    private static NodePropertyDescriptor optionalInt(String name, String display, String description) {
        return NodePropertyDescriptor.optional(name, display, NodePropertyType.INTEGER, description, "");
    }

    /** What an author has to be able to assert for this particular action to be repeatable. */
    private String repeatabilityNote() {
        return switch (kind) {
            case ANSWER_CALLBACK -> "Whether repeating this acknowledgement after a crash of unknown "
                    + "outcome is safe. A repeat is refused as an expired or invalid query id and "
                    + "leaves the first acknowledgement standing.";
            case EDIT_MESSAGE -> "Whether repeating this edit after a crash of unknown outcome is "
                    + "safe. A replay of the same edit is answered 'message is not modified'; declare "
                    + "it repeatable only where nothing else edits this message.";
            case DELETE_MESSAGE -> "Whether repeating this deletion after a crash of unknown outcome "
                    + "is safe. A repeat is answered 'message to delete not found' and the message is "
                    + "gone either way.";
        };
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        ConcurrentHashMap<String, TelegramRuntimeControls.Gate> actions = new ConcurrentHashMap<>();
        return message -> {
            final Settings settings;
            final Payload payload;
            final TelegramRuntimeControls.Admission admission;
            try {
                settings = Settings.from(configuration, profiles, message.tenantId());
                payload = Payload.from(kind, message.payload(), settings);
                if (!settings.profile.allowsMethod(payload.method()))
                    throw TelegramSendNodeBehavior.invalid("Telegram action is not allowed by the bot profile");
                admission = controls.acquire(message.tenantId(), settings.profile, settings.maxConcurrency, actions);
                if (!admission.acquired()) return CompletableFuture.failedFuture(new TelegramSendException(
                        TelegramSendException.Code.CAPACITY_UNAVAILABLE, "Telegram capacity is unavailable"));
                if (!controls.rates.allow(message.tenantId(), settings.profile.name(), settings.profile.maxPerSecond())) {
                    admission.release();
                    return CompletableFuture.completedFuture(result(payload, "RATE_LIMITED", 0, 429,
                            "LOCAL_RATE_LIMIT", null));
                }
                if (payload instanceof CallbackPayload callback) {
                    TelegramRuntimeControls.CallbackReservation reservation = controls.callbacks.reserve(
                            message.tenantId(), settings.profile.name(), callback.callbackId);
                    if (reservation != TelegramRuntimeControls.CallbackReservation.ACCEPTED) {
                        admission.release();
                        String status = reservation == TelegramRuntimeControls.CallbackReservation.DUPLICATE
                                ? "REJECTED" : "TEMPORARY_FAILURE";
                        String reason = reservation == TelegramRuntimeControls.CallbackReservation.DUPLICATE
                                ? "DUPLICATE_CALLBACK" : "LOCAL_CAPACITY";
                        return CompletableFuture.completedFuture(result(payload, status, 0, 0, reason, null));
                    }
                }
            } catch (TelegramSendException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try { return invoke(settings, payload); }
                    finally { admission.release(); }
                }, controls.executor);
            } catch (RuntimeException rejected) {
                admission.release();
                return CompletableFuture.failedFuture(new TelegramSendException(
                        TelegramSendException.Code.CAPACITY_UNAVAILABLE, "Telegram capacity is unavailable"));
            }
        };
    }

    private NodeResult invoke(Settings settings, Payload payload) {
        byte[] bytes = TelegramSendNodeBehavior.json(payload.body()).getBytes(StandardCharsets.UTF_8);
        TelegramBotApiClient.Outcome outcome = botApi.call(settings.profile, settings.requestTimeoutMs,
                settings.retries, payload.method(),
                new TelegramBotApiClient.Body(bytes, "application/json; charset=utf-8"));
        if (outcome.state() == TelegramBotApiClient.State.TRANSPORT_UNAVAILABLE)
            return result(payload, "TEMPORARY_FAILURE", 0, 0, "TRANSPORT_UNAVAILABLE", null);
        if (outcome.state() == TelegramBotApiClient.State.CONNECT_FAILED)
            return result(payload, "TEMPORARY_FAILURE", outcome.attempt(), 0, "CONNECT_FAILED", null);
        if (outcome.state() == TelegramBotApiClient.State.AMBIGUOUS)
            return result(payload, "AMBIGUOUS", outcome.attempt(), 0, "DELIVERY_STATE_UNKNOWN", null);

        TelegramBotApiClient.ApiResponse api = outcome.response();
        if (api.ok() && api.httpStatus() >= 200 && api.httpStatus() < 300) {
            boolean validResult = payload instanceof EditPayload
                    ? Boolean.TRUE.equals(api.booleanResult()) || api.result() != null
                    : Boolean.TRUE.equals(api.booleanResult());
            if (!validResult)
                return result(payload, "AMBIGUOUS", outcome.attempt(), api.code(),
                        "DELIVERY_STATE_UNKNOWN", null);
            String status = payload instanceof CallbackPayload ? "ANSWERED"
                    : payload instanceof EditPayload ? "EDITED" : "DELETED";
            return result(payload, status, outcome.attempt(), api.code(), status, null);
        }

        String status;
        String reason;
        if (api.httpStatus() >= 500) {
            status = "TEMPORARY_FAILURE";
            reason = "REMOTE_TEMPORARY_FAILURE";
        } else if (payload instanceof CallbackPayload
                && api.reason() == TelegramBotApiClient.RemoteReason.CALLBACK_EXPIRED) {
            status = "EXPIRED";
            reason = "CALLBACK_EXPIRED";
        } else if (payload instanceof EditPayload
                && api.reason() == TelegramBotApiClient.RemoteReason.NOT_MODIFIED) {
            status = "REJECTED";
            reason = "ALREADY_NOT_MODIFIED";
        } else if (api.reason() == TelegramBotApiClient.RemoteReason.NOT_FOUND) {
            status = "REJECTED";
            reason = "NOT_FOUND";
        } else if (api.code() == 401 || api.code() == 403
                || api.reason() == TelegramBotApiClient.RemoteReason.PERMISSION_DENIED) {
            status = "REJECTED";
            reason = "PERMISSION_DENIED";
        } else if (api.code() == 429) {
            status = "RATE_LIMITED";
            reason = "RETRY_LATER";
        } else if (api.code() >= 400 && api.code() < 500) {
            status = "REJECTED";
            reason = "REMOTE_REJECTED";
        } else {
            status = "PERMANENT_FAILURE";
            reason = "PERMANENT_FAILURE";
        }
        return result(payload, status, outcome.attempt(), api.code(), reason, api.retryAfter());
    }

    private static NodeResult result(Payload payload, String status, int attempt, int code,
                                     String message, Integer retryAfter) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", payload.version());
        output.put("status", status);
        output.put("attempt", attempt);
        output.put("code", code);
        output.put("message", message);
        if (!payload.correlationId().isEmpty()) output.put("correlationId", payload.correlationId());
        payload.addIdentifiers(output);
        output.put("metadata", Map.of("method", payload.method()));
        if (retryAfter != null) output.put("retry_after", retryAfter);
        return NodeResult.continueWith(output);
    }

    private record Settings(TelegramProfile profile, int requestTimeoutMs, int maxTextChars,
                            int maxButtons, int maxConcurrency, int retries) {
        static Settings from(NodeConfiguration configuration, TelegramProfileResolver resolver, String tenant) {
            String name = configuration.property("botProfile").orElseThrow(() -> new TelegramSendException(
                    TelegramSendException.Code.CONFIGURATION, "Telegram bot profile is required"));
            final TelegramProfile profile;
            try {
                Optional<TelegramProfile> resolved = resolver.resolve(tenant, name);
                profile = resolved == null ? null : resolved.orElse(null);
            } catch (RuntimeException resolverFailure) {
                throw new TelegramSendException(TelegramSendException.Code.CONFIGURATION,
                        "Telegram bot profile is unavailable");
            }
            if (profile == null || !tenant.equals(profile.tenant()) || !name.equals(profile.name()))
                throw new TelegramSendException(TelegramSendException.Code.CONFIGURATION,
                        "Telegram bot profile is unavailable");
            return new Settings(profile,
                    tighten(configuration, "requestTimeoutMs", profile.requestTimeoutMs(), 100),
                    tighten(configuration, "maxTextChars", profile.maxTextChars(), 1),
                    tighten(configuration, "maxButtons", profile.maxButtons(), 0),
                    tighten(configuration, "maxConcurrency", profile.maxConcurrency(), 1),
                    tighten(configuration, "retries", profile.retries(), 0));
        }

        private static int tighten(NodeConfiguration configuration, String name, int ceiling, int minimum) {
            String raw = configuration.property(name, "");
            if (raw.isBlank()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > ceiling) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException invalid) {
                throw new TelegramSendException(TelegramSendException.Code.CONFIGURATION,
                        "Telegram tightening property is invalid: " + name);
            }
        }
    }

    private sealed interface Payload permits CallbackPayload, EditPayload, DeletePayload {
        String version();
        String correlationId();
        String method();
        Map<String, Object> body();
        void addIdentifiers(Map<String, Object> output);

        static Payload from(Kind kind, Object raw, Settings settings) {
            if (!(raw instanceof Map<?, ?> map))
                throw TelegramSendNodeBehavior.invalid("Telegram payload must be an object");
            return switch (kind) {
                case ANSWER_CALLBACK -> CallbackPayload.from(map, settings);
                case EDIT_MESSAGE -> EditPayload.from(map, settings);
                case DELETE_MESSAGE -> DeletePayload.from(map, settings);
            };
        }
    }

    private record CallbackPayload(String callbackId, String text, boolean showAlert, int cacheTime,
                                   String url, String correlationId) implements Payload {
        private static final Set<String> FIELDS = Set.of("version", "callbackId", "text", "showAlert",
                "cacheTime", "url", "correlationId");

        static CallbackPayload from(Map<?, ?> map, Settings settings) {
            rejectUnknown(map, FIELDS);
            requireVersion(map, "telegram.answer.callback.v1");
            String callbackId = TelegramSendNodeBehavior.requiredString(map, "callbackId", 256);
            if (callbackId.getBytes(StandardCharsets.UTF_8).length > 256)
                throw TelegramSendNodeBehavior.invalid("Telegram callback identifier is too long");
            String text = TelegramSendNodeBehavior.optionalUnicodeString(map.get("text"),
                    Math.min(200, settings.maxTextChars));
            boolean showAlert = TelegramSendNodeBehavior.bool(map.get("showAlert"));
            int cacheTime = map.get("cacheTime") == null ? 0
                    : TelegramSendNodeBehavior.nonnegativeInt(map.get("cacheTime"));
            if (cacheTime > 86_400)
                throw TelegramSendNodeBehavior.invalid("Telegram callback cache time is invalid");
            String url = TelegramSendNodeBehavior.optionalString(map.get("url"), 2_048);
            if (url != null) validateUrl(url, settings.profile);
            return new CallbackPayload(callbackId, text, showAlert, cacheTime, url, correlation(map));
        }

        @Override public String version() { return "telegram.answer.callback.v1"; }
        @Override public String method() { return "answerCallbackQuery"; }
        @Override public Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("callback_query_id", callbackId);
            if (text != null) body.put("text", text);
            if (showAlert) body.put("show_alert", true);
            if (cacheTime != 0) body.put("cache_time", cacheTime);
            if (url != null) body.put("url", url);
            return body;
        }
        @Override public void addIdentifiers(Map<String, Object> output) { output.put("callbackId", callbackId); }
    }

    private record EditPayload(String editType, String chatId, Integer messageId, String inlineMessageId,
                               String businessConnectionId, String text, String parseMode,
                               List<Map<String, Object>> entities, boolean keyboardPresent,
                               List<List<Map<String, Object>>> keyboard, String correlationId) implements Payload {
        private static final Set<String> FIELDS = Set.of("version", "editType", "chatId", "messageId",
                "inlineMessageId", "businessConnectionId", "text", "parseMode", "entities",
                "inlineKeyboard", "correlationId");

        static EditPayload from(Map<?, ?> map, Settings settings) {
            rejectUnknown(map, FIELDS);
            requireVersion(map, "telegram.edit.message.v1");
            String editType = TelegramSendNodeBehavior.requiredString(map, "editType", 16);
            if (!Set.of("text", "caption", "markup").contains(editType))
                throw TelegramSendNodeBehavior.invalid("Telegram edit type is invalid");
            String inline = TelegramSendNodeBehavior.optionalString(map.get("inlineMessageId"), 256);
            String chat = TelegramSendNodeBehavior.optionalString(map.get("chatId"), 64);
            Integer messageId = map.get("messageId") == null ? null
                    : TelegramSendNodeBehavior.positiveInt(map.get("messageId"));
            if (inline != null) {
                if (inline.isBlank() || chat != null || messageId != null)
                    throw TelegramSendNodeBehavior.invalid("Telegram edit addressing is invalid");
            } else if (chat == null || messageId == null || !TelegramSendNodeBehavior.validChat(chat)
                    || !settings.profile.allowsChat(chat)) {
                throw TelegramSendNodeBehavior.invalid("Telegram edit chat is not allowed");
            }
            String business = TelegramSendNodeBehavior.optionalString(map.get("businessConnectionId"), 128);
            if (business != null && !settings.profile.allowBusiness())
                throw TelegramSendNodeBehavior.invalid("Telegram business authority is not allowed");
            String text = null;
            String parseMode = null;
            List<Map<String, Object>> entities = List.of();
            if (!editType.equals("markup")) {
                if (!map.containsKey("text")) throw TelegramSendNodeBehavior.invalid("Telegram edit text is required");
                int ceiling = editType.equals("caption") ? Math.min(1_024, settings.maxTextChars)
                        : settings.maxTextChars;
                text = TelegramSendNodeBehavior.optionalUnicodeString(map.get("text"), ceiling);
                if (text == null || (editType.equals("text") && text.isEmpty()))
                    throw TelegramSendNodeBehavior.invalid("Telegram edit text is invalid");
                parseMode = TelegramSendNodeBehavior.optionalString(map.get("parseMode"), 16);
                if (parseMode != null && !Set.of("HTML", "MarkdownV2").contains(parseMode))
                    throw TelegramSendNodeBehavior.invalid("Telegram parse mode is not allowed");
                entities = TelegramSendNodeBehavior.entities(map.get("entities"), text);
                if (parseMode != null && !entities.isEmpty())
                    throw TelegramSendNodeBehavior.invalid("Telegram parse mode and entities are mutually exclusive");
            } else if (map.containsKey("text") || map.containsKey("parseMode") || map.containsKey("entities")) {
                throw TelegramSendNodeBehavior.invalid("Telegram markup edit contains text fields");
            }
            boolean keyboardPresent = map.containsKey("inlineKeyboard");
            if (editType.equals("markup") && !keyboardPresent)
                throw TelegramSendNodeBehavior.invalid("Telegram markup edit requires inlineKeyboard");
            if (keyboardPresent && map.get("inlineKeyboard") == null)
                throw TelegramSendNodeBehavior.invalid("Telegram keyboard is invalid");
            List<List<Map<String, Object>>> keyboard = keyboardPresent
                    ? TelegramSendNodeBehavior.keyboard(map.get("inlineKeyboard"), settings.maxButtons, settings.profile)
                    : List.of();
            return new EditPayload(editType, chat, messageId, inline, business, text, parseMode,
                    entities, keyboardPresent, keyboard, correlation(map));
        }

        @Override public String version() { return "telegram.edit.message.v1"; }
        @Override public String method() {
            return switch (editType) {
                case "text" -> "editMessageText";
                case "caption" -> "editMessageCaption";
                default -> "editMessageReplyMarkup";
            };
        }
        @Override public Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            address(body, chatId, messageId, inlineMessageId);
            if (businessConnectionId != null) body.put("business_connection_id", businessConnectionId);
            if (editType.equals("text")) body.put("text", text);
            if (editType.equals("caption")) body.put("caption", text);
            if (parseMode != null) body.put("parse_mode", parseMode);
            if (!entities.isEmpty()) body.put(editType.equals("caption") ? "caption_entities" : "entities", entities);
            if (keyboardPresent) body.put("reply_markup", Map.of("inline_keyboard", keyboard));
            return body;
        }
        @Override public void addIdentifiers(Map<String, Object> output) {
            if (inlineMessageId != null) output.put("inlineMessageId", inlineMessageId);
            else { output.put("chatId", chatId); output.put("messageId", messageId); }
            output.put("editType", editType);
        }
    }

    private record DeletePayload(String chatId, int messageId, String correlationId) implements Payload {
        private static final Set<String> FIELDS = Set.of("version", "chatId", "messageId", "correlationId");

        static DeletePayload from(Map<?, ?> map, Settings settings) {
            rejectUnknown(map, FIELDS);
            requireVersion(map, "telegram.delete.message.v1");
            String chat = TelegramSendNodeBehavior.requiredString(map, "chatId", 64);
            if (!TelegramSendNodeBehavior.validChat(chat) || !settings.profile.allowsChat(chat))
                throw TelegramSendNodeBehavior.invalid("Telegram delete chat is not allowed");
            return new DeletePayload(chat, TelegramSendNodeBehavior.positiveInt(map.get("messageId")), correlation(map));
        }

        @Override public String version() { return "telegram.delete.message.v1"; }
        @Override public String method() { return "deleteMessage"; }
        @Override public Map<String, Object> body() {
            return Map.of("chat_id", chatId, "message_id", messageId);
        }
        @Override public void addIdentifiers(Map<String, Object> output) {
            output.put("chatId", chatId);
            output.put("messageId", messageId);
        }
    }

    private static void address(Map<String, Object> body, String chat, Integer message, String inline) {
        if (inline != null) body.put("inline_message_id", inline);
        else { body.put("chat_id", chat); body.put("message_id", message); }
    }

    private static void validateUrl(String value, TelegramProfile profile) {
        final URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException malformed) { throw TelegramSendNodeBehavior.invalid("Telegram URL is invalid"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || !profile.allowsUrlHost(uri.getHost()))
            throw TelegramSendNodeBehavior.invalid("Telegram URL is not allowed");
    }

    private static void rejectUnknown(Map<?, ?> map, Set<String> allowed) {
        for (Object key : map.keySet())
            if (!(key instanceof String name) || !allowed.contains(name))
                throw TelegramSendNodeBehavior.invalid("Telegram payload contains an unknown field");
    }

    private static void requireVersion(Map<?, ?> map, String version) {
        if (!version.equals(TelegramSendNodeBehavior.string(map.get("version"))))
            throw TelegramSendNodeBehavior.invalid("Unsupported Telegram payload version");
    }

    private static String correlation(Map<?, ?> map) {
        String value = TelegramSendNodeBehavior.optionalString(map.get("correlationId"), 128);
        return value == null ? "" : value;
    }
}
