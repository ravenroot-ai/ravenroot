package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Stable core descriptor and fail-closed runtime for a durable human decision. */
final class HumanTaskNodeBehaviorFactory implements NodeBehaviorFactory {
    static final String RESPONSE_CONTENT_TYPE = "application/vnd.ravenroot.payload+json";

    private final HumanTaskService tasks;
    private final HumanTaskPolicy policy;

    HumanTaskNodeBehaviorFactory(HumanTaskService tasks) {
        this(tasks, HumanTaskPolicy.DEFAULTS);
    }

    HumanTaskNodeBehaviorFactory(HumanTaskService tasks, HumanTaskPolicy policy) {
        this.tasks = tasks;
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("human-task", "Human task", "Human workflow",
                "Creates durable, tenant-scoped work for a person and resumes from the pinned graph version.",
                "flow", false, List.of(
                NodePropertyDescriptor.boundedText("title", "Title", NodePropertyType.STRING, true,
                        "Static bounded title shown in the human-task inbox. Payload interpolation is not supported.",
                        "", policy.maxTitleUtf8Bytes(), 0, 0),
                NodePropertyDescriptor.boundedText("description", "Description", NodePropertyType.TEXT, false,
                        "Static bounded instructions shown in the inbox. Payload interpolation is not supported.",
                        "", policy.maxDescriptionUtf8Bytes(), 0, 0),
                NodePropertyDescriptor.optional("responseContentType", "Response media type",
                        NodePropertyType.STRING, "Exact media type required for a resolved response.",
                        RESPONSE_CONTENT_TYPE),
                NodePropertyDescriptor.boundedText("responseSchema", "Response schema",
                        NodePropertyType.STRING, false, "Bounded response schema identifier.",
                        "ravenroot.human-task.response", policy.maxResponseSchemaUtf8Bytes(), 0, 0),
                NodePropertyDescriptor.optional("responseSchemaVersion", "Response schema version",
                        NodePropertyType.STRING, "Exact response schema version required at resolution.", "1"),
                choice("responseKind", "Response kind", "Required top-level response shape.",
                        PayloadKind.MAP.name(), "SCALAR", "LIST", "MAP"),
                NodePropertyDescriptor.optionalBounded("maxResponseBytes", "Maximum response bytes",
                        NodePropertyType.INTEGER, "Inclusive encoded-envelope byte bound owned by the server.",
                        Integer.toString(policy.defaultResponseBytes()), 1, policy.maxResponseBytes()),
                NodePropertyDescriptor.boundedText("authorizedRoles", "Authorized roles", NodePropertyType.TEXT,
                        false, "Comma-separated roles; every listed role is required.", "", 0,
                        policy.maxAuthorizationTokens(), policy.maxAuthorizationTokenUtf8Bytes()),
                NodePropertyDescriptor.boundedText("authorizedScopes", "Authorized scopes", NodePropertyType.TEXT,
                        false, "Comma-separated scopes; every listed scope is required.", "", 0,
                        policy.maxAuthorizationTokens(), policy.maxAuthorizationTokenUtf8Bytes()),
                NodePropertyDescriptor.optionalBounded("escalateAfterSeconds", "Escalate after (seconds)",
                        NodePropertyType.INTEGER, "Zero disables escalation; otherwise must be earlier than expiry.",
                        Long.toString(policy.defaultEscalationSeconds()), 0, policy.maxEscalationSeconds()),
                NodePropertyDescriptor.optionalBounded("expiresAfterSeconds", "Expire after (seconds)",
                        NodePropertyType.INTEGER, "Durable expiry delay bounded by server policy.",
                        Long.toString(policy.defaultExpirySeconds()), 1, policy.maxExpirySeconds()),
                choice("correlationSource", "Correlation source",
                        "Deterministic correlation source; human tasks use their task ID.", "task-id", "task-id"),
                choice("deduplicationSource", "Deduplication source",
                        "Deterministic deduplication source; human tasks use the node attempt ID.",
                        "attempt-id", "attempt-id"),
                NodePropertyDescriptor.optional("resolvedOutcome", "Resolved outcome", NodePropertyType.STRING,
                        "Outcome selected after a valid response.", "resolved"),
                NodePropertyDescriptor.optional("deniedOutcome", "Denied outcome", NodePropertyType.STRING,
                        "Outcome selected after denial.", "denied"),
                NodePropertyDescriptor.optional("expiredOutcome", "Expired outcome", NodePropertyType.STRING,
                        "Outcome selected after durable expiry.", "expired"),
                NodePropertyDescriptor.optional("cancelledOutcome", "Cancelled outcome", NodePropertyType.STRING,
                        "Outcome selected after cancellation.", "cancelled")),
                Set.of("durable", "human-task", "restart-safe", "bounded-metadata"))
                .withOutcomes(
                        NodeOutcomeDescriptor.fromProperty("resolvedOutcome", "A responder supplied a valid response."),
                        NodeOutcomeDescriptor.fromProperty("deniedOutcome", "An authorized responder denied the task."),
                        NodeOutcomeDescriptor.fromProperty("expiredOutcome", "The durable expiry timer fired."),
                        NodeOutcomeDescriptor.fromProperty("cancelledOutcome", "The requester or a responder cancelled the task."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        HumanTaskDefinition definition = definition(node);
        return message -> {
            if (tasks == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "human-task requires durable human-task, handler, timer, and journal capabilities"));
            }
            var result = tasks.suspend(message, definition);
            if (!result.created()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "human-task suspension was refused: " + result.code()));
            }
            return CompletableFuture.failedFuture(new DurableHumanTaskSuspension(result.task().request().taskId()));
        };
    }

    private HumanTaskDefinition definition(GraphNode node) {
        String title = bounded(NodeProperties.required(node, "title"), "title",
                policy.maxTitleUtf8Bytes(), false, node);
        String description = bounded(NodeProperties.string(node, "description", ""), "description",
                policy.maxDescriptionUtf8Bytes(), true, node);
        int maxBytes = Math.toIntExact(NodeProperties.number(node, "maxResponseBytes",
                policy.defaultResponseBytes()));
        if (maxBytes < 1 || maxBytes > policy.maxResponseBytes()
                || maxBytes > policy.decisionBodyMaxBytes()) {
            throw invalid(node, "maxResponseBytes", "must be between 1 and " + policy.maxResponseBytes());
        }
        long expirySeconds = NodeProperties.number(node, "expiresAfterSeconds", policy.defaultExpirySeconds());
        long escalationSeconds = NodeProperties.number(node, "escalateAfterSeconds",
                policy.defaultEscalationSeconds());
        if (expirySeconds < 1 || expirySeconds > policy.maxExpirySeconds()) {
            throw invalid(node, "expiresAfterSeconds", "must be between 1 and " + policy.maxExpirySeconds());
        }
        if (escalationSeconds < 0 || escalationSeconds > policy.maxEscalationSeconds()
                || escalationSeconds >= expirySeconds) {
            throw invalid(node, "escalateAfterSeconds", "must be zero or earlier than expiry");
        }
        PayloadKind kind;
        try {
            kind = PayloadKind.valueOf(NodeProperties.string(node, "responseKind", PayloadKind.MAP.name()));
        } catch (IllegalArgumentException unknown) {
            throw invalid(node, "responseKind", "must be SCALAR, LIST, or MAP");
        }
        String responseSchema = bounded(NodeProperties.string(node, "responseSchema",
                "ravenroot.human-task.response"), "responseSchema",
                policy.maxResponseSchemaUtf8Bytes(), false, node);
        return new HumanTaskDefinition(new HumanTaskMetadata(title, description),
                new HumanTaskResponseSchema(NodeProperties.string(node, "responseContentType",
                        RESPONSE_CONTENT_TYPE), responseSchema, NodeProperties.string(node,
                        "responseSchemaVersion", "1"), kind, maxBytes),
                new HandlerAuthorization(tokens(node, "authorizedRoles"),
                        tokens(node, "authorizedScopes")),
                escalationSeconds == 0 ? Optional.empty()
                        : Optional.of(java.time.Duration.ofSeconds(escalationSeconds)),
                java.time.Duration.ofSeconds(expirySeconds),
                new HumanTaskReentryMapping(NodeProperties.string(node, "resolvedOutcome", "resolved"),
                        NodeProperties.string(node, "deniedOutcome", "denied"),
                        NodeProperties.string(node, "expiredOutcome", "expired"),
                        NodeProperties.string(node, "cancelledOutcome", "cancelled")),
                policy.executionLimits(maxBytes));
    }

    private static NodePropertyDescriptor choice(String name, String display, String description,
                                                  String defaultValue, String... choices) {
        return new NodePropertyDescriptor(name, display, NodePropertyType.STRING, false, description,
                defaultValue, List.of(choices), false);
    }

    private Set<String> tokens(GraphNode node, String property) {
        String raw = NodeProperties.string(node, property, "");
        if (raw.isBlank()) return Set.of();
        var values = new LinkedHashSet<String>();
        for (String part : raw.split(",", -1)) {
            String token = part.strip();
            if (token.isBlank()) throw invalid(node, property, "contains a blank token");
            if (token.getBytes(StandardCharsets.UTF_8).length > policy.maxAuthorizationTokenUtf8Bytes()) {
                throw invalid(node, property, "contains a token above "
                        + policy.maxAuthorizationTokenUtf8Bytes() + " UTF-8 bytes");
            }
            values.add(token);
        }
        if (values.size() > policy.maxAuthorizationTokens()) {
            throw invalid(node, property, "contains more than " + policy.maxAuthorizationTokens() + " tokens");
        }
        return Set.copyOf(values);
    }

    private static String bounded(String value, String property, int maxBytes, boolean blankAllowed,
                                  GraphNode node) {
        if (!blankAllowed && value.isBlank()) throw invalid(node, property, "cannot be blank");
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid(node, property, "exceeds " + maxBytes + " UTF-8 bytes");
        }
        return value;
    }

    private static IllegalArgumentException invalid(GraphNode node, String property, String reason) {
        return new IllegalArgumentException("Node " + node.id() + " property '" + property + "' " + reason);
    }
}
