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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final Map<String, Set<String>> REQUIRED_PROVIDERS = Map.of(
            "META-INF/services/org.eclipse.jetty.websocket.api.ExtensionConfig$Parser",
            Set.of("org.eclipse.jetty.websocket.common.ExtensionConfigParser"),
            "META-INF/services/org.eclipse.jetty.websocket.core.Extension",
            Set.of("org.eclipse.jetty.websocket.core.internal.FragmentExtension",
                    "org.eclipse.jetty.websocket.core.internal.FrameCaptureExtension",
                    "org.eclipse.jetty.websocket.core.internal.IdentityExtension",
                    "org.eclipse.jetty.websocket.core.internal.PerMessageDeflateExtension",
                    "org.eclipse.jetty.websocket.core.internal.ValidationExtension"),
            "META-INF/services/org.eclipse.jetty.http.HttpFieldPreEncoder",
            Set.of("org.eclipse.jetty.http.Http10FieldPreEncoder",
                    "org.eclipse.jetty.http.Http11FieldPreEncoder"));
    private static final Set<String> REQUIRED_LICENSES = Set.of(
            "META-INF/licenses/jetty-12.1.12-LICENSE.txt",
            "META-INF/licenses/jetty-12.1.12-NOTICE.txt",
            "META-INF/licenses/slf4j-api-2.0.17-LICENSE.txt");

    @Test
    void shadedJarAndZipPreserveServicesAttributionAndWire(@TempDir Path directory) throws Exception {
        assertTrue(Files.isRegularFile(SHADED_JAR));
        assertTrue(Files.isRegularFile(BINARY_ZIP));
        try (var jar = new JarFile(SHADED_JAR.toFile())) {
            assertEquals("true", jar.getManifest().getMainAttributes().getValue("Multi-Release"));
            assertServiceInventory(jar, "shaded jar");
            REQUIRED_LICENSES.forEach(name -> assertTrue(jar.getEntry(name) != null, name));
        }
        try (var zip = new ZipFile(BINARY_ZIP.toFile())) {
            REQUIRED_LICENSES.forEach(name -> assertTrue(zip.stream().anyMatch(entry ->
                    entry.getName().endsWith("/licenses/" + name.substring(name.lastIndexOf('/') + 1))), name));
            var nestedProviders = new HashMap<String, Set<String>>();
            Set<String> nestedClasses = new HashSet<>();
            zip.stream().filter(entry -> entry.getName().contains("/lib/") && entry.getName().endsWith(".jar"))
                    .forEach(entry -> collectNestedServices(zip, entry.getName(), nestedProviders,
                            nestedClasses, directory));
            REQUIRED_PROVIDERS.forEach((service, expected) ->
                    assertEquals(expected, nestedProviders.get(service), service));
            REQUIRED_PROVIDERS.values().stream().flatMap(Set::stream).forEach(provider ->
                    assertTrue(nestedClasses.contains(providerClass(provider)), provider));
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
        runPackaged("binary zip", directory.resolve("zip-run"), "/bin/sh", launcher.toString());
    }

    private static void assertServiceInventory(JarFile jar, String label) throws IOException {
        for (var required : REQUIRED_PROVIDERS.entrySet()) {
            var entry = jar.getJarEntry(required.getKey());
            assertTrue(entry != null, required.getKey());
            try (var input = jar.getInputStream(entry)) {
                assertEquals(required.getValue(), providers(input.readAllBytes()), label + ": " + required.getKey());
            }
            required.getValue().forEach(provider ->
                    assertTrue(jar.getEntry(providerClass(provider)) != null, label + ": " + provider));
        }
    }

    private static void collectNestedServices(ZipFile zip, String entryName,
                                              Map<String, Set<String>> foundProviders,
                                              Set<String> foundClasses, Path directory) {
        try {
            Path nested = Files.createTempFile(directory, "dependency-", ".jar");
            try (var input = zip.getInputStream(zip.getEntry(entryName))) {
                Files.copy(input, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (var jar = new JarFile(nested.toFile())) {
                for (String service : REQUIRED_SERVICES) {
                    var entry = jar.getJarEntry(service);
                    if (entry != null) {
                        try (var input = jar.getInputStream(entry)) {
                            foundProviders.merge(service, providers(input.readAllBytes()), (left, right) -> {
                                var combined = new HashSet<>(left);
                                combined.addAll(right);
                                return Set.copyOf(combined);
                            });
                        }
                    }
                }
                REQUIRED_PROVIDERS.values().stream().flatMap(Set::stream).map(InteractionWebSocketPackagingIT::providerClass)
                        .filter(name -> jar.getEntry(name) != null).forEach(foundClasses::add);
            }
            Files.deleteIfExists(nested);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Set<String> providers(byte[] bytes) {
        return java.util.Arrays.stream(new String(bytes, StandardCharsets.UTF_8).split("\\R"))
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String providerClass(String provider) {
        return provider.replace('.', '/') + ".class";
    }

    private static void runPackaged(String label, Path state, String... command) throws Exception {
        Files.createDirectories(state);
        int httpPort = freePort();
        int websocketPort = freePort();
        Path log = state.resolve("server.log");
        var builder = new ProcessBuilder(command);
        builder.directory(state.toFile());
        String inheritedPath = builder.environment().getOrDefault("PATH", "");
        builder.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin")
                + java.io.File.pathSeparator + inheritedPath);
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
            Task task = createHumanTask(httpPort);
            socket.sendText("{\"version\":1,\"type\":\"command\",\"messageId\":\"package-smoke\","
                    + "\"command\":\"human-task.cancel\",\"taskId\":\"" + task.taskId()
                    + "\",\"generation\":" + task.generation() + "}", true).get(5, TimeUnit.SECONDS);
            String result = listener.messages.poll(5, TimeUnit.SECONDS);
            assertTrue(result.contains("\"type\":\"command.result\""), result);
            assertTrue(result.contains("\"inReplyTo\":\"package-smoke\""), result);
            assertTrue(result.contains("\"taskId\":\"" + task.taskId() + "\""), result);
            assertTrue(result.contains("\"outcome\":\"cancelled\""), result);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            assertEquals(WebSocket.NORMAL_CLOSURE, listener.closeCode.get(5, TimeUnit.SECONDS));
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), label + " resisted forced termination");
                fail(label + " required forced termination");
            }
        }
        assertTrue(!Files.readString(log, StandardCharsets.UTF_8).contains(TOKEN),
                () -> label + " leaked the bearer into its log");
    }

    private static Task createHumanTask(int httpPort) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var submitted = client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + httpPort + "/v1/executions?mode=run&payload=package-smoke"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/graphml+xml")
                .POST(HttpRequest.BodyPublishers.ofString(HUMAN_TASK_GRAPH)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, submitted.statusCode(), submitted.body());
        Instant deadline = Instant.now().plusSeconds(20);
        var taskPattern = java.util.regex.Pattern.compile("\\\"taskId\\\":\\\"([^\\\"]+)\\\".*?"
                + "\\\"generation\\\":(\\d+)");
        while (Instant.now().isBefore(deadline)) {
            var inbox = client.send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + httpPort + "/v1/human-tasks?limit=20"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, inbox.statusCode(), inbox.body());
            var match = taskPattern.matcher(inbox.body());
            if (match.find()) return new Task(UUID.fromString(match.group(1)), Long.parseLong(match.group(2)));
            Thread.sleep(200);
        }
        fail("packaged server did not expose the submitted durable Human Task");
        throw new AssertionError("unreachable");
    }

    private record Task(UUID taskId, long generation) { }

    private static final String HUMAN_TASK_GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <key id="property-title" for="node" attr.name="title" attr.type="string"/>
              <graph id="package-human-task" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="action"><data key="kind">BEHAVIOR</data><data key="behavior">human-task</data>
                  <data key="property-title">Package smoke review</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge id="start-action" source="start" target="action"><data key="outcome">continue</data></edge>
                <edge id="resolved" source="action" target="end"><data key="outcome">resolved</data></edge>
                <edge id="denied" source="action" target="end"><data key="outcome">denied</data></edge>
                <edge id="expired" source="action" target="end"><data key="outcome">expired</data></edge>
                <edge id="cancelled" source="action" target="end"><data key="outcome">cancelled</data></edge>
                <edge id="failure" source="action" target="error"/>
              </graph>
            </graphml>
            """;

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
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
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
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int statusCode,
                                                                         String reason) {
            closeCode.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }
    }
}
