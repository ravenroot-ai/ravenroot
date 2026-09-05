package ai.ravenroot.api.application;

import ai.ravenroot.api.deployment.RequestReplyOutcome;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that make a termination reason additive rather than a status change in disguise.
 *
 * <p>Two things have to hold together for the design to be worth its weight. The reason must be
 * <em>readable</em> — a cancelled execution must be distinguishable from a failed one at every type
 * that reports a terminal state. And it must be <em>invisible</em> to everything that only knows
 * about statuses: a reader written before this existed must see exactly what it saw before, which is
 * why the status of a cancelled run is still {@code FAILED} and why every pre-existing constructor
 * still compiles and still means what it meant.</p>
 */
class ExecutionTerminationReasonContractTest {

    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID TRAVERSAL = UUID.randomUUID();

    @Test
    @DisplayName("a cancelled outcome keeps the FAILED status a pre-existing reader expects")
    void aCancelledOutcomeKeepsTheStatusAPreExistingReaderExpects() {
        ExecutionOutcome cancelled = terminal(ExecutionTerminationReason.CANCELLED);
        ExecutionOutcome failed = terminal(null);

        assertEquals(ProcessInstanceStatus.FAILED, cancelled.status());
        assertEquals(failed.status(), cancelled.status(),
                "the whole point of the design: a reader that knows only statuses sees no change");
        assertTrue(cancelled.cancelled());
        assertFalse(failed.cancelled());
        assertNull(failed.terminationReason(),
                "absence means nothing distinguishes this termination, and it is the same absence a "
                        + "row written before the reason existed reports");
    }

    /**
     * A non-terminal outcome carries no reason, dropped rather than rejected.
     *
     * <p>The same treatment {@code paused} gets on the same record, deliberately, so this type has
     * one rule about non-terminal outcomes instead of two opposite ones. The loud refusal lives on
     * the aggregates below, which is where stored state is classified as corrupt.</p>
     */
    @Test
    @DisplayName("a running outcome cannot carry a termination reason")
    void aRunningOutcomeCannotCarryATerminationReason() {
        var running = new ExecutionOutcome(INSTANCE, TRAVERSAL, ProcessInstanceStatus.RUNNING, null,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false,
                ExecutionTerminationReason.CANCELLED);
        assertNull(running.terminationReason());
        assertFalse(running.cancelled());
    }

    @Test
    @DisplayName("applying live pause state preserves the termination reason")
    void applyingLivePauseStatePreservesTheTerminationReason() {
        ExecutionOutcome cancelled = terminal(ExecutionTerminationReason.CANCELLED);
        assertEquals(ExecutionTerminationReason.CANCELLED, cancelled.withPaused(true).terminationReason(),
                "withPaused returns the same terminal outcome; it must not quietly drop a component");
    }

    /**
     * The aggregates refuse the two combinations that describe no real execution.
     *
     * <p>Both are reconstructions a durable adapter must not fold silently: these constructors are
     * what stored rows are rebuilt through, so a contradictory row is classified as corrupt instead of
     * being loaded and believed.</p>
     */
    @Test
    @DisplayName("a reason on a non-terminal or completed aggregate is refused, not repaired")
    void aReasonOnANonTerminalOrCompletedAggregateIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Traversal(TRAVERSAL, "start",
                TraversalStatus.RUNNING, Map.of(), ExecutionTerminationReason.CANCELLED));
        assertThrows(IllegalArgumentException.class, () -> new ProcessInstance(INSTANCE,
                ProcessInstanceStatus.RUNNING, Map.of(), ExecutionTerminationReason.CANCELLED));
        assertThrows(IllegalArgumentException.class, () -> new ProcessInstance(INSTANCE,
                ProcessInstanceStatus.COMPLETED, Map.of(), ExecutionTerminationReason.CANCELLED));
    }

    @Test
    @DisplayName("a terminating transition carries the reason with the status, not after it")
    void aTerminatingTransitionCarriesTheReasonWithTheStatus() {
        var traversal = new Traversal(TRAVERSAL, "start", TraversalStatus.ACCEPTED, Map.of());
        Traversal stopped = traversal.transitionTo(TraversalStatus.FAILED,
                ExecutionTerminationReason.CANCELLED);
        assertEquals(TraversalStatus.FAILED, stopped.status());
        assertEquals(ExecutionTerminationReason.CANCELLED, stopped.terminationReason());
        assertNull(traversal.transitionTo(TraversalStatus.FAILED).terminationReason(),
                "the pre-existing single-argument transition still means an unqualified failure");
    }

    /**
     * The invariant that had to be checked rather than assumed.
     *
     * <p>{@link RequestReplyOutcome} hard-asserts that a {@code FAILED} waiter state is accompanied
     * by an execution outcome whose status is {@code FAILED}. Had cancellation been modelled as a new
     * {@code ProcessInstanceStatus}, a cancelled traversal observed by a request/reply waiter would
     * have violated that assertion and turned a cancellation into a construction failure at the
     * boundary. Because the status stays {@code FAILED} and only the reason is added, the invariant
     * holds unchanged — and the reason travels through the wrapper intact.</p>
     */
    @Test
    @DisplayName("a cancelled execution still satisfies the request/reply FAILED invariant")
    void aCancelledExecutionStillSatisfiesTheRequestReplyFailedInvariant() {
        ExecutionOutcome cancelled = terminal(ExecutionTerminationReason.CANCELLED);
        var outcome = new RequestReplyOutcome(INSTANCE, TRAVERSAL, RequestReplyTerminalState.FAILED,
                Optional.of(cancelled));
        assertTrue(outcome.executionOutcome().orElseThrow().cancelled());
        assertNull(outcome.payload(), "a cancelled run reaches no end node and produces no payload");
    }

    private static ExecutionOutcome terminal(ExecutionTerminationReason reason) {
        return new ExecutionOutcome(INSTANCE, TRAVERSAL, ProcessInstanceStatus.FAILED, null,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false, reason);
    }
}
