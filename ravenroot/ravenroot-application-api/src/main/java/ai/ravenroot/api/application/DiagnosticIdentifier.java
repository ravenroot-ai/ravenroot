package ai.ravenroot.api.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Projection-only formatting for graph-authored identifiers at public and structured-log boundaries. */
public final class DiagnosticIdentifier {
    /** Maximum UTF-8 byte count for a public identifier display. */
    public static final int MAX_IDENTIFIER_UTF8_BYTES = 128;
    /** Maximum UTF-8 byte count for a public property-name token. */
    public static final int MAX_PROPERTY_UTF8_BYTES = 64;
    private static final String TRUNCATED = "~#";
    private static final String HOST_REDACTION = "[ravenroot:redacted:host]";
    private static final String PROFILE_REDACTION = "[ravenroot:redacted:profile]";
    private static final Pattern PROPERTY_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(^|[^A-Za-z0-9_])(host(?:name)?|profile)\\s*[:=]\\s*([^\\s,;|?&#]+)");
    private static final Pattern URI_AUTHORITY = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]{1,31}://)(?:[^\\s/?#@]+@)?(?:\\[[^]]+]|[^\\s/?#:]+)(?::[0-9]{1,5})?");

    private DiagnosticIdentifier() { }

    /**
     * Formats a raw node identifier for a public or structured-log boundary.
     * @param raw exact graph-authored node identifier
     * @return sanitized display and opaque locator
     */
    public static Formatted node(String raw) {
        return format(raw, MAX_IDENTIFIER_UTF8_BYTES, "node");
    }

    /**
     * Formats a raw property name as a closed token or opaque deterministic fallback.
     * @param raw exact submitted property name
     * @return bounded property display and opaque reference
     */
    public static Formatted property(String raw) {
        Objects.requireNonNull(raw, "raw");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String reference = reference(raw);
        if (isSafePropertyToken(normalized)) return new Formatted(normalized, reference);
        return new Formatted("property-" + reference.substring("sha256:".length(), "sha256:".length() + 16),
                reference);
    }

    /**
     * Verifies a display token produced by this formatter without interpreting escaped text.
     * @param value candidate display token
     * @param maximumUtf8Bytes maximum allowed UTF-8 byte count
     * @return whether the token is normalized, bounded, and free of sensitive raw locations
     */
    public static boolean isSafeDisplay(String value, int maximumUtf8Bytes) {
        if (value == null || value.isEmpty() || utf8(value) > maximumUtf8Bytes) return false;
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || !redactSensitiveLocations(value).equals(value)) return false;
        if (value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || isBidiControl(codePoint))) return false;
        String withoutRedactionMarkers = value.replace(RuntimeActivityData.REDACTION_MARKER, "");
        if (withoutRedactionMarkers.isBlank()) return value.contains(RuntimeActivityData.REDACTION_MARKER);
        RuntimeActivityData.TextProjection projection = RuntimeActivityData.message(withoutRedactionMarkers);
        return projection != null && !projection.redacted();
    }

    /**
     * Checks the closed ASCII grammar accepted for public property names.
     * @param value candidate property-name token
     * @return whether the value is a safe bounded property token
     */
    public static boolean isSafePropertyToken(String value) {
        return value != null && PROPERTY_TOKEN.matcher(value).matches()
                && utf8(value) <= MAX_PROPERTY_UTF8_BYTES && isSafeDisplay(value, MAX_PROPERTY_UTF8_BYTES);
    }

    /**
     * Creates a stable graph-local locator containing no identifier text.
     * @param raw exact graph-authored identifier
     * @return fixed-size lowercase SHA-256 locator token
     */
    public static String reference(String raw) {
        Objects.requireNonNull(raw, "raw");
        return "sha256:" + HexFormat.of().formatHex(digest(raw)).substring(0, 32);
    }

    private static Formatted format(String raw, int maximum, String fallback) {
        Objects.requireNonNull(raw, "raw");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        RuntimeActivityData.TextProjection redacted = RuntimeActivityData.message(normalized);
        String candidate = redacted == null ? fallback : escape(redactSensitiveLocations(redacted.value()));
        String reference = reference(raw);
        if (utf8(candidate) <= maximum) return new Formatted(candidate, reference);
        String suffix = TRUNCATED + reference.substring("sha256:".length(), "sha256:".length() + 16);
        int budget = maximum - utf8(suffix);
        var bounded = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < candidate.length();) {
            int codePoint = candidate.codePointAt(offset);
            String text = new String(Character.toChars(codePoint));
            int bytes = utf8(text);
            if (used + bytes > budget) break;
            bounded.append(text);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        String display = bounded.append(suffix).toString();
        // A byte cut can land inside a redaction marker. In that case retain only a readable type
        // prefix and the opaque digest suffix; a partial marker must never be mistaken for raw text.
        if (!isSafeDisplay(display, maximum)) display = fallback + suffix;
        return new Formatted(display, reference);
    }

    private static String redactSensitiveLocations(String value) {
        Matcher assignments = SENSITIVE_ASSIGNMENT.matcher(value);
        var assigned = new StringBuffer(value.length());
        while (assignments.find()) {
            String marker = assignments.group(2).toLowerCase(java.util.Locale.ROOT).startsWith("host")
                    ? HOST_REDACTION : PROFILE_REDACTION;
            assignments.appendReplacement(assigned, Matcher.quoteReplacement(
                    assignments.group(1) + assignments.group(2) + "=" + marker));
        }
        assignments.appendTail(assigned);
        Matcher uris = URI_AUTHORITY.matcher(assigned.toString());
        var safe = new StringBuffer(assigned.length());
        while (uris.find()) {
            uris.appendReplacement(safe, Matcher.quoteReplacement(uris.group(1) + HOST_REDACTION));
        }
        uris.appendTail(safe);
        return safe.toString();
    }

    private static String escape(String value) {
        var safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\\') safe.append("\\\\");
            else if (Character.isISOControl(codePoint) || isBidiControl(codePoint)) {
                safe.append(String.format("\\u{%04X}", codePoint));
            } else safe.appendCodePoint(codePoint);
        });
        return safe.toString();
    }

    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    private static byte[] digest(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Safe display text paired with its text-free deterministic reference.
     * @param display bounded sanitized display
     * @param reference opaque fixed-size reference
     */
    public record Formatted(String display, String reference) {
        /** Validates both formatted identifier components. */
        public Formatted {
            Objects.requireNonNull(display, "display");
            Objects.requireNonNull(reference, "reference");
        }
    }
}
