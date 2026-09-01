package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class OpenApiClientNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() {
        return new NodePackage() {
            @Override public String id() { return "ai.ravenroot.extensions.openapi.client"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<ai.ravenroot.api.node.NodeBehavior> behaviors() {
                return List.of(new OpenApiCallNodeBehavior(name -> Optional.of(
                        OpenApiClientTestSupport.profile(Set.of("getPet"), 2))));
            }
        };
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("call", descriptor.behavior(),
                Map.of("apiProfile", "pets", "operationId", "getPet"));
    }
}
