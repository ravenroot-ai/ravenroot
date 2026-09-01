package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class KafkaText {
    private KafkaText() { }
    static byte[] utf8(CharSequence value) {
        String text = value.toString();
        text.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) throw new IllegalArgumentException("control character");
        });
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IllegalArgumentException("malformed UTF-16", malformed);
        }
    }
    static Object validatedJsonSource(Object value, PayloadLimits limits) {
        return new JsonSourceValidator(limits).validate(value, 1);
    }
    static boolean safeCorrelation(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128) return false;
        try { utf8(value); return true; } catch (IllegalArgumentException malformed) { return false; }
    }

    /** Mirrors the supported PayloadValue source model so validation precedes its canonical writer. */
    private static final class JsonSourceValidator {
        private final PayloadLimits limits;
        private int count;

        private JsonSourceValidator(PayloadLimits limits) { this.limits = limits; }

        private Object validate(Object value, int depth) {
            if (depth > limits.maxDepth()) throw new IllegalArgumentException("JSON depth exceeded");
            if (++count > limits.maxValueCount()) throw new IllegalArgumentException("JSON value count exceeded");
            if (value == null || value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long || value instanceof Float
                    || value instanceof Double) return value;
            if (value instanceof CharSequence text) {
                String snapshot = text.toString();
                if (snapshot.length() > limits.maxTextLength()) throw new IllegalArgumentException("JSON text too long");
                utf8(snapshot); return snapshot;
            }
            if (value instanceof Map<?, ?> map) {
                if (map.size() > limits.maxCollectionSize()) throw new IllegalArgumentException("JSON map too large");
                var snapshot = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (snapshot.size() >= limits.maxCollectionSize())
                        throw new IllegalArgumentException("JSON map too large");
                    if (!(entry.getKey() instanceof String key) || key.length() > limits.maxKeyLength())
                        throw new IllegalArgumentException("invalid JSON map key");
                    utf8(key); snapshot.put(key, validate(entry.getValue(), depth + 1));
                }
                return snapshot;
            }
            if (value instanceof Iterable<?> iterable) {
                var snapshot = new ArrayList<Object>();
                for (Object element : iterable) {
                    if (snapshot.size() >= limits.maxCollectionSize())
                        throw new IllegalArgumentException("JSON array too large");
                    snapshot.add(validate(element, depth + 1));
                }
                return snapshot;
            }
            if (value instanceof PayloadValue payload) { validatePayload(payload, depth); return payload; }
            throw new IllegalArgumentException("unsupported JSON value");
        }

        private void validatePayload(PayloadValue value, int depth) {
            switch (value) {
                case PayloadValue.TextValue text -> {
                    if (text.value().length() > limits.maxTextLength())
                        throw new IllegalArgumentException("JSON text too long");
                    utf8(text.value());
                }
                case PayloadValue.ListValue list -> {
                    if (list.values().size() > limits.maxCollectionSize())
                        throw new IllegalArgumentException("JSON array too large");
                    for (PayloadValue element : list.values()) validate(element, depth + 1);
                }
                case PayloadValue.MapValue map -> {
                    if (map.entries().size() > limits.maxCollectionSize())
                        throw new IllegalArgumentException("JSON map too large");
                    for (Map.Entry<String, PayloadValue> entry : map.entries().entrySet()) {
                        String key = entry.getKey();
                        if (key.length() > limits.maxKeyLength())
                            throw new IllegalArgumentException("invalid JSON map key");
                        utf8(key); validate(entry.getValue(), depth + 1);
                    }
                }
                default -> { }
            }
        }
    }
}
