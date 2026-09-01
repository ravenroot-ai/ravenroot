package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GraphRunner#merge} at the unit level, isolated from {@link JoinCoordinator}.
 *
 * <p>{@link JoinAtEndNodeTest} forces arrival <em>timing</em> -- one branch is held back and
 * released after the other has already completed -- and then reads the merged payload back in
 * branch-id order. That is not evidence that {@code merge}'s own
 * {@code ordered.sort(Comparator.comparing(JoinArrival::branchId))} produced the order: {@link
 * JoinCoordinator#evaluate} builds the {@code ready} list it hands to {@code merge} by iterating
 * {@link ai.ravenroot.api.persistence.JoinRecord#plus}'s {@code SortedMap}, so the list {@code
 * merge} receives is already sorted by branch id before its own sort ever runs, regardless of which
 * branch physically arrived last. Removing {@code merge}'s sort leaves {@link JoinAtEndNodeTest}
 * green, because the guarantee it observes is produced redundantly, one level up.
 *
 * <p>This test calls {@link GraphRunner#merge} directly -- it is package-private, and this test
 * lives in the same package, {@code ai.ravenroot.core.runtime} -- with an arrival list built
 * deliberately <em>out</em> of branch-id order, so nothing upstream can be supplying the order
 * under test. It is red under the mutant that removed {@code merge}'s sort line, verified by
 * running that exact mutation rather than by inspection.</p>
 */
class GraphRunnerMergeOrderTest {

    @Test
    void sortsArrivalsByBranchIdEvenWhenTheInputIsNotSorted() {
        // b2, b0, b1 -- deliberately not branch-id order and not arrival order either, so no
        // incidental ordering of the input list could produce a sorted output by accident.
        List<JoinArrival> unsorted = new ArrayList<>(List.of(
                new JoinArrival("b2", "b2-result", Map.of(), Set.of()),
                new JoinArrival("b0", "b0-result", Map.of(), Set.of()),
                new JoinArrival("b1", "b1-result", Map.of(), Set.of())));

        JoinArrival merged = GraphRunner.merge(unsorted);

        assertEquals(List.of("b0-result", "b1-result", "b2-result"), merged.payload(),
                "merge() must sort by branch id on its own, independent of the order its caller "
                        + "already happens to hand it in");
    }
}
