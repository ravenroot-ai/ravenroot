package ai.ravenroot.api.audit;

import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Gates read, export and redaction access to an {@link AuditTrail} through SEC-03's
 * {@link AuthorizationService}, exactly as {@code AuthorizedRavenrootApplication} already gates the
 * application it wraps — no parallel identity or authorization mechanism.
 *
 * <h2>Reading the trail is itself audited</h2>
 * <p>Every successful {@link #read} and {@link #export} appends its own {@link AuditCategory#ACCESS}
 * record to the very trail it just served, naming who read what range. This is deliberate, not
 * recursive: the append happens once, after the read already returned its result, so there is no loop
 * — it simply means a reader of the trail becomes, truthfully, part of the trail.</p>
 *
 * <h2>Fail-closed, following existing precedent</h2>
 * <p>If the self-referential access record cannot be appended, the call fails rather than silently
 * serving the read anyway, mirroring {@code DefaultAuthorizationService.decide}'s existing behaviour of
 * denying an authorization decision it cannot audit. This is not a new posture invented for this
 * class: it is the same rule already enforced one layer down.</p>
 */
public final class AuthorizedAuditTrail {
    private final AuditTrail delegate;
    private final AuthorizationService authorizationService;
    private final Clock clock;

/**
 * Wraps an audit trail with application authorization for sensitive reads and redactions.
 * @param delegate underlying trail, which never receives untrusted authorization decisions
 * @param authorizationService policy decision point used before each exposed operation
 */
    public AuthorizedAuditTrail(AuditTrail delegate, AuthorizationService authorizationService) {
        this(delegate, authorizationService, Clock.systemUTC());
    }

    AuthorizedAuditTrail(AuditTrail delegate, AuthorizationService authorizationService, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

/**
 * Reads a tenant's audit records after authorizing {@code AUDIT_READ}.
 * @param caller authenticated requester
 * @param tenantId tenant whose records are requested
 * @param afterSequence exclusive record cursor
 * @param limit the limit constraint applied while processing the request.
 * @return authorized page of audit records
 */
    public List<AuditRecord> read(RequestContext caller, String tenantId, long afterSequence, int limit) {
        authorizationService.requireAllowed(caller, AuthorizationAction.AUDIT_READ,
                ProtectedResource.collection("audit-trail", tenantId));
        List<AuditRecord> records = delegate.read(tenantId, afterSequence, limit);
        recordSelfAccess(caller, tenantId, "audit.read", "afterSequence=" + afterSequence + ",limit=" + limit
                + ",returned=" + records.size());
        return records;
    }

/**
 * Exports records after authorizing the stronger {@code AUDIT_EXPORT} action.
 * @param caller authenticated requester
 * @param tenantId tenant whose records are exported
 * @param afterSequence exclusive record cursor
 * @param limit the limit constraint applied while processing the request.
 * @return authorized export page in ascending sequence order
 */
    public List<AuditRecord> export(RequestContext caller, String tenantId, long afterSequence, int limit) {
        authorizationService.requireAllowed(caller, AuthorizationAction.AUDIT_EXPORT,
                ProtectedResource.collection("audit-trail", tenantId));
        // The actual remote transport is deliberately not this class's concern, exactly as
        // OutboxPublisher.EventSink leaves the transport to a later adapter (ADR 0011 section 6): this
        // returns a verifiable page of records, and shipping them off-box is the caller's seam.
        List<AuditRecord> records = delegate.read(tenantId, afterSequence, limit);
        recordSelfAccess(caller, tenantId, "audit.export", "afterSequence=" + afterSequence + ",limit=" + limit
                + ",returned=" + records.size());
        return records;
    }

/**
 * Verifies a tenant chain after authorizing {@code AUDIT_READ}.
 * @param caller authenticated requester
 * @param tenantId tenant chain to verify
 * @return integrity result, including anomalies rather than silently discarding them
 */
    public ChainVerificationResult verify(RequestContext caller, String tenantId) {
        authorizationService.requireAllowed(caller, AuthorizationAction.AUDIT_READ,
                ProtectedResource.collection("audit-trail", tenantId));
        ChainVerificationResult result = delegate.verify(tenantId);
        recordSelfAccess(caller, tenantId, "audit.verify", "intact=" + result.intact()
                + ",anomalies=" + result.anomalies().size());
        return result;
    }

/**
 * Redacts a contiguous range after authorizing {@code AUDIT_ADMIN}.
 * @param caller authenticated requester
 * @param tenantId tenant whose records are redacted
 * @param fromSequenceInclusive first sequence in the inclusive range
 * @param toSequenceInclusive last sequence in the inclusive range
 * @param reason safe redaction reason retained by the tombstone
 * @return replacement records preserving the chain linkage
 */
    public AuditRecord redact(RequestContext caller, String tenantId, long fromSequenceInclusive,
                              long toSequenceInclusive, String reason) {
        authorizationService.requireAllowed(caller, AuthorizationAction.AUDIT_ADMIN,
                ProtectedResource.collection("audit-trail", tenantId));
        return delegate.redact(tenantId, fromSequenceInclusive, toSequenceInclusive, reason,
                SecurityContext.of(caller).qualifiedIdentity());
    }

    private void recordSelfAccess(RequestContext caller, String tenantId, String action, String detail) {
        AuditEnvelope envelope = AuditEnvelope.of(tenantId, SecurityContext.of(caller).qualifiedIdentity(),
                AuditCategory.ACCESS, action, "audit-trail", tenantId, AuditOutcome.ALLOWED, "",
                caller.requestId(), clock.instant(),
                OpaquePayload.of(detail.getBytes(StandardCharsets.UTF_8), "text/plain"));
        try {
            delegate.append(envelope);
        } catch (RuntimeException unavailable) {
            // The cause is attached rather than dropped. The message stays deliberately non-specific
            // because it reaches a caller who has just been refused, but an operator reading the server
            // log needs to know whether the trail was full, unwritable or corrupt — and discarding the
            // only exception that says so turns a diagnosable outage into a bare denial.
            var denied = new AuthorizationDeniedException("audit trail self-access record unavailable");
            denied.initCause(unavailable);
            throw denied;
        }
    }
}
