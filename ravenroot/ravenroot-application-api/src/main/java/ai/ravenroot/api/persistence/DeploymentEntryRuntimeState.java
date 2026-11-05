package ai.ravenroot.api.persistence;

/** Monotone runtime budget and root dispatch reservation retained independently of activity capture.
 * @param traversalSteps total charged original deliveries and retries
 * @param amplifiedDeliveries total charged non-root deliveries
 * @param payloadBytes total charged cumulative payload bytes
 * @param rootFanOutReserved whether original SOURCE successors have already been charged
 */
public record DeploymentEntryRuntimeState(long traversalSteps, long amplifiedDeliveries, long payloadBytes,
        boolean rootFanOutReserved) {
    /** Requires finite nonnegative counters. */
    public DeploymentEntryRuntimeState {
        if (traversalSteps < 0 || amplifiedDeliveries < 0 || payloadBytes < 0)
            throw new IllegalArgumentException("entry budget counters cannot be negative");
    }
    /** Refuses resetting counters or withdrawing the original source reservation.
     * @param previous previously committed runtime state
     */
    public void requireSuccessorOf(DeploymentEntryRuntimeState previous) {
        if (previous != null && (traversalSteps < previous.traversalSteps || amplifiedDeliveries < previous.amplifiedDeliveries
                || payloadBytes < previous.payloadBytes || previous.rootFanOutReserved && !rootFanOutReserved))
            throw new IllegalArgumentException("entry runtime counters cannot decrease");
    }
}
