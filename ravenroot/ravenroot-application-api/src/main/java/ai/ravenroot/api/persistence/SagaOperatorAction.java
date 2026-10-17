package ai.ravenroot.api.persistence;

/** Governed recovery requests that never manufacture a participant outcome. */
public enum SagaOperatorAction {
    /** Re-open delivery or participant lookup for a forward operation with an unknown outcome. */
    RECONCILE,
    /** Re-open a previously attempted compensation under its stable compensation identity. */
    RETRY_COMPENSATION,
    /** Request compensation of effects already recorded as confirmed. */
    COMPENSATE
}
