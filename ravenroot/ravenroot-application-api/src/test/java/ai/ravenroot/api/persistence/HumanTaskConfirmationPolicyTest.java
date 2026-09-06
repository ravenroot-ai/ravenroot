package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskConfirmationPolicyTest {
    @Test
    void classicPresentationPreservesThePreEmbeddedShape() {
        var presentation = HumanTaskConfirmationPresentation.none();
        assertEquals(0, presentation.version());
        assertEquals(Set.of(), presentation.actions());
        assertEquals("", presentation.prompt());
    }

    @Test
    void embeddedPresentationNeedsAnActionAndLabelsForEveryAction() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskConfirmationPresentation(
                1, "Approve publication", HumanTaskCommentRequirement.OPTIONAL, Set.of(), "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskConfirmationPresentation(
                1, "Approve publication", HumanTaskCommentRequirement.OPTIONAL,
                Set.of(HumanTaskConfirmationAction.RESOLVE), "", "Reject", "Cancel"));
    }

    @Test
    void policyBoundsDisplayAndNormalizesDecisionMetadataOutsidePayload() {
        var policy = new HumanTaskPolicy.Confirmation(16, 8, 32, 250, 1_000);
        var presentation = new HumanTaskConfirmationPresentation(1, "Approve",
                HumanTaskCommentRequirement.REQUIRED, Set.of(HumanTaskConfirmationAction.RESOLVE),
                "Approve", "", "");

        policy.requirePresentation(presentation);
        assertEquals("reason", policy.normalizeComment("  reason  ",
                HumanTaskCommentRequirement.REQUIRED));
        assertEquals("line one\nline two", policy.normalizeComment("line one\nline two",
                HumanTaskCommentRequirement.OPTIONAL));
        assertThrows(IllegalArgumentException.class, () -> policy.normalizeComment("",
                HumanTaskCommentRequirement.REQUIRED));
        assertThrows(IllegalArgumentException.class, () -> policy.normalizeComment("comment",
                HumanTaskCommentRequirement.DISALLOWED));
        assertThrows(IllegalArgumentException.class, () -> policy.normalizeComment("bad\u0000control",
                HumanTaskCommentRequirement.OPTIONAL));
        assertThrows(IllegalArgumentException.class, () -> policy.normalizeComment("bad\ud800",
                HumanTaskCommentRequirement.OPTIONAL));
        assertThrows(IllegalArgumentException.class, () -> policy.requirePresentation(
                new HumanTaskConfirmationPresentation(1, "prompt beyond limit",
                        HumanTaskCommentRequirement.OPTIONAL,
                        Set.of(HumanTaskConfirmationAction.DENY), "", "Reject", "")));
    }
}
