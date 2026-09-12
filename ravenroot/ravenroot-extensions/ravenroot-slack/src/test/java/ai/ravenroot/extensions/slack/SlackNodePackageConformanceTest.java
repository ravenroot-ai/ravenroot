package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

class SlackNodePackageConformanceTest extends NodeBehaviorContract {
    @TempDir Path directory;
    @Override protected NodePackage nodePackage() { return SlackTestSupport.nodePackage(directory.resolve("deliveries.db")); }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("slack", descriptor.behavior(), Map.of("slackProfile", SlackTestSupport.PROFILE));
    }
}
