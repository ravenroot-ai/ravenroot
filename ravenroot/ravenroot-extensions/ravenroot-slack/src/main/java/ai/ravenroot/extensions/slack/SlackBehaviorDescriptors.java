package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.List;
import java.util.Set;

final class SlackBehaviorDescriptors {
    static final String POST_MESSAGE = "slack.post-message";
    static final String EVENTS = "slack.events";
    static final String COMMANDS = "slack.commands";
    private SlackBehaviorDescriptors() { }

    static NodeTypeDescriptor postMessage() {
        return new NodeTypeDescriptor(POST_MESSAGE, "Post Slack message", "Slack",
                "Posts one bounded slack.message.v1 payload through an operator-owned profile.", "actor", false,
                List.of(profile(), optional("channelId", "Channel", NodePropertyType.STRING),
                        optional("requestTimeoutMs", "Request timeout", NodePropertyType.INTEGER),
                        optional("maxTextChars", "Text limit", NodePropertyType.INTEGER),
                        optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER),
                        optional("retries", "Rate-limit retries", NodePropertyType.INTEGER)),
                Set.of("network", "credential-reference", "side-effect"));
    }
    static NodeTypeDescriptor events() {
        return source(EVENTS, "Receive Slack event", "Verifies and durably accepts Slack Events API callbacks.");
    }
    static NodeTypeDescriptor commands() {
        return source(COMMANDS, "Receive Slack command", "Verifies and durably accepts Slack slash commands.");
    }
    private static NodeTypeDescriptor source(String id, String display, String description) {
        return new NodeTypeDescriptor(id, display, "Slack", description, "source", false,
                List.of(profile()), Set.of("inbound-source", "network", "durable-ingress"));
    }
    static String profile(NodeConfiguration configuration) {
        String value = configuration.requiredProperty("slackProfile");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new SlackException(SlackException.Code.CONFIGURATION);
        return value;
    }
    private static NodePropertyDescriptor profile() {
        return NodePropertyDescriptor.required("slackProfile", "Slack profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; graph content cannot create provider authority.");
    }
    private static NodePropertyDescriptor optional(String name, String display, NodePropertyType type) {
        return NodePropertyDescriptor.optional(name, display, type, "May only tighten the operator profile.", "");
    }
}
