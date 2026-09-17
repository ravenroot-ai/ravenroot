package ai.ravenroot.core.runner;

import ai.ravenroot.api.catalog.*;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The visible graph resource controls lifecycle; Agent nodes perform the work. */
public final class WorkspaceBehavior implements NodeBehaviorFactory {
    public static final Set<String> COMMANDS = Set.of("open", "inspect", "checkpoint", "close", "abort");
    private final RunnerJobService service;
    public WorkspaceBehavior(RunnerJobService service) { this.service = java.util.Objects.requireNonNull(service); }
    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("workspace", "Workspace", "Resources",
                "Opens, inspects, checkpoints, closes or aborts an explicitly declared governed workspace.",
                "workspace", false, List.of(
                    NodePropertyDescriptor.required("workspaceProfile", "Workspace profile", NodePropertyType.STRING, "Approved immutable workspace policy."),
                    NodePropertyDescriptor.optionalBounded("workspaceVersion", "Profile version", NodePropertyType.INTEGER, "Exact approved version.", "1", 1, Long.MAX_VALUE),
                    new NodePropertyDescriptor("workspaceScope", "Workspace scope", NodePropertyType.STRING, false,
                            "Must agree with the approved profile.", "", List.of("EPHEMERAL", "PROCESS_INSTANCE", "NAMED")),
                    new NodePropertyDescriptor("runtimeLifecycle", "Runtime lifecycle", NodePropertyType.STRING, false,
                            "Must agree with the approved profile; independent of filesystem scope.", "", List.of("PER_INVOCATION", "PER_WORKSPACE")),
                    NodePropertyDescriptor.optional("runnerPool", "Runner pool", NodePropertyType.STRING, "Approved profile placement pool.", ""),
                    NodePropertyDescriptor.optional("runtimeProfile", "Runtime profile", NodePropertyType.STRING, "Approved profile image/runtime binding.", "")),
                Set.of("durable", "runner", "restart-safe"), NodeRuntimeNature.WORKER,
                Set.of(NodeRuntimeNature.WORKER), COMMANDS)
                .withOutcomes(List.of("ready", "inspected", "checkpointed", "closed", "aborted", "blocked")
                        .stream().map(name -> NodeOutcomeDescriptor.literal(name, "Workspace lifecycle result."))
                        .toArray(NodeOutcomeDescriptor[]::new));
    }
    @Override public NodeHandler create(GraphNode node) {
        return message -> {
            try { service.suspendWorkspace(message, node); }
            catch (RuntimeException suspension) { return CompletableFuture.failedFuture(suspension); }
            return CompletableFuture.failedFuture(new IllegalStateException("workspace lifecycle did not park"));
        };
    }
}
