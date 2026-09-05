package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

class StorageNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() {
        return new StorageNodePackage(name -> Optional.of(StorageTestSupport.profile(
                Set.of(StorageProfile.Operation.GET, StorageProfile.Operation.PUT,
                        StorageProfile.Operation.LIST, StorageProfile.Operation.DELETE,
                        StorageProfile.Operation.DELETE_VERSION), 2, 10)));
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("storage", descriptor.behavior(), Map.of("storageProfile", "assets",
                "key", "folder/object.txt"));
    }
}
