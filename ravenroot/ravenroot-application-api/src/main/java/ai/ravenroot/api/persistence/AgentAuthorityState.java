package ai.ravenroot.api.persistence;

/** Durable lifecycle states for process-rooted agent authority. */
public enum AgentAuthorityState {
    /** The root may admit work while its global control epoch remains current. */
    ACTIVE,
    /** The owning execution ended or was cancelled. */
    CANCELLED,
    /** The store-global emergency control revoked the root. */
    KILLED
}
