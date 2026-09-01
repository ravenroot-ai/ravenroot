package ai.ravenroot.api.application;

import java.time.Instant;

/**
 * Non-sensitive control-plane audit record for cancel and drain (API-02). Mirrors
 * {@code ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent}'s ATTEMPT-then-terminal shape rather
 * than inventing a second one: an {@link Disposition#ATTEMPT} is recorded before the mutation runs, so a
 * process killed mid-cancel still leaves evidence the operator asked, and a terminal
 * {@link Disposition#DENIED}, {@link Disposition#FAILED} or {@link Disposition#SUCCEEDED} record closes
 * it. {@code detail} carries the coarse result (e.g. {@code CancelResult.Outcome} or
 * {@code DrainResult.Outcome} name) and nothing document- or payload-derived.
 * @param occurredAt instant at which the event or audit record occurred.
 * @param requestId the stable request id used to identify the requested resource.
 * @param subject authenticated subject that requested the control action.
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param action authorization action represented by the audit record.
 * @param resourceType kind of resource targeted by the control action.
 * @param resourceId the stable resource id used to identify the requested resource.
 * @param disposition whether the requested control action was attempted, denied, or completed.
 * @param detail bounded diagnostic detail associated with the event or audit action.
 */
public record ExecutionControlAuditEvent(Instant occurredAt, String requestId, String subject, String tenantId,
                                         String action, String resourceType, String resourceId,
                                         Disposition disposition, String detail) {
/**
 * Enumerates the supported disposition values returned by this API.
 */
    public enum Disposition {
        /** Recorded before the mutation; the result is not yet known. */
        ATTEMPT,
        /** Refused by policy -- authorization. */
        DENIED,
        /** Attempted and then failed inside the platform, rather than being refused. */
        FAILED,
        /** The command ran to completion (whatever {@link CancelResult}/{@link DrainResult} it produced). */
        SUCCEEDED
    }
}
