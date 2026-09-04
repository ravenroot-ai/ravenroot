package ai.ravenroot.core.approval;

/** Bounded diagnostic for a continuation that cannot safely restore graph execution accounting. */
public final class ToolApprovalContinuationCheckpointException extends IllegalArgumentException {
    public enum Reason { LEGACY_BUDGET_UNAVAILABLE, UNKNOWN_VERSION, MALFORMED, UNSAFE_REENTRY_STATE }

    private final Reason reason;

    ToolApprovalContinuationCheckpointException(Reason reason) {
        super("tool approval continuation checkpoint refused: " + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
