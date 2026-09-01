package ai.ravenroot.api.deployment;

/**
 * The observable lifecycle state of a long-lived graph deployment (ADR 0021 D2).
 *
 * <h2>The state is the signal; the cause is not</h2>
 * <p>A sanitized human-readable cause travels <em>alongside</em> this enum on
 * {@link DeploymentStatus}, never inside it. Probes, alerts and dashboards key on the constant and
 * must never parse the cause: the cause is written for a person reading an incident, so it is free
 * to change wording, gain detail or be redacted, and anything matching on its text would break
 * silently when it does. This split is the agreed contract with the operational surface
 * ({@code DeploymentCapConfiguration}) and is not a stylistic preference.
 *
 * <h2>Transitions</h2>
 * <p>{@code COLD} is the state of a deployment that exists but has never been asked to run —
 * distinct from {@code STOPPED}, which has run and been shut down, because an operator seeing
 * "never started" and "started and stopped" is looking at two different situations.
 * {@code STARTING} completes only at {@code READY}: readiness gates start completion, so a caller
 * that observes a completed start has a deployment that is actually serving. {@code DEGRADED} is
 * serving with reduced capability rather than not serving. {@code FAILED} is terminal for that
 * activation attempt and carries a cause; recovery is a new {@code start}.
 */
public enum DeploymentState {
    /** Exists and has never been started. Distinct from {@link #STOPPED}, which has run. */
    COLD,

    /**
     * Start is in progress and has not reached readiness.
     *
     * <p>A start that fails part-way rolls back atomically rather than leaving a half-built
     * deployment in this state: the deployment returns to {@link #COLD} or moves to {@link #FAILED},
     * never lingers as "starting" with some listeners bound and others not.
     */
    STARTING,

    /** Started, readiness satisfied, admitting work. This is the only state that admits ingress. */
    READY,

    /**
     * Serving with reduced capability — the distinction from {@link #FAILED} is that work is still
     * being accepted and completed, so a readiness probe treats it as serving while an alert treats
     * it as needing attention.
     */
    DEGRADED,

    /** Stop is in progress: admission is already closed, accepted work is draining. */
    STOPPING,

    /** Started at least once and now shut down, with its resources released. */
    STOPPED,

    /**
     * Start or operation failed terminally for this activation. Carries a sanitized cause on
     * {@link DeploymentStatus}. Recovery is a fresh {@code start}, not an implicit retry.
     */
    FAILED
}
