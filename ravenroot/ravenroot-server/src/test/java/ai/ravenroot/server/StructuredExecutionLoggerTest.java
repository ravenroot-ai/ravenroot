package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.StableEdgeId;
import ai.ravenroot.server.audit.StructuredExecutionLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredExecutionLoggerTest {
    @Test
    void preservesSseCorrelationFieldsInTheServerAuditLine() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var event = new ExecutionEvent(42, Instant.parse("2026-07-21T20:00:00Z"), "tenant-a", "request-1",
                "apache-pekko", "graph-hash", processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_COMPLETED, "node-1", 0, false, "outcome=continue", null,
                Duration.ofMillis(125));

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"sequence\":42"));
        assertTrue(line.contains("\"processInstanceId\":\"" + processInstanceId + "\""));
        assertTrue(line.contains("\"traversalId\":\"" + traversalId + "\""));
        assertTrue(line.contains("\"executionId\":\"" + traversalId + "\""));
        assertTrue(line.contains("\"invocationId\":\"" + invocationId + "\""));
        assertTrue(line.contains("\"attemptId\":\"" + attemptId + "\""));
        assertTrue(line.contains("\"graphVersion\":\"graph-hash\""));
        assertTrue(line.contains("\"nodeId\":\"node-1\""));
        // The server-side audit line carries the identity, so an execution can be joined to
        // the authorization decision that permitted it without a separate correlation store.
        assertTrue(line.contains("\"tenantId\":\"tenant-a\""), line);
        assertTrue(line.contains("\"requestId\":\"request-1\""), line);
        assertTrue(line.contains("\"processingDuration\":0.125"), line);
    }

    @Test
    @DisplayName("the audit line distinguishes a retry's start from an initial attempt's")
    void carriesTheAttemptScopeSoARetryIsNotIndistinguishableFromAnInitialAttempt() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();

        String initial = StructuredExecutionLogger.toJson(attemptEvent(1, processInstanceId, traversalId,
                invocationId, ExecutionEventType.NODE_STARTED, null, 1, 0));
        String retried = StructuredExecutionLogger.toJson(attemptEvent(2, processInstanceId, traversalId,
                invocationId, ExecutionEventType.NODE_STARTED, null, 2, 0));

        assertTrue(initial.contains("\"attemptOrdinal\":1"), initial);
        assertTrue(retried.contains("\"attemptOrdinal\":2"),
                "without the ordinal a retry's NODE_STARTED is byte-identical to an initial "
                        + "attempt's on the one line an operator greps, so three starts could not be "
                        + "told from one visit retried twice: " + retried);
    }

    @Test
    void carriesTheConnectorCountAndTheRetryClassifierWithAbsenceKeptAsAbsence() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();

        String retry = StructuredExecutionLogger.toJson(attemptEvent(3, processInstanceId, traversalId,
                invocationId, ExecutionEventType.NODE_RETRY_SCHEDULED, "retryable-no-effect", 1, 3));
        assertTrue(retry.contains("\"connectorAttempts\":3"), retry);
        assertTrue(retry.contains("\"publicReason\":\"retryable-no-effect\""),
                "the classifier is what turns a run of retry lines into a diagnosis: " + retry);

        String silent = StructuredExecutionLogger.toJson(attemptEvent(4, processInstanceId, traversalId,
                invocationId, ExecutionEventType.NODE_STARTED, null, 1, 0));
        assertTrue(silent.contains("\"connectorAttempts\":0"),
                "a node that reported nothing must read as nothing, not as one attempt: " + silent);
        assertTrue(silent.contains("\"publicReason\":null"),
                "an absent classifier stays absent rather than becoming an empty string: " + silent);
    }

    /**
     * The exact set of keys the audit line carries and the SSE frame does not.
     *
     * <p>This class's own Javadoc used to say the two projections "differ in exactly one respect".
     * It had silently become three, and nothing caught it because nothing compared them. The set is
     * asserted rather than described so the next addition to either serialiser has to come here and
     * say which side it belongs on — which is the review this divergence deserves, since one side is
     * an operator's audit log and the other is a browser's event stream with its own disclosure
     * rules (SEC-07).</p>
     *
     * <p>Asserted in both directions. The reverse set is empty today: everything the SSE frame
     * carries, the audit line carries too, apart from the two <em>rendered</em> keys it composes for
     * a browser — {@code description} and the {@code message}/{@code output} projections — which are
     * presentation rather than data.</p>
     */
    @Test
    @DisplayName("the audit line and the SSE frame diverge by exactly the keys this class documents")
    void theDivergenceFromTheSseProjectionIsExactlyTheDocumentedSet() {
        ExecutionEvent event = attemptEvent(7, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionEventType.NODE_RETRY_SCHEDULED, "retryable-no-effect", 2, 3);

        Set<String> auditOnly = new TreeSet<>(topLevelKeys(StructuredExecutionLogger.toJson(event)));
        Set<String> sseKeys = topLevelKeys(RavenrootServer.executionEventJson(event));
        auditOnly.removeAll(sseKeys);

        assertEquals(Set.of("tenantId", "requestId", "attemptOrdinal", "connectorAttempts", "event",
                        "joinWaitDuration", "nodeCatalogKey", "deploymentId", "workloadId", "detail"),
                auditOnly,
                "the audit projection gained or lost a key without this class's Javadoc being "
                        + "updated to say which side it belongs on");

        Set<String> sseOnly = new TreeSet<>(sseKeys);
        sseOnly.removeAll(topLevelKeys(StructuredExecutionLogger.toJson(event)));
        assertEquals(Set.of("description", "message", "messageRedacted", "messageTruncated"), sseOnly,
                "the SSE frame carries only rendered presentation the audit line has no reader for");
    }

    /**
     * Top-level keys of a flat JSON object, by name.
     *
     * <p>Both serialisers emit one flat object of scalars, and the fixture above uses values with no
     * quotes or colons in them, so a name-shaped match is exact here rather than approximate. A
     * general JSON parser would be the right tool against arbitrary input and is not needed against
     * two known producers and one known event.</p>
     */
    private static Set<String> topLevelKeys(String json) {
        var keys = new TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("\"([a-zA-Z]+)\":").matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static ExecutionEvent attemptEvent(long sequence, UUID processInstanceId, UUID traversalId,
                                               UUID invocationId, ExecutionEventType type,
                                               String publicReason, int attemptOrdinal,
                                               int connectorAttempts) {
        return new ExecutionEvent(sequence, Instant.parse("2026-07-21T20:00:00Z"), "tenant-a", "request-1",
                "apache-pekko", "graph-hash", processInstanceId, traversalId, invocationId,
                UUID.randomUUID(), type, "node-1", 0, false, "detail", null, null, null, null, null, 0,
                publicReason, null, null, null, attemptOrdinal, connectorAttempts);
    }

    @Test
    void joinWaitDurationIsNullForANonJoinEventRatherThanZero() {
        var event = new ExecutionEvent(1, Instant.parse("2026-08-10T00:00:00Z"), "tenant-a", "request-1", "engine",
                "v1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionEventType.NODE_COMPLETED, "node-1", 0, false, "outcome=continue", null);

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"joinWaitDuration\":null"), line);
        assertTrue(line.contains("\"processingDuration\":null"), line);
    }

    @Test
    void joinWaitDurationIsRenderedAsSecondsForAJoinSettlementEvent() {
        var event = new ExecutionEvent(1, Instant.parse("2026-08-10T00:00:00Z"), "tenant-a", "request-1", "engine",
                "v1", UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.JOIN_SATISFIED, "join",
                0, false, "quorum=2 arrived=2", Duration.ofMillis(437));

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"joinWaitDuration\":0.437"), line);
    }

    /** ADR 0021 D5's identity half reaching the server-side audit line. */
    @Test
    void deploymentAndWorkloadIdentityReachTheAuditLineWhenPresent() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var event = new ExecutionEvent(1, Instant.parse("2026-08-10T00:00:00Z"), "tenant-a", "request-1", "engine",
                "v1", processInstanceId, traversalId, null, null, ExecutionEventType.EXECUTION_STARTED, null, 0,
                false, "execution accepted", null, null, null, "orders", traversalId.toString());

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"deploymentId\":\"orders\""), line);
        assertTrue(line.contains("\"workloadId\":\"" + traversalId + "\""), line);
    }

    /** Absence must render as JSON {@code null}, never an empty string, for a playground submission. */
    @Test
    void deploymentAndWorkloadIdentityAreNullRatherThanEmptyWhenAbsent() {
        var event = new ExecutionEvent(1, Instant.parse("2026-08-10T00:00:00Z"), "tenant-a", "request-1", "engine",
                "v1", UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.EXECUTION_STARTED, null,
                0, false, "execution accepted", null);

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"deploymentId\":null"), line);
        assertTrue(line.contains("\"workloadId\":null"), line);
    }

    @Test
    void edgeTraversalIdentityReachesTheStructuredAuditLine() {
        var event = new ExecutionEvent(9, Instant.EPOCH, "tenant-a", "request-1", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionEventType.EDGE_TRAVERSED, "source", 0, false, "edge traversed",
                null, null, null, null, null, 0, "continue", null, null, "edge-9");

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"type\":\"EDGE_TRAVERSED\""), line);
        assertTrue(line.contains("\"edgeId\":\"edge-9\""), line);
    }

    @Test
    void maximumStableIdentityReachesTheStructuredAuditLineWithoutTruncation() {
        String oneByteControl = Character.toString(1);
        String edgeId = oneByteControl.repeat(StableEdgeId.MAX_UTF8_BYTES);
        var event = new ExecutionEvent(9, Instant.EPOCH, "tenant-a", "request-1", "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionEventType.EDGE_TRAVERSED, "source", 0, false, "edge traversed",
                null, null, null, null, null, 0, "continue", null, null, edgeId);

        String line = StructuredExecutionLogger.toJson(event);

        assertTrue(line.contains("\"edgeId\":\"" + "\\u0001".repeat(StableEdgeId.MAX_UTF8_BYTES) + "\""),
                "the audit sink must preserve every escaped identity byte");
    }
}
