package ai.ravenroot.distribution;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QA-04b. Drives a real, separately-launched {@code ravenroot.jar}
 * over plain HTTP the way an operator or the CI smoke-test job does - not the in-process {@code
 * RavenrootServer} unit tests in {@code ravenroot-server}, which never leave the JVM and never
 * touch the packaged artifact.
 *
 * <p>Runs as a Failsafe {@code IT}, alongside {@link ReleaseArtifactModelAdapterBoundaryIT} in this
 * module: both need {@code target/ravenroot.jar}, which exists only after {@code package}, and this
 * module already has {@code maven-failsafe-plugin} bound to its default {@code
 * integration-test}/{@code verify} phases (see that class's Javadoc). No pom change was needed.
 *
 * <p><b>Server lifecycle.</b> A free loopback port is chosen with {@code new ServerSocket(0)}
 * (the same idiom {@code ravenroot-mail}'s test fixtures use), released immediately, then handed to
 * the child process as {@code RAVENROOT_PORT} - the same env vars CI's {@code smoke-test} job uses
 * ({@code RAVENROOT_AUTH_MODE=disabled}, {@code RAVENROOT_BIND_ADDRESS=127.0.0.1}). Start-up is
 * confirmed by polling {@code GET /health} with a bounded deadline; if the process dies or the
 * deadline elapses first, {@link #waitForHealth()} fails the build immediately with the captured
 * process log instead of leaving the suite to hang. Teardown ({@link #stopServer()}) always runs and
 * force-destroys the process, so a failing test never leaks a listening JVM into the next build step.
 */
class RavenrootExecutionLifecycleIT {

    private static final Path RAVENROOT_JAR = Path.of("target", "ravenroot.jar");
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(15);
    // Fixed rather than short-circuited on completion: see the Javadoc note inside
    // sseStreamReportsJournalledEventsAndNeverATraversalTerminalFrame for why. Node events for this
    // fixture arrive within ~200ms in practice; this leaves a wide margin before asserting none of
    // the traffic seen included a terminal frame.
    private static final Duration SSE_OBSERVATION_WINDOW = Duration.ofSeconds(6);

    private static final String EXECUTABLE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="lifecycle-it" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="future"><data key="kind">BEHAVIOR</data><data key="behavior">future-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="future"><data key="outcome">continue</data></edge>
                <edge id="e2" source="future" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static Process process;
    private static int port;
    private static Path logFile;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        assertTrue(Files.exists(RAVENROOT_JAR),
                () -> "Missing " + RAVENROOT_JAR.toAbsolutePath()
                        + " - this IT must run after package (mvn ... package failsafe:integration-test), "
                        + "same as ReleaseArtifactModelAdapterBoundaryIT.");

        port = choosePort();
        logFile = Files.createTempFile("ravenroot-lifecycle-it-", ".log");
        // The server's execution store and audit trail default to "./data/..." relative to the
        // process's working directory and are durable across restarts (confirmed by hand: a fresh
        // process's first SSE journalOffset was 22, not 1, after three earlier manual launches from
        // this same module directory). Left unset, repeated IT runs would accumulate state in
        // ravenroot-distribution/data/ instead of each run starting from an empty journal.
        Path dataDir = Files.createTempDirectory("ravenroot-lifecycle-it-data-");

        ProcessBuilder builder = new ProcessBuilder(
                resolveJavaBinary(), "-jar", RAVENROOT_JAR.toAbsolutePath().toString());
        builder.environment().put("RAVENROOT_AUTH_MODE", "disabled");
        builder.environment().put("RAVENROOT_BIND_ADDRESS", "127.0.0.1");
        builder.environment().put("RAVENROOT_PORT", Integer.toString(port));
        builder.environment().put("RAVENROOT_EXECUTION_STORE_DIR", dataDir.resolve("execution-store").toString());
        builder.environment().put("RAVENROOT_ARTIFACT_STORE_DIR", dataDir.resolve("artifact-store").toString());
        builder.environment().put("RAVENROOT_AUDIT_DIR", dataDir.resolve("audit").toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        process = builder.start();

        waitForHealth();
    }

    @AfterAll
    static void stopServer() throws InterruptedException {
        if (process == null) {
            return;
        }
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String resolveJavaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int choosePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Fails loudly, with the captured process log, rather than letting the suite hang: if the
     * process exits before the deadline, or the deadline elapses before a 200 is observed, this
     * throws immediately instead of waiting out the full CI job timeout.
     */
    private static void waitForHealth() throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(HEALTH_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                fail("ravenroot.jar exited before becoming healthy (exit code " + process.exitValue()
                        + "). Log:\n" + readLog());
            }
            try {
                HttpResponse<String> response = CLIENT.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .timeout(Duration.ofSeconds(1)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException notReadyYet) {
                // Connection refused / reset while the listener is still coming up - expected, retry.
            }
            Thread.sleep(300);
        }
        process.destroyForcibly();
        fail("ravenroot.jar on port " + port + " never answered GET /health within " + HEALTH_TIMEOUT
                + ". Log:\n" + readLog());
    }

    private static String readLog() throws IOException {
        return Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "(no log captured)";
    }

    // ---- execution start and completion ----------------------------------------------------

    /**
     * Submitted without {@code mode}, so {@code future} -- this
     * graph's only {@code BEHAVIOR} node -- is bypassed here, not run. Harmless for this assertion:
     * {@code future-behavior} is not in the catalog either, so even {@code mode=run} would only
     * default it, never actually construct and execute it. See
     * {@link #sseStreamReportsJournalledEventsAndNeverATraversalTerminalFrame} for the full account
     * of what TEST_PASSTHROUGH does and does not make observable over this exact wire.
     */
    @Test
    void executionStartsAndCompletesOverHttp() throws Exception {
        HttpResponse<String> start = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/executions?payload=hello"))
                        .header("Content-Type", "application/graphml+xml")
                        .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, start.statusCode(), () -> "start response: " + start.body());
        String executionId = jsonString(start.body(), "executionId");
        assertNotNull(executionId, () -> "no executionId in start response: " + start.body());

        HttpResponse<String> result = pollUntilTerminal(executionId);

        assertEquals("COMPLETED", jsonString(result.body(), "status"), () -> result.body());
        assertEquals("hello", jsonString(result.body(), "payload"), () -> result.body());
        assertEquals(executionId, jsonString(result.body(), "executionId"), () -> result.body());
        Object decoded = PayloadJson.read(
                result.body().getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS).toJava();
        Map<?, ?> fields = assertInstanceOf(Map.class, decoded);
        List<?> visitedNodes = assertInstanceOf(List.class, fields.get("visitedNodes"));
        assertEquals(3, visitedNodes.size(), () -> "expected three unique visited nodes: " + result.body());
        assertEquals(Set.of("start", "future", "end"), Set.copyOf(visitedNodes),
                () -> "expected visited-node membership, independent of presentation order: " + result.body());
    }

    private HttpResponse<String> pollUntilTerminal(String executionId) throws Exception {
        Instant deadline = Instant.now().plus(EXECUTION_TIMEOUT);
        HttpResponse<String> last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> attempt = CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/executions/" + executionId))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            last = attempt;
            assertEquals(200, attempt.statusCode(), () -> "polling execution result: " + attempt.body());
            if (!attempt.body().contains("\"status\":\"RUNNING\"") && !attempt.body().contains("\"status\":\"ACCEPTED\"")) {
                return attempt;
            }
            Thread.sleep(300);
        }
        fail("execution " + executionId + " never reached a terminal state within " + EXECUTION_TIMEOUT
                + ". Last observed: " + (last == null ? "(no response)" : last.body()));
        throw new AssertionError("unreachable");
    }

    // ---- SSE event stream --------------------------------------------------------------------

    @Test
    void sseStreamReportsJournalledEventsAndNeverATraversalTerminalFrame() throws Exception {
        HttpRequest sseRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/events"))
                .GET().build();
        java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> sseFuture =
                CLIENT.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
        // Headers (and therefore the underlying connection) are established as soon as this
        // completes, before any execution is submitted below - so no early event is missed.
        HttpResponse<InputStream> sseResponse = sseFuture.get(5, TimeUnit.SECONDS);
        assertEquals(200, sseResponse.statusCode());
        assertTrue(sseResponse.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
                () -> "unexpected content type: " + sseResponse.headers().firstValue("Content-Type"));

        BlockingQueue<String> dataLines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(sseResponse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        dataLines.add(line.substring("data:".length()).trim());
                    }
                }
            } catch (IOException streamClosed) {
                // Expected once the test closes the connection below.
            }
        }, "sse-reader-it");
        reader.setDaemon(true);
        reader.start();

        try {
            HttpResponse<String> start = CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/executions?payload=world"))
                            .header("Content-Type", "application/graphml+xml")
                            .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, start.statusCode(), () -> "start response: " + start.body());
            String executionId = jsonString(start.body(), "executionId");

            boolean sawStarted = false;
            boolean sawNodeActivity = false;
            boolean sawTerminalFrame = false;
            List<String> observed = new java.util.ArrayList<>();
            List<String> terminalFrames = new java.util.ArrayList<>();
            // Fixed observation window rather than an early exit on completion: this jar composes a
            // durable ExecutionStore by default, so GET /v1/events dispatches to
            // RavenrootServer.streamDurableExecutionEvents, whose Javadoc ("No traversal-terminal
            // frame") declares that stream never carries an
            // EXECUTION_COMPLETED/EXECUTION_FAILED frame - completion or failure can arise from a join
            // timeout, an abandoned branch or an aggregate rejection, none of which are journalled, and
            // a synthesised frame would carry no journalOffset and corrupt the Last-Event-ID cursor. A
            // client determines a traversal has ended by observing /v1/runtime's activeExecutions
            // instead (see awaitActiveExecutionsDrain below), never from this stream. There is
            // therefore nothing to wait for early: the assertion below is that no terminal frame ever
            // arrives, so the loop always runs the full window.
            Instant deadline = Instant.now().plus(SSE_OBSERVATION_WINDOW);
            while (Instant.now().isBefore(deadline)) {
                String data = dataLines.poll(300, TimeUnit.MILLISECONDS);
                if (data == null || !data.contains("\"traversalId\":\"" + executionId + "\"")) {
                    continue;
                }
                observed.add(data);
                if (data.contains("\"eventType\":\"EXECUTION_STARTED\"")) {
                    sawStarted = true;
                }
                // This disjunction used to list NODE_COMPLETED and NODE_DEFAULTED as well.
                // Both were unreachable here, and the second one was unreachable on the durable path
                // ANYWHERE -- NODE_DEFAULTED was published only to the in-memory stream and never
                // journalled, which was the defect in the durable path. The disjunction was harmless
                // because it is permissive (NODE_STARTED alone carries the assertion), and that is
                // exactly why it survived: it encoded the wrong assumption in a place where nothing
                // could contradict it.
                //
                // NODE_DEFAULTED is now journalled, so it is no longer wrong in general -- but it is
                // still unreachable FROM THIS SUBMISSION, and so is NODE_COMPLETED, for a reason
                // worth recording because it is not obvious from the request above: POST
                // /v1/executions defaults its `mode` parameter to "test"
                // (RavenrootServer.submitExecution), which is ExecutionPolicy.TEST_PASSTHROUGH, and
                // that delivers NodeCommand.PASSTHROUGH to every node. Every node in this run is
                // therefore BYPASSED -- none completes, and none defaults, because a bypassed node's
                // behavior is never constructed at all. Narrowed to the two types this request can
                // actually produce; a submission that wanted the other two would need "?mode=run".
                if (data.contains("\"eventType\":\"NODE_STARTED\"")
                        || data.contains("\"eventType\":\"NODE_BYPASSED\"")) {
                    sawNodeActivity = true;
                }
                if (data.contains("\"eventType\":\"EXECUTION_COMPLETED\"") || data.contains("\"eventType\":\"EXECUTION_FAILED\"")) {
                    sawTerminalFrame = true;
                    terminalFrames.add(data);
                }
            }

            assertTrue(sawStarted, () -> "never observed EXECUTION_STARTED for " + executionId
                    + " on /v1/events. Matching frames seen: " + observed);
            assertTrue(sawNodeActivity, () -> "never observed a NODE_* event for " + executionId
                    + " on /v1/events. Matching frames seen: " + observed);
            // Pinning negative assertion, diagnosed under the repository test-isolation rule: the
            // durable projection deliberately never carries a traversal-terminal frame. If this ever
            // starts failing, either the declared contract changed on purpose (update this
            // assertion and RavenrootServerTest's matching Javadoc together) or a regression started
            // synthesising a terminal frame the journal never recorded.
            assertFalse(sawTerminalFrame, () -> "the durable stream (GET /v1/events, RavenrootServer"
                    + ".streamDurableExecutionEvents) must never carry an EXECUTION_COMPLETED/"
                    + "EXECUTION_FAILED frame - see its Javadoc, \"No traversal-terminal frame\". "
                    + "Unexpected terminal frame(s) for " + executionId + ": " + terminalFrames);

            // The documented way a client learns a traversal ended: DurableSseReplayIntegrationTest's
            // submitAndAwaitCompletion helper establishes the same pattern in-process.
            awaitActiveExecutionsDrain();
        } finally {
            reader.interrupt();
            sseResponse.body().close();
        }
    }

    /**
     * Termination is read from the aggregate - {@code /v1/runtime}'s {@code activeExecutions} back to
     * zero - never from the event stream (see the Javadoc note above and {@code
     * DurableSseReplayIntegrationTest.submitAndAwaitCompletion} in {@code ravenroot-server}, which
     * documents and exercises the identical pattern in-process).
     */
    private void awaitActiveExecutionsDrain() throws Exception {
        Instant deadline = Instant.now().plus(EXECUTION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/runtime")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), () -> "runtime snapshot: " + response.body());
            var matcher = java.util.regex.Pattern.compile("\"activeExecutions\":(\\d+)").matcher(response.body());
            assertTrue(matcher.find(), () -> "no activeExecutions field in " + response.body());
            if (Integer.parseInt(matcher.group(1)) == 0) {
                return;
            }
            Thread.sleep(300);
        }
        fail("activeExecutions on /v1/runtime never drained to zero within " + EXECUTION_TIMEOUT);
    }

    // ---- representative error path ------------------------------------------------------------

    /**
     * Pins the same contract {@code RavenrootServerTest
     * .readingAnUnknownOrMalformedExecutionIdIsAnExplicitClassifiedRefusal} pins in-process: a
     * syntactically invalid execution id is a classified {@code 400 INVALID_REQUEST}, never a
     * {@code 404} (which would claim the server looked for it and failed to find it) and never a
     * bare non-2xx with an empty body.
     */
    @Test
    void malformedExecutionIdIsRejectedAsInvalidRequestNotUnknownExecution() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/executions/not-a-uuid"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(), () -> "response body: " + response.body());
        assertEquals("INVALID_REQUEST", jsonString(response.body(), "code"), () -> response.body());
        assertTrue(response.body().contains("\"contract\":\"ravenroot.error/1\""),
                () -> "expected the standard error envelope contract: " + response.body());
    }

    // ---- shared helpers ----------------------------------------------------------------------

    private static String jsonString(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("Missing JSON field " + field + " in " + body);
        }
        int valueStart = start + marker.length();
        int end = body.indexOf('"', valueStart);
        if (end < 0) {
            throw new AssertionError("Unterminated JSON field " + field + " in " + body);
        }
        return body.substring(valueStart, end);
    }
}
