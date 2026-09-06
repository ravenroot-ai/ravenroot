package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

class MatrixNodePackageConformanceTest extends NodeBehaviorContract {
    @TempDir Path directory;
    @Override protected NodePackage nodePackage() { return MatrixTestSupport.nodePackage(directory.resolve("sync.db")); }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("matrix", descriptor.behavior(),
                Map.of("matrixProfile", MatrixTestSupport.PROFILE));
    }
}
