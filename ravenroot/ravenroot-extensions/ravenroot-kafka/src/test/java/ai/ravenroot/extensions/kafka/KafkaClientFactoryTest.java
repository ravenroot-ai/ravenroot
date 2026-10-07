package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class KafkaClientFactoryTest {
    @Test void productionProducerConstructsWithGuardedKafkaClient() {
        KafkaProfile source = KafkaTestSupport.profile();
        KafkaProfile profile = new KafkaProfile(source.tenant(), source.name(),
                List.of("localhost:65530"), source.clientDnsLookup(), true, source.saslMechanism(),
                source.username(), source.credentialRef(), source.clientId(), source.defaultTopic(),
                source.topics(), source.headers(), source.allowPartition(), source.maxPartition(),
                source.allowTimestamp(), source.compression(), source.acks(), source.idempotence(),
                source.retries(), source.maxInFlight(), source.allowAutoCreate(),
                source.maxConcurrency(), source.maxPerSecond(), source.timeoutMs(),
                source.maxRecordBytes(), source.bufferMemoryBytes());
        KafkaProtocol.CreateAttempt attempt = new ApacheKafkaProtocol().beginCreate(
                profile, "secret".toCharArray(), 1_000, ReservedNetworkPolicy.shippedDefault());

        assertDoesNotThrow(() -> {
            attempt.establish();
            attempt.claim().close(0, () -> { });
        });
    }

    @Test void productionConsumerConstructsWithGuardedKafkaClient() {
        KafkaConsumerProfile source = KafkaConsumerTestSupport.profile();
        KafkaConsumerProfile profile = new KafkaConsumerProfile(source.tenant(), source.name(),
                List.of("localhost:65530"), source.clientDnsLookup(), true, source.saslMechanism(),
                source.username(), source.credentialRef(), source.clientId(), source.groupLogicalName(),
                source.groupId(), source.staticMemberId(), source.topics(), source.topicPattern(),
                source.headers(), source.assignmentStrategy(), source.autoOffsetReset(),
                source.isolationLevel(), source.startupTimeoutMs(), source.pollTimeoutMs(),
                source.maxPollIntervalMs(), source.sessionTimeoutMs(), source.heartbeatIntervalMs(),
                source.maxInFlight(), source.maxFetchBytes(), source.maxPartitionFetchBytes(),
                source.maxRecordBytes(), source.maxKeyBytes(), source.maxValueBytes(),
                source.maxHeaderBytes(), source.drainTimeoutMs(), source.retryBackoffMs(),
                source.maxRetryBackoffMs(), source.poisonAttempts(), source.poisonPolicy(),
                source.deadLetterTopic());

        KafkaConsumerProtocol.Owner owner = assertDoesNotThrow(() ->
                new ApacheKafkaConsumerProtocol().open(profile, "secret".toCharArray(),
                        ReservedNetworkPolicy.shippedDefault()));
        assertDoesNotThrow(() -> owner.close(Duration.ZERO));
    }
}
