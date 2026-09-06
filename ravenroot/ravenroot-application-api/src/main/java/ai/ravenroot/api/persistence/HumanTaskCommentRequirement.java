package ai.ravenroot.api.persistence;

/** Whether an embedded confirmation action accepts or requires a decision comment. */
public enum HumanTaskCommentRequirement {
    /** The presentation accepts no decision comment. */
    DISALLOWED,
    /** A responder may attach one bounded decision comment. */
    OPTIONAL,
    /** A responder must attach one bounded non-blank decision comment. */
    REQUIRED
}
