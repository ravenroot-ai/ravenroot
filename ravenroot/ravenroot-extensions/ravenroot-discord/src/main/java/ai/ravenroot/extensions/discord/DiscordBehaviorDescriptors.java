package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class DiscordBehaviorDescriptors {
    static final String SEND = "discord.send";
    static final String INTERACTIONS = "discord.interactions";
    private DiscordBehaviorDescriptors() { }

    static NodeTypeDescriptor send() {
        List<NodePropertyDescriptor> properties = new ArrayList<>();
        properties.add(profile());
        properties.add(NodePropertyDescriptor.optional("channelId", "Channel", NodePropertyType.STRING,
                "Optional narrowing to one channel already allowed by the operator profile.", ""));
        properties.add(integer("requestTimeoutMs", "Request timeout"));
        properties.add(integer("maxContentChars", "Content limit"));
        properties.add(integer("maxAttachmentBytes", "Attachment byte limit"));
        properties.add(integer("maxAttachments", "Attachment count"));
        properties.add(integer("maxConcurrency", "Concurrency"));
        properties.add(integer("retries", "Rate-limit retries"));
        return new NodeTypeDescriptor(SEND, "Send Discord message", "Discord",
                "Sends one bounded discord.message.v1 payload through an operator-owned profile.",
                "actor", false, List.copyOf(properties), Set.of("network", "credential-reference", "side-effect"));
    }

    static NodeTypeDescriptor interactions() {
        return new NodeTypeDescriptor(INTERACTIONS, "Receive Discord slash command", "Discord",
                "Verifies and durably accepts guild slash commands relayed with the original signed body.",
                "source", false, List.of(profile()), Set.of("inbound-source", "network", "durable-ingress"));
    }

    static String profile(NodeConfiguration configuration) {
        String value = configuration.requiredProperty("discordProfile");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new DiscordException(DiscordException.Code.CONFIGURATION);
        return value;
    }

    private static NodePropertyDescriptor profile() {
        return NodePropertyDescriptor.required("discordProfile", "Discord profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; graph content cannot create provider authority.");
    }
    private static NodePropertyDescriptor integer(String name, String display) {
        return NodePropertyDescriptor.optional(name, display, NodePropertyType.INTEGER,
                "May only tighten the operator profile.", "");
    }
}
