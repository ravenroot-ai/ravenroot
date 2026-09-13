package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskReviewPresentationTest {
    @Test
    void plainTextPreservesExactBytesAndBindsThem() {
        String text = "Subject: mail\r\n\r\nHello, π\tthere";
        var presentation = HumanTaskReviewPresentation.plainText(text, 64);

        assertEquals(text, presentation.text());
        assertEquals("text/plain", presentation.contentType());
        assertEquals(ToolApprovalRegistration.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                presentation.contentDigest());
    }

    @Test
    void byteLimitIsInclusiveAndNeverTruncates() {
        assertEquals("", HumanTaskReviewPresentation.plainText("", 1).text());
        assertEquals("π", HumanTaskReviewPresentation.plainText("π", 2).text());
        assertThrows(IllegalArgumentException.class,
                () -> HumanTaskReviewPresentation.plainText("π", 1));
    }

    @Test
    void refusesExecutableModesAndUnicodeControls() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskReviewPresentation(
                1, "text/html", "safe", "sha256:" + "0".repeat(64), 64));
        assertThrows(IllegalArgumentException.class,
                () -> HumanTaskReviewPresentation.plainText("hidden\u0000text", 64));
        assertThrows(IllegalArgumentException.class,
                () -> HumanTaskReviewPresentation.plainText("spoof\u202e", 64));
        assertThrows(IllegalArgumentException.class,
                () -> HumanTaskReviewPresentation.plainText("broken\ud800", 64));
    }
}
