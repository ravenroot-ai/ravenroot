package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound on {@link ExecutionEvent#detail()}.
 *
 * <p>The original claim splits into two questions. {@code DeploymentStatus} asserted that {@code detail}
 * was "bounded and redacted"; it was neither. Redaction was withdrawn rather than implemented,
 * because by the time the value reaches construction it is flat text and no lexical rule separates a
 * quoted payload from a legitimate diagnostic — a matcher there would pass a sentinel fixture, miss
 * every real case, and hand the next reader a false assurance. The bound is the half that can
 * actually be enforced without understanding the text, so it is enforced here and nowhere else.</p>
 *
 * <p>These tests pin the compact constructor specifically. That location is the point of the fix:
 * {@code ExecutionMonitor} builds every event core emits, but {@link ExecutionEvent}'s six
 * compatibility constructors let an adapter build one without going through it, and the compact
 * constructor is the only point none of them can bypass.
 * {@link #boundsAnEventBuiltThroughACompatibilityConstructor()} is the guard on that: moving the
 * bound to the producer turns it red while everything else stays green.</p>
 */
class ExecutionEventDetailBoundTest {

    private static ExecutionEvent withDetail(String detail) {
        return new ExecutionEvent(1L, Instant.EPOCH, "tenant-a", "request-1", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.NODE_FAILED,
                "node-1", 0, false, detail, null, null, null, null, null);
    }

    @Test
    void leavesAStringInsideTheBoundExactlyAsItWas() {
        String detail = "connection refused";

        assertEquals(detail, withDetail(detail).detail(),
                "a detail inside the bound must be byte-identical: truncation is for the pathological "
                        + "case, and a bound that rewrote ordinary messages would make every diagnostic "
                        + "in the product suspect");
    }

    @Test
    void leavesAStringExactlyAtTheBoundAlone() {
        String detail = "x".repeat(ExecutionEvent.MAX_DETAIL_LENGTH);

        assertEquals(detail, withDetail(detail).detail(),
                "the bound is inclusive; a string of exactly MAX_DETAIL_LENGTH is not over it");
    }

    @Test
    void cutsAnOversizedDetailToTheBoundIncludingTheMarker() {
        String detail = "y".repeat(ExecutionEvent.MAX_DETAIL_LENGTH * 4);

        String bounded = withDetail(detail).detail();

        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, bounded.length(),
                "the marker counts against the bound rather than being added on top of it, or the "
                        + "'bound' would be a number no consumer could size a column or a log line to");
        assertTrue(bounded.endsWith(ExecutionEvent.DETAIL_TRUNCATION_MARKER),
                "a cut string must carry the marker: a truncated message that reads as a complete one "
                        + "is how an operator concludes the failure was what the fragment says it was");
    }

    /**
     * The marker is the property that matters more than the length. An unbounded message is a
     * capacity problem; a silently cut one is a correctness problem for the person reading it. When
     * this was written the repository still carried one such case — {@link NodeAttempt}'s park cause
     * was bounded at the same 512 with no marker, so a cause at exactly the limit could not be told
     * from a complete one. Sharing this rule rather than copying it closes that gap; see
     * {@code NodeAttemptParkCauseBoundTest}.
     */
    @Test
    void makesTruncationVisibleRatherThanSilent() {
        String bounded = withDetail("z".repeat(ExecutionEvent.MAX_DETAIL_LENGTH + 1)).detail();

        assertTrue(bounded.endsWith(ExecutionEvent.DETAIL_TRUNCATION_MARKER),
                "one character over the bound must still be marked, not quietly shaved to the limit: "
                        + "a silently cut message is read as a complete one, and the operator concludes "
                        + "the failure was whatever the surviving fragment says");
        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, bounded.length(),
                "even the one-over case is cut to the bound, not left one character long");
    }

    /**
     * A cut that lands between a high and a low surrogate leaves a lone surrogate, which has no valid
     * UTF-8 encoding. Both places the value goes next — the SSE and {@code /v1/events/recent} JSON,
     * and the structured log line — would then each have to invent a replacement, so a cut in the
     * wrong place turns a truncated message into an encoding defect several modules away.
     */
    @Test
    void neverSplitsASurrogatePair() {
        // The alignment is the whole point of this fixture. Astral characters are two chars each, so
        // a string of nothing but them puts every pair on an even index and the cut at
        // MAX_DETAIL_LENGTH - marker falls cleanly BETWEEN pairs -- the back-off branch never runs and
        // the test passes without testing anything. One leading ASCII char shifts every pair onto an
        // odd index, so the cut now lands in the middle of one. Both are asserted: the misaligned case
        // exercises the branch, the aligned case proves the branch does not fire when it should not.
        assertPairPreserved("!" + "💣".repeat(ExecutionEvent.MAX_DETAIL_LENGTH), "misaligned");
        assertPairPreserved("💣".repeat(ExecutionEvent.MAX_DETAIL_LENGTH), "aligned");
    }

    private static void assertPairPreserved(String detail, String alignment) {
        String bounded = withDetail(detail).detail();
        String body = bounded.substring(0, bounded.length() - ExecutionEvent.DETAIL_TRUNCATION_MARKER.length());

        assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)),
                alignment + ": the cut left a high surrogate with no low surrogate after it. That has no "
                        + "valid UTF-8 encoding, so the SSE/JSON and structured-log paths would each have "
                        + "to substitute a replacement character for it");
        assertTrue(bounded.length() <= ExecutionEvent.MAX_DETAIL_LENGTH,
                alignment + ": backing off the cut to preserve a pair must shorten the result, never "
                        + "overrun the bound. Got " + bounded.length());
        assertTrue(bounded.endsWith(ExecutionEvent.DETAIL_TRUNCATION_MARKER),
                alignment + ": the marker must survive the back-off");
    }

    /**
     * The reason the bound lives in the compact constructor. This is the pre-{@code deploymentId}
     * 18-argument shape; an adapter using it must inherit the bound anyway.
     */
    @Test
    void boundsAnEventBuiltThroughACompatibilityConstructor() {
        var event = new ExecutionEvent(1L, Instant.EPOCH, "tenant-a", "request-1", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.NODE_FAILED,
                "node-1", 0, false, "w".repeat(ExecutionEvent.MAX_DETAIL_LENGTH * 2), null, null, null);

        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, event.detail().length(),
                "a compatibility constructor delegates to the compact one, so it cannot opt out of the "
                        + "bound. If this fails, the bound was moved somewhere a caller can bypass");
    }

    @Test
    void stillNormalizesNullToEmptyRatherThanFailing() {
        assertEquals("", withDetail(null).detail(),
                "bounding must not disturb the pre-existing null-to-empty normalization");
    }
}
