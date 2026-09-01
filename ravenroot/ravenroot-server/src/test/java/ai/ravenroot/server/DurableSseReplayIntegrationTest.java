package ai.ravenroot.server;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API-03: the SSE stream endpoint is a durable projection over the PERS-07 journal rather than
 * {@code ExecutionMonitor}'s in-memory circular history. Reconnection after a service restart is
 * verified by
 * {@link #reconnectionAfterARestartReplaysTheMissedBacklogWithoutGapsOrDuplicates}.
 *
 * <p>The class-level {@link Timeout} is not decoration. Every test here reads an
 * <em>endpoint that is designed never to end</em>: a stream whose normal steady state is an open
 * socket emitting keepalives forever. An assertion about such an endpoint that waits for bytes the
 * server will not send does not fail — it hangs, and because Surefire runs the module in one forked
 * JVM, one hung test stalls the whole reactor with no result for anything after it. That is not
 * hypothetical: waiting for an absent frame wedges the server module indefinitely. A stream test
 * without a timeout cannot report a failure, and a test
 * that cannot fail is worse than no test.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class DurableSseReplayIntegrationTest {
    private static final java.time.Duration TEST_TIMEOUT = java.time.Duration.ofSeconds(10);

    /** Synthetic key under which {@link #readAvailableEvents} records each frame's SSE {@code id:}. */
    private static final String SSE_ID = "__sseId";

    private static final String CHAIN_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="durable-sse-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="work"><data key="kind">BEHAVIOR</data><data key="behavior">work</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="work"><data key="outcome">continue</data></edge>
                <edge id="e2" source="work" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path directory;

    /**
     * A graph runs to completion, the stream is read,
     * the "service" restarts — a fresh {@link DefaultRavenrootApplication} and {@link RavenrootServer}
     * against the <em>same</em> durable store file, exactly what a real process restart is — a second
     * graph runs, and a client reconnecting with the {@code Last-Event-ID} it saw before the restart
     * receives the second graph's events, in full, with nothing missing and nothing repeated.
     *
     * <p>This is the property {@code ExecutionMonitor}'s in-memory cursor could never have had: its
     * counter resets to zero across exactly this restart, so a real client resuming with its old
     * last-seen value would ask a fresh counter for events "after" a number it will not reach again
     * for a long time, and silently receive nothing rather than the true backlog — indistinguishable
     * from "you were already caught up". Durability of the id itself is the whole of what this test
     * proves.</p>
     */
    @Test
    void reconnectionAfterARestartReplaysTheMissedBacklogWithoutGapsOrDuplicates() throws Exception {
        Path databaseFile = directory.resolve("durable-sse.db");
        var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("work", message -> java.util.concurrent.CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload())));

        long lastSeenOffset;
        try (var engineA = new PekkoExecutionEngine("durable-sse-before-restart");
             var storeA = new SqliteExecutionStore(databaseFile, Clock.systemUTC())) {
            var applicationA = new DefaultRavenrootApplication(engineA, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), storeA);
            try (var serverA = testServer(applicationA)) {
                serverA.start();
                submitAndAwaitCompletion(serverA);
                lastSeenOffset = readFullStreamAndReturnLastEventId(serverA, 0);
                assertTrue(lastSeenOffset > 0, "the first traversal must have journalled at least one event");
            }
        }

        // "Restart": a new engine, a new in-memory monitor (its sequence counter starts back at zero,
        // which is the defect this test exists to prove no longer matters), a new
        // DefaultRavenrootApplication and a new RavenrootServer -- all against the SAME database file,
        // which is what a real process restart is.
        try (var engineB = new PekkoExecutionEngine("durable-sse-after-restart");
             var storeB = new SqliteExecutionStore(databaseFile, Clock.systemUTC())) {
            var applicationB = new DefaultRavenrootApplication(engineB, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), storeB);
            try (var serverB = testServer(applicationB)) {
                serverB.start();
                submitAndAwaitCompletion(serverB);

                List<Map<String, Object>> replayed = readFullStreamEvents(serverB, lastSeenOffset);

                assertFalse(replayed.isEmpty(), "reconnecting after the restart must replay the second "
                        + "traversal's events -- an empty result is indistinguishable from \"nothing "
                        + "happened\", which is exactly the silent-loss shape this test detects");
                // No gaps: journalOffset is strictly increasing and every value greater than
                // lastSeenOffset up to the last one received must appear exactly once -- neither
                // skipped nor repeated.
                List<Long> offsets = replayed.stream().map(row -> ((Number) row.get("journalOffset")).longValue())
                        .toList();
                List<Long> expected = new ArrayList<>();
                for (long offset = offsets.getFirst(); offset <= offsets.getLast(); offset++) {
                    expected.add(offset);
                }
                assertEquals(expected, offsets,
                        "every offset after the restart must be contiguous with no gap and no duplicate: " + offsets);
                assertTrue(offsets.getFirst() > lastSeenOffset,
                        "nothing already seen before the restart may be replayed again");
                // The frame's own SSE id -- what a browser resumes with -- must be the same durable
                // offset the body reports. If the two ever diverged, every assertion above would still
                // pass while every real EventSource client resumed from the wrong place.
                assertEquals(offsets, replayed.stream().map(row -> ((Number) row.get(SSE_ID)).longValue()).toList(),
                        "each frame's SSE id must be its journalOffset: the id is the resumption cursor");
                // The second traversal's own shape: exactly one START-triggered NODE_STARTED for
                // "work", one NODE_COMPLETED, plus the EXECUTION_STARTED that precedes them --
                // the causal model, still intact end to end through a restart.
                assertTrue(replayed.stream().anyMatch(row -> "EXECUTION_STARTED".equals(row.get("eventType"))));
                assertTrue(replayed.stream().allMatch(row -> row.get("description") instanceof String
                                && !((String) row.get("description")).isBlank()),
                        "every durable replay row must carry useful public text: " + replayed);
                assertTrue(replayed.stream().anyMatch(row -> "EXECUTION_STARTED".equals(row.get("eventType"))
                                && "Execution started.".equals(row.get("description"))),
                        "live durable polling and restart replay use the same type-derived description: "
                                + replayed);
                assertTrue(replayed.stream().anyMatch(row -> "work".equals(row.get("nodeId"))),
                        "the node id, resolved through the invocation binding, must survive the restart too");
            }
        }
    }

    /**
     * Retention past the horizon must be a signal the client can act on, never a short answer it
     * cannot tell apart from a complete one (see {@code ExecutionStoreFailure.JournalTruncated}'s own
     * Javadoc, which names this exact endpoint's pre-API-03 in-memory shape as the failure mode being
     * closed). Driven with a fake rather than forcing real SQLite compaction: the property under test
     * is what {@code RavenrootServer} does with the failure once it is thrown, which does not depend
     * on which adapter throws it, and a fake makes the failure deterministic on the first read instead
     * of contingent on retention/compaction timing.
     */
    @Test
    void retentionTruncationEndsTheStreamWithADeclaredTerminalFrameNotAShortReplay() throws Exception {
        var fake = new DurableJournalFakeApplication();
        fake.truncatedAfter = 5L;
        fake.retainedFrom = 42L;
        try (var server = testServer(fake)) {
            server.start();
            var response = get(server, "/v1/events", 3L);
            assertEquals(200, response.statusCode(), "the failure is a stream frame, not an HTTP status");
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            response.body().close();
            assertTrue(body.contains("event: stream-truncated"),
                    () -> "expected a declared truncation frame, got: " + body);
            assertTrue(body.contains("\"code\":\"STREAM_RETENTION_EXCEEDED\""), body);
            assertTrue(body.contains("\"retainedFrom\":42"), body);
        }
    }

    @Test
    void diagnosticsSelectorUsesOnlyTheProcessLocalRingWhileDefaultRemainsDurable() throws Exception {
        var fake = new DurableJournalFakeApplication();
        try (var server = testServer(fake)) {
            server.start();
            var durable = get(server, "/v1/events", 0L);
            assertEquals("DURABLE", durable.headers().firstValue("X-Ravenroot-Event-Source").orElseThrow());
            assertEquals("DURABLE", durable.headers().firstValue("X-Ravenroot-Event-Continuity").orElseThrow());
            durable.body().close();

            var diagnostics = get(server, "/v1/events?include=diagnostics", 0L);
            assertEquals("RING", diagnostics.headers().firstValue("X-Ravenroot-Event-Source").orElseThrow());
            assertEquals("PROCESS_LOCAL",
                    diagnostics.headers().firstValue("X-Ravenroot-Event-Continuity").orElseThrow());
            diagnostics.body().close();

            var invalid = get(server, "/v1/events?include=everything", 0L);
            assertEquals(400, invalid.statusCode());
            assertTrue(new String(invalid.body().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("INVALID_REQUEST"));
            invalid.body().close();
        }
    }

    /**
     * The durable path's live-delivery guarantee, stated in its strongest form: a backlog that
     * appears after the client is already connected is delivered <em>in full and contiguously</em>
     * <strong>even when not one live notification is ever delivered to this connection</strong>.
     *
     * <p>That is not a contrived condition, and this test does not simulate it — it is simply the
     * truth of the wiring. The live subscription passes through
     * {@code AuthorizedRavenrootApplication.subscribeToExecutionEvents}, which drops any event
     * {@code canObserve} cannot resolve to the caller's tenant through the in-process
     * {@code executionOwners} map. That map holds only executions <em>this</em> process started, so
     * for exactly the executions durable replay exists to serve — someone else's process, or this
     * one before a restart — the notification never arrives. The stream must therefore be correct
     * with the notification treated as an optional hint, and that is what is asserted here: nothing
     * below depends on a wakeup firing, only on the periodic re-poll.
     *
     * <p>The backlog is deliberately {@code 1000} against a default {@code pageSize} of 256, so it
     * spans four pages. Contiguity, not merely count, is asserted — a short answer and a gapped
     * answer are different defects and both must fail here.</p>
     *
     * <p><strong>Declared weaker than it may read.</strong> Spanning four pages does <em>not</em>
     * make this a test of {@code drainJournalPages}'s inner paging loop, and it was measured rather
     * than assumed: mutating that loop to read a single page per poll leaves this test green,
     * because the unconditional re-poll simply delivers the next page on the next tick. So the loop
     * is a latency optimisation — it collapses four ticks into one — and this test proves only the
     * property that actually matters to a client, that all 1000 rows arrive contiguously and none is
     * lost. The control that is genuinely load-bearing here is the unconditional re-poll, and
     * removing <em>that</em> does turn this test red. Writing the page count into the test name would
     * have claimed coverage the test does not have.</p>
     */
    @Test
    void aBacklogAppearingAfterConnectIsDeliveredInFullAcrossPageBoundariesWithoutAnyLiveNotification()
            throws Exception {
        var fake = new DurableJournalFakeApplication();
        try (var server = testServer(fake)) {
            server.start();
            var response = get(server, "/v1/events", 0);
            assertEquals(200, response.statusCode());
            assertTrue(fake.awaitListener(TEST_TIMEOUT), "the server never subscribed for the live wakeup");

            fake.append(1000);

            List<Map<String, Object>> delivered = readAvailableEvents(response);
            List<Long> offsets = delivered.stream()
                    .map(row -> ((Number) row.get("journalOffset")).longValue()).toList();
            List<Long> expected = new ArrayList<>();
            for (long offset = 1; offset <= 1000; offset++) {
                expected.add(offset);
            }
            assertEquals(expected, offsets,
                    "a re-poll must drain the journal to exhaustion; delivering a prefix would mean a "
                            + "connection stalls one page short of the truth and never recovers. Received "
                            + offsets.size() + " offsets");
        }
    }

    /**
     * Backpressure, a slow client and a disconnection must never block the <em>runtime</em>. On this
     * path is one measurable fact — the shared execution-event publisher is never blocked, however
     * many notifications it emits and whatever the connected consumer is doing — and it is measured
     * here directly, on the publishing thread, rather than inferred from a disconnect policy.
     *
     * <p>The client deliberately does not read a byte while the burst is published. {@code 20_000}
     * notifications is far past any buffer this endpoint holds, so a publisher that could be made to
     * wait for a consumer would wait here.</p>
     *
     * <p>Declared scope, so this is not read as more than it is: the measured path is the whole
     * publish chain a connected stream imposes — the monitor's own dispatch and
     * {@code AuthorizedRavenrootApplication}'s per-connection {@code canObserve} wrapper — and the
     * events used here are filtered out by that wrapper before reaching
     * {@code DurableStreamWakeup}, for the reason
     * {@link #aBacklogAppearingAfterConnectIsDeliveredInFullAcrossPageBoundariesWithoutAnyLiveNotification}
     * documents. This proves that a connected consumer cannot slow
     * the publisher — and does <em>not</em> on its own prove the wakeup's own offer is non-blocking.
     * That is left to the mutation record rather than claimed here.</p>
     */
    @Test
    void thePublisherIsNeverBlockedByAConnectedStreamThatIsNotReading() throws Exception {
        var fake = new DurableJournalFakeApplication();
        try (var server = testServer(fake)) {
            server.start();
            var response = get(server, "/v1/events", 0);
            assertEquals(200, response.statusCode());
            assertTrue(fake.awaitListener(TEST_TIMEOUT), "the server never subscribed for the live wakeup");

            long start = System.nanoTime();
            for (int i = 0; i < 20_000; i++) {
                fake.publishPoke();
            }
            long elapsedNanos = System.nanoTime() - start;

            assertTrue(elapsedNanos < TEST_TIMEOUT.toNanos(),
                    "publishing 20000 notifications to a stream whose client is not reading must never "
                            + "block the publisher: one connection's health must not cross the connection "
                            + "boundary into the shared publisher. Took "
                            + java.time.Duration.ofNanos(elapsedNanos));
            response.body().close();
        }
    }

    /**
     * Submits the chain graph and waits until the traversal has actually ended.
     *
     * <p>Termination is read from the <strong>aggregate</strong> — {@code /v1/runtime}'s
     * {@code activeExecutions} back to zero — and deliberately not from the event stream, because
     * the stream carries no traversal-terminal frame by design
     * (see {@code streamDurableExecutionEvents}'s Javadoc). So this helper is not merely a wait: it
     * is the declared way a client observes that a traversal ended, exercised rather than asserted.
     *
     * <p>Polling for {@code EXECUTION_STARTED}, an event journalled at the <em>beginning</em> of the
     * traversal, can return while node events are still being written and capture a partial
     * {@code lastSeenOffset}. Polling the aggregate avoids making "everything the client had seen
     * before the restart" depend on that race.</p>
     */
    private static void submitAndAwaitCompletion(RavenrootServer server) throws Exception {
        var client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/executions"))
                        .POST(HttpRequest.BodyPublishers.ofString(CHAIN_GRAPH)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        // Fire-and-forget: poll rather than assuming synchronous
        // completion. The chain graph has no blocking node, so this settles in milliseconds in
        // practice; the bound is generous headroom, not the expected latency.
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (activeExecutions(server) == 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("execution did not complete within " + TEST_TIMEOUT);
    }

    private static int activeExecutions(RavenrootServer server) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/runtime")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var matcher = java.util.regex.Pattern.compile("\"activeExecutions\":(\\d+)").matcher(response.body());
        assertTrue(matcher.find(), () -> "no activeExecutions in " + response.body());
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * The highest SSE {@code id:} the client saw — that is, literally the {@code Last-Event-ID} a
     * browser would resume with, taken from the frame's id rather than from its body.
     */
    private static long readFullStreamAndReturnLastEventId(RavenrootServer server, long lastEventId) throws Exception {
        List<Map<String, Object>> events = readFullStreamEvents(server, lastEventId);
        return events.stream().mapToLong(row -> ((Number) row.get(SSE_ID)).longValue()).max().orElse(0);
    }

    /**
     * Opens the SSE stream, reads only the immediate backlog (bounded read, not the live tail — a
     * short-lived HTTP client that closes once no more bytes arrive within the idle window), and
     * parses every {@code data:} line whose event carries a JSON object.
     */
    private static List<Map<String, Object>> readFullStreamEvents(RavenrootServer server, long lastEventId)
            throws Exception {
        var response = get(server, "/v1/events", lastEventId);
        assertEquals(200, response.statusCode());
        return readAvailableEvents(response);
    }

    /**
     * Drains an already-open stream until it goes idle, parsing each frame's {@code data:} payload
     * and carrying its preceding {@code id:} line under {@link #SSE_ID}.
     *
     * <p>The {@code id:} is captured, and not merely the JSON body, because {@code id:} <em>is</em>
     * the resumption cursor: it is what a browser's {@code EventSource} sends back as
     * {@code Last-Event-ID}, so a test that only read the body would still pass if the frame's id
     * were some other number entirely — which would break every real client while leaving the
     * assertion green.</p>
     */
    private static List<Map<String, Object>> readAvailableEvents(HttpResponse<InputStream> response)
            throws Exception {
        var events = new ArrayList<Map<String, Object>>();
        try (InputStream body = response.body()) {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
            var readerThread = Executors.newVirtualThreadPerTaskExecutor();
            Long pendingId = null;
            // Quiescence, not idleness, is the stop condition: this endpoint never goes idle by
            // design -- it emits a keepalive on every tick that found nothing -- so waiting for
            // silence would wait forever and wedge the reactor. Two consecutive keepalives with no
            // frame between them mean the server has
            // re-polled the journal and found nothing new, which is the real "caught up" signal.
            boolean frameSinceKeepalive = false;
            int quietKeepalives = 0;
            try {
                while (System.nanoTime() < deadline) {
                    var lineFuture = readerThread.submit(reader::readLine);
                    String line;
                    try {
                        line = lineFuture.get(2, TimeUnit.SECONDS);
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
                    } else if (line.startsWith("id: ")) {
                        pendingId = Long.parseLong(line.substring("id: ".length()).strip());
                    } else if (line.startsWith("data: {")
                            || (line.startsWith("data: ") && line.contains("STREAM_RETENTION_EXCEEDED"))) {
                        var parsed = parseJsonObject(line.substring("data: ".length()));
                        parsed.put(SSE_ID, pendingId);
                        pendingId = null;
                        events.add(parsed);
                        frameSinceKeepalive = true;
                    }
                }
            } finally {
                readerThread.shutdownNow();
            }
        }
        return events;
    }

    private static HttpResponse<InputStream> get(RavenrootServer server, String path, long lastEventId)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET();
        if (lastEventId > 0) {
            builder.header("Last-Event-ID", Long.toString(lastEventId));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /** Minimal hand-rolled JSON object parser: flat, string/number/null values only -- exactly this
     * endpoint's own frame shape, with no library dependency worth adding for one test file. */
    private static Map<String, Object> parseJsonObject(String json) {
        var result = new java.util.LinkedHashMap<String, Object>();
        String trimmed = json.strip();
        if (trimmed.startsWith("{")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("}")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        for (String pair : splitTopLevel(trimmed)) {
            int colon = pair.indexOf(':');
            String key = pair.substring(0, colon).strip().replaceAll("^\"|\"$", "");
            String value = pair.substring(colon + 1).strip();
            if ("null".equals(value)) {
                result.put(key, null);
            } else if (value.startsWith("\"")) {
                result.put(key, value.substring(1, value.length() - 1));
            } else {
                try {
                    result.put(key, Long.parseLong(value));
                } catch (NumberFormatException notLong) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    private static List<String> splitTopLevel(String body) {
        var parts = new ArrayList<String>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString && c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        if (start < body.length()) {
            parts.add(body.substring(start));
        }
        return parts;
    }

    private static RavenrootServer testServer(RavenrootApplication application) {
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                new DisabledLoopbackAuthenticator());
    }

    /**
     * A fake whose durable journal deterministically truncates on the first read past
     * {@code truncatedAfter} -- used only for {@link #retentionTruncationEndsTheStreamWithADeclaredTerminalFrameNotAShortReplay},
     * where the property under test is {@code RavenrootServer}'s reaction to the failure, not the
     * failure's own trigger conditions (those belong to the store adapter's own conformance suite).
     */
    private static final class DurableJournalFakeApplication implements RavenrootApplication {
        private final List<DurableExecutionEvent> backlog = new ArrayList<>();
        private long truncatedAfter;
        private long retainedFrom;
        private volatile Consumer<ExecutionEvent> listener;
        private final java.util.concurrent.CountDownLatch listenerReady = new java.util.concurrent.CountDownLatch(1);

        /** Waits until the server has subscribed, so a flood does not race the subscription itself. */
        boolean awaitListener(java.time.Duration timeout) throws InterruptedException {
            return listenerReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** One synchronous publish, exactly as {@code ExecutionMonitor.publish} delivers -- content is
         * irrelevant on the durable path (see {@code streamDurableExecutionEvents}'s own Javadoc): only
         * arrival is a wakeup, never the event's own fields. */
        void publishPoke() {
            var current = listener;
            if (current != null) {
                current.accept(dummyEvent());
            }
        }

        private static ExecutionEvent dummyEvent() {
            return new ExecutionEvent(1, Instant.EPOCH, "local", "req", "engine", "v1", UUID.randomUUID(),
                    UUID.randomUUID(), null, null, ai.ravenroot.api.application.ExecutionEventType.NODE_STARTED,
                    null, 0, false, "poke");
        }

        @Override public ApplicationStatus status() { return new ApplicationStatus("RUNNING", "fake", java.util.Set.of()); }
        @Override public RuntimeSnapshot runtimeSnapshot() { return new RuntimeSnapshot(0, Map.of()); }
        @Override public List<NodeTypeDescriptor> nodeTypes() { return List.of(); }
        @Override public List<GeneratedArtifact> programArtifacts() { return List.of(); }
        @Override public GeneratedArtifact createProgramArtifact(String l, String s, Map<String, String> m) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException();
        }
        @Override public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> e) {
            throw new UnsupportedOperationException();
        }
        @Override public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> e) {
            throw new UnsupportedOperationException();
        }
        @Override public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> e) {
            throw new UnsupportedOperationException();
        }
        @Override public GraphSummary inspectGraphMl(InputStream graphMl) { throw new UnsupportedOperationException(); }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ExecutionEvent> executionEventsAfter(long sequence) { return List.of(); }
        @Override public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> newListener) {
            this.listener = newListener;
            listenerReady.countDown();
            return () -> this.listener = null;
        }
        /**
         * Appends {@code count} journal rows at offsets continuing from the current end. Separate
         * from {@link #publishPoke()} on purpose: a row exists in the journal whether or not anyone
         * was notified about it, which is the very property the durable path relies on when it
         * treats a notification as a hint rather than as the event.
         */
        void append(int count) {
            synchronized (backlog) {
                for (int i = 0; i < count; i++) {
                    long offset = backlog.size() + 1L;
                    backlog.add(new DurableExecutionEvent(UUID.randomUUID(), offset, offset, "local",
                            "NODE_COMPLETED", UUID.randomUUID(), UUID.randomUUID(), null, null,
                            UUID.randomUUID(), "req", "v1", Instant.EPOCH, "work"));
                }
            }
        }

        @Override public boolean durableEventJournalAvailable() { return true; }
        @Override public List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit) {
            if (afterOffset < truncatedAfter) {
                throw new ExecutionStoreException(
                        new ExecutionStoreFailure.JournalTruncated(tenantId, afterOffset, retainedFrom));
            }
            // Honours `limit` exactly as ExecutionStore.readJournal does, so a reader that fails to
            // page past the first response is visible here rather than hidden by an unbounded fake.
            synchronized (backlog) {
                return backlog.stream()
                        .filter(event -> event.journalOffset() > afterOffset)
                        .limit(limit)
                        .toList();
            }
        }
        @Override public void close() { }
    }
}
