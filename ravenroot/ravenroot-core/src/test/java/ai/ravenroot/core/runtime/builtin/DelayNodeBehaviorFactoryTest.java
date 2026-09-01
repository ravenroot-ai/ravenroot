package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayNodeBehaviorFactoryTest {
    @Test
    void publishesAnInspectorReadyBoundedDuration() {
        var descriptor = new DelayNodeBehaviorFactory().descriptor();

        assertEquals("delay", descriptor.behavior());
        assertEquals("Control flow", descriptor.category());
        assertEquals("durationMs", descriptor.properties().getFirst().name());
        assertEquals("1000", descriptor.properties().getFirst().defaultValue());
        assertTrue(descriptor.capabilities().contains("non-blocking"));
    }

    @Test
    void schedulesRatherThanCompletingOrSleepingTheCallingThread() throws Exception {
        var handler = new DelayNodeBehaviorFactory().create(new GraphNode("pause", NodeKind.BEHAVIOR, "delay",
                Map.of("durationMs", 100)));
        var identity = new SecurityContext("request-delay", "tenant-a", "tester", PrincipalType.USER,
                "urn:ravenroot:test");
        var message = new NodeMessage(identity, UUID.randomUUID(),
                UUID.randomUUID(), "pause", "payload", Map.of());

        var future = handler.handle(message).toCompletableFuture();

        assertTrue(!future.isDone(), "the handler returned before the configured delay elapsed");
        NodeResult result = future.get(2, TimeUnit.SECONDS);
        assertEquals("payload", result.payload());
    }

    @Test
    void rejectsNegativeAndUnboundedDurationsAtComposition() {
        var factory = new DelayNodeBehaviorFactory();

        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new GraphNode("negative", NodeKind.BEHAVIOR, "delay",
                        Map.of("durationMs", -1))));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new GraphNode("unbounded", NodeKind.BEHAVIOR, "delay",
                        Map.of("durationMs", DelayNodeBehaviorFactory.MAX_DURATION_MS + 1))));
    }
}
