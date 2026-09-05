package ai.ravenroot.api.persistence;

/** Store-global lifecycle of first-party agent authority. */
public enum AgentAuthorityControlState {
    /** New authority operations may be admitted for the current epoch. */
    ACTIVE,
    /** New authority operations are refused until an operator advances the epoch. */
    KILLED
}
