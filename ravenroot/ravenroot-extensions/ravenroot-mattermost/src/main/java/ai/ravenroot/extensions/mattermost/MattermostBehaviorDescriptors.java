package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.List;
import java.util.Set;

final class MattermostBehaviorDescriptors {
    static final String SEND = "mattermost.send";
    static final String OUTGOING_WEBHOOK = "mattermost.outgoing-webhook";
    private MattermostBehaviorDescriptors() { }

    static NodeTypeDescriptor send() {
        return new NodeTypeDescriptor(SEND, "Send Mattermost message", "Mattermost",
                "Posts one bounded mattermost.message.v1 payload through an operator-owned profile.",
                "actor", false, List.of(profile(), optional("channelId", "Public channel", NodePropertyType.STRING),
                optional("requestTimeoutMs", "Request timeout", NodePropertyType.INTEGER),
                optional("maxTextChars", "Text limit", NodePropertyType.INTEGER),
                optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER),
                optional("retries", "Rate-limit retries", NodePropertyType.INTEGER)),
                Set.of("network", "credential-reference", "side-effect"));
    }
    static NodeTypeDescriptor outgoing() {
        return new NodeTypeDescriptor(OUTGOING_WEBHOOK, "Receive Mattermost outgoing webhook", "Mattermost",
                "Authenticates and durably accepts a Mattermost public-channel outgoing webhook.",
                "source", false, List.of(profile()), Set.of("inbound-source", "network", "durable-ingress"));
    }
    static String profile(NodeConfiguration configuration) {
        String value = configuration.requiredProperty("mattermostProfile");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new MattermostException(MattermostException.Code.CONFIGURATION);
        return value;
    }
    private static NodePropertyDescriptor profile() {
        return NodePropertyDescriptor.required("mattermostProfile", "Mattermost profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; graph content cannot create provider authority.");
    }
    private static NodePropertyDescriptor optional(String name, String display, NodePropertyType type) {
        return NodePropertyDescriptor.optional(name, display, type, "May only tighten the operator profile.", "");
    }
}
