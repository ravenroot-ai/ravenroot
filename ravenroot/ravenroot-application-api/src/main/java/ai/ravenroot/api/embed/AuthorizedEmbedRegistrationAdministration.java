package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;

import java.time.Clock;
import java.util.Objects;

/**
 * The only way to reach {@link EmbedRegistrationAuthority#provision} and {@code revoke}.
 *
 * <h2>Operator-only, and why the principal type is checked here too</h2>
 * <p>{@link AuthorizationAction#EMBED_REGISTRATION_ADMIN} is already restricted to a
 * {@link PrincipalType#USER} principal by the central policy. It is re-checked here because this
 * class, not the policy, is what a composition root binds: a deployment that supplied a permissive
 * {@link AuthorizationService} would otherwise be one substitution away from a provision reachable by
 * the same workload principal the browser routes authenticate with. Every embed HTTP route builds a
 * {@code WORKLOAD} context, so this single comparison is what makes «never from a graph, payload,
 * plugin or browser route» a property of the code rather than of the wiring.</p>
 *
 * <h2>Audit is a precondition, not a side effect</h2>
 * <p>An {@code ATTEMPTED} record is written before the store is asked and a terminal record after,
 * and <strong>both are guarded</strong>. If the attempt cannot be recorded the store is never asked:
 * {@link EmbedProvisionOutcome.Unavailable}, nothing written. If the terminal record cannot be
 * written the operation has already happened, and saying "unavailable" would be a lie in the
 * dangerous direction -- so the answer is {@link EmbedProvisionOutcome.AppliedUnrecorded}, which says
 * exactly that: it took effect, the evidence did not.
 *
 * <p>The terminal record used to sit outside every {@code try}. That is a defect worth naming rather
 * than quietly fixing, because of how it hid: a test whose audit sink threw on <em>every</em> call
 * still went red when the fail-closed branch was removed, but it went red on an escaping exception
 * rather than on its own assertion. Against a sink that fails once and then recovers -- an ordinary
 * shape for an audit backend -- the same removal produced a durably written registration, no error
 * and no failing test. The guarantee was covered by accident. {@code
 * anAttemptThatCannotBeRecordedRefusesEvenWhenTheSinkRecoversImmediately} is the test that pins it.
 *
 * <p>A refusal is audited too, and before it throws: an unaudited denial of a privileged operation
 * is the record an audit trail most needs. {@code recordDenied} lets the refusal win if the sink is
 * also down, because on that path nothing was mutated.
 *
 */
public final class AuthorizedEmbedRegistrationAdministration {

    private final AuthorizationService authorization;
    private final EmbedRegistrationAuthority authority;
    private final EmbedRegistrationAuditSink audit;
    private final EmbedProjectionBudget budget;
    private final Clock clock;

    /** Creates administration with the standard projection budget.
     * @param authorization policy decision point
     * @param authority registration persistence authority
     * @param audit fail-closed audit sink
     * @param clock clock used to timestamp audit records
     */
    public AuthorizedEmbedRegistrationAdministration(AuthorizationService authorization,
                                                     EmbedRegistrationAuthority authority,
                                                     EmbedRegistrationAuditSink audit, Clock clock) {
        this(authorization, authority, audit, EmbedProjectionBudget.DEFAULTS, clock);
    }

    /** Creates administration with an explicit projection budget.
     * @param authorization policy decision point
     * @param authority registration persistence authority
     * @param audit fail-closed audit sink
     * @param budget maximum browser projection size accepted at provision time
     * @param clock clock used to timestamp audit records
     */
    public AuthorizedEmbedRegistrationAdministration(AuthorizationService authorization,
                                                     EmbedRegistrationAuthority authority,
                                                     EmbedRegistrationAuditSink audit,
                                                     EmbedProjectionBudget budget, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The budget this administration enforces, so a caller provisions against the same ceiling.
     * @return enforced projection budget
     */
    public EmbedProjectionBudget budget() {
        return budget;
    }

    /** Authorizes, audits, and provisions an embed registration.
     * @param operator authenticated user requesting the mutation
     * @param command compare-and-set provision command
     * @return provision outcome; never leaks cross-tenant registration state
     */
    public EmbedProvisionOutcome provision(RequestContext operator, EmbedProvisionCommand command) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(command, "command");
        requireOperator(operator, command.registrationId(), command.tenantId(),
                command.expectedRevision(), EmbedRegistrationAuditSink.Phase.PROVISION);
        if (!operator.tenantId().equals(command.tenantId())) {
            // NOT merely defence in depth, and the comment here used to say it was.
            // DefaultAuthorizationService skips the tenant comparison entirely for a PLATFORM_ADMIN
            // (see its `!platformAdmin && ...` guard), and EMBED_REGISTRATION_ADMIN is granted to
            // PLATFORM_ADMIN. For that role this line is the only thing standing between a typo in
            // --tenant and a cross-tenant provision. Deleting it because "the policy already denies
            // it" would be deleting the check for the one principal the policy does not check.
            recordDenied(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.PROVISION, "TENANT_MISMATCH");
            throw new AuthorizationDeniedException("cross-tenant embed provision denied");
        }
        EmbedProvisionOutcome.Reason refusal = EmbedRegistrationRules.rejectionOf(command, budget);
        if (refusal != null) {
            recordDenied(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.PROVISION, refusal.name());
            return new EmbedProvisionOutcome.Rejected(refusal);
        }
        try {
            record(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.PROVISION,
                    EmbedRegistrationAuditSink.Outcome.ATTEMPTED, "EXPECTED_REVISION");
        } catch (RuntimeException auditUnavailable) {
            return EmbedProvisionOutcome.Unavailable.INSTANCE;
        }
        EmbedProvisionOutcome outcome;
        try {
            outcome = authority.provision(command);
        } catch (RuntimeException storeFailure) {
            recordDenied(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.PROVISION,
                    EmbedRegistrationAuditSink.Outcome.FAILED, "STORE_FAILURE");
            return EmbedProvisionOutcome.Unavailable.INSTANCE;
        }
        if (outcome == null) outcome = EmbedProvisionOutcome.Unavailable.INSTANCE;
        try {
            record(operator, command.registrationId(), revisionOf(outcome, command.expectedRevision()),
                    EmbedRegistrationAuditSink.Phase.PROVISION, outcomeOf(outcome), detailOf(outcome));
        } catch (RuntimeException auditUnavailable) {
            // The terminal record used to sit outside every try, which made this whole class's
            // "audit is a precondition" claim true only for the attempt record. Two independent
            // failures exposed the gap: the escaping exception was what made the
            // fail-closed test go red, rather than its own assertion, and the exit-code table in the
            // runbook said "nothing was written" for a case where something had been.
            return outcome instanceof EmbedProvisionOutcome.Provisioned provisioned
                    ? new EmbedProvisionOutcome.AppliedUnrecorded(provisioned.aggregate().revision())
                    : EmbedProvisionOutcome.Unavailable.INSTANCE;
        }
        return outcome;
    }

    /** Authorizes, audits, and terminally revokes an embed registration.
     * @param operator authenticated user requesting the mutation
     * @param command compare-and-set revoke command
     * @return revocation outcome; never leaks cross-tenant registration state
     */
    public EmbedRevokeOutcome revoke(RequestContext operator, EmbedRevokeCommand command) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(command, "command");
        requireOperator(operator, command.registrationId(), command.tenantId(),
                command.expectedRevision(), EmbedRegistrationAuditSink.Phase.REVOKE);
        if (!operator.tenantId().equals(command.tenantId())) {
            // See provision: for a PLATFORM_ADMIN this is the only cross-tenant check there is.
            recordDenied(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.REVOKE, "TENANT_MISMATCH");
            throw new AuthorizationDeniedException("cross-tenant embed revocation denied");
        }
        try {
            record(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.REVOKE,
                    EmbedRegistrationAuditSink.Outcome.ATTEMPTED, "EXPECTED_REVISION");
        } catch (RuntimeException auditUnavailable) {
            return EmbedRevokeOutcome.Unavailable.INSTANCE;
        }
        EmbedRevokeOutcome outcome;
        try {
            outcome = authority.revoke(command);
        } catch (RuntimeException storeFailure) {
            recordDenied(operator, command.registrationId(), command.expectedRevision(),
                    EmbedRegistrationAuditSink.Phase.REVOKE,
                    EmbedRegistrationAuditSink.Outcome.FAILED, "STORE_FAILURE");
            return EmbedRevokeOutcome.Unavailable.INSTANCE;
        }
        if (outcome == null) outcome = EmbedRevokeOutcome.Unavailable.INSTANCE;
        try {
            record(operator, command.registrationId(), revisionOf(outcome, command.expectedRevision()),
                    EmbedRegistrationAuditSink.Phase.REVOKE, outcomeOf(outcome), detailOf(outcome));
        } catch (RuntimeException auditUnavailable) {
            return outcome instanceof EmbedRevokeOutcome.Revoked revoked
                    ? new EmbedRevokeOutcome.AppliedUnrecorded(revoked.revision())
                    : EmbedRevokeOutcome.Unavailable.INSTANCE;
        }
        return outcome;
    }

    /**
     * Both halves of operator-only, each audited before it throws.
     *
     * <p>Previously this method threw before any record was written, so the one class of attempt an
     * audit trail most needs -- a privileged operation refused -- left no trace anywhere, while the
     * cross-tenant refusal below it did record one. The audit contract requires a record for every
     * attempt, including a denial.</p>
     */
    private void requireOperator(RequestContext operator, String registrationId, String tenantId,
                                 long expectedRevision, EmbedRegistrationAuditSink.Phase phase) {
        if (operator.principalType() != PrincipalType.USER) {
            recordDenied(operator, registrationId, expectedRevision, phase, "PRINCIPAL_TYPE");
            throw new AuthorizationDeniedException("embed registration administration is operator-only");
        }
        try {
            authorization.requireAllowed(operator, AuthorizationAction.EMBED_REGISTRATION_ADMIN,
                    ProtectedResource.owned("embed-registration", registrationId, tenantId));
        } catch (AuthorizationDeniedException denied) {
            recordDenied(operator, registrationId, expectedRevision, phase, "POLICY_DENIED");
            throw denied;
        }
    }

    /**
     * Records a refusal, and lets the refusal win if the sink cannot take it.
     *
     * <p>Deliberately different from the attempt record, which refuses the operation when it cannot be
     * written. Here the operation is already refused: nothing was mutated, so an unwritable record
     * costs evidence of a denial rather than allowing an unaudited act. Throwing the audit failure
     * instead would replace a precise "denied" with a vague "unavailable" and tell the caller less.</p>
     */
    private void recordDenied(RequestContext operator, String registrationId, long revision,
                              EmbedRegistrationAuditSink.Phase phase, String detail) {
        recordDenied(operator, registrationId, revision, phase,
                EmbedRegistrationAuditSink.Outcome.DENIED, detail);
    }

    private void recordDenied(RequestContext operator, String registrationId, long revision,
                              EmbedRegistrationAuditSink.Phase phase,
                              EmbedRegistrationAuditSink.Outcome outcome, String detail) {
        try {
            record(operator, registrationId, revision, phase, outcome, detail);
        } catch (RuntimeException auditUnavailable) {
            // Nothing was written to the store on this path; see the Javadoc above.
        }
    }

    private void record(RequestContext operator, String registrationId, long revision,
                        EmbedRegistrationAuditSink.Phase phase,
                        EmbedRegistrationAuditSink.Outcome outcome, String detail) {
        audit.record(new EmbedRegistrationAuditSink.Event(clock.instant(), operator.requestId(),
                operator.tenantId(), operator.subject(), registrationId, revision, phase, outcome, detail));
    }

    private static long revisionOf(EmbedProvisionOutcome outcome, long fallback) {
        return switch (outcome) {
            case EmbedProvisionOutcome.Provisioned provisioned -> provisioned.aggregate().revision();
            case EmbedProvisionOutcome.Conflict conflict -> conflict.currentRevision();
            case EmbedProvisionOutcome.AppliedUnrecorded applied -> applied.revision();
            default -> fallback;
        };
    }

    private static long revisionOf(EmbedRevokeOutcome outcome, long fallback) {
        return switch (outcome) {
            case EmbedRevokeOutcome.Revoked revoked -> revoked.revision();
            case EmbedRevokeOutcome.AlreadyRevoked revoked -> revoked.revision();
            case EmbedRevokeOutcome.Conflict conflict -> conflict.currentRevision();
            case EmbedRevokeOutcome.AppliedUnrecorded applied -> applied.revision();
            default -> fallback;
        };
    }

    private static EmbedRegistrationAuditSink.Outcome outcomeOf(EmbedProvisionOutcome outcome) {
        return switch (outcome) {
            case EmbedProvisionOutcome.Provisioned ignored -> EmbedRegistrationAuditSink.Outcome.ALLOWED;
            case EmbedProvisionOutcome.Conflict ignored -> EmbedRegistrationAuditSink.Outcome.CONFLICT;
            case EmbedProvisionOutcome.Rejected ignored -> EmbedRegistrationAuditSink.Outcome.DENIED;
            case EmbedProvisionOutcome.Unavailable ignored -> EmbedRegistrationAuditSink.Outcome.FAILED;
            // Unreachable: this member is only ever produced *by* a failed terminal record.
            case EmbedProvisionOutcome.AppliedUnrecorded ignored -> EmbedRegistrationAuditSink.Outcome.FAILED;
        };
    }

    private static EmbedRegistrationAuditSink.Outcome outcomeOf(EmbedRevokeOutcome outcome) {
        return switch (outcome) {
            case EmbedRevokeOutcome.Revoked ignored -> EmbedRegistrationAuditSink.Outcome.ALLOWED;
            case EmbedRevokeOutcome.AlreadyRevoked ignored -> EmbedRegistrationAuditSink.Outcome.ALLOWED;
            case EmbedRevokeOutcome.Conflict ignored -> EmbedRegistrationAuditSink.Outcome.CONFLICT;
            case EmbedRevokeOutcome.NotFound ignored -> EmbedRegistrationAuditSink.Outcome.DENIED;
            case EmbedRevokeOutcome.Unavailable ignored -> EmbedRegistrationAuditSink.Outcome.FAILED;
            case EmbedRevokeOutcome.AppliedUnrecorded ignored -> EmbedRegistrationAuditSink.Outcome.FAILED;
        };
    }

    private static String detailOf(EmbedProvisionOutcome outcome) {
        return switch (outcome) {
            case EmbedProvisionOutcome.Provisioned ignored -> "PROVISIONED";
            case EmbedProvisionOutcome.Conflict ignored -> "REVISION_CONFLICT";
            case EmbedProvisionOutcome.Rejected rejected -> rejected.reason().name();
            case EmbedProvisionOutcome.Unavailable ignored -> "STORE_UNAVAILABLE";
            case EmbedProvisionOutcome.AppliedUnrecorded ignored -> "APPLIED_UNRECORDED";
        };
    }

    private static String detailOf(EmbedRevokeOutcome outcome) {
        return switch (outcome) {
            case EmbedRevokeOutcome.Revoked ignored -> "REVOKED";
            case EmbedRevokeOutcome.AlreadyRevoked ignored -> "ALREADY_REVOKED";
            case EmbedRevokeOutcome.Conflict ignored -> "REVISION_CONFLICT";
            case EmbedRevokeOutcome.NotFound ignored -> "NOT_FOUND";
            case EmbedRevokeOutcome.Unavailable ignored -> "STORE_UNAVAILABLE";
            case EmbedRevokeOutcome.AppliedUnrecorded ignored -> "APPLIED_UNRECORDED";
        };
    }
}
