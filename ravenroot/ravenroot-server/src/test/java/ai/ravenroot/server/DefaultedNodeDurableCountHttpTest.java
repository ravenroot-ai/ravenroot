package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a journal-capable store composed, <strong>how many times</strong>
 * a node defaulted is now answerable from the HTTP surface.
 *
 * <h2>What was measured before, and why the set was never the answer</h2>
 * <p>{@code GET /v1/executions/{id}} has always carried {@code defaultedNodes}, and it always will
 * say <em>which</em> nodes defaulted — it is a set, so a node reached twice appears once. The count
 * lives only in the event stream, one event per visit, exactly as it does for {@code visitedNodes}
 * ({@code NODE_STARTED}), {@code bypassedNodes} ({@code NODE_BYPASSED}) and
 * {@code handledFailureNodes} ({@code NODE_FAILED}). {@code NODE_DEFAULTED} was the one type never
 * written to the journal, so on a deployment that composed one — the configuration meant to be the
 * more complete of the two — that count is obtainable over this API, so the published schema need not
 * state that it is unavailable.</p>
 *
 * <h2>Why the graph visits one node twice rather than defaulting two nodes</h2>
 * <p>Two defaulted nodes would leave every assertion here green under an implementation that reported
 * only the set: two nodes, two events, and set size equals event count. Visiting <em>one</em> node
 * twice separates them — the set has one entry and the stream must have two — which is the precise
 * difference between "which nodes defaulted" and "how many times". {@code probe} carries
 * {@code joinPolicy=each}, which is what stops its two predecessors from being read as a fan-in and
 * makes each arrival its own invocation ({@code JoinSpec.validate}).</p>
 *
 * <h2>This asserts the HTTP surface, not the journal</h2>
 * <p>Deliberately: a test that read the store back would prove the row was written and prove nothing
 * about a client being able to obtain the count, which is the property under test.
 * Everything below goes over the socket, and the frames are read as a real {@code EventSource} reads
 * them. The journal's own content and causation are asserted separately, in
 * {@code JournalCausationTest} in {@code ravenroot-core}.</p>
 *
 * <p>The class-level {@link Timeout} is load-bearing for the same reason
 * {@code DurableSseReplayIntegrationTest} documents at length: {@code /v1/events} is an endpoint
 * designed never to end, so an assertion that waits for bytes the server will not send hangs rather
 * than fails, and one hung test stalls this module's whole forked JVM.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class DefaultedNodeDurableCountHttpTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * {@code start} fans out to two registered branches, both of which continue into {@code probe},
     * whose behavior name is deliberately absent from this deployment's registry. {@code probe} is
     * therefore composed as the unknown-behavior pass-through and is invoked once per arrival.
     */
    private static final String TWICE_DEFAULTED_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="joinPolicy" for="node" attr.name="joinPolicy" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="defaulted-twice" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="b0"><data key="kind">BEHAVIOR</data><data key="behavior">b0</data></node>
                <node id="b1"><data key="kind">BEHAVIOR</data><data key="behavior">b1</data></node>
                <node id="probe"><data key="kind">BEHAVIOR</data>
                  <data key="behavior">absent-from-this-catalog</data>
                  <data key="joinPolicy">each</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e0" source="start" target="b0"><data key="outcome">continue</data></edge>
                <edge id="e1" source="start" target="b1"><data key="outcome">continue</data></edge>
                <edge id="e2" source="b0" target="probe"><data key="outcome">continue</data></edge>
                <edge id="e3" source="b1" target="probe"><data key="outcome">continue</data></edge>
                <edge id="e4" source="probe" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path directory;

    @Test
    void theDefaultedCountIsObtainableOverHttpWhereAJournalIsComposed() throws Exception {
        var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("b0", message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload())))
                .register("b1", message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload())));

        try (var engine = new PekkoExecutionEngine("defaulted-durable-count");
             var store = new SqliteExecutionStore(directory.resolve("defaulted.db"), Clock.systemUTC())) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            try (var server = testServer(application)) {
                server.start();
                String traversalId = submitAndAwaitCompletion(server);

                // The set: the API's existing answer, and the one that cannot count. Asserted rather
                // than assumed, because "the stream has two" only means something beside "the set has
                // one" -- otherwise this test would pass on a run where probe was reached once.
                String result = body(get(server, "/v1/executions/" + traversalId));
                assertTrue(result.contains("\"defaultedNodes\":[\"probe\"]"),
                        () -> "expected defaultedNodes to name probe exactly once -- it is a set, which "
                                + "is precisely why it is not the count: " + result);

                List<String> frames = readAvailableFrames(get(server, "/v1/events"));
                assertTrue(frames.stream().anyMatch(frame -> frame.contains("\"eventType\":\"NODE_STARTED\"")),
                        () -> "this stream is not serving from the journal -- the durable frame carries "
                                + "\"eventType\", the in-memory ring carries \"type\", and every assertion "
                                + "below would otherwise be measuring the wrong source: " + frames);

                long defaultedVisits = frames.stream()
                        .filter(frame -> frame.contains("\"eventType\":\"NODE_DEFAULTED\""))
                        .filter(frame -> frame.contains("\"nodeId\":\"probe\""))
                        .count();
                assertEquals(2, defaultedVisits,
                        () -> "probe was reached twice and defaulted on both arrivals, so the durable "
                                + "stream must carry one NODE_DEFAULTED per visit -- this is the count "
                                + "that was not obtainable over this API at all where a journal exists: "
                                + frames);

                // The node id is what makes the count attributable, and on this source it is a join
                // against InvocationAdded rather than a field of the envelope. A journalled row that
                // resolved to no node would satisfy a count of the type alone while telling a client
                // nothing about which node it counted.
                assertTrue(frames.stream()
                                .filter(frame -> frame.contains("\"eventType\":\"NODE_DEFAULTED\""))
                                .noneMatch(frame -> frame.contains("\"nodeId\":null")),
                        () -> "every defaulted row must resolve to its node through the invocation "
                                + "binding: " + frames);
            }
        }
    }

    private static RavenrootServer testServer(RavenrootApplication application) {
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                new DisabledLoopbackAuthenticator());
    }

    /**
     * Submits the graph and waits for the traversal to actually end, read from {@code /v1/runtime}'s
     * {@code activeExecutions} rather than from the stream, which carries no traversal-terminal
     * frame by design.
     *
     * @return the traversal id, which is what {@code /v1/executions/{id}} is keyed by
     */
    private static String submitAndAwaitCompletion(RavenrootServer server) throws Exception {
        // ?mode=run is load-bearing and was found the hard way. This route's `mode` parameter
        // DEFAULTS TO "test", which is ExecutionPolicy.TEST_PASSTHROUGH: every node, registered or
        // not, is delivered NodeCommand.PASSTHROUGH and reported as NODE_BYPASSED, so nothing ever
        // defaults and defaultedNodes comes back empty. A submission without this parameter would
        // make this whole test measure the bypass path while claiming to measure the defaulted one.
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/executions?mode=run"))
                        .POST(HttpRequest.BodyPublishers.ofString(TWICE_DEFAULTED_GRAPH)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        var matcher = Pattern.compile("\"executionId\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find(), () -> "no executionId in " + response.body());
        String executionId = matcher.group(1);

        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (activeExecutions(server) == 0) {
                return executionId;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("execution did not complete within " + TEST_TIMEOUT);
    }

    private static int activeExecutions(RavenrootServer server) throws Exception {
        String runtime = body(get(server, "/v1/runtime"));
        var matcher = Pattern.compile("\"activeExecutions\":(\\d+)").matcher(runtime);
        assertTrue(matcher.find(), () -> "no activeExecutions in " + runtime);
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Drains the stream until it is quiescent, returning each frame's raw {@code data:} payload.
     *
     * <p>Quiescence, not silence, is the stop condition: this endpoint emits a keepalive on every
     * tick that found nothing, so waiting for the socket to go quiet waits forever. Two consecutive
     * keepalives with no frame between them mean the server re-polled the journal and found nothing
     * new — the real "caught up" signal, and the same one
     * {@code DurableSseReplayIntegrationTest} uses.</p>
     */
    private static List<String> readAvailableFrames(HttpResponse<InputStream> response) throws Exception {
        assertEquals(200, response.statusCode());
        var frames = new ArrayList<String>();
        try (InputStream stream = response.body()) {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            var readerThread = Executors.newVirtualThreadPerTaskExecutor();
            long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
            boolean frameSinceKeepalive = false;
            int quietKeepalives = 0;
            try {
                while (System.nanoTime() < deadline) {
                    var pending = readerThread.submit(reader::readLine);
                    String line;
                    try {
                        line = pending.get(2, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException idle) {
                        break;
                    }
                    if (line == null) {
                        break;
                    }
                    if (line.startsWith(": keepalive")) {
                        quietKeepalives = frameSinceKeepalive ? 0 : quietKeepalives + 1;
                        frameSinceKeepalive = false;
                        if (quietKeepalives >= 2) {
                            break;
                        }
                    } else if (line.startsWith("data: {")) {
                        frames.add(line.substring("data: ".length()));
                        frameSinceKeepalive = true;
                    }
                }
            } finally {
                readerThread.shutdownNow();
            }
        }
        return frames;
    }

    private static HttpResponse<InputStream> get(RavenrootServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String body(HttpResponse<InputStream> response) throws Exception {
        assertEquals(200, response.statusCode());
        try (InputStream stream = response.body()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
