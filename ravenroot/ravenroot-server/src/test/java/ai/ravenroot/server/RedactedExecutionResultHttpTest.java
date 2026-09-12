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
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.SecurityContext;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /v1/executions/{id}} answers {@link ExecutionLookup.Redacted} with its own wire code and
 * its own {@code payloadState} field, rather than the pre-existing collapse onto
 * {@code EXECUTION_RESULT_EXPIRED} that {@code RavenrootServer#readExecution} used to perform. Mirrors
 * {@code CancelledExecutionResultHttpTest}'s stub-application shape exactly, one {@link ExecutionLookup}
 * arm over.
 *
 * <p>Expired and redacted are different facts about the same shape of absence -- a record that aged
 * out under a retention policy working as configured, versus a payload that was refused at write time
 * and that reading sooner would not have recovered -- and this class is the wire evidence that a
 * caller can actually tell them apart, rather than an assumption resting on the two types compiling
 * differently.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RedactedExecutionResultHttpTest {

    @Test
    void aWithheldPayloadsFourTenBodyCarriesStatusAndPayloadState() throws Exception {
        UUID executionId = UUID.randomUUID();
        var stub = new StubApplication(new ExecutionLookup.Redacted(executionId, ProcessInstanceStatus.COMPLETED,
                null, ResultPayloadState.WITHHELD));
        try (var server = testServer(stub)) {
            server.start();

            var response = get(server, "/v1/executions/" + executionId);
            assertEquals(410, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"code\":\"EXECUTION_RESULT_REDACTED\""), response.body());
            assertEquals("COMPLETED", jsonString(response.body(), "status"), response.body());
            assertTrue(response.body().contains("\"terminationReason\":null"), response.body());
            assertTrue(response.body().contains("\"cancelled\":false"), response.body());
            assertEquals("WITHHELD", jsonString(response.body(), "payloadState"), response.body());
        }
    }

    /** A redacted, cancelled execution must report the cancellation exactly as an expired one does. */
    @Test
    void anUnconvertibleCancelledExecutionsBodyCarriesTheReasonAndTheRefusal() throws Exception {
        UUID executionId = UUID.randomUUID();
        var stub = new StubApplication(new ExecutionLookup.Redacted(executionId, ProcessInstanceStatus.FAILED,
                ExecutionTerminationReason.CANCELLED, ResultPayloadState.UNCONVERTIBLE));
        try (var server = testServer(stub)) {
            server.start();

            var response = get(server, "/v1/executions/" + executionId);
            assertEquals(410, response.statusCode(), response.body());
            assertEquals("FAILED", jsonString(response.body(), "status"), response.body());
            assertEquals("CANCELLED", jsonString(response.body(), "terminationReason"), response.body());
            assertTrue(response.body().contains("\"cancelled\":true"), response.body());
            assertEquals("UNCONVERTIBLE", jsonString(response.body(), "payloadState"), response.body());
        }
    }

    /**
     * The regression this whole class exists to guard: before this fix, {@code readExecution}
     * rendered both arms with the identical code and status, so a caller had no field on the wire to
     * branch on. Two live requests against two stubbed answers, diffed on the one field a client
     * actually reads.
     */
    @Test
    void expiredAndRedactedAreDistinguishableWireAnswersForTheSameStatus() throws Exception {
        UUID expiredId = UUID.randomUUID();
        UUID redactedId = UUID.randomUUID();
        var stub = new TwoAnswerStubApplication(
                new ExecutionLookup.Expired(expiredId, ProcessInstanceStatus.COMPLETED),
                new ExecutionLookup.Redacted(redactedId, ProcessInstanceStatus.COMPLETED, null,
                        ResultPayloadState.WITHHELD));
        try (var server = testServer(stub)) {
            server.start();

            var expiredResponse = get(server, "/v1/executions/" + expiredId);
            var redactedResponse = get(server, "/v1/executions/" + redactedId);

            assertEquals(410, expiredResponse.statusCode());
            assertEquals(410, redactedResponse.statusCode());
            assertNotEquals(jsonString(expiredResponse.body(), "code"), jsonString(redactedResponse.body(), "code"),
                    () -> "expired: " + expiredResponse.body() + " redacted: " + redactedResponse.body());
            assertTrue(expiredResponse.body().contains("\"code\":\"EXECUTION_RESULT_EXPIRED\""));
            assertTrue(redactedResponse.body().contains("\"code\":\"EXECUTION_RESULT_REDACTED\""));
            // Only the redacted answer says why the payload is absent; an expired one never carries
            // this field at all, which is what proves the two bodies are genuinely distinct shapes and
            // not the same body wearing two codes.
            assertTrue(redactedResponse.body().contains("\"payloadState\""), redactedResponse.body());
            assertTrue(!expiredResponse.body().contains("\"payloadState\""), expiredResponse.body());
        }
    }

    private static RavenrootServer testServer(RavenrootApplication application) {
        return new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null, new DisabledLoopbackAuthenticator());
    }

    private static HttpResponse<String> get(RavenrootServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonString(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    /**
     * The minimal {@link RavenrootApplication} needed to answer one canned {@link ExecutionLookup}
     * through {@code AuthorizedRavenrootApplication.executionResult}, without composing a real engine,
     * store or behavior registry -- copied from {@code CancelledExecutionResultHttpTest}'s own stub
     * rather than shared, matching this test tree's existing convention of a private stub per file.
     */
    private static class StubApplication implements RavenrootApplication {
        final ExecutionLookup lookup;

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

    /** Answers two distinct execution ids with two distinct canned lookups, so one server can serve
     * both halves of a same-request comparison. */
    private static final class TwoAnswerStubApplication extends StubApplication {
        private final ExecutionLookup second;

        TwoAnswerStubApplication(ExecutionLookup first, ExecutionLookup second) {
            super(first);
            this.second = second;
        }

        @Override
        public ExecutionLookup executionResult(String tenantId, UUID executionId) {
            if (executionId.equals(lookup.executionId())) {
                return lookup;
            }
            return second;
        }
    }
}
