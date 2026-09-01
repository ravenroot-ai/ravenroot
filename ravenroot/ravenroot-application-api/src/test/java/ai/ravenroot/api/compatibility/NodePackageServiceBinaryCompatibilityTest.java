package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves an SDK /1 package compiled before service methods existed still links and executes. */
class NodePackageServiceBinaryCompatibilityTest {

    @Test
    void previouslyCompiledNodePackageUsesTheSdkTwoDefaultBridges(@TempDir Path workspace) throws Exception {
        Path sources = Files.createDirectories(workspace.resolve("sources"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path api = Files.createDirectories(sources.resolve("ai/ravenroot/api/node"));
        Path fixture = Files.createDirectories(sources.resolve("fixture"));
        Files.writeString(api.resolve("NodePackage.java"), """
                package ai.ravenroot.api.node;
                public interface NodePackage {
                    String id(); String version(); String sdkContract();
                    java.util.List<NodeBehavior> behaviors();
                }
                """);
        Files.writeString(api.resolve("NodeBehavior.java"), """
                package ai.ravenroot.api.node;
                public interface NodeBehavior {
                    ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor();
                    NodeAction create(NodeConfiguration configuration);
                }
                """);
        Files.writeString(api.resolve("InboundSourceCapable.java"), """
                package ai.ravenroot.api.node;
                public interface InboundSourceCapable {
                    ai.ravenroot.api.deployment.InboundSource createSource(NodeConfiguration configuration,
                        ai.ravenroot.api.deployment.InboundSourceContext context);
                }
                """);
        Files.writeString(fixture.resolve("LegacyNodePackage.java"), """
                package fixture;
                public final class LegacyNodePackage implements ai.ravenroot.api.node.NodePackage {
                    public LegacyNodePackage() {}
                    public String id() { return "test.precompiled"; }
                    public String version() { return "1"; }
                    public String sdkContract() { return "ravenroot.node-sdk/1"; }
                    public java.util.List<ai.ravenroot.api.node.NodeBehavior> behaviors() {
                        return java.util.List.of(new LegacyBehavior());
                    }
                    public static final class LegacyBehavior implements ai.ravenroot.api.node.NodeBehavior {
                        public ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor() {
                            return new ai.ravenroot.api.catalog.NodeTypeDescriptor("legacy-probe", "Legacy",
                                "Test", "", "actor", false, java.util.List.of(), java.util.Set.of());
                        }
                        public ai.ravenroot.api.node.NodeAction create(
                                ai.ravenroot.api.node.NodeConfiguration configuration) {
                            return message -> java.util.concurrent.CompletableFuture.completedFuture(
                                ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
                        }
                    }
                    public static final class LegacySourceBehavior implements ai.ravenroot.api.node.NodeBehavior,
                            ai.ravenroot.api.node.InboundSourceCapable {
                        public ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor() {
                            return new ai.ravenroot.api.catalog.NodeTypeDescriptor("legacy-source", "Legacy source",
                                "Test", "", "source", false, java.util.List.of(), java.util.Set.of());
                        }
                        public ai.ravenroot.api.node.NodeAction create(
                                ai.ravenroot.api.node.NodeConfiguration configuration) {
                            return message -> java.util.concurrent.CompletableFuture.completedFuture(
                                ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
                        }
                        public ai.ravenroot.api.deployment.InboundSource createSource(
                                ai.ravenroot.api.node.NodeConfiguration configuration,
                                ai.ravenroot.api.deployment.InboundSourceContext context) {
                            return new ai.ravenroot.api.deployment.InboundSource() {
                                public java.util.concurrent.CompletionStage<Void> start(
                                        ai.ravenroot.api.deployment.InboundSourceContext delivered) {
                                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                                }
                                public java.util.concurrent.CompletionStage<Void> stop() {
                                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                                }
                            };
                        }
                    }
                }
                """);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int status = compiler.run(null, null, null, "--release", "21", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(),
                api.resolve("NodePackage.java").toString(), api.resolve("NodeBehavior.java").toString(),
                api.resolve("InboundSourceCapable.java").toString(),
                fixture.resolve("LegacyNodePackage.java").toString());
        assertEquals(0, status);
        // The fixture is now already compiled. Remove its private snapshots so linkage must resolve
        // the current SDK interfaces from the parent loader.
        Files.delete(classes.resolve("ai/ravenroot/api/node/NodePackage.class"));
        Files.delete(classes.resolve("ai/ravenroot/api/node/NodeBehavior.class"));
        Files.delete(classes.resolve("ai/ravenroot/api/node/InboundSourceCapable.class"));

        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                NodePackage.class.getClassLoader())) {
            NodePackage legacy = (NodePackage) loader.loadClass("fixture.LegacyNodePackage")
                    .getConstructor().newInstance();
            assertEquals(NodeSdk.LEGACY_CONTRACT, legacy.sdkContract());
            assertTrue(NodeSdk.supports(legacy.sdkContract()));
            NodeBehavior behavior = legacy.behaviors().get(0);
            assertTrue(behavior.requiredServices().isEmpty(), "new default method must link on old bytecode");
            assertNotNull(behavior.create(new NodeConfiguration("n", "legacy-probe", Map.of()),
                    NodePackageServices.unavailable()), "SDK /2 service overload must bridge to legacy create");
            InboundSourceCapable source = (InboundSourceCapable) loader
                    .loadClass("fixture.LegacyNodePackage$LegacySourceBehavior")
                    .getConstructor().newInstance();
            assertNotNull(source.createSource(new NodeConfiguration("s", "legacy-source", Map.of()), null,
                    NodePackageServices.unavailable()),
                    "SDK /2 source service overload must bridge to legacy createSource");
        }
    }
}
