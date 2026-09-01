package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped server and CLI install third-party node types from an <em>operator's</em>
 * allowlist.
 *
 * <p>The security property under test is negative and is the reason the mechanism looks the way it
 * does: the only input is deployment configuration. No graph, payload or request can name a class,
 * and nothing is discovered by scanning — so a jar that is merely present contributes nothing until
 * an operator names it.</p>
 */
class NodePackageLoaderTest {

    @Test
    void anUnsetAllowlistInstallsNothing() {
        assertTrue(NodePackageLoader.fromCommaSeparated(null).isEmpty());
        assertTrue(NodePackageLoader.fromCommaSeparated("").isEmpty());
        assertTrue(NodePackageLoader.fromCommaSeparated("   ").isEmpty());
        assertTrue(NodePackageLoader.fromEnvironment(Map.of()).isEmpty(),
                "the shipped default is no third-party node packages");
    }

    /**
     * The whole mechanism: a package on the classpath is inert until named. {@link LoadableProbePackage}
     * is on this test's classpath throughout, and the first assertion is that its presence alone
     * installs nothing.
     */
    @Test
    void aPackageOnTheClasspathIsInertUntilAnOperatorNamesIt() {
        assertTrue(NodePackageLoader.fromEnvironment(Map.of()).isEmpty(),
                "classpath presence is not a decision anyone made about this package");

        var named = NodePackageLoader.fromEnvironment(Map.of(
                NodePackageLoader.ENVIRONMENT_VARIABLE, LoadableProbePackage.class.getName()));

        assertEquals(1, named.size());
        assertEquals("com.example.ravenroot.probe", named.getFirst().id());
    }

    @Test
    void severalPackagesLoadInTheOrderTheOperatorWrote() {
        var loaded = NodePackageLoader.fromCommaSeparated(
                " " + LoadableProbePackage.class.getName() + " , " + SecondProbePackage.class.getName() + " ");
        assertEquals(List.of("com.example.ravenroot.probe", "com.example.ravenroot.probe2"),
                loaded.stream().map(NodePackage::id).toList());
    }

    @Test
    void anUnknownClassAbortsStartupRatherThanBeingSkipped() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodePackageLoader.fromCommaSeparated("com.example.NotOnTheClasspath"));
        assertTrue(failure.getMessage().contains("com.example.NotOnTheClasspath"), failure.getMessage());
        assertTrue(failure.getMessage().contains(NodePackageLoader.ENVIRONMENT_VARIABLE),
                "the diagnostic must name the setting the operator has to fix: " + failure.getMessage());
    }

    @Test
    void aClassThatIsNotANodePackageIsRefused() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodePackageLoader.fromCommaSeparated(String.class.getName()));
        assertTrue(failure.getMessage().contains(NodePackage.class.getName()), failure.getMessage());
    }

    @Test
    void aPackageWithoutANoArgumentConstructorIsRefused() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodePackageLoader.fromCommaSeparated(NeedsArgumentsPackage.class.getName()));
        assertTrue(failure.getMessage().contains("no-argument constructor"), failure.getMessage());
    }

    /** End to end: an operator's allowlist entry becomes a catalogued, schema-validated node type. */
    @Test
    void anOperatorNamedPackageBecomesACataloguedNodeType() {
        var registry = NodePackages.registerAll(new BehaviorRegistry(),
                NodePackageLoader.fromEnvironment(Map.of(
                        NodePackageLoader.ENVIRONMENT_VARIABLE, LoadableProbePackage.class.getName())));

        NodeTypeDescriptor descriptor = registry.descriptor("loadable-probe").orElseThrow();
        assertEquals("Examples", descriptor.category());
        assertTrue(registry.descriptors().stream().anyMatch(entry -> entry.behavior().equals("loadable-probe")),
                "the node type must appear in the catalog the editor is served");
    }

    // ------------------------------------------------------------------------ fixtures

    public static final class LoadableProbePackage implements NodePackage {
        @Override
        public String id() {
            return "com.example.ravenroot.probe";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public String sdkContract() {
            return NodeSdk.CONTRACT;
        }

        @Override
        public List<NodeBehavior> behaviors() {
            return List.of(probe("loadable-probe"));
        }
    }

    public static final class SecondProbePackage implements NodePackage {
        @Override
        public String id() {
            return "com.example.ravenroot.probe2";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public String sdkContract() {
            return NodeSdk.CONTRACT;
        }

        @Override
        public List<NodeBehavior> behaviors() {
            return List.of(probe("loadable-probe-2"));
        }
    }

    public static final class NeedsArgumentsPackage implements NodePackage {
        @SuppressWarnings("unused")
        public NeedsArgumentsPackage(String required) {
        }

        @Override
        public String id() {
            return "com.example.ravenroot.needs-arguments";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public String sdkContract() {
            return NodeSdk.CONTRACT;
        }

        @Override
        public List<NodeBehavior> behaviors() {
            return List.of();
        }
    }

    private static NodeBehavior probe(String name) {
        return new NodeBehavior() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(name, "Probe", "Examples", "Test probe.",
                        "actor", false, List.of(), Set.of("deterministic"));
            }

            @Override
            public NodeAction create(NodeConfiguration configuration) {
                return message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload()));
            }
        };
    }
}
