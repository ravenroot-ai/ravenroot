package ai.ravenroot.api.programming;

import java.time.Instant;

/**
 * Non-sensitive control-plane audit record; source, payload, prompt and credentials are excluded.
 * @param occurredAt time at which the lifecycle event was recorded.
 * @param requestId request correlation identifier for the control-plane action.
 * @param subject authenticated actor that requested the action.
 * @param tenantId tenant in whose artifact registry the action ran.
 * @param action closed lifecycle action name.
 * @param artifactId artifact affected by the action.
 * @param sha256 source digest of the artifact.
 * @param state lifecycle state recorded by the event.
 * @param disposition whether the action was attempted, denied, failed, or succeeded.
 * @param revision artifact lifecycle revision at the event.
 * @param evidenceDigest commitment to the complete artifact evidence at that revision.
 */
public record ArtifactLifecycleAuditEvent(Instant occurredAt, String requestId, String subject,
                                          String tenantId, String action, String artifactId,
                                          String sha256, ArtifactState state, Disposition disposition,
                                          long revision, String evidenceDigest) {
    /**
     * {@code revision} and {@code evidenceDigest} are what make this record a BINDING
     * rather than a label.
     *
     * <p>{@code sha256} cannot identify a revision: a transition copies it verbatim, so every
     * revision of an artifact shares one hash. And nothing here previously covered the artifact's
     * {@code evidence.*} metadata at all, so who approved it and why were recorded on the trail only
     * as the requesting {@code subject} — leaving the artifact's own claim about its approver bound
     * to nothing.
     *
     * <p>{@code evidenceDigest} is {@link ArtifactEvidence#hex()} over the whole artifact: identity,
     * source hash, revision, state, timestamps and every metadata entry. See that class for what the
     * unkeyed digest does and does not prove, and for what "immutably bound" means while the registry
     * is still volatile.
     *
     * <p>Both are {@code 0} / {@code ""} on records written before an artifact exists — the
     * {@code ATTEMPT} for a creation, where there is nothing yet to commit to.
     */
    /**
     * What the record says about the operation.
     *
     * <p>{@link #ATTEMPT} is written before the mutation runs, so that an operation which kills the
     * process still leaves evidence that it was tried. {@link #FAILED} closes that record: without it
     * an attempt whose mutation then threw stayed on the trail forever as the last word, and the audit
     * sink mapped it to {@code AuditOutcome.ALLOWED} — a permanent claim that a failed operation was
     * permitted and took effect.
     */
    public enum Disposition {
        /** Recorded before the mutation; the result is not yet known. */
        ATTEMPT,
        /** Refused by policy — authorization or a dual-control gate. */
        DENIED,
        /** Attempted and then failed inside the platform, rather than being refused. */
        FAILED,
        /**
         * The mutation ran to completion. SEC-12.
         *
         * <p>Without this value the trail had no positive record that anything succeeded: an
         * {@code ATTEMPT} closed by neither {@code DENIED} nor {@code FAILED} was the only evidence
         * that an approval or an activation had taken effect. That is an inference from
         * <em>absence</em>, and absence is exactly what a process killed mid-activation also
         * produces — so "approved by this approver" and "crashed while approving" were the same
         * record. SEC-12 exists to bind approver and activation immutably to an artifact, which
         * cannot rest on a record that was never written.
         *
         * <p>It carries the state the artifact reached, so the terminal record states the outcome
         * rather than restating the intent.
         */
        SUCCEEDED
    }
}
