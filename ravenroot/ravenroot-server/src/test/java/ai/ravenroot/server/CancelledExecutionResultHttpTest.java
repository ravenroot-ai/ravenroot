package ai.ravenroot.server;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /v1/executions/{id}} reports a cancellation as a cancellation, in both of its response
 * shapes.
 *
 * <p>A cancelled execution is stored as {@code FAILED} and qualified by
 * {@code ExecutionTerminationReason.CANCELLED}; read alone, that status describes an incident that
 * did not happen -- see that type's own Javadoc. The durable and in-memory halves of this are
 * asserted elsewhere; what is proved here is the wire, because a reason that never leaves the
 * process distinguishes nothing for the caller who actually has to act on it.</p>
 *
 * <p>Both shapes are covered on purpose. The 200 body carries the reason beside the status, and the
 * 410 body -- returned once the full result is past its retention horizon -- carries it beside the
 * terminal status it still reports, since that is the read with nothing left to check it against.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CancelledExecutionResultHttpTest {

    private static BehaviorRegistry hangingBehaviors(CountDownLatch reached) {
        return BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("hang", message -> {
                    reached.countDown();
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
    }

    private static final String HANG_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="cancelled-result-http-test" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="hang"><data key="kind">BEHAVIOR</data><data key="behavior">hang</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="hang"><data key="outcome">continue</data></edge>
                <edge id="e2" source="hang" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * The live/terminal case: a genuinely cancelled execution, read back through
     * {@code GET /v1/executions/{id}} while its result is still held in the process-local registry.
     * Before this fix {@code executionOutcomeJson} carried {@code status} alone -- this is the 200
     * body regression test the priority-two REST gap named.
     */
    @Test
    void aCancelledExecutionsTwoHundredBodyCarriesTheReasonBesideStatus() throws Exception {
        var hangReached = new CountDownLatch(1);
        try (var engine = new PekkoExecutionEngine("cancelled-result-http-test");
             var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                     hangingBehaviors(hangReached));
             var server = testServer(application)) {
            server.start();

            var submitted = post(server, "/v1/executions?mode=run", HANG_GRAPH);
            assertEquals(202, submitted.statusCode(), submitted.body());
            String executionId = extract(submitted.body(), "executionId");
            assertTrue(hangReached.await(5, TimeUnit.SECONDS), "the hang node never started");

            var cancelled = post(server, "/v1/executions/" + executionId + "/cancel", "");
            assertEquals(200, cancelled.statusCode(), cancelled.body());
            assertTrue(cancelled.body().contains("\"outcome\":\"CANCELLED\""), cancelled.body());

            String body = pollResultUntilTerminal(server, executionId);
            assertEquals("FAILED", jsonString(body, "status"),
                    () -> "a cancelled execution must still report FAILED, exactly as before this "
                            + "feature -- only the reason is new: " + body);
            assertEquals("CANCELLED", jsonString(body, "terminationReason"),
                    () -> "the reason must be present beside status, or a cancellation reads as an "
                            + "ordinary incident: " + body);
            assertTrue(body.contains("\"cancelled\":true"), body);
        }
    }

    /**
     * The evicted case: a stub {@link RavenrootApplication} that answers
     * {@link ExecutionLookup.Expired} directly, so the assertion is deterministic rather than
     * dependent on actually exhausting the result registry's retention bound (already proved at the
     * registry level by {@code ExecutionResultRegistryTest}). Before this fix, {@code readExecution}
     * discarded {@code Expired.status()} and {@code Expired.terminationReason()} entirely in favour of
     * a bare 410 {@code EXECUTION_RESULT_EXPIRED} -- this is the fix for exactly that discard.
     */
    @Test
    void anEvictedCancelledExecutionsFourTenBodyStillCarriesStatusAndReason() throws Exception {
        UUID executionId = UUID.randomUUID();
        var stub = new StubApplication(new ExecutionLookup.Expired(executionId, ProcessInstanceStatus.FAILED,
                ExecutionTerminationReason.CANCELLED));
        try (var server = testServer(stub)) {
            server.start();

            var response = get(server, "/v1/executions/" + executionId);
            assertEquals(410, response.statusCode(), response.body());
            assertEquals("FAILED", jsonString(response.body(), "status"), response.body());
            assertEquals("CANCELLED", jsonString(response.body(), "terminationReason"), response.body());
            assertTrue(response.body().contains("\"cancelled\":true"), response.body());
            assertTrue(response.body().contains("\"code\":\"EXECUTION_RESULT_EXPIRED\""), response.body());
        }
    }

    /** An expired execution that never was a cancellation must not gain one on the wire. */
    @Test
    void anEvictedOrdinaryFailureReportsNoTerminationReason() throws Exception {
        UUID executionId = UUID.randomUUID();
        var stub = new StubApplication(new ExecutionLookup.Expired(executionId, ProcessInstanceStatus.FAILED));
        try (var server = testServer(stub)) {
            server.start();

            var response = get(server, "/v1/executions/" + executionId);
            assertEquals(410, response.statusCode(), response.body());
            assertEquals("FAILED", jsonString(response.body(), "status"), response.body());
            assertTrue(response.body().contains("\"terminationReason\":null"), response.body());
            assertTrue(response.body().contains("\"cancelled\":false"), response.body());
        }
    }

    private static RavenrootServer testServer(RavenrootApplication application) {
        return new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null, new DisabledLoopbackAuthenticator());
    }

    private static String pollResultUntilTerminal(RavenrootServer server, String executionId) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 12; attempt++) {
            var response = get(server, "/v1/executions/" + executionId);
            assertEquals(200, response.statusCode(), response.body());
            body = response.body();
            if (!body.contains("\"status\":\"RUNNING\"")) {
                return body;
            }
            Thread.sleep(500);
        }
        return body;
    }

    private static HttpResponse<String> post(RavenrootServer server, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/graphml+xml")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(RavenrootServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    private static String jsonString(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    /**
     * The minimal {@link RavenrootApplication} needed to answer a canned {@link ExecutionLookup}
     * through {@code AuthorizedRavenrootApplication.executionResult}, without composing a real
     * engine, store or behavior registry -- every method this stub does not override throws, which is
     * deliberate: a test relying on any of them would fail loudly rather than silently exercising
     * production defaults.
     */
    private static final class StubApplication implements RavenrootApplication {
        private final ExecutionLookup lookup;

        StubApplication(ExecutionLookup lookup) {
            this.lookup = lookup;
        }

        @Override
        public ExecutionLookup executionResult(String tenantId, UUID executionId) {
            return lookup;
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
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GraphSummary inspectGraphMl(InputStream graphMl) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public ai.ravenroot.api.application.ExecutionSubmission startGraphMl(SecurityContext security,
                                                                             UUID executionId,
                                                                             InputStream graphMl, Object payload) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            return () -> { };
        }

        @Override
        public List<ExecutionEvent> executionEventsAfter(long sequence) {
            return List.of();
        }

        @Override
        public boolean durableEventJournalAvailable() {
            return false;
        }

        @Override
        public List<ai.ravenroot.api.application.DurableExecutionEvent> durableEventsAfter(String tenantId,
                                                                                            long afterOffset,
                                                                                            int limit) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public void close() {
        }
    }
}
