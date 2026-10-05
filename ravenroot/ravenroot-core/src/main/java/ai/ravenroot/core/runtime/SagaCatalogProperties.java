package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;

/** Adds the governed saga fields consumed by {@link SagaGraphContract} to catalog entries. */
final class SagaCatalogProperties {
    private SagaCatalogProperties() { }

    static NodeTypeDescriptor decorate(NodeTypeDescriptor descriptor) {
        if (!descriptor.capabilities().contains("side-effect")
                && !descriptor.capabilities().contains("saga-pure")) return descriptor;
        return descriptor.withProperties(
                text("saga.scope", "Saga scope", "Versioned saga scope. Leave empty outside a saga."),
                text("saga.step", "Saga step", "Logical step identity retained across transport retries."),
                NodePropertyDescriptor.optional("saga.participant", "Participant contract", NodePropertyType.STRING,
                        "pure, jdbc-receipt-v1, amqp-inbox-v1, or http-idempotency-v1.", ""),
                text("saga.adapter", "Saga adapter",
                        "Trusted runtime adapter identifier required by effectful participant protocols."),
                text("saga.receiptStatement", "Receipt statement",
                        "Operator-owned JDBC query that binds operation id and payload fingerprint."),
                text("saga.inboxBinding", "Inbox binding",
                        "Operator-governed AMQP consumer inbox binding checked again by the outbox worker."),
                text("saga.compensation", "Compensation node", "Node id for the explicit compensation operation."),
                text("saga.dependsOn", "Depends on", "Comma-separated logical steps that must complete first."),
                new NodePropertyDescriptor("saga.role", "Saga role", NodePropertyType.STRING, false,
                        "Whether this node is a forward or compensation operation.", "forward",
                        java.util.List.of("forward", "compensation"), false),
                NodePropertyDescriptor.optional("saga.irreversible", "Irreversible", NodePropertyType.BOOLEAN,
                        "Explicitly admits a step that cannot be compensated.", "false"),
                NodePropertyDescriptor.optional("saga.businessCompletionRequired", "Wait for business completion",
                        NodePropertyType.BOOLEAN, "Wait beyond broker acceptance for an inbox/effect receipt.", "false"),
                NodePropertyDescriptor.optional("saga.deadlineMs", "Saga deadline (ms)", NodePropertyType.INTEGER,
                        "Maximum elapsed time for this saga scope before durable cancellation and compensation.", ""),
                text("saga.commandType", "Saga command type",
                        "Versioned application command type inserted into an AMQP body."));
    }

    private static NodePropertyDescriptor text(String name, String label, String description) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.STRING, description, "");
    }
}
