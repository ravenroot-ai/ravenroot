package org.apache.kafka.clients.producer;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.producer.internals.ProducerMetadata;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.Time;

import java.time.Duration;

/**
 * Version-pinned construction bridge that injects Ravenroot's guarded Kafka network client.
 *
 * <p>This class intentionally compiles against Kafka's package-private 4.1.2 constructor. A Kafka
 * upgrade that changes that boundary fails the Ravenroot build rather than silently restoring the
 * default resolver.</p>
 *
 * @param <K> serialized key type
 * @param <V> serialized value type
 */
public final class RavenrootKafkaProducer<K, V> extends KafkaProducer<K, V> {
    private final Metrics connectionMetrics;

    /**
     * Creates a producer around an already guarded client and its exact metadata instance.
     *
     * @param config validated Kafka producer configuration
     * @param keySerializer key serializer
     * @param valueSerializer value serializer
     * @param metadata metadata shared with the guarded client
     * @param client guarded Kafka client
     * @param apiVersions API-version state shared with the guarded client
     * @param time Kafka time source
     * @param connectionMetrics metrics owned by the guarded client
     */
    public RavenrootKafkaProducer(ProducerConfig config, Serializer<K> keySerializer,
                                  Serializer<V> valueSerializer, ProducerMetadata metadata,
                                  KafkaClient client, ApiVersions apiVersions, Time time,
                                  Metrics connectionMetrics) {
        super(config, keySerializer, valueSerializer, metadata, client, null, apiVersions, time);
        this.connectionMetrics = connectionMetrics;
    }

    /**
     * Closes both the producer and the separately constructed guarded-client metrics.
     *
     * @param timeout maximum time to wait for producer shutdown
     */
    @Override public void close(Duration timeout) {
        try { super.close(timeout); }
        finally { connectionMetrics.close(); }
    }
}
