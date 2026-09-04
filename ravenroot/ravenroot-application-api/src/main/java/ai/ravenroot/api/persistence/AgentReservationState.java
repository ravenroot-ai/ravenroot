package ai.ravenroot.api.persistence;

/** Durable lifecycle of one exact external-operation reservation. */
public enum AgentReservationState {
    /** Resources reserved before external dispatch. */
    HELD,
    /** External dispatch crossed the effect boundary. */
    DISPATCHED,
    /** Known usage was charged within the reservation. */
    SETTLED,
    /** Reported usage exceeded the reservation and revoked authority. */
    BREACHED,
    /** The post-dispatch effect outcome cannot be determined safely. */
    INDETERMINATE,
    /** The held reservation was released before dispatch. */
    RELEASED;

    /**
     * Returns whether no further lifecycle transition is legal.
     * @return whether the state is terminal
     */
    public boolean terminal() {
        return this == SETTLED || this == BREACHED || this == INDETERMINATE || this == RELEASED;
    }
}
