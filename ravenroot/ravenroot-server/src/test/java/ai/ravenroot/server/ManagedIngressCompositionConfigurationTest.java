package ai.ravenroot.server;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.server.ingress.ManagedIngressRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Production startup is explicitly prepare-then-bind and fails closed before a socket exists. */
class ManagedIngressCompositionConfigurationTest {
    private static final IngressAuthorityDeclaration AUTHORITY = authority("example.ingress", "/managed/example");

    @Test void replicaCountIsReadFromActualEnvironmentShape() {
        assertEquals(1, RavenrootServerMain.replicaCount(Map.of()));
        assertEquals(2, RavenrootServerMain.replicaCount(Map.of("RAVENROOT_REPLICAS", "2")));
        assertThrows(IllegalArgumentException.class,
                () -> RavenrootServerMain.replicaCount(Map.of("RAVENROOT_REPLICAS", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> RavenrootServerMain.replicaCount(Map.of("RAVENROOT_REPLICAS", "two")));
    }

    @Test void invalidAuthoritySetFailsBeforeTheSelectedPortIsBound() throws Exception {
        int port = unusedPort();
        var first = new ContributingPackage("example.ingress", AUTHORITY);
        var overlapping = new ContributingPackage("other.ingress",
                authority("other.ingress", "/managed/example/nested"));

        assertThrows(IllegalArgumentException.class,
                () -> RavenrootServerStartup.prepare(List.of(first, overlapping), environment(port, 1)));
        assertImmediatelyBindable(port);
    }

    @Test void coreProbeAndStaticNamespacesFailBeforeTheSelectedPortIsBound() throws Exception {
        for (String reserved : List.of("/", "/v1", "/v1/status", "/health", "/health/detail",
                "/ready", "/ready/detail")) {
            int port = unusedPort();
            assertThrows(IllegalArgumentException.class, () -> {
                var declaration = authority("reserved." + Math.abs(reserved.hashCode()), reserved);
                RavenrootServerStartup.prepare(
                        List.of(new ContributingPackage(declaration.packageId(), declaration)), environment(port, 1));
            }, reserved);
            assertImmediatelyBindable(port);
        }
    }

    @Test void packageIdentityMismatchFailsBeforeTheSelectedPortIsBound() throws Exception {
        int port = unusedPort();
        var mismatch = new ContributingPackage("enabled.package",
                authority("forged.package", "/managed/forged"));

        assertThrows(IllegalArgumentException.class,
                () -> RavenrootServerStartup.prepare(List.of(mismatch), environment(port, 1)));
        assertImmediatelyBindable(port);
    }

    @Test void projectionIdentityMismatchFailsBeforeTheSelectedPortIsBound() throws Exception {
        int port = unusedPort();
        var projection = new IngressRequestProjectionPolicy("forged.package", Set.of("Content-Type"), null,
                128, 8, 256, 1, 128, 64);
        var mismatch = new ProjectedPackage("example.ingress", AUTHORITY, projection);

        assertThrows(IllegalArgumentException.class,
                () -> RavenrootServerStartup.prepare(List.of(mismatch), environment(port, 1)));
        assertImmediatelyBindable(port);
    }

    @Test void unavailableListenerFailsBeforeTheSelectedPortIsBound() throws Exception {
        int port = unusedPort();
        var unavailable = new ContributingPackage("example.ingress",
                new IngressAuthorityDeclaration("example.ingress", "private", "/managed/private",
                        Set.of("invoke"), 1, 1, 128, 128, Duration.ofSeconds(1)));

        assertThrows(IllegalArgumentException.class,
                () -> RavenrootServerStartup.prepare(List.of(unavailable), environment(port, 1)));
        assertImmediatelyBindable(port);
    }

    @Test void replicaConfigurationFailsBeforeTheSelectedPortIsBound() throws Exception {
        int port = unusedPort();

        assertThrows(IllegalStateException.class, () -> RavenrootServerStartup.prepare(
                List.of(new ContributingPackage("example.ingress", AUTHORITY)), environment(port, 2)));
        assertImmediatelyBindable(port);
    }

    @Test void packageInstallActivatesNoRouteAndHandleReadinessBeginsOnlyAfterStart() {
        var installed = new AtomicReference<ai.ravenroot.api.ingress.ManagedIngress>();
        var listener = new RecordingListener(false);
        try (var prepared = RavenrootServerStartup.prepare(
                List.of(new ContributingPackage("example.ingress", AUTHORITY)), Map.of())) {
            prepared.installInto(installed::set);
            assertNotNull(installed.get(), "the application receives only the managed capability");
            assertEquals(0, prepared.activeRouteCount(),
                    "package installation is declaration only; graph lifecycle owns route acquisition");

            var handle = prepared.bind(() -> listener);
            assertFalse(handle.ready());
            assertEquals(0, prepared.activeRouteCount());
            handle.start();
            assertTrue(handle.ready());
            assertEquals(0, prepared.activeRouteCount());
            handle.close();
            handle.close();
            assertFalse(handle.ready());
            assertEquals(1, listener.closes.get(), "close is idempotent");
        }
    }

    @Test void failedListenerStartRollsBackResourcesAndNeverReportsReady() {
        var listener = new RecordingListener(true);
        var prepared = RavenrootServerStartup.prepare(
                List.of(new ContributingPackage("example.ingress", AUTHORITY)), Map.of());
        var handle = prepared.bind(() -> listener);

        assertThrows(IllegalStateException.class, handle::start);
        assertFalse(handle.ready());
        assertEquals(1, listener.closes.get(), "failed start closes every listener-owned resource");
        handle.close();
        assertEquals(1, listener.closes.get(), "late close cannot repeat rollback");
    }

    @Test void registeredRecordRetainsItsOriginalBinaryShape() throws Exception {
        var type = ai.ravenroot.server.plugin.PluginActivationOrchestrator.Registered.class;
        assertArrayEquals(new String[] {"registry", "activation"},
                java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new));
        assertNotNull(type.getDeclaredConstructor(ai.ravenroot.core.runtime.BehaviorRegistry.class,
                ai.ravenroot.plugin.bundle.PluginActivation.class),
                "the canonical constructor without the inventory component must remain linkable");
    }

    private static Map<String, String> environment(int port, int replicas) {
        return Map.of("RAVENROOT_PORT", Integer.toString(port), "RAVENROOT_REPLICAS", Integer.toString(replicas));
    }

    private static int unusedPort() throws IOException {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private static void assertImmediatelyBindable(int port) throws IOException {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            assertTrue(socket.isBound());
        }
    }

    private static IngressAuthorityDeclaration authority(String packageId, String path) {
        return new IngressAuthorityDeclaration(packageId, "main", path, Set.of("invoke"),
                2, 2, 1_024, 1_024, Duration.ofSeconds(2));
    }

    private record ContributingPackage(String id, IngressAuthorityDeclaration declaration)
            implements NodePackage, IngressAuthorityContributor {
        @Override public String version() { return "1.0.0"; }
        @Override public String sdkContract() { return NodeSdk.CONTRACT; }
        @Override public List<NodeBehavior> behaviors() { return List.of(); }
        @Override public List<IngressAuthorityDeclaration> ingressAuthorities() { return List.of(declaration); }
    }

    private record ProjectedPackage(String id, IngressAuthorityDeclaration declaration,
                                    IngressRequestProjectionPolicy projection)
            implements NodePackage, IngressAuthorityContributor {
        @Override public String version() { return "1.0.0"; }
        @Override public String sdkContract() { return NodeSdk.CONTRACT; }
        @Override public List<NodeBehavior> behaviors() { return List.of(); }
        @Override public List<IngressAuthorityDeclaration> ingressAuthorities() { return List.of(declaration); }
        @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
            return Optional.of(projection);
        }
    }

    private static final class RecordingListener implements RavenrootServerStartup.Listener {
        private final boolean failStart;
        private final AtomicInteger closes = new AtomicInteger();
        private ManagedIngressRegistry ingress;

        private RecordingListener(boolean failStart) {
            this.failStart = failStart;
        }

        @Override public void install(ManagedIngressRegistry ingress) {
            this.ingress = ingress;
        }

        @Override public void start() {
            if (failStart) throw new IllegalStateException("synthetic listener failure");
        }

        @Override public void close() {
            if (closes.incrementAndGet() == 1 && ingress != null) ingress.close();
        }
    }
}
