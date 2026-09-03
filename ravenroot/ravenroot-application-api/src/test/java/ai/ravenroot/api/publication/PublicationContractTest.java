package ai.ravenroot.api.publication;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static PublicationPolicy policy(Set<String> addresses, int maximum, boolean reverseRules) {
        PublicationRule destination = new PublicationRule.Destination(new PublicationRuleId("destination.allowed"),
                Set.of("repository"), addresses);
        PublicationRule artifact = new PublicationRule.ArtifactType(new PublicationRuleId("artifact.allowed"),
                Set.of("document"), false);
        return new PublicationPolicy("public", "v1", maximum,
                reverseRules ? List.of(artifact, destination) : List.of(destination, artifact));
    }
}
