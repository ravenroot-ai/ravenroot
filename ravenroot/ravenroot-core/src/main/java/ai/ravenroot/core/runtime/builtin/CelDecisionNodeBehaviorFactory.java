package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class CelDecisionNodeBehaviorFactory implements NodeBehaviorFactory {
    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("cel-decision", "CEL decision", "Control flow",
                "Routes the unchanged payload using the result of a checked CEL boolean expression.",
                "flow", false, List.of(
                NodePropertyDescriptor.required("expression", "CEL expression", NodePropertyType.CEL_EXPRESSION,
                        "Boolean expression evaluated against payload, attributes and properties."),
                NodePropertyDescriptor.optional("trueOutcome", "True outcome", NodePropertyType.STRING,
                        "Outgoing edge outcome selected for true.", "true"),
                NodePropertyDescriptor.optional("falseOutcome", "False outcome", NodePropertyType.STRING,
                        "Outgoing edge outcome selected for false.", "false")), Set.of("deterministic", "cel"))
                // Parameterized, not fixed: the author names these. Declaring the literals "true" and
                // "false" here would be right only for a node that left both properties alone, and
                // would state the wrong outcome for every node that did not.
                .withOutcomes(
                        NodeOutcomeDescriptor.fromProperty("trueOutcome",
                                "The expression evaluated to true."),
                        NodeOutcomeDescriptor.fromProperty("falseOutcome",
                                "The expression evaluated to anything other than true."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        Script script = CelTransformNodeBehaviorFactory.compile(node, NodeProperties.required(node, "expression"));
        String trueOutcome = NodeProperties.string(node, "trueOutcome", "true");
        String falseOutcome = NodeProperties.string(node, "falseOutcome", "false");
        return message -> {
            try {
                Boolean decision = script.execute(Boolean.class,
                        CelTransformNodeBehaviorFactory.bindings(message, node, "decision"));
                return CompletableFuture.completedFuture(new NodeResult(Boolean.TRUE.equals(decision)
                        ? trueOutcome : falseOutcome, message.payload(), message.attributes()));
            } catch (IllegalArgumentException absent) {
                return CompletableFuture.failedFuture(absent);
            } catch (ScriptException error) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "CEL decision failed at node " + node.id() + ": " + error.getMessage(), error));
            }
        };
    }
}
