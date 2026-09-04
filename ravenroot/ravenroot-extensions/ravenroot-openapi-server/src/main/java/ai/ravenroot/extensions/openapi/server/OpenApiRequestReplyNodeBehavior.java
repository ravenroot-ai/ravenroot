package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeDirective;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Deployment-scoped synchronous OpenAPI request/reply ingress anchor. */
public final class OpenApiRequestReplyNodeBehavior implements NodeBehavior, InboundSourceCapable {
    public static final String BEHAVIOR = "openapi.request-reply";
    private final Supplier<OpenApiServerConfiguration> configuration;
    private final OpenApiAdmissionRegistry admission;
    private final OpenApiResponseRegistry responses;

    OpenApiRequestReplyNodeBehavior(Supplier<OpenApiServerConfiguration> configuration) {
        this(configuration, new OpenApiAdmissionRegistry(), new OpenApiResponseRegistry());
    }

    OpenApiRequestReplyNodeBehavior(Supplier<OpenApiServerConfiguration> configuration,
                               OpenApiAdmissionRegistry admission,
                               OpenApiResponseRegistry responses) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "OpenAPI request/reply", "OpenAPI",
                "Waits for one declared, validated OpenAPI response from a graph traversal.",
                "actor", false, List.of(
                NodePropertyDescriptor.required("apiProfile", "API profile", NodePropertyType.STRING,
                        "Opaque operator profile; it fixes the specification, route and authorization."),
                NodePropertyDescriptor.optional("operations", "Operations", NodePropertyType.STRING,
                        "Comma-separated subset of operator-authorized operation ids.", ""),
                lower("maxRequestBytes", "Request bytes"), lower("maxIdempotencyBytes", "Idempotency bytes"),
                lower("maxResponseBytes", "Response bytes"), lower("deadlineMs", "Deadline"),
                lower("maxConcurrency", "Concurrency")),
                Set.of("inbound-source", "network", "request-reply"), null, Set.of(), Set.of("respond"))
                .withOutcomes(NodeOutcomeDescriptor.literal("responded",
                        "A declared response was accepted for the active HTTP exchange."));
    }

    private static NodePropertyDescriptor lower(String name, String label) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.INTEGER,
                "May only tighten the operator profile ceiling.", "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> {
            if (message.command().directive() != NodeDirective.APPLICATION) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload()));
            }
            if (!"respond".equals(message.command().name())) {
                return java.util.concurrent.CompletableFuture.failedFuture(OpenApiValues.invalid());
            }
            try {
                Object response = responses.complete(message.security().tenantId(), message.processInstanceId(),
                        message.traversalId(), message.nodeId(), message.payload());
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new NodeResult("responded", response, message.attributes()));
            } catch (RuntimeException rejected) {
                return java.util.concurrent.CompletableFuture.failedFuture(OpenApiValues.invalid());
            }
        };
    }

    @Override public InboundSource createSource(NodeConfiguration node, InboundSourceContext context) {
        OpenApiServerConfiguration packageConfiguration = configuration.get();
        OpenApiServerProfile profile = packageConfiguration.requireProfile(node.requiredProperty("apiProfile"));
        Set<String> operations = node.property("operations").map(OpenApiRequestReplyNodeBehavior::operations)
                .orElse(profile.allowedOperations());
        Settings settings = new Settings(profile, operations,
                lower(node, "maxRequestBytes", profile.maxRequestBytes()),
                lower(node, "maxIdempotencyBytes", profile.maxIdempotencyBytes()),
                lower(node, "maxResponseBytes", Math.toIntExact(Math.min(Integer.MAX_VALUE,
                        packageConfiguration.authority().maxResponseBytes()))),
                lower(node, "deadlineMs", profile.deadlineMs()),
                lower(node, "maxConcurrency", profile.maxConcurrency()));
        return new OpenApiRequestReplySource(packageConfiguration, settings, admission, responses);
    }

    private static Set<String> operations(String value) {
        List<String> parsed = Arrays.stream(value.split(",", -1)).map(String::trim)
                .peek(operation -> { if (!operation.matches("[A-Za-z0-9._-]{1,128}")) throw OpenApiValues.invalid(); })
                .toList();
        Set<String> result = Set.copyOf(parsed);
        if (result.isEmpty() || result.size() != parsed.size()) throw OpenApiValues.invalid();
        return result;
    }

    private static int lower(NodeConfiguration node, String name, int ceiling) {
        int value;
        try { value = Integer.parseInt(node.property(name, Integer.toString(ceiling))); }
        catch (NumberFormatException invalid) { throw OpenApiValues.invalid(); }
        if (value < 1 || value > ceiling) throw OpenApiValues.invalid();
        return value;
    }


    record Settings(OpenApiServerProfile profile, Set<String> operations,
                    int maxRequestBytes, int maxIdempotencyBytes, int maxResponseBytes,
                    int deadlineMs, int maxConcurrency) {
        Settings {
            Objects.requireNonNull(profile, "profile");
            operations = Set.copyOf(operations);
            if (!profile.allowedOperations().containsAll(operations)) throw OpenApiValues.invalid();
        }
    }
}
