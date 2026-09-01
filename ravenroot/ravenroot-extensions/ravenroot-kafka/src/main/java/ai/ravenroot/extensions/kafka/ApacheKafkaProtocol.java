package ai.ravenroot.extensions.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/** Official Apache Kafka client adapter; serializers and security properties are never graph supplied. */
final class ApacheKafkaProtocol implements KafkaProtocol {
    private final BiFunction<Map<String, Object>, Integer, Producer<byte[], byte[]>> factories;

    ApacheKafkaProtocol() { this((properties, ignored) -> new KafkaProducer<>(properties,
            new ByteArraySerializer(), new ByteArraySerializer())); }
    ApacheKafkaProtocol(BiFunction<Map<String, Object>, Integer, Producer<byte[], byte[]>> factories) {
        this.factories = Objects.requireNonNull(factories);
    }

    @Override public CreateAttempt beginCreate(KafkaProfile profile, char[] password, int timeoutMs) {
        return new Attempt(profile, new String(password), timeoutMs);
    }

    private final class Attempt implements CreateAttempt {
        private final KafkaProfile profile;
        private final String password;
        private final int timeoutMs;
        private final AtomicBoolean closeScheduled = new AtomicBoolean();
        private boolean cancelled;
        private boolean established;
        private boolean claimed;
        private Producer<byte[], byte[]> producer;

        private Attempt(KafkaProfile profile, String password, int timeoutMs) {
            this.profile = profile; this.password = password; this.timeoutMs = timeoutMs;
        }

        @Override public void establish() throws ClientFailure {
            try {
                Producer<byte[], byte[]> opened = factories.apply(properties(profile, password, timeoutMs), timeoutMs);
                synchronized (this) {
                    if (cancelled) { closeOwned(opened); throw new ClientFailure(FailureKind.TEMPORARY); }
                    producer = Objects.requireNonNull(opened);
                    established = true;
                }
            } catch (ClientFailure failure) { cancel(); throw failure; }
            catch (RuntimeException failure) { cancel(); throw new ClientFailure(permanent(failure)
                    ? FailureKind.PERMANENT : FailureKind.TEMPORARY); }
        }

        @Override public synchronized Client claim() throws ClientFailure {
            if (cancelled || !established || claimed || producer == null) {
                cancel(); throw new ClientFailure(FailureKind.TEMPORARY);
            }
            claimed = true;
            Producer<byte[], byte[]> transferred = producer;
            producer = null;
            return new ClientImpl(transferred);
        }

        @Override public void cancel() {
            Producer<byte[], byte[]> owned;
            synchronized (this) {
                if (claimed) return;
                cancelled = true; owned = producer; producer = null;
            }
            if (owned != null) closeOwned(owned);
        }

        private void closeOwned(Producer<byte[], byte[]> owned) {
            if (!closeScheduled.compareAndSet(false, true)) return;
            Thread.ofVirtual().name("ravenroot-kafka-create-close").start(() -> {
                try { owned.close(Duration.ZERO); } catch (RuntimeException ignored) { }
            });
        }
    }

    private static final class ClientImpl implements Client {
        private final AtomicReference<Producer<byte[], byte[]>> producer;
        private ClientImpl(Producer<byte[], byte[]> producer) { this.producer = new AtomicReference<>(producer); }
        @Override public void send(Record record, Observer observer, int timeoutMs) {
            var headers = new ArrayList<org.apache.kafka.common.header.Header>();
            record.headers().forEach((key, value) -> headers.add(new org.apache.kafka.common.header.internals.RecordHeader(key, value)));
            current().send(new ProducerRecord<>(record.topic(), record.partition(), record.timestamp(), record.key(),
                    record.value(), headers), (metadata, failure) -> {
                if (failure != null) observer.failed(failure);
                else observer.acknowledged(new Metadata(metadata.topic(), metadata.partition(), metadata.offset(),
                        metadata.timestamp(), metadata.serializedKeySize(), metadata.serializedValueSize()));
            });
        }
        @Override public void flush() { current().flush(); }
        @Override public void close(int timeoutMs, Runnable ownershipRevoked) {
            Producer<byte[], byte[]> owned = producer.getAndSet(null);
            ownershipRevoked.run();
            if (owned != null) owned.close(Duration.ofMillis(Math.max(0, timeoutMs)));
        }
        private Producer<byte[], byte[]> current() {
            Producer<byte[], byte[]> current = producer.get();
            if (current == null) throw new IllegalStateException("Kafka client ownership revoked");
            return current;
        }
    }

    private static Map<String, Object> properties(KafkaProfile p, String password, int budget) {
        Map<String, Object> values = new HashMap<>();
        values.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, p.bootstrapServers());
        values.put(ProducerConfig.CLIENT_DNS_LOOKUP_CONFIG, p.clientDnsLookup());
        values.put(ProducerConfig.CLIENT_ID_CONFIG, p.clientId());
        values.put(ProducerConfig.ACKS_CONFIG, p.acks());
        values.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, p.idempotence());
        values.put(ProducerConfig.RETRIES_CONFIG, p.retries());
        values.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, p.maxInFlight());
        values.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, p.compression());
        values.put("allow.auto.create.topics", p.allowAutoCreate());
        values.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, budget);
        values.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, budget);
        values.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, budget);
        values.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        values.put(ProducerConfig.BUFFER_MEMORY_CONFIG, p.bufferMemoryBytes());
        values.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, p.maxRecordBytes());
        values.put("security.protocol", p.tls() ? "SASL_SSL" : "SASL_PLAINTEXT");
        values.put("sasl.mechanism", p.saslMechanism());
        values.put("ssl.endpoint.identification.algorithm", "https");
        values.put("sasl.jaas.config", jaas(p.saslMechanism(), p.username(), password));
        return Map.copyOf(values);
    }

    private static String jaas(String mechanism, String username, String password) {
        String module = "PLAIN".equals(mechanism) ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return module + " required username=\"" + escape(username) + "\" password=\"" + escape(password) + "\";";
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    static boolean permanent(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof AuthenticationException || current instanceof AuthorizationException
                    || current instanceof ConfigException || current instanceof IllegalArgumentException) return true;
        return false;
    }
}
