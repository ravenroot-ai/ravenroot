package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.deployment.SourceStartFailureCode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Compile-time-closed Kafka source-start failure vocabulary. */
enum KafkaSourceStartFailure implements SourceStartFailureCode {
    ASSIGNMENT_LOST_BEFORE_READY("assignment-lost-before-ready"),
    ASSIGNMENT_TIMEOUT("assignment-timeout"),
    BROKER_AUTHORIZATION_FAILED("broker-authorization-failed"),
    CHECKPOINT_POLICY_FORBIDDEN("checkpoint-policy-forbidden"),
    CLUSTER_PROFILE_REQUIRED("cluster-profile-required"),
    CLUSTER_PROFILE_UNAVAILABLE("cluster-profile-unavailable"),
    CONSUMER_FAILED("consumer-failed"),
    CONSUMER_WAKEUP("consumer-wakeup"),
    CREDENTIAL_UNAVAILABLE("credential-unavailable"),
    DEAD_LETTER_TOPIC_FORBIDDEN("dead-letter-topic-forbidden"),
    DURABLE_INGRESS_LOST("durable-ingress-lost"),
    DURABLE_INGRESS_REQUIRED("durable-ingress-required"),
    GROUP_FORBIDDEN("group-forbidden"),
    INVALID_TIGHTENING("invalid-tightening"),
    MEMBERSHIP_INVALID("membership-invalid"),
    PARTITION_ORDER_VIOLATION("partition-order-violation"),
    POISON_POLICY_FORBIDDEN("poison-policy-forbidden"),
    POISON_RECORD_HALTED("poison-record-halted"),
    STARTUP_CANCELLED("startup-cancelled"),
    SUBSCRIPTION_MODE_INVALID("subscription-mode-invalid"),
    TOPIC_PATTERN_FORBIDDEN("topic-pattern-forbidden"),
    TOPICS_FORBIDDEN("topics-forbidden"),
    TOPICS_INVALID("topics-invalid"),
    UNKNOWN_GRAPH_PROPERTY("unknown-graph-property");

    private final String code;
    KafkaSourceStartFailure(String code) { this.code = SourceStartFailureCode.requireValid(code); }
    @Override public String code() { return code; }
    static Set<String> codes() {
        return Arrays.stream(values()).map(KafkaSourceStartFailure::code)
                .collect(Collectors.toUnmodifiableSet());
    }
}
