package ai.ravenroot.extensions.github;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GithubBehaviorDescriptors {
    private GithubBehaviorDescriptors() { }

    static List<NodePropertyDescriptor> profile(boolean repeatable) {
        List<NodePropertyDescriptor> values = new ArrayList<>();
        values.add(NodePropertyDescriptor.required("githubProfile", "GitHub profile", ai.ravenroot.api.catalog.NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; authority never comes from graph content."));
        if (repeatable) values.add(ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(
                "Recovery repeats only the same content-bound operation and reconciles durable remote state first."));
        return List.copyOf(values);
    }

    static NodeTypeDescriptor descriptor(String behavior, String title, String description,
                                         boolean source, boolean sideEffect, boolean repeatable) {
        Set<String> capabilities = source ? Set.of("inbound-source", "network", "durable-ingress", "credential-reference")
                : sideEffect ? Set.of("network", "credential-reference", "side-effect")
                : Set.of("network", "credential-reference");
        return new NodeTypeDescriptor(behavior, title, "GitHub", description, source ? "source" : "actor",
                false, profile(repeatable), capabilities);
    }

    static String profile(ai.ravenroot.api.node.NodeConfiguration configuration) {
        String name = configuration.requiredProperty("githubProfile");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new GithubException(GithubException.Code.CONFIGURATION);
        return name;
    }
}
