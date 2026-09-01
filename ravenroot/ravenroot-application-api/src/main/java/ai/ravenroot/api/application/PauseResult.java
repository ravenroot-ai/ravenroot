package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of {@link AuthorizedRavenrootApplication#pauseExecution}.
 *
 * <h2>Shaped after {@link CancelResult}, and for the same reason</h2>
 * <p>{@link #note()} exists here for the reason it exists there: the qualification an operator needs
 * does not survive as a Javadoc line, because nobody holding a JSON body or a terminal line is
 * reading this file. What has to travel with a pause is the part that is easy to get wrong — the
 * node that was already running is <em>not</em> stopped, and whatever it does it will still do — so
 * it travels as data.</p>
 *
 * <p>There is deliberately no {@code UNKNOWN} outcome, exactly as on {@link CancelResult}: an
 * unknown traversal id fails closed at the authorization boundary before a result of this type is
 * ever constructed.</p>
 * @param outcome classification indicating whether pausing was applied, unavailable, or refused
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param note operator-facing explanation of the outcome.
 */
public record PauseResult(Outcome outcome, UUID traversalId, String note) {

/**
 * Rejects incomplete pause outcomes and preserves their operator-visible explanation.
 */
    public PauseResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(note, "note");
    }

/**
 * The traversal was running and is now holding before its next node.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a result recording that the traversal will stop before its next node invocation.
 */
    public static PauseResult paused(UUID traversalId) {
        return new PauseResult(Outcome.PAUSED, traversalId,
                "This traversal will not start another node until it is resumed. The node that was "
                        + "already running is not interrupted: it finishes, and any effect it issues "
                        + "is issued. Its state is kept, so the execution can be inspected while it "
                        + "holds, and it remains cancellable.");
    }

/**
 * Idempotent repeat: this traversal was already holding. Not an error.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a non-error result recording that the traversal was already held.
 */
    public static PauseResult alreadyPaused(UUID traversalId) {
        return new PauseResult(Outcome.ALREADY_PAUSED, traversalId,
                "This traversal was already paused; this request changed nothing.");
    }

    /**
     * Nothing was holding: the traversal had already finished, was cancelled, or never ran in this
     * process. Distinguishable rather than reported as a success that did nothing.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a result recording that this process has no active traversal to hold.
     */
    public static PauseResult notActive(UUID traversalId) {
        return new PauseResult(Outcome.NOT_ACTIVE, traversalId,
                "This traversal is not running here, so there was nothing to pause. It may have "
                        + "completed, been cancelled, or never have started in this process.");
    }

/**
 * Enumerates the supported outcome values returned by this API.
 */
    public enum Outcome {
/**
 * Rejects incomplete pause outcomes and preserves their operator-visible explanation.
 */
        PAUSED,
/**
 * Rejects incomplete pause outcomes and preserves their operator-visible explanation.
 */
        ALREADY_PAUSED,
/**
 * Rejects incomplete pause outcomes and preserves their operator-visible explanation.
 */
        NOT_ACTIVE
    }
}
