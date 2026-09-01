package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;

class OpenApiServerNodePackageConformanceTest extends NodeBehaviorContract {
    private final NodePackage nodePackage = new OpenApiServerNodePackage(
            () -> java.util.Optional.of(OpenApiServerTestSupport.configuration()));
    @Override protected NodePackage nodePackage() { return nodePackage; }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("openapi", descriptor.behavior(), Map.of("apiProfile", "orders"));
    }
}
