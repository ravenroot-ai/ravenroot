package ai.ravenroot.server;

import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.interaction.InteractionWebSocketConfiguration;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.support.ForwardingRavenrootApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RavenrootServerInteractionLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void installedInteractionListenerStartsAndClosesWithTheServerLifecycle() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        int interactionPort = unusedPort(loopback);
        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.enabled", "true");
        properties.setProperty("ravenroot.websocket.port", Integer.toString(interactionPort));
        var configuration = InteractionWebSocketConfiguration.from(properties, Map.of());
        Clock clock = Clock.systemUTC();

        try (var store = new SqliteExecutionStore(directory.resolve("interaction-lifecycle.db"), clock);
             var engine = new PekkoExecutionEngine("interaction-lifecycle-test")) {
            var application = new ForwardingRavenrootApplication(
                    new DefaultRavenrootApplication(engine, new ExecutionMonitor())) {
                @Override public boolean durableEventJournalAvailable() {
                    return true;
                }
            };
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(loopback, 0), null, new DisabledLoopbackAuthenticator())) {
                server.installHumanTasks(new HumanTaskService(store, clock), ignored -> { });
                server.installInteractionWebSockets(configuration);
                assertThrows(IllegalStateException.class,
                        () -> server.installInteractionWebSockets(configuration),
                        "one server must own exactly one interaction listener");

                server.start();
                assertTrue(canConnect(loopback, interactionPort),
                        "server start must make the separately bound interaction listener reachable");
                assertThrows(IllegalStateException.class,
                        () -> server.installInteractionWebSockets(configuration),
                        "listener authority must be immutable after server start");

                server.close();
                assertFalse(canConnect(loopback, interactionPort),
                        "server close must retire the interaction listener before returning");
            }
        }
    }

    private static int unusedPort(InetAddress address) throws Exception {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(address, 0));
            return socket.getLocalPort();
        }
    }

    private static boolean canConnect(InetAddress address, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 250);
            return true;
        } catch (java.io.IOException unavailable) {
            return false;
        }
    }
}
