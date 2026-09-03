package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.publication.PublicationAuditEvent;
import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationContent;
import ai.ravenroot.api.publication.PublicationDecision;
import ai.ravenroot.api.publication.PublicationDestination;
import ai.ravenroot.api.publication.PublicationPolicy;
import ai.ravenroot.api.publication.PublicationProvenance;
import ai.ravenroot.api.publication.PublicationResource;
import ai.ravenroot.api.publication.PublicationRule;
import ai.ravenroot.api.publication.PublicationRuleId;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.publication.PublicationCandidateMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryGuardNodeBehaviorFactoryTest {

    @Test
    void defaultCatalogExposesTheNodeButFailsClosedWithoutAProfile() throws Exception {
        PublicationPolicy policy = policy(false);
        var registry = BehaviorRegistry.standard();

        assertTrue(registry.descriptor("boundary-guard").isPresent());
        NodeResult result = invoke(registry, node(policy), candidate("public text"));

        assertEquals("violation", result.outcome());
        assertEquals("POLICY_MISSING", ((Map<?, ?>) result.payload()).get("reason"));
    }

    @Test
    void continuePassesTheExactPayloadAndAuditsOnlyBoundedMetadata() throws Exception {
        PublicationPolicy policy = policy(false);
        var events = new ArrayList<PublicationAuditEvent>();
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                (id, version) -> java.util.Optional.of(policy), events::add);
        PublicationCandidate candidate = candidate("public text");

        NodeResult result = invoke(registry, node(policy), candidate);

        assertEquals("continue", result.outcome());
        assertSame(candidate, result.payload());
        assertEquals(1, events.size());
        assertEquals(policy.reference(), events.getFirst().decision().policy());
        assertFalse(events.getFirst().toString().contains("public text"));
        assertEquals(Set.of("ravenroot.publication.policyId", "ravenroot.publication.policyVersion",
                "ravenroot.publication.policyDigest", "ravenroot.publication.ruleId",
                "ravenroot.publication.reason", "ravenroot.publication.candidateBytes",
                "ravenroot.publication.resourceCount"), result.attributes().keySet());
    }

    @Test
    void rejectedContentNeverAppearsInPayloadAttributesAuditOrDiagnostics() throws Exception {
        String protectedValue = "do-not-copy-this-value";
        PublicationPolicy policy = policy(true);
        var events = new ArrayList<PublicationAuditEvent>();
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                (id, version) -> java.util.Optional.of(policy), events::add);

        NodeResult result = invoke(registry, node(policy), candidate(protectedValue));

        assertEquals("violation", result.outcome());
        assertEquals("SENSITIVE_CONTENT", ((Map<?, ?>) result.payload()).get("reason"));
        assertFalse(result.payload().toString().contains(protectedValue));
        assertFalse(result.attributes().toString().contains(protectedValue));
        assertFalse(events.toString().contains(protectedValue));
        assertEquals(null, result.actionDiagnostic());
    }

    @Test
    void malformedInputAndAuditFailureAreNormalViolations() throws Exception {
        PublicationPolicy policy = policy(false);
        var normal = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                (id, version) -> java.util.Optional.of(policy), event -> { });
        NodeResult malformed = invoke(normal, node(policy), Map.of("candidate", "protected-value"));
        assertEquals("violation", malformed.outcome());
        assertEquals("CANDIDATE_MALFORMED", ((Map<?, ?>) malformed.payload()).get("reason"));
        assertFalse(malformed.payload().toString().contains("protected-value"));

        var failedAudit = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                (id, version) -> java.util.Optional.of(policy), event -> {
                    throw new IllegalStateException("protected-audit-value");
                });
        NodeResult failed = invoke(failedAudit, node(policy), candidate("public text"));
        assertEquals("violation", failed.outcome());
        assertEquals("AUDIT_FAILED", ((Map<?, ?>) failed.payload()).get("reason"));
        assertFalse(failed.payload().toString().contains("protected-audit-value"));
    }

    @Test
    void mapProjectionUsesTheSameGuardContract() throws Exception {
        PublicationPolicy policy = policy(false);
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                (id, version) -> java.util.Optional.of(policy), event -> { });
        PublicationCandidate candidate = candidate("public text");
        Map<String, Object> projection = Map.of(
                "contract", PublicationCandidate.CONTRACT,
                "destination", Map.of("type", "repository", "address", "urn:public"),
                "resources", List.of(Map.of(
                        "path", "guide.txt", "artifactType", "document", "mediaType", "text/plain",
                        "language", "en", "content", Map.of("encoding", "utf-8",
                                "fragments", List.of("public text")))),
                "provenance", Map.of("sourceType", "build", "sourceId", "one", "sourceVersion", "v1",
                        "contentDigest", candidate.provenance().contentDigest()));

        NodeResult result = invoke(registry, node(policy), projection);

        assertEquals("continue", result.outcome());
        assertSame(projection, result.payload());
    }

    private static GraphNode node(PublicationPolicy policy) {
        return new GraphNode("guard", NodeKind.BEHAVIOR, "boundary-guard", Map.of(
                "policyId", policy.reference().id(), "policyVersion", policy.reference().version(),
                "policyDigest", policy.reference().digest()));
    }

    private static NodeResult invoke(BehaviorRegistry registry, GraphNode node, Object payload) throws Exception {
        var message = new NodeMessage(TestIdentities.TENANT_A, UUID.randomUUID(), UUID.randomUUID(),
                node.id(), payload, Map.of("upstream", "not-audited"));
        return registry.create(node).orElseThrow().handle(message).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static PublicationPolicy policy(boolean sensitive) {
        var rules = new ArrayList<PublicationRule>();
        rules.add(new PublicationRule.Destination(new PublicationRuleId("destination.public"),
                Set.of("repository"), Set.of("urn:public")));
        rules.add(new PublicationRule.ArtifactType(new PublicationRuleId("artifact.document"),
                Set.of("document"), false));
        rules.add(new PublicationRule.Provenance(new PublicationRuleId("provenance.build"), Set.of("build")));
        if (sensitive) {
            rules.add(new PublicationRule.SensitiveContent(new PublicationRuleId("content.secret"),
                    PublicationRule.SensitiveKind.SECRET,
                    List.of(new PublicationRule.Signature("do-not-copy-this-value",
                            PublicationRule.MatchMode.TOKEN)), true, true, true, 32_768));
        }
        return new PublicationPolicy("public", "v1", 16_384, rules);
    }

    private static PublicationCandidate candidate(String content) {
        var resource = new PublicationResource("guide.txt", "document", "text/plain", "en",
                new PublicationContent.Text(content));
        var incomplete = new PublicationCandidate(new PublicationDestination("repository", "urn:public"),
                List.of(resource), null);
        String digest = PublicationCandidateMetrics.measure(incomplete,
                PublicationPolicy.HARD_MAX_CANDIDATE_BYTES).resourceDigest();
        return new PublicationCandidate(incomplete.destination(), incomplete.resources(),
                new PublicationProvenance("build", "one", "v1", digest));
    }
}
