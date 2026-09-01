package ai.ravenroot.api.audit;

/**
 * The generic result of an audited action, deliberately coarser than any one producer's own vocabulary.
 *
 * <p>{@code AuthorizationAuditEvent.allowed()}, {@code ArtifactLifecycleAuditEvent.Disposition} and
 * {@code RateLimitAuditEvent}'s HTTP status all collapse onto this without losing the distinction that
 * matters for audit: did the actor's request go through, was it refused by policy, or did the platform
 * itself fail while handling it. The port stores the producer's own reason as free text on
 * {@link AuditEnvelope#reason()}, so nothing above this coarse split is actually lost.</p>
 */
public enum AuditOutcome {
    /** The action was permitted and, where applicable, took effect. */
    ALLOWED,

    /** The action was refused by policy (authorization denial, rate limit, dual-control gate). */
    DENIED,

    /** The platform failed while attempting the action, independent of any policy decision. */
    FAILED,

    /**
     * The action was recorded <em>before</em> its result was known, and a terminal record for the same
     * {@link AuditEnvelope#correlationId()} follows.
     *
     * <p>This exists because the alternative was worse than an extra constant. The artifact lifecycle
     * records an intent before performing the mutation — which is the right order, since an audit
     * record written only on success cannot evidence an attempt that crashed the process — and the
     * first implementation mapped that intent to {@link #ALLOWED}. An operation that failed after its
     * attempt record therefore read back, permanently, as having been permitted and taken effect.
     *
     * <p>{@link #ALLOWED}, {@link #DENIED} and {@link #FAILED} are all claims about a result. An
     * attempt has none yet, and every way of squeezing it into one of the three asserts something
     * untrue: {@code ALLOWED} claims effect, {@code DENIED} claims a policy refusal that did not
     * happen, {@code FAILED} claims a failure not yet observed. A reader reconciling an audit trail
     * needs to be able to tell "we do not yet know" from "we know it succeeded".
     */
    ATTEMPTED
}
