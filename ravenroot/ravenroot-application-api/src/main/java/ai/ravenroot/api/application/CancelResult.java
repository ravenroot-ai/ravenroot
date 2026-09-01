package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of {@link AuthorizedRavenrootApplication#cancelExecution}, exposing every distinguishable
 * result an operator needs (API-02).
 *
 * <h2>Why {@code note} is a field, not a Javadoc line</h2>
 * <p>Cancelling a traversal that already emitted an external effect -- a mail sent, a payment issued --
 * must not read back as a bare {@link Outcome#CANCELLED}. Future work stops; the past does not, and an
 * operator who reads "cancelled" and concludes the mail never went makes wrong decisions on that belief.
 * {@link #note()} is what carries that qualification over REST and the CLI, where nobody reads this
 * class's source: a javadoc comment reaches a developer reading this file, never an operator reading a
 * JSON body or a terminal line, so the caveat has to travel as data or it does not travel at all.</p>
 *
 * <p>There is deliberately no {@code UNKNOWN} outcome here. An unknown traversal id fails closed at the
 * authorization boundary -- {@link ai.ravenroot.api.security.ProtectedResource#unknownOwnership} denies
 * the request with {@link ai.ravenroot.api.security.AuthorizationDeniedException} before a
 * {@code CancelResult} is ever constructed -- matching {@code ExecutionOwnershipRegistry}'s existing rule
 * that evicted ownership becomes unknown and fails closed, rather than this type inventing a second,
 * weaker way to say the same thing as a successful-looking return value.</p>
 * @param outcome classification indicating whether cancellation was applied, unavailable, or refused
 * @param traversalId traversal whose cancellation was evaluated
 * @param note operator-facing explanation of the outcome.
 */
public record CancelResult(Outcome outcome, UUID traversalId, String note) {

/**
 * Rejects incomplete results so every transport receives an outcome and traversal identity.
 */
    public CancelResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(note, "note");
    }

/**
 * The traversal was active and has been asked to stop.
 * @param traversalId active traversal to stop
 * @return result explaining that future work was cancelled but prior effects remain
 */
    public static CancelResult cancelled(UUID traversalId) {
        return new CancelResult(Outcome.CANCELLED, traversalId,
                "Future work on this traversal has stopped. Effects already issued before cancellation "
                        + "(an external call, a message sent, a payment issued) may persist: they are "
                        + "not, and cannot be, undone by this cancellation.");
    }

/**
 * Idempotent repeat: this traversal was already cancelled. Not an error.
 * @param traversalId traversal already in the cancelled state
 * @return idempotent result explaining that this request changed no state
 */
    public static CancelResult alreadyCancelled(UUID traversalId) {
        return new CancelResult(Outcome.ALREADY_CANCELLED, traversalId,
                "This traversal was already cancelled; this request changed nothing.");
    }

/**
 * The traversal had already run to completion before this request. Not a silent success.
 * @param traversalId traversal that completed before cancellation was attempted
 * @return result explaining that no future work remained to stop
 */
    public static CancelResult alreadyCompleted(UUID traversalId) {
        return new CancelResult(Outcome.ALREADY_COMPLETED, traversalId,
                "This traversal had already completed before this request; there was no future work "
                        + "left to stop.");
    }

/**
 * Enumerates the supported outcome values returned by this API.
 */
    public enum Outcome {
        /** Cancellation stopped future work. */
        CANCELLED,
        /** Traversal was already cancelled, so the request was idempotent. */
        ALREADY_CANCELLED,
        /** Traversal had already reached a terminal completed state. */
        ALREADY_COMPLETED
    }
}
