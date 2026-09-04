package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Stable core descriptor and fail-closed runtime for a durable human decision. */
final class HumanTaskNodeBehaviorFactory implements NodeBehaviorFactory {
    static final int DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;
    static final int HARD_MAX_RESPONSE_BYTES = 256 * 1024;
    static final long DEFAULT_EXPIRY_SECONDS = Duration.ofDays(7).toSeconds();
    static final long MAX_DELAY_SECONDS = Duration.ofDays(30).toSeconds();
    static final String RESPONSE_CONTENT_TYPE = "application/vnd.ravenroot.payload+json";
    private static final int MAX_AUTHORITY_TOKENS = 16;
    private static final int MAX_AUTHORITY_TOKEN_BYTES = 256;

    private final HumanTaskService tasks;

    HumanTaskNodeBehaviorFactory(HumanTaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("human-task", "Human task", "Human workflow",
                "Creates durable, tenant-scoped work for a person and resumes from the pinned graph version.",
                "flow", false, List.of(
                NodePropertyDescriptor.required("title", "Title", NodePropertyType.STRING,
                        "Static bounded title shown in the human-task inbox. Payload interpolation is not supported."),
                NodePropertyDescriptor.optional("description", "Description", NodePropertyType.TEXT,
                        "Static bounded instructions shown in the inbox. Payload interpolation is not supported.", ""),
                NodePropertyDescriptor.optional("responseContentType", "Response media type",
                        NodePropertyType.STRING, "Exact media type required for a resolved response.",
                        RESPONSE_CONTENT_TYPE),
                NodePropertyDescriptor.optional("responseSchema", "Response schema",
                        NodePropertyType.STRING, "Bounded response schema identifier.",
                        "ravenroot.human-task.response"),
                NodePropertyDescriptor.optional("responseSchemaVersion", "Response schema version",
                        NodePropertyType.STRING, "Exact response schema version required at resolution.", "1"),
                choice("responseKind", "Response kind", "Required top-level response shape.",
                        PayloadKind.MAP.name(), "SCALAR", "LIST", "MAP"),
                NodePropertyDescriptor.optional("maxResponseBytes", "Maximum response bytes",
                        NodePropertyType.INTEGER, "Inclusive byte bound (1-262144).", "65536"),
                NodePropertyDescriptor.optional("authorizedRoles", "Authorized roles", NodePropertyType.TEXT,
                        "Comma-separated roles; every listed role is required.", ""),
                NodePropertyDescriptor.optional("authorizedScopes", "Authorized scopes", NodePropertyType.TEXT,
                        "Comma-separated scopes; every listed scope is required.", ""),
                NodePropertyDescriptor.optional("escalateAfterSeconds", "Escalate after (seconds)",
                        NodePropertyType.INTEGER, "Zero disables escalation; otherwise must be earlier than expiry.", "0"),
                NodePropertyDescriptor.optional("expiresAfterSeconds", "Expire after (seconds)",
                        NodePropertyType.INTEGER, "Durable expiry delay (1-2592000).", "604800"),
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

    private static HumanTaskDefinition definition(GraphNode node) {
        String title = bounded(NodeProperties.required(node, "title"), "title",
                HumanTaskMetadata.MAX_TITLE_UTF8_BYTES, false, node);
        String description = bounded(NodeProperties.string(node, "description", ""), "description",
                HumanTaskMetadata.MAX_DESCRIPTION_UTF8_BYTES, true, node);
        int maxBytes = Math.toIntExact(NodeProperties.number(node, "maxResponseBytes",
                DEFAULT_MAX_RESPONSE_BYTES));
        if (maxBytes < 1 || maxBytes > HARD_MAX_RESPONSE_BYTES) {
            throw invalid(node, "maxResponseBytes", "must be between 1 and " + HARD_MAX_RESPONSE_BYTES);
        }
        long expirySeconds = NodeProperties.number(node, "expiresAfterSeconds", DEFAULT_EXPIRY_SECONDS);
        long escalationSeconds = NodeProperties.number(node, "escalateAfterSeconds", 0);
        if (expirySeconds < 1 || expirySeconds > MAX_DELAY_SECONDS) {
            throw invalid(node, "expiresAfterSeconds", "must be between 1 and " + MAX_DELAY_SECONDS);
        }
        if (escalationSeconds < 0 || escalationSeconds >= expirySeconds) {
            throw invalid(node, "escalateAfterSeconds", "must be zero or earlier than expiry");
        }
        PayloadKind kind;
        try {
            kind = PayloadKind.valueOf(NodeProperties.string(node, "responseKind", PayloadKind.MAP.name()));
        } catch (IllegalArgumentException unknown) {
            throw invalid(node, "responseKind", "must be SCALAR, LIST, or MAP");
        }
        return new HumanTaskDefinition(new HumanTaskMetadata(title, description),
                new HumanTaskResponseSchema(NodeProperties.string(node, "responseContentType",
                        RESPONSE_CONTENT_TYPE), NodeProperties.string(node, "responseSchema",
                        "ravenroot.human-task.response"), NodeProperties.string(node,
                        "responseSchemaVersion", "1"), kind, maxBytes),
                new HandlerAuthorization(tokens(node, "authorizedRoles"),
                        tokens(node, "authorizedScopes")),
                escalationSeconds == 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(escalationSeconds)),
                Duration.ofSeconds(expirySeconds),
                new HumanTaskReentryMapping(NodeProperties.string(node, "resolvedOutcome", "resolved"),
                        NodeProperties.string(node, "deniedOutcome", "denied"),
                        NodeProperties.string(node, "expiredOutcome", "expired"),
                        NodeProperties.string(node, "cancelledOutcome", "cancelled")));
    }

    private static NodePropertyDescriptor choice(String name, String display, String description,
                                                  String defaultValue, String... choices) {
        return new NodePropertyDescriptor(name, display, NodePropertyType.STRING, false, description,
                defaultValue, List.of(choices), false);
    }

    private static Set<String> tokens(GraphNode node, String property) {
        String raw = NodeProperties.string(node, property, "");
        if (raw.isBlank()) return Set.of();
        var values = new LinkedHashSet<String>();
        for (String part : raw.split(",", -1)) {
            String token = part.strip();
            if (token.isBlank()) throw invalid(node, property, "contains a blank token");
            if (token.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORITY_TOKEN_BYTES) {
                throw invalid(node, property, "contains a token above 256 UTF-8 bytes");
            }
            values.add(token);
        }
        if (values.size() > MAX_AUTHORITY_TOKENS) {
            throw invalid(node, property, "contains more than 16 tokens");
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
