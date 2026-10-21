package ai.ravenroot.api.correlation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded public summary. Ended traversals do not implicitly close an external business operation. */
public record BusinessHistory(BusinessScope scope, String applicationCorrelationId, Optional<String> publicReference,
                               Lifecycle lifecycle, long revision, long runningTraversals,
                               long completedTraversals, long failedTraversals, long cancelledTraversals,
                               boolean incompleteHistory, Instant createdAt, Instant updatedAt, Instant expiresAt) {
    /** Explicit closure authority; tombstones never silently reopen. */
    public enum Lifecycle { OPEN, COMPLETED, FAILED, CANCELLED, EXPIRED }
    /** Honest summary state, distinct from lifecycle and each traversal's result. */
    public enum State { RUNNING, AWAITING_EXTERNAL_EVENT, COMPLETED, FAILED, PARTIAL, CANCELLED, INCOMPLETE_HISTORY }
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
    /** Derives observation without erasing an earlier failure or inferring closure from quietness. */
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
