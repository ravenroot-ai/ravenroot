package ai.ravenroot.api.application;

/** Transport-neutral runtime transitions exposed to CLI, HTTP and UI adapters. */
public enum ExecutionEventType {
    /** An execution was created and began processing. */
    EXECUTION_STARTED,
    /** A node behavior began execution. */
    NODE_STARTED,
    /** The framework traversed a node without constructing or invoking its behavior. */
    NODE_BYPASSED,
    /** A node used its configured default path. */
    NODE_DEFAULTED,
    /** A node behavior returned a successful result. */
    NODE_COMPLETED,
    /** The runtime dispatched one unambiguous authored edge to its successor. */
    EDGE_TRAVERSED,
    /** A node behavior returned or raised a failure. */
    NODE_FAILED,

    /**
     * A fan-in join met its quorum and fired (CORE-03).
     *
     * <p>Published <b>once per iteration</b> of that join, not once per traversal — the wording this
     * said previously, when a join could only fire once and a graph whose cycle ran three times
     * therefore reported one firing and silently dropped two.</p>
     */
    JOIN_SATISFIED,

    /**
     * A fan-in join is retaining more iterations of state than a runtime threshold expects.
     *
     * <p>Diagnostic, and deliberately nothing more: not a ceiling, not a refusal, not a failure. A
     * join that re-arms keeps one bucket of arrivals per lap and never drops the buckets that have
     * already fired, so a traversal whose cycle goes round many times grows that state without any
     * single arrival looking wrong. This event is what makes the growth observable. No runtime bound
     * is currently specified.</p>
     *
     * <p><b>It counts iterations retained, not iterations waiting.</b> A join cannot begin an
     * iteration before firing the previous one, so the number of iterations *waiting* on a join is
     * always zero or one and a threshold over it could never be crossed — an alarm that never fires is
     * worse than no alarm. The number here is the count of laps whose arrivals are still held, which
     * is the quantity that actually grows.</p>
     *
     * <p>The threshold is an internal default rather than a contract, so its value may change between
     * versions without migrating anything, and nothing durable is keyed by it. The event is emitted
     * once per crossing of the threshold, not once per arrival above it.</p>
     */
    JOIN_ITERATION_BACKLOG,

    /**
     * A branch result reached a fan-in join and changed nothing — a duplicate of a branch that
     * already counted, or an arrival at a join that had already settled.
     *
     * <p>Emitted rather than dropped silently. Both causes are normal on a healthy system, so
     * neither is a failure; but a graph whose duplicate rate is climbing is a graph whose upstream
     * is being redelivered, and without an event nothing distinguishes that from nothing happening
     * (CORE-03).</p>
     */
    JOIN_ARRIVAL_DISCARDED,

    /** A fan-in join timed out or lost too many branches to reach its quorum (CORE-03). */
    JOIN_FAILED,

    /** The execution reached its normal terminal result. */
    EXECUTION_COMPLETED,
    /** The execution reached a failed terminal result. */
    EXECUTION_FAILED
}
