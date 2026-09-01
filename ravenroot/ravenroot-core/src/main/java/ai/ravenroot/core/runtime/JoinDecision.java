package ai.ravenroot.core.runtime;

import java.util.List;

/**
 * What a branch must do after presenting itself at a fan-in join (CORE-03).
 *
 * <p>Sealed, and exhaustive over the four things that can happen to an arrival: it completes the
 * join, it is not yet enough, it was not needed, or the join is now impossible. Every one of them is
 * a distinct action for the caller, which is why they are separate types rather than a nullable
 * result plus a flag.</p>
 */
sealed interface JoinDecision {

    /**
     * This branch met the quorum and is the one that continues the traversal. Exactly one branch per
     * join ever receives this, guaranteed by the store's compare-and-set rather than by a lock.
     */
    record Proceed(List<JoinArrival> arrivals) implements JoinDecision {
        public Proceed {
            arrivals = List.copyOf(arrivals);
        }
    }

    /** The quorum is not met and is still reachable. The branch stops and waits. */
    record Wait() implements JoinDecision {
    }

    /**
     * The arrival changed nothing and the branch stops.
     *
     * <p>Never an error, and deliberately so. It is the ordinary outcome in two situations that both
     * occur on healthy systems: a branch that was still running when a {@code k < n} quorum was met
     * by others, and a redelivery of a branch that already counted — delivery is at-least-once, so
     * treating a duplicate as a fault would make at-least-once delivery itself look like a defect.
     * It is published as an event so it is countable instead of invisible.</p>
     */
    record Discarded(Reason reason, String branchId) implements JoinDecision {
        enum Reason {
            /** This branch already had a recorded outcome for this join. */
            DUPLICATE,
            /** The join had already settled when this branch arrived. */
            LATE
        }
    }

    /** The join can no longer be satisfied. The branch propagates this failure. */
    record Failed(JoinFailureException failure) implements JoinDecision {
    }
}
