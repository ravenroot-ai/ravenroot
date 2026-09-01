package ai.ravenroot.extensions.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Narrow, byte-only consumer seam used to test lifecycle and offset semantics without a broker. */
interface KafkaConsumerProtocol {
    Owner open(KafkaConsumerProfile profile, char[] password);

    interface Owner {
        void subscribe(Subscription subscription, RebalanceListener listener);
        List<Record> poll(Duration timeout);
        Set<Partition> assignment();
        void pause(Collection<Partition> partitions);
        void resume(Collection<Partition> partitions);
        void commit(Map<Partition, Long> nextOffsets);
        boolean deadLetter(Record record, String reason, Duration timeout);
        void wakeup();
        void close(Duration timeout);
    }

    interface RebalanceListener {
        void revoked(Set<Partition> partitions);
        void assigned(Set<Partition> partitions);
        void lost(Set<Partition> partitions);
    }

    record Subscription(Set<String> topics, String pattern) {
        public Subscription {
            topics = Set.copyOf(topics == null ? Set.of() : topics);
            pattern = pattern == null || pattern.isBlank() ? null : pattern;
            if (topics.isEmpty() == (pattern == null)) {
                throw new IllegalArgumentException("exactly one Kafka subscription form is required");
            }
        }
    }

    record Partition(String topic, int partition) implements Comparable<Partition> {
        public Partition {
            if (topic == null || topic.isBlank() || partition < 0) {
                throw new IllegalArgumentException("invalid Kafka partition");
            }
        }
        @Override public int compareTo(Partition other) {
            int topicOrder = topic.compareTo(other.topic);
            return topicOrder != 0 ? topicOrder : Integer.compare(partition, other.partition);
        }
    }

    record Header(String name, byte[] value) {
        public Header {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("invalid Kafka header");
            value = value == null ? null : value.clone();
        }
        @Override public byte[] value() { return value == null ? null : value.clone(); }
    }

    record Record(Partition partition, long offset, long timestamp, String timestampType,
                  byte[] key, byte[] value, List<Header> headers, Integer leaderEpoch,
                  int serializedKeySize, int serializedValueSize) {
        public Record {
            if (partition == null || offset < 0 || timestamp < -1 || timestampType == null
                    || value == null || serializedValueSize < 0 || serializedKeySize < -1) {
                throw new IllegalArgumentException("invalid Kafka record");
            }
            key = key == null ? null : key.clone();
            value = value.clone();
            headers = copyHeaders(headers);
            if (leaderEpoch != null && leaderEpoch < 0) throw new IllegalArgumentException("invalid leader epoch");
        }
        @Override public byte[] key() { return key == null ? null : key.clone(); }
        @Override public byte[] value() { return value.clone(); }
        private static List<Header> copyHeaders(List<Header> headers) {
            if (headers == null || headers.isEmpty()) return List.of();
            var copy = new ArrayList<Header>(headers.size());
            headers.forEach(header -> copy.add(new Header(header.name(), header.value())));
            return List.copyOf(copy);
        }
    }

    static Map<Partition, Long> immutableOffsets(Map<Partition, Long> offsets) {
        return Map.copyOf(new LinkedHashMap<>(offsets));
    }
}
