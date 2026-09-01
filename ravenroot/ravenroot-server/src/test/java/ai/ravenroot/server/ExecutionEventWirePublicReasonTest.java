package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.execution.NodeActionDiagnostic;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire shape produced by the one serializer SSE and {@code /v1/events/recent} share.
 */
class ExecutionEventWirePublicReasonTest {

    @Test
    void aRoutedFailureIsNotSerializedAsASuccess() {
        String json = RavenrootServer.executionEventJson(event(ExecutionEventType.NODE_COMPLETED, "failed"));

        assertFalse(json.contains("successfully"), json);
        assertTrue(json.contains("Node completed and routed its \\\"failed\\\" outcome."), json);
        assertTrue(json.contains("\"publicReason\":\"failed\""), json);
    }

    /**
     * The key is removed, not repurposed. It held the public sentence under a name that promises the
     * diagnostic, which made the wire itself carry a false statement — the same defect as the panel's,
     * aimed at whoever reads this API. Nothing was promised it: {@code docs/api/openapi.json} never
     * declared it.
     */
    @Test
    void theLegacyDetailAliasIsGoneRatherThanCarryingSomethingThatIsNotTheDetail() {
        String json = RavenrootServer.executionEventJson(event(ExecutionEventType.NODE_COMPLETED, "failed"));

        assertFalse(json.contains("\"detail\""), json);
    }

    /** The authenticated author gets useful safe text, while raw detail remains structurally absent. */
    @Test
    void usefulFailureTextIsSeparateFromItsClassifierAndRedactedOnTheWire() {
        String secret = "hunter2-wire-sentinel";
        var failure = new ExecutionEvent(1L, Instant.now(), "tenant", "request", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.NODE_FAILED,
                "mail-1", 0, false, "SMTP rejected alice: password=" + secret + "; retry disabled",
                null, null, null, null, null, 0, "IllegalStateException",
                RuntimeActivityData.message("SMTP rejected alice: password=" + secret + "; retry disabled"), null);

        String json = RavenrootServer.executionEventJson(failure);

        assertFalse(json.contains(secret), json);
        assertFalse(json.contains("\"detail\""), json);
        assertTrue(json.contains("SMTP rejected alice"), json);
        assertTrue(json.contains("retry disabled"), json);
        assertTrue(json.contains(RuntimeActivityData.REDACTION_MARKER), json);
        assertTrue(json.contains("\"messageRedacted\":true"), json);
        assertTrue(json.contains("\"messageTruncated\":false"), json);
        assertTrue(json.contains("\"publicReason\":\"IllegalStateException\""), json);
    }

    @Test
    void quotedAssignedCredentialIsFullyRedactedOnTheWire() {
        var failure = new ExecutionEvent(1L, Instant.now(), "tenant", "request", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.NODE_FAILED,
                "mail-1", 0, false,
                "SMTP payload {\"password\":\"alpha beta gamma\"}; retry disabled",
                null, null, null, null, null, 0, "IllegalStateException",
                RuntimeActivityData.message(
                        "SMTP payload {\"password\":\"alpha beta gamma\"}; retry disabled"), null);

        String json = RavenrootServer.executionEventJson(failure);

        assertFalse(json.contains("alpha"), json);
        assertFalse(json.contains("beta gamma"), json);
        assertTrue(json.contains(RuntimeActivityData.REDACTION_MARKER), json);
        assertTrue(json.contains("\"messageRedacted\":true"), json);
        assertTrue(json.contains("retry disabled"), json);
    }

    @Test
    void trustedLogOutputUsesItsOwnSafeFieldsAndNeverAliasesTraversalPayload() {
        var output = NodeActionDiagnostic.log("logged customer-42 token=wire-secret").output();
        var completed = new ExecutionEvent(1L, Instant.now(), "tenant", "request", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.NODE_COMPLETED,
                "log-1", 0, false, "outcome=continue", null, null, "log", null, null, 0,
                "continue", null, output);

        String json = RavenrootServer.executionEventJson(completed);

        assertTrue(json.contains("logged customer-42"), json);
        assertFalse(json.contains("wire-secret"), json);
        assertTrue(json.contains("\"outputRedacted\":true"), json);
        assertFalse(json.contains("\"detail\""), json);
    }

    @Test
    void reboundLogOutputSerializesBothFlagsAndVisibleMarkersWithinTheWireBound() {
        var large = new LinkedHashMap<String, Object>();
        for (int index = 0; index <= 30; index++) {
            large.put("a%02d".formatted(index), "x".repeat(1_000));
        }
        large.put("zz_password", "late-wire-secret");
        var output = NodeActionDiagnostic.log(large).output();
        var completed = new ExecutionEvent(1L, Instant.now(), "tenant", "request", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.NODE_COMPLETED,
                "log-1", 0, false, "outcome=continue", null, null, "log", null, null, 0,
                "continue", null, output);

        String json = RavenrootServer.executionEventJson(completed);

        assertFalse(json.contains("late-wire-secret"), json);
        assertTrue(json.contains(RuntimeActivityData.REDACTION_MARKER), json);
        assertTrue(json.contains(RuntimeActivityData.TRUNCATION_MARKER), json);
        assertTrue(json.contains("\"outputRedacted\":true"), json);
        assertTrue(json.contains("\"outputTruncated\":true"), json);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length
                <= RuntimeActivityData.MAX_OUTPUT_UTF8_BYTES + 2_048, json);
    }

    /** Absent stays absent: "" would be a token a reader could look up nowhere. */
    @Test
    void anEventWithNoClassifierSerializesNullRatherThanAnEmptyToken() {
        String json = RavenrootServer.executionEventJson(event(ExecutionEventType.NODE_STARTED, null));

        assertTrue(json.contains("\"publicReason\":null"), json);
    }

    @Test
    void edgeTraversalCarriesItsStableIdentityWithoutAnyPayloadOrDiagnostic() {
        var event = new ExecutionEvent(7L, Instant.now(), "tenant", "request", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionEventType.EDGE_TRAVERSED, "source", 0, false, "must stay internal",
                null, null, null, null, null, 0, "continue", null, null, "edge-7");

        String json = RavenrootServer.executionEventJson(event);

        assertTrue(json.contains("\"type\":\"EDGE_TRAVERSED\""), json);
        assertTrue(json.contains("\"edgeId\":\"edge-7\""), json);
        assertTrue(json.contains("\"nodeId\":\"source\""), json);
        assertFalse(json.contains("must stay internal"), json);
    }

    private static ExecutionEvent event(ExecutionEventType type, String publicReason) {
        return new ExecutionEvent(1L, Instant.now(), "tenant", "request", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, type, "cel-1", 0, false,
                "outcome=failed", null, null, null, null, null, 0, publicReason);
    }
}
