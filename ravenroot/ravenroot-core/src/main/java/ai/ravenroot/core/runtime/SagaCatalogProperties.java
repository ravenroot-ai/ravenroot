package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;

/** Adds the governed saga fields consumed by {@link SagaGraphContract} to catalog entries. */
final class SagaCatalogProperties {
    private SagaCatalogProperties() { }

    static NodeTypeDescriptor decorate(NodeTypeDescriptor descriptor) {
        if (participant(descriptor) == null) return descriptor;
        return descriptor.withProperties(
                text("saga.scope", "Saga scope", "Versioned saga scope. Leave empty outside a saga."),
                text("saga.step", "Saga step", "Logical step identity retained across transport retries."),
                new NodePropertyDescriptor("saga.participant", "Participant contract", NodePropertyType.STRING, false,
                        "Explicit participant contract supported by this trusted behavior.", "",
                        java.util.List.of(participant(descriptor)), false),
                text("saga.adapter", "Saga adapter",
                        "pure".equals(participant(descriptor)) ? "Pure participants do not use an effectful adapter."
                                : "Required trusted adapter: " + adapter(descriptor) + "."),
                text("saga.receiptStatement", "Receipt statement",
                        "Operator-owned JDBC query that binds operation id and payload fingerprint."),
                text("saga.inboxBinding", "Inbox binding",
                        "Operator-governed AMQP consumer inbox binding checked again by the outbox worker."),
                text("saga.compensation", "Compensation node", "Node id for the explicit compensation operation."),
                text("saga.dependsOn", "Depends on", "Comma-separated logical steps that must complete first."),
                new NodePropertyDescriptor("saga.role", "Saga role", NodePropertyType.STRING, false,
                        "Whether this node is a forward or compensation operation.", "",
                        java.util.List.of("forward", "compensation"), false),
                NodePropertyDescriptor.optional("saga.irreversible", "Irreversible", NodePropertyType.BOOLEAN,
                        "Explicitly admits a step that cannot be compensated.", ""),
                NodePropertyDescriptor.optional("saga.businessCompletionRequired", "Wait for business completion",
                        NodePropertyType.BOOLEAN, "Wait beyond broker acceptance for an inbox/effect receipt.", ""),
                NodePropertyDescriptor.optional("saga.deadlineMs", "Saga deadline (ms)", NodePropertyType.INTEGER,
                        "Maximum elapsed time for this saga scope before durable cancellation and compensation.", ""),
                text("saga.commandType", "Saga command type",
                        "Versioned application command type inserted into an AMQP body."));
    }

    /** Capability and behavior must agree; generic side effects grant no saga authority. */
    static String participant(NodeTypeDescriptor descriptor) {
        var capabilities = descriptor.capabilities();
        if (capabilities.contains("saga-pure") && !capabilities.contains("side-effect")) return "pure";
        String adapter = switch (descriptor.behavior()) {
            case "jdbc.insert" -> "ravenroot.jdbc-receipt.v1";
            case "amqp.publish" -> "ravenroot.amqp-inbox.v1";
            case "http-request" -> "ravenroot.http-idempotency.v1";
            default -> null;
        };
        return adapter != null && capabilities.contains("saga-adapter:" + adapter) ? switch (descriptor.behavior()) {
            case "jdbc.insert" -> "jdbc-receipt-v1";
            case "amqp.publish" -> "amqp-inbox-v1";
            default -> "http-idempotency-v1";
        } : null;
    }

    private static String adapter(NodeTypeDescriptor descriptor) {
        return switch (descriptor.behavior()) {
            case "jdbc.insert" -> "ravenroot.jdbc-receipt.v1";
            case "amqp.publish" -> "ravenroot.amqp-inbox.v1";
            default -> "ravenroot.http-idempotency.v1";
        };
    }

    private static NodePropertyDescriptor text(String name, String label, String description) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.STRING, description, "");
    }
}
