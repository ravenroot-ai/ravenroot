package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Deployment-scoped manual-ack AMQP 0-9-1 source. */
public final class AmqpConsumeNodeBehavior implements NodeBehavior, InboundSourceCapable {
    public static final String BEHAVIOR = "amqp.consume";
    private static final Set<String> CONFIGURATION = Set.of("brokerProfile", "queue", "prefetch", "maxInFlight",
            "retryBackoffMs", "maxRetryBackoffMs", "drainTimeoutMs", "poisonAttempts", "poisonPolicy",
            "deadLetterMode", "checkpointPolicy");
    private final CredentialResolver credentials;
    private final AmqpProfileResolver profiles;
    private final AmqpConsumerPolicyResolver policies;
    private final AmqpConsumerProtocol protocol;
    private final Executor executor;
    private final Clock clock;

    public AmqpConsumeNodeBehavior() {
        this(new EnvironmentAmqpCredentialResolver(), new EnvironmentAmqpProfileResolver(),
                new EnvironmentAmqpConsumerPolicyResolver(), new RabbitMqAmqpConsumerProtocol(),
                task -> Thread.ofVirtual().name("ravenroot-amqp091-consumer").start(task), Clock.systemUTC());
    }

    AmqpConsumeNodeBehavior(CredentialResolver credentials, AmqpProfileResolver profiles,
                            AmqpConsumerPolicyResolver policies, AmqpConsumerProtocol protocol,
                            Executor executor, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials); this.profiles = Objects.requireNonNull(profiles);
        this.policies = Objects.requireNonNull(policies); this.protocol = Objects.requireNonNull(protocol);
        this.executor = Objects.requireNonNull(executor); this.clock = Objects.requireNonNull(clock);
    }

    @Override public NodeTypeDescriptor descriptor() {
        List<NodePropertyDescriptor> properties = new ArrayList<>();
        properties.add(NodePropertyDescriptor.required("brokerProfile", "Broker profile", NodePropertyType.STRING,
                "Opaque tenant-scoped profile; endpoint, credentials, queue authority and DLX stay operator-owned."));
        properties.add(optional("queue", "Queue", NodePropertyType.STRING,
                "Optional confirmation of the exact operator-authorized queue."));
        properties.add(optional("prefetch", "Prefetch", NodePropertyType.INTEGER,
                "Positive tightening of the operator QoS ceiling."));
        properties.add(optional("maxInFlight", "Maximum in-flight", NodePropertyType.INTEGER,
                "Positive tightening of bounded delivery admission."));
        properties.add(optional("retryBackoffMs", "Retry backoff (ms)", NodePropertyType.INTEGER,
                "Positive tightening of initial retry backoff."));
        properties.add(optional("maxRetryBackoffMs", "Maximum retry backoff (ms)", NodePropertyType.INTEGER,
                "Positive tightening of maximum retry backoff."));
        properties.add(optional("drainTimeoutMs", "Drain timeout (ms)", NodePropertyType.INTEGER,
                "Non-negative tightening of stop cleanup."));
        properties.add(optional("poisonAttempts", "Poison attempts", NodePropertyType.INTEGER,
                "Positive tightening of the delivery retry ceiling."));
        properties.add(allowed("poisonPolicy", "Poison policy", List.of("profile", "dead-letter"), "profile"));
        PropertyCondition dlx = PropertyCondition.equalTo("poisonPolicy", "dead-letter");
        properties.add(new NodePropertyDescriptor("deadLetterMode", "Dead-letter mode", NodePropertyType.STRING,
                false, "Confirms broker DLX rejection; topology remains operator-owned.", "", List.of("broker-dlx"),
                false, dlx, dlx));
        properties.add(allowed("checkpointPolicy", "Checkpoint policy", List.of("require-durable"),
                "require-durable"));
        // PERS-04 (ADR 0022). An inbound source declares no recovery repeatability, and the
        // reason is that it has no attempt of its own for the contract to describe. The node is armed
        // once (InboundSourceCapable) and thereafter the broker drives it; the unit that can be lost
        // or repeated is a delivery and its acknowledgement, which 'checkpointPolicy: require-durable'
        // already governs, not a dispatched node attempt whose effect might have half happened.
        // Declaring the property here would offer an author a decision about an event that does not
        // occur on this node.
        return new NodeTypeDescriptor(BEHAVIOR, "Consume AMQP deliveries", "AMQP 0-9-1",
                "Consumes an operator-authorized queue with bounded QoS and durable manual acknowledgements.",
                "actor", false, List.copyOf(properties),
                Set.of("network", "credential-reference", "inbound-source"));
    }

    private static NodePropertyDescriptor optional(String name, String label, NodePropertyType type,
                                                    String description) {
        return NodePropertyDescriptor.optional(name, label, type, description, "");
    }

    private static NodePropertyDescriptor allowed(String name, String label, List<String> allowed,
                                                   String defaultValue) {
        return new NodePropertyDescriptor(name, label, NodePropertyType.STRING, false, "", defaultValue, allowed);
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }

    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return new AmqpConsumerSource(configuration, credentials, profiles, policies, protocol, executor, clock);
    }

    static Set<String> knownConfiguration() { return CONFIGURATION; }
}
