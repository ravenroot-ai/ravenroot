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

    /** Exact tool approvals can be registered and transitioned atomically with execution state. */
    TOOL_APPROVALS,

    /** Process-rooted agent grants and reservations share the execution batch transaction. */
    AGENT_AUTHORITY_BUDGETS,

    /** First-class human tasks can be registered, transitioned and listed atomically. */
    HUMAN_TASKS,

    /**
     * Operator holds on a traversal can be committed, read back and settled atomically with
     * execution state.
     *
     * <p>Declaring it asserts what makes a hold survive a restart: the hold, its paired handler and
     * the {@code WAITING} transitions beside them commit together or none of them does. An adapter
     * that wrote the hold outside the transaction would produce the two states this capability
     * exists to rule out — a traversal recorded as waiting that nothing is holding, so nothing can
     * ever release it, and a hold over a traversal still recorded as running, which a recovery sweep
     * would treat as ordinary interrupted work.</p>
     *
     * <p>Separate from {@link #DURABLE_HANDLERS} even though a hold always registers one: a handler
     * carries no continuation by contract, so an adapter can support handlers in full and still have
     * nowhere to put the bounded state a held traversal needs in order to be continued. A caller
     * must be able to ask about the second without inferring it from the first.</p>
     */
    EXECUTION_PAUSES,

    /**
     * The journal can be compacted on demand, discarding the payloads of records that are both
     * delivered to every destination and past their retention window.
     *
     * <p>Separate from {@link #EVENT_JOURNAL} because an adapter can honestly offer a journal it
     * never prunes — correct, and eventually a disk incident — while an adapter that prunes must
     * additionally honour the replay floor. Splitting them keeps the second obligation from riding in
     * unannounced on the first.</p>
     */
    JOURNAL_COMPACTION,

    /**
     * A durable, tenant-scoped inventory of process instances and their traversals can be listed with
     * deterministic pagination and looked up directly, without any in-memory registry or event-stream
     * completeness.
     *
     * <p>Declaring this asserts three things together, because a caller that gets two of them is worse
     * off than one that gets none. First, the inventory is served from the same authoritative rows the
     * lifecycle transitions write, so there is no projection that can lag, no offset to repair and no
     * rebuild path that could invent successful work. Second, pagination is deterministic while new
     * work is accepted: the sort key is immutable per row, so a row never moves between pages of an
     * in-flight scan. Third, a key belonging to another tenant is indistinguishable from a missing
     * one, so the inventory is not an existence oracle.</p>
     *
     * <p>Separate from {@link #DURABLE} because the two are independent claims: a non-durable adapter
     * can serve a perfectly honest inventory of the state it currently holds, and it should, so that
     * the conformance assertions for ordering, filtering and tenant isolation run against something
     * rather than being skipped into invisibility.</p>
     */
    PROCESS_INVENTORY,

    /**
     * Terminal inventory rows are retained for a declared window and then removable on demand, with a
     * per-tenant floor that says how far back the answer is still complete.
     *
     * <p>Separate from {@link #PROCESS_INVENTORY} for the reason {@link #JOURNAL_COMPACTION} is
     * separate from {@link #EVENT_JOURNAL}: an adapter can honestly offer an inventory it never
     * prunes — correct, and eventually a disk incident — while an adapter that prunes takes on the
     * further obligation to publish the floor. Without the floor, a caller that fails to find an
     * instance cannot tell "never existed" from "expired by policy", and those two demand opposite
     * actions. Splitting the capabilities keeps the second obligation from riding in unannounced on
     * the first.</p>
     */
    INVENTORY_RETENTION
}
