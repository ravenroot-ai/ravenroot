package com.example.ravenroot.nodes;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A minimal third-party node: it prefixes the incoming payload with a configured greeting.
 *
 * <p>Trivial on purpose. What this file is meant to demonstrate is not the transformation but the
 * five things every node package has to get right, each marked below: declare the schema, read
 * configuration once, fail through the stage, keep out of the reserved namespace, and declare
 * capabilities truthfully.</p>
 */
public final class GreetingNodeBehavior implements NodeBehavior {

    /** The graph-facing name. This is what a graph's {@code behavior} attribute must say. */
    public static final String BEHAVIOR = "example-greeting";

    private static final String GREETING = "greeting";
    private static final String SEPARATOR = "separator";

    /**
     * (1) Declare the schema.
     *
     * <p>Every property this behavior reads is declared here, which is what puts it under the
     * runtime's validation: type-checked, required-ness enforced, allowed values enforced, all
     * before any node is spawned. A property read but not declared is one the graph can set to
     * anything.</p>
     *
     * <p>Built from constants and allocated fresh each call, so the descriptor is equal across calls
     * — the conformance contract requires that, because the runtime reads it at registration, at
     * validation and when serving the catalog to the editor.</p>
     */
    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(
                BEHAVIOR,
                "Greeting",
                "Examples",
                "Prefixes the incoming payload with a configured greeting.",
                "actor",
                false,
                List.of(
                        NodePropertyDescriptor.required(GREETING, "Greeting", NodePropertyType.STRING,
                                "Text placed before the payload."),
                        NodePropertyDescriptor.optional(SEPARATOR, "Separator", NodePropertyType.STRING,
                                "Placed between the greeting and the payload.", ", ")),
                // (5) Declare capabilities truthfully. These are read by the runtime, not merely
                // displayed: a behavior declaring a generative capability has its output marked with
                // a machine-readable synthetic-provenance marker. This node generates nothing and
                // reaches nothing, so it declares neither.
                Set.of("deterministic"));
    }

    /**
     * (2) Read configuration once, here.
     *
     * <p>This runs while the graph is being built; the returned action runs on every message. Work
     * that depends only on configuration — parsing, validation, precomputation — belongs on this
     * side of the boundary.</p>
     */
    @Override
    public NodeAction create(NodeConfiguration configuration) {
        String greeting = configuration.requiredProperty(GREETING);
        String separator = configuration.property(SEPARATOR, ", ");

        return message -> {
            try {
                String result = greeting + separator + message.payload();
                return CompletableFuture.completedFuture(new NodeResult("continue", result,
                        // (4) Stay out of the reserved 'ravenroot.' namespace. Attributes written
                        // there are the runtime's own operative state and are discarded unread, so
                        // writing one is not an attack but is silently ineffective.
                        Map.of("example.greeted", true)));
            } catch (RuntimeException failure) {
                // (3) Fail through the stage, never by throwing. The traversal's failure event, its
                // state transition and its join release are all attached to the returned stage; a
                // synchronous throw can escape that accounting, and whether it does depends on which
                // engine adapter is installed.
                return CompletableFuture.failedFuture(failure);
            }
        };
    }
}
