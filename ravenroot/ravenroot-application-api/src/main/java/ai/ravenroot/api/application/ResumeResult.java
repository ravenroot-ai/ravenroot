package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of {@link AuthorizedRavenrootApplication#resumeExecution}.
 *
 * <p>Resume is a transition of its own, not the withdrawal of a pause, and this type is where that
 * distinction becomes observable: {@link Outcome#NOT_PAUSED} is a running traversal that was never
 * holding, and it is reported rather than folded into a success. A caller that cannot tell "I
 * released it" from "it was never held" cannot tell whether its own pause ever took effect.</p>
 * @param outcome classification indicating whether resumption was applied, unavailable, or refused
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param note operator-facing explanation of the outcome.
 */
public record ResumeResult(Outcome outcome, UUID traversalId, String note) {

/**
 * Rejects incomplete resume outcomes and preserves their operator-visible explanation.
 */
    public ResumeResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(note, "note");
    }

/**
 * The traversal was holding and is dispatching again from the hop it held at.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a result recording that the identified traversal has resumed dispatching.
 */
    public static ResumeResult resumed(UUID traversalId) {
        return new ResumeResult(Outcome.RESUMED, traversalId,
                "This traversal is running again, continuing from the node it was holding before.");
    }

/**
 * Running, but not holding: there was nothing to release.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a result recording that no held traversal state was available to resume.
 */
    public static ResumeResult notPaused(UUID traversalId) {
        return new ResumeResult(Outcome.NOT_PAUSED, traversalId,
                "This traversal is running and was not paused; this request changed nothing.");
    }

/**
 * Not running here at all: completed, cancelled, or never started in this process.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a result recording that this process does not own an active traversal with that identifier.
 */
    public static ResumeResult notActive(UUID traversalId) {
        return new ResumeResult(Outcome.NOT_ACTIVE, traversalId,
                "This traversal is not running here, so there was nothing to resume. It may have "
                        + "completed, been cancelled, or never have started in this process.");
    }

/**
 * Enumerates the supported outcome values returned by this API.
 */
    public enum Outcome {
/**
 * Rejects incomplete resume outcomes and preserves their operator-visible explanation.
 */
        RESUMED,
/**
 * Rejects incomplete resume outcomes and preserves their operator-visible explanation.
 */
        NOT_PAUSED,
/**
 * Rejects incomplete resume outcomes and preserves their operator-visible explanation.
 */
        NOT_ACTIVE
    }
}
