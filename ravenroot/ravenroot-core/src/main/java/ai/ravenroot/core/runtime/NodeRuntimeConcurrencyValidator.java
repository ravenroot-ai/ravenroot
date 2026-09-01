package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Fail-first trusted-bound validation for {@code runtime.maxConcurrency}. */
public final class NodeRuntimeConcurrencyValidator {
    private final BehaviorRegistry behaviors;

    public NodeRuntimeConcurrencyValidator(BehaviorRegistry behaviors) {
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
    }

    public void validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        graph.nodes().forEach(this::validateNode);
    }

    private void validateNode(GraphNode node) {
        if (!NodeRuntimeMaxConcurrencyProperty.declaredBy(node.properties())) return;
        NodeTypeDescriptor descriptor = node.kind() == NodeKind.BEHAVIOR
                ? behaviors.descriptor(node.behavior()).orElse(null) : null;
        if (node.kind() != NodeKind.BEHAVIOR) {
            throw refuse(NodeRuntimeConcurrencyException.Reason.DECLARED_ON_NON_BEHAVIOR_NODE, node, null, null);
        }
        if (descriptor == null) {
            throw refuse(NodeRuntimeConcurrencyException.Reason.DECLARED_BY_UNCATALOGUED_BEHAVIOR,
                    node, null, null);
        }
        int declared;
        try {
            declared = NodeRuntimeMaxConcurrencyProperty.parse(
                    node.properties().get(NodeRuntimeMaxConcurrencyProperty.NAME));
        } catch (IllegalArgumentException invalid) {
            throw refuse(NodeRuntimeConcurrencyException.Reason.INVALID_VALUE, node, descriptor, null);
        }
        if (declared < 1) {
            throw refuse(NodeRuntimeConcurrencyException.Reason.INVALID_VALUE, node, descriptor, declared);
        }
        if (declared > descriptor.runtimeConcurrency().ceiling()) {
            throw refuse(NodeRuntimeConcurrencyException.Reason.EXCEEDS_TRUSTED_CEILING,
                    node, descriptor, declared);
        }
    }

    private static NodeRuntimeConcurrencyException refuse(NodeRuntimeConcurrencyException.Reason reason,
                                                           GraphNode node, NodeTypeDescriptor descriptor,
                                                           Integer declared) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("nodeId", node.id());
        detail.put("nodeKind", node.kind().name());
        if (node.behavior() != null) detail.put("behavior", node.behavior());
        if (declared != null) detail.put("declaredMaxConcurrency", declared);
        if (descriptor != null) {
            detail.put("defaultMaxConcurrency", descriptor.runtimeConcurrency().defaultValue());
            detail.put("maxConcurrencyCeiling", descriptor.runtimeConcurrency().ceiling());
        }
        return new NodeRuntimeConcurrencyException(reason, detail);
    }
}
