package ai.ravenroot.api.execution;

/**
 * Optional native facilities an execution-engine adapter may advertise.
 *
 * <p>A capability describes something an engine can do <em>in addition</em> to the mandatory
 * Ravenroot semantics. Stop, cancel, drain and resume-on-failure are never capabilities: making them
 * optional would make the conformance suite conditional and leave applications unable to rely on any
 * behaviour at all without first interrogating the engine.</p>
 */
public enum EngineCapability {
    /** Engine nodes may be placed across cluster members. */
    CLUSTERING,
    /** Engine state can survive a process restart. */
    PERSISTENCE,
    /** Work may be partitioned by a stable shard key. */
    SHARDING,
    /** Timers are recovered rather than lost when the runtime restarts. */
    DURABLE_TIMERS,
    /** Scheduled callbacks can run on a distributed scheduler. */
    DISTRIBUTED_SCHEDULER,
    /** Message ordering may be selected by a priority-aware mailbox. */
    PRIORITY_MAILBOX,
    /** Adapter-native execution telemetry is available. */
    TELEMETRY,
    /**
     * The engine can abort a node computation already in flight, not merely release its caller.
     *
     * <p>Neither supported adapter declares this, and that is a statement about the JVM rather than
     * about either adapter: user code cannot be aborted safely from the outside. It is declared here
     * so the weaker, cooperative guarantee is something an application reads from the engine instead
     * of inferring from silence.</p>
     */
    PREEMPTIVE_CANCELLATION
}
