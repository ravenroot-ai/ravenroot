package ai.ravenroot.api.node.service;

/** Invocation-bound authority and accounting view. */
public interface AgentResourceSession {
    /** Reserves and dispatches one model turn before any provider byte leaves the runtime. */
    AgentModelReservation reserveModelTurn(long ordinal);

    /** Creates one invocation-bound child using authority that must be strictly attenuated. */
    AgentResourceSession createChild(AgentChildResourceRequest request);

    /** Marks the grant terminal after the agent has produced its final result. */
    void complete();

    /** Cancels the grant and its delegated subtree. */
    void cancel();

    /** Releases only process-local association while durable approval waiting continues. */
    void suspend();
}
