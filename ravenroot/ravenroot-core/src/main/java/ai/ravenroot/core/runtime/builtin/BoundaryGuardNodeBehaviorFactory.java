package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.publication.PublicationAuditEvent;
import ai.ravenroot.api.publication.PublicationAuditSink;
import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationDecision;
import ai.ravenroot.api.publication.PublicationPolicyReference;
import ai.ravenroot.api.publication.PublicationRuleId;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.publication.PublicationBoundaryGuard;
import ai.ravenroot.core.publication.PublicationCandidateDecoder;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Pure publication decision node; it owns no provider and performs no external effect. */
final class BoundaryGuardNodeBehaviorFactory implements NodeBehaviorFactory {
    static final String BEHAVIOR = "boundary-guard";
    private static final PublicationRuleId INVALID_REFERENCE = new PublicationRuleId("boundary.policy.reference");
    private static final PublicationRuleId MALFORMED_CANDIDATE = new PublicationRuleId("boundary.candidate.malformed");
    private static final PublicationRuleId AUDIT_FAILURE = new PublicationRuleId("boundary.audit.failure");

    private final PublicationBoundaryGuard guard;
    private final PublicationAuditSink audit;

    BoundaryGuardNodeBehaviorFactory(PublicationBoundaryGuard guard, PublicationAuditSink audit) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Publication boundary guard", "Security",
                "Evaluates a typed publication candidate against an operator-owned immutable policy profile. "
                        + "It validates but never publishes, rewrites, or silently redacts content.",
                "decision", false, List.of(
                NodePropertyDescriptor.required("policyId", "Policy ID", NodePropertyType.STRING,
                        "Operator-owned immutable publication policy profile identifier."),
                NodePropertyDescriptor.required("policyVersion", "Policy version", NodePropertyType.STRING,
                        "Exact immutable policy version selected for this graph."),
                NodePropertyDescriptor.required("policyDigest", "Policy digest", NodePropertyType.STRING,
                        "Canonical sha256 binding pinned by the graph for recovery-safe evaluation.")),
                Set.of("policy", "publication-boundary"))
                .withOutcomes(
                        NodeOutcomeDescriptor.literal("continue", "The exact candidate passed the pinned policy."),
                        NodeOutcomeDescriptor.literal("violation", "Evaluation failed closed or a policy rule refused the candidate."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        PublicationPolicyReference expected = reference(node);
        return message -> {
            PublicationDecision decision;
            if (expected == null) {
                decision = violation(PublicationPolicyReference.UNCONFIGURED, INVALID_REFERENCE,
                        PublicationDecision.Reason.POLICY_REFERENCE_INVALID, 0, 0);
            } else {
                PublicationCandidate candidate;
                try {
                    candidate = PublicationCandidateDecoder.decode(message.payload());
                } catch (RuntimeException malformed) {
                    candidate = null;
                }
                decision = candidate == null
                        ? violation(expected, MALFORMED_CANDIDATE, PublicationDecision.Reason.CANDIDATE_MALFORMED,
                        0, 0)
                        : guard.evaluate(expected, candidate);
            }
            try {
                audit.record(new PublicationAuditEvent(message.processInstanceId(), message.traversalId(),
                        message.invocationId(), message.attemptId(), decision));
            } catch (RuntimeException auditFailure) {
                decision = violation(decision.policy(), AUDIT_FAILURE, PublicationDecision.Reason.AUDIT_FAILED,
                        decision.candidateBytes(), decision.resourceCount());
            }
            Map<String, Object> safeAttributes = Map.of(
                    "ravenroot.publication.policyId", decision.policy().id(),
                    "ravenroot.publication.policyVersion", decision.policy().version(),
                    "ravenroot.publication.policyDigest", decision.policy().digest(),
                    "ravenroot.publication.ruleId", decision.ruleId().value(),
                    "ravenroot.publication.reason", decision.reason().name(),
                    "ravenroot.publication.candidateBytes", decision.candidateBytes(),
                    "ravenroot.publication.resourceCount", decision.resourceCount());
            Object output = decision.disposition() == PublicationDecision.Disposition.CONTINUE
                    ? message.payload() : decision.toMap();
            return CompletableFuture.completedFuture(new NodeResult(
                    decision.disposition() == PublicationDecision.Disposition.CONTINUE ? "continue" : "violation",
                    output, safeAttributes));
        };
    }

    private static PublicationPolicyReference reference(GraphNode node) {
        try {
            return new PublicationPolicyReference(NodeProperties.required(node, "policyId"),
                    NodeProperties.required(node, "policyVersion"), NodeProperties.required(node, "policyDigest"));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static PublicationDecision violation(PublicationPolicyReference policy, PublicationRuleId rule,
                                                 PublicationDecision.Reason reason, long bytes, int resources) {
        return new PublicationDecision(PublicationDecision.Disposition.VIOLATION, policy, rule, reason,
                bytes, resources);
    }
}
