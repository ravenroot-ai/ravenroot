package ai.ravenroot.api.persistence;

/**
 * Optional facilities an execution-store adapter may declare, following the
 * {@link ai.ravenroot.api.execution.EngineCapability} precedent (ADR 0010 section 11).
 *
 * <p>The set is closed in the port and is not adapter-extensible, because an adapter-defined
 * capability could not be asserted by a shared conformance suite.</p>
 *
 * <p>Enforcement is <strong>asymmetric</strong>: absence of a capability skips its assertion,
 * presence never skips it. Declaring a capability an adapter does not honour, in order to route
 * around a skipped assertion, invalidates the conformance result.</p>
 */
public enum StoreCapability {
    /**
     * State survives process death. The conformance assertion for this forces a real reopen or a
     * simulated process death and checks recovery, so an in-memory adapter must not declare it.
     */
    DURABLE,

    /** A batch is applied all-or-nothing, never partially, even across a failure. */
    TRANSACTIONAL_BATCH,

    /**
     * Leases and fencing tokens are coordinated across operating-system processes, and tokens are
     * never reused after a lease is lost, including across a store restart. An in-memory adapter
     * cannot honour the restart half of that and must not declare it.
     */
    CROSS_PROCESS_LEASE,

    /** Expired idempotency records can be purged on demand. Its absence must be declared, not silent. */
    IDEMPOTENCY_PURGE,

    /**
     * Publishable events can be journalled inside the same batch as the transition that produced
     * them, read back in order, and delivered through a durable per-destination outbox cursor with
     * inbox deduplication (ADR 0011, PERS-07).
     *
     * <p>Declaring this asserts the property the whole mechanism exists for: an event and the
     * transition beside it commit together or neither does. An adapter that writes the event outside
     * the transition, or after it, must not declare it — that adapter is at-most-once on the event
     * and would silently reintroduce the loss window this capability says it closed.</p>
     */
    EVENT_JOURNAL,

    /**
     * Named handlers can be registered, correlated and transitioned inside the same batch as the
     * aggregate transitions beside them, and a terminal handler produces a claimable
     * {@link PendingWork.HandlerTrigger} (PERS-05).
     *
     * <p>Separate from {@link #DURABLE} because the two are independent claims and both are needed
     * for a human task that survives a full shutdown: this capability says the handler mechanism
     * exists and is transactional with the transition beside it, and {@code DURABLE} says the rows
     * survive process death. An in-memory adapter can honour this one honestly — the conformance
     * suite runs every handler assertion against it — and must still not claim {@code DURABLE}.</p>
     *
     * <p>Declaring it asserts the property the whole mechanism exists for: a registration and the
     * {@code WAITING} transition beside it commit together or neither does, and a resolution and the
     * re-entry traversal it authorizes commit together or neither does. An adapter that wrote the
     * handler outside the transaction would leave a process waiting on a handler that does not
     * exist, or resumed by a traversal nothing authorized.</p>
     */
    DURABLE_HANDLERS,

    /**
     * The journal can be compacted on demand, discarding the payloads of records that are both
     * delivered to every destination and past their retention window.
     *
     * <p>Separate from {@link #EVENT_JOURNAL} because an adapter can honestly offer a journal it
     * never prunes — correct, and eventually a disk incident — while an adapter that prunes must
     * additionally honour the replay floor. Splitting them keeps the second obligation from riding in
     * unannounced on the first.</p>
     */
    JOURNAL_COMPACTION
}
