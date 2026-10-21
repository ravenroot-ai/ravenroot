package ai.ravenroot.api.correlation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded public summary. Ended traversals do not implicitly close an external business operation.
 * @param scope tenant, logical deployment and exact immutable graph version
 * @param applicationCorrelationId opaque business operation identity within the explicit scope
 * @param publicReference optional bounded non-secret exact lookup reference
 * @param lifecycle explicit business closure state; a quiet history remains open
 * @param revision monotone compare-and-set revision of the retained record
 * @param runningTraversals number of admitted traversals that have not settled
 * @param completedTraversals number of successfully settled traversals without declaring business closure
 * @param failedTraversals number of failed traversals retained even when later traversals succeed
 * @param cancelledTraversals number of explicitly cancelled traversals
 * @param incompleteHistory whether retention or incomplete collection prevents a complete historical account
 * @param createdAt time this business history was first established
 * @param updatedAt time of the latest recorded change; pagination does not depend on this value
 * @param expiresAt retention deadline for the represented history
 */
public record BusinessHistory(BusinessScope scope, String applicationCorrelationId, Optional<String> publicReference,
                               Lifecycle lifecycle, long revision, long runningTraversals,
                               long completedTraversals, long failedTraversals, long cancelledTraversals,
                               boolean incompleteHistory, Instant createdAt, Instant updatedAt, Instant expiresAt) {
    /** Explicit closure authority; tombstones never silently reopen. */
    public enum Lifecycle {
        /** Awaiting an explicit business closure; quiet periods do not complete it. */
        OPEN,
        /** Explicitly closed as completed. */
        COMPLETED,
        /** Explicitly closed as failed or irrecoverably incomplete. */
        FAILED,
        /** Explicitly cancelled; late events cannot reopen it. */
        CANCELLED,
        /** Retention expired; small closure metadata remains observable. */
        EXPIRED
    }
    /** Honest summary state, distinct from lifecycle and each traversal's result. */
    public enum State {
        /** At least one admitted traversal remains unsettled. */
        RUNNING,
        /** No traversal is running, and the business history remains explicitly open. */
        AWAITING_EXTERNAL_EVENT,
        /** Explicit business completion with no retained failure. */
        COMPLETED,
        /** Failure is retained; later quietness does not erase it. */
        FAILED,
        /** Successful and failed traversal results coexist in the history. */
        PARTIAL,
        /** The business history was explicitly cancelled. */
        CANCELLED,
        /** Retention or incomplete collection prevents a complete historical account. */
        INCOMPLETE_HISTORY
    }
    /** Validates monotone revisions and nonnegative independent traversal counts. */
    public BusinessHistory {
        Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (applicationCorrelationId == null || applicationCorrelationId.isBlank() || revision < 1
                || runningTraversals < 0 || completedTraversals < 0 || failedTraversals < 0 || cancelledTraversals < 0)
            throw new IllegalArgumentException("invalid business history");
        publicReference = publicReference == null ? Optional.empty() : publicReference;
    }
    /** Derives observation without erasing an earlier failure or inferring closure from quietness.
 * @return honest observation state derived from explicit closure and retained traversal results
 */
    public State state() {
        if (incompleteHistory || lifecycle == Lifecycle.EXPIRED) return State.INCOMPLETE_HISTORY;
        if (lifecycle == Lifecycle.CANCELLED) return State.CANCELLED;
        if (lifecycle == Lifecycle.FAILED) return State.FAILED;
        if (failedTraversals > 0) return completedTraversals > 0 ? State.PARTIAL : State.FAILED;
        if (runningTraversals > 0) return State.RUNNING;
        if (lifecycle == Lifecycle.COMPLETED) return State.COMPLETED;
        return State.AWAITING_EXTERNAL_EVENT;
    }
}
