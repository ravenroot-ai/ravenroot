package ai.ravenroot.api.persistence;
import java.util.*;
/** Truthful input-custody admission; a retired or missing input is never represented as an empty payload.
 * @param outcome original admission or explicit retained-input availability
 * @param intent original retained input, present only when custody is available
 */
public record DeploymentEntryAdmission(Outcome outcome, Optional<DeploymentEntryIntent> intent) {
    /** Durable admission and retained-input availability categories. */
    public enum Outcome {
        /** New input and immutable business relationship committed together. */
        ACCEPTED,
        /** The original input remains retained; retry did not replace it. */
        PENDING,
        /** The original traversal is already terminal; no new input or traversal is admitted. */
        SETTLED,
        /** A legacy/incomplete admission has no retained custody input and requires explicit reconciliation. */
        INPUT_UNAVAILABLE
    }
    /** Refuses combinations that would fabricate retained data for a settled or incomplete admission. */
    public DeploymentEntryAdmission {
        Objects.requireNonNull(outcome); intent = Objects.requireNonNull(intent);
        if ((outcome == Outcome.ACCEPTED || outcome == Outcome.PENDING) != intent.isPresent())
            throw new IllegalArgumentException("invalid entry admission availability");
    }
}
