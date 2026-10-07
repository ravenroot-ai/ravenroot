package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.apache.kafka.clients.HostResolver;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.internals.ConsumerMetadata;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.clients.producer.internals.ProducerMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Exact profile-scoped admission and DNS pinning for every Kafka transport connection. */
final class KafkaConnectionGuard implements HostResolver {
    private static final String REFUSED = "OUTBOUND_DESTINATION_POLICY_REFUSED";
    private static final String PLAINTEXT_REFUSED = "OUTBOUND_TRANSPORT_ENCRYPTION_REQUIRED";

    private final ReservedNetworkPolicy policy;
    private final String profile;
    private final boolean tls;
    private final Map<String, Set<Integer>> endpoints = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Endpoint>> nodes = new AtomicReference<>(Map.of());

    KafkaConnectionGuard(ReservedNetworkPolicy policy, String profile, boolean tls) {
        this.policy = java.util.Objects.requireNonNull(policy);
        this.profile = java.util.Objects.requireNonNull(profile);
        this.tls = tls;
    }

    List<InetAddress> requireAndRegister(String host, int port) {
        List<InetAddress> resolved;
        try {
            resolved = policy.resolveAllowedDestination("kafka", profile, host, port);
        } catch (SecurityException refused) {
            throw new SecurityException(REFUSED);
        }
        if (!tls) {
            try {
                resolved = policy.resolveAllowedPlaintextDestination("kafka", profile, host, port);
            } catch (SecurityException refused) {
                throw new SecurityException(PLAINTEXT_REFUSED);
            }
        }
        endpoints.compute(host, (ignored, ports) -> {
            Set<Integer> updated = ConcurrentHashMap.newKeySet();
            if (ports != null) updated.addAll(ports);
            updated.add(port);
            return Set.copyOf(updated);
        });
        return resolved;
    }

    void replaceMetadata(Collection<Node> candidates) {
        nodes.set(validateAll(candidates));
    }

    void mergeMetadata(Collection<Node> candidates) {
        Map<String, Endpoint> admitted = validateAll(candidates);
        nodes.updateAndGet(current -> {
            Map<String, Endpoint> updated = new java.util.HashMap<>(current);
            updated.putAll(admitted);
            return Map.copyOf(updated);
        });
    }

    private Map<String, Endpoint> validateAll(Collection<Node> candidates) {
        Map<String, Endpoint> admitted = new java.util.HashMap<>();
        for (Node node : candidates) {
            requireAndRegister(node.host(), node.port());
            admitted.put(node.idString(), new Endpoint(node.host(), node.port()));
        }
        return Map.copyOf(admitted);
    }

    void register(Node node) {
        requireAndRegister(node.host(), node.port());
        Endpoint endpoint = new Endpoint(node.host(), node.port());
        nodes.updateAndGet(current -> {
            Map<String, Endpoint> updated = new java.util.HashMap<>(current);
            updated.put(node.idString(), endpoint);
            return Map.copyOf(updated);
        });
    }

    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
        Set<Integer> ports = endpoints.get(host);
        if (ports == null || ports.isEmpty()) throw refusedHost();
        List<InetAddress> pinned = null;
        try {
            for (int port : ports) {
                List<InetAddress> current = requireAndRegister(host, port);
                if (pinned == null) pinned = current;
                else if (!sameAddresses(pinned, current)) throw new SecurityException(REFUSED);
            }
        } catch (SecurityException refused) {
            throw refusedHost();
        }
        return pinned == null ? new InetAddress[0] : pinned.toArray(InetAddress[]::new);
    }

    void requirePinnedConnection(String nodeId, InetSocketAddress address) throws IOException {
        Endpoint endpoint = nodes.get().get(nodeId);
        InetAddress selected = address == null ? null : address.getAddress();
        if (endpoint == null || selected == null || address.getPort() != endpoint.port())
            throw new IOException(REFUSED);
        final List<InetAddress> current;
        try { current = requireAndRegister(endpoint.host(), endpoint.port()); }
        catch (SecurityException refused) { throw new IOException(refused.getMessage()); }
        if (!current.contains(selected)) throw new IOException(REFUSED);
    }

    private static boolean sameAddresses(List<InetAddress> left, List<InetAddress> right) {
        return left.size() == right.size() && left.stream().allMatch(right::contains);
    }

    private static UnknownHostException refusedHost() {
        return new UnknownHostException(REFUSED);
    }

    private record Endpoint(String host, int port) { }

    static final class Producer extends ProducerMetadata {
        private final KafkaConnectionGuard guard;

        Producer(long retryBackoffMs, long retryBackoffMaxMs, long metadataMaxAgeMs,
                 long metadataMaxIdleMs, LogContext logContext,
                 ClusterResourceListeners listeners, Time time, KafkaConnectionGuard guard) {
            super(retryBackoffMs, retryBackoffMaxMs, metadataMaxAgeMs, metadataMaxIdleMs,
                    logContext, listeners, time);
            this.guard = guard;
        }

        @Override public synchronized void update(int requestVersion, MetadataResponse response,
                                                  boolean partial, long nowMs) {
            if (partial) guard.mergeMetadata(response.brokers());
            else guard.replaceMetadata(response.brokers());
            super.update(requestVersion, response, partial, nowMs);
        }

        @Override public synchronized Set<TopicPartition> updatePartitionLeadership(
                Map<TopicPartition, Metadata.LeaderIdAndEpoch> leaders, List<Node> nodes) {
            guard.mergeMetadata(nodes);
            return super.updatePartitionLeadership(leaders, nodes);
        }
    }

    static final class Consumer extends ConsumerMetadata {
        private final KafkaConnectionGuard guard;

        Consumer(ConsumerConfig config, SubscriptionState subscriptions, LogContext logContext,
                 ClusterResourceListeners listeners, KafkaConnectionGuard guard) {
            super(config, subscriptions, logContext, listeners);
            this.guard = guard;
        }

        @Override public synchronized void update(int requestVersion, MetadataResponse response,
                                                  boolean partial, long nowMs) {
            if (partial) guard.mergeMetadata(response.brokers());
            else guard.replaceMetadata(response.brokers());
            super.update(requestVersion, response, partial, nowMs);
        }

        @Override public synchronized Set<TopicPartition> updatePartitionLeadership(
                Map<TopicPartition, Metadata.LeaderIdAndEpoch> leaders, List<Node> nodes) {
            guard.mergeMetadata(nodes);
            return super.updatePartitionLeadership(leaders, nodes);
        }
    }
}
