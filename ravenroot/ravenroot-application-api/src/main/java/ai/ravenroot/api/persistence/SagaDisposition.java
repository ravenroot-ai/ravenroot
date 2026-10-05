package ai.ravenroot.api.persistence;

/** Durable terminal and non-terminal outcomes of a governed saga scope. */
public enum SagaDisposition {
    /** Forward work may still be dispatched or completed. */
    RUNNING(false),
    /** Governed operator pause prevents new dispatch. */
    PAUSED(false),
    /** At least one confirmed effect still requires compensation. */
    COMPENSATION_PENDING(false),
    /** A forward or compensation outcome needs participant reconciliation. */
    UNRESOLVED(false),
    /** Every required forward business effect completed. */
    SUCCEEDED(true),
    /** Every confirmed effect was compensated. */
    COMPENSATED(true);

    private final boolean terminal;

    SagaDisposition(boolean terminal) { this.terminal = terminal; }

    /**
     * Whether no late participant outcome can still change the business disposition.
     * @return terminal flag
     */
    public boolean terminal() { return terminal; }
}
