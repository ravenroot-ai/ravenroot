package ai.ravenroot.api.publication;

import java.util.Map;
import java.util.Objects;

/**
 * Payload-free, bounded policy evidence safe for diagnostics and audit sinks.
 *
 * @param disposition exact graph-routing disposition
 * @param policy pinned policy identity and digest
 * @param ruleId refusing rule or guard check
 * @param reason fixed payload-free reason
 * @param candidateBytes measured candidate size
 * @param resourceCount number of candidate resources
 */
public record PublicationDecision(Disposition disposition, PublicationPolicyReference policy,
                                  PublicationRuleId ruleId, Reason reason,
                                  long candidateBytes, int resourceCount) {
    /** Exact graph-routing decisions. */
    public enum Disposition { /** Candidate passed the selected policy. */ CONTINUE,
        /** Candidate must not be published. */ VIOLATION }

    /** Fixed reason vocabulary; no candidate-derived free text can be represented. */
    public enum Reason {
        /** Every rule allowed the candidate. */ CONTINUED("Candidate satisfies the selected publication policy"),
        /** Node has no usable configured reference. */ POLICY_REFERENCE_INVALID("Publication policy reference is missing or invalid"),
        /** Operator resolver returned no profile. */ POLICY_MISSING("Selected publication policy is unavailable"),
        /** Resolved profile differs from the pinned digest. */ POLICY_DIGEST_MISMATCH("Selected publication policy revision does not match the pinned digest"),
        /** Candidate shape or version is invalid. */ CANDIDATE_MALFORMED("Publication candidate is malformed or unsupported"),
        /** Candidate exceeds a hard or profile byte ceiling. */ CANDIDATE_TOO_LARGE("Publication candidate exceeds the configured size limit"),
        /** Candidate omitted required provenance fields. */ PROVENANCE_INCOMPLETE("Publication candidate provenance is incomplete"),
        /** Provenance digest does not bind the resources. */ PROVENANCE_MISMATCH("Publication candidate provenance does not match its resources"),
        /** A rule cannot safely inspect this content representation. */ CONTENT_UNSUPPORTED("Publication content cannot be evaluated safely"),
        /** Destination rule refused the candidate. */ DESTINATION_DENIED("Publication destination is not permitted"),
        /** Logical-path rule refused the candidate. */ PATH_DENIED("A publication logical path is not permitted"),
        /** Sensitive-content rule matched. */ SENSITIVE_CONTENT("Publication content matches a protected pattern"),
        /** Language rule refused the candidate. */ LANGUAGE_DENIED("Publication content language is not permitted"),
        /** Artifact rule refused the candidate. */ ARTIFACT_DENIED("Publication artifact type is not permitted"),
        /** A required companion resource is absent. */ REQUIRED_FILE_MISSING("Publication candidate is missing a required companion resource"),
        /** Provenance rule refused the producer family. */ PROVENANCE_DENIED("Publication provenance is not permitted"),
        /** Evaluator failed or returned an invalid decision. */ EVALUATOR_FAILED("Publication policy evaluation failed closed"),
        /** Audit evidence could not be recorded. */ AUDIT_FAILED("Publication decision audit failed closed");

        private final String message;
        Reason(String message) { this.message = message; }
        /**
         * Bounded fixed English explanation containing no candidate value.
         *
         * @return the fixed explanation
         */
        public String message() { return message; }
    }

    /** Validates internally consistent bounded evidence. */
    public PublicationDecision {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(ruleId, "rule id");
        Objects.requireNonNull(reason, "reason");
        if (candidateBytes < 0 || candidateBytes > PublicationPolicy.HARD_MAX_CANDIDATE_BYTES
                || resourceCount < 0 || resourceCount > 1_024) {
            throw new IllegalArgumentException("publication decision metadata is outside supported bounds");
        }
        if ((disposition == Disposition.CONTINUE) != (reason == Reason.CONTINUED)) {
            throw new IllegalArgumentException("continue disposition and reason must agree");
        }
    }

    /**
     * Stable map projection used as the violation payload.
     *
     * @return a payload-free immutable decision map
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "contract", "ravenroot.publication-decision/1",
                "disposition", disposition.name().toLowerCase(java.util.Locale.ROOT),
                "policyId", policy.id(),
                "policyVersion", policy.version(),
                "policyDigest", policy.digest(),
                "ruleId", ruleId.value(),
                "reason", reason.name(),
                "message", reason.message(),
                "candidateBytes", candidateBytes,
                "resourceCount", resourceCount);
    }
}
