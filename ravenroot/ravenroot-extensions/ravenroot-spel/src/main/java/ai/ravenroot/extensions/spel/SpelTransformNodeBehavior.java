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

final class SpelTransformNodeBehavior implements NodeBehavior {
    private static final NodeTypeDescriptor DESCRIPTOR = new NodeTypeDescriptor(
            "spel.transform", "Restricted SpEL transform", "Transformations",
            "Evaluates an allowlisted SpEL expression over a bounded canonical payload tree.",
            "flow", false,
            List.of(NodePropertyDescriptor.required("expression", "Expression", NodePropertyType.TEXT,
                    "Restricted SpEL expression; its bounded canonical result becomes the payload.")),
            Set.of("deterministic", "spel"))
            .withOutcomes(NodeOutcomeDescriptor.literal("continue", "The bounded expression result."));

    @Override
    public NodeTypeDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        RestrictedSpelExpression expression = RestrictedSpelExpression.compile(
                configuration.requiredProperty("expression"));
        Semaphore slots = new Semaphore(SpelBounds.PER_NODE_CONCURRENCY, true);
        return message -> {
            final Object input;
            try {
                input = CanonicalTree.input(message.payload());
            } catch (SpelNodeException rejected) {
                return CompletableFuture.failedFuture(rejected);
            }
            return SpelRuntime.evaluate(slots, () -> CanonicalTree.result(expression.evaluate(input)))
                    .thenApply(value -> new NodeResult("continue", value, message.attributes()));
        };
    }
}
