package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionEventProcessingDurationTest {
    private static final UUID PROCESS = UUID.randomUUID();
    private static final UUID TRAVERSAL = UUID.randomUUID();
    private static final UUID INVOCATION = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Test
    void acceptsANonnegativeDurationOnlyForTerminalNodeEvents() {
        var duration = Duration.ofMillis(125);

        assertEquals(duration, event(ExecutionEventType.NODE_COMPLETED, duration).processingDuration());
        assertEquals(duration, event(ExecutionEventType.NODE_BYPASSED, duration).processingDuration());
        assertEquals(Duration.ZERO, event(ExecutionEventType.NODE_FAILED, Duration.ZERO).processingDuration());
        assertThrows(IllegalArgumentException.class,
                () -> event(ExecutionEventType.NODE_STARTED, duration));
        assertThrows(IllegalArgumentException.class,
                () -> event(ExecutionEventType.NODE_FAILED, Duration.ofNanos(-1)));
    }

    @Test
    void terminalDurationMayBeAbsentWhenNoMatchingStartWasRetained() {
        assertNull(event(ExecutionEventType.NODE_COMPLETED, null).processingDuration());
        assertNull(event(ExecutionEventType.NODE_BYPASSED, null).processingDuration());
        assertNull(event(ExecutionEventType.NODE_FAILED, null).processingDuration());
    }

    @Test
    void constructorFromBeforeIssue280RemainsSourceCompatibleAndLeavesDurationUnmeasured() {
        var event = new ExecutionEvent(1, Instant.EPOCH, "tenant", "request", "engine", "graph",
                PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, ExecutionEventType.NODE_COMPLETED, "node", 0,
                false, "outcome=continue", null);

        assertNull(event.processingDuration());
    }

    private static ExecutionEvent event(ExecutionEventType type, Duration processingDuration) {
        return new ExecutionEvent(1, Instant.EPOCH, "tenant", "request", "engine", "graph",
                PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, type, "node", 0, false, "detail", null,
                processingDuration);
    }
}
