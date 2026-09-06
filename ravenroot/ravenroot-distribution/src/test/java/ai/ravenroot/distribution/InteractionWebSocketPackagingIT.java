package ai.ravenroot.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Proves the optional listener survives both shipped launch forms and shading boundaries. */
class InteractionWebSocketPackagingIT {
    private static final Path SHADED_JAR = Path.of("target", "ravenroot.jar");
    private static final Path BINARY_ZIP = Path.of("target", "ravenroot-bin.zip");
    private static final String TOKEN = "interaction-packaging-token-0123456789";
    private static final Set<String> REQUIRED_SERVICES = Set.of(
            "META-INF/services/org.eclipse.jetty.websocket.api.ExtensionConfig$Parser",
            "META-INF/services/org.eclipse.jetty.websocket.core.Extension",
            "META-INF/services/org.eclipse.jetty.http.HttpFieldPreEncoder");
    private static final Set<String> REQUIRED_LICENSES = Set.of(
            "META-INF/licenses/jetty-12.1.12-LICENSE.txt",
            "META-INF/licenses/jetty-12.1.12-NOTICE.txt",
            "META-INF/licenses/slf4j-api-2.0.17-LICENSE.txt");

    @Test
    void shadedJarAndZipPreserveServicesAttributionAndWire(@TempDir Path directory) throws Exception {
        assertTrue(Files.isRegularFile(SHADED_JAR));
        assertTrue(Files.isRegularFile(BINARY_ZIP));
        try (var jar = new JarFile(SHADED_JAR.toFile())) {
            REQUIRED_SERVICES.forEach(name -> assertTrue(jar.getEntry(name) != null, name));
            REQUIRED_LICENSES.forEach(name -> assertTrue(jar.getEntry(name) != null, name));
        }
        try (var zip = new ZipFile(BINARY_ZIP.toFile())) {
            REQUIRED_LICENSES.forEach(name -> assertTrue(zip.stream().anyMatch(entry ->
                    entry.getName().endsWith("/licenses/" + name.substring(name.lastIndexOf('/') + 1))), name));
            Set<String> nestedServices = new HashSet<>();
            zip.stream().filter(entry -> entry.getName().contains("/lib/") && entry.getName().endsWith(".jar"))
                    .forEach(entry -> collectNestedServices(zip, entry.getName(), nestedServices, directory));
            assertTrue(nestedServices.containsAll(REQUIRED_SERVICES), () -> "missing: "
                    + difference(REQUIRED_SERVICES, nestedServices));
        }

        runPackaged("shaded jar", directory.resolve("jar"),
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", SHADED_JAR.toAbsolutePath().toString());
        Path extracted = directory.resolve("zip");
        unzip(BINARY_ZIP, extracted);
        Path launcher;
        try (var paths = Files.walk(extracted)) {
            launcher = paths.filter(path -> path.endsWith(Path.of("bin", "ravenroot-server")))
                    .findFirst().orElseThrow();
        }
        runPackaged("binary zip", directory.resolve("zip-run"), "sh", launcher.toString());
    }

    private static void collectNestedServices(ZipFile zip, String entryName, Set<String> found, Path directory) {
        try {
            Path nested = Files.createTempFile(directory, "dependency-", ".jar");
            try (var input = zip.getInputStream(zip.getEntry(entryName))) {
                Files.copy(input, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (var jar = new JarFile(nested.toFile())) {
                REQUIRED_SERVICES.stream().filter(name -> jar.getEntry(name) != null).forEach(found::add);
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        var missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static void runPackaged(String label, Path state, String... command) throws Exception {
        Files.createDirectories(state);
        int httpPort = freePort();
        int websocketPort = freePort();
        Path log = state.resolve("server.log");
        var builder = new ProcessBuilder(command);
        builder.directory(state.toFile());
        builder.environment().put("RAVENROOT_AUTH_MODE", "local-token");
        builder.environment().put("RAVENROOT_AUTH_LOCAL_TOKEN", TOKEN);
        builder.environment().put("RAVENROOT_BIND_ADDRESS", "127.0.0.1");
        builder.environment().put("RAVENROOT_PORT", Integer.toString(httpPort));
        builder.environment().put("RAVENROOT_WEBSOCKET_ENABLED", "true");
        builder.environment().put("RAVENROOT_WEBSOCKET_BIND", "127.0.0.1");
        builder.environment().put("RAVENROOT_WEBSOCKET_PORT", Integer.toString(websocketPort));
        builder.environment().put("RAVENROOT_EXECUTION_STORE_DIR", state.resolve("store").toString());
        builder.environment().put("RAVENROOT_ARTIFACT_STORE_DIR", state.resolve("artifacts").toString());
        builder.environment().put("RAVENROOT_AUDIT_DIR", state.resolve("audit").toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            waitForHealth(label, process, httpPort, log);
            var listener = new Listener();
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .subprotocols("ravenroot.interactions.v1")
                    .buildAsync(URI.create("ws://127.0.0.1:" + websocketPort + "/v1/interactions"), listener)
                    .get(10, TimeUnit.SECONDS);
            socket.sendText("{\"version\":1,\"type\":\"authenticate\",\"bearer\":\"" + TOKEN + "\"}", true)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"type\":\"authenticated\""));
            socket.sendText("{\"version\":1,\"type\":\"command\",\"messageId\":\"package-smoke\","
                    + "\"command\":\"human-task.cancel\",\"taskId\":\"" + UUID.randomUUID()
                    + "\",\"generation\":1}", true).get(5, TimeUnit.SECONDS);
            assertTrue(listener.messages.poll(5, TimeUnit.SECONDS).contains("\"code\":\"RESOURCE_REFUSED\""));
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        assertTrue(!Files.readString(log, StandardCharsets.UTF_8).contains(TOKEN),
                () -> label + " leaked the bearer into its log");
    }

    private static void waitForHealth(String label, Process process, int port, Path log) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) fail(label + " exited early:\n" + Files.readString(log));
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return;
            } catch (IOException ignored) { }
            Thread.sleep(200);
        }
        fail(label + " did not become healthy:\n" + Files.readString(log));
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void unzip(Path archive, Path target) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                Path output = target.resolve(entry.getName()).normalize();
                if (!output.startsWith(target)) throw new IOException("unsafe archive path");
                if (entry.isDirectory()) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    try (var input = zip.getInputStream(entry)) { Files.copy(input, output); }
                }
            }
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder current = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data,
                                                                        boolean last) {
            current.append(data);
            if (last) {
                messages.add(current.toString());
                current.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
