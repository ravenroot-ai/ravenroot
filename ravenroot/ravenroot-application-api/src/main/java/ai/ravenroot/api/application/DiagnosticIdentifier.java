package ai.ravenroot.api.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

/** Projection-only formatting for graph-authored identifiers at public and structured-log boundaries. */
public final class DiagnosticIdentifier {
    public static final int MAX_IDENTIFIER_UTF8_BYTES = 128;
    public static final int MAX_PROPERTY_UTF8_BYTES = 64;
    private static final String TRUNCATED = "~#";

    private DiagnosticIdentifier() { }

    public static Formatted node(String raw) {
        return format(raw, MAX_IDENTIFIER_UTF8_BYTES, "node");
    }

    public static Formatted property(String raw) {
        return format(raw, MAX_PROPERTY_UTF8_BYTES, "property");
    }

    /** Verifies a display token produced by this formatter without interpreting escaped text. */
    public static boolean isSafeDisplay(String value, int maximumUtf8Bytes) {
        if (value == null || value.isEmpty() || utf8(value) > maximumUtf8Bytes) return false;
        if (value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || isBidiControl(codePoint))) return false;
        String withoutRedactionMarkers = value.replace(RuntimeActivityData.REDACTION_MARKER, "");
        if (withoutRedactionMarkers.isBlank()) return value.contains(RuntimeActivityData.REDACTION_MARKER);
        RuntimeActivityData.TextProjection projection = RuntimeActivityData.message(withoutRedactionMarkers);
        return projection != null && !projection.redacted();
    }

    /** Stable graph-local locator over the exact raw UTF-8 identifier; it contains no identifier text. */
    public static String reference(String raw) {
        Objects.requireNonNull(raw, "raw");
        return "sha256:" + HexFormat.of().formatHex(digest(raw)).substring(0, 32);
    }

    private static Formatted format(String raw, int maximum, String fallback) {
        Objects.requireNonNull(raw, "raw");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        RuntimeActivityData.TextProjection redacted = RuntimeActivityData.message(normalized);
        String candidate = redacted == null ? fallback : escape(redacted.value());
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
        return new Formatted(bounded.append(suffix).toString(), reference);
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

    public record Formatted(String display, String reference) {
        public Formatted {
            Objects.requireNonNull(display, "display");
            Objects.requireNonNull(reference, "reference");
        }
    }
}
