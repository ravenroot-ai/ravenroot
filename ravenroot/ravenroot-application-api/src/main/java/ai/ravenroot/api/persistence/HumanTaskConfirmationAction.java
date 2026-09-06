package ai.ravenroot.api.persistence;

/** One operator-safe action an embedded Human Task confirmation may offer. */
public enum HumanTaskConfirmationAction {
    /** Accepts the built-in confirmation response. */
    RESOLVE,
    /** Selects the graph's denial outcome without a response payload. */
    DENY,
    /** Cancels the task under the requester's existing cancellation authority. */
    CANCEL
}
