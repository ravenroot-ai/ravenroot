package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskConfirmationPolicyTest {
    @Test
    void classicPresentationPreservesThePreEmbeddedShape() {
        var presentation = HumanTaskConfirmationPresentation.none();
        assertEquals(0, presentation.version());
        assertEquals(java.util.List.of(), presentation.actions());
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
    void currentAdmissionRejectsEquivalentActiveLabelsButIgnoresInactiveLabels() {
        var policy = HumanTaskPolicy.DEFAULTS.confirmation();
        for (HumanTaskConfirmationPresentation ambiguous : List.of(
                presentation("Proceed", "proceed"),
                presentation("Proceed now", "  PROCEED\u00a0  now  "),
                presentation("Proceed", "ＰＲＯＣＥＥＤ"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> policy.requirePresentation(ambiguous));
        }

        var inactiveDuplicates = new HumanTaskConfirmationPresentation(1, "Approve",
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.RESOLVE),
                "Proceed", "ＰＲＯＣＥＥＤ", "");
        assertDoesNotThrow(() -> policy.requirePresentation(inactiveDuplicates));
        assertEquals("ＰＲＯＣＥＥＤ", inactiveDuplicates.denyLabel(),
                "visible-name comparison must not rewrite the pinned authored label");

        assertDoesNotThrow(() -> policy.requirePresentation(
                presentation("Proceed", "\ufeffProceed")),
                "format characters outside Unicode White_Space/Zs remain significant; clients must not "
                        + "silently widen the documented normalization with a generic trim");
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

    private static HumanTaskConfirmationPresentation presentation(String resolve, String deny) {
        return new HumanTaskConfirmationPresentation(1, "Approve",
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY),
                resolve, deny, "");
    }
}
