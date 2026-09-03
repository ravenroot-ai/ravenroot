package ai.ravenroot.api.publication;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationContractTest {

    @Test
    void policyDigestCoversEffectiveDataAndCanonicalizesSets() {
        var addresses = new HashSet<>(List.of("urn:test:two", "urn:test:one"));
        var first = policy(addresses, 1_024, false);
        var second = policy(new java.util.LinkedHashSet<>(List.of("urn:test:one", "urn:test:two")), 1_024, false);

        assertEquals(first.reference(), second.reference());
        assertNotEquals(first.reference().digest(), policy(addresses, 2_048, false).reference().digest());
        assertNotEquals(first.reference().digest(), policy(addresses, 1_024, true).reference().digest());
    }

    @Test
    void policyAndContentSnapshotsCannotBeChangedByTheirCallers() {
        var fragments = new ArrayList<>(List.of("one"));
        var content = new PublicationContent.Text(fragments);
        fragments.add("two");
        assertEquals(List.of("one"), content.fragments());
        assertThrows(UnsupportedOperationException.class, () -> content.fragments().add("three"));

        var rules = new ArrayList<PublicationRule>();
        rules.add(new PublicationRule.ArtifactType(new PublicationRuleId("artifact.allowed"), Set.of("document"), false));
        var policy = new PublicationPolicy("public", "v1", 1_024, rules);
        rules.clear();
        assertEquals(1, policy.rules().size());
    }

    @Test
    void decisionsCannotCarryCandidateText() {
        var policy = policy(Set.of("urn:test:one"), 1_024, false);
        var decision = new PublicationDecision(PublicationDecision.Disposition.VIOLATION, policy.reference(),
                new PublicationRuleId("secret.detected"), PublicationDecision.Reason.SENSITIVE_CONTENT, 12, 1);

        assertEquals(Set.of("contract", "disposition", "policyId", "policyVersion", "policyDigest",
                        "ruleId", "reason", "message", "candidateBytes", "resourceCount"),
                decision.toMap().keySet());
        assertTrue(decision.toMap().values().stream()
                .noneMatch(value -> String.valueOf(value).contains("ultra-secret-marker")));
    }

    @Test
    void identifiersDigestsAndBudgetsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new PublicationRuleId("Not Stable"));
        assertThrows(IllegalArgumentException.class,
                () -> new PublicationPolicyReference("p", "v1", "sha256:not-a-digest"));
        assertThrows(IllegalArgumentException.class,
                () -> new PublicationPolicy("p", "v1", PublicationPolicy.HARD_MAX_CANDIDATE_BYTES + 1,
                        List.of(new PublicationRule.ArtifactType(new PublicationRuleId("artifact.allowed"),
                                Set.of("document"), false))));
    }

    @Test
    void profileRuleIdsAreUniqueAndCannotUseTheGuardNamespace() {
        var duplicate = new PublicationRuleId("rule.duplicate");
        assertThrows(IllegalArgumentException.class, () -> new PublicationPolicy("p", "v1", 1_024, List.of(
                new PublicationRule.ArtifactType(duplicate, Set.of("document"), false),
                new PublicationRule.Provenance(duplicate, Set.of("build")))));
        assertThrows(IllegalArgumentException.class, () -> new PublicationPolicy("p", "v1", 1_024, List.of(
                new PublicationRule.ArtifactType(new PublicationRuleId("boundary.profile"),
                        Set.of("document"), false))));
    }

    @Test
    void protectedValueRecordsAndFailuresHaveRedactedDiagnosticStrings() {
        String protectedValue = "protected-marker";
        var destination = new PublicationDestination("repository", protectedValue);
        var text = new PublicationContent.Text(protectedValue);
        var binary = new PublicationContent.Base64Binary(java.util.Base64.getEncoder().encodeToString(
                protectedValue.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var resource = new PublicationResource(protectedValue, protectedValue, "text/plain", "en", text);
        var provenance = new PublicationProvenance(protectedValue, protectedValue, protectedValue,
                "sha256:" + "7".repeat(64));
        var candidate = new PublicationCandidate(destination, List.of(resource), provenance);
        var signature = new PublicationRule.Signature(protectedValue, PublicationRule.MatchMode.TOKEN);
        var rules = List.<PublicationRule>of(
                new PublicationRule.Destination(new PublicationRuleId("destination.test"), Set.of("repository"),
                        Set.of(protectedValue)),
                new PublicationRule.LogicalPath(new PublicationRuleId("path.test"), Set.of(protectedValue),
                        true, true, true),
                new PublicationRule.SensitiveContent(new PublicationRuleId("content.test"),
                        PublicationRule.SensitiveKind.SECRET, List.of(signature), true, true, true, 1_024),
                new PublicationRule.Language(new PublicationRuleId("language.test"), Set.of("zz-private"), true),
                new PublicationRule.ArtifactType(new PublicationRuleId("artifact.test"), Set.of(protectedValue), false),
                new PublicationRule.RequiredFilePair(new PublicationRuleId("pair.test"), "." + protectedValue,
                        ".companion"),
                new PublicationRule.Provenance(new PublicationRuleId("provenance.test"), Set.of(protectedValue)));
        var diagnosticValues = new ArrayList<Object>(List.of(
                destination, text, binary, resource, provenance, candidate, signature));
        diagnosticValues.addAll(rules);
        diagnosticValues.add(rules);

        for (Object diagnostic : diagnosticValues) {
            assertFalse(String.valueOf(diagnostic).contains(protectedValue), diagnostic.getClass().getName());
        }
        assertFalse(String.valueOf(candidate).contains(provenance.contentDigest()));
        assertFalse(String.valueOf(binary).contains(binary.fragments().getFirst()));
        List<Runnable> invalidValues = List.of(
                () -> new PublicationCandidate(protectedValue, destination, List.of(resource), provenance),
                () -> new PublicationDestination("repository", protectedValue.repeat(200)),
                () -> new PublicationResource(protectedValue.repeat(200), "document", "text/plain", "en", text),
                () -> new PublicationRule.Signature(protectedValue.repeat(100), PublicationRule.MatchMode.TOKEN),
                () -> new PublicationRule.Destination(new PublicationRuleId("destination.failure"),
                        Set.of("repository"), Set.of(protectedValue.repeat(200))),
                () -> new PublicationRule.LogicalPath(new PublicationRuleId("path.failure"),
                        Set.of(protectedValue.repeat(200)), true, true, true),
                () -> new PublicationRule.ArtifactType(new PublicationRuleId("artifact.failure"),
                        Set.of(protectedValue + " value"), false),
                () -> new PublicationRule.RequiredFilePair(new PublicationRuleId("pair.failure"),
                        protectedValue, protectedValue));
        for (Runnable invalidValue : invalidValues) {
            RuntimeException failure = assertThrows(RuntimeException.class, invalidValue::run);
            assertFalse(failure.toString().contains(protectedValue));
        }
        assertFalse(String.valueOf(new PublicationPolicy("p", "v1", 1_024, rules)).contains(protectedValue));
    }

    private static PublicationPolicy policy(Set<String> addresses, int maximum, boolean reverseRules) {
        PublicationRule destination = new PublicationRule.Destination(new PublicationRuleId("destination.allowed"),
                Set.of("repository"), addresses);
        PublicationRule artifact = new PublicationRule.ArtifactType(new PublicationRuleId("artifact.allowed"),
                Set.of("document"), false);
        return new PublicationPolicy("public", "v1", maximum,
                reverseRules ? List.of(artifact, destination) : List.of(destination, artifact));
    }
}
