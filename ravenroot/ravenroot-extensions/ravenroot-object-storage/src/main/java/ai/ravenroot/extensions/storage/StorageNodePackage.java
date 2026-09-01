package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

public final class StorageNodePackage implements NodePackage {
    private final List<NodeBehavior> behaviors;

    public StorageNodePackage() {
        StorageRuntime runtime = new StorageRuntime(new EnvironmentStorageProfileResolver());
        behaviors = List.of(new ObjectGetNodeBehavior(runtime), new ObjectPutNodeBehavior(runtime));
    }

    StorageNodePackage(StorageProfileResolver profiles) {
        StorageRuntime runtime = new StorageRuntime(profiles);
        behaviors = List.of(new ObjectGetNodeBehavior(runtime), new ObjectPutNodeBehavior(runtime));
    }

    @Override public String id() { return "ai.ravenroot.extensions.storage"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
}
