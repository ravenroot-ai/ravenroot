package ai.ravenroot.server.payload;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.testkit.api.PayloadTransportContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP half of API-01: the same contract over the wire.
 *
 * <p>A fresh server per test is deliberate. The submission rate limiter's burst is 10 and this class
 * submits more often than that, so a shared server would make an unrelated budget decide whether a
 * payload assertion passes. Nothing here reconfigures or relaxes a limiter — each test simply starts
 * with the production defaults, which is also what an operator's first request sees.</p>
 */
class HttpPayloadContractTest extends PayloadTransportContract {
    private PekkoExecutionEngine engine;
    private RavenrootServer server;
    private PayloadObservationHarness harness;
    private ExecutionMonitor monitor;
    private RecordingApplication recording;
    private HttpClient client;

    @BeforeEach
    void start() {
        harness = new PayloadObservationHarness();
        monitor = new ExecutionMonitor();
        engine = new PekkoExecutionEngine("ravenroot-http-payload-" + UUID.randomUUID());
        var application = new DefaultRavenrootApplication(engine, monitor, harness.registry());
        recording = new RecordingApplication(application);
        server = new RavenrootServer(
                recording,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                new DisabledLoopbackAuthenticator());
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        server.close();
        engine.close();
    }

    @Override
    protected PayloadValue submit(PayloadEnvelope envelope) throws Exception {
        harness.reset();
        var response = postStructured(envelope);
        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"payloadContract\":\"" + PayloadEnvelope.CONTRACT + "\""),
                response.body());
        assertTrue(response.body().contains("\"executionPolicy\":\"TEST_PASSTHROUGH\""), response.body());
        awaitTerminal(response.body());
        assertEquals(0, harness.invocations(), "TEST_PASSTHROUGH invoked the payload recorder behavior");
        return PayloadValue.fromJava(recording.awaitPayload(), limits());
    }

    @Override
    protected PayloadValue submitLegacyText(String text) throws Exception {
        harness.reset();
        // Byte for byte the pre-API-01 request: GraphML in the body, text in the query parameter, and
        // the content type an existing client sends. Nothing about it mentions the new representation.
        var request = HttpRequest.newBuilder(URI.create(base() + "/v1/executions?payload="
                        + URLEncoder.encode(text, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/graphml+xml")
                .POST(HttpRequest.BodyPublishers.ofString(PayloadObservationHarness.GRAPH))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"payloadSchema\":\"" + PayloadEnvelope.LEGACY_TEXT_SCHEMA + "\""),
                response.body());
        awaitTerminal(response.body());
        assertEquals(0, harness.invocations(), "legacy Play invoked the payload recorder behavior");
        return PayloadValue.fromJava(recording.awaitPayload(), limits());
    }

    @Override
    protected String submitExpectingRejection(PayloadEnvelope envelope) throws Exception {
        harness.reset();
        var response = postStructured(envelope);
        assertTrue(response.statusCode() >= 400, "expected a refusal, got " + response.statusCode());
        return field(response.body(), "code");
    }

    private HttpResponse<String> postStructured(PayloadEnvelope envelope) throws Exception {
        String body = "{\"contract\":\"" + StructuredSubmission.CONTRACT + "\",\"graphml\":"
                + quote(PayloadObservationHarness.GRAPH) + ",\"payload\":" + envelope.toJson() + "}";
        var request = HttpRequest.newBuilder(URI.create(base() + "/v1/executions"))
                .header("Content-Type", StructuredSubmission.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    private void awaitTerminal(String responseBody) throws InterruptedException {
        UUID executionId = UUID.fromString(field(responseBody, "executionId"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (monitor.eventsAfter(0).stream().anyMatch(event -> executionId.equals(event.executionId())
                    && (event.type() == ai.ravenroot.api.application.ExecutionEventType.EXECUTION_COMPLETED
                    || event.type() == ai.ravenroot.api.application.ExecutionEventType.EXECUTION_FAILED))) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Play did not finish: " + executionId);
    }

    private static String quote(String value) {
        return ai.ravenroot.api.payload.PayloadJson.write(PayloadValue.of(value));
    }

    static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("missing field " + name + " in " + body);
        }
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }

    /** Records the decoded application-boundary payload, then delegates to the real Test runner. */
    private static final class RecordingApplication implements RavenrootApplication {
        private final RavenrootApplication delegate;
        private final ArrayBlockingQueue<Object[]> payloads = new ArrayBlockingQueue<>(4);

        private RecordingApplication(RavenrootApplication delegate) {
            this.delegate = delegate;
        }

        private Object awaitPayload() throws InterruptedException {
            Object[] value = payloads.poll(10, TimeUnit.SECONDS);
            if (value == null) throw new AssertionError("decoded payload never reached the application boundary");
            return value[0];
        }

        @Override public ai.ravenroot.api.application.ApplicationStatus status() { return delegate.status(); }
        @Override public ai.ravenroot.api.application.RuntimeSnapshot runtimeSnapshot() {
            return delegate.runtimeSnapshot();
        }
        @Override public List<ai.ravenroot.api.catalog.NodeTypeDescriptor> nodeTypes() { return delegate.nodeTypes(); }
        @Override public List<ai.ravenroot.api.programming.GeneratedArtifact> programArtifacts() {
            return delegate.programArtifacts();
        }
        @Override public ai.ravenroot.api.programming.GeneratedArtifact createProgramArtifact(
                String language, String source, Map<String, String> metadata) {
            return delegate.createProgramArtifact(language, source, metadata);
        }
        @Override public CompletionStage<ai.ravenroot.api.programming.GeneratedArtifact> validateProgramArtifact(
                String id) { return delegate.validateProgramArtifact(id); }
        @Override public CompletionStage<ai.ravenroot.api.programming.ArtifactTestResult> testProgramArtifact(
                String id, Object payload) { return delegate.testProgramArtifact(id, payload); }
        @Override public ai.ravenroot.api.programming.GeneratedArtifact approveProgramArtifact(
                String id, Map<String, String> evidence) { return delegate.approveProgramArtifact(id, evidence); }
        @Override public ai.ravenroot.api.programming.GeneratedArtifact activateProgramArtifact(
                String id, Map<String, String> evidence) { return delegate.activateProgramArtifact(id, evidence); }
        @Override public ai.ravenroot.api.programming.GeneratedArtifact retireProgramArtifact(
                String id, Map<String, String> evidence) { return delegate.retireProgramArtifact(id, evidence); }
        @Override public ai.ravenroot.api.application.GraphSummary inspectGraphMl(InputStream graphMl) {
            return delegate.inspectGraphMl(graphMl);
        }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload) {
            return delegate.startGraphMl(security, executionId, graphMl, payload);
        }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload,
                                                          ExecutionPolicy policy) {
            assertEquals(ExecutionPolicy.TEST_PASSTHROUGH, policy);
            payloads.offer(new Object[]{payload});
            return delegate.startGraphMl(security, executionId, graphMl, payload, policy);
        }
        @Override public List<ai.ravenroot.api.application.ExecutionEvent> executionEventsAfter(long sequence) {
            return delegate.executionEventsAfter(sequence);
        }
        @Override public AutoCloseable subscribeToExecutionEvents(
                Consumer<ai.ravenroot.api.application.ExecutionEvent> listener) {
            return delegate.subscribeToExecutionEvents(listener);
        }
        @Override public boolean durableEventJournalAvailable() { return delegate.durableEventJournalAvailable(); }
        @Override public List<ai.ravenroot.api.application.DurableExecutionEvent> durableEventsAfter(
                String tenantId, long afterOffset, int limit) {
            return delegate.durableEventsAfter(tenantId, afterOffset, limit);
        }
        @Override public void close() { delegate.close(); }
    }
}
