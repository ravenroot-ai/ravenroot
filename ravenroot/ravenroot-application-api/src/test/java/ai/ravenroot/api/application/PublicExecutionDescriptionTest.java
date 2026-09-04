package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicExecutionDescriptionTest {

    @Test
    void everyKnownTypeHasStableHumanCopyWithoutDiagnosticInput() {
        for (ExecutionEventType type : ExecutionEventType.values()) {
            String description = PublicExecutionDescription.forType(type);
            assertFalse(description.isBlank(), type.name());
            assertTrue(description.endsWith("."), type.name());
        }
        assertEquals("Node failed. Protected diagnostics may contain more detail.",
                PublicExecutionDescription.forType(ExecutionEventType.NODE_FAILED));
    }

    @Test
    void durableAndLiveTypesResolveToTheSameDescription() {
        for (ExecutionEventType type : ExecutionEventType.values()) {
            assertEquals(PublicExecutionDescription.forType(type),
                    PublicExecutionDescription.forEventType(type.name()));
        }
    }

    /**
     * Handler-lifecycle types live only in the durable journal, so they are not
     * {@link ExecutionEventType} members. They still need authored copy: a handler event rendered as
     * generic activity would be indistinguishable from a node event in the one view an operator uses
     * to find out why a process has not moved.
     */
    @Test
    void durableHandlerTypesHaveTheirOwnCopyRatherThanTheUnknownFallback() {
        for (String eventType : java.util.List.of(
                ai.ravenroot.api.persistence.HandlerEventData.HANDLER_REGISTERED,
                ai.ravenroot.api.persistence.HandlerEventData.HANDLER_ESCALATED,
                ai.ravenroot.api.persistence.HandlerEventData.HANDLER_EXPIRED,
                ai.ravenroot.api.persistence.HandlerEventData.HANDLER_DENIED,
                ai.ravenroot.api.persistence.HandlerEventData.HANDLER_RESOLVED)) {
            String description = PublicExecutionDescription.forEventType(eventType);
            assertNotEquals(PublicExecutionDescription.UNKNOWN_EVENT, description, eventType);
            assertTrue(description.endsWith("."), eventType);
            assertTrue(description.toLowerCase(java.util.Locale.ROOT).contains("handler"), eventType);
        }
    }

    @Test
    void unknownAndLegacyTypesHaveANonBlankSafeFallbackInsteadOfBeingEchoed() {
        assertEquals(PublicExecutionDescription.UNKNOWN_EVENT,
                PublicExecutionDescription.forEventType("PASSWORD=hunter2\nUNKNOWN"));
        assertEquals(PublicExecutionDescription.UNKNOWN_EVENT,
                PublicExecutionDescription.forEventType(null));
    }

    @Test
    void authoredUnicodeSurvivesWhileControlsAndLinesBecomeOneSpace() {
        assertEquals("Task completed 日本語 safely",
                PublicExecutionDescription.normalizeAuthoredText(
                        "\nTask\tcompleted\u0000\u2028日本語\u2066 safely\r\n"));
    }

    @Test
    void byteBoundNeverSplitsAMultiByteCodePointAndIncludesVisibleMarker() {
        String bounded = PublicExecutionDescription.normalizeAuthoredText("🙂".repeat(200));

        assertTrue(bounded.endsWith(PublicExecutionDescription.TRUNCATION_MARKER));
        assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length
                <= PublicExecutionDescription.MAX_UTF8_BYTES);
        assertFalse(bounded.contains("\ufffd"));
    }

    @Test
    void controlsOnlyBecomeTheGenericFallback() {
        assertEquals(PublicExecutionDescription.UNKNOWN_EVENT,
                PublicExecutionDescription.normalizeAuthoredText("\u0000\n\t\u2066"));
    }

    // The class promises that a caller cannot put text through it. That promise is what makes
    // interpolating a classifier into a sentence safe at all, so it is checked here against the
    // strings a caller would actually have to hand -- an exception message, a diagnostic, markup.
    @Test
    void interpolatesOnlyAConformingClassifierAndOtherwiseFallsBack() {
        assertEquals("Node completed and routed its \"failed\" outcome.",
                PublicExecutionDescription.forType(ExecutionEventType.NODE_COMPLETED, "failed"));
        assertEquals("Node completed successfully.",
                PublicExecutionDescription.forType(ExecutionEventType.NODE_COMPLETED,
                        ExecutionEvent.DEFAULT_ROUTED_OUTCOME));

        for (String hostile : new String[] {
                "Tool is not allowlisted: program.execute",
                "outcome=failed",
                "<img src=x onerror=alert(1)>",
                "password=hunter2",
                "failed\nNODE_COMPLETED",
                "",
                null,
        }) {
            assertEquals("Node completed.",
                    PublicExecutionDescription.forType(ExecutionEventType.NODE_COMPLETED, hostile),
                    "accepted a non-classifier: " + hostile);
        }
    }

    /**
     * The reason-less form is the durable journal's path -- it never captured the outcome -- and it is
     * also the path an older peer reaches. It must be true of a node that routed a FAILURE, because
     * such a node emits NODE_COMPLETED too. That is the exact false row this test prevents.
     */
    @Test
    void theTypeOnlySentenceNeverClaimsSuccessForACompletedNode() {
        assertEquals("Node completed.", PublicExecutionDescription.forType(ExecutionEventType.NODE_COMPLETED));
        assertFalse(PublicExecutionDescription.forEventType("NODE_COMPLETED").contains("successfully"));
    }

    @Test
    void rejectsAClassifierLongerThanTheBoundRatherThanTruncatingItIntoAPlausibleOne() {
        assertEquals("failed", PublicExecutionDescription.conformingReason("failed"));
        assertNull(PublicExecutionDescription.conformingReason(
                "a".repeat(PublicExecutionDescription.MAX_REASON_LENGTH + 1)));
        assertEquals(ExecutionEvent.MAX_PUBLIC_REASON_LENGTH, PublicExecutionDescription.MAX_REASON_LENGTH);
    }

    @Test
    void namesTheCauseClassOnAFailureWithoutEverNamingItsMessage() {
        assertEquals("Node failed with UnknownProgramArtifactException."
                        + " Protected diagnostics may contain more detail.",
                PublicExecutionDescription.forType(ExecutionEventType.NODE_FAILED,
                        "UnknownProgramArtifactException"));
        assertEquals("Join conditions could not be satisfied: QUORUM_UNREACHABLE.",
                PublicExecutionDescription.forType(ExecutionEventType.JOIN_FAILED, "QUORUM_UNREACHABLE"));
    }
}
