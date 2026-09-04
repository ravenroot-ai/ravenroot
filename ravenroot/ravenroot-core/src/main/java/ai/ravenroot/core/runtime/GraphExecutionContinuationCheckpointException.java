package ai.ravenroot.core.runtime;

/** Bounded diagnostic for a continuation that cannot safely restore graph execution accounting. */
public final class GraphExecutionContinuationCheckpointException extends IllegalArgumentException {
    public enum Reason { LEGACY_BUDGET_UNAVAILABLE, UNKNOWN_VERSION, MALFORMED, UNSAFE_REENTRY_STATE }

    private final Reason reason;

    GraphExecutionContinuationCheckpointException(Reason reason) {
        super("graph continuation checkpoint refused: "
                + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
