package ai.ravenroot.core.publication;

import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationDecision;
import ai.ravenroot.api.publication.PublicationPolicy;
import ai.ravenroot.api.publication.PublicationPolicyEvaluator;
import ai.ravenroot.api.publication.PublicationPolicyReference;
import ai.ravenroot.api.publication.PublicationPolicyResolver;
import ai.ravenroot.api.publication.PublicationRuleId;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reusable pure guard that resolves, pins, bounds, validates, and evaluates one exact candidate. */
public final class PublicationBoundaryGuard {
    private static final PublicationRuleId REFERENCE = new PublicationRuleId("boundary.policy.reference");
    private static final PublicationRuleId MISSING = new PublicationRuleId("boundary.policy.missing");
    private static final PublicationRuleId DRIFT = new PublicationRuleId("boundary.policy.digest");
    private static final PublicationRuleId SIZE = new PublicationRuleId("boundary.candidate.size");
    private static final PublicationRuleId MALFORMED = new PublicationRuleId("boundary.candidate.malformed");
    private static final PublicationRuleId PROVENANCE = new PublicationRuleId("boundary.provenance.incomplete");
    private static final PublicationRuleId PROVENANCE_DIGEST = new PublicationRuleId("boundary.provenance.mismatch");
    private static final PublicationRuleId EVALUATOR = new PublicationRuleId("boundary.evaluator.failure");

    private final PublicationPolicyResolver resolver;
    private final PublicationPolicyEvaluator evaluator;

    /** Creates a guard from operator-owned resolution and a pure evaluator. */
    public PublicationBoundaryGuard(PublicationPolicyResolver resolver, PublicationPolicyEvaluator evaluator) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /** Resolves and evaluates without performing or authorizing a publication effect. */
    public PublicationDecision evaluate(PublicationPolicyReference expected, PublicationCandidate candidate) {
        if (expected == null) {
            return violation(PublicationPolicyReference.UNCONFIGURED, REFERENCE,
                    PublicationDecision.Reason.POLICY_REFERENCE_INVALID, 0, 0);
        }
        PublicationPolicy policy;
        try {
            policy = resolver.resolve(expected.id(), expected.version()).orElse(null);
        } catch (RuntimeException failure) {
            return violation(expected, EVALUATOR, PublicationDecision.Reason.EVALUATOR_FAILED, 0, 0);
        }
        if (policy == null) {
            return violation(expected, MISSING, PublicationDecision.Reason.POLICY_MISSING, 0, 0);
        }
        if (!sameReference(expected, policy.reference())) {
            return violation(expected, DRIFT, PublicationDecision.Reason.POLICY_DIGEST_MISMATCH, 0, 0);
        }
        PublicationCandidateMetrics.Measurement measurement;
        try {
            measurement = PublicationCandidateMetrics.measure(candidate, policy.maxCandidateBytes());
        } catch (PublicationCandidateMetrics.MeasurementException failure) {
            return violation(expected, failure.failure() == PublicationCandidateMetrics.Failure.TOO_LARGE ? SIZE : MALFORMED,
                    failure.failure() == PublicationCandidateMetrics.Failure.TOO_LARGE
                            ? PublicationDecision.Reason.CANDIDATE_TOO_LARGE
                            : PublicationDecision.Reason.CANDIDATE_MALFORMED, 0, 0);
        }
        if (candidate.provenance() == null || !candidate.provenance().complete()) {
            return violation(expected, PROVENANCE, PublicationDecision.Reason.PROVENANCE_INCOMPLETE,
                    measurement.bytes(), measurement.resourceCount());
        }
        if (!constantTimeEquals(candidate.provenance().contentDigest(), measurement.resourceDigest())) {
            return violation(expected, PROVENANCE_DIGEST, PublicationDecision.Reason.PROVENANCE_MISMATCH,
                    measurement.bytes(), measurement.resourceCount());
        }
        try {
            PublicationDecision decision = evaluator.evaluate(policy, candidate);
            if (decision == null || !sameReference(expected, decision.policy())
                    || decision.candidateBytes() != measurement.bytes()
                    || decision.resourceCount() != measurement.resourceCount()) {
                return violation(expected, EVALUATOR, PublicationDecision.Reason.EVALUATOR_FAILED,
                        measurement.bytes(), measurement.resourceCount());
            }
            return decision;
        } catch (RuntimeException failure) {
            return violation(expected, EVALUATOR, PublicationDecision.Reason.EVALUATOR_FAILED,
                    measurement.bytes(), measurement.resourceCount());
        }
    }

    private static PublicationDecision violation(PublicationPolicyReference reference, PublicationRuleId rule,
                                                 PublicationDecision.Reason reason, long bytes, int count) {
        return new PublicationDecision(PublicationDecision.Disposition.VIOLATION, reference, rule, reason,
                bytes, count);
    }

    private static boolean sameReference(PublicationPolicyReference left, PublicationPolicyReference right) {
        return left.id().equals(right.id()) && left.version().equals(right.version())
                && constantTimeEquals(left.digest(), right.digest());
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
