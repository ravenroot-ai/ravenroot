package ai.ravenroot.extensions.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.clients.consumer.RoundRobinAssignor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Official Apache Kafka byte consumer; all security and weakening-sensitive properties are profile-owned. */
final class ApacheKafkaConsumerProtocol implements KafkaConsumerProtocol {
    private final BiFunction<Map<String, Object>, KafkaConsumerProfile, Consumer<byte[], byte[]>> consumers;
    private final BiFunction<Map<String, Object>, KafkaConsumerProfile, Producer<byte[], byte[]>> producers;

    ApacheKafkaConsumerProtocol() {
        this((properties, ignored) -> new KafkaConsumer<>(properties,
                        new ByteArrayDeserializer(), new ByteArrayDeserializer()),
                (properties, ignored) -> new KafkaProducer<>(properties,
                        new ByteArraySerializer(), new ByteArraySerializer()));
    }

    ApacheKafkaConsumerProtocol(
            BiFunction<Map<String, Object>, KafkaConsumerProfile, Consumer<byte[], byte[]>> consumers,
            BiFunction<Map<String, Object>, KafkaConsumerProfile, Producer<byte[], byte[]>> producers) {
        this.consumers = Objects.requireNonNull(consumers);
        this.producers = Objects.requireNonNull(producers);
    }

    @Override public Owner open(KafkaConsumerProfile profile, char[] password) {
        // Kafka's JAAS parser ultimately retains an immutable String for the client lifetime. Build
        // that unavoidable value directly from the erasable char[] (no password-only String), wrap
        // it in Kafka's PASSWORD type before it enters any properties map, reuse the same masked
        // object for consumer and optional DLQ construction, and never log properties/exceptions.
        Password jaas = jaasCredential(profile, password);
        Consumer<byte[], byte[]> consumer = consumers.apply(consumerProperties(profile, jaas), profile);
        Producer<byte[], byte[]> dlq = null;
        try {
            if (profile.deadLetters()) dlq = producers.apply(producerProperties(profile, jaas), profile);
            return new Client(consumer, dlq, profile.deadLetterTopic());
        } catch (RuntimeException failure) {
            try { consumer.close(Duration.ZERO); } catch (RuntimeException ignored) { }
            if (dlq != null) try { dlq.close(Duration.ZERO); } catch (RuntimeException ignored) { }
            throw failure;
        }
    }

    static Map<String, Object> consumerProperties(KafkaConsumerProfile p, char[] password) {
        return consumerProperties(p, jaasCredential(p, password));
    }
    private static Map<String, Object> consumerProperties(KafkaConsumerProfile p, Password jaas) {
        Map<String, Object> values = commonProperties(p, jaas);
        values.put(ConsumerConfig.GROUP_ID_CONFIG, p.groupId());
        if (p.staticMemberId() != null) values.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, p.staticMemberId());
        values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        values.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, p.autoOffsetReset());
        values.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, assignmentClass(p.assignmentStrategy()));
        values.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, p.maxInFlight());
        values.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, p.maxFetchBytes());
        values.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, p.maxPartitionFetchBytes());
        values.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, p.maxPollIntervalMs());
        values.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, p.sessionTimeoutMs());
        values.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, p.heartbeatIntervalMs());
        values.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return Map.copyOf(values);
    }

    static Map<String, Object> producerProperties(KafkaConsumerProfile p, char[] password) {
        return producerProperties(p, jaasCredential(p, password));
    }
    private static Map<String, Object> producerProperties(KafkaConsumerProfile p, Password jaas) {
        Map<String, Object> values = commonProperties(p, jaas);
        values.put(ProducerConfig.ACKS_CONFIG, "all");
        values.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        values.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        values.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, p.maxRetryBackoffMs());
        values.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, p.maxRetryBackoffMs());
        values.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, p.maxRetryBackoffMs());
        values.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, p.maxRecordBytes());
        return Map.copyOf(values);
    }

    private static Map<String, Object> commonProperties(KafkaConsumerProfile p, Password jaas) {
        Map<String, Object> values = new HashMap<>();
        values.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, p.bootstrapServers());
        values.put(ConsumerConfig.CLIENT_DNS_LOOKUP_CONFIG, p.clientDnsLookup());
        values.put(ConsumerConfig.CLIENT_ID_CONFIG, p.clientId());
        values.put("security.protocol", p.tls() ? "SASL_SSL" : "SASL_PLAINTEXT");
        values.put("sasl.mechanism", p.saslMechanism());
        values.put("ssl.endpoint.identification.algorithm", "https");
        values.put("sasl.jaas.config", jaas);
        return values;
    }

    private static String assignmentClass(String strategy) {
        return switch (strategy) {
            case "cooperative-sticky" -> CooperativeStickyAssignor.class.getName();
            case "range" -> RangeAssignor.class.getName();
            case "round-robin" -> RoundRobinAssignor.class.getName();
            default -> throw new IllegalArgumentException("unsupported assignment strategy");
        };
    }

    private static Password jaasCredential(KafkaConsumerProfile profile, char[] password) {
        String module = "PLAIN".equals(profile.saslMechanism())
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        StringBuilder value = new StringBuilder(module.length() + profile.username().length() + password.length + 40);
        value.append(module).append(" required username=\"").append(escape(profile.username()))
                .append("\" password=\"");
        for (char character : password) {
            if (character == '\\' || character == '"') value.append('\\');
            value.append(character);
        }
        return new Password(value.append("\";").toString());
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private static final class Client implements Owner {
        private final Consumer<byte[], byte[]> consumer;
        private final Producer<byte[], byte[]> dlq;
        private final String dlqTopic;

        private Client(Consumer<byte[], byte[]> consumer, Producer<byte[], byte[]> dlq, String dlqTopic) {
            this.consumer = consumer; this.dlq = dlq; this.dlqTopic = dlqTopic;
        }
        @Override public void subscribe(Subscription subscription, RebalanceListener listener) {
            ConsumerRebalanceListener bridge = new ConsumerRebalanceListener() {
                @Override public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    listener.revoked(partitions(partitions));
                }
                @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    listener.assigned(partitions(partitions));
                }
                @Override public void onPartitionsLost(Collection<TopicPartition> partitions) {
                    listener.lost(partitions(partitions));
                }
            };
            if (subscription.pattern() != null) consumer.subscribe(Pattern.compile(subscription.pattern()), bridge);
            else consumer.subscribe(subscription.topics(), bridge);
        }
        @Override public List<Record> poll(Duration timeout) {
            var answer = new ArrayList<Record>();
            consumer.poll(timeout).forEach(record -> {
                var headers = new ArrayList<Header>();
                record.headers().forEach(header -> headers.add(new Header(header.key(), header.value())));
                answer.add(new Record(new Partition(record.topic(), record.partition()), record.offset(),
                        record.timestamp(), record.timestampType().name(), record.key(), record.value(), headers,
                        record.leaderEpoch().isPresent() ? record.leaderEpoch().get() : null,
                        record.serializedKeySize(), record.serializedValueSize()));
            });
            return List.copyOf(answer);
        }
        @Override public Set<Partition> assignment() { return partitions(consumer.assignment()); }
        @Override public void pause(Collection<Partition> partitions) { consumer.pause(nativePartitions(partitions)); }
        @Override public void resume(Collection<Partition> partitions) { consumer.resume(nativePartitions(partitions)); }
        @Override public void commit(Map<Partition, Long> nextOffsets) {
            Map<TopicPartition, OffsetAndMetadata> nativeOffsets = new LinkedHashMap<>();
            nextOffsets.forEach((partition, offset) -> nativeOffsets.put(nativePartition(partition),
                    new OffsetAndMetadata(offset)));
            if (!nativeOffsets.isEmpty()) consumer.commitSync(nativeOffsets);
        }
        @Override public boolean deadLetter(Record record, String reason, Duration timeout) {
            if (dlq == null || dlqTopic == null) return false;
            List<org.apache.kafka.common.header.Header> headers = List.of(
                    new RecordHeader("ravenroot.original.topic", record.partition().topic().getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader("ravenroot.original.partition", Integer.toString(record.partition().partition()).getBytes(StandardCharsets.US_ASCII)),
                    new RecordHeader("ravenroot.original.offset", Long.toString(record.offset()).getBytes(StandardCharsets.US_ASCII)),
                    new RecordHeader("ravenroot.failure", reason.getBytes(StandardCharsets.US_ASCII)));
            try {
                dlq.send(new ProducerRecord<byte[], byte[]>(dlqTopic, null, record.timestamp(), record.key(), record.value(), headers))
                        .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception failure) { return false; }
        }
        @Override public void wakeup() { consumer.wakeup(); }
        @Override public void close(Duration timeout) {
            try { consumer.close(timeout); }
            finally { if (dlq != null) dlq.close(timeout); }
        }
        private static Set<Partition> partitions(Collection<TopicPartition> values) {
            return values.stream().map(value -> new Partition(value.topic(), value.partition()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        private static Collection<TopicPartition> nativePartitions(Collection<Partition> values) {
            return values.stream().map(Client::nativePartition).toList();
        }
        private static TopicPartition nativePartition(Partition value) {
            return new TopicPartition(value.topic(), value.partition());
        }
    }
}
