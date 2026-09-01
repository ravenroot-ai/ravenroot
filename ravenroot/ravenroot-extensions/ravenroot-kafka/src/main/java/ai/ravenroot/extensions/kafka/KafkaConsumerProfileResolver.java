package ai.ravenroot.extensions.kafka;

import java.util.Optional;

@FunctionalInterface
public interface KafkaConsumerProfileResolver {
    Optional<KafkaConsumerProfile> resolve(String tenant, String profile);
}
