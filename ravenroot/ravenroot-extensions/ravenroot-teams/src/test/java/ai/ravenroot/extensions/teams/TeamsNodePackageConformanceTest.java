package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

class TeamsNodePackageConformanceTest extends NodeBehaviorContract {
    @TempDir Path directory;
    @Override protected NodePackage nodePackage() {
        return TeamsTestSupport.nodePackage(directory.resolve("deliveries.db"));
    }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("teams", descriptor.behavior(),
                Map.of("teamsProfile", TeamsTestSupport.PROFILE));
    }
}
