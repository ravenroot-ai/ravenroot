package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutionMonitorProcessingDurationTest {
    @Test
    void measuresCompletionWithTheMonotonicSourceAndOnlyOnTheTerminalEvent() {
        var monitor = new ExecutionMonitor(readings(100, 137));
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        monitor.nodeStarted(identity, "review", invocationId, attemptId);
        monitor.nodeCompleted(identity, "review", invocationId, attemptId, false, "continue");

        var events = monitor.eventsAfter(0);
        assertEquals(2, events.size());
        assertNull(events.get(0).processingDuration());
        assertEquals(Duration.ofNanos(37), events.get(1).processingDuration());
    }

    @Test
    void correlatesInterleavedMeasurementsByAttemptId() {
        var monitor = new ExecutionMonitor(readings(100, 200, 240, 260));
        var identity = identity();
        UUID firstInvocation = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        UUID secondInvocation = UUID.randomUUID();
        UUID secondAttempt = UUID.randomUUID();

        monitor.nodeStarted(identity, "review", firstInvocation, firstAttempt);
        monitor.nodeStarted(identity, "review", secondInvocation, secondAttempt);
        monitor.nodeCompleted(identity, "review", firstInvocation, firstAttempt, false, "continue");
        monitor.nodeFailed(identity, "review", secondInvocation, secondAttempt,
                new IllegalStateException("expected"));

        assertEquals(Duration.ofNanos(140), event(monitor, ExecutionEventType.NODE_COMPLETED).processingDuration());
        assertEquals(Duration.ofNanos(60), event(monitor, ExecutionEventType.NODE_FAILED).processingDuration());
    }

    @Test
    void clampsARegressingSupplierToAValidNonnegativeDuration() {
        var monitor = new ExecutionMonitor(readings(20, 10));
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        monitor.nodeStarted(identity, "review", invocationId, attemptId);
        monitor.nodeCompleted(identity, "review", invocationId, attemptId, false, "continue");

        assertEquals(Duration.ZERO, event(monitor, ExecutionEventType.NODE_COMPLETED).processingDuration());
    }

    @Test
    void publishesOrphanAndDuplicateTerminalsWithoutInventingDurations() {
        var monitor = new ExecutionMonitor(readings(10, 25));
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID orphanAttempt = UUID.randomUUID();

        monitor.nodeCompleted(identity, "orphan", UUID.randomUUID(), orphanAttempt, false, "continue");
        monitor.nodeStarted(identity, "review", invocationId, attemptId);
        monitor.nodeFailed(identity, "review", invocationId, attemptId, new IllegalStateException("first"));
        monitor.nodeFailed(identity, "review", invocationId, attemptId, new IllegalStateException("duplicate"));

        var terminalEvents = monitor.eventsAfter(0).stream()
                .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                        || event.type() == ExecutionEventType.NODE_FAILED)
                .toList();
        assertNull(terminalEvents.get(0).processingDuration());
        assertEquals(Duration.ofNanos(15), terminalEvents.get(1).processingDuration());
        assertNull(terminalEvents.get(2).processingDuration());
    }

    @Test
    void executionTerminalCleanupTurnsALateNodeTerminalIntoAnOrphan() {
        var monitor = new ExecutionMonitor(readings(10));
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        monitor.nodeStarted(identity, "slow", invocationId, attemptId);
        monitor.executionFailed(identity, new IllegalStateException("traversal failed"));
        monitor.nodeCompleted(identity, "slow", invocationId, attemptId, false, "continue");

        assertNull(event(monitor, ExecutionEventType.NODE_COMPLETED).processingDuration());
    }

    @Test
    void listenersRunSynchronouslyAndOneRuntimeExceptionDoesNotEscapeOrSkipTheNextListener() {
        var monitor = new ExecutionMonitor();
        var observedThread = new AtomicReference<Thread>();
        var observations = new AtomicInteger();
        Thread publisher = Thread.currentThread();
        monitor.subscribe(ignored -> { throw new IllegalStateException("observer defect"); });
        monitor.subscribe(ignored -> {
            observedThread.set(Thread.currentThread());
            observations.incrementAndGet();
        });

        monitor.executionStarted(identity());

        assertSame(publisher, observedThread.get());
        assertEquals(1, observations.get());
        assertEquals(1, monitor.eventsAfter(0).size());
    }

    private static ExecutionMonitor.ExecutionIdentity identity() {
        return new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "test", "graph",
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static ExecutionEvent event(ExecutionMonitor monitor, ExecutionEventType type) {
        return monitor.eventsAfter(0).stream().filter(candidate -> candidate.type() == type).findFirst().orElseThrow();
    }

    private static LongSupplier readings(long... values) {
        var remaining = new ArrayDeque<Long>(List.of(java.util.Arrays.stream(values).boxed().toArray(Long[]::new)));
        return remaining::removeFirst;
    }
}
