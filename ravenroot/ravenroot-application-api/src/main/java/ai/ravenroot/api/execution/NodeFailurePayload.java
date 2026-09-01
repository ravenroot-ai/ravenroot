package ai.ravenroot.api.execution;

import ai.ravenroot.api.application.ExecutionEvent;

import java.util.Objects;

/**
 * The documented payload shape delivered down a node's failure route.
 *
 * <h2>Never a fabricated result</h2>
 * <p>A node whose attempt failed produced no result, so nothing here stands in for one. This carries
 * {@link #errorClass()} and {@link #message()} describing why the attempt failed, and {@link #input()}
 * — the payload the failing node actually received — so a handler wired to the failure route can act
 * on what was being processed without re-deriving it from anywhere else.</p>
 *
 * <h2>Redaction: bounded, not scrubbed</h2>
 * <p>{@link #message()} follows exactly the doctrine of {@link ExecutionEvent#detail()}, because it
 * is built from the same input by the same unwrap: the deepest cause's own message,
 * verbatim. No lexical rule can tell a payload fragment an exception message quoted from the
 * diagnostic wording around it — see {@code ExecutionEventDetailIsNotRedactedTest} — so a "redactor"
 * here would provide exactly that false assurance. What this class actually guarantees,
 * structurally rather than by convention, is narrower and real: {@link #message()} is never a raw
 * stack trace, because nothing here ever reads {@link Throwable#getStackTrace()} or
 * {@link Throwable#printStackTrace()}, and it shares {@link ExecutionEvent#MAX_DETAIL_LENGTH} rather
 * than a second, independently-drifting bound on the same kind of value.</p>
 *
 * <h2>Explicit payload projection</h2>
 * <p>{@code PayloadValue.fromJava} recognises this record explicitly and emits exactly the four
 * documented members below. It does not inspect the throwable (which is not retained here), reflect
 * over this record, or fall back to {@code toString()}. The {@code input} member is recursively
 * projected through the same closed payload model, so unsupported values and every configured
 * {@code PayloadLimits} breach remain classified payload rejections rather than best-effort output.</p>
 *
 * @param nodeId     the id of the node whose attempt failed
 * @param errorClass the deepest cause's fully-qualified class name
 * @param message    the deepest cause's message, bounded exactly like {@link ExecutionEvent#detail()}
 * @param input      the payload the failing node received, forwarded verbatim
 */
public record NodeFailurePayload(String nodeId, String errorClass, String message, Object input) {

/**
 * Normalizes a throwable into the bounded, serializable failure data exposed to API clients.
 */
    public NodeFailurePayload {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId cannot be blank");
        }
        if (errorClass == null || errorClass.isBlank()) {
            throw new IllegalArgumentException("errorClass cannot be blank");
        }
        message = bound(message == null ? "" : message);
    }

    /**
     * Builds the payload from the node id, the throwable its attempt failed with, and the input it
     * had received. Unwraps to the deepest cause the same way {@code ExecutionMonitor}'s own failure
     * message does, so a hostile message wrapped in a benign one reads here exactly as it reads on
     * the {@code NODE_FAILED} event for the same attempt.
 * @param nodeId the graph node that failed while processing the input
 * @param error the thrown failure whose class, message and causal summary are captured
 * @param input the offending input represented only when it is safe for this payload's policy
 * @return a failure payload with bounded diagnostic text and serialization-safe values
     */
    public static NodeFailurePayload of(String nodeId, Throwable error, Object input) {
        Objects.requireNonNull(error, "error");
        Throwable deepest = error;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        String rawMessage = deepest.getMessage() == null
                ? deepest.getClass().getSimpleName() : deepest.getMessage();
        return new NodeFailurePayload(nodeId, deepest.getClass().getName(), rawMessage, input);
    }

    private static String bound(String message) {
        int max = ExecutionEvent.MAX_DETAIL_LENGTH;
        String marker = ExecutionEvent.DETAIL_TRUNCATION_MARKER;
        if (message.length() <= max) {
            return message;
        }
        int keep = max - marker.length();
        if (Character.isHighSurrogate(message.charAt(keep - 1))) {
            keep--;
        }
        return message.substring(0, keep) + marker;
    }
}
