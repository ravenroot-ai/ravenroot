package ai.ravenroot.api.application;

/**
 * The single truncation rule for this package's operator-facing diagnostic strings.
 *
 * <p>{@link ExecutionEvent#detail()} and {@link NodeAttempt#parkCause()} are the same kind of value —
 * free text written for the human deciding what happened — bounded at the same 512 for the same
 * reason. The {@code detail} field has a marker counted inside the bound; the park cause was still cut in
 * silence. Two diagnostics in one package truncating by different rules is how the next reader learns
 * the wrong rule, so the rule lives here once and both records call it.</p>
 *
 * <p>The bound and the marker stay declared on each record rather than here, because they are that
 * record's published contract: a caller sizing a column or a log line reads
 * {@link NodeAttempt#MAX_PARK_CAUSE_LENGTH}, and a helper that silently substituted its own limit
 * would make that constant a claim the code does not honour.</p>
 */
final class DiagnosticText {

    /**
     * The marker every diagnostic in this package uses for a value that was cut. Plain ASCII: these
     * values are read in log lines and inside JSON, and a single-character ellipsis is easy to miss in
     * both and easy to mangle across encodings.
     */
    static final String TRUNCATION_MARKER = " [truncated]";

    private DiagnosticText() {
    }

    /**
     * Caps {@code value} at {@code maxLength} <em>chars</em>, marker included, so the result never
     * exceeds the bound its caller published.
     *
     * <p>The cut never lands between a high and a low surrogate. Splitting a pair would leave a lone
     * surrogate that has no valid UTF-8 encoding, and the surfaces these values reach next — SSE and
     * JSON responses, structured log lines, a persisted row — would each have to invent a replacement
     * for it, so a cut in the wrong place turns a truncated message into an encoding defect several
     * modules away.</p>
     */
    static String bounded(String value, int maxLength, String marker) {
        if (value.length() <= maxLength) {
            return value;
        }
        int keep = maxLength - marker.length();
        if (Character.isHighSurrogate(value.charAt(keep - 1))) {
            keep--;
        }
        return value.substring(0, keep) + marker;
    }
}
