package ai.ravenroot.api.execution;

/** Why a node reached {@link NodeLifecycleState#TERMINATED}. */
public enum NodeTerminationReason {
    /** Every message accepted before the drain began was completed by the node. */
    STOPPED,
    /**
     * At least the possibility of abandoned work exists: the node was cancelled, so every message
     * accepted and not yet settled was failed with {@link NodeCancelledException}. A cancellation
     * that reached the node after it had already drained reports {@link #STOPPED} instead, because
     * nothing was abandoned.
     */
    CANCELLED
}
