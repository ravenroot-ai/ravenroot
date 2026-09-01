package ai.ravenroot.server.audit;

/**
 * The one JSON string-escaping implementation every JSON-lines-emitting class in this module must
 * use. Newline and control characters must not alter structured records.
 *
 * <h2>Why one shared implementation is necessary</h2>
 * <p>Previously, escaping was written six times. {@code StructuredExecutionLogger}'s own
 * copy handled every character below {@code 0x20} (the six named JSON escapes plus a generic
 * unicode-escape fallback for the rest); {@code StructuredAuthorizationLogger},
 * {@code StructuredRateLimitLogger}, {@code StructuredArtifactLifecycleLogger},
 * {@code StructuredGraphMlRejectionLogger} and {@code StructuredPayloadRejectionLogger} each carried a
 * narrower copy handling only backslash, quote, newline and carriage return. A raw tab, form feed or
 * any other sub-{@code 0x20} character in a value reached those five as literal bytes -- which is
 * <em>invalid JSON</em>, not merely unescaped text: the record stops being the structured record it
 * claims to be. Extending individual copies can leave sibling implementations with narrower
 * escaping, while a newly added sink can omit escaping entirely. One shared implementation prevents
 * those differences.</p>
 *
 * <h2>Where this is not needed, and why</h2>
 * <p>Not applied inside {@code FileAuditTrail}: every {@code AuditEnvelope} string field is
 * Base64-encoded before it reaches the persisted line, which makes the file's own record structure
 * immune to its content regardless of what any sink puts there -- an encoding that cannot be broken
 * beats an escape function that must be remembered, and duplicating this class's logic there would
 * protect nothing the encoding does not already protect. This class exists for the two kinds of
 * record that are <em>not</em> protected that way: a JSON-lines stream written directly to a
 * {@link java.io.PrintStream} (six classes, all in this module), and a JSON blob one of this module's
 * own {@code AuditTrail*Sink} classes chooses to place inside an {@code AuditEnvelope}'s {@code detail}
 * payload -- Base64 protects the byte sequence FileAuditTrail persists, not the JSON syntax inside it,
 * so that blob still has to be valid JSON on its own terms if anything is ever going to decode it.</p>
 */
public final class JsonStrings {
    private JsonStrings() {
    }

    /** Escapes {@code value} for placement inside a JSON string literal. {@code null} becomes {@code ""}. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        var escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
