package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.publication.PublicationAuditEvent;
import ai.ravenroot.api.publication.PublicationAuditSink;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** Persists payload-free publication guard decisions in the deployment audit chain. */
public final class AuditTrailPublicationSink implements PublicationAuditSink {
    private static final String PSEUDO_TENANT = "-";
    private final AuditTrail auditTrail;

    public AuditTrailPublicationSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(PublicationAuditEvent event) {
        Objects.requireNonNull(event, "event");
        var decision = event.decision();
        String detail = "process=" + event.processInstanceId()
                + ";traversal=" + event.traversalId()
                + ";invocation=" + event.invocationId()
                + ";attempt=" + event.attemptId()
                + ";policyVersion=" + decision.policy().version()
                + ";policyDigest=" + decision.policy().digest()
                + ";rule=" + decision.ruleId().value()
                + ";candidateBytes=" + decision.candidateBytes()
                + ";resourceCount=" + decision.resourceCount();
        AuditOutcome outcome = decision.disposition() == ai.ravenroot.api.publication.PublicationDecision.Disposition.CONTINUE
                ? AuditOutcome.ALLOWED : AuditOutcome.DENIED;
        auditTrail.append(AuditEnvelope.of(PSEUDO_TENANT, PSEUDO_TENANT, AuditCategory.DECISION,
                "publication.evaluate", "publication-policy", decision.policy().id(), outcome,
                decision.reason().name(), event.invocationId().toString(), Instant.now(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "text/plain")));
    }
}
