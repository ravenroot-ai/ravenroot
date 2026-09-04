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
     * An attempt failed, its failure was classified retryable, and the orchestrator committed a
     * further durable attempt.
     *
     * <h4>It replaces {@link #NODE_FAILED}, it does not accompany it</h4>
     * <p>The precedent is {@link #NODE_BYPASSED}, which replaces {@link #NODE_COMPLETED} rather than
     * qualifying it: both are "a settlement the framework qualifies", and one event per settlement is
     * the rule this runtime already follows. Publishing both would also change what
     * {@code NODE_FAILED} means for every consumer that already counts it — a node whose transient
     * blips are absorbed by two retries and then succeeds would start reporting failures it does not
     * report today, and a failure count that rises because retries were <em>added</em> is a metric
     * that says the opposite of what happened. The last attempt of an exhausted policy publishes
     * {@link #NODE_FAILED} exactly as before.</p>
     *
     * <p>The event names the attempt that <em>failed</em>, not the one that was scheduled: its
     * {@code attemptId} and {@link ExecutionEvent#attemptOrdinal()} are the failing attempt's, and the
     * successor announces itself with its own {@link #NODE_STARTED} carrying the next ordinal. Naming
     * the new attempt here instead would put an attempt's identity on the wire before the durable
     * decision to run it had any observable effect, and a crash in the backoff would leave a
     * started-looking attempt that no {@code NODE_STARTED} ever followed.</p>
     *
     * <p>{@link ExecutionEvent#publicReason()} carries the failure's classification token, and
     * {@link ExecutionEvent#detail()} the next ordinal and the wait — so an operator reading the
     * activity log sees why it is being retried and when, without correlating two events.</p>
     */
    NODE_RETRY_SCHEDULED,

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

    /**
     * An operator's pause took effect: this traversal is holding and will not begin another node
     * until it is resumed.
     *
     * <h4>Published for the transition, never for the repeat</h4>
     * <p>Only the call that actually installed the hold publishes this. A second pause over a
     * traversal that is already holding changes nothing and publishes nothing, which is what keeps
     * this event countable: one {@code EXECUTION_PAUSED} means one traversal stopped dispatching,
     * not one operator pressed a button. The command's own answer still distinguishes the repeat --
     * see {@code PauseResult.Outcome.ALREADY_PAUSED} -- because a caller needs to know its request
     * was redundant even though nobody else needs an event for it.</p>
     *
     * <h4>What it is for, and what was wrong without it</h4>
     * <p>A pause reported its result only to the caller that issued it. To everyone else the
     * execution went quiet: it stayed listed as running, it stopped publishing node events, and
     * those two facts together are also the exact signature of a traversal whose behavior has
     * deadlocked. A second operator, or the same operator reconnecting, could not tell a deliberate
     * hold from a stall, and the two call for opposite responses.</p>
     *
     * <h4>It cannot appear before {@link #EXECUTION_STARTED} or after a terminal event</h4>
     * <p>A traversal becomes pausable when it is admitted, which is before the runtime has the
     * identity every event is published under. A pause accepted in that window is announced once the
     * traversal starts, immediately after {@link #EXECUTION_STARTED}, rather than published with an
     * identity that does not exist yet. At the other end, a traversal that has begun closing refuses
     * a new hold, so no pause can be published between the last dispatch and
     * {@link #EXECUTION_COMPLETED} or {@link #EXECUTION_FAILED}.</p>
     *
     * <p>The event carries no node, no payload and no caller-supplied text: a hold is a fact about
     * the traversal, and there is nothing about it that needs a diagnostic.</p>
     */
    EXECUTION_PAUSED,

    /**
     * A holding traversal was released and is dispatching again from the hop it held at.
     *
     * <h4>It pairs with {@link #EXECUTION_PAUSED}, and only with a published one</h4>
     * <p>Emitted only where a resume removed a hold that this stream has already announced. Three
     * other paths remove the same hold and none of them publishes this: cancelling a paused
     * traversal, the traversal's own completion, and the runner's shutdown. Each of those ends the
     * traversal, so a {@code EXECUTION_RESUMED} on any of them would tell an observer the execution
     * went back to running immediately before it stopped forever -- a transition that never
     * happened. A resume that finds nothing holding publishes nothing and answers
     * {@code ResumeResult.Outcome.NOT_PAUSED} to its caller instead.</p>
     *
     * <p>It says the hold is gone and the parked hop has been handed a thread, not that the hop has
     * run. The next node announces itself with its own {@link #NODE_STARTED}, exactly as it would
     * have without the pause.</p>
     */
    EXECUTION_RESUMED,

    /** The execution reached its normal terminal result. */
    EXECUTION_COMPLETED,
    /** The execution reached a failed terminal result. */
    EXECUTION_FAILED
}
