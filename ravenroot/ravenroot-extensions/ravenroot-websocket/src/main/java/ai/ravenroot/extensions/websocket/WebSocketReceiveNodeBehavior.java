package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import java.util.List;
import java.util.Set;

/** Deployment-scoped, process-local receive source. Generic WebSocket frames have no durable identity or acknowledgement. */
public final class WebSocketReceiveNodeBehavior implements NodeBehavior, InboundSourceCapable {
    public static final String BEHAVIOR = "websocket.receive";
    private final WebSocketProfileResolver profiles;
    private final WebSocketAdmissionRegistry admission;

    public WebSocketReceiveNodeBehavior() {
        this(new EnvironmentWebSocketProfileResolver(), WebSocketAdmissionRegistry.global());
    }

    WebSocketReceiveNodeBehavior(WebSocketProfileResolver profiles) {
        this(profiles, new WebSocketAdmissionRegistry());
    }

    WebSocketReceiveNodeBehavior(WebSocketProfileResolver profiles, WebSocketAdmissionRegistry admission) {
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.admission = java.util.Objects.requireNonNull(admission);
    }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_WEBSOCKET);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Receive WebSocket frames", "WebSocket",
                "Starts a process-local, non-replayable WSS receive source; accepted frames start new "
                        + "traversals and never produce a correlated reply.",
                "actor", false, List.of(
                NodePropertyDescriptor.required("websocketProfile", "WebSocket profile", NodePropertyType.STRING,
                        "Opaque operator profile; origin, path and credentials never come from GraphML."),
                optional("maxMessageBytes", "Maximum message bytes"),
                optional("maxFragments", "Maximum fragments"),
                optional("timeoutMs", "Handshake deadline (ms)")),
                Set.of("network", "credential-reference", "inbound-source"));
    }

    private static NodePropertyDescriptor optional(String name, String label) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.INTEGER,
                "May only tighten the operator profile ceiling.", "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> java.util.concurrent.CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload()));
    }

    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }

    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        return new WebSocketReceiveSource(WebSocketSettings.compile(configuration, profiles), services, admission);
    }
}
