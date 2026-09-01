package missingdep.fixture;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/**
 * Test fixture source only (PLAT-12), compiled at test time by PluginBundleLoaderTest via
 * javax.tools.JavaCompiler -- never by the normal Maven build -- specifically so this class and
 * MissingDepFixtureHelper never land on the test JVM's own classpath. If they did, the "missing
 * dependency" this fixture exists to prove would silently resolve through the parent classloader
 * instead of failing, since ai.ravenroot.extensions.fixture (an ordinary, already-compiled test
 * package) is NOT a reserved namespace and PluginClassLoader falls back to the parent for anything
 * it does not find in the bundle's own jar.
 */
public final class MissingDepNodePackage implements NodePackage {
    @Override
    public String id() {
        return "test.missingdep";
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
        // Forces resolution of MissingDepFixtureHelper here, not at class load: the bundle jar built
        // WITH this class present must activate cleanly, and the bundle jar built WITHOUT it must
        // fail exactly when this line runs.
        MissingDepFixtureHelper.touch();
        return List.of();
    }
}
