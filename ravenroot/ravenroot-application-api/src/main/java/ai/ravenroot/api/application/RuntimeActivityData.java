package ai.ravenroot.api.application;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, credential-aware projection for authenticated author-facing Runtime activity.
 *
 * <p>This is defense in depth over a finite set of high-confidence credential syntaxes, not a
 * general DLP guarantee. An unlabelled secret in prose is not discoverable here. These values are
 * permitted only on the owner-filtered diagnostics endpoints; they remain forbidden from assistant,
 * provider, webhook and telemetry projections.</p>
 */
public final class RuntimeActivityData {
    /** Maximum UTF-8 size of an author-facing failure message. */
public static final int MAX_MESSAGE_UTF8_BYTES = 1_024;
    /** Maximum UTF-8 size of an author-facing output projection. */
public static final int MAX_OUTPUT_UTF8_BYTES = 16 * 1_024;
    /** Maximum recursive depth retained in output projections. */
public static final int MAX_OUTPUT_DEPTH = 6;
    /** Maximum entries retained from one collection. */
public static final int MAX_COLLECTION_SIZE = 32;
    /** Maximum values visited while projecting one output. */
public static final int MAX_VALUE_COUNT = 128;
    /** Maximum UTF-8 size retained from one text value. */
public static final int MAX_TEXT_UTF8_BYTES = 1_024;
    /** Maximum UTF-8 size of one retained map key. */
public static final int MAX_KEY_UTF8_BYTES = 128;

    /** Marker replacing recognized credential material. */
public static final String REDACTION_MARKER = "[ravenroot:redacted:credential]";
    /** Marker identifying a bounded projection. */
public static final String TRUNCATION_MARKER = "[ravenroot:truncated]";

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization)\\s*[:=]\\s*(?:bearer|basic)\\s+)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bbearer\\s+)([A-Za-z0-9._~+/-]{8,}=*)");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)([\\\"']?\\b(?:[a-z0-9]+[-_.])*(?:api[-_]?key|access[-_]?token|refresh[-_]?token|"
                    + "id[-_]?token|password|passwd|client[-_]?secret|private[-_]?key|credential|secret|token)"
                    + "(?![-_.]?(?:ref|reference|count|type)\\b)[\\\"']?\\s*[:=]\\s*)"
                    + "(\\\"(?:\\\\.|[^\\\"\\\\\\r\\n])*\\\"|'(?:\\\\.|[^'\\\\\\r\\n])*'|"
                    + "[^\\s,;&\\\"'}\\]]+)");
    private static final Pattern COOKIE = Pattern.compile(
            "(?i)(\\b(?:cookie|set-cookie)\\s*[:=]\\s*)"
                    + "([a-z0-9_.-]+=[^\\s,;]+(?:;\\s*[a-z0-9_.-]+=[^\\s,;]+)*)");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----");
    private static final Set<String> SECRET_KEYS = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie", "password", "passwd",
            "secret", "token", "accesstoken", "refreshtoken", "idtoken", "apikey", "clientsecret",
            "privatekey", "credential", "credentials");

    private RuntimeActivityData() {
    }

    /**
 * Normalizes, redacts, then UTF-8 bounds text, retaining unambiguous operation flags.
* @param value candidate stable edge identity or text value
* @return bounded text projection, or {@code null} for blank input
 */
    public static TextProjection message(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = normalize(value);
        Redaction redaction = redact(normalized);
        Bound bound = boundUtf8(redaction.value(), MAX_MESSAGE_UTF8_BYTES);
        if (redaction.changed() && bound.truncated() && !bound.value().contains(REDACTION_MARKER)) {
            bound = boundUtf8WithRequiredMarker(redaction.value(), MAX_MESSAGE_UTF8_BYTES, REDACTION_MARKER);
        }
        return new TextProjection(bound.value(), redaction.changed(), bound.truncated());
    }

    /**
 * Projects a JVM value onto the closed JSON payload model with declared redaction/truncation.
* @param value candidate stable edge identity or text value
* @return closed payload projection with redaction and truncation flags
 */
    public static OutputProjection output(Object value) {
        var state = new ProjectionState();
        PayloadValue projected = project(value, 1, state);
        String encoded = PayloadJson.write(projected);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_UTF8_BYTES) {
            // The preserved prefix becomes a JSON string, so its quotes and backslashes are escaped
            // once more. Half the wire budget is a conservative bound on that second encoding.
            Bound bounded = boundUtf8(encoded, MAX_OUTPUT_UTF8_BYTES / 2);
            if (state.redacted && !bounded.value().contains(REDACTION_MARKER)) {
                bounded = boundUtf8WithRequiredMarker(
                        encoded, MAX_OUTPUT_UTF8_BYTES / 2, REDACTION_MARKER);
            }
            projected = PayloadValue.of(bounded.value());
            state.truncated = true;
        }
        return new OutputProjection(projected, state.redacted, state.truncated);
    }

    private static PayloadValue project(Object value, int depth, ProjectionState state) {
        if (depth > MAX_OUTPUT_DEPTH) return state.marker("depth");
        if (++state.values > MAX_VALUE_COUNT) return state.marker("value-count");
        if (value == null) return PayloadValue.NULL;
        if (value instanceof PayloadValue payloadValue) return project(payloadValue.toJava(), depth, state);
        if (value instanceof Boolean flag) return PayloadValue.of(flag.booleanValue());
        if (value instanceof Byte number) return PayloadValue.of(number.longValue());
        if (value instanceof Short number) return PayloadValue.of(number.longValue());
        if (value instanceof Integer number) return PayloadValue.of(number.longValue());
        if (value instanceof Long number) return PayloadValue.of(number.longValue());
        if (value instanceof Float number && Float.isFinite(number)) return PayloadValue.of(number.doubleValue());
        if (value instanceof Double number && Double.isFinite(number)) return PayloadValue.of(number.doubleValue());
        if (value instanceof CharSequence text) {
            Redaction redaction = redact(normalize(text.toString()));
            Bound bound = boundUtf8(redaction.value(), MAX_TEXT_UTF8_BYTES);
            if (redaction.changed() && bound.truncated() && !bound.value().contains(REDACTION_MARKER)) {
                bound = boundUtf8WithRequiredMarker(redaction.value(), MAX_TEXT_UTF8_BYTES, REDACTION_MARKER);
            }
            state.redacted |= redaction.changed();
            state.truncated |= bound.truncated();
            return PayloadValue.of(bound.value());
        }
        if (state.seen.put(value, Boolean.TRUE) != null) return state.marker("cycle");
        try {
            if (value instanceof Map<?, ?> map) return projectMap(map, depth, state);
            if (value instanceof Iterable<?> iterable) return projectIterable(iterable, depth, state);
            if (value.getClass().isArray()) return projectArray(value, depth, state);
            return state.marker("unsupported-type");
        } finally {
            state.seen.remove(value);
        }
    }

    private static PayloadValue projectMap(Map<?, ?> source, int depth, ProjectionState state) {
        if (source.keySet().stream().anyMatch(key -> !(key instanceof String))) return state.marker("non-string-key");
        List<String> keys = source.keySet().stream().map(String.class::cast).sorted().toList();
        var projected = new LinkedHashMap<String, PayloadValue>();
        int retained = Math.min(keys.size(), MAX_COLLECTION_SIZE);
        if (keys.size() > MAX_COLLECTION_SIZE) retained--;
        for (int index = 0; index < retained; index++) {
            String key = keys.get(index);
            if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_UTF8_BYTES) {
                projected.put(TRUNCATION_MARKER, state.marker("key-length"));
                break;
            }
            if (secretKey(key)) {
                projected.put(key, PayloadValue.of(REDACTION_MARKER));
                state.redacted = true;
            } else {
                projected.put(key, project(source.get(key), depth + 1, state));
            }
        }
        if (keys.size() > MAX_COLLECTION_SIZE) {
            String markerKey = "$ravenrootTruncation";
            while (projected.containsKey(markerKey) || source.containsKey(markerKey)) markerKey += "_";
            projected.put(markerKey, state.marker("collection"));
        }
        return PayloadValue.map(projected);
    }

    private static PayloadValue projectIterable(Iterable<?> source, int depth, ProjectionState state) {
        var projected = new ArrayList<PayloadValue>();
        var iterator = source.iterator();
        while (iterator.hasNext() && projected.size() < MAX_COLLECTION_SIZE) {
            projected.add(project(iterator.next(), depth + 1, state));
        }
        if (iterator.hasNext()) projected.set(MAX_COLLECTION_SIZE - 1, state.marker("collection"));
        return PayloadValue.list(projected);
    }

    private static PayloadValue projectArray(Object source, int depth, ProjectionState state) {
        int length = java.lang.reflect.Array.getLength(source);
        int retained = Math.min(length, MAX_COLLECTION_SIZE);
        if (length > MAX_COLLECTION_SIZE) retained--;
        var projected = new ArrayList<PayloadValue>(Math.min(length, MAX_COLLECTION_SIZE));
        for (int index = 0; index < retained; index++) {
            projected.add(project(java.lang.reflect.Array.get(source, index), depth + 1, state));
        }
        if (length > MAX_COLLECTION_SIZE) projected.add(state.marker("collection"));
        return PayloadValue.list(projected);
    }

    private static boolean secretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.endsWith("ref") || normalized.endsWith("reference")
                || normalized.endsWith("count") || normalized.endsWith("type")) return false;
        return SECRET_KEYS.contains(normalized) || SECRET_KEYS.stream().anyMatch(normalized::endsWith);
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        var safe = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) safe.append(' ');
            else if (codePoint == '\n' || codePoint == '\t' || !Character.isISOControl(codePoint)) safe.appendCodePoint(codePoint);
            else safe.append(' ');
        });
        return safe.toString();
    }

    private static Redaction redact(String value) {
        boolean changed = false;
        String current = value;
        for (Pattern pattern : List.of(PRIVATE_KEY_BLOCK, AUTHORIZATION, BEARER)) {
            Matcher matcher = pattern.matcher(current);
            if (!matcher.find()) continue;
            changed = true;
            String replacement = matcher.groupCount() == 0 ? Matcher.quoteReplacement(REDACTION_MARKER)
                    : "$1" + Matcher.quoteReplacement(REDACTION_MARKER);
            current = matcher.replaceAll(replacement);
        }
        Matcher assigned = ASSIGNED_SECRET.matcher(current);
        if (assigned.find()) {
            changed = true;
            var replaced = new StringBuffer(current.length());
            do {
                String assignedValue = assigned.group(2);
                boolean quoted = assignedValue.length() >= 2
                        && (assignedValue.charAt(0) == '\"' || assignedValue.charAt(0) == '\'')
                        && assignedValue.charAt(assignedValue.length() - 1) == assignedValue.charAt(0);
                String safeValue = quoted
                        ? assignedValue.charAt(0) + REDACTION_MARKER
                                + String.valueOf(assignedValue.charAt(assignedValue.length() - 1))
                        : REDACTION_MARKER;
                assigned.appendReplacement(replaced, Matcher.quoteReplacement(assigned.group(1) + safeValue));
            } while (assigned.find());
            assigned.appendTail(replaced);
            current = replaced.toString();
        }
        for (Pattern pattern : List.of(COOKIE, JWT)) {
            Matcher matcher = pattern.matcher(current);
            if (!matcher.find()) continue;
            changed = true;
            String replacement = matcher.groupCount() == 0 ? Matcher.quoteReplacement(REDACTION_MARKER)
                    : "$1" + Matcher.quoteReplacement(REDACTION_MARKER);
            current = matcher.replaceAll(replacement);
        }
        return new Redaction(current, changed);
    }

    private static Bound boundUtf8(String value, int maximum) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maximum) return new Bound(value, false);
        int budget = maximum - TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
        var bounded = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int encoded = utf8Length(codePoint);
            if (used + encoded > budget) break;
            bounded.appendCodePoint(codePoint);
            used += encoded;
            offset += Character.charCount(codePoint);
        }
        return new Bound(bounded.append(TRUNCATION_MARKER).toString(), true);
    }

    private static Bound boundUtf8WithRequiredMarker(String value, int maximum, String requiredMarker) {
        int budget = maximum - requiredMarker.getBytes(StandardCharsets.UTF_8).length
                - TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
        var bounded = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int encoded = utf8Length(codePoint);
            if (used + encoded > budget) break;
            bounded.appendCodePoint(codePoint);
            used += encoded;
            offset += Character.charCount(codePoint);
        }
        return new Bound(bounded.append(requiredMarker).append(TRUNCATION_MARKER).toString(), true);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    /** Non-forgeable, already-safe author message projection. */
    public static final class TextProjection {
        private final String value;
        private final boolean redacted;
        private final boolean truncated;
        private TextProjection(String value, boolean redacted, boolean truncated) {
            this.value = value;
            this.redacted = redacted;
            this.truncated = truncated;
        }
        /**
 * Returns the bounded projected text.
 * @return the bounded projected text
 */
public String value() { return value; }
        /**
 * Reports whether credential material was replaced.
 * @return whether credential material was replaced
 */
public boolean redacted() { return redacted; }
        /**
 * Reports whether the text projection was shortened.
 * @return whether the text projection was shortened
 */
public boolean truncated() { return truncated; }
    }

    /** Non-forgeable, already-safe author output projection. */
    public static final class OutputProjection {
        private final PayloadValue value;
        private final boolean redacted;
        private final boolean truncated;
        private OutputProjection(PayloadValue value, boolean redacted, boolean truncated) {
            this.value = value;
            this.redacted = redacted;
            this.truncated = truncated;
        }
        /**
 * Returns the closed payload projection.
 * @return the closed payload projection
 */
public PayloadValue value() { return value; }
        /**
 * Reports whether credential material was replaced.
 * @return whether credential material was replaced
 */
public boolean redacted() { return redacted; }
        /**
 * Reports whether the output projection was shortened.
 * @return whether the output projection was shortened
 */
public boolean truncated() { return truncated; }
    }

    private record Redaction(String value, boolean changed) { }
    private record Bound(String value, boolean truncated) { }

    private static final class ProjectionState {
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private int values;
        private boolean redacted;
        private boolean truncated;
        private PayloadValue marker(String reason) {
            truncated = true;
            return PayloadValue.of("[ravenroot:truncated:" + reason + "]");
        }
    }
}
