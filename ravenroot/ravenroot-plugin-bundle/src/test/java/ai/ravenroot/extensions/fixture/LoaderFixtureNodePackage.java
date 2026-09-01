package ai.ravenroot.extensions.fixture;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Test fixture only (PLAT-12): a real, fully working {@link NodePackage} -- unlike
 * {@code AllowedFixtureBehavior}, which exists only to be read as class file bytes -- for
 * {@code PluginBundleLoaderTest}'s green-path activation test and for proving the classloader's
 * parent-first/child-first policy against real instantiated behavior.
 */
public final class LoaderFixtureNodePackage implements NodePackage {

    @Override
    public String id() {
        return "ai.ravenroot.extensions.fixture";
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
        return List.of(new FixtureProbeBehavior());
    }

    private static final class FixtureProbeBehavior implements NodeBehavior {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("fixture.probe", "Fixture Probe", "Test", "Test fixture only",
                    "actor", false, List.of(), Set.of());
        }

        @Override
        public NodeAction create(ai.ravenroot.api.node.NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(null));
        }
    }
}
