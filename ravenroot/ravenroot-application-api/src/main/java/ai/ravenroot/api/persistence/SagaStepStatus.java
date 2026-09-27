package ai.ravenroot.api.persistence;

/** Persisted lifecycle of one logical occurrence of a saga step. */
public enum SagaStepStatus {
    /** Participant input and stable identity are durable. */
    INTENT_RECORDED,
    /** The participant call may have begun. */
    DISPATCHED,
    /** Participant protocol proved no effect occurred. */
    CONFIRMED_NO_EFFECT,
    /** Participant protocol returned or reconciled a committed effect. */
    CONFIRMED_SUCCESS,
    /** The effect may have occurred and must be reconciled. */
    OUTCOME_UNKNOWN,
    /** A confirmed effect awaits its compensation dependency order. */
    COMPENSATION_PENDING,
    /** The compensation call may have begun. */
    COMPENSATING,
    /** Participant protocol confirmed compensation. */
    COMPENSATED,
    /** Compensation may have occurred and must be reconciled. */
    COMPENSATION_UNKNOWN,
    /** The authored contract explicitly has no compensation. */
    IRREVERSIBLE
}
