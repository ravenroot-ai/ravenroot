package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.GroupRebalanceConfig;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.MetadataRecoveryStrategy;
import org.apache.kafka.clients.RavenrootNetworkClient;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.RavenrootKafkaConsumer;
import org.apache.kafka.clients.consumer.internals.ConsumerUtils;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RavenrootKafkaProducer;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.network.ChannelBuilder;
import org.apache.kafka.common.network.Selector;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Constructs Kafka 4.1.2 clients whose resolver guards bootstrap and discovered nodes. */
final class KafkaClientFactory {
    private KafkaClientFactory() { }

    static Producer<byte[], byte[]> producer(Map<String, Object> properties, KafkaProfile profile,
                                              ReservedNetworkPolicy policy) {
        Map<String, Object> configured = new java.util.HashMap<>(properties);
        configured.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        configured.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        configured.put("enable.metrics.push", false);
        ProducerConfig config = new ProducerConfig(configured);
        Time time = Time.SYSTEM;
        String clientId = config.getString(ProducerConfig.CLIENT_ID_CONFIG);
        LogContext logs = new LogContext("[Producer clientId=" + clientId + "] ");
        Metrics metrics = new Metrics(time);
        KafkaClient client = null;
        try {
            KafkaConnectionGuard guard = new KafkaConnectionGuard(
                    policy, profile.tenant() + "/" + profile.name(), profile.tls());
            var metadata = new KafkaConnectionGuard.Producer(
                    config.getLong(ProducerConfig.RETRY_BACKOFF_MS_CONFIG),
                    config.getLong(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG),
                    config.getLong(ProducerConfig.METADATA_MAX_AGE_CONFIG),
                    config.getLong(ProducerConfig.METADATA_MAX_IDLE_CONFIG), logs,
                    new ClusterResourceListeners(), time, guard);
            bootstrap(config, guard, metadata);
            ApiVersions versions = new ApiVersions();
            Sensor throttle = metrics.sensor("ravenroot-kafka-producer-throttle");
            client = networkClient(config, clientId, metrics, "producer", logs, versions, time,
                    config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION),
                    config.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG), metadata, guard, throttle);
            return new RavenrootKafkaProducer<>(config, new ByteArraySerializer(),
                    new ByteArraySerializer(), metadata, client, versions, time, metrics);
        } catch (Throwable failure) {
            if (client != null) try { client.close(); } catch (Exception ignored) { }
            metrics.close();
            throw failure;
        }
    }

    static Consumer<byte[], byte[]> consumer(Map<String, Object> properties,
                                              KafkaConsumerProfile profile,
                                              ReservedNetworkPolicy policy) {
        Map<String, Object> configured = new java.util.HashMap<>(properties);
        configured.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        configured.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        configured.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "classic");
        configured.put("enable.metrics.push", false);
        ConsumerConfig config = new ConsumerConfig(configured);
        Time time = Time.SYSTEM;
        GroupRebalanceConfig rebalance = new GroupRebalanceConfig(
                config, GroupRebalanceConfig.ProtocolType.CONSUMER);
        LogContext logs = ConsumerUtils.createLogContext(config, rebalance);
        SubscriptionState subscriptions = ConsumerUtils.createSubscriptionState(config, logs);
        Metrics metrics = new Metrics(time);
        KafkaClient client = null;
        try {
            KafkaConnectionGuard guard = new KafkaConnectionGuard(
                    policy, profile.tenant() + "/" + profile.name(), profile.tls());
            var metadata = new KafkaConnectionGuard.Consumer(
                    config, subscriptions, logs, new ClusterResourceListeners(), guard);
            bootstrap(config, guard, metadata);
            ApiVersions versions = new ApiVersions();
            Sensor throttle = metrics.sensor("ravenroot-kafka-consumer-throttle");
            client = networkClient(config,
                    config.getString(ConsumerConfig.CLIENT_ID_CONFIG), metrics, "consumer", logs,
                    versions, time, ConsumerUtils.CONSUMER_MAX_INFLIGHT_REQUESTS_PER_CONNECTION,
                    config.getInt(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG), metadata, guard, throttle);
            List<ConsumerPartitionAssignor> assignors = ConsumerPartitionAssignor.getAssignorInstances(
                    config.getList(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG),
                    config.originals(Collections.singletonMap(ConsumerConfig.CLIENT_ID_CONFIG,
                            config.getString(ConsumerConfig.CLIENT_ID_CONFIG))));
            return new RavenrootKafkaConsumer<>(logs, time, config, new ByteArrayDeserializer(),
                    new ByteArrayDeserializer(), client, subscriptions, metadata, assignors, metrics);
        } catch (Throwable failure) {
            if (client != null) try { client.close(); } catch (Exception ignored) { }
            metrics.close();
            throw failure;
        }
    }

    private static void bootstrap(org.apache.kafka.common.config.AbstractConfig config,
                                  KafkaConnectionGuard guard,
                                  org.apache.kafka.clients.Metadata metadata) {
        List<InetSocketAddress> addresses = ClientUtils.parseAndValidateAddresses(config);
        for (InetSocketAddress address : addresses)
            guard.requireAndRegister(address.getHostString(), address.getPort());
        metadata.bootstrap(addresses);
        guard.replaceMetadata(metadata.fetch().nodes());
    }

    private static KafkaClient networkClient(org.apache.kafka.common.config.AbstractConfig config,
                                             String clientId, Metrics metrics, String metricsPrefix,
                                             LogContext logs, ApiVersions versions, Time time,
                                             int maxInFlight, int requestTimeoutMs,
                                             org.apache.kafka.clients.Metadata metadata,
                                             KafkaConnectionGuard guard, Sensor throttle) {
        ChannelBuilder channel = null;
        Selector selector = null;
        try {
            channel = ClientUtils.createChannelBuilder(config, time, logs);
            selector = new Selector(config.getLong(CommonClientConfigs.CONNECTIONS_MAX_IDLE_MS_CONFIG),
                    metrics, time, metricsPrefix, channel, logs);
            return new RavenrootNetworkClient(null, metadata, new GuardedKafkaSelectable(selector, guard), clientId,
                    maxInFlight, config.getLong(CommonClientConfigs.RECONNECT_BACKOFF_MS_CONFIG),
                    config.getLong(CommonClientConfigs.RECONNECT_BACKOFF_MAX_MS_CONFIG),
                    config.getInt(CommonClientConfigs.SEND_BUFFER_CONFIG),
                    config.getInt(CommonClientConfigs.RECEIVE_BUFFER_CONFIG), requestTimeoutMs,
                    config.getLong(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG),
                    config.getLong(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG),
                    time, true, versions, throttle, logs, guard, null,
                    config.getLong(CommonClientConfigs.METADATA_RECOVERY_REBOOTSTRAP_TRIGGER_MS_CONFIG),
                    MetadataRecoveryStrategy.forName(config.getString(
                            CommonClientConfigs.METADATA_RECOVERY_STRATEGY_CONFIG)), guard::register);
        } catch (Throwable failure) {
            if (selector != null) try { selector.close(); } catch (RuntimeException ignored) { }
            else if (channel != null) try { channel.close(); } catch (RuntimeException ignored) { }
            throw failure;
        }
    }
}
