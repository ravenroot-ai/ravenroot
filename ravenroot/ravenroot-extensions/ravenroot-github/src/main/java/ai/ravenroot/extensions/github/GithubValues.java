package ai.ravenroot.extensions.github;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GithubValues {
    static final PayloadLimits LIMITS = new PayloadLimits(4 * 1024 * 1024, 32, 512, 20_000,
            2 * 1024 * 1024, 256);

    private GithubValues() { }

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
                || text.codePoints().anyMatch(character -> character < 0x20 || character == 0x7f)) throw invalid();
        return text;
    }

    static String optionalString(Object value, int maximum) {
        return value == null ? "" : string(value, maximum);
    }

    static long number(Object value, long minimum, long maximum) {
        if (!(value instanceof Long number) || number < minimum || number > maximum) throw invalid();
        return number;
    }

    static boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw invalid();
        return flag;
    }

    static void exact(Map<String, Object> value, Set<String> fields) {
        if (!fields.containsAll(value.keySet())) throw invalid();
    }

    static Set<String> strings(Object value, int maximumEntries, int maximumLength) {
        if (value == null) return Set.of();
        List<Object> source = list(value);
        if (source.size() > maximumEntries) throw invalid();
        var result = new java.util.LinkedHashSet<String>();
        source.forEach(entry -> {
            if (!result.add(string(entry, maximumLength))) throw invalid();
        });
        return Set.copyOf(result);
    }

    static byte[] canonicalBase64(String encoded, int maximumBytes) {
        if (encoded == null || encoded.length() > maximumBytes * 2) throw invalid();
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
        if (decoded.length > maximumBytes || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) throw invalid();
        return decoded;
    }

    static Map<String, Object> json(byte[] bytes) {
        return object(PayloadJson.read(bytes, LIMITS).toJava());
    }

    static byte[] jsonBytes(Map<String, ?> value) {
        return PayloadJson.write(PayloadValue.fromJava(value, LIMITS)).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }

    static List<Map<String, Object>> objectList(Object value, int maximum) {
        List<Object> source = list(value);
        if (source.size() > maximum) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        source.forEach(item -> result.add(object(item)));
        return List.copyOf(result);
    }

    static GithubException invalid() { return new GithubException(GithubException.Code.INVALID_INPUT); }
}
