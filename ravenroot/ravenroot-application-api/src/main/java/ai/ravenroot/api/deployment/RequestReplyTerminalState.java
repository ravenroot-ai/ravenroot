package ai.ravenroot.api.deployment;

/** The one terminal observation delivered to a {@link RequestReplyExchange}. */
public enum RequestReplyTerminalState {
    /** The traversal reached a terminal node and produced a bounded result. */
    COMPLETED,
    /** The traversal or result projection failed. */
    FAILED,
    /** The live caller detached before the traversal completed. */
    CANCELLED,
    /** The caller's finite deadline elapsed before a result won. */
    TIMED_OUT
}
