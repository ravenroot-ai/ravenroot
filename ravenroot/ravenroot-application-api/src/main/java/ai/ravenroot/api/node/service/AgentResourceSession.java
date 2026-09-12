package ai.ravenroot.api.node.service;

/** Invocation-bound authority and accounting view. */
public interface AgentResourceSession {
    /**
     * Reserves one model turn before any provider byte leaves the runtime.
     * @param ordinal caller-owned monotonic turn ordinal
     * @return held reservation carrying effective outbound limits
     */
    AgentModelReservation reserveModelTurn(long ordinal);

    /**
     * Creates one invocation-bound child using authority that must be strictly attenuated.
     * @param request child identity, scopes and finite resource bounds
     * @return child invocation's resource session
     */
    AgentResourceSession createChild(AgentChildResourceRequest request);

    /** Marks the grant terminal after the agent has produced its final result. */
    void complete();

    /** Cancels the grant and its delegated subtree. */
    void cancel();

    /**
     * Releases resources owned by a failed attempt while preserving a logical grant only when the
     * trusted mediator can prove a runtime retry may follow. The fail-closed compatibility default
     * cancels the grant, so an older or third-party mediator cannot accidentally retain authority.
     */
    default void failAttempt() {
        cancel();
    }

    /** Releases only process-local association while durable approval waiting continues. */
    void suspend();
}
