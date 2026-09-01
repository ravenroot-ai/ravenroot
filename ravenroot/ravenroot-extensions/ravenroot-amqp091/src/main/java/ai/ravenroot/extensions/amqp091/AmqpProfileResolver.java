package ai.ravenroot.extensions.amqp091;

import java.util.Optional;

@FunctionalInterface
public interface AmqpProfileResolver {
    Optional<AmqpProfile> resolve(String tenant, String profile);
}
