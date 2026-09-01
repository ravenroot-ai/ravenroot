package ai.ravenroot.server.error;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelope;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.payload.PayloadObservationHarness;
import ai.ravenroot.server.payload.StructuredSubmission;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.support.ForwardingRavenrootApplication;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error half of API-01, asserted on the surface a client actually sees.
 *
 * <p>Building error bodies from {@code exception.getMessage()} can expose caller-supplied text such as
 * an artifact id, or whatever an internal component writes into an exception. These tests pin the
 * required property: response text is a function of the code, and the cause lives server-side under a
 * handle.</p>
 */
class ErrorEnvelopeHttpTest {

    /**
     * A create with a blank body reaches the adapter's {@code IllegalArgumentException} branch, which
     * before API-01 answered with {@code {"error": exception.getMessage()}}. It is the cheapest
     * reachable proof that an internal message no longer has a route to a client.
     */
    @Test
    void anInternalCauseNeverReachesTheClient() throws Exception {
        String internalMessage = "Program source cannot be blank";
        try (var engine = new PekkoExecutionEngine("ravenroot-error-envelope-" + UUID.randomUUID());
             var server = server(engine, new PayloadObservationHarness())) {
            server.start();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/program-artifacts"))
                    .POST(HttpRequest.BodyPublishers.ofString("   ")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode(), response.body());
            assertFalse(response.body().contains(internalMessage),
                    "the error response carried an internal exception message: " + response.body());
            // The guarantee is positive, not merely the absence of one string: the text a client sees
            // is the code's own. Any path that reverts to echoing an exception fails this equality,
            // whatever that exception happens to say.
            assertEquals(ErrorCode.INVALID_REQUEST.code(), field(response.body(), "code"), response.body());
            assertEquals(ErrorCode.INVALID_REQUEST.message(), field(response.body(), "message"),
                    response.body());
            assertEquals(ErrorCode.INVALID_REQUEST.message(), field(response.body(), "error"),
                    response.body());
            assertTrue(response.body().contains("\"contract\":\"" + ErrorEnvelope.CONTRACT + "\""),
                    response.body());
            assertTrue(response.body().contains("\"correlationId\":\""), response.body());
        }
    }

    @Test
    void theCorrelationIdIsMintedByTheServerAndNotTakenFromTheRequest() throws Exception {
        String forged = "forged-correlation-id";
        try (var engine = new PekkoExecutionEngine("ravenroot-error-correlation-" + UUID.randomUUID());
             var server = server(engine, new PayloadObservationHarness())) {
            server.start();
            var client = HttpClient.newHttpClient();
            var first = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/status"))
                    .header("X-Request-Id", forged)
                    .header("X-Correlation-Id", forged)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, first.statusCode(), first.body());
            String firstId = field(first.body(), "correlationId");
            assertNotEquals(forged, firstId,
                    "a client-supplied handle became the audit correlation id: " + first.body());
            assertFalse(first.body().contains(forged), first.body());
            assertFalse(firstId.isBlank(), first.body());
            var second = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/status"))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, second.statusCode(), second.body());
            String secondId = field(second.body(), "correlationId");
            assertNotEquals(firstId, secondId,
                    "independent requests on one route must not reuse a correlation handle");
        }
    }

    /**
     * The other half of the payload policy: what the response withholds is recorded server-side, and
     * the two are joinable by the incident handle the caller was given.
     */
    @Test
    void aRejectedPayloadIsDiagnosableServerSideUnderTheIncidentTheClientReceived() throws Exception {
        String marker = "ravenroot.security.tenantId";
        var audit = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(audit, true, StandardCharsets.UTF_8));
        try (var engine = new PekkoExecutionEngine("ravenroot-error-incident-" + UUID.randomUUID());
             var server = server(engine, new PayloadObservationHarness())) {
            server.start();
            String duplicated = "{\"contract\":\"" + StructuredSubmission.CONTRACT + "\",\"graphml\":"
                    + quote(PayloadObservationHarness.GRAPH)
                    + ",\"payload\":{\"contract\":\"" + PayloadEnvelope.CONTRACT
                    + "\",\"value\":{\"" + marker + "\":1,\"" + marker + "\":2}}}";
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions"))
                    .header("Content-Type", StructuredSubmission.MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(duplicated, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode(), response.body());
            assertEquals("PAYLOAD_DUPLICATE_KEY", field(response.body(), "code"), response.body());
            assertFalse(response.body().contains(marker),
                    "the response echoed the rejected key: " + response.body());
            String incidentId = field(response.body(), "incidentId");
            String recorded = audit.toString(StandardCharsets.UTF_8);
            assertTrue(recorded.contains(incidentId), "no server-side record for " + incidentId);
            assertTrue(recorded.contains(marker),
                    "the detail did not reach the server-side record, so it was lost rather than moved");
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void aMalformedStructuredSubmissionIsClassifiedRatherThanEchoed() throws Exception {
        String marker = "not-json-4c1f";
        try (var engine = new PekkoExecutionEngine("ravenroot-error-malformed-" + UUID.randomUUID());
             var server = server(engine, new PayloadObservationHarness())) {
            server.start();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions"))
                    .header("Content-Type", StructuredSubmission.MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString("{" + marker)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode(), response.body());
            assertEquals("PAYLOAD_MALFORMED", field(response.body(), "code"), response.body());
            assertFalse(response.body().contains(marker),
                    "the response echoed the malformed document: " + response.body());
        }
    }

    @Test
    void anUnknownPayloadContractVersionIsRefusedRatherThanBestEffortInterpreted() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-error-version-" + UUID.randomUUID());
             var server = server(engine, new PayloadObservationHarness())) {
            server.start();
            String body = "{\"contract\":\"" + StructuredSubmission.CONTRACT + "\",\"graphml\":"
                    + quote(PayloadObservationHarness.GRAPH)
                    + ",\"payload\":{\"contract\":\"ravenroot.payload/99\",\"value\":\"x\"}}";
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions"))
                    .header("Content-Type", StructuredSubmission.MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(415, response.statusCode(), response.body());
            assertEquals("PAYLOAD_UNSUPPORTED_CONTRACT_VERSION", field(response.body(), "code"),
                    response.body());
        }
    }

    @Test
    void anUnknownEnvelopeMemberIsIgnoredSoTheContractCanGrowAdditively() throws Exception {
        var harness = new PayloadObservationHarness();
        var observed = new ArrayBlockingQueue<Object>(1);
        try (var engine = new PekkoExecutionEngine("ravenroot-error-additive-" + UUID.randomUUID())) {
            var delegate = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), harness.registry());
            RavenrootApplication recording = new ForwardingRavenrootApplication(delegate) {
                @Override
                public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                         UUID executionId, InputStream graphMl, Object payload,
                                                         ExecutionPolicy policy) {
                    assertEquals(ExecutionPolicy.TEST_PASSTHROUGH, policy);
                    observed.offer(payload);
                    return delegate().startGraphMl(security, executionId, graphMl, payload, policy);
                }
            };
            try (var server = server(recording)) {
            server.start();
            String body = "{\"contract\":\"" + StructuredSubmission.CONTRACT + "\",\"graphml\":"
                    + quote(PayloadObservationHarness.GRAPH)
                    + ",\"tracingContext\":{\"traceparent\":\"00-abc-def-01\"}"
                    + ",\"payload\":{\"contract\":\"" + PayloadEnvelope.CONTRACT
                    + "\",\"futureMember\":true,\"value\":\"kept\"}}";
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions"))
                    .header("Content-Type", StructuredSubmission.MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(202, response.statusCode(), response.body());
            assertEquals("kept", observed.poll(10, TimeUnit.SECONDS));
            assertEquals(0, harness.invocations(), "TEST_PASSTHROUGH invoked the payload recorder behavior");
            }
        }
    }

    @Test
    void aLegacyApplicationFailsClosedWithARedacted501AndTheServerRemainsUsable() throws Exception {
        var harness = new PayloadObservationHarness();
        try (var engine = new PekkoExecutionEngine("ravenroot-error-legacy-policy-" + UUID.randomUUID())) {
            RavenrootApplication legacy = new ForwardingRavenrootApplication(
                    new DefaultRavenrootApplication(engine, new ExecutionMonitor(), harness.registry()));
            try (var server = server(legacy)) {
                server.start();
                var client = HttpClient.newHttpClient();
                var response = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + "/v1/executions"))
                        .POST(HttpRequest.BodyPublishers.ofString(PayloadObservationHarness.GRAPH)).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(501, response.statusCode(), response.body());
                assertEquals(ErrorCode.EXECUTION_POLICY_UNSUPPORTED.code(), field(response.body(), "code"));
                assertEquals(ErrorCode.EXECUTION_POLICY_UNSUPPORTED.message(), field(response.body(), "message"));
                assertFalse(response.body().contains("Execution policy is not implemented"), response.body());
                assertEquals(0, harness.invocations());

                var health = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode(), health.body());
            }
        }
    }

    private static RavenrootServer server(PekkoExecutionEngine engine, PayloadObservationHarness harness) {
        return server(new DefaultRavenrootApplication(engine, new ExecutionMonitor(), harness.registry()));
    }

    private static RavenrootServer server(RavenrootApplication application) {
        return new RavenrootServer(
                application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                new DisabledLoopbackAuthenticator());
    }

    private static String quote(String value) {
        return ai.ravenroot.api.payload.PayloadJson.write(PayloadValue.of(value));
    }

    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("missing field " + name + " in " + body);
        }
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }
}
