package ai.ravenroot.api.embed;

import java.util.Objects;

/**
 * Closed provision vocabulary. Failure is a value here, not an exception.
 *
 * <p>A conflict and a policy rejection are both expected outcomes of a correct operator command, so
 * neither is signalled by throwing: a caller that must distinguish them would otherwise have to
 * catch and inspect, which is the shape in which a swallowed exception becomes a permissive default.
 * {@link Unavailable} is reserved for an adapter that could not answer at all; it is never the
 * answer to a well-formed but unacceptable command.</p>
 */
public sealed interface EmbedProvisionOutcome {

    /** Successful provision, including the persisted registration snapshot.
     * @param aggregate resulting non-null registration aggregate
     */
    record Provisioned(EmbedRegistrationAggregate aggregate) implements EmbedProvisionOutcome {
        /** Rejects a missing persisted aggregate. */
        public Provisioned {
            Objects.requireNonNull(aggregate, "aggregate");
        }
    }

    /** The expected revision was not the current one; exactly one concurrent writer wins.
     * @param expectedRevision revision submitted by the operator
     * @param currentRevision revision held by the aggregate
     */
    record Conflict(long expectedRevision, long currentRevision) implements EmbedProvisionOutcome { }

    /**
     * The registration <em>was</em> written and its terminal audit record was not.
     *
     * <p>This member exists because the honest answer to «did it take effect?» and the honest answer
     * to «is it in the audit trail?» stopped agreeing, and collapsing that onto
     * {@link Unavailable} would tell an operator that nothing happened when something did. Compensating
     * -- rolling the provision back -- was rejected: the rollback is itself a mutation needing a record
     * it equally cannot write, and it would lose a compare-and-set race a concurrent writer already won.
     *
     * <p>The registration at {@code revision} is live. What is missing is the evidence, and the
     * operator's next step is to establish it by other means rather than to retry the command.</p>
     * @param revision live aggregate revision committed without the terminal audit record
     */
    record AppliedUnrecorded(long revision) implements EmbedProvisionOutcome { }

    /** Well-formed command refused by a deployment rule.
     * @param reason safe-to-disclose refusal category
     */
    record Rejected(Reason reason) implements EmbedProvisionOutcome {
        /** Rejects a missing refusal category. */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Authority was unavailable; this does not describe a policy refusal. */
    enum Unavailable implements EmbedProvisionOutcome { /** Singleton unavailable result. */ INSTANCE }

    /**
     * Why a well-formed command was refused. Deliberately coarse: these names are audited and, in the
     * CLI, printed, so each one must be safe to disclose and must not narrow down a secret.
     */
    enum Reason {
        /** The graph version was not {@code PUBLISHED} or {@code ACTIVE} at provision time. */
        SNAPSHOT_NOT_PUBLISHED,
        /** At least one deployment-policy gate denied the projection. */
        ELIGIBILITY_DENIED,
        /** Snapshot identity, digest, tenant or policy revision disagreed across the command. */
        IDENTITY_INCOHERENT,
        /** The captured payload exceeds the projection budget this deployment enforces. */
        BUDGET_EXCEEDED,
        /** The capability set does not carry {@code GRAPH_READ}. */
        CAPABILITY_MISSING,
        /** The registration id is terminally revoked and is never reissued. */
        REGISTRATION_REVOKED
    }
}
