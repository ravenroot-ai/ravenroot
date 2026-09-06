package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.List;
import java.util.Set;

final class MatrixBehaviorDescriptors {
    static final String SEND = "matrix.send";
    static final String SYNC = "matrix.sync";
    private MatrixBehaviorDescriptors() { }

    static NodeTypeDescriptor send() {
        return new NodeTypeDescriptor(SEND, "Send Matrix message", "Matrix",
                "Sends one bounded matrix.message.v1 room message through an operator-owned profile.",
                "actor", false, List.of(profile(), optional("roomId", "Room", NodePropertyType.STRING),
                optional("requestTimeoutMs", "Request timeout", NodePropertyType.INTEGER),
                optional("maxTextChars", "Text limit", NodePropertyType.INTEGER),
                optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER)),
                Set.of("network", "credential-reference", "side-effect"));
    }

    static NodeTypeDescriptor sync() {
        return new NodeTypeDescriptor(SYNC, "Receive Matrix messages", "Matrix",
                "Polls Matrix /sync and durably accepts bounded authorized room timeline events.",
                "source", false, List.of(profile(), optional("pollTimeoutMs", "Poll timeout", NodePropertyType.INTEGER),
                optional("maxEventsPerSync", "Events per sync", NodePropertyType.INTEGER)),
                Set.of("inbound-source", "network", "durable-ingress", "credential-reference"));
    }

    static String profile(NodeConfiguration configuration) {
        String value = configuration.requiredProperty("matrixProfile");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new MatrixException(MatrixException.Code.CONFIGURATION);
        return value;
    }

    private static NodePropertyDescriptor profile() {
        return NodePropertyDescriptor.required("matrixProfile", "Matrix profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; graph content cannot create provider authority.");
    }
    private static NodePropertyDescriptor optional(String name, String display, NodePropertyType type) {
        return NodePropertyDescriptor.optional(name, display, type, "May only tighten the operator profile.", "");
    }
}
