package ai.ravenroot.extensions.amqp091;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable bounded projection; no delivery tag, channel, credential or raw client object escapes. */
final class AmqpDeliveryEvent {
    private AmqpDeliveryEvent() { }

    static Projected project(AmqpConsumerProtocol.Delivery delivery, AmqpConsumerPolicy policy, int attempt) {
        AmqpConsumerProtocol.Properties properties = delivery.properties();
        String identity = clean(properties.messageId(), 255);
        if (identity == null && !policy.identityHeader().isEmpty()) {
            identity = headerIdentity(properties.headers().get(policy.identityHeader()));
        }
        if (identity == null) throw new Invalid("missing-stable-message-identity");
        byte[] body = delivery.body();
        if (body.length > policy.maxBodyBytes()) throw new Invalid("body-too-large");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "amqp.delivery.v1");
        event.put("exchange", wireText(delivery.exchange(), "exchange-invalid"));
        event.put("routingKey", wireText(delivery.routingKey(), "routing-key-invalid"));
        event.put("redelivered", delivery.redelivered());
        event.put("messageId", identity);
        optional(event, "correlationId", clean(properties.correlationId(), 255));
        optional(event, "replyTo", clean(properties.replyTo(), 255));
        optional(event, "type", clean(properties.type(), 255));
        optional(event, "appId", clean(properties.appId(), 255));
        optional(event, "contentType", clean(properties.contentType(), 255));
        optional(event, "contentEncoding", clean(properties.contentEncoding(), 255));
        if (properties.timestamp() >= 0) event.put("timestamp", properties.timestamp());
        event.put("headers", headers(properties.headers(), policy));
        event.put("body", representation(body));
        event.put("bodySize", body.length);
        event.put("attempt", attempt);
        event.put("correlation", clean(properties.correlationId(), 255) == null
                ? "amqp:" + policy.profile() + ":" + identity : properties.correlationId());
        event.put("provenance", Map.of("source", "amqp-0-9-1", "profile", policy.profile(),
                "queue", policy.queue(), "messageId", identity));
        return new Projected(java.util.Collections.unmodifiableMap(event), identity,
                idempotentKey(policy, identity, delivery, body));
    }

    private static Map<String, Object> headers(Map<String, Object> raw, AmqpConsumerPolicy policy) {
        Map<String, Object> safe = new LinkedHashMap<>();
        int total = 0;
        for (String name : policy.headers().stream().sorted().toList()) {
            if (!raw.containsKey(name)) continue;
            Object projected = scalar(raw.get(name));
            if (projected == null) throw new Invalid("unsupported-header");
            int size = name.getBytes(StandardCharsets.UTF_8).length + projectedSize(projected);
            try { total = Math.addExact(total, size); }
            catch (ArithmeticException overflow) { throw new Invalid("headers-too-large"); }
            if (total > policy.maxHeaderBytes()) throw new Invalid("headers-too-large");
            safe.put(name, projected);
        }
        return java.util.Collections.unmodifiableMap(safe);
    }

    private static Object scalar(Object value) {
        if (value instanceof String text) return safe(text, 4_096, "header-invalid");
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double) return value;
        if (value instanceof byte[] bytes) return Map.of("format", "base64",
                "base64", Base64.getEncoder().encodeToString(bytes), "size", bytes.length);
        return null;
    }

    private static int projectedSize(Object value) {
        if (value instanceof Map<?, ?> encoded) {
            Object base64 = encoded.get("base64");
            return base64 instanceof String text ? text.getBytes(StandardCharsets.UTF_8).length : Integer.MAX_VALUE;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String headerIdentity(Object value) {
        if (value instanceof String text) return clean(text, 255);
        if (value instanceof byte[] bytes) {
            String text = strictUtf8(bytes);
            return text == null ? null : clean(text, 255);
        }
        return null;
    }

    private static Map<String, Object> representation(byte[] bytes) {
        String text = strictUtf8(bytes);
        if (text != null && text.length() <= 16_384 && text.codePoints().noneMatch(Character::isISOControl)) {
            return Map.of("format", "utf8", "text", text);
        }
        return Map.of("format", "base64", "base64", Base64.getEncoder().encodeToString(bytes));
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) { return null; }
    }

    private static String safe(String value, int maximum, String reason) {
        String cleaned = clean(value, maximum);
        if (cleaned == null) throw new Invalid(reason);
        return cleaned;
    }

    private static String wireText(String value, String reason) {
        if (value == null || !AmqpWireLimits.isShortstr(value)
                || value.codePoints().anyMatch(Character::isISOControl)) throw new Invalid(reason);
        return value;
    }

    private static String clean(String value, int maximum) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximum
                || value.codePoints().anyMatch(Character::isISOControl)) return null;
        return value;
    }

    private static void optional(Map<String, Object> target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private static String idempotentKey(AmqpConsumerPolicy policy, String identity,
                                        AmqpConsumerProtocol.Delivery delivery, byte[] body) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return policy.profile() + "/" + encoder.encodeToString(policy.queue().getBytes(StandardCharsets.UTF_8))
                + "/" + encoder.encodeToString(identity.getBytes(StandardCharsets.UTF_8))
                + "/" + contentBinding(policy, identity, delivery, body);
    }

    private static String contentBinding(AmqpConsumerPolicy policy, String identity,
                                         AmqpConsumerProtocol.Delivery delivery, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "ravenroot.amqp.delivery-key.v1".getBytes(StandardCharsets.UTF_8));
            update(digest, policy.profile().getBytes(StandardCharsets.UTF_8));
            update(digest, policy.queue().getBytes(StandardCharsets.UTF_8));
            update(digest, identity.getBytes(StandardCharsets.UTF_8));
            update(digest, delivery.exchange().getBytes(StandardCharsets.UTF_8));
            update(digest, delivery.routingKey().getBytes(StandardCharsets.UTF_8));
            update(digest, body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, byte[] field) {
        digest.update((byte) (field.length >>> 24));
        digest.update((byte) (field.length >>> 16));
        digest.update((byte) (field.length >>> 8));
        digest.update((byte) field.length);
        digest.update(field);
    }

    record Projected(Map<String, Object> payload, String identity, String idempotentKey) { }
    static final class Invalid extends RuntimeException {
        private final String safeReason;
        Invalid(String safeReason) { super(safeReason); this.safeReason = safeReason; }
        String safeReason() { return safeReason; }
    }
}
