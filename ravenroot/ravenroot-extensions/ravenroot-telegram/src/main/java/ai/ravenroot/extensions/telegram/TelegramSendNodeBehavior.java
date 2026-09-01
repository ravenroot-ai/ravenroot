package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.CredentialResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Ordinary request/response Telegram Bot API sender. */
public final class TelegramSendNodeBehavior implements NodeBehavior {
    public static final URI PRODUCTION_ORIGIN = TelegramBotApiClient.PRODUCTION_ORIGIN;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final PayloadLimits JSON_LIMITS = new PayloadLimits(MAX_RESPONSE_BYTES, 16, 256, 2_048, 32_768, 256);
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "chatId", "messageThreadId", "businessConnectionId",
            "text", "photo", "parseMode", "entities", "disableNotification", "protectContent", "replyToMessageId",
            "inlineKeyboard", "correlationId");
    private final TelegramProfileResolver profiles;
    private final TelegramBotApiClient botApi;
    private final TelegramRuntimeControls controls;

    public TelegramSendNodeBehavior() { this(new EnvironmentTelegramCredentialResolver(), new EnvironmentTelegramProfileResolver()); }
    public TelegramSendNodeBehavior(CredentialResolver credentials, TelegramProfileResolver profiles) {
        this(profiles, new TelegramBotApiClient(credentials), TelegramRuntimeControls.PRODUCTION);
    }
    TelegramSendNodeBehavior(CredentialResolver credentials, TelegramProfileResolver profiles, HttpClient client, URI origin) {
        this(profiles, new TelegramBotApiClient(credentials, client, origin),
                new RuntimeControls(System::nanoTime, task -> Thread.startVirtualThread(task), 32, 16, 4_096));
    }
    TelegramSendNodeBehavior(CredentialResolver credentials, TelegramProfileResolver profiles, HttpClient client, URI origin,
                             RuntimeControls controls) {
        this(profiles, new TelegramBotApiClient(credentials, client, origin), controls);
    }
    private TelegramSendNodeBehavior(TelegramProfileResolver profiles, TelegramBotApiClient botApi,
                                     TelegramRuntimeControls controls) {
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.botApi = java.util.Objects.requireNonNull(botApi);
        this.controls = java.util.Objects.requireNonNull(controls);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("telegram.send", "Send Telegram message", "Telegram",
                "Sends one bounded telegram.send.v1 message through an operator-owned bot profile.", "actor", false,
                List.of(NodePropertyDescriptor.required("botProfile", "Bot profile", NodePropertyType.STRING,
                                "Opaque tenant-scoped operator profile."),
                        optionalInt("requestTimeoutMs", "Request timeout", "May only tighten the operator profile."),
                        optionalInt("maxTextChars", "Text limit", "May only tighten the operator profile."),
                        optionalInt("maxMediaBytes", "Media byte limit", "May only tighten the operator profile."),
                        optionalInt("maxButtons", "Button limit", "May only tighten the operator profile."),
                        optionalInt("maxConcurrency", "Concurrency", "May only tighten the operator profile (1–16)."),
                        optionalInt("retries", "Pre-accept retries", "May only tighten the operator profile (0–3).")
                        // PERS-04, ADR 0022. telegram.send deliberately does NOT declare
                        // RecoveryRepeatabilityProperty, for the same reason mail.send does not.
                        // sendMessage and sendPhoto have no idempotency key, so a repeat is a second
                        // message in the chat, not a retry of the first; and the transport already
                        // draws the only line it can draw -- 'retries' is bounded to the pre-accept
                        // phase, and TelegramBotApiClient returns AMBIGUOUS rather than retrying once
                        // a request may have been received. Recovery cannot see which side of that
                        // line an attempt died on, so no instance value would be honest here.
                        //
                        // The three telegram action nodes DO declare it, and the difference is not a
                        // matter of degree: an edit, a delete and a callback answer converge on
                        // replay, a message send accumulates. See TelegramActionNodeBehavior.
                        ),
                Set.of("network", "credential-reference", "side-effect"));
    }
    private static NodePropertyDescriptor optionalInt(String name, String display, String description) {
        return NodePropertyDescriptor.optional(name, display, NodePropertyType.INTEGER, description, "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        java.util.concurrent.ConcurrentHashMap<String, TelegramRuntimeControls.Gate> actions =
                new java.util.concurrent.ConcurrentHashMap<>();
        return message -> {
            final Settings settings;
            final Payload payload;
            final TelegramRuntimeControls.Admission admission;
            try {
                settings = Settings.from(configuration, profiles, message.tenantId());
                payload = Payload.from(message.payload(), settings);
                admission = controls.acquire(message.tenantId(), settings.profile, settings.maxConcurrency, actions);
                if (!admission.acquired()) return CompletableFuture.failedFuture(
                        new TelegramSendException(TelegramSendException.Code.CAPACITY_UNAVAILABLE, "Telegram capacity is unavailable"));
                if (!controls.rates.allow(message.tenantId(), settings.profile.name(), settings.profile.maxPerSecond())) {
                    admission.release();
                    return CompletableFuture.completedFuture(result("RATE_LIMITED", payload, 0, 429,
                            "LOCAL_RATE_LIMIT", null, null, null, null));
                }
            } catch (TelegramSendException failure) { return CompletableFuture.failedFuture(failure); }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try { return send(settings, payload); }
                    finally { admission.release(); }
                }, controls.executor);
            } catch (RuntimeException rejected) {
                admission.release();
                return CompletableFuture.failedFuture(new TelegramSendException(
                        TelegramSendException.Code.CAPACITY_UNAVAILABLE, "Telegram capacity is unavailable"));
            }
        };
    }

    private NodeResult send(Settings settings, Payload payload) {
        String method = payload.photo == null ? "sendMessage" : "sendPhoto";
        RequestBody body = payload.requestBody();
        TelegramBotApiClient.Outcome outcome = botApi.call(settings.profile, settings.requestTimeoutMs,
                settings.retries, method, new TelegramBotApiClient.Body(body.bytes, body.contentType));
        if (outcome.state() == TelegramBotApiClient.State.TRANSPORT_UNAVAILABLE)
            return result("TEMPORARY_FAILURE", payload, 0, 0, "TRANSPORT_UNAVAILABLE", null, null, null, method);
        if (outcome.state() == TelegramBotApiClient.State.CONNECT_FAILED)
            return result("TEMPORARY_FAILURE", payload, outcome.attempt(), 0, "CONNECT_FAILED", null, null, null, method);
        if (outcome.state() == TelegramBotApiClient.State.AMBIGUOUS)
            return result("AMBIGUOUS", payload, outcome.attempt(), 0, "DELIVERY_STATE_UNKNOWN", null, null, null, method);
        TelegramBotApiClient.ApiResponse api = outcome.response();
        Long messageId = api.result() == null ? null : longNumber(api.result().get("message_id"));
        Long date = api.result() == null ? null : longNumber(api.result().get("date"));
        if (api.ok() && api.httpStatus() >= 200 && api.httpStatus() < 300)
            return result("SENT", payload, outcome.attempt(), api.code(), "SENT", messageId, date, null, method);
        String status = api.code() == 401 || api.code() == 403 ? "REJECTED" : api.code() == 429 ? "RATE_LIMITED"
                : api.httpStatus() >= 500 ? "TEMPORARY_FAILURE" : api.code() == 400 ? "REJECTED" : "PERMANENT_FAILURE";
        String message = status.equals("RATE_LIMITED") ? "RETRY_LATER" : status;
        Object safePayload = result(status, payload, outcome.attempt(), api.code(), message,
                null, null, api.migrateTo(), method).payload();
        Map<String, Object> output = new LinkedHashMap<>();
        if (safePayload instanceof Map<?, ?> safeMap)
            safeMap.forEach((key, value) -> { if (key instanceof String name) output.put(name, value); });
        if (api.retryAfter() != null) output.put("retry_after", api.retryAfter());
        return NodeResult.continueWith(output);
    }

    private static NodeResult result(String status, Payload payload, int attempt, int code, String message,
                                     Long messageId, Long date, Long migrateTo, String method) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", "telegram.send.v1"); out.put("status", status); out.put("chatId", payload.chatId);
        out.put("correlationId", payload.correlationId); out.put("attempt", attempt); out.put("code", code); out.put("message", message);
        if (messageId != null) out.put("messageId", messageId); if (date != null) out.put("timestamp", date);
        if (migrateTo != null) out.put("migrate_to_chat_id", migrateTo);
        if (method != null) out.put("metadata", Map.of("method", method));
        return NodeResult.continueWith(out);
    }

    private record Settings(TelegramProfile profile, int requestTimeoutMs, int maxTextChars, int maxMediaBytes,
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
            if (profile == null) throw new TelegramSendException(
                    TelegramSendException.Code.CONFIGURATION, "Telegram bot profile is unavailable");
            if (!tenant.equals(profile.tenant()) || !name.equals(profile.name()))
                throw new TelegramSendException(TelegramSendException.Code.CONFIGURATION,
                        "Telegram bot profile identity does not match the request");
            return new Settings(profile,
                    tighten(configuration, "requestTimeoutMs", profile.requestTimeoutMs(), 100),
                    tighten(configuration, "maxTextChars", profile.maxTextChars(), 1),
                    tighten(configuration, "maxMediaBytes", profile.maxMediaBytes(), 1),
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

    private record Payload(String chatId, Long threadId, String businessId, String text, Photo photo,
                           String parseMode, List<Map<String, Object>> entities, boolean disableNotification,
                           boolean protectContent, Long replyTo, List<List<Map<String, Object>>> keyboard,
                           String correlationId) {
        static Payload from(Object raw, Settings settings) {
            if (!(raw instanceof Map<?, ?> map)) throw invalid("Telegram payload must be an object");
            for (Object key : map.keySet()) if (!(key instanceof String name) || !PAYLOAD_FIELDS.contains(name))
                throw invalid("Telegram payload contains an unknown field");
            if (!"telegram.send.v1".equals(string(map.get("version")))) throw invalid("Unsupported Telegram payload version");
            String chat = requiredString(map, "chatId", 64);
            if (!validChat(chat) || !settings.profile.allowsChat(chat)) throw invalid("Telegram chat is not allowed");
            String text = optionalUnicodeString(map.get("text"), settings.maxTextChars);
            Photo photo = Photo.from(map.get("photo"), settings.maxMediaBytes);
            if ((text == null || text.isEmpty()) && photo == null) throw invalid("Telegram text or photo is required");
            if (!settings.profile.allowsMethod(photo == null ? "sendMessage" : "sendPhoto"))
                throw invalid("Telegram method is not allowed by the bot profile");
            if (photo != null && text != null && text.codePointCount(0, text.length()) > Math.min(1_024, settings.maxTextChars))
                throw invalid("Telegram photo caption is too long");
            String parseMode = optionalString(map.get("parseMode"), 16);
            if (parseMode != null && !Set.of("HTML", "MarkdownV2").contains(parseMode)) throw invalid("Telegram parse mode is not allowed");
            List<Map<String, Object>> entities = TelegramSendNodeBehavior.entities(map.get("entities"), text);
            if (parseMode != null && !entities.isEmpty()) throw invalid("Telegram parse mode and entities are mutually exclusive");
            String business = optionalString(map.get("businessConnectionId"), 128);
            if (business != null && !settings.profile.allowBusiness()) throw invalid("Telegram business authority is not allowed");
            List<List<Map<String, Object>>> keyboard = TelegramSendNodeBehavior.keyboard(
                    map.get("inlineKeyboard"), settings.maxButtons, settings.profile);
            return new Payload(chat, positiveLong(map.get("messageThreadId")), business, text, photo, parseMode, entities,
                    bool(map.get("disableNotification")), bool(map.get("protectContent")), positiveLong(map.get("replyToMessageId")),
                    keyboard, optionalString(map.get("correlationId"), 128) == null ? "" : optionalString(map.get("correlationId"), 128));
        }

        RequestBody requestBody() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("chat_id", chatId); if (threadId != null) fields.put("message_thread_id", threadId);
            if (businessId != null) fields.put("business_connection_id", businessId);
            if (text != null) fields.put(photo == null ? "text" : "caption", text);
            if (parseMode != null) fields.put("parse_mode", parseMode);
            if (!entities.isEmpty()) fields.put(photo == null ? "entities" : "caption_entities", entities);
            if (disableNotification) fields.put("disable_notification", true);
            if (protectContent) fields.put("protect_content", true);
            if (replyTo != null) fields.put("reply_parameters", Map.of("message_id", replyTo));
            if (!keyboard.isEmpty()) fields.put("reply_markup", Map.of("inline_keyboard", keyboard));
            if (photo == null) return RequestBody.json(json(fields));
            return RequestBody.multipart(fields, photo);
        }
    }

    private record Photo(byte[] bytes, String filename, String mimeType) {
        static Photo from(Object raw, int maxBytes) {
            if (raw == null) return null;
            if (!(raw instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(Set.of("contentBase64", "filename", "mimeType")::contains))
                throw invalid("Telegram photo must be a bounded inline upload");
            String encoded = requiredString(map, "contentBase64", Math.min(14_000_000, maxBytes * 2));
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded); }
            catch (IllegalArgumentException malformed) { throw invalid("Telegram photo Base64 is invalid"); }
            if (bytes.length == 0 || bytes.length > maxBytes) throw invalid("Telegram photo exceeds its byte limit");
            String filename = optionalString(map.get("filename"), 64); if (filename == null) filename = "photo.jpg";
            if (!filename.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("Telegram photo filename is unsafe");
            String mime = optionalString(map.get("mimeType"), 32); if (mime == null) mime = "image/jpeg";
            if (!Set.of("image/jpeg", "image/png", "image/webp").contains(mime)) throw invalid("Telegram photo media type is unsafe");
            return new Photo(bytes, filename, mime);
        }
    }

    private record RequestBody(byte[] bytes, String contentType) {
        static RequestBody json(String json) { return new RequestBody(json.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8"); }
        static RequestBody multipart(Map<String, Object> fields, Photo photo) {
            String boundary = "ravenroot-" + UUID.randomUUID();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(photo.bytes.length + 8_192);
                for (var entry : fields.entrySet()) {
                    part(out, boundary, entry.getKey(), entry.getValue() instanceof String || entry.getValue() instanceof Number
                            || entry.getValue() instanceof Boolean ? entry.getValue().toString()
                            : TelegramSendNodeBehavior.json(entry.getValue()));
                }
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\""
                        + photo.filename + "\"\r\nContent-Type: " + photo.mimeType + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(photo.bytes); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
                return new RequestBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
        private static void part(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                    + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    static List<Map<String, Object>> entities(Object raw, String text) {
        if (raw == null) return List.of(); if (text == null || !(raw instanceof List<?> list) || list.size() > 100) throw invalid("Telegram entities are invalid");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(Set.of("type", "offset", "length", "language")::contains))
                throw invalid("Telegram entity is invalid");
            String type = requiredString(map, "type", 32);
            if (!Set.of("bold", "italic", "underline", "strikethrough", "spoiler", "code", "pre", "blockquote").contains(type))
                throw invalid("Telegram entity type is unsafe");
            int offset = nonnegativeInt(map.get("offset")); int length = positiveInt(map.get("length"));
            if ((long) offset + length > text.length() || splitsSurrogatePair(text, offset)
                    || splitsSurrogatePair(text, offset + length))
                throw invalid("Telegram entity range is invalid");
            Map<String, Object> item = new LinkedHashMap<>(); item.put("type", type); item.put("offset", offset); item.put("length", length);
            String language = optionalString(map.get("language"), 32); if (language != null) { if (!type.equals("pre")) throw invalid("Telegram entity language is invalid"); item.put("language", language); }
            out.add(item);
        }
        return List.copyOf(out);
    }

    static List<List<Map<String, Object>>> keyboard(Object raw, int maxButtons, TelegramProfile profile) {
        if (raw == null) return List.of(); if (!(raw instanceof List<?> rows) || rows.size() > 20) throw invalid("Telegram keyboard is invalid");
        int count = 0; List<List<Map<String, Object>>> out = new ArrayList<>();
        for (Object rowValue : rows) {
            if (!(rowValue instanceof List<?> row) || row.isEmpty() || row.size() > 8) throw invalid("Telegram keyboard row is invalid");
            List<Map<String, Object>> outputRow = new ArrayList<>();
            for (Object buttonValue : row) {
                if (++count > maxButtons || !(buttonValue instanceof Map<?, ?> button)
                        || !button.keySet().stream().allMatch(Set.of("text", "callbackData", "url")::contains))
                    throw invalid("Telegram keyboard exceeds its authority");
                String text = requiredString(button, "text", 64); String callback = optionalString(button.get("callbackData"), 64);
                String url = optionalString(button.get("url"), 2_048); if ((callback == null) == (url == null)) throw invalid("Telegram button needs one action");
                Map<String, Object> item = new LinkedHashMap<>(); item.put("text", text);
                if (callback != null) { int bytes = callback.getBytes(StandardCharsets.UTF_8).length; if (bytes < 1 || bytes > 64) throw invalid("Telegram callback exceeds 64 bytes"); item.put("callback_data", callback); }
                else { URI uri; try { uri = URI.create(url); } catch (RuntimeException malformed) { throw invalid("Telegram button URL is invalid"); }
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                            || !profile.allowsUrlHost(uri.getHost())) throw invalid("Telegram button URL is not allowed"); item.put("url", uri.toString()); }
                outputRow.add(item);
            }
            out.add(List.copyOf(outputRow));
        }
        return List.copyOf(out);
    }

    static boolean validChat(String value) {
        if (value.matches("@[A-Za-z][A-Za-z0-9_]{4,31}")) return true;
        try { return Long.parseLong(value) != 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static void requireWellFormed(String value) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid("Telegram text contains malformed Unicode"); }
            else if (Character.isLowSurrogate(c)) throw invalid("Telegram text contains malformed Unicode");
        }
    }
    private static boolean splitsSurrogatePair(String value, int offset) {
        return offset > 0 && offset < value.length() && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset));
    }
    static String json(Object value) { return PayloadJson.write(PayloadValue.fromJava(value, JSON_LIMITS)); }
    static String requiredString(Map<?, ?> map, String name, int max) { String value = optionalString(map.get(name), max); if (value == null || value.isBlank()) throw invalid("Telegram field is required: " + name); return value; }
    static String optionalString(Object value, int max) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > max) throw invalid("Telegram text field is invalid");
        requireWellFormed(text);
        return text;
    }
    static String optionalUnicodeString(Object value, int maxCodePoints) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw invalid("Telegram text field is invalid");
        requireWellFormed(text);
        if (text.codePointCount(0, text.length()) > maxCodePoints) throw invalid("Telegram text field is invalid");
        return text;
    }
    static String string(Object value) { return value instanceof String text ? text : null; }
    static boolean bool(Object value) { if (value == null) return false; if (!(value instanceof Boolean flag)) throw invalid("Telegram boolean field is invalid"); return flag; }
    static Long positiveLong(Object value) { if (value == null) return null; long parsed = longValue(value); if (parsed <= 0) throw invalid("Telegram identifier must be positive"); return parsed; }
    static int nonnegativeInt(Object value) { long parsed = longValue(value); if (parsed < 0 || parsed > Integer.MAX_VALUE) throw invalid("Telegram integer is invalid"); return (int) parsed; }
    static int positiveInt(Object value) { int parsed = nonnegativeInt(value); if (parsed == 0) throw invalid("Telegram integer must be positive"); return parsed; }
    static long longValue(Object value) {
        if (!(value instanceof Number number) || number instanceof Float || number instanceof Double)
            throw invalid("Telegram integer field is invalid");
        if (number instanceof BigInteger integer) {
            try { return integer.longValueExact(); }
            catch (ArithmeticException outsideSigned64) { throw invalid("Telegram integer field is invalid"); }
        }
        if (number instanceof java.math.BigDecimal decimal) {
            try { return decimal.longValueExact(); }
            catch (ArithmeticException outsideSigned64) { throw invalid("Telegram integer field is invalid"); }
        }
        return number.longValue();
    }
    private static Long longNumber(Object value) { return value instanceof Number number && !(number instanceof Double || number instanceof Float) ? number.longValue() : null; }
    static TelegramSendException invalid(String message) { return new TelegramSendException(TelegramSendException.Code.INVALID_INPUT, message); }

    static final class RuntimeControls extends TelegramRuntimeControls {
        RuntimeControls(java.util.function.LongSupplier ticker, java.util.concurrent.Executor executor,
                        int globalLimit, int tenantLimit, int maximumRateKeys) {
            super(ticker, executor, globalLimit, tenantLimit, maximumRateKeys, 4_096);
        }
    }
}
