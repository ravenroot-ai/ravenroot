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
                presentation("Proceed now", "  ＰＲＯＣＥＥＤ\u00a0\u1680\u2000\u2001\u2002\u2003"
                        + "\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000NOW  "),
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
                "FEFF is outside the frozen separator set and remains significant");
        assertDoesNotThrow(() -> policy.requirePresentation(
                presentation("A", "\ud833\udcd6")),
                "the fixed Java 21/browser parity vector must remain distinct without ambient Unicode folding");
        assertDoesNotThrow(() -> policy.requirePresentation(
                presentation("É", "é")),
                "non-ASCII case remains exact under the frozen comparison contract");
    }

    @Test
    void currentAdmissionUsesStableVisibleBlanknessWhileHistoricalShapeStaysReadable() {
        var policy = HumanTaskPolicy.DEFAULTS.confirmation();
        var separatorPrompt = presentationWithPrompt("\u00a0\u3000", "Proceed", "Deny");
        var separatorLabel = presentation("\u00a0\u3000", "Deny");

        assertDoesNotThrow(() -> new HumanTaskConfirmationPresentation(1, "\u00a0",
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.RESOLVE), "\u00a0", "", ""),
                "historical structural decoding must not apply current visible-text admission");
        assertThrows(IllegalArgumentException.class, () -> policy.requirePresentation(separatorPrompt));
        assertThrows(IllegalArgumentException.class, () -> policy.requirePresentation(separatorLabel));
        assertDoesNotThrow(() -> policy.requirePresentation(
                presentationWithPrompt("\ufeff", "\ufeff", "Deny")),
                "FEFF-only text remains significant under the fixed contract");
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
        return presentationWithPrompt("Approve", resolve, deny);
    }

    private static HumanTaskConfirmationPresentation presentationWithPrompt(
            String prompt, String resolve, String deny) {
        return new HumanTaskConfirmationPresentation(1, prompt,
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY),
                resolve, deny, "");
    }
}
