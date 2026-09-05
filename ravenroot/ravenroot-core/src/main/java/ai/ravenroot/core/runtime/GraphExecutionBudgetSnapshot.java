package ai.ravenroot.core.runtime;

/** Trusted, bounded counters needed to continue one traversal without resetting its limits. */
public record GraphExecutionBudgetSnapshot(
        long traversalSteps,
        long amplifiedDeliveries,
        long payloadBytes,
        int inFlightHops,
        int liveActors) {

    public GraphExecutionBudgetSnapshot {
        if (traversalSteps < 0 || amplifiedDeliveries < 0 || payloadBytes < 0
                || inFlightHops < 0 || liveActors < 0) {
            throw new IllegalArgumentException("graph execution budget counters cannot be negative");
        }
    }
}
