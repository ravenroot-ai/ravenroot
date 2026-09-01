package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeActionDiagnostic;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class LogNodeBehaviorFactory implements NodeBehaviorFactory {
    private static final System.Logger LOGGER = System.getLogger("ai.ravenroot.node.log");

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("log", "Log", "Actions",
                "Writes an intentional workflow message to the server log and passes the payload through.",
                "handler", false, List.of(
                NodePropertyDescriptor.optional("message", "Message", NodePropertyType.TEXT,
                        "Supports {{payload}}, {{attributes.name}} and {{properties.name}} placeholders.",
                        "{{payload}}")), Set.of("deterministic", "side-effect"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The message was written and the payload passes through. The only outcome this "
                                + "node produces: an unresolvable placeholder fails the node instead."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        String template = NodeProperties.string(node, "message", "{{payload}}");
        return message -> {
            String rendered = NodeProperties.render(template, message, node);
            LOGGER.log(System.Logger.Level.INFO, "ravenroot_node node={0} execution={1} message={2}",
                    node.id(), message.executionId(), rendered);
            return CompletableFuture.completedFuture(new NodeResult("continue", message.payload(),
                    NodeProperties.attributes(message, "ravenroot.logged", true),
                    NodeActionDiagnostic.log(rendered)));
        };
    }
}
