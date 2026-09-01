package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.ExecutionEngines;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PekkoExecutionEngineTest {
    private static final ai.ravenroot.api.security.SecurityContext IDENTITY =
            new ai.ravenroot.api.security.SecurityContext("pekko-request", "tenant-a", "alice",
                    ai.ravenroot.api.security.PrincipalType.USER, "urn:ravenroot:test");

    @Test
    void wrapsAndExecutesPureJavaNodes() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-engine-test")) {
            var ref = engine.spawn("upper", (RavenNode) (message, context) ->
                    java.util.concurrent.CompletableFuture.completedFuture(
                            NodeResult.continueWith(message.payload().toString().toUpperCase())));

            var result = engine.send(ref, new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "upper", "raven", Map.of()))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("RAVEN", result.payload());
        }
    }

    @Test
    void isDiscoverableWithoutACompileTimeAdapterDependency() {
        assertTrue(ExecutionEngines.available().contains("pekko"));
        try (var engine = ExecutionEngines.create("pekko", "ravenroot-provider-test")) {
            assertEquals("apache-pekko", engine.id());
        }
    }
}
