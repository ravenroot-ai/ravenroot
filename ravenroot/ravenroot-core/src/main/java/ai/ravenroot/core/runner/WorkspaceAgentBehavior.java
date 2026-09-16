package ai.ravenroot.core.runner;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.runner.AgentCommand;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Declarative reference to an approved profile. No credentials, scripts, grants or host paths. */
public final class WorkspaceAgentBehavior implements NodeBehaviorFactory {
    private final RunnerJobService service;
    public WorkspaceAgentBehavior(RunnerJobService service) { this.service = java.util.Objects.requireNonNull(service); }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("workspace-agent", "Workspace agent", "Agents",
                "Runs an approved specialist on a process-scoped workspace and parks while its runner works.",
                "agent", true, List.of(
                NodePropertyDescriptor.required("agentDefinition", "Agent definition", NodePropertyType.STRING, "Approved definition name."),
                NodePropertyDescriptor.optionalBounded("agentVersion", "Definition version", NodePropertyType.INTEGER,
                        "Exact immutable version.", "1", 1, Integer.MAX_VALUE),
                NodePropertyDescriptor.required("runner", "Designated runner", NodePropertyType.STRING, "Approved runner identifier.")),
                Set.of("durable", "runner", "restart-safe"), NodeRuntimeNature.WORKER,
                Set.of(NodeRuntimeNature.WORKER), AgentCommand.STANDARD_NAMES)
                .withOutcomes(AgentCommand.STANDARD_OUTCOMES.stream().sorted()
                        .map(name -> NodeOutcomeDescriptor.literal(name, "Governed runner result."))
                        .toArray(NodeOutcomeDescriptor[]::new));
    }

    @Override public NodeHandler create(GraphNode node) {
        String name = java.util.Objects.requireNonNull(node.properties().get("agentDefinition")).toString();
        long version = Long.parseLong(node.properties().getOrDefault("agentVersion", "1").toString());
        String runner = java.util.Objects.requireNonNull(node.properties().get("runner")).toString();
        return message -> {
            try { service.suspend(message, name, version, runner); }
            catch (RuntimeException suspension) { return CompletableFuture.failedFuture(suspension); }
            return CompletableFuture.failedFuture(new IllegalStateException("runner job did not park"));
        };
    }
}
