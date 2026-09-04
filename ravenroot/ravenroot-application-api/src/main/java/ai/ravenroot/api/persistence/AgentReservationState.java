package ai.ravenroot.api.persistence;

/** Durable lifecycle of one exact external-operation reservation. */
public enum AgentReservationState {
    HELD, DISPATCHED, SETTLED, INDETERMINATE, RELEASED;

    public boolean terminal() {
        return this == SETTLED || this == INDETERMINATE || this == RELEASED;
    }
}
