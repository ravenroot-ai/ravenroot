package ai.ravenroot.core.runtime;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.NodeKind;

import java.util.Objects;

/** Runs behavior-owned cross-property and capability checks before traversal side effects exist. */
final class BehaviorCapabilityPreflight {
    private final BehaviorRegistry behaviors;

    BehaviorCapabilityPreflight(BehaviorRegistry behaviors) {
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
    }

    void validate(GraphDefinition graph) {
        validate(graph, node -> true);
    }

    void validate(GraphDefinition graph, java.util.function.Predicate<ai.ravenroot.core.graph.GraphNode> include) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(include, "include");
        graph.nodes().stream()
                .filter(node -> node.kind() == NodeKind.BEHAVIOR)
                .filter(include)
                .forEach(behaviors::validate);
    }
}
