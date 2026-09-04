package ai.ravenroot.api.persistence;

/** Durable lifecycle states for one attenuated agent grant. */
public enum AgentGrantState {
    /** The grant may admit resources. */
    ACTIVE,
    /** The grant or an ancestor was cancelled. */
    CANCELLED,
    /** The grant completed or exhausted a finite ceiling. */
    EXHAUSTED
}
