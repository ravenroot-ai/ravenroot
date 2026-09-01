package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeActionDiagnostic;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogNodeBehaviorFactoryTest {
    @Test
    void emitsTheExactRenderedLogActionSeparatelyFromTheTraversalPayload() throws Exception {
        var node = new GraphNode("logger", NodeKind.BEHAVIOR, "log",
                Map.of("message", "Customer {{payload.customer}} password={{payload.password}}; done"));
        var handler = new LogNodeBehaviorFactory().create(node);
        var identity = new SecurityContext("request-log", "tenant-a", "tester", PrincipalType.USER,
                "urn:ravenroot:test");
        Map<String, Object> traversalPayload = Map.of("customer", "alice", "password", "hunter2");
        var message = new NodeMessage(identity, UUID.randomUUID(), UUID.randomUUID(), "logger",
                traversalPayload, Map.of());

        var result = handler.handle(message).toCompletableFuture().get();
        String output = PayloadJson.write(result.actionDiagnostic().output().value());

        assertSame(traversalPayload, result.payload(),
                "the log action is not a replacement for or inference from traversal output");
        assertEquals(NodeActionDiagnostic.Kind.LOG, result.actionDiagnostic().kind());
        assertTrue(output.contains("Customer alice"), output);
        assertTrue(output.contains("done"), output);
        assertTrue(output.contains(RuntimeActivityData.REDACTION_MARKER), output);
        assertFalse(output.contains("hunter2"), output);
        assertTrue(result.actionDiagnostic().output().redacted());
    }
}
