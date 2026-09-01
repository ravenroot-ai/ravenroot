package ai.ravenroot.api.deployment;

/**
 * Activation was refused because admitting the deployment would exceed the pod's cap (ADR
 * 0021 D7).
 *
 * <h2>Fail-closed, and a type rather than a message</h2>
 * <p>The refusal is the contract: a pod at its cap rejects a new activation rather than accepting it
 * and hoping shutdown still fits inside {@code terminationGracePeriodSeconds}. The exception is a
 * distinct type so alerting keys on it rather than on message text — the same split
 * {@link DeploymentStatus} makes between state and cause, for the same reason.
 *
 * <p>The cap itself is an operator setting owned by the server's configuration surface
 * ({@code DeploymentCapConfiguration}, {@code RAVENROOT_MAX_ACTIVE_DEPLOYMENTS}). Core receives a
 * plain integer and never reads the environment: where platform configuration lives remains
 * deliberately unspecified, and this contract must not answer it in passing.
 */
public final class DeploymentAdmissionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

/**
 * Number of deployments active on this pod when admission was refused.
 */
    private final int active;
/**
 * Operator-configured per-pod deployment limit that triggered refusal.
 */
    private final int cap;

/**
 * Creates a typed refusal with the observed local capacity evidence.
 * @param id deployment that could not be activated.
 * @param active deployments active on this pod at refusal time.
 * @param cap configured maximum active deployments on this pod.
 */
    public DeploymentAdmissionException(DeploymentId id, int active, int cap) {
        super("Refusing to activate deployment " + id + ": " + active + " of " + cap
                + " active deployments on this pod. The cap bounds the in-flight work a graceful "
                + "shutdown must drain within its termination grace period.");
        this.active = active;
        this.cap = cap;
    }

/**
 * Active deployments at the moment of refusal.
 * @return active deployment count observed at refusal time.
 */
    public int active() {
        return active;
    }

/**
 * The configured per-pod cap.
 * @return configured per-pod admission ceiling.
 */
    public int cap() {
        return cap;
    }
}
