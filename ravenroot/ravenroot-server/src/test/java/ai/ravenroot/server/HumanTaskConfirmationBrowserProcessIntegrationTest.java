package ai.ravenroot.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-process lifecycle groundwork for the confirmation-browser restart acceptance scenario.
 *
 * <p>It is opt-in until the companion browser spec and deployment-ingress fixture are available.
 * The control endpoint lives only in this JUnit JVM and owns only the child it launched; no
 * production server endpoint permits lifecycle control.</p>
 */
class HumanTaskConfirmationBrowserProcessIntegrationTest {
    static final String TENANT = "local";
    static final String DEPLOYMENT_ID = "human-task-e2e";
    static final String GRAPH_ID = "human-task-confirmation-e2e";
    static final String NODE_ID = "human-confirmation";
    static final String DOWNSTREAM_NODE_ID = "human-confirmation-downstream";

    @TempDir Path root;

    @Test
    void startsAndStopsTheOwnedPackagedServerOnTheReusableSqliteLocation() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.humanTaskConfirmation.browserTest"),
                "run after the real deployment-ingress/browser fixture is assembled");
        int backendPort = freePort();
        try (Control control = new Control()) {
            Process first = start(backendPort);
            control.own(first);
            awaitHealth(backendPort);
            assertEquals(200, request(backendPort, "/health").statusCode());
            control.stop();

            Process recovery = start(backendPort);
            control.own(recovery);
            awaitHealth(backendPort);
            control.stop();
        }
    }

    private Process start(int port) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        var command = List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                HumanTaskConfirmationWorkbenchProcess.class.getName());
        var process = new ProcessBuilder(command).redirectErrorStream(true);
        var env = process.environment();
        env.put("RAVENROOT_PORT", Integer.toString(port));
        env.put("RAVENROOT_AUTH_MODE", "local-token");
        env.put("RAVENROOT_AUTH_LOCAL_TOKEN", "human-task-e2e-nonsecret-fixture-token-0123456789");
        env.put("RAVENROOT_REPLICAS", "1");
        env.put("RAVENROOT_PROGRAM_RUNTIME", "disabled");
        env.put("RAVENROOT_EXECUTION_STORE_DIR", root.resolve("execution-store").toString());
        env.put("RAVENROOT_AUDIT_DIR", root.resolve("audit").toString());
        env.put("RAVENROOT_ARTIFACT_STORE_DIR", root.resolve("artifacts").toString());
        return process.start();
    }

    private static void awaitHealth(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            try { if (request(port, "/health").statusCode() == 200) return; }
            catch (Exception ignored) { }
            Thread.sleep(100);
        }
        throw new AssertionError("owned confirmation child did not become healthy");
    }

    private static HttpResponse<String> request(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private static final class Control implements AutoCloseable {
        private final HttpServer server;
        private volatile Process child;
        private Control() throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/stop", exchange -> {
                try {
                    stop();
                    exchange.sendResponseHeaders(204, -1);
                } catch (Exception failed) {
                    exchange.sendResponseHeaders(500, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }
        void own(Process process) { child = process; }
        void stop() throws Exception {
            Process process = child; child = null;
            if (process == null) return;
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "owned child survived teardown");
        }
        @Override public void close() throws Exception { stop(); server.stop(0); }
    }
}
