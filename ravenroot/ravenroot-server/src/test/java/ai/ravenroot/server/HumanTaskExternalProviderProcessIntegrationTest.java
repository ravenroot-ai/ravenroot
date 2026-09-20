package ai.ravenroot.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-child-JVM proof for the configured external Human Task provider protocol. */
class HumanTaskExternalProviderProcessIntegrationTest {
    private static final String TOKEN = "human-task-external-fixture-token-0123456789";
    private static final byte[] CAPABILITY_SECRET =
            "fixture-capability-secret-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROVIDER_SECRET =
            "fixture-provider-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir Path directory;

    @Test
    void publicProviderCompletionIsCorsBoundSignedReplaySafeAndDurableAcrossRestart() throws Exception {
        int backendPort = freePort();
        int providerPort = freePort();
        String providerOrigin = "http://127.0.0.1:" + providerPort;
        String workbenchOrigin = "http://127.0.0.1:" + backendPort;
        Path ui = Files.createDirectories(directory.resolve("ui"));
        Files.writeString(ui.resolve("index.html"), "<!doctype html><title>fixture</title>");
        Path database = directory.resolve("external.db");
        Path configuration = interactionConfiguration(providerOrigin);

        try (Child first = start("first", backendPort, providerOrigin, database, ui, configuration)) {
            Ready initial = first.awaitReady(HumanTaskConfirmationWorkbenchProcess.TASKS_READY);
            String firstLocator = initial.locators().get(0);
            String secondLocator = initial.locators().get(1);
            Launch revoked = issue(backendPort, workbenchOrigin, firstLocator);
            Launch durable = issue(backendPort, workbenchOrigin, secondLocator);
            Launch expiring = issue(backendPort, workbenchOrigin, secondLocator);

            HttpResponse<String> preflight = send(HttpRequest.newBuilder(completionUri(backendPort))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", providerOrigin)
                    .header("Access-Control-Request-Method", "POST").build());
            assertTrue(preflight.statusCode() == 200 || preflight.statusCode() == 204);
            assertEquals(providerOrigin, preflight.headers()
                    .firstValue("Access-Control-Allow-Origin").orElseThrow());

            byte[] denied = completionBody(durable.capability(), "DENY", "provider checked");
            assertEquals(403, callback(backendPort, "http://127.0.0.1:" + freePort(), denied,
                    signature(denied)).statusCode(), "origin is exact");
            assertEquals(403, callback(backendPort, providerOrigin, denied,
                    "sha256=" + "0".repeat(64)).statusCode(), "signature covers exact body bytes");

            revoke(backendPort, revoked);
            first.close();
            try (Child restarted = start("recovery", backendPort, providerOrigin, database, ui, configuration)) {
                restarted.awaitReady(HumanTaskConfirmationWorkbenchProcess.RECOVERY_READY);

                byte[] revokedBody = completionBody(revoked.capability(), "DENY", "revoked");
                assertEquals(409, callback(backendPort, providerOrigin, revokedBody,
                        signature(revokedBody)).statusCode(), "revocation survives child restart");

        // Deliberately discard the first success response. Replaying the exact provider callback is
        // the public lost-response reconciliation path and must report the same durable outcome.
                HttpResponse<Void> lost = HttpClient.newHttpClient().send(callbackRequest(
                        backendPort, providerOrigin, denied, signature(denied)),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(200, lost.statusCode());
                HttpResponse<String> replay = callback(backendPort, providerOrigin, denied, signature(denied));
                assertEquals(200, replay.statusCode());
                assertTrue(replay.body().contains("ALREADY_APPLIED"), replay.body());

                byte[] conflicting = completionBody(durable.capability(), "CANCEL", "stale generation");
                assertEquals(409, callback(backendPort, providerOrigin, conflicting,
                        signature(conflicting)).statusCode(), "terminal generation fence refuses another outcome");

                long remaining = Duration.between(java.time.Instant.now(), expiring.expiresAt()).toMillis();
                if (remaining > 0) Thread.sleep(remaining + 100);
                byte[] expired = completionBody(expiring.capability(), "DENY", "expired");
                assertEquals(409, callback(backendPort, providerOrigin, expired,
                        signature(expired)).statusCode());
            }
        }
    }

    @Test
    void publicLaunchRefusesAnExternalProviderAtTheWorkbenchOrigin() throws Exception {
        int backendPort = freePort();
        String workbenchOrigin = "http://127.0.0.1:" + backendPort;
        Path ui = Files.createDirectories(directory.resolve("same-origin-ui"));
        Files.writeString(ui.resolve("index.html"), "<!doctype html><title>fixture</title>");
        Path configuration = interactionConfiguration(workbenchOrigin);
        try (Child child = start("first", backendPort, workbenchOrigin,
                directory.resolve("same-origin.db"), ui, configuration)) {
            String locator = child.awaitReady(HumanTaskConfirmationWorkbenchProcess.TASKS_READY)
                    .locators().getFirst();
            assertEquals(403, issueResponse(backendPort, workbenchOrigin, locator).statusCode(),
                    "a script-capable external frame must never share the Workbench origin");
        }
    }

    private Path interactionConfiguration(String providerOrigin) throws Exception {
        Path path = directory.resolve("interactions.json");
        String json = "{\"schemaVersion\":1,\"capabilityTtlSeconds\":8,"
                + "\"maxCompletionBytes\":65536,\"capabilitySecretBase64\":\""
                + Base64.getEncoder().encodeToString(CAPABILITY_SECRET) + "\",\"profiles\":[{"
                + "\"id\":\"fixture-provider\",\"version\":1,\"kind\":\"EXTERNAL\","
                + "\"launchUri\":\"" + providerOrigin + "/task\",\"origin\":\""
                + providerOrigin + "\",\"completionSecretBase64\":\""
                + Base64.getEncoder().encodeToString(PROVIDER_SECRET) + "\"}]}";
        Files.writeString(path, json);
        return path;
    }

    private Child start(String phase, int port, String providerOrigin, Path database, Path ui,
                        Path configuration)
            throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(HumanTaskConfirmationWorkbenchProcess.class.getName());
        command.add("--phase=" + phase);
        command.add("--database=" + database);
        command.add("--port=" + port);
        command.add("--token=" + TOKEN);
        command.add("--ui-dir=" + ui);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("RAVENROOT_HUMAN_TASK_INTERACTION_CONFIG", configuration.toString());
        builder.environment().put("RAVENROOT_HUMAN_TASK_FIXTURE_PRESENTATION_KIND", "EXTERNAL");
        builder.environment().put("RAVENROOT_BROWSER_ALLOWED_ORIGINS", providerOrigin);
        builder.environment().put("RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_PROMPT_BYTES", "8192");
        builder.environment().put("RAVENROOT_HUMAN_TASK_MAX_DECISION_COMMENT_BYTES", "8192");
        return new Child(builder.start());
    }

    private static Launch issue(int port, String origin, String locator) throws Exception {
        HttpResponse<String> response = issueResponse(port, origin, locator);
        assertEquals(200, response.statusCode(), response.body());
        String[] parts = locator.split(":", 2);
        return new Launch(jsonText(response.body(), "capability"), UUID.fromString(parts[0]),
                Long.parseLong(parts[1]), java.time.Instant.parse(jsonText(response.body(), "expiresAt")));
    }

    private static HttpResponse<String> issueResponse(int port, String origin, String locator)
            throws Exception {
        String[] parts = locator.split(":", 2);
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + port + "/v1/human-tasks/" + parts[0] + "/interaction?generation=" + parts[1]))
                .POST(HttpRequest.BodyPublishers.noBody()).header("Authorization", "Bearer " + TOKEN)
                .header("Origin", origin).build());
    }

    private static void revoke(int port, Launch launch) throws Exception {
        String body = "{\"schemaVersion\":1,\"capability\":\"" + launch.capability() + "\"}";
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + port + "/v1/human-tasks/" + launch.taskId()
                        + "/interaction?generation=" + launch.generation()))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json").build());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static byte[] completionBody(String capability, String action, String comment) {
        return ("{\"schemaVersion\":1,\"capability\":\"" + capability + "\",\"action\":\""
                + action + "\",\"comment\":\"" + comment + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> callback(int port, String origin, byte[] body, String signature)
            throws Exception {
        return HttpClient.newHttpClient().send(callbackRequest(port, origin, body, signature),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest callbackRequest(int port, String origin, byte[] body, String signature) {
        return HttpRequest.newBuilder(completionUri(port)).POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json").header("Origin", origin)
                .header("X-Ravenroot-Provider-Signature", signature).build();
    }

    private static URI completionUri(int port) {
        return URI.create("http://127.0.0.1:" + port + "/v1/human-task-interactions/complete");
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PROVIDER_SECRET, "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static String jsonText(String json, String name) {
        var matcher = Pattern.compile("\\\"" + name + "\\\":\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("missing " + name + ": " + json);
        return matcher.group(1);
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private record Launch(String capability, UUID taskId, long generation,
                          java.time.Instant expiresAt) { }
    private record Ready(List<String> locators) { }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final StringBuilder transcript = new StringBuilder();
        private final Thread drain;
        private boolean closed;

        private Child(Process process) {
            this.process = process;
            drain = Thread.ofVirtual().start(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        synchronized (transcript) { transcript.append(line).append('\n'); }
                        output.offer(line);
                    }
                } catch (Exception ignored) { }
            });
        }

        private Ready awaitReady(String marker) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                String line = output.poll(250, TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith(marker + " ")) {
                    var locators = new ArrayList<String>();
                    var matches = Pattern.compile("\\{\\\"taskId\\\":\\\"([^\\\"]+)\\\","
                            + "\\\"generation\\\":(\\d+)\\}").matcher(line);
                    while (matches.find()) locators.add(matches.group(1) + ":" + matches.group(2));
                    assertEquals(2, locators.size(), line);
                    return new Ready(List.copyOf(locators));
                }
                if (!process.isAlive()) throw new AssertionError("child exited: " + transcript());
            }
            throw new AssertionError("child readiness timed out: " + transcript());
        }

        private String transcript() {
            synchronized (transcript) { return transcript.toString(); }
        }

        @Override public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            process.destroy();
            if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fixture child survived teardown");
            drain.join(Duration.ofSeconds(3));
        }
    }
}
