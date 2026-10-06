package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.consumer.internals.ConsumerMetadata;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;

import java.time.Duration;
import java.util.List;

/**
 * Version-pinned construction bridge that injects Ravenroot's guarded Kafka network client.
 *
 * <p>The bridge deliberately targets Kafka 4.1.2's package-private constructor. Provider API drift
 * therefore fails compilation instead of bypassing the guarded resolver.</p>
 *
 * @param <K> deserialized key type
 * @param <V> deserialized value type
 */
public final class RavenrootKafkaConsumer<K, V> extends KafkaConsumer<K, V> {
    private final Metrics connectionMetrics;

    /**
     * Creates a consumer around an already guarded client and its exact metadata instance.
     *
     * @param logContext Kafka logging context
     * @param time Kafka time source
     * @param config validated Kafka consumer configuration
     * @param keyDeserializer key deserializer
     * @param valueDeserializer value deserializer
     * @param client guarded Kafka client
     * @param subscriptions subscription state shared with metadata
     * @param metadata metadata shared with the guarded client
     * @param assignors configured classic-group assignors
     * @param connectionMetrics metrics owned by the guarded client
     */
    public RavenrootKafkaConsumer(LogContext logContext, Time time, ConsumerConfig config,
                                  Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer,
                                  KafkaClient client, SubscriptionState subscriptions,
                                  ConsumerMetadata metadata, List<ConsumerPartitionAssignor> assignors,
                                  Metrics connectionMetrics) {
        super(logContext, time, config, keyDeserializer, valueDeserializer, client, subscriptions,
                metadata, assignors);
        this.connectionMetrics = connectionMetrics;
    }

    /**
     * Closes both the consumer and the separately constructed guarded-client metrics.
     *
     * @param timeout maximum time to wait for consumer shutdown
     */
    @Override public void close(Duration timeout) {
        try { super.close(timeout); }
        finally { connectionMetrics.close(); }
    }
}
