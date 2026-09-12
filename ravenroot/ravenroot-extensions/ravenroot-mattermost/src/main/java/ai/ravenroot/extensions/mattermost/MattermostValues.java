package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MattermostValues {
    static final PayloadLimits LIMITS = new PayloadLimits(4 * 1024 * 1024, 16, 128, 8_192,
            3 * 1024 * 1024, 128);
    private MattermostValues() { }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, member) -> {
            if (!(key instanceof String name) || result.putIfAbsent(name, member) != null) throw invalid();
        });
        return Map.copyOf(result);
    }
    static List<Object> list(Object value) {
        if (!(value instanceof List<?> source)) throw invalid();
        return List.copyOf(source);
    }
    static String string(Object value, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum
                || text.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw invalid();
        return text;
    }
    static String text(Object value, int maximum) {
        if (!(value instanceof String text) || text.isBlank()
                || text.codePointCount(0, text.length()) > maximum
                || text.codePoints().anyMatch(c -> c == 0 || c == 0x7f)) throw invalid();
        return text;
    }
    static String optionalString(Object value, int maximum) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > maximum
                || text.codePoints().anyMatch(c -> c == 0 || c == 0x7f)) throw invalid();
        return text;
    }
    static long number(Object value, long minimum, long maximum) {
        final long result;
        if (value instanceof Long integer) result = integer;
        else if (value instanceof Integer integer) result = integer.longValue();
        else if (value instanceof Double floating && Double.isFinite(floating)
                && floating == Math.rint(floating) && Math.abs(floating) <= 9_007_199_254_740_991d)
            result = floating.longValue();
        else throw invalid();
        if (result < minimum || result > maximum) throw invalid();
        return result;
    }
    static void exact(Map<String, Object> value, Set<String> fields) {
        if (!fields.containsAll(value.keySet())) throw invalid();
    }
    static Set<String> strings(Object value, int maximumEntries, int maximumLength) {
        List<Object> source = list(value);
        if (source.isEmpty() || source.size() > maximumEntries) throw invalid();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object item : source) if (!result.add(string(item, maximumLength))) throw invalid();
        return Set.copyOf(result);
    }
    static byte[] canonicalBase64(String value, int maximumBytes) {
        if (value == null || value.length() > maximumBytes * 2) throw invalid();
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > maximumBytes || !Base64.getEncoder().encodeToString(bytes).equals(value)) throw invalid();
            return bytes;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    static Map<String, Object> json(byte[] bytes) { return object(PayloadJson.read(bytes, LIMITS).toJava()); }
    static byte[] jsonBytes(Map<String, ?> value) {
        return PayloadJson.write(PayloadValue.fromJava(value, LIMITS)).getBytes(StandardCharsets.UTF_8);
    }
    static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static NodeResult result(String outcome, Map<String, Object> payload, long maximumBytes) {
        try {
            int ceiling = Math.toIntExact(Math.min(maximumBytes, 1024 * 1024));
            new PayloadLimits(ceiling, 8, 32, 256, ceiling, 128)
                    .enforceAndMeasure(Map.of("outcome", outcome, "payload", payload));
            return new NodeResult(outcome, Map.copyOf(payload), Map.of());
        } catch (RuntimeException failure) { throw new MattermostException(MattermostException.Code.RESPONSE_INVALID); }
    }
    static MattermostException invalid() { return new MattermostException(MattermostException.Code.INVALID_INPUT); }
}
