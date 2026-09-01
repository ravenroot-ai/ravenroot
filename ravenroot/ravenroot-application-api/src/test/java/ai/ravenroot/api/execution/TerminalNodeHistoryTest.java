package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention policy behind {@code ExecutionEngine.status} (CORE-04).
 *
 * <p>The conformance suite asserts that an engine's retention is bounded, because that is what an
 * adapter owes. How large the bound is and which entry goes first are policy, and policy is pinned
 * here, deterministically and without spawning anything.</p>
 */
class TerminalNodeHistoryTest {
    @Test
    void retainsTerminalStatusUntilTheCapacityIsExceeded() {
        var history = new TerminalNodeHistory(3);
        List<NodeRef> nodes = record(history, 3);

        assertEquals(3, history.size());
        nodes.forEach(node -> assertEquals(NodeLifecycleState.TERMINATED,
                history.get(node).orElseThrow().status().state()));
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryRatherThanTheOldestOne() {
        var history = new TerminalNodeHistory(3);
        List<NodeRef> nodes = record(history, 3);

        // Reading is what makes an entry recently used: a caller still asking about a node is
        // exactly the caller the retention exists for, and evicting by creation order would drop the
        // node it is holding in favour of one nobody has mentioned since.
        assertTrue(history.get(nodes.get(0)).isPresent());
        NodeRef newcomer = record(history, 1).getFirst();

        assertEquals(3, history.size());
        assertTrue(history.get(nodes.get(0)).isPresent(), "the entry that was just read must survive");
        assertTrue(history.get(nodes.get(1)).isEmpty(), "the least recently used entry is the one that goes");
        assertTrue(history.get(nodes.get(2)).isPresent());
        assertTrue(history.get(newcomer).isPresent());
    }

    @Test
    void neverGrowsPastItsCapacityHoweverManyNodesTerminate() {
        var history = new TerminalNodeHistory(8);

        List<NodeRef> nodes = record(history, 5_000);

        assertEquals(8, history.size());
        // The bound is on the history, not on the traffic through it: the most recent terminations
        // are retained and everything older has been forgotten.
        nodes.subList(nodes.size() - 8, nodes.size())
                .forEach(node -> assertTrue(history.get(node).isPresent()));
        nodes.subList(0, nodes.size() - 8)
                .forEach(node -> assertTrue(history.get(node).isEmpty()));
    }

    @Test
    void defaultsToACapacityLargeEnoughToOutliveAnExecutionButStillBounded() {
        var history = new TerminalNodeHistory();

        assertEquals(TerminalNodeHistory.DEFAULT_CAPACITY, history.capacity());
        assertEquals(1024, TerminalNodeHistory.DEFAULT_CAPACITY);
    }

    @Test
    void keepsTheNodesOwnTerminationStageSoALateStopStillReportsAFailedOnStop() {
        var history = new TerminalNodeHistory(2);
        var lifecycle = new NodeLifecycle(new NodeRef("cleanup-failed"));
        lifecycle.beginDrain();
        lifecycle.terminate(new IllegalStateException("onStop failed"));

        history.record(lifecycle);

        var retained = history.get(lifecycle.node()).orElseThrow();
        assertSame(lifecycle.terminated(), retained.terminated());
        assertTrue(retained.terminated().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void refusesToRetainANodeThatHasNotTerminated() {
        var history = new TerminalNodeHistory(2);
        var running = new NodeLifecycle(new NodeRef("still-running"));

        // Retaining a non-terminal snapshot would make a live node answer from the bounded store,
        // where it could be evicted while it is still running.
        assertThrows(IllegalArgumentException.class, () -> history.record(running));
        assertEquals(0, history.size());
    }

    @Test
    void releasesEverythingWhenCleared() {
        var history = new TerminalNodeHistory(4);
        List<NodeRef> nodes = record(history, 4);

        history.clear();

        assertEquals(0, history.size());
        nodes.forEach(node -> assertTrue(history.get(node).isEmpty()));
        assertTrue(history.get(null).isEmpty());
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalNodeHistory(0));
        assertThrows(IllegalArgumentException.class, () -> new TerminalNodeHistory(-1));
        assertFalse(new TerminalNodeHistory(1).get(new NodeRef("absent")).isPresent());
    }

    private static List<NodeRef> record(TerminalNodeHistory history, int count) {
        var recorded = new ArrayList<NodeRef>(count);
        for (int index = 0; index < count; index++) {
            var lifecycle = new NodeLifecycle(new NodeRef("node-" + java.util.UUID.randomUUID()));
            lifecycle.beginDrain();
            lifecycle.terminate();
            history.record(lifecycle);
            recorded.add(lifecycle.node());
        }
        return recorded;
    }
}
