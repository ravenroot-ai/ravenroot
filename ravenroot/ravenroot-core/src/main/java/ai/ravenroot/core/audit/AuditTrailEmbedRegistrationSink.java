package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.embed.EmbedRegistrationAuditSink;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.util.Locale;
import java.util.Objects;

/**
 * Durable, redacted operator-administration audit for embed registrations.
 *
 * <p>Propagates an append failure rather than swallowing it, which is what makes
 * {@link ai.ravenroot.api.embed.AuthorizedEmbedRegistrationAdministration}'s fail-closed contract
 * real: a provision whose attempt could not be recorded does not proceed.</p>
 *
 * <p>It lives in {@code ravenroot-core} rather than beside the server's other audit adapters because
 * the operator surface that writes registrations is the CLI, which does not depend on
 * {@code ravenroot-server}. A second copy in the CLI module would be a second redaction policy, and
 * the one that eventually diverges is always the one nobody is looking at.</p>
 *
 * <p>The resource id is the registration id and the detail is the outcome token. Nothing else from
 * the command reaches the trail — no parent origin, no graph or version identifier, no digest, no
 * node, no capability set. The payload is deliberately empty rather than a serialized command: an
 * audit record that carries the projection is a second copy of the graph in a store with different
 * retention rules.</p>
 */
public final class AuditTrailEmbedRegistrationSink implements EmbedRegistrationAuditSink {

    private final AuditTrail trail;

    public AuditTrailEmbedRegistrationSink(AuditTrail trail) {
        this.trail = Objects.requireNonNull(trail, "trail");
    }

    @Override
    public void record(Event event) {
        trail.append(AuditEnvelope.of(event.tenantId(), event.principal(), AuditCategory.ADMINISTRATION,
                "embed-registration:" + event.phase().name().toLowerCase(Locale.ROOT),
                "embed-registration", event.registrationId(), switch (event.outcome()) {
                    case ALLOWED -> AuditOutcome.ALLOWED;
                    // A conflict is a refusal: the operator named a revision that was not current and
                    // nothing was written.
                    case DENIED, CONFLICT -> AuditOutcome.DENIED;
                    // The port has its own ATTEMPTED member, for exactly this shape: a record written
                    // before the result is known. Collapsing it onto any of the other three would
                    // assert something untrue -- see AuditOutcome.ATTEMPTED's own Javadoc, which
                    // exists because a previous producer mapped an intent onto ALLOWED and every
                    // operation that then crashed read back as having taken effect.
                    case ATTEMPTED -> AuditOutcome.ATTEMPTED;
                    case FAILED -> AuditOutcome.FAILED;
                },
                // Revision travels in the detail so a reader can reconstruct the compare-and-set
                // sequence from the trail alone; it is a counter, not a secret.
                event.outcome().name() + ":" + event.detail() + ":r" + event.revision(),
                event.requestId(), event.occurredAt(), OpaquePayload.empty("text/plain")));
    }
}
