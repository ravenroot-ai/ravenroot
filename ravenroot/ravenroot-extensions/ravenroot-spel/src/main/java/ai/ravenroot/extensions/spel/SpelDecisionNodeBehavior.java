package ai.ravenroot.extensions.spel;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

final class SpelDecisionNodeBehavior implements NodeBehavior {
    private static final NodeTypeDescriptor DESCRIPTOR = new NodeTypeDescriptor(
            "spel.decision", "Restricted SpEL decision", "Control flow",
            "Routes a bounded canonical payload using an allowlisted Boolean SpEL expression.",
            "flow", false,
            List.of(
                    NodePropertyDescriptor.required("expression", "Expression", NodePropertyType.TEXT,
                            "Restricted SpEL expression that must return exactly Boolean."),
                    NodePropertyDescriptor.optional("trueOutcome", "True outcome", NodePropertyType.STRING,
                            "Outcome emitted for true.", "true"),
                    NodePropertyDescriptor.optional("falseOutcome", "False outcome", NodePropertyType.STRING,
                            "Outcome emitted for false.", "false")),
            Set.of("deterministic", "spel"))
            .withOutcomes(
                    NodeOutcomeDescriptor.fromProperty("trueOutcome", "Expression returned true."),
                    NodeOutcomeDescriptor.fromProperty("falseOutcome", "Expression returned false."));

    @Override
    public NodeTypeDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        RestrictedSpelExpression expression = RestrictedSpelExpression.compile(
                configuration.requiredProperty("expression"));
        String trueOutcome = configuration.property("trueOutcome", "true");
        String falseOutcome = configuration.property("falseOutcome", "false");
        Semaphore slots = new Semaphore(SpelBounds.PER_NODE_CONCURRENCY, true);
        return message -> {
            final Object input;
            try {
                input = CanonicalTree.input(message.payload());
            } catch (SpelNodeException rejected) {
                return CompletableFuture.failedFuture(rejected);
            }
            return SpelRuntime.evaluate(slots, () -> {
                Object result = expression.evaluate(input);
                if (!(result instanceof Boolean decision)) {
                    throw new SpelNodeException(SpelNodeException.Code.DECISION_NOT_BOOLEAN);
                }
                return decision;
            }).thenApply(decision -> new NodeResult(Boolean.TRUE.equals(decision) ? trueOutcome : falseOutcome,
                    input, message.attributes()));
        };
    }
}
