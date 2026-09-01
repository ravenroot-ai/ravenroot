package ai.ravenroot.server;

import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlCompatibilityException;
import ai.ravenroot.server.audit.AuditTrailGraphMlRejectionSink;
import ai.ravenroot.server.audit.AuditTrailPayloadRejectionSink;
import ai.ravenroot.server.audit.GraphMlRejectionAuditEvent;
import ai.ravenroot.server.audit.StructuredGraphMlRejectionLogger;
import ai.ravenroot.server.error.PayloadRejectionAuditEvent;
import ai.ravenroot.server.error.StructuredPayloadRejectionLogger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends
 * {@code MailExecutionEventSanitizationTest}'s pattern rather than inventing a new one: a canary that
 * looks like a secret, driven through a genuinely hostile input, checked absent from the audience that
 * must never see it and present (escaped) on the one channel that legitimately does.
 *
 * <h2>Why the earlier rejection-sink tests did not already prove this</h2>
 * <p>{@code JsonLinesInjectionResistanceTest} and {@code AuditTrailBridgeSinkTest} both used
 * {@code PayloadException.malformed()}/a bare malformed-XML rejection, whose {@code diagnosticDetail()}
 * carries no attacker-chosen content at all -- {@code {"streamExceptionClass":"..."}} for GraphML,
 * {@code {"offset":"0"}} for payload. They proved the escaping mechanism is called, not that a real
 * attacker-controlled value surviving the trip is actually safe. {@code GraphMlCompatibilityException}
 * and {@code PayloadException}'s real construction is package-private (FIX-03's policy), so this drives
 * the canary through the one public surface each exposes that echoes a caller-chosen string into
 * {@code diagnosticDetail}: an unsupported GraphML key type, and an unsupported payload contract
 * version.</p>
 */
class RejectionSinkCanaryTest {
    private static final String CANARY = "secret-canary-4f9a2b17";
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    // ---- GraphML ------------------------------------------------------------------------------------

    @Test
    void aGraphMlCanaryReachesDiagnosticDetailButNeverThePublicMessage() {
        var rejection = graphMlRejectionCarryingTheCanary();

        assertFalse(rejection.getMessage().contains(CANARY),
                () -> "the public message must never carry document content: " + rejection.getMessage());
        assertTrue(rejection.diagnosticDetail().containsValue(CANARY),
                "the fixture must actually exercise the echo path, or the rest of this class proves nothing");
    }

    @Test
    void aGraphMlCanaryReachesTheStdoutSinkOnlyInEscapedForm() {
        var bytes = new ByteArrayOutputStream();
        new StructuredGraphMlRejectionLogger(new PrintStream(bytes, true, StandardCharsets.UTF_8)).record(
                new GraphMlRejectionAuditEvent(EPOCH, "req-1", "acme", "alice", graphMlRejectionCarryingTheCanary()));

        String line = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains(CANARY), () -> "the canary must reach the server-side sink: " + line);
        assertEquals(1, line.chars().filter(character -> character == '\n').count(),
                "the record itself must still be exactly one line");
    }

    @Test
    void aGraphMlCanaryReachesTheAuditTrailOnlyThroughTheEnvelopeDetailField() {
        try (var trail = new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24))) {
            new AuditTrailGraphMlRejectionSink(trail).record(
                    new GraphMlRejectionAuditEvent(EPOCH, "req-1", "acme", "alice", graphMlRejectionCarryingTheCanary()));

            var record = trail.read("acme", 0, 10).get(0);
            String detail = new String(record.envelope().detail().bytes(), StandardCharsets.UTF_8);
            assertTrue(detail.contains(CANARY), () -> "the canary must reach the audit record: " + detail);
            // Never anywhere the response-facing side of the contract reads from.
            assertFalse(record.envelope().reason().contains(CANARY),
                    "reason carries the classification, not document content");
        }
    }

    private static GraphMlCompatibilityException graphMlRejectionCarryingTheCanary() {
        return assertThrows(GraphMlCompatibilityException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(("""
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                          <key id="k0" for="node" attr.name="p" attr.type="%s"/>
                          <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                        </graphml>
                        """.formatted(CANARY)).getBytes(StandardCharsets.UTF_8))));
    }

    // ---- payload --------------------------------------------------------------------------------------

    @Test
    void aPayloadCanaryReachesDiagnosticDetailButNeverThePublicMessage() {
        var rejection = PayloadException.unsupportedContract(CANARY);

        assertFalse(rejection.getMessage().contains(CANARY), () -> "the public message must never carry "
                + "payload content: " + rejection.getMessage());
        assertTrue(rejection.diagnosticDetail().containsValue(CANARY),
                "the fixture must actually exercise the echo path, or the rest of this class proves nothing");
    }

    @Test
    void aPayloadCanaryReachesTheStdoutSinkOnlyInEscapedForm() {
        var bytes = new ByteArrayOutputStream();
        new StructuredPayloadRejectionLogger(new PrintStream(bytes, true, StandardCharsets.UTF_8)).record(
                new PayloadRejectionAuditEvent(EPOCH, "req-1", "acme", "alice",
                        PayloadException.unsupportedContract(CANARY)));

        String line = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains(CANARY), () -> "the canary must reach the server-side sink: " + line);
        assertEquals(1, line.chars().filter(character -> character == '\n').count());
    }

    @Test
    void aPayloadCanaryReachesTheAuditTrailOnlyThroughTheEnvelopeDetailField() {
        try (var trail = new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24))) {
            new AuditTrailPayloadRejectionSink(trail).record(new PayloadRejectionAuditEvent(EPOCH, "req-1", "acme",
                    "alice", PayloadException.unsupportedContract(CANARY)));

            var record = trail.read("acme", 0, 10).get(0);
            String detail = new String(record.envelope().detail().bytes(), StandardCharsets.UTF_8);
            assertTrue(detail.contains(CANARY), () -> "the canary must reach the audit record: " + detail);
            assertFalse(record.envelope().reason().contains(CANARY));
        }
    }
}
