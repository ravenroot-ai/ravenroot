package ai.ravenroot.server;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /v1/events/recent} — the three properties that make this endpoint an honest instrument
 * rather than a convenient one.
 *
 * <p>Each test here guards a distinction the endpoint exists to preserve, and each was written by
 * breaking the guard first and confirming that this test — and only this test — went red. The event
 * window is supplied by a stub rather than by running a graph so the retention floor and the cursor
 * can be positioned exactly; a real execution can produce a gap only by evicting 2048 events, which
 * would make the interesting case the slowest one to reach.</p>
 */
class RecentExecutionEventsRouteTest {
    @TempDir
    Path uiDirectory;

    @Test
    void refusesALimitAboveTheCapAndNamesIt() throws Exception {
        try (var server = testServer(new StubApplication(events(1, 2, 3), OptionalLong.of(1)))) {
            server.start();
            var response = get(server, "/v1/events/recent?limit="
                    + (RavenrootServer.RECENT_EVENTS_MAX_LIMIT + 1));

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("EVENT_LIMIT_ABOVE_MAXIMUM"), response.body());
            // The cap lives in two places -- the enum's fixed message and the server constant -- because
            // ErrorEnvelope has no entry point for a composed message and must not gain one. This is the
            // coupling that makes the duplication safe rather than a latent lie.
            assertTrue(response.body().contains(String.valueOf(RavenrootServer.RECENT_EVENTS_MAX_LIMIT)),
                    () -> "the refusal must name the cap the caller exceeded: " + response.body());
            // The point of refusing rather than clamping: no events come back at all.
            assertFalse(response.body().contains("\"events\""),
                    () -> "an over-limit request must be refused, never silently clamped: " + response.body());
        }
    }

    @Test
    void reportsAGapWhenTheCursorPrecedesTheRetentionFloor() throws Exception {
        // The floor is 500: everything between the caller's cursor (10) and 500 has been evicted.
        try (var server = testServer(new StubApplication(events(500, 501), OptionalLong.of(500)))) {
            server.start();
            var response = get(server, "/v1/events/recent?after=10");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"continuity\":\"GAP_DETECTED\""),
                    () -> "events existed before the retained window and must not be presented as "
                            + "continuity: " + response.body());
            assertTrue(response.body().contains("\"oldestAvailable\":500"), response.body());
        }
    }

    @Test
    void reportsContinuousWhenTheCursorIsInsideTheRetainedWindow() throws Exception {
        try (var server = testServer(new StubApplication(events(5, 6, 7), OptionalLong.of(1)))) {
            server.start();
            var response = get(server, "/v1/events/recent?after=4");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"continuity\":\"CONTINUOUS\""), response.body());
        }
    }

    /**
     * The other half of the gap distinction, and the one an instrument gets wrong in the direction that
     * looks healthy: a quiet server must say "nothing new", not "a gap".
     */
    @Test
    void anEmptyWindowWithNoGapMeansNothingNew() throws Exception {
        try (var server = testServer(new StubApplication(List.of(), OptionalLong.of(7)))) {
            server.start();
            var response = get(server, "/v1/events/recent?after=7");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"events\":[]"), response.body());
            assertTrue(response.body().contains("\"continuity\":\"CONTINUOUS\""),
                    () -> "an empty window inside the retained range is 'nothing happened', not a gap: "
                            + response.body());
        }
    }

    /** A floor the application cannot state must not be reported as continuity it cannot establish. */
    @Test
    void reportsUnknownContinuityWhenTheApplicationCannotStateAFloor() throws Exception {
        try (var server = testServer(new StubApplication(events(3, 4), OptionalLong.empty()))) {
            server.start();
            var response = get(server, "/v1/events/recent?after=2");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"continuity\":\"UNKNOWN\""), response.body());
            assertTrue(response.body().contains("\"oldestAvailable\":null"), response.body());
        }
    }

    @Test
    void declaresTheRingAsItsSourceWhenNoDurableJournalIsAvailable() throws Exception {
        try (var server = testServer(new StubApplication(events(1), OptionalLong.of(1)))) {
            server.start();
            var response = get(server, "/v1/events/recent");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"source\":\"RING\""),
                    () -> "the same query against two deployments must be distinguishable: "
                            + response.body());
        }
    }

    @Test
    void prefersAndDeclaresTheDurableProjectionWhenOneIsAvailable() throws Exception {
        var stub = new StubApplication(events(1), OptionalLong.of(1));
        stub.durable = true;
        try (var server = testServer(stub)) {
            server.start();
            var response = get(server, "/v1/events/recent?after=0");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"source\":\"DURABLE\""), response.body());
        }
    }

    /**
     * Ascending, strictly greater than the cursor — the property that makes the cursor resumable.
     *
     * <p>Driven by a real execution rather than by the stub, because the events must be
     * <em>observable</em> as well as retained: {@code AuthorizedRavenrootApplication} resolves each
     * event's owner through its execution-ownership registry and fails closed on unknown ownership, so
     * only events from an execution this process actually started survive the read. A stub can position
     * the retention floor but cannot register ownership, which is exactly why the content assertions
     * live here and the window assertions live above.</p>
     */
    @Test
    void returnsEventsAscendingAndStrictlyAfterTheCursorForARealExecution() throws Exception {
        try (var engine = new ai.ravenroot.pekko.PekkoExecutionEngine("recent-events-order-test");
             var server = testServer(new ai.ravenroot.core.runtime.DefaultRavenrootApplication(engine,
                     new ai.ravenroot.core.runtime.ExecutionMonitor()))) {
            server.start();
            var started = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, started.statusCode());

            String body = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                body = get(server, "/v1/events/recent?after=0").body();
                if (body.contains("EXECUTION_COMPLETED")) {
                    break;
                }
                Thread.sleep(100);
            }
            final String observed = body;
            assertTrue(observed.contains("EXECUTION_COMPLETED"), () -> "execution never completed: " + observed);
            assertTrue(observed.contains("\"source\":\"RING\""), observed);

            var sequences = new ArrayList<Long>();
            var matcher = java.util.regex.Pattern.compile("\"sequence\":(\\d+)").matcher(observed);
            while (matcher.find()) {
                sequences.add(Long.parseLong(matcher.group(1)));
            }
            assertTrue(sequences.size() > 1, () -> "expected several events: " + observed);
            for (int index = 1; index < sequences.size(); index++) {
                assertTrue(sequences.get(index) > sequences.get(index - 1),
                        () -> "ascending order: " + observed);
            }
            assertTrue(sequences.getFirst() > 0, () -> "the cursor is exclusive: " + observed);
            assertTrue(observed.contains("\"lastSequence\":" + sequences.getLast()), observed);
        }
    }

    /** The public sentence is useful while the legacy detail key remains a safe compatibility alias. */
    @Test
    void forwardsPublicDescriptionWithoutForwardingTheInternalDiagnostic() throws Exception {
        try (var engine = new ai.ravenroot.pekko.PekkoExecutionEngine("recent-events-detail-test");
             var server = testServer(new ai.ravenroot.core.runtime.DefaultRavenrootApplication(engine,
                     new ai.ravenroot.core.runtime.ExecutionMonitor()))) {
            server.start();
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                body = get(server, "/v1/events/recent?after=0").body();
                if (body.contains("EXECUTION_COMPLETED")) {
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(body.contains("\"description\":\"Execution started.\""), body);
            // The `detail` key is GONE, not renamed. It used to carry a copy of this same public
            // sentence, which made a field named for the diagnostic hold something that was not the
            // diagnostic -- the wire telling its reader something untrue, in the same way the panel
            // was telling its reader something untrue. Asserting its absence is what keeps a later
            // change from quietly reinstating an alias under that name.
            assertFalse(body.contains("\"detail\""), body);
            assertTrue(body.contains("\"publicReason\""), body);
            assertFalse(body.contains("execution accepted"), body);
        }
    }

    @Test
    void hostileFailureDetailNeverCrossesTheRecentEventsWire() throws Exception {
        String sentinel = "password=hunter2<script>alert(1)</script>\n/home/runner/secret";
        var event = new ExecutionEvent(1, Instant.EPOCH, "local", "request-1", "stub", "v1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionEventType.NODE_FAILED, "node", 0, false, sentinel);
        String body = RavenrootServer.executionEventJson(event);

        assertTrue(body.contains("\"description\":\"Node failed. Protected diagnostics may contain more detail.\""),
                body);
        assertFalse(body.contains("hunter2"), body);
        assertFalse(body.contains("<script>"), body);
        assertFalse(body.contains("/home/runner"), body);
    }

    private static final String EXECUTABLE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="recent-events-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="future"><data key="kind">BEHAVIOR</data><data key="behavior">future-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="future"><data key="outcome">continue</data></edge>
                <edge id="e2" source="future" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    // -------------------------------------------------- include=diagnostics (content, not source)

    /**
     * The assertion this parameter exists for.
     *
     * <p>Diagnostics live only in the ring, whose window is shorter. When the caller's cursor has
     * fallen behind that window the failure messages are <em>gone</em>, and the caller must be told
     * so — not handed events whose diagnostic fields are quietly missing. Absent-because-aged-out and
     * absent-because-this-source-never-had-them must not look alike, and only the first is a gap.</p>
     */
    @Test
    void diagnosticsRequestedAgainstAnAgedOutWindowReportsGoneRatherThanSilentlyFieldLess() throws Exception {
        var stub = new StubApplication(events(500, 501), OptionalLong.of(500));
        stub.durable = true; // a durable journal exists and must NOT be allowed to answer this
        try (var server = testServer(stub)) {
            server.start();
            var response = get(server, "/v1/events/recent?after=10&include=diagnostics");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"source\":\"RING\""),
                    () -> "diagnostics exist only in the ring, so the ring must serve them even when a "
                            + "durable journal is available: " + response.body());
            assertTrue(response.body().contains("\"continuity\":\"GAP_DETECTED\""),
                    () -> "aged-out diagnostics must be reported gone, never as silently absent "
                            + "fields: " + response.body());
            assertTrue(response.body().contains("\"oldestAvailable\":500"), response.body());
        }
    }

    /** Diagnostics select the ring by content; the caller never names a source. */
    @Test
    void diagnosticsSelectTheRingEvenWhenADurableJournalIsAvailable() throws Exception {
        var stub = new StubApplication(events(1, 2), OptionalLong.of(1));
        stub.durable = true;
        try (var server = testServer(stub)) {
            server.start();
            var withDiagnostics = get(server, "/v1/events/recent?after=0&include=diagnostics");
            var without = get(server, "/v1/events/recent?after=0");

            assertTrue(withDiagnostics.body().contains("\"source\":\"RING\""), withDiagnostics.body());
            assertTrue(withDiagnostics.body().contains("\"include\":[\"diagnostics\"]"),
                    withDiagnostics.body());
            // The same deployment, the same cursor, a different content request: durable-first still
            // applies whenever diagnostics are not asked for.
            assertTrue(without.body().contains("\"source\":\"DURABLE\""), without.body());
            assertTrue(without.body().contains("\"include\":[]"), without.body());
        }
    }

    /** An unrecognised selector is refused, not ignored: ignoring answers a narrower question. */
    @Test
    void anUnrecognisedIncludeSelectorIsRefused() throws Exception {
        try (var server = testServer(new StubApplication(events(1), OptionalLong.of(1)))) {
            server.start();
            var response = get(server, "/v1/events/recent?include=everything");

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("INVALID_REQUEST"), response.body());
        }
    }

    /**
     * The end-to-end shape: diagnostics requested, window intact, fields present.
     *
     * <p>Uses a real execution because {@code detail} has to be genuinely produced rather than posed.</p>
     */
    @Test
    void diagnosticsRequestedWithinTheWindowCarriesTheInProcessFields() throws Exception {
        try (var engine = new ai.ravenroot.pekko.PekkoExecutionEngine("recent-events-diagnostics-test");
             var server = testServer(new ai.ravenroot.core.runtime.DefaultRavenrootApplication(engine,
                     new ai.ravenroot.core.runtime.ExecutionMonitor()))) {
            server.start();
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                body = get(server, "/v1/events/recent?after=0&include=diagnostics").body();
                if (body.contains("EXECUTION_COMPLETED")) {
                    break;
                }
                Thread.sleep(100);
            }
            final String observed = body;
            assertTrue(observed.contains("\"description\":\"Execution started.\""), observed);
            // Same reason as above: absent, with the classifier under its own name in its place.
            assertFalse(observed.contains("\"detail\""), observed);
            assertTrue(observed.contains("\"publicReason\""), observed);
            assertTrue(observed.contains("\"activeInstances\""), observed);
            assertTrue(observed.contains("\"continuity\":\"CONTINUOUS\""), observed);
        }
    }

    // -------------------------------------------------- durable failures are not retention answers

    /**
     * A truncated journal is a gap, and the store names the floor it still holds.
     *
     * <p>{@code JournalTruncated} carries {@code retainedFrom}, so this is the one moment the durable
     * branch can report {@code oldestAvailable} honestly — exactly when the caller has been told there
     * is a gap and most needs to know where the surviving history starts.</p>
     */
    @Test
    void aTruncatedJournalIsReportedAsAGapCarryingTheRetainedFloor() throws Exception {
        var stub = new StubApplication(List.of(), OptionalLong.of(0));
        stub.durable = true;
        stub.durableFailure = new ai.ravenroot.api.persistence.ExecutionStoreException(
                new ai.ravenroot.api.persistence.ExecutionStoreFailure.JournalTruncated("local", 5L, 900L));
        try (var server = testServer(stub)) {
            server.start();
            var response = get(server, "/v1/events/recent?after=5");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"continuity\":\"GAP_DETECTED\""), response.body());
            assertTrue(response.body().contains("\"oldestAvailable\":900"),
                    () -> "the store names its retained floor on truncation and it must be reported: "
                            + response.body());
        }
    }

    /**
     * The distinction the regression analysis asked for: a broken store must not answer "your events aged out".
     *
     * <p>Reporting an unavailable store as {@code GAP_DETECTED} would hand the operator a retention
     * explanation for an infrastructure fault, and nothing anywhere would record the difference.</p>
     */
    @Test
    void aStoreFailureThatIsNotTruncationIsAnErrorRatherThanAGap() throws Exception {
        var stub = new StubApplication(List.of(), OptionalLong.of(0));
        stub.durable = true;
        stub.durableFailure = new ai.ravenroot.api.persistence.ExecutionStoreException(
                new ai.ravenroot.api.persistence.ExecutionStoreFailure.Unavailable("disk offline"));
        try (var server = testServer(stub)) {
            server.start();
            var response = get(server, "/v1/events/recent?after=5");

            assertEquals(500, response.statusCode());
            assertFalse(response.body().contains("GAP_DETECTED"),
                    () -> "a broken store must not be reported as retention truncation: "
                            + response.body());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static HttpResponse<String> get(RavenrootServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private RavenrootServer testServer(RavenrootApplication application) {
        return new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                uiDirectory, new DisabledLoopbackAuthenticator());
    }

    private static List<ExecutionEvent> events(long... sequences) {
        var built = new ArrayList<ExecutionEvent>();
        for (long sequence : sequences) {
            built.add(new ExecutionEvent(sequence, Instant.EPOCH, "local", "request-" + sequence, "stub",
                    "v1", UUID.nameUUIDFromBytes(("p" + sequence).getBytes()),
                    UUID.nameUUIDFromBytes(("t" + sequence).getBytes()), null, null,
                    ExecutionEventType.EXECUTION_STARTED, null, 0, false, "detail-" + sequence));
        }
        return List.copyOf(built);
    }

    private static final class StubApplication implements RavenrootApplication {
        private final List<ExecutionEvent> events;
        private final OptionalLong floor;
        private boolean durable;
        /** When set, the durable read throws this instead of returning, to exercise both branches. */
        private ai.ravenroot.api.persistence.ExecutionStoreException durableFailure;

        private StubApplication(List<ExecutionEvent> events, OptionalLong floor) {
            this.events = events;
            this.floor = floor;
        }

        @Override
        public List<ExecutionEvent> executionEventsAfter(long sequence) {
            return events.stream().filter(event -> event.sequence() > sequence).toList();
        }

        @Override
        public OptionalLong oldestRetainedEventSequence() {
            return floor;
        }

        @Override
        public boolean durableEventJournalAvailable() {
            return durable;
        }

        @Override
        public List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit) {
            if (durableFailure != null) {
                throw durableFailure;
            }
            return List.of();
        }

        @Override
        public ApplicationStatus status() {
            return new ApplicationStatus("RUNNING", "stub", Set.of());
        }

        @Override
        public RuntimeSnapshot runtimeSnapshot() {
            return new RuntimeSnapshot(0, Map.of());
        }

        @Override
        public List<NodeTypeDescriptor> nodeTypes() {
            return List.of();
        }

        @Override
        public List<GeneratedArtifact> programArtifacts() {
            return List.of();
        }

        @Override
        public GeneratedArtifact createProgramArtifact(String language, String source,
                                                       Map<String, String> metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphSummary inspectGraphMl(InputStream graphMl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                                Object payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            return () -> { };
        }

        @Override
        public void close() {
        }
    }
}
