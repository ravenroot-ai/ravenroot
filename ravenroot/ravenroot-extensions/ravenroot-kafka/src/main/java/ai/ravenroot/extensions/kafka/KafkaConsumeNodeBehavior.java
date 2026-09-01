package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.security.CredentialResolver;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Long-lived, deployment-scoped Kafka consumer group source. */
public final class KafkaConsumeNodeBehavior implements NodeBehavior, InboundSourceCapable {
    public static final String BEHAVIOR = "kafka.consume";
    private static final Set<String> CONFIG = Set.of("clusterProfile", "subscriptionMode", "topics",
            "topicPattern", "group", "staticMember", "maxInFlight", "pollTimeoutMs", "drainTimeoutMs",
            "retryBackoffMs", "maxRetryBackoffMs", "poisonAttempts", "poisonPolicy", "deadLetterTopic",
            "checkpointPolicy");

    private final CredentialResolver credentials;
    private final KafkaConsumerProfileResolver profiles;
    private final KafkaConsumerProtocol protocol;
    private final Executor executor;
    private final Clock clock;

    public KafkaConsumeNodeBehavior() {
        this(new EnvironmentKafkaCredentialResolver(), new EnvironmentKafkaConsumerProfileResolver());
    }
    public KafkaConsumeNodeBehavior(CredentialResolver credentials, KafkaConsumerProfileResolver profiles) {
        this(credentials, profiles, new ApacheKafkaConsumerProtocol(),
                task -> Thread.ofVirtual().name("ravenroot-kafka-consumer").start(task), Clock.systemUTC());
    }
    KafkaConsumeNodeBehavior(CredentialResolver credentials, KafkaConsumerProfileResolver profiles,
                             KafkaConsumerProtocol protocol, Executor executor, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials); this.profiles = Objects.requireNonNull(profiles);
        this.protocol = Objects.requireNonNull(protocol); this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public NodeTypeDescriptor descriptor() {
        List<NodePropertyDescriptor> p = new ArrayList<>();
        p.add(NodePropertyDescriptor.required("clusterProfile", "Cluster profile", NodePropertyType.STRING,
                "Opaque tenant-scoped consumer profile; brokers, security and Kafka group id remain operator-owned."));
        p.add(allowed("subscriptionMode", "Subscription", List.of("profile", "topics", "pattern"), "profile"));
        p.add(conditional("topics", "Topics", "subscriptionMode", "topics",
                "Comma-separated subset of exact topics authorized by the profile."));
        p.add(conditional("topicPattern", "Topic pattern", "subscriptionMode", "pattern",
                "Anchored pattern equal to the pattern authorized by the profile."));
        p.add(optional("group", "Group", NodePropertyType.STRING,
                "Logical group name only; the physical Kafka group id remains in the operator profile."));
        p.add(allowed("staticMember", "Membership", List.of("profile", "dynamic"), "profile"));
        p.add(optional("maxInFlight", "Maximum in-flight", NodePropertyType.INTEGER, "May only tighten profile admission."));
        p.add(optional("pollTimeoutMs", "Poll timeout (ms)", NodePropertyType.INTEGER, "May only tighten the profile poll bound."));
        p.add(optional("drainTimeoutMs", "Drain timeout (ms)", NodePropertyType.INTEGER, "May only tighten the profile drain bound."));
        p.add(optional("retryBackoffMs", "Initial retry backoff (ms)", NodePropertyType.INTEGER, "May only tighten the profile bound."));
        p.add(optional("maxRetryBackoffMs", "Maximum retry backoff (ms)", NodePropertyType.INTEGER, "May only tighten the profile bound."));
        p.add(optional("poisonAttempts", "Poison attempts", NodePropertyType.INTEGER, "May only tighten the profile retry ceiling."));
        p.add(allowed("poisonPolicy", "Poison policy", List.of("profile", "halt", "dead-letter"), "profile"));
        p.add(conditional("deadLetterTopic", "Dead-letter topic", "poisonPolicy", "dead-letter",
                "Logical confirmation of the operator-authorized dead-letter topic."));
        p.add(allowed("checkpointPolicy", "Checkpoint policy", List.of("require-durable"), "require-durable"));
        // PERS-04 (ADR 0022). No recovery repeatability, for the reason given on
        // AmqpConsumeNodeBehavior: an inbound source is armed once and then driven by the broker, so
        // the repeatable unit is a record and its offset commit -- governed by
        // 'checkpointPolicy: require-durable' -- and not a dispatched attempt of this node.
        return new NodeTypeDescriptor(BEHAVIOR, "Consume Kafka records", "Kafka",
                "Consumes an operator-authorized Kafka group with manual contiguous durable offset commits.",
                "actor", false, List.copyOf(p), Set.of("network", "credential-reference", "inbound-source"));
    }

    private static NodePropertyDescriptor optional(String name, String label, NodePropertyType type, String description) {
        return NodePropertyDescriptor.optional(name, label, type, description, "");
    }
    private static NodePropertyDescriptor allowed(String name, String label, List<String> values, String defaultValue) {
        return new NodePropertyDescriptor(name, label, NodePropertyType.STRING, false, "", defaultValue, values);
    }
    private static NodePropertyDescriptor conditional(String name, String label, String sibling, String value,
                                                       String description) {
        PropertyCondition condition = PropertyCondition.equalTo(sibling, value);
        return new NodePropertyDescriptor(name, label, NodePropertyType.STRING, false, description, "", List.of(),
                false, condition, condition);
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        // The source node is an ingress anchor when reached by an ordinary traversal; it has no transport side effect.
        return message -> java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }

    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return new KafkaConsumerSource(configuration, credentials, profiles, protocol, executor, clock);
    }

    static Set<String> knownConfiguration() { return CONFIG; }
}
