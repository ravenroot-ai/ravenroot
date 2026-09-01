package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import java.util.List;

public final class WebSocketNodePackage implements NodePackage {
    private final List<NodeBehavior> behaviors;
    public WebSocketNodePackage() { this(new EnvironmentWebSocketProfileResolver()); }
    WebSocketNodePackage(WebSocketProfileResolver profiles) {
        WebSocketAdmissionRegistry admission = WebSocketAdmissionRegistry.global();
        behaviors = List.of(new WebSocketSendNodeBehavior(profiles, admission),
                new WebSocketReceiveNodeBehavior(profiles, admission));
    }
    @Override public String id() { return "ai.ravenroot.extensions.websocket"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
}
