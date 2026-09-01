package ai.ravenroot.extensions.amqp091;

import java.util.Optional;

@FunctionalInterface
interface AmqpConsumerPolicyResolver {
    Optional<AmqpConsumerPolicy> resolve(String tenant, String profile);
}
