package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound on {@link NodeAttempt#parkCause()}.
 *
 * <p>The park cause was already capped at 512; what it lacked was any way for the reader to know the
 * cap had fired. A cause cut mid-sentence read as a finished one, and the reader is by definition
 * someone investigating a parked node — the person least placed to notice that the evidence was
 * shortened. These tests pin the marker as much as the length.</p>
 *
 * <p>They also pin the <em>unit</em>. The cap is measured in chars rather than code points so that the 512
 * here and the 512 on {@link ExecutionEvent#MAX_DETAIL_LENGTH} mean the same thing;
 * {@link #boundsAstralTextInCharsNotCodePoints()} is the guard that would catch a revert to
 * {@code codePoints().limit(...)}, which the length assertions on ASCII fixtures alone would not.</p>
 */
class NodeAttemptParkCauseBoundTest {

    private static String cause(String raw) {
        return new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.RUNNING).park(raw).parkCause();
    }

    @Test
    void leavesACauseInsideTheBoundExactlyAsItWas() {
        assertEquals("smtp conversation never acknowledged", cause("smtp conversation never acknowledged"),
                "truncation is for the pathological case; a bound that rewrote ordinary causes would "
                        + "make every park diagnostic in the product suspect");
    }

    @Test
    void aCauseOfExactlyTheBoundIsUnmarkedAndThereforeProvablyComplete() {
        String exact = "x".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH);

        String sanitized = cause(exact);

        assertEquals(exact, sanitized, "the bound is inclusive; a cause of exactly the limit is not over it");
        assertFalse(sanitized.endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER),
                "the ambiguity this rule closes: a cause at exactly the limit must be readable as "
                        + "complete, which is only true if the unmarked case really was not cut");
    }

    @Test
    void cutsAnOversizedCauseToTheBoundIncludingTheMarker() {
        String sanitized = cause("y".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH * 3));

        assertTrue(sanitized.endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER),
                "a cut cause must carry the marker, or the operator concludes the node parked for "
                        + "whatever reason the surviving fragment happens to state");
        assertEquals(NodeAttempt.MAX_PARK_CAUSE_LENGTH, sanitized.length(),
                "the marker counts against the bound rather than being added on top of it, or the "
                        + "'bound' would be a number no consumer could size a column or a log line to");
    }

    @Test
    void marksACauseOneCharacterOverTheBound() {
        String sanitized = cause("z".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH + 1));

        assertTrue(sanitized.endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER),
                "one character over must still be marked, not quietly shaved back to the limit");
        assertEquals(NodeAttempt.MAX_PARK_CAUSE_LENGTH, sanitized.length(),
                "even the one-over case is cut to the bound, not left one character long");
    }

    /**
     * The unit decision, asserted rather than described. Under the old {@code codePoints().limit(512)}
     * a cause of astral characters produced 1024 chars while the constant claimed 512, so the two
     * diagnostics of this package disagreed about what their shared number measured.
     */
    @Test
    void boundsAstralTextInCharsNotCodePoints() {
        String sanitized = cause("💣".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH));

        assertTrue(sanitized.length() <= NodeAttempt.MAX_PARK_CAUSE_LENGTH,
                "the cap counts chars, so astral text cannot occupy twice the declared bound. Got "
                        + sanitized.length());
        assertTrue(sanitized.endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER), "and it is marked");
    }

    /**
     * A cut that lands between a high and a low surrogate leaves a lone surrogate, which has no valid
     * UTF-8 encoding — so the JSON surfaces, the structured log line and the persisted row would each
     * have to invent a replacement for it.
     */
    @Test
    void neverSplitsASurrogatePair() {
        // Alignment is the whole point of the fixture. Astral characters are two chars each, so a
        // string of nothing but them puts the cut cleanly BETWEEN pairs and the back-off branch never
        // runs. One leading ASCII char shifts every pair by one, so the cut lands inside one. Both are
        // asserted: the misaligned case exercises the branch, the aligned case proves it does not fire
        // when it should not.
        assertPairPreserved("!" + "💣".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH), "misaligned");
        assertPairPreserved("💣".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH), "aligned");
    }

    private static void assertPairPreserved(String raw, String alignment) {
        String sanitized = cause(raw);
        String body = sanitized.substring(0,
                sanitized.length() - NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER.length());

        assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)),
                alignment + ": the cut left a high surrogate with no low surrogate after it, which has "
                        + "no valid UTF-8 encoding");
        assertTrue(sanitized.length() <= NodeAttempt.MAX_PARK_CAUSE_LENGTH,
                alignment + ": backing off the cut must shorten the result, never overrun the bound. Got "
                        + sanitized.length());
        assertTrue(sanitized.endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER),
                alignment + ": the marker must survive the back-off");
    }

    /**
     * Truncation composes with the two behaviours the park cause already had, rather than replacing
     * them. Both are asserted on an oversized input, because that is the path the change rewired.
     */
    @Test
    void stillStripsControlCharactersAndStillFallsBackToUnspecified() {
        String sanitized = cause("line one\nERROR forged log line\r\tand a tab "
                + "w".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH));

        assertFalse(sanitized.contains("\n"), "a newline would let a park cause forge a log line");
        assertFalse(sanitized.contains("\r"));
        assertFalse(sanitized.contains("\t"));
        assertEquals(NodeAttempt.MAX_PARK_CAUSE_LENGTH, sanitized.length());
        assertTrue(sanitized.endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER));

        // Stripping runs before the bound precisely so this case survives: a cause that is nothing but
        // control characters must still read "unspecified", not a marker with no diagnostic before it.
        assertEquals("unspecified", cause("\u0007\u0001\u0002".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH)),
                "an all-control cause carries no information, and a bare marker would imply it did");
    }

    /** The marker is the same text an operator already learned from the event stream. */
    @Test
    void usesTheSameMarkerAsTheOtherDiagnosticInThisPackage() {
        assertEquals(ExecutionEvent.DETAIL_TRUNCATION_MARKER, NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER,
                "two markers for the same event is how a reader learns to recognise only one of them");
        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, NodeAttempt.MAX_PARK_CAUSE_LENGTH,
                "and the two bounds are one number measured in one unit");
    }
}
