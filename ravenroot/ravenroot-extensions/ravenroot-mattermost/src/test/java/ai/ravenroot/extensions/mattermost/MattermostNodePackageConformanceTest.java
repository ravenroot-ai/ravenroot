package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

class MattermostNodePackageConformanceTest extends NodeBehaviorContract {
    @TempDir Path directory;
    @Override protected NodePackage nodePackage() {
        return MattermostTestSupport.nodePackage(directory.resolve("deliveries.db"));
    }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("mattermost", descriptor.behavior(),
                Map.of("mattermostProfile", MattermostTestSupport.PROFILE));
    }
}
