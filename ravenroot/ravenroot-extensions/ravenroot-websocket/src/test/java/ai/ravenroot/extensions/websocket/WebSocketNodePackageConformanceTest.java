package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;

class WebSocketNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() {
        return new WebSocketNodePackage(WebSocketTestSupport.resolver(WebSocketTestSupport.profile(2, 8)));
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("socket", descriptor.behavior(), Map.of("websocketProfile", "events"));
    }
}
