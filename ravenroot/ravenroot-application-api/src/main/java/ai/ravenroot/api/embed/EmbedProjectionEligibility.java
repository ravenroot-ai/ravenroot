package ai.ravenroot.api.embed;

import java.util.Objects;

/**
 * Revisioned deployment-policy decision consumed at read time.
 *
 * <p>The individual gates stay explicit so provenance, classification, retention, takedown and DSR
 * implementations can evolve independently. A missing or false gate denies the complete projection;
 * this type never describes partial redaction.</p>
 *
 * <p><strong>These values are attested, not computed.</strong> No evaluator exists yet — see
 * {@link #allowed(String)} — so a reader must not treat a stored {@code true} as evidence that a
 * check ran. It is evidence that someone said so, and the audit trail records who.</p>
 *
 * @param policyRevision label identifying the policy attestation
 * @param deploymentAllowed whether the selected deployment may expose its graph
 * @param provenanceAllowed whether provenance policy permits the projection
 * @param classificationAllowed whether data classification permits the projection
 * @param retentionAllowed whether retention policy permits the projection
 * @param dsrSuppressionClear whether no DSR suppression blocks the projection
 * @param takedownClear whether no takedown blocks the projection
 * @param eeaDeployment whether the deployment satisfies the EEA gate
 */
public record EmbedProjectionEligibility(String policyRevision, boolean deploymentAllowed,
                                         boolean provenanceAllowed, boolean classificationAllowed,
                                         boolean retentionAllowed, boolean dsrSuppressionClear,
                                         boolean takedownClear, boolean eeaDeployment) {
    /**
     * Validates the non-blank revision that identifies the policy attestation.
     */
    public EmbedProjectionEligibility {
        Objects.requireNonNull(policyRevision, "policyRevision");
        if (policyRevision.isBlank()) throw new IllegalArgumentException("policyRevision must not be blank");
    }

    /**
     * Requires every independently attested policy gate to be open.
     * @return {@code true} only when no gate denies browser projection
     */
    public boolean allowsProjection() {
        return deploymentAllowed && provenanceAllowed && classificationAllowed && retentionAllowed
                && dsrSuppressionClear && takedownClear && eeaDeployment;
    }

    /**
     * Every gate open. <strong>A test and fixture convenience, not a production shortcut.</strong>
     *
     * <p>No production call site uses this convenience, and the reason is worth stating plainly:
     * <b>no evaluator for these gates exists anywhere in this
     * repository.</b> Nothing computes whether a graph is under takedown, whether its retention has
     * expired, whether a DSR suppression covers it or whether the deployment is inside the EEA. The
     * values on this record are an <em>attestation</em> — today, an operator's, typed on a command
     * line — and {@code policyRevision} is a free-text label stored verbatim and only ever compared
     * with itself.</p>
     *
     * <p>So {@link #allowsProjection()} is enforced faithfully by
     * {@link EmbedRegistrationRules} and by {@link EmbedRegistrationAggregate}, and what it enforces
     * is what somebody said. A factory that turns "policy-2026-02" into seven trues, reachable from
     * the operator surface, is how that stops being visible: the store, the audit trail and
     * {@code embed-registration show} would all display a registration as policy-verified when no
     * policy was consulted. The operator CLI now takes one explicit flag per gate, with no default,
     * so an attestation is made rather than assumed.</p>
     *
     * <p>A real evaluator can be wired to the policy source when that integration is available.</p>
     */
    /**
     * Creates an all-open attestation for tests and fixtures only.
     * @param policyRevision label persisted with this explicit attestation
     * @return eligibility value with every gate set to {@code true}
     */
    public static EmbedProjectionEligibility allowed(String policyRevision) {
        return new EmbedProjectionEligibility(policyRevision, true, true, true, true, true, true, true);
    }
}
