package ai.ravenroot.api.flow;

/** Durable lifecycle of one intergraph invocation relation. */
public enum FlowInvocationStatus {
    /** Intent exists; child launch has not been durably correlated yet. */
    INTENT,
    /** A fresh child process and traversal are durably correlated. */
    LAUNCHED,
    /** Child completed and a bounded result is available. */
    COMPLETED,
    /** Child failed before producing a result. */
    FAILED,
    /** Caller or child cancellation settled the relation. */
    CANCELLED,
    /** Deadline elapsed before a terminal child result was observed. */
    DEADLINE_EXCEEDED,
    /** Cancellation was requested but a still-running child was confirmed. */
    ORPHANED,
    /** Launch or settlement may have happened but cannot be proved safely. */
    AMBIGUOUS;

    /**
     * Reports whether no further child lifecycle transition is accepted.
     * @return whether this status is terminal
     */
    public boolean terminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, DEADLINE_EXCEEDED, ORPHANED, AMBIGUOUS -> true;
            case INTENT, LAUNCHED -> false;
        };
    }
}
