package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumerProfileTest {
    @Test void profileRequiresReadCommittedBoundedAuthorityAndAnchoredPatterns() {
        KafkaConsumerProfile valid = KafkaConsumerTestSupport.profile();
        assertEquals("read_committed", valid.isolationLevel());
        assertThrows(IllegalArgumentException.class, () -> copy(valid, Map.of("isolation", "read_uncommitted")));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, Map.of("maxInFlight", 0)));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, Map.of("topics", Set.of(), "pattern", "orders.*")));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, Map.of("tls", false)));
    }

    @Test void environmentProfileFailsClosedOnMalformedOrMissingAndPreservesOpaqueGroup() {
        KafkaConsumerProfile p = KafkaConsumerTestSupport.profile();
        String value = String.join(";", String.join(",", p.bootstrapServers()), p.clientDnsLookup(),
                Boolean.toString(p.tls()), p.saslMechanism(), p.username(), p.credentialRef(), p.clientId(),
                p.groupLogicalName(), p.groupId(), p.staticMemberId(), String.join(",", p.topics()), "",
                String.join(",", p.headers()), p.assignmentStrategy(), p.autoOffsetReset(), p.isolationLevel(),
                Integer.toString(p.startupTimeoutMs()), Integer.toString(p.pollTimeoutMs()),
                Integer.toString(p.maxPollIntervalMs()), Integer.toString(p.sessionTimeoutMs()),
                Integer.toString(p.heartbeatIntervalMs()), Integer.toString(p.maxInFlight()),
                Integer.toString(p.maxFetchBytes()), Integer.toString(p.maxPartitionFetchBytes()),
                Integer.toString(p.maxRecordBytes()), Integer.toString(p.maxKeyBytes()),
                Integer.toString(p.maxValueBytes()), Integer.toString(p.maxHeaderBytes()),
                Integer.toString(p.drainTimeoutMs()), Integer.toString(p.retryBackoffMs()),
                Integer.toString(p.maxRetryBackoffMs()), Integer.toString(p.poisonAttempts()),
                p.poisonPolicy(), "");
        String key = "RAVENROOT_KAFKA_CONSUMER_PROFILE_" + EnvironmentKeyCodec.hex("tenant-a")
                + "_" + EnvironmentKeyCodec.hex("reader");
        var resolver = new EnvironmentKafkaConsumerProfileResolver(Map.of(key, value));
        assertEquals("rr-orders-v1", resolver.resolve("tenant-a", "reader").orElseThrow().groupId());
        assertTrue(new EnvironmentKafkaConsumerProfileResolver(Map.of(key, value + ";extra"))
                .resolve("tenant-a", "reader").isEmpty());
        assertTrue(resolver.resolve("tenant-b", "reader").isEmpty());
    }

    private static KafkaConsumerProfile copy(KafkaConsumerProfile p, Map<String, Object> changes) {
        @SuppressWarnings("unchecked") Set<String> topics = (Set<String>) changes.getOrDefault("topics", p.topics());
        return new KafkaConsumerProfile(p.tenant(), p.name(), p.bootstrapServers(), p.clientDnsLookup(),
                (boolean) changes.getOrDefault("tls", p.tls()), p.saslMechanism(), p.username(), p.credentialRef(),
                p.clientId(), p.groupLogicalName(), p.groupId(), p.staticMemberId(), topics,
                (String) changes.getOrDefault("pattern", p.topicPattern()), p.headers(), p.assignmentStrategy(),
                p.autoOffsetReset(), (String) changes.getOrDefault("isolation", p.isolationLevel()),
                p.startupTimeoutMs(), p.pollTimeoutMs(), p.maxPollIntervalMs(), p.sessionTimeoutMs(),
                p.heartbeatIntervalMs(), (int) changes.getOrDefault("maxInFlight", p.maxInFlight()),
                p.maxFetchBytes(), p.maxPartitionFetchBytes(), p.maxRecordBytes(), p.maxKeyBytes(), p.maxValueBytes(),
                p.maxHeaderBytes(), p.drainTimeoutMs(), p.retryBackoffMs(), p.maxRetryBackoffMs(),
                p.poisonAttempts(), p.poisonPolicy(), p.deadLetterTopic());
    }
}
