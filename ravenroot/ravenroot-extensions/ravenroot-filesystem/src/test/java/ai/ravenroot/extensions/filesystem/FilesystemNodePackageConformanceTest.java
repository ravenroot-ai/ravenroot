package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

class FilesystemNodePackageConformanceTest extends NodeBehaviorContract {
    @TempDir Path root;

    @Override protected NodePackage nodePackage() {
        return new FilesystemNodePackage((tenant, name) -> Optional.of(FilesystemTestSupport.profile(root)));
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return FilesystemTestSupport.configuration(descriptor.behavior(), Map.of());
    }
}
