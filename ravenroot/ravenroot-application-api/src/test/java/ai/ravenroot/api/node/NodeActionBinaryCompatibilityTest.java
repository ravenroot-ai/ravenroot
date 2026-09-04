package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeActionBinaryCompatibilityTest {
    @TempDir Path temporary;

    @Test
    void classCompiledAgainstOneArgumentInterfaceLinksThroughCancellationBridge() throws Exception {
        Path api = temporary.resolve("ai/ravenroot/api/node/NodeAction.java");
        Path legacy = temporary.resolve("legacy/LegacyAction.java");
        Files.createDirectories(api.getParent());
        Files.createDirectories(legacy.getParent());
        Files.writeString(api, """
                package ai.ravenroot.api.node;
                public interface NodeAction {
                    java.util.concurrent.CompletionStage<ai.ravenroot.api.execution.NodeResult> handle(
                        ai.ravenroot.api.execution.NodeMessage message);
                }
                """);
        Files.writeString(legacy, """
                package legacy;
                public final class LegacyAction implements ai.ravenroot.api.node.NodeAction {
                    public java.util.concurrent.CompletionStage<ai.ravenroot.api.execution.NodeResult> handle(
                            ai.ravenroot.api.execution.NodeMessage message) {
                        return java.util.concurrent.CompletableFuture.completedFuture(
                            ai.ravenroot.api.execution.NodeResult.continueWith("legacy:" + message.payload()));
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"),
                "-d", temporary.toString(), api.toString(), legacy.toString()) == 0);

        try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},
                NodeAction.class.getClassLoader())) {
            NodeAction action = (NodeAction) loader.loadClass("legacy.LegacyAction").getConstructor().newInstance();
            var never = new CancellationSignal() {
                @Override public boolean cancelled() { return false; }
                @Override public void onCancel(Runnable listener) { }
            };
            assertEquals("legacy:value", action.handle(message("value"), never)
                    .toCompletableFuture().join().payload());
        }
    }

    private static NodeMessage message(Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", "tenant", "subject", PrincipalType.WORKLOAD,
                "issuer"), id, id, id, id, Set.of(), "node", payload, Map.of());
    }
}
