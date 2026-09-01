package ai.ravenroot.api.audit;

import ai.ravenroot.api.persistence.OpaquePayload;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A caller-authored, tenant-scoped security audit fact, before the trail assigns it a position in the
 * per-tenant chain (SEC-13).
 *
 * <p>This is the audit analogue of {@code EventEnvelope} (ADR 0011, PERS-07): the caller states what
 * happened, and {@link AuditTrail#append(AuditEnvelope)} assigns where it lands. It deliberately does
 * <strong>not</strong> carry a caller-computed digest the way {@code EventEnvelope} does. An event's
 * digest exists so a redelivered event can be recognised as the same event by content alone; an audit
 * record's whole purpose is its position in a tenant's chain, so there is nothing to preserve by
 * computing a digest before that position exists. {@link AuditTrail} computes the one digest that
 * matters — over this content <em>and</em> the chain state — at the point it commits the record.</p>
 *
 * <p>{@code tenantId} and {@code principal} must come from SEC-07's {@code SecurityContext} (or the
 * ingress {@code RequestContext} it projects), never invented downstream. {@code detail} follows the
 * same payload-free discipline {@code AuthorizationAuditEvent} and {@code ArtifactLifecycleAuditEvent}
 * already established: it is opaque and must not carry request bodies, prompts or credentials.</p>
 *
 * @param envelopeVersion schema version of this envelope shape
 * @param auditId         globally unique identity of this audit fact, minted once by the producer
 * @param tenantId        the tenant this fact belongs to and whose chain it joins
 * @param principal       the audit-stable identity that acted, e.g. {@code SecurityContext.qualifiedIdentity()}
 *                        where available, or the bare subject where the producer does not yet carry more
 * @param category        one of the seven SEC-13 categories
 * @param action          opaque domain action name (e.g. {@code "authorize:EXECUTION_START"},
 *                        {@code "artifact.activate"}). A {@code String}, not an enum, for the same reason
 *                        {@code EventEnvelope.eventType} is: the port must not enumerate domain vocabulary
 * @param resourceType    type of the resource acted on, empty when not applicable
 * @param resourceId      identity of the resource acted on, empty when not applicable
 * @param outcome         the coarse result
 * @param reason          producer-supplied free-text reason, empty when the outcome is self-explanatory
 * @param correlationId   end-to-end request correlation, shared with the journal and every other audit
 *                        stream in the codebase
 * @param occurredAt      when the fact happened, on the producer's clock
 * @param detail          opaque, non-sensitive extra detail; {@link OpaquePayload#empty(String)} when none
 */
public record AuditEnvelope(int envelopeVersion, UUID auditId, String tenantId, String principal,
                            AuditCategory category, String action, String resourceType, String resourceId,
                            AuditOutcome outcome, String reason, String correlationId, Instant occurredAt,
                            OpaquePayload detail) {

    public static final int CURRENT_VERSION = 1;

/**
 * Validates the versioned audit-event fields before an event can be appended.
 */
    public AuditEnvelope {
        if (envelopeVersion < 1) {
            throw new IllegalArgumentException("envelopeVersion must be positive");
        }
        Objects.requireNonNull(auditId, "auditId");
        tenantId = requireText(tenantId, "tenantId");
        principal = requireText(principal, "principal");
        Objects.requireNonNull(category, "category");
        action = requireText(action, "action");
        resourceType = resourceType == null ? "" : resourceType;
        resourceId = resourceId == null ? "" : resourceId;
        Objects.requireNonNull(outcome, "outcome");
        reason = reason == null ? "" : reason;
        correlationId = requireText(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(detail, "detail");
    }

/**
 * Builds an envelope at {@link #CURRENT_VERSION} with a fresh {@code auditId}.
 * @param tenantId tenant that owns the audited action
 * @param principal authenticated actor recorded as the action's subject
 * @param category high-level event category
 * @param action stable action token within that category
 * @param resourceType resource-kind token
 * @param resourceId resource identifier, when an event is resource-specific
 * @param outcome permitted, denied, or failed outcome
 * @param reason safe-to-disclose outcome explanation
 * @param correlationId optional request or execution correlation identifier
 * @param occurredAt event time supplied by the producer
 * @param detail optional immutable structured detail
 * @return a current-version envelope with a newly generated audit identifier
 */
    public static AuditEnvelope of(String tenantId, String principal, AuditCategory category, String action,
                                   String resourceType, String resourceId, AuditOutcome outcome, String reason,
                                   String correlationId, Instant occurredAt, OpaquePayload detail) {
        return new AuditEnvelope(CURRENT_VERSION, UUID.randomUUID(), tenantId, principal, category, action,
                resourceType, resourceId, outcome, reason, correlationId, occurredAt, detail);
    }

/**
 * Convenience overload for the common case of no extra detail.
 * @param tenantId tenant that owns the audited action
 * @param principal authenticated actor recorded as the action's subject
 * @param category high-level event category
 * @param action stable action token within that category
 * @param resourceType resource-kind token
 * @param resourceId resource identifier, when an event is resource-specific
 * @param outcome permitted, denied, or failed outcome
 * @param reason safe-to-disclose outcome explanation
 * @param correlationId optional request or execution correlation identifier
 * @param occurredAt event time supplied by the producer
 * @return a current-version envelope with no extra detail
 */
    public static AuditEnvelope of(String tenantId, String principal, AuditCategory category, String action,
                                   String resourceType, String resourceId, AuditOutcome outcome, String reason,
                                   String correlationId, Instant occurredAt) {
        return of(tenantId, principal, category, action, resourceType, resourceId, outcome, reason,
                correlationId, occurredAt, OpaquePayload.empty("text/plain"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
