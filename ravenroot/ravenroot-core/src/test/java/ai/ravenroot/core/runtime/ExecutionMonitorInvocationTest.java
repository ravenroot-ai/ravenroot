package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ExecutionMonitorInvocationTest {
    @Test
    void recordsSeparateCorrelatableEventsForRepeatedVisitsToTheSameNode() {
        var monitor = new ExecutionMonitor();
        var executionId = UUID.randomUUID();
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "test", "graph",
                UUID.randomUUID(), executionId);
        var first = UUID.randomUUID();
        var reentry = UUID.randomUUID();
        monitor.executionStarted(identity);
        monitor.nodeStarted(identity, "review", first);
        monitor.nodeCompleted(identity, "review", first, false, "continue");
        monitor.nodeStarted(identity, "review", reentry);
        monitor.nodeCompleted(identity, "review", reentry, false, "continue");
        // Nothing failed on this run, so the handled-failure set is empty and the published detail
        // is the unchanged "execution completed".
        monitor.executionCompleted(identity, java.util.Set.of());

        var nodeEvents = monitor.eventsAfter(0).stream().filter(event -> event.nodeId() != null).toList();
        assertEquals(4, nodeEvents.size());
        assertEquals(executionId, nodeEvents.getFirst().executionId());
        assertEquals(first, nodeEvents.get(0).invocationId());
        assertEquals(first, nodeEvents.get(1).invocationId());
        assertEquals(reentry, nodeEvents.get(2).invocationId());
        assertEquals(reentry, nodeEvents.get(3).invocationId());
        assertNotEquals(first, reentry);
        assertEquals(ExecutionEventType.NODE_COMPLETED, nodeEvents.get(3).type());
    }
}
