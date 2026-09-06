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
 *
 * <h2>Relationship to the registry's two lifecycle vocabularies (ADR 0038 D5)</h2>
 * <p>Three enums describe a deployment's lifecycle and they are deliberately not the same enum:
 * {@code DeploymentRegistry.DesiredKind} is <em>intent</em>, {@code DeploymentRegistry.ObservedKind}
 * is <em>evidence</em> reported by whoever holds the lease, and this one is the <em>process-local
 * observable state</em> of an activation this JVM owns. Intent and evidence are recorded by an
 * authority that may be another process entirely; this enum is answered from memory, without a store
 * read, which is what makes it safe to poll from a readiness probe.</p>
 *
 * <table border="1">
 *   <caption>How the three vocabularies line up</caption>
 *   <tr><th>DesiredKind (intent)</th><th>ObservedKind (evidence)</th><th>DeploymentState (local)</th></tr>
 *   <tr><td>RUNNING</td><td>STARTING, READY, DEGRADED</td><td>{@link #STARTING}, {@link #READY}, {@link #DEGRADED}</td></tr>
 *   <tr><td>PAUSED</td><td>PAUSED</td><td>no member</td></tr>
 *   <tr><td>DRAINED</td><td>DRAINING, DRAINED</td><td>folded into {@link #STOPPING}</td></tr>
 *   <tr><td>STOPPED</td><td>STOPPING, STOPPED</td><td>{@link #STOPPING}, {@link #STOPPED}</td></tr>
 *   <tr><td>REMOVED</td><td>&mdash; (the aggregate is tombstoned)</td><td>no member</td></tr>
 *   <tr><td>&mdash; (no intent yet)</td><td>COLD</td><td>{@link #COLD}</td></tr>
 *   <tr><td>&mdash;</td><td>FAILED</td><td>{@link #FAILED}</td></tr>
 * </table>
 *
 * <p><b>The three gaps are stated rather than closed.</b> This enum has no {@code PAUSED} and no
 * {@code DRAINING}, and it will not grow them as a side effect of the registry growing its own. Every
 * member here is mapped onto {@code LocalDeploymentState} and published over HTTP and the CLI, so a
 * new member is a change to an externally published value space, which belongs to the change that
 * owns that surface and can carry its migration and release notes. Until then a paused deployment is
 * reported by the registry's evidence, which is where an operator asking about a remote deployment is
 * already looking, and a draining one continues to report {@link #STOPPING} exactly as it does
 * today.</p>
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
