package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.egress.TrustedNetworkPolicy;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.MetadataRecoveryStrategy;
import org.apache.kafka.clients.RavenrootNetworkClient;
import org.apache.kafka.clients.producer.internals.ProducerMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.network.ChannelState;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.NetworkSend;
import org.apache.kafka.common.network.Selectable;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KafkaConnectionGuardTest {
    @Test void trustedAdvertisedBrokerIsResolvedAndPinnedForPlaintext() throws Exception {
        KafkaConnectionGuard guard = guard(9092);
        guard.replaceMetadata(List.of(new Node(1, "10.8.0.4", 9092)));

        assertArrayEquals(new InetAddress[]{InetAddress.getByName("10.8.0.4")},
                guard.resolve("10.8.0.4"));
        RecordingSelectable transport = new RecordingSelectable();
        new GuardedKafkaSelectable(transport, guard).connect("1",
                new InetSocketAddress(InetAddress.getByName("10.8.0.4"), 9092), 1, 1);
        assertEquals(1, transport.connects);
    }

    @Test void sameHostDifferentCoordinatorPortIsRefusedBeforeSocketConnect() throws Exception {
        KafkaConnectionGuard guard = guard(9092);
        guard.replaceMetadata(List.of(new Node(1, "10.8.0.4", 9092)));
        guard.resolve("10.8.0.4");
        RecordingSelectable transport = new RecordingSelectable();
        GuardedKafkaSelectable guarded = new GuardedKafkaSelectable(transport, guard);

        assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED",
                assertThrows(SecurityException.class,
                        () -> guard.register(new Node(2, "10.8.0.4", 9093))).getMessage());
        IOException refused = assertThrows(IOException.class, () -> guarded.connect("coordinator",
                new InetSocketAddress(InetAddress.getByName("10.8.0.4"), 9093), 1, 1));
        assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED", refused.getMessage());
        assertEquals(0, transport.connects);
    }

    @Test void selectedAddressCannotBeReusedByNumericAliasOrAnotherNodeIdentity() throws Exception {
        KafkaConnectionGuard guard = namedGuard();
        guard.register(new Node(7, "localhost", 9092));
        InetAddress selected = guard.resolve("localhost")[0];
        RecordingSelectable transport = new RecordingSelectable();
        GuardedKafkaSelectable guarded = new GuardedKafkaSelectable(transport, guard);

        guarded.connect("7", new InetSocketAddress(selected, 9092), 1, 1);
        assertEquals(1, transport.connects);
        assertThrows(SecurityException.class,
                () -> guard.register(new Node(8, selected.getHostAddress(), 9092)));
        assertThrows(IOException.class,
                () -> guarded.connect("8", new InetSocketAddress(selected, 9092), 1, 1));
        assertEquals(1, transport.connects);
    }

    @Test void unregisteredDnsOrNumericNodeFailsClosedAtResolver() {
        KafkaConnectionGuard guard = guard(9092);

        assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED",
                assertThrows(java.net.UnknownHostException.class,
                        () -> guard.resolve("broker.internal.test")).getMessage());
        assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED",
                assertThrows(java.net.UnknownHostException.class,
                        () -> guard.resolve("10.8.0.9")).getMessage());
    }

    @Test void plaintextRuleMustMatchAdvertisedPortAndAddress() {
        KafkaConnectionGuard guard = guard(9092);

        assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED",
                assertThrows(SecurityException.class,
                        () -> guard.requireAndRegister("10.8.0.4", 9093)).getMessage());
        assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED",
                assertThrows(SecurityException.class,
                        () -> guard.requireAndRegister("192.168.2.4", 9092)).getMessage());
        assertEquals("OUTBOUND_TRANSPORT_ENCRYPTION_REQUIRED",
                assertThrows(SecurityException.class,
                        () -> guard(9092, false).requireAndRegister("10.8.0.4", 9092)).getMessage());
    }

    @Test void failedMetadataBatchCannotPartiallyReplaceOrInheritNodeIdentity() throws Exception {
        KafkaConnectionGuard guard = guard(9092);
        ProducerMetadata metadata = new KafkaConnectionGuard.Producer(
                100, 1_000, 60_000, 60_000, new LogContext(),
                new ClusterResourceListeners(), Time.SYSTEM, guard);
        metadata.updateWithCurrentRequestVersion(response(List.of(
                new Node(4, "10.8.0.4", 9092))), false, Time.SYSTEM.milliseconds());

        assertThrows(SecurityException.class, () -> metadata.updateWithCurrentRequestVersion(
                response(List.of(new Node(5, "10.8.0.5", 9092),
                        new Node(4, "10.8.0.4", 9093))), false, Time.SYSTEM.milliseconds()));

        RecordingSelectable transport = new RecordingSelectable();
        GuardedKafkaSelectable guarded = new GuardedKafkaSelectable(transport, guard);
        guarded.connect("4", new InetSocketAddress(InetAddress.getByName("10.8.0.4"), 9092), 1, 1);
        assertThrows(IOException.class, () -> guarded.connect("5",
                new InetSocketAddress(InetAddress.getByName("10.8.0.5"), 9092), 1, 1));
        assertEquals(1, transport.connects);
    }

    @Test void realNetworkClientReadyPathGuardsReusedNodeIdBeforeTransport() {
        KafkaConnectionGuard guard = guard(9092);
        RecordingSelectable transport = new RecordingSelectable();
        Metrics metrics = new Metrics(Time.SYSTEM);
        ProducerMetadata metadata = new KafkaConnectionGuard.Producer(
                100, 1_000, 60_000, 60_000, new LogContext(),
                new ClusterResourceListeners(), Time.SYSTEM, guard);
        RavenrootNetworkClient client = new RavenrootNetworkClient(null, metadata,
                new GuardedKafkaSelectable(transport, guard), "guard-test", 1,
                0, 0, 1_024, 1_024, 1_000, 1_000, 1_000, Time.SYSTEM, true,
                new ApiVersions(), metrics.sensor("guard-test"), new LogContext(), guard, null,
                300_000, MetadataRecoveryStrategy.NONE, guard::register);
        try {
            client.ready(new Node(9, "10.8.0.4", 9092), Time.SYSTEM.milliseconds());
            assertEquals(1, transport.connects);
            assertEquals("OUTBOUND_DESTINATION_POLICY_REFUSED",
                    assertThrows(SecurityException.class, () -> client.ready(
                            new Node(9, "10.8.0.4", 9093), Time.SYSTEM.milliseconds())).getMessage());
            assertEquals(1, transport.connects);
        } finally {
            client.close();
            metrics.close();
        }
    }

    private static MetadataResponse response(List<Node> brokers) {
        return MetadataResponse.prepareResponse(true, 0, brokers, "guard-test", -1, List.of(), 0);
    }

    private static KafkaConnectionGuard guard(int port) {
        return guard(port, true);
    }

    private static KafkaConnectionGuard guard(int port, boolean plaintext) {
        String json = """
                {"version":1,"rules":[{"name":"kafka-mesh","protocols":["kafka"],
                "ports":[%d],"hosts":[],"addresses":["10.8.0.0/16"],
                "profiles":["tenant-a/cluster-a"],"allowPlaintext":%s}]}
                """.formatted(port, plaintext).replace("\n", "");
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ReservedNetworkPolicy policy = ReservedNetworkPolicy.fromEnvironment(Map.of(
                TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encoded,
                ReservedNetworkPolicy.EXCEPTIONS_ENVIRONMENT_VARIABLE, ""));
        return new KafkaConnectionGuard(policy, "tenant-a/cluster-a", false);
    }

    private static KafkaConnectionGuard namedGuard() {
        String json = """
                {"version":1,"rules":[{"name":"named-kafka","protocols":["kafka"],
                "ports":[9092],"hosts":["localhost"],"addresses":["127.0.0.0/8","::1/128"],
                "profiles":["tenant-a/cluster-a"],"allowPlaintext":true}]}
                """.replace("\n", "");
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ReservedNetworkPolicy policy = ReservedNetworkPolicy.fromEnvironment(Map.of(
                TrustedNetworkPolicy.ENVIRONMENT_VARIABLE, encoded));
        return new KafkaConnectionGuard(policy, "tenant-a/cluster-a", false);
    }

    private static final class RecordingSelectable implements Selectable {
        private int connects;
        @Override public void connect(String id, InetSocketAddress address, int send, int receive) {
            connects++;
        }
        @Override public void wakeup() { }
        @Override public void close() { }
        @Override public void close(String id) { }
        @Override public void send(NetworkSend send) { }
        @Override public void poll(long timeout) { }
        @Override public List<NetworkSend> completedSends() { return List.of(); }
        @Override public Collection<NetworkReceive> completedReceives() { return List.of(); }
        @Override public Map<String, ChannelState> disconnected() { return Map.of(); }
        @Override public List<String> connected() { return List.of(); }
        @Override public void mute(String id) { }
        @Override public void unmute(String id) { }
        @Override public void muteAll() { }
        @Override public void unmuteAll() { }
        @Override public boolean isChannelReady(String id) { return false; }
    }
}
