package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code ExecutionEvent.joinWaitDuration} must be populated on join settlement and
 * absent everywhere else — never a manufactured zero standing in for "not measured".
 *
 * <p>This exercises only {@link ExecutionMonitor}'s own contract: given a duration, it reaches the
 * published event unchanged; given no duration to give (every non-join event), the field stays
 * {@code null}. {@link JoinWaitDurationTest} proves the duration {@link JoinCoordinator} actually
 * computes is the real elapsed time between a join opening and settling, which this test does not
 * touch at all.</p>
 */
class ExecutionMonitorJoinWaitDurationTest {
    private static final JoinFailureException FAILURE = new JoinFailureException(
            JoinFailureException.Reason.TIMEOUT, "join", 2, List.of(), List.of(), List.of("b1"));

    @Test
    void joinSatisfiedCarriesTheGivenDurationOntoItsEvent() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        var duration = Duration.ofMillis(437);

        monitor.joinSatisfied(identity, "join", 2, 2, duration);

        var event = onlyEvent(monitor);
        assertEquals(ExecutionEventType.JOIN_SATISFIED, event.type());
        assertEquals(duration, event.joinWaitDuration());
    }

    @Test
    void joinFailedCarriesTheGivenDurationOntoItsEvent() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        var duration = Duration.ofSeconds(30);

        monitor.joinFailed(identity, "join", FAILURE, duration);

        var event = onlyEvent(monitor);
        assertEquals(ExecutionEventType.JOIN_FAILED, event.type());
        assertEquals(duration, event.joinWaitDuration());
    }

    /**
     * The guard that can fail. A caller that reaches settlement without a duration to report is a
     * defect in the caller, not a value to publish — {@code null} here would be indistinguishable
     * from a legitimate absence on every other event type. Proven non-vacuous the same way the
     * verdict-equivalence test is: the pre-PLAT-01 {@code joinSatisfied}/{@code joinFailed}
     * signatures took no duration parameter at all, so they could not state this invariant. Passing
     * {@code null} here proves that the current guard can fail.
     */
    @Test
    void joinSatisfiedRefusesAMissingDurationRatherThanPublishingAbsenceAsHealth() {
        var monitor = new ExecutionMonitor();
        assertThrows(NullPointerException.class,
                () -> monitor.joinSatisfied(identity(), "join", 2, 2, null));
    }

    @Test
    void joinFailedRefusesAMissingDurationRatherThanPublishingAbsenceAsHealth() {
        var monitor = new ExecutionMonitor();
        assertThrows(NullPointerException.class,
                () -> monitor.joinFailed(identity(), "join", FAILURE, null));
    }

    /**
     * Every event type that is not a join settlement must never carry a duration, so a bridge cannot
     * mistake "this event type never measures join wait" for "this join waited zero time".
     */
    @Test
    void nonJoinEventsNeverCarryAJoinWaitDuration() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        var invocationId = UUID.randomUUID();

        monitor.executionStarted(identity);
        monitor.nodeStarted(identity, "review", invocationId);
        monitor.nodeCompleted(identity, "review", invocationId, false, "continue");
        monitor.joinArrivalDiscarded(identity, "join", "b0", "DUPLICATE");
        monitor.executionCompleted(identity, java.util.Set.of());

        var events = monitor.eventsAfter(0);
        assertEquals(5, events.size());
        events.forEach(event -> assertNull(event.joinWaitDuration(),
                () -> event.type() + " must not carry a join wait duration"));
    }

    private static ExecutionMonitor.ExecutionIdentity identity() {
        return new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "test", "graph",
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static ai.ravenroot.api.application.ExecutionEvent onlyEvent(ExecutionMonitor monitor) {
        var events = monitor.eventsAfter(0);
        assertEquals(1, events.size());
        return events.getFirst();
    }
}
