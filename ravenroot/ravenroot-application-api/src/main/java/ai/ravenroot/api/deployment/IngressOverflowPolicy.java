package ai.ravenroot.api.deployment;

/**
 * What a deployment does when its bounded ingress buffer is full.
 *
 * <h2>One constant, and the absence of the others is the honest part</h2>
 * <p>Bounded buffering forces a choice at the bound, and the ADR requires the choice to be
 * <em>declared</em>. Phase A declares exactly one: {@link #REJECT}. The alternatives an inbound
 * adapter might expect — drop-oldest, drop-newest, block-the-producer — are not offered, and that is
 * a statement about what can be honoured rather than a gap in the enum.
 *
 * <p>Dropping requires knowing that the dropped event will be redelivered, which is the
 * at-least-once guarantee ADR 0021 D6 places behind durable traversal acceptance in Phase B. Until
 * that exists, a deployment that dropped an event would be discarding data with no one able to
 * resend it. Rejecting hands the decision back to the source, which still holds the event and is the
 * only party that can honestly decide.
 */
public enum IngressOverflowPolicy {
    /**
     * Refuse the event with {@link IngressDisposition#REJECTED_BUFFER_FULL}, leaving custody with
     * the source. The only policy Phase A can honour without the durability D6 defines.
     */
    REJECT
}
