package ai.ravenroot.api.application;

/**
 * Operator-facing lifecycle of one process-local graph deployment.
 *
 * <h2>What this vocabulary claims, and what it deliberately does not</h2>
 * <p>Every value here describes <b>this server process only</b>. Nothing in it implies durability,
 * desired/observed reconciliation, leases, fencing, failover or cluster-wide ownership; a status
 * carrying {@link LocalDeploymentStatus#SCOPE} says so on the wire so a reader is never left to infer
 * a guarantee from a state name. A durable and cluster-wide lifecycle is expected to migrate this
 * contract onto the durable registry rather than to add a second
 * incompatible local surface beside it.</p>
 *
 * <h2>Why this is not {@code ai.ravenroot.api.deployment.DeploymentState}</h2>
 * <p>That enum is the engine's own, and it carries {@code COLD} — a word that means "this object was
 * constructed and never started", which is an engine-internal fact rather than an operator-facing
 * one. This vocabulary renames that condition {@link #REGISTERED}, because at this boundary what the
 * caller did was register a graph version, and it drops nothing else. Keeping the two separate is
 * what lets the engine's states evolve without every HTTP, CLI and UI consumer becoming a downstream
 * of an internal enum.</p>
 */
public enum LocalDeploymentState {
    /**
     * The graph version is registered under this tenant's deployment id and has never been started.
     * Registration reserves the identity and validates the graph; it starts nothing.
     */
    REGISTERED,
    /** Domain, nodes and inbound sources are being constructed; readiness is not yet claimed. */
    STARTING,
    /** Serving in this process: every source this graph names is bound and admission is open. */
    READY,
    /** Serving, but at least one inbound source reported reduced health. */
    DEGRADED,
    /** Admission is closed and this deployment's own resources are being released. */
    STOPPING,
    /**
     * Not running in this process. Reached from a completed stop, and also from a start that was
     * never issued after the deployment had already been stopped once. The registration survives, so
     * the deployment can be started again under the same id.
     */
    STOPPED,
    /**
     * A start failed and rolled back atomically: no half-bound listeners and no orphaned actors were
     * left behind. The registration survives and a later start may succeed.
     */
    FAILED
}
