package ai.ravenroot.api.deployment;

/**
 * What a deployment did with an offered inbound event.
 *
 * <h2>Every refusal is distinguishable, because the caller's remedy differs</h2>
 * <p>An external source that is told only "no" cannot decide whether to retry, to slow down, or to
 * stop. These constants exist so it can: a full buffer is transient and worth retrying, a deployment
 * that is not ready may become ready, and a closed admission during shutdown will not reopen.
 */
public enum IngressDisposition {
    /**
     * The event was accepted into the deployment's bounded buffer.
     *
     * <p><b>Accepted is not acknowledged.</b> This says the runtime took custody in memory; it says
     * nothing about durability. See {@link TrustedIngress} for what Phase A does and does not claim,
     * and why an external source must not treat this as an ack.
     */
    ACCEPTED,

    /** The bounded buffer is full. Transient: the source should apply backpressure and retry. */
    REJECTED_BUFFER_FULL,

    /**
     * The deployment is not in {@link DeploymentState#READY}. May become ready; may be
     * {@link DeploymentState#FAILED}. The source should consult status rather than spin.
     */
    REJECTED_NOT_READY,

    /**
     * Admission is closed because the deployment is stopping or stopped. Terminal for this
     * deployment: stop closes admission first and does not reopen it.
     */
    REJECTED_ADMISSION_CLOSED,

    /**
     * The process instance this traversal would run under is already leased by another live worker,
     * so this deployment refused rather than executing an instance it does not own.
     *
     * <h4>Unreachable today, and deliberately kept</h4>
     * <p>Do not delete this as dead code. It is not dead by design; it is unreachable by a set of
     * facts that are all expected to change:
     * <ul>
     *   <li>both deployment ingress paths mint a <b>fresh</b> process instance identifier per event,
     *       from the application's own identity source, so nothing can already hold it — exactly the
     *       property that made fail-closed uncontroversial on the direct submission path;</li>
     *   <li>ingress to a <b>named node</b>, which is the topology that would re-enter an existing
     *       instance, throws {@link UnsupportedOperationException} — it is an open ADR 0021
     *       decision, not an implemented path;</li>
     *   <li>a redelivered event is recognised by its idempotency key and answered with a duplicate
     *       receipt, starting no second traversal at all.</li>
     * </ul>
     * Contention therefore requires a UUID collision today. It becomes genuinely reachable the moment
     * ingress-to-a-named-node lands, and <b>whether that case should refuse or queue is that work's
     * product decision, not this one's</b>: deciding it here would mean shipping a queueing path no
     * test could fire.
     */
    REJECTED_INSTANCE_BUSY
}
