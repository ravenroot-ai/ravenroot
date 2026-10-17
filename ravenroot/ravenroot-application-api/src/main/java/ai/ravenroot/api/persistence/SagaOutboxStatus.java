package ai.ravenroot.api.persistence;

/** Delivery stage of an application command; broker acceptance is distinct from business completion. */
public enum SagaOutboxStatus {
    /** Eligible after its not-before instant. */
    PENDING,
    /** Owned under a bounded lease and fencing token. */
    CLAIMED,
    /** Durable broker confirmation was observed. */
    BROKER_ACCEPTED,
    /** Participant inbox and business effect were confirmed. */
    BUSINESS_COMPLETED,
    /** Delivery or lookup exhausted its bounded attempt policy. */
    EXHAUSTED
}
