package ai.ravenroot.api.publication;

/** Pure evaluator SPI shared by the built-in guard and provider bundles. */
@FunctionalInterface
public interface PublicationPolicyEvaluator {
    /**
     * Evaluates the exact candidate against the exact immutable policy.
     *
     * @param policy exact immutable policy
     * @param candidate exact candidate proposed for publication
     * @return a bounded payload-free decision
     */
    PublicationDecision evaluate(PublicationPolicy policy, PublicationCandidate candidate);
}
