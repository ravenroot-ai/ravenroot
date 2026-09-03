package ai.ravenroot.api.application;

import java.nio.charset.StandardCharsets;

/**
 * Human-readable execution text that is safe for an authenticated public runtime surface.
 *
 * <p>This is intentionally not a wrapper around {@link ExecutionEvent#detail()}. That diagnostic can
 * contain exception messages and graph-authored values. Public descriptions are instead selected
 * from source-authored sentences. Consequently there is no API that accepts a {@link Throwable}, a
 * diagnostic detail, or arbitrary caller text.</p>
 *
 * <h2>Why the event type alone was not enough </h2>
 * <p>Selecting purely by type made every {@code NODE_COMPLETED} render one fixed sentence, and that
 * sentence claimed <em>success</em>. A node that routes its {@code failed} outcome completes — the
 * traversal continues down the failure route the author declared — so it emits {@code NODE_COMPLETED}
 * and read "Node completed successfully." A reader looking at the activity log to find out what went
 * wrong was told, at that exact spot, that nothing had. The generic sentence was not merely thin:
 * it was false, and false in the direction that looks healthy.</p>
 *
 * <p>The fix is <b>not</b> to relax what may be published. It is that
 * {@link ExecutionEvent#publicReason()} now carries a classifier — a bare token, character-restricted
 * at the event boundary so it cannot hold prose — beside the diagnostic. These methods interpolate
 * that token into a source-authored sentence, and <b>re-validate it here</b> rather than trusting the
 * producer: this class is the one that promises callers cannot inject text through it, and a promise
 * that holds only while every caller behaves is not one. A non-conforming token is dropped and the
 * reason-less sentence is used, never repaired into something that reads legitimate.</p>
 *
 * <p>Consequently {@link #forType(ExecutionEventType)}, the no-classifier form, <b>no longer asserts
 * success for a completed node</b>. It cannot: without the outcome it does not know. It says "Node
 * completed." and stops there. The durable journal never captured the outcome, so replayed history
 * goes through exactly that path — an honest weaker sentence rather than the confident wrong one.</p>
 *
 * <p>The representation is a single line and at most {@link #MAX_UTF8_BYTES} UTF-8 bytes. Keeping the
 * bound in bytes makes the SSE frame and recent-events response limits independent of whether the
 * sentence is ASCII or multi-byte Unicode. Controls, line separators, formatting characters and
 * malformed surrogate code units become ordinary spaces before the bound is applied.</p>
 */
public final class PublicExecutionDescription {

    /** Maximum UTF-8 size of one public description. */
public static final int MAX_UTF8_BYTES = 256;
    /** Marker appended when a public description is shortened. */
public static final String TRUNCATION_MARKER = " [truncated]";
    /** Fallback sentence for an unknown or absent event type. */
public static final String UNKNOWN_EVENT = "Execution activity was reported.";

    private PublicExecutionDescription() {
    }

    /**
 * Bound on an accepted classifier, mirroring {@link ExecutionEvent#MAX_PUBLIC_REASON_LENGTH}.
 *
 * <p>Restated rather than referenced so the two can be asserted equal by a test instead of being
 * silently coupled: this module must be able to reject a token the event layer would have
 * accepted, which is the point of validating twice.</p>
 */
    public static final int MAX_REASON_LENGTH = 64;

    /**
 * Returns the public sentence for a live runtime event type with no classifier available.
 *
 * <p>See this class's own documentation on why {@link ExecutionEventType#NODE_COMPLETED} does not
 * claim success here.</p>
* @param type execution event type
* @return bounded source-authored sentence for the event type
 */
    public static String forType(ExecutionEventType type) {
        String authored = type == null ? UNKNOWN_EVENT : switch (type) {
            case EXECUTION_STARTED -> "Execution started.";
            case NODE_STARTED -> "Node execution started.";
            case NODE_BYPASSED -> "Node was bypassed.";
            case NODE_DEFAULTED -> "Node used its configured fallback.";
            // No "successfully" without knowing the outcome. This branch is reached when the
            // classifier is absent or was rejected, and a node that routed a failure outcome reaches
            // it too -- so the sentence must be true of that node as well.
            case NODE_COMPLETED -> "Node completed.";
            case EDGE_TRAVERSED -> "Edge was traversed.";
            case NODE_FAILED -> "Node failed. Protected diagnostics may contain more detail.";
            case JOIN_SATISFIED -> "Join conditions were satisfied.";
            case JOIN_ITERATION_BACKLOG -> "A join is holding state for several iterations.";
            case JOIN_ARRIVAL_DISCARDED -> "A duplicate or late join arrival was ignored.";
            case JOIN_FAILED -> "Join conditions could not be satisfied.";
            case EXECUTION_COMPLETED -> "Execution completed successfully.";
            case EXECUTION_FAILED -> "Execution failed. Protected diagnostics may contain more detail.";
        };
        return normalizeAuthoredText(authored);
    }

    /**
 * Returns the public sentence for a live runtime event, using its classifier where one applies.
 *
 * <p>Falls back to {@link #forType(ExecutionEventType)} whenever the classifier is absent, does
 * not conform, or belongs to a type whose meaning does not depend on it. The result is always a
 * source-authored sentence; {@code publicReason} can only ever appear inside one as a quoted
 * token, never as the sentence.</p>
* @param type execution event type
* @param publicReason bounded public classifier, or {@code null} when none applies
* @return bounded source-authored sentence selected by type and classifier
 */
    public static String forType(ExecutionEventType type, String publicReason) {
        String reason = conformingReason(publicReason);
        if (type == null || reason == null) {
            return forType(type);
        }
        String authored = switch (type) {
            // The default outcome is the only one that means plain success, so it is the only one
            // that earns the word. Every other outcome is named instead of characterised: this class
            // cannot know whether a graph author's "retry" or "escalate" is good news.
            case NODE_COMPLETED -> ExecutionEvent.DEFAULT_ROUTED_OUTCOME.equals(reason)
                    ? "Node completed successfully."
                    : "Node completed and routed its \"" + reason + "\" outcome.";
            // The class, never the message -- see ExecutionMonitor#failureClass. The trailing pointer
            // to protected diagnostics is kept because it remains true: the class narrows the failure,
            // it does not fully explain it.
            case NODE_FAILED -> "Node failed with " + reason
                    + ". Protected diagnostics may contain more detail.";
            case EXECUTION_FAILED -> "Execution failed with " + reason
                    + ". Protected diagnostics may contain more detail.";
            case JOIN_FAILED -> "Join conditions could not be satisfied: " + reason + ".";
            // Two different facts now share NODE_BYPASSED, and the difference matters to
            // whoever is reading the activity view: one says the run itself is not executing
            // anything, the other says one node is switched off in the saved graph while the rest of
            // the run is real. Note what this branch does NOT do: it does not interpolate `reason`
            // into the sentence the way the outcome branches above do. Here the classifier selects
            // among sentences instead of appearing inside one, so an unrecognised token falls through
            // to the plain "Node was bypassed." rather than producing a sentence built around a
            // string this class does not know the meaning of.
            case NODE_BYPASSED -> switch (reason) {
                case ExecutionEvent.BYPASS_REASON_AUTHORED ->
                        "Node was bypassed: the graph author switched this node off.";
                case ExecutionEvent.BYPASS_REASON_COMMAND ->
                        "Node was bypassed: the traversal was not executing node behaviours.";
                default -> null;
            };
            default -> null;
        };
        return authored == null ? forType(type) : normalizeAuthoredText(authored);
    }

    /**
 * Returns {@code publicReason} when it is a classifier this class will interpolate, {@code null}
 * otherwise.
 *
 * <p>Deliberately duplicates the rule {@link ExecutionEvent} already enforces. The event layer's
 * check protects the field; this one protects the <em>sentence</em>, and they are different
 * obligations with different callers — {@link #forType(ExecutionEventType, String)} is public and
 * reachable with any string at all, including from a test fixture or an adapter that built its
 * event through a compatibility constructor. Trusting the producer here would make this class's
 * central promise — that no caller can put text through it — conditional on code it does not
 * own.</p>
 */
    static String conformingReason(String publicReason) {
        if (publicReason == null || publicReason.isEmpty() || publicReason.length() > MAX_REASON_LENGTH) {
            return null;
        }
        for (int index = 0; index < publicReason.length(); index++) {
            char character = publicReason.charAt(index);
            boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-' || character == ':';
            if (!permitted) {
                return null;
            }
        }
        return publicReason;
    }

    /**
 * Returns the same sentence for the durable journal's string event type.
 *
 * <p>The journal deliberately carries an open string for forward compatibility. Unknown and
 * legacy codes therefore receive a useful generic sentence instead of being echoed or rendered
 * as an empty row.</p>
* @param eventType domain event type recorded in the durable journal
* @return bounded sentence for the durable event type
 */
    public static String forEventType(String eventType) {
        if (eventType == null) {
            return UNKNOWN_EVENT;
        }
        String handler = handlerSentence(eventType);
        if (handler != null) {
            return normalizeAuthoredText(handler);
        }
        try {
            return forType(ExecutionEventType.valueOf(eventType));
        } catch (IllegalArgumentException unknown) {
            return UNKNOWN_EVENT;
        }
    }

    /**
 * Returns the source-authored sentence for a durable handler-lifecycle event type, or {@code null}
 * when the type is not one.
 *
 * <p>These types live only in the durable journal, so they are matched by name here rather than
 * added to {@link ExecutionEventType}. That enum is the <em>live, in-process</em> runtime
 * vocabulary and is switched over exhaustively by the monitor, the telemetry bridge, the audit sink
 * and the rate-limit registry; a handler event is produced by whichever process is alive when a
 * trigger arrives, which may be one that was not running when the wait began, so it is not an
 * in-process runtime transition and would be a false member of that set. Matching by name here
 * keeps the addition to a single method.</p>
 *
 * <p>The sentences say <em>which level</em> the event is about, because that is what a reader
 * scanning an activity log has to be able to tell apart: a handler event and a node event share a
 * process and a traversal, and only the wording and the identifiers beside it distinguish them.
 * None of them interpolates caller text, for the reason this class exists.</p>
* @param eventType domain event type recorded in the durable journal
* @return authored sentence, or {@code null} when this is not a handler event type
 */
    private static String handlerSentence(String eventType) {
        return switch (eventType) {
            case ai.ravenroot.api.persistence.HandlerEventData.HANDLER_REGISTERED ->
                    "A handler was registered and the process is waiting for it.";
            case ai.ravenroot.api.persistence.HandlerEventData.HANDLER_ESCALATED ->
                    "A waiting handler was escalated and can still be resolved.";
            case ai.ravenroot.api.persistence.HandlerEventData.HANDLER_EXPIRED ->
                    "A handler's wait ended without a trigger.";
            case ai.ravenroot.api.persistence.HandlerEventData.HANDLER_DENIED ->
                    "A handler was denied and the process continued.";
            case ai.ravenroot.api.persistence.HandlerEventData.HANDLER_RESOLVED ->
                    "A handler was resolved and the process re-entered.";
            case ai.ravenroot.api.persistence.HandlerEventData.HANDLER_TRIGGER_REFUSED ->
                    "A handler trigger was refused. Protected diagnostics may contain more detail.";
            default -> null;
        };
    }

    /** Package-visible so the contract's Unicode/control/bound behavior can be tested directly. */
    static String normalizeAuthoredText(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_EVENT;
        }
        var normalized = new StringBuilder(value.length());
        boolean previousSpace = true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int kind = Character.getType(codePoint);
            boolean replace = Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || kind == Character.FORMAT
                    || kind == Character.LINE_SEPARATOR
                    || kind == Character.PARAGRAPH_SEPARATOR
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
            if (replace) {
                if (!previousSpace) {
                    normalized.append(' ');
                    previousSpace = true;
                }
            } else {
                normalized.appendCodePoint(codePoint);
                previousSpace = false;
            }
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') {
            normalized.setLength(length - 1);
        }
        if (normalized.isEmpty()) {
            return UNKNOWN_EVENT;
        }
        return boundUtf8(normalized.toString());
    }

    private static String boundUtf8(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES) {
            return value;
        }
        int budget = MAX_UTF8_BYTES - TRUNCATION_MARKER.length();
        var bounded = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int encoded = utf8Bytes(codePoint);
            if (used + encoded > budget) {
                break;
            }
            bounded.appendCodePoint(codePoint);
            used += encoded;
            offset += Character.charCount(codePoint);
        }
        return bounded.append(TRUNCATION_MARKER).toString();
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }
}
