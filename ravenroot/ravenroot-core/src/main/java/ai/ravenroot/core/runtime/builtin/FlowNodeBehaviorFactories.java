package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.flow.FlowHandle;
import ai.ravenroot.api.flow.FlowInvocationCapability;
import ai.ravenroot.api.flow.FlowInvocationResult;
import ai.ravenroot.api.flow.FlowTarget;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Catalog factories for version-pinned intergraph invocation. */
public final class FlowNodeBehaviorFactories {
    private static final long MAX_DEADLINE_MS = Duration.ofDays(7).toMillis();

    private FlowNodeBehaviorFactories() {}

    public static List<NodeBehaviorFactory> all(FlowInvocationCapability capability) {
        return List.of(new Call(capability), new Start(capability), new Await(capability));
    }

    private abstract static class Base implements NodeBehaviorFactory {
        final FlowInvocationCapability capability;
        Base(FlowInvocationCapability capability) { this.capability = capability; }

        FlowTarget target(GraphNode node) {
            return new FlowTarget(DeploymentId.of(NodeProperties.required(node, "deploymentId")),
                    NodeProperties.number(node, "version", -1));
        }

        Duration deadline(GraphNode node) {
            long value = NodeProperties.number(node, "deadlineMs", 60_000);
            if (value < 1 || value > MAX_DEADLINE_MS) {
                throw new IllegalArgumentException("Node " + node.id() + " deadlineMs must be 1.."
                        + MAX_DEADLINE_MS);
            }
            return Duration.ofMillis(value);
        }

        List<NodePropertyDescriptor> targetProperties() {
            return List.of(
                    NodePropertyDescriptor.required("deploymentId", "Deployment", NodePropertyType.STRING,
                            "Registered deployment identifier. No bundle is installed by this node."),
                    NodePropertyDescriptor.required("version", "Version", NodePropertyType.INTEGER,
                            "Exact immutable target version; latest-version lookup is intentionally unsupported."),
                    NodePropertyDescriptor.optionalBounded("deadlineMs", "Deadline (ms)",
                            NodePropertyType.INTEGER, "Bounded child deadline including queue and execution time.",
                            "60000", 1, MAX_DEADLINE_MS));
        }

        NodeResult result(FlowInvocationResult result) {
            String outcome = result.status().name().toLowerCase(java.util.Locale.ROOT);
            return new NodeResult(outcome,
                    result.status() == ai.ravenroot.api.flow.FlowInvocationStatus.COMPLETED
                            ? result.output() : result.failurePayload(), Map.of());
        }

        <T> CompletionStage<T> detached(CompletionStage<T> stage) {
            return stage.handleAsync((value, failure) -> {
                if (failure == null) return value;
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                throw new CompletionException(cause);
            });
        }

        NodeTypeDescriptor outcomes(NodeTypeDescriptor descriptor) {
            return descriptor.withOutcomes(
                    NodeOutcomeDescriptor.literal("completed", "The pinned child completed."),
                    NodeOutcomeDescriptor.literal("failed", "The child failed without an output."),
                    NodeOutcomeDescriptor.literal("cancelled", "The child was cancelled."),
                    NodeOutcomeDescriptor.literal("deadline_exceeded", "The child exceeded its deadline."),
                    NodeOutcomeDescriptor.literal("orphaned", "Cancellation could not be confirmed."),
                    NodeOutcomeDescriptor.literal("ambiguous", "Recovery could not prove a safe outcome."));
        }
    }

    private static final class Call extends Base {
        Call(FlowInvocationCapability capability) { super(capability); }
        public NodeTypeDescriptor descriptor() {
            return outcomes(new NodeTypeDescriptor("call-flow", "Call flow", "Control flow",
                    "Starts a fresh exact-version child and waits for its bounded terminal output.",
                    "flow", false, targetProperties(), Set.of("durable", "intergraph", "version-pinned")));
        }
        public void validate(GraphNode node) { target(node); deadline(node); }
        public NodeHandler create(GraphNode node) {
            FlowTarget target = target(node); Duration deadline = deadline(node);
            return message -> detached(capability.invoke(message, target, message.payload(), deadline))
                    .thenApply(this::result);
        }
    }

    private static final class Start extends Base {
        Start(FlowInvocationCapability capability) { super(capability); }
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("start-flow", "Start flow", "Control flow",
                    "Starts a fresh exact-version child and returns an opaque runtime-issued handle.",
                    "flow", false, targetProperties(), Set.of("durable", "intergraph", "version-pinned"))
                    .withOutcomes(NodeOutcomeDescriptor.literal("started", "The child intent and launch were recorded."));
        }
        public void validate(GraphNode node) { target(node); deadline(node); }
        public NodeHandler create(GraphNode node) {
            FlowTarget target = target(node); Duration deadline = deadline(node);
            return message -> detached(capability.start(message, target, message.payload(), deadline))
                    .thenApply(handle -> new NodeResult("started", Map.of("flowHandle", handle.toString()), Map.of()));
        }
    }

    private static final class Await extends Base {
        Await(FlowInvocationCapability capability) { super(capability); }
        public NodeTypeDescriptor descriptor() {
            return outcomes(new NodeTypeDescriptor("await-flow", "Await flow", "Control flow",
                    "Waits for a child referenced by an opaque handle returned by start-flow.",
                    "flow", false, List.of(), Set.of("durable", "intergraph", "worker-free")));
        }
        public NodeHandler create(GraphNode node) {
            return message -> detached(capability.await(message, handle(message.payload()))).thenApply(this::result);
        }
        private FlowHandle handle(Object payload) {
            Object value = payload instanceof Map<?, ?> map ? map.get("flowHandle") : payload;
            if (!(value instanceof String text)) throw new IllegalArgumentException("await-flow requires flowHandle");
            return FlowHandle.parse(text);
        }
    }
}
