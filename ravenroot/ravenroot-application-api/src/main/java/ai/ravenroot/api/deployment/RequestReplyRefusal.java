package ai.ravenroot.api.deployment;

/** An expected refusal that starts no traversal and allocates no externally visible exchange. */
public enum RequestReplyRefusal {
/**
 * Deployment has not reached readiness.
 */
    NOT_READY,
/**
 * Deployment stopped admitting new request/reply traversals.
 */
    ADMISSION_CLOSED,
/**
 * Configured live-waiter capacity is exhausted.
 */
    CAPACITY_EXHAUSTED,
/**
 * Caller supplied a deadline outside the deployment policy.
 */
    INVALID_DEADLINE,
/**
 * Payload projection failed or exceeded its configured bounds.
 */
    PAYLOAD_REJECTED,
/**
 * Selected graph target is not admitted by this deployment.
 */
    UNSUPPORTED_TARGET,
/**
 * Runtime-issued request identity collided before traversal dispatch.
 */
    IDENTITY_COLLISION,
/**
 * Deployment runtime cannot currently host request/reply execution.
 */
    RUNTIME_UNAVAILABLE,
/**
 * Implementation does not provide this optional request/reply capability.
 */
    UNSUPPORTED
}
