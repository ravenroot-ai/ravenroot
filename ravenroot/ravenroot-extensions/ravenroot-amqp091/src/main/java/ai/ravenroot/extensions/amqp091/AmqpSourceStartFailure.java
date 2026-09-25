package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.deployment.SourceStartFailureCode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Compile-time-closed AMQP source-start failure vocabulary. */
enum AmqpSourceStartFailure implements SourceStartFailureCode {
    AMQP_CONSUMER_ALREADY_ACTIVE("amqp-consumer-already-active"),
    AMQP_CONSUMER_FAILED("amqp-consumer-failed"),
    AMQP_CONSUMER_POLICY_UNAVAILABLE("amqp-consumer-policy-unavailable"),
    AMQP_CONSUMER_UNAVAILABLE("amqp-consumer-unavailable"),
    AMQP_PROFILE_UNAVAILABLE("amqp-profile-unavailable"),
    CREDENTIAL_UNAVAILABLE("credential-unavailable"),
    DURABLE_INGRESS_LOST("durable-ingress-lost"),
    DURABLE_INGRESS_REQUIRED("durable-ingress-required"),
    INVALID_CHECKPOINT_POLICY("invalid-checkpoint-policy"),
    INVALID_DEAD_LETTER_MODE("invalid-dead-letter-mode"),
    INVALID_DRAIN_TIMEOUT("invalid-drain-timeout"),
    INVALID_MAX_IN_FLIGHT("invalid-max-in-flight"),
    INVALID_MAX_RETRY_BACKOFF("invalid-max-retry-backoff"),
    INVALID_POISON_ATTEMPTS("invalid-poison-attempts"),
    INVALID_POISON_POLICY("invalid-poison-policy"),
    INVALID_PREFETCH("invalid-prefetch"),
    INVALID_RETRY_BACKOFF("invalid-retry-backoff"),
    POISON_POLICY_FORBIDDEN("poison-policy-forbidden"),
    QUEUE_NOT_AUTHORIZED("queue-not-authorized"),
    STARTUP_CANCELLED("startup-cancelled"),
    UNKNOWN_GRAPH_PROPERTY("unknown-graph-property");

    private final String code;
    AmqpSourceStartFailure(String code) { this.code = SourceStartFailureCode.requireValid(code); }
    @Override public String code() { return code; }
    static Set<String> codes() {
        return Arrays.stream(values()).map(AmqpSourceStartFailure::code)
                .collect(Collectors.toUnmodifiableSet());
    }
}
