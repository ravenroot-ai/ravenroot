package ai.ravenroot.server;

import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parent-JVM control boundary for the real confirmation-browser restart scenario.
 *
 * <p>The loopback control server is a JUnit-only owner of one child at a time. It has no bearer
 * input and returns only safe task locators and context pins; browser authentication still takes
 * place through the workbench UI. The child’s continuous transcript drain makes readiness failures
 * diagnosable without risking a full stdout pipe deadlock.</p>
 */
class HumanTaskConfirmationBrowserProcessIntegrationTest {
    static final String TENANT = HumanTaskConfirmationWorkbenchProcess.TENANT;
    static final String DEPLOYMENT_ID = HumanTaskConfirmationWorkbenchProcess.DEPLOYMENT_ID;
    static final String GRAPH_ID = HumanTaskConfirmationWorkbenchProcess.GRAPH_ID;
    static final String NODE_ID = HumanTaskConfirmationWorkbenchProcess.NODE_ID;
    static final String DOWNSTREAM_NODE_ID = HumanTaskConfirmationWorkbenchProcess.DOWNSTREAM_NODE_ID;
    static final String FIXTURE_TOKEN = "human-task-e2e-nonsecret-fixture-token-0123456789";
    static final String PINNED_RECOVERY_COMMENT = "R".repeat(5_000);

    @Test
    void createsTwoDeploymentHostedTasksThenReopensTheSameStoreWithoutLocalRegistration()
            throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.humanTaskConfirmation.browserTest"),
                "run after the companion real-browser scenario is enabled");
        int backendPort = freePort();
        Path output = Files.createDirectories(Path.of(System.getProperty("user.dir"), "target",
                "human-task-confirmation-browser", UUID.randomUUID().toString()));
        Ready verify;
        try (Control control = new Control(backendPort, output)) {
            runBrowser(control, output);

            verify = control.start(Phase.VERIFY);
            assertEquals(Phase.VERIFY, verify.phase());
            assertEquals(2, verify.locatorCount(), verify.json());
        }
        awaitNoReplayVisibilityWindow();
        assertDurableSettlement(output, verify);
    }

    private static void awaitNoReplayVisibilityWindow() throws InterruptedException {
        Thread.sleep(HumanTaskConfirmationWorkbenchProcess.NO_REPLAY_VISIBILITY_WINDOW);
    }

    private static void assertDurableSettlement(Path output, Ready verify) throws Exception {
        Path database = output.resolve("execution-store").resolve("confirmation.db");
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC(), HumanTaskPolicy.DEFAULTS)) {
            var comments = new java.util.HashSet<String>();
            for (String locator : verify.locators()) {
                UUID taskId = UUID.fromString(locator.substring(0, locator.indexOf(':')));
                var task = store.loadHumanTask(TENANT, taskId).toCompletableFuture().join().orElseThrow();
                assertEquals(HumanTaskStatus.RESOLVED, task.status(), "terminal task status");
                assertEquals("urn:ravenroot:local|WORKLOAD|local-workload", task.actor(),
                        "browser decision actor");
                comments.add(task.decisionComment());
                var process = store.load(task.key()).toCompletableFuture().join();
                assertEquals(ProcessInstanceStatus.COMPLETED, process.state().status(),
                        "resolved process completes after durable re-entry");
                assertEquals(2, process.state().traversals().size(),
                        "each process retains its original traversal and exactly one re-entry traversal");
                long downstreamVisits = process.state().traversals().values().stream()
                        .flatMap(traversal -> traversal.invocations().values().stream())
                        .filter(invocation -> DOWNSTREAM_NODE_ID.equals(invocation.nodeId()))
                        .filter(invocation -> invocation.status() == NodeInvocationStatus.COMPLETED)
                        .count();
                assertEquals(1, downstreamVisits,
                        "each durable ingress has one downstream completion after replay");
            }
            assertEquals(java.util.Set.of(PINNED_RECOVERY_COMMENT,
                    "Reviewed once through the central form."), comments,
                    "comments remain durable metadata rather than execution payload");
            assertTrue(store.claimPendingWork(TENANT, "human-task-confirmation-parent-probe", 100,
                    HumanTaskConfirmationWorkbenchProcess.FIXTURE_WORK_CLAIM_LEASE)
                    .toCompletableFuture().join().isEmpty(),
                    "third child left no replayable continuation after the complete visibility window");
        }
    }

    private static void runBrowser(Control control, Path output) throws Exception {
        Path ui = Path.of(System.getProperty("user.dir")).resolve("../ravenroot-ui").toAbsolutePath().normalize();
        Path executable = ui.resolve("node_modules/.bin/playwright");
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException("the installed Playwright binary is required for the real harness");
        }
        var process = new ProcessBuilder(executable.toString(), "test",
                "--config=playwright.human-task-confirmation.config.js")
                .directory(ui.toFile()).redirectErrorStream(true)
                .redirectOutput(output.resolve("playwright-driver.log").toFile());
        process.environment().put("RAVENROOT_HUMAN_TASK_CONFIRMATION_CONTROL_ORIGIN", control.origin());
        process.environment().put("RAVENROOT_HUMAN_TASK_CONFIRMATION_FIXTURE_TOKEN", FIXTURE_TOKEN);
        // Keep this nested project's evidence with its UUID-scoped harness output. The complete UI
        // suite runs afterward and owns the UI project's default test-results directory.
        process.environment().put("RAVENROOT_HUMAN_TASK_CONFIRMATION_OUTPUT_DIR",
                output.resolve("playwright-results").toString());
        Process browser = process.start();
        if (!browser.waitFor(130, TimeUnit.SECONDS)) {
            browser.destroyForcibly();
            throw new AssertionError("real confirmation Playwright process exceeded its bounded timeout");
        }
        assertEquals(0, browser.exitValue(), () -> "real confirmation browser failed: "
                + log(output.resolve("playwright-driver.log")));
    }

    private static String log(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            return "(Playwright transcript unavailable)";
        }
    }

    private static HttpResponse<String> request(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private enum Phase {
        FIRST("first", HumanTaskConfirmationWorkbenchProcess.TASKS_READY, 8_192),
        RECOVERY("recovery", HumanTaskConfirmationWorkbenchProcess.RECOVERY_READY, 4_096),
        VERIFY("verify", HumanTaskConfirmationWorkbenchProcess.RECOVERY_READY, 16_384);

        private final String wire;
        private final String marker;
        private final int confirmationLimit;

        Phase(String wire, String marker, int confirmationLimit) {
            this.wire = wire;
            this.marker = marker;
            this.confirmationLimit = confirmationLimit;
        }

        static Phase fromWire(String value) {
            for (Phase phase : values()) if (phase.wire.equals(value)) return phase;
            throw new IllegalArgumentException("unknown fixture phase");
        }
    }

    private record Ready(Phase phase, String json, List<String> locators) {
        private int locatorCount() {
            return locators.size();
        }
    }

    private final class Control implements AutoCloseable {
        private final HttpServer server;
        private final int backendPort;
        private final Path output;
        private volatile Child child;
        private volatile Ready ready;

        private Control(int backendPort, Path output) throws IOException {
            this.backendPort = backendPort;
            this.output = output;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/start", this::start);
            server.createContext("/stop", this::stop);
            server.createContext("/ready", this::ready);
            server.createContext("/graph", this::graph);
            server.start();
        }

        private Ready start(Phase phase) throws Exception {
            if (child != null) throw new IllegalStateException("fixture child is already running");
            Child started = launch(phase);
            child = started;
            try {
                Ready observed = started.awaitReady(phase);
                ready = observed;
                return observed;
            } catch (Exception failure) {
                stop();
                throw failure;
            }
        }

        private Child launch(Phase phase) throws IOException {
            Path transcript = output.resolve("child-" + phase.wire + ".log");
            Path database = output.resolve("execution-store").resolve("confirmation.db");
            Files.createDirectories(database.getParent());
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Path uiDirectory = Path.of(System.getProperty("user.dir")).resolve("../ravenroot-ui/dist")
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(uiDirectory.resolve("index.html"))) {
                throw new IllegalStateException("the verified UI dist asset is required before the harness starts");
            }
            var command = new ArrayList<String>();
            command.add(java.toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(HumanTaskConfirmationWorkbenchProcess.class.getName());
            command.add("--phase=" + phase.wire);
            command.add("--database=" + database);
            command.add("--port=" + backendPort);
            command.add("--token=" + FIXTURE_TOKEN);
            command.add("--ui-dir=" + uiDirectory);
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            // These are non-secret fixture bounds. They deliberately tighten after the first child
            // and loosen again for the third, while the task keeps its first-child pinned limits.
            builder.environment().put("RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_PROMPT_BYTES",
                    Integer.toString(phase.confirmationLimit));
            builder.environment().put("RAVENROOT_HUMAN_TASK_MAX_DECISION_COMMENT_BYTES",
                    Integer.toString(phase.confirmationLimit));
            var process = builder.start();
            return new Child(process, transcript, phase);
        }

        private void start(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    reply(exchange, 405, "");
                    return;
                }
                Ready started = start(Phase.fromWire(query(exchange).get("phase")));
                reply(exchange, 200, started.json());
            } catch (Exception | AssertionError failure) {
                reply(exchange, 500, "{\"error\":\"fixture start failed\"}");
            }
        }

        private void stop(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    reply(exchange, 405, "");
                    return;
                }
                stop();
                reply(exchange, 204, "");
            } catch (Exception failure) {
                reply(exchange, 500, "{\"error\":\"fixture stop failed\"}");
            }
        }

        private void ready(HttpExchange exchange) throws IOException {
            Ready observed = ready;
            if (observed == null) {
                reply(exchange, 409, "{\"status\":\"not-started\"}");
                return;
            }
            reply(exchange, 200, observed.json());
        }

        private void graph(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                reply(exchange, 405, "");
                return;
            }
            replyGraphMl(exchange, HumanTaskConfirmationWorkbenchProcess.graph());
        }

        private synchronized void stop() throws Exception {
            Child owned = child;
            child = null;
            if (owned == null) return;
            owned.stop();
        }

        private String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override public void close() throws Exception {
            stop();
            server.stop(0);
        }
    }

    private static final class Child {
        private final Process process;
        private final Path transcript;
        private final Phase phase;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Thread drain;

        private Child(Process process, Path transcript, Phase phase) {
            this.process = process;
            this.transcript = transcript;
            this.phase = phase;
            drain = Thread.ofVirtual().name("human-task-confirmation-transcript-" + phase.wire).start(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8);
                     var writer = Files.newBufferedWriter(transcript, StandardCharsets.UTF_8)) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        lines.offer(line);
                    }
                } catch (IOException ignored) {
                    // The owning assertion reports the bounded transcript on readiness failure.
                }
            });
        }

        private Ready awaitReady(Phase expected) throws Exception {
            Duration timeout = expected == Phase.VERIFY
                    ? HumanTaskConfirmationWorkbenchProcess.VERIFY_READY_TIMEOUT
                    : Duration.ofSeconds(45);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(250, TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith(expected.marker + " ")) {
                    String json = line.substring(expected.marker.length() + 1);
                    return new Ready(expected, json, locators(json));
                }
                if (!process.isAlive()) {
                    throw new AssertionError("fixture child exited before readiness: " + transcriptText());
                }
            }
            throw new AssertionError("fixture child did not become ready: " + transcriptText());
        }

        private void stop() throws Exception {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "owned child survived teardown");
            drain.join(Duration.ofSeconds(5));
        }

        private String transcriptText() {
            try {
                return Files.exists(transcript) ? Files.readString(transcript, StandardCharsets.UTF_8) : "";
            } catch (IOException ignored) {
                return "";
            }
        }
    }

    private static List<String> locators(String json) {
        var ids = new ArrayList<String>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\{\\\"taskId\\\":\\\"([^\\\"]+)\\\",\\\"generation\\\":(\\d+)\\}")
                .matcher(json);
        while (matcher.find()) ids.add(matcher.group(1) + ":" + matcher.group(2));
        return List.copyOf(ids);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return Map.of();
        return java.util.Arrays.stream(raw.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "",
                        (left, right) -> right));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void replyGraphMl(HttpExchange exchange, String graph) throws IOException {
        byte[] bytes = graph.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
