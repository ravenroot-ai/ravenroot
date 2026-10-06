package ai.ravenroot.api.deployment;

/** Durable custody of one stable-consumer event's deterministic traversal. */
public enum DurableIngressStartState {
    /** No execution was committed for this event identity. */
    ABSENT,
    /** The inbox commit survived, but execution creation did not. */
    INBOX_ONLY,
    /** An execution was accepted, but its first invocation is not durable yet. */
    ACCEPTED_UNSTARTED,
    /** At least one invocation was committed to the execution store. */
    STARTED,
    /** The execution ended without any durable invocation and needs operator reconciliation. */
    TERMINAL_UNSTARTED
}
