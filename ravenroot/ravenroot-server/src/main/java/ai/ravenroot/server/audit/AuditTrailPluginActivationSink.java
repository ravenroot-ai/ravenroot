package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.plugin.PluginActivationAuditSink;
import ai.ravenroot.api.plugin.PluginActivationEvent;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bridges plugin bundle activation outcomes into the SEC-13 durable trail as
 * {@link AuditCategory#VERSION} (PLAT-12) -- the fourth sink of this exact shape, alongside
 * {@link AuditTrailRateLimitSink}, {@link ai.ravenroot.core.audit.AuditTrailAuthorizationSink} and
 * {@link AuditTrailArtifactLifecycleSink}, not a bespoke integration.
 *
 * <p>Plugin activation happens at startup, before any request or tenant context exists, so this
 * follows {@link AuditTrailRateLimitSink}'s own precedent for the same situation: tenant and subject
 * are the {@code "-"} placeholder, landing every record in a single pseudo-tenant chain, because
 * there is no real tenant to scope a startup decision to and inventing one would misattribute it.</p>
 *
 * <p>Carries {@link PluginActivationEvent#detail()} in full, unredacted -- deliberately richer than
 * whatever a console message shows for the same event, per {@code DESIGN.md}'s "where detail goes":
 * this record is the complete one, not a substitute for the console message and never able to
 * suppress it, since the caller always writes the console message first and attempts this audit call
 * afterward in its own try/catch.</p>
 */
public final class AuditTrailPluginActivationSink implements PluginActivationAuditSink {
    private static final String PSEUDO_TENANT = "-";

    private final AuditTrail auditTrail;

    public AuditTrailPluginActivationSink(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public void record(PluginActivationEvent event) {
        Objects.requireNonNull(event, "event");
        AuditOutcome outcome = event.outcome() == PluginActivationEvent.Outcome.ACTIVATED
                ? AuditOutcome.ALLOWED : AuditOutcome.DENIED;
        String action = event.outcome() == PluginActivationEvent.Outcome.ACTIVATED
                ? "plugin.activate" : "plugin.refuse";
        String pluginId = event.pluginId() == null ? PSEUDO_TENANT : event.pluginId();
        auditTrail.append(AuditEnvelope.of(PSEUDO_TENANT, PSEUDO_TENANT, AuditCategory.VERSION, action,
                "plugin", pluginId, outcome, event.reasonToken(), event.incidentId(), event.occurredAt(),
                OpaquePayload.of(event.detail().getBytes(StandardCharsets.UTF_8), "text/plain")));
    }
}
