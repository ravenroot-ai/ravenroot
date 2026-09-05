package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every clause that decides whether a hold can be written down, asserted one at a time.
 *
 * <h2>Why these exist beside the end-to-end tests</h2>
 * <p>{@code DurablePausedExecutionRecoveryTest} asserts the behaviour a boundary produces — the hold
 * is taken, nothing is written down, the traversal still holds — and that is the assertion that
 * matters. It can only make it for shapes a graph can actually reach, and two of these clauses have
 * no such shape: a fan-in hop always carries a lap as well, so removing the fan-in clause on its own
 * changed no test, and an unfinished invocation cannot coexist with a single-branch traversal at the
 * gate at all. Both are guards against the unsafe direction, so leaving them unasserted meant a
 * regression could delete one and nothing would notice.</p>
 *
 * <p>Each test below removes exactly one fact from an otherwise writable boundary, so a clause that
 * stops being consulted reddens exactly the test named after it. The positive control is what keeps
 * them from holding vacuously: it asserts the same boundary <em>is</em> writable when nothing is
 * wrong with it, so "not writable" is a decision here rather than the default.</p>
 */
final class PauseBoundaryAdmissionTest {

    /** A single-branch traversal at a completed node, against a store that keeps holds. */
    private static PauseBoundary writable() {
        return new PauseBoundary(true, 1, false, false, false, false);
    }

    @Test
    void aSingleBranchTraversalAtACompletedNodeIsWritable() {
        assertTrue(writable().writable(),
                "the control: without this every assertion below could hold for the wrong reason");
    }

    @Test
    void aStoreThatDoesNotKeepHoldsMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(false, 1, false, false, false, false).writable(),
                "there is nowhere to write the hold, so it stays process-local");
    }

    @Test
    void aFirstNodeWithNoPredecessorMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(true, 0, false, false, false, false).writable(),
                "a continuation is anchored behind a completed invocation, and there is none");
    }

    @Test
    void aMergeWithSeveralPredecessorsMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(true, 2, false, false, false, false).writable(),
                "several parents are a fan-in's business, not a single dispatch's");
    }

    @Test
    void aHopEnteringAFanInMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(true, 1, true, false, false, false).writable(),
                "continuing one arrival of a correlation the join store owns would present it twice");
    }

    @Test
    void aLapContextMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(true, 1, false, true, false, false).writable(),
                "a lap distinguishes a second pass from a retry and lives only in the runner");
    }

    @Test
    void aTraversalThatHasFannedOutMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(true, 1, false, false, true, false).writable(),
                "a one-hop continuation would discard every branch but one on restart");
    }

    @Test
    void anUnfinishedInvocationMakesTheBoundaryUnwritable() {
        assertFalse(new PauseBoundary(true, 1, false, false, false, true).writable(),
                "a branch still inside a node is a branch a one-hop continuation would drop");
    }
}
