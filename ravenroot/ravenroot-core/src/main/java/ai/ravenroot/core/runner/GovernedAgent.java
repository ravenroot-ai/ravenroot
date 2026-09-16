package ai.ravenroot.core.runner;

import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.runner.AgentCommand;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeHandler;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Composes workspace access into the Agent node without changing unreferenced Agent execution. */
public final class GovernedAgent {
    private GovernedAgent() { }
    public static boolean isNamed(GraphNode node) {
        return node != null && "agent".equals(node.behavior())
                && !Objects.toString(node.properties().get("agentDefinition"), "").isBlank();
    }
    public static boolean usesWorkspace(GraphNode node) {
        return node != null && "agent".equals(node.behavior())
                && !Objects.toString(node.properties().get("workspaceRef"), "").isBlank();
    }
    public static NodeTypeDescriptor descriptor(NodeTypeDescriptor ordinary) {
        var properties = new ArrayList<NodePropertyDescriptor>();
        if (ordinary != null) for (var property : ordinary.properties()) {
            properties.add(new NodePropertyDescriptor(property.name(), property.displayName(), property.type(),
                    property.required(), property.description(), property.defaultValue(), property.allowedValues(),
                    property.adapterBinding(), PropertyCondition.blank("agentDefinition"),
                    property.required() ? PropertyCondition.blank("agentDefinition") : property.requiredWhen(),
                    property.minimumValue(), property.maximumValue(), property.maximumUtf8Bytes(),
                    property.maximumItems(), property.maximumItemUtf8Bytes()));
        }
        properties.add(NodePropertyDescriptor.optional("workspaceRef", "Workspace", NodePropertyType.WORKSPACE_REFERENCE,
                "An explicitly declared Workspace in this graph. Blank retains bounded conversational execution.", ""));
        properties.add(new NodePropertyDescriptor("agentDefinition", "Named Agent", NodePropertyType.STRING, false,
                "Approved versioned Agent identity, instructions, model and tools.", "", List.of(), false,
                null, PropertyCondition.present("workspaceRef")));
        properties.add(NodePropertyDescriptor.optionalBounded("agentVersion", "Agent version", NodePropertyType.INTEGER,
                "Exact immutable approved definition version.", "1", 1, Long.MAX_VALUE));
        var outcomes = new ArrayList<NodeOutcomeDescriptor>();
        if (ordinary != null) outcomes.addAll(ordinary.outcomes());
        for (String name : new TreeSet<>(AgentCommand.STANDARD_OUTCOMES)) {
            if (outcomes.stream().noneMatch(value -> value.name().equals(name)))
                outcomes.add(NodeOutcomeDescriptor.literal(name, "Governed Agent result."));
        }
        return new NodeTypeDescriptor("agent", "Agent", "AI", "Named bounded Agent with optional governed Workspace access.",
                "agent", true, properties, ordinary == null ? Set.of("ai", "agentic") : ordinary.capabilities(),
                NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER), AgentCommand.STANDARD_NAMES,
                outcomes, ordinary == null ? null : ordinary.runtimeConcurrency(), ordinary == null ? List.of() : ordinary.additionalProperties());
    }
    public static NodeHandler create(RunnerJobService service, GraphNode node) {
        return message -> {
            try { service.suspendAgent(message, node); }
            catch (RuntimeException suspension) { return CompletableFuture.failedFuture(suspension); }
            return CompletableFuture.failedFuture(new IllegalStateException("governed Agent did not park"));
        };
    }
}
