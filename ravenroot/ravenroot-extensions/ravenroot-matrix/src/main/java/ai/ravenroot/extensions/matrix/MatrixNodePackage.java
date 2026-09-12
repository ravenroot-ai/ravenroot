package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.time.Clock;
import java.util.List;

/** Optional Matrix messaging package. */
public final class MatrixNodePackage implements NodePackage {
    private final MatrixRuntime runtime;
    private final List<NodeBehavior> behaviors;

    /** Creates a package backed by operator-provided Matrix configuration. */
    public MatrixNodePackage() {
        runtime = new MatrixRuntime(MatrixConfiguration::fromEnvironment);
        behaviors = behaviors(runtime);
    }
    MatrixNodePackage(MatrixConfiguration configuration, MatrixSyncStore store, Clock clock) {
        runtime = new MatrixRuntime(configuration, store, clock);
        behaviors = behaviors(runtime);
    }

    @Override public String id() { return MatrixConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }

    private static List<NodeBehavior> behaviors(MatrixRuntime runtime) {
        return List.of(new MatrixSyncSourceBehavior(runtime), new MatrixSendBehavior(runtime));
    }
}
