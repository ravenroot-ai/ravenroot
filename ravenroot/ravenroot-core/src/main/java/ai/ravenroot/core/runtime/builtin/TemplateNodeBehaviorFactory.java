package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class TemplateNodeBehaviorFactory implements NodeBehaviorFactory {
    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("template", "Template", "Transformations",
                "Builds a text output from the incoming payload, attributes and node properties.",
                "flow", false, List.of(NodePropertyDescriptor.required("template", "Template",
                NodePropertyType.TEXT, "Text with {{payload}} and optional attribute/property placeholders.")),
                Set.of("deterministic"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The rendered text becomes the outgoing payload. The only outcome this node "
                                + "produces: an unresolvable placeholder fails the node instead."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        String template = NodeProperties.required(node, "template");
        return message -> CompletableFuture.completedFuture(new NodeResult("continue",
                NodeProperties.render(template, message, node), message.attributes()));
    }
}
