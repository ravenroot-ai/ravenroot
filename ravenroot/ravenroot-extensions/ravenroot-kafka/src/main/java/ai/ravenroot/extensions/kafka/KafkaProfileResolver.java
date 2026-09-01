package ai.ravenroot.extensions.kafka;

import java.util.Optional;

@FunctionalInterface
public interface KafkaProfileResolver {
    Optional<KafkaProfile> resolve(String tenant, String profile);
}
