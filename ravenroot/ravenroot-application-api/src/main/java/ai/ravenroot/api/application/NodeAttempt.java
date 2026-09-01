package ai.ravenroot.api.application;

import java.util.UUID;

/**
 * Immutable state of one ordered attempt to deliver a node invocation.
 *
 * <p>Two components are conditional on {@link #status()} and each is an <em>iff</em>, not a
 * convention: {@link #completion()} is present exactly when the attempt is
 * {@link NodeAttemptStatus#COMPLETED}, and {@link #parkCause()} is present exactly when it is
 * {@link NodeAttemptStatus#PARKED}. Both are enforced in the canonical constructor, so an aggregate
 * folded out of a store cannot carry a parked attempt with no reason for the human who has to decide
 * about it, nor a completion attached to a state that never completed.</p>
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param ordinal the ordinal constraint applied while processing the request.
 * @param status current state of this attempt in the attempt lifecycle
 * @param completion terminal conclusion, present only once the attempt is resolved
 * @param parkCause bounded operator-facing explanation retained while the attempt is parked
 */
public record NodeAttempt(UUID attemptId, int ordinal, NodeAttemptStatus status,
                          NodeAttemptCompletion completion, String parkCause) {

    /**
     * Bound on the operator-facing cause, marker included, so a store row cannot become an unbounded
     * text sink. Counted in chars, exactly like {@link ExecutionEvent#MAX_DETAIL_LENGTH} — see
     * {@link #sanitize(String)} for why the unit changed.
     */
    public static final int MAX_PARK_CAUSE_LENGTH = 512;

    /**
     * Appended to a cause that was cut, so a truncated cause is never readable as a complete one.
     * The park cause is read by a human deciding why a node parked, and a diagnostic cut
     * mid-sentence with nothing to show for it is read as the whole story.
     *
     * <p>Identical to {@link ExecutionEvent#DETAIL_TRUNCATION_MARKER} by construction: both take the
     * text from {@code DiagnosticText}, so an operator learns one marker rather than one per
     * surface.</p>
     */
    public static final String PARK_CAUSE_TRUNCATION_MARKER = DiagnosticText.TRUNCATION_MARKER;

/**
 * Enforces the relationship between attempt state, terminal completion and park diagnostics.
 */
    public NodeAttempt {
        if (attemptId == null) throw new IllegalArgumentException("attemptId cannot be null");
        if (ordinal < 1) throw new IllegalArgumentException("attempt ordinal must be positive");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (status == NodeAttemptStatus.COMPLETED && completion == null) {
            throw new IllegalArgumentException("completed attempt requires a completion");
        }
        if (status != NodeAttemptStatus.COMPLETED && completion != null) {
            throw new IllegalArgumentException("only a completed attempt may have a completion");
        }
        parkCause = parkCause == null || parkCause.isBlank() ? null : sanitize(parkCause);
        if (status == NodeAttemptStatus.PARKED && parkCause == null) {
            throw new IllegalArgumentException("parked attempt requires a cause");
        }
        if (status != NodeAttemptStatus.PARKED && parkCause != null) {
            throw new IllegalArgumentException("only a parked attempt may have a park cause");
        }
    }

/**
 * Pre-PERS-04 arity: an attempt that is not parked.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param ordinal the ordinal constraint applied while processing the request.
 * @param status current state of this attempt in the attempt lifecycle
 * @param completion terminal conclusion, present only once the attempt is resolved
 */
    public NodeAttempt(UUID attemptId, int ordinal, NodeAttemptStatus status,
                       NodeAttemptCompletion completion) {
        this(attemptId, ordinal, status, completion, null);
    }

/**
 * Creates a backwards-compatible attempt that has no park diagnostic.
 * @param attemptId stable ID of this execution attempt
 * @param ordinal one-based position among retries of the same invocation
 * @param status initial lifecycle state for the attempt
 */
    public NodeAttempt(UUID attemptId, int ordinal, NodeAttemptStatus status) {
        this(attemptId, ordinal, status,
                status == NodeAttemptStatus.COMPLETED ? NodeAttemptCompletion.SUCCEEDED : null, null);
    }

/**
 * Compatibility alias for early attempt1 terminology.
 * @return the legacy one-based attempt number, equal to {@link #ordinal()}
 */
    public int number() {
        return ordinal;
    }

/**
 * Returns a copy after one legal lifecycle transition.
 * @param next target lifecycle state
 * @return updated attempt retaining its identity and ordinal
 */
    public NodeAttempt transitionTo(NodeAttemptStatus next) {
        if (next == NodeAttemptStatus.PARKED) {
            // Parking without a cause would produce exactly the state the human resolving it cannot
            // act on, so the plain transition refuses and the caller must use park(cause).
            throw new IllegalArgumentException("Parking requires a cause: use park(String)");
        }
        if (status == NodeAttemptStatus.PARKED && next == NodeAttemptStatus.COMPLETED) {
            // This path hardcodes SUCCEEDED below, and SUCCEEDED means "the runtime observed it".
            // Nobody observed a parked attempt, so completing one here would forge the observation.
            throw new IllegalArgumentException(
                    "A parked attempt completes only through resolveVerified()");
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal node attempt transition: " + status + " -> " + next);
        }
        return new NodeAttempt(attemptId, ordinal, next,
                next == NodeAttemptStatus.COMPLETED ? NodeAttemptCompletion.SUCCEEDED : null, null);
    }

/**
 * Completes a running attempt whose node returned a WAIT result.
 * @return the same attempt resolved with {@link NodeAttemptCompletion#WAIT}
 */
    public NodeAttempt completeWait() {
        if (status != NodeAttemptStatus.WAITING) {
            throw new IllegalStateException("Only a WAITING attempt can complete with WAIT");
        }
        return new NodeAttempt(attemptId, ordinal, NodeAttemptStatus.COMPLETED,
                NodeAttemptCompletion.WAIT, null);
    }

    /**
     * Records that this attempt was dispatched and its outcome is unknown (ADR 0022).
     *
     * <p>Legal only from {@link NodeAttemptStatus#RUNNING}: {@code SCHEDULED} is provably
     * effect-free because the runtime persists {@code RUNNING} before the engine send, and
     * {@code WAITING} means the dispatch outcome <em>is</em> known.</p>
 * @param cause bounded description of why the outcome requires operator resolution
 * @return a waiting attempt carrying that diagnostic
     */
    public NodeAttempt park(String cause) {
        if (cause == null || cause.isBlank()) {
            throw new IllegalArgumentException("park cause cannot be blank");
        }
        if (!status.canTransitionTo(NodeAttemptStatus.PARKED)) {
            throw new IllegalStateException(
                    "Illegal node attempt transition: " + status + " -> " + NodeAttemptStatus.PARKED);
        }
        return new NodeAttempt(attemptId, ordinal, NodeAttemptStatus.PARKED, null, cause);
    }

    /**
     * Closes a parked attempt on a human's assertion that the effect did happen. The completion is
     * {@link NodeAttemptCompletion#OPERATOR_VERIFIED} and never {@code SUCCEEDED}.
 * @return a resolved attempt recording operator verification of the effect
     */
    public NodeAttempt resolveVerified() {
        requireParked();
        return new NodeAttempt(attemptId, ordinal, NodeAttemptStatus.COMPLETED,
                NodeAttemptCompletion.OPERATOR_VERIFIED, null);
    }

/**
 * Closes a parked attempt on a human's assertion that the effect did not happen.
 * @return a resolved attempt recording that the effect did not happen
 */
    public NodeAttempt resolveFailed() {
        requireParked();
        return new NodeAttempt(attemptId, ordinal, NodeAttemptStatus.FAILED, null, null);
    }

    private void requireParked() {
        if (status != NodeAttemptStatus.PARKED) {
            throw new IllegalStateException("Only a PARKED attempt can be resolved, not " + status);
        }
    }

    /**
     * Strips control characters, then bounds the result with a visible marker. This value reaches
     * operator surfaces, logs and a store row, so an unbounded or newline-bearing string would let a
     * node's failure text forge log lines, and a silently cut one would let a fragment be read as the
     * whole cause.
     *
     * <p><strong>The bound counts chars, not code points, and that is deliberate.</strong>
     * It previously counted code points, so an astral cause could occupy 1024 chars while claiming a
     * bound of 512 — and {@link ExecutionEvent#MAX_DETAIL_LENGTH}, which documents itself as matching
     * this constant "rather than inventing a second number for the same kind of value", counts chars.
     * One 512 meaning two things in one package is the drift this rule removes. Chars is
     * also the unit the consumers actually budget in: a column width, a log line, a JSON response.
     * The new bound is never looser than the old one for any input, so no store row can overflow that
     * did not before.</p>
     *
     * <p>Order matters. Stripping and trimming happen first and the bound last, so a cause made only
     * of control characters still reaches the {@code "unspecified"} fallback instead of being cut to a
     * marker with no diagnostic in front of it. Control characters are all BMP, so replacing them with
     * a space never changes the char count the bound then measures.</p>
     */
    private static String sanitize(String cause) {
        var text = new StringBuilder(cause.length());
        cause.codePoints()
                .forEach(point -> text.appendCodePoint(Character.isISOControl(point) ? ' ' : point));
        String sanitized = text.toString().trim();
        return sanitized.isEmpty()
                ? "unspecified"
                : DiagnosticText.bounded(sanitized, MAX_PARK_CAUSE_LENGTH, PARK_CAUSE_TRUNCATION_MARKER);
    }
}
