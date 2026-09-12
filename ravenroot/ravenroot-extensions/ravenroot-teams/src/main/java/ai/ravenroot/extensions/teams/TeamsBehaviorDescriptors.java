package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.List;
import java.util.Set;

final class TeamsBehaviorDescriptors {
    static final String SEND = "teams.send";
    static final String OUTGOING_WEBHOOK = "teams.outgoing-webhook";

    private TeamsBehaviorDescriptors() { }

    static NodeTypeDescriptor send() {
        return new NodeTypeDescriptor(SEND, "Send Teams message", "Teams",
                "Sends one bounded teams.message.v1 payload through an operator-owned Teams Workflow.",
                "actor", false,
                List.of(profile(), optional("channelId", "Channel", NodePropertyType.STRING),
                        optional("requestTimeoutMs", "Request timeout", NodePropertyType.INTEGER),
                        optional("maxTextChars", "Text limit", NodePropertyType.INTEGER),
                        optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER)),
                Set.of("network", "credential-reference", "side-effect"));
    }

    static NodeTypeDescriptor outgoingWebhook() {
        return new NodeTypeDescriptor(OUTGOING_WEBHOOK, "Receive Teams outgoing webhook", "Teams",
                "Verifies and durably accepts relayed Microsoft Teams Outgoing Webhook activities.",
                "source", false, List.of(profile()),
                Set.of("inbound-source", "network", "durable-ingress"));
    }

    static String profile(NodeConfiguration configuration) {
        String value = configuration.requiredProperty("teamsProfile");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new TeamsException(TeamsException.Code.CONFIGURATION);
        return value;
    }

    private static NodePropertyDescriptor profile() {
        return NodePropertyDescriptor.required("teamsProfile", "Teams profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; graph content cannot create provider authority.");
    }

    private static NodePropertyDescriptor optional(String name, String display, NodePropertyType type) {
        return NodePropertyDescriptor.optional(name, display, type, "May only tighten the operator profile.", "");
    }
}
