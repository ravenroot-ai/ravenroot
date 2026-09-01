package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bridges the artifact/version lifecycle audit event into the SEC-13 durable trail as
 * {@link AuditCategory#VERSION}, replacing {@link StructuredArtifactLifecycleLogger}'s stdout line for
 * production use. Carries the same payload-free discipline the source event already established:
 * {@code sha256} and {@code state} are recorded as free text, never a request body or source.
 */
public final class AuditTrailArtifactLifecycleSink implements ArtifactLifecycleAuditSink {
    private final AuditTrail auditTrail;

    public AuditTrailArtifactLifecycleSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(ArtifactLifecycleAuditEvent event) {
        Objects.requireNonNull(event, "event");
        // Revision and evidence digest travel with the record, so the chained trail
        // commits to WHICH revision reached this state and to everything the artifact claimed about
        // itself there -- approver, reason, test result -- not merely to its source hash, which is
        // identical across every revision. Still payload-free: a digest, never the source.
        String detail = "sha256=" + event.sha256() + ";state=" + event.state()
                + ";revision=" + event.revision() + ";evidence=" + event.evidenceDigest();
        auditTrail.append(AuditEnvelope.of(event.tenantId(), event.subject(), AuditCategory.VERSION,
                event.action(), "artifact", event.artifactId(), outcomeFor(event.disposition()),
                event.disposition().name(), event.requestId(), event.occurredAt(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "text/plain")));
    }

    /**
     * An attempt is not an outcome, and mapping it to {@link AuditOutcome#ALLOWED} said it was.
     *
     * <p>{@code ATTEMPT} is recorded before the mutation runs. Reading it back as {@code ALLOWED}
     * asserted that the operation was permitted <em>and took effect</em>, so an artifact approval that
     * failed afterwards left a trail claiming it had succeeded — the exact reverse of what an audit
     * record is for. {@link AuditOutcome#ATTEMPTED} carries the distinction the three-value vocabulary
     * could not, and the correlation identifier ties the attempt to the terminal record that follows.
     */
    private static AuditOutcome outcomeFor(ArtifactLifecycleAuditEvent.Disposition disposition) {
        return switch (disposition) {
            case ATTEMPT -> AuditOutcome.ATTEMPTED;
            case DENIED -> AuditOutcome.DENIED;
            case FAILED -> AuditOutcome.FAILED;
            // The only disposition that may map to ALLOWED, because it is the only one
            // asserting the operation was permitted AND took effect -- which is what ALLOWED claims.
            case SUCCEEDED -> AuditOutcome.ALLOWED;
        };
    }
}
