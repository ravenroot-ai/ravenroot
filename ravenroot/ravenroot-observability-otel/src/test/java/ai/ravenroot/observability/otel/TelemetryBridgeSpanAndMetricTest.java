package ai.ravenroot.observability.otel;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link TelemetryBridge} directly with real {@link
 * ExecutionEvent} instances (no {@code ExecutionMonitor} in this test: pairing and history are
 * already {@code ExecutionMonitor}'s own tested responsibility in {@code ravenroot-core}; this
 * class owns only the event-to-span/metric translation) and inspects what a real OpenTelemetry SDK,
 * wired to in-memory exporters, actually recorded.
 */
class TelemetryBridgeSpanAndMetricTest {
    private static final String TENANT_ID = "tenant-a";
    private static final String REQUEST_ID = "request-1";

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build();
    private final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metrics)
            .build();
    private final OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();
    private final TelemetryBridge bridge = new TelemetryBridge(openTelemetry);

    @AfterEach
    void shutdown() {
        bridge.close();
        tracerProvider.shutdown();
        meterProvider.shutdown();
    }

    @Test
    void executionStartedAndCompletedProduceOneSpanWithTheRealElapsedDuration() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant start = Instant.parse("2026-08-10T00:00:00Z");
        Instant end = start.plusMillis(250);

        bridge.accept(traversalEvent(1, start, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        bridge.accept(traversalEvent(2, end, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_COMPLETED, "done"));

        List<SpanData> finished = spans.getFinishedSpanItems();
        assertEquals(1, finished.size());
        SpanData span = finished.get(0);
        assertEquals("ravenroot.execution", span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(start, Instant.ofEpochSecond(0, span.getStartEpochNanos()),
                "the span must be timestamped with the event's own occurredAt, not wall-clock processing time");
        assertEquals(traversalId.toString(), span.getAttributes().get(io.opentelemetry.api.common.AttributeKey
                .stringKey("ravenroot.traversal_id")));

        MetricData executionDuration = onlyMetric(metrics, "ravenroot.execution.duration");
        double recordedSeconds = executionDuration.getHistogramData().getPoints().iterator().next().getSum();
        assertEquals(0.25, recordedSeconds, 0.001,
                "the histogram must record the real 250ms delta between the two events, not a fabricated value");
    }

    @Test
    void executionFailedIsRecordedWithErrorStatus() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant start = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(traversalEvent(1, start, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        bridge.accept(traversalEvent(2, start.plusSeconds(1), processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_FAILED, "boom"));

        SpanData span = spans.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }

    @Test
    void aNodeSpanIsAChildOfItsTraversalSpan() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(traversalEvent(1, t0, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        bridge.accept(nodeEvent(2, t0.plusMillis(10), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_STARTED, "review", "node processing started", false));
        bridge.accept(nodeEvent(3, t0.plusMillis(60), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_COMPLETED, "review", "outcome=continue", false));
        bridge.accept(traversalEvent(4, t0.plusMillis(70), processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_COMPLETED, "done"));

        List<SpanData> finished = spans.getFinishedSpanItems();
        assertEquals(2, finished.size());
        SpanData traversalSpan = finished.stream().filter(s -> s.getName().equals("ravenroot.execution"))
                .findFirst().orElseThrow();
        SpanData nodeSpan = finished.stream().filter(s -> s.getName().equals("ravenroot.node"))
                .findFirst().orElseThrow();
        assertEquals(traversalSpan.getSpanContext().getSpanId(), nodeSpan.getParentSpanId());
        assertEquals(traversalSpan.getSpanContext().getTraceId(), nodeSpan.getSpanContext().getTraceId());

        MetricData nodeDuration = onlyMetric(metrics, "ravenroot.node.duration");
        double recordedSeconds = nodeDuration.getHistogramData().getPoints().iterator().next().getSum();
        assertEquals(0.05, recordedSeconds, 0.001);
    }

    @Test
    void nodeDefaultedDoesNotCloseTheSpanThatTheFollowingNodeCompletedCloses() {
        // ExecutionMonitor's own contract (nodeCompleted): a fallback node publishes NODE_DEFAULTED
        // immediately before NODE_COMPLETED, both carrying the same invocationId/attemptId. A bridge
        // that treated NODE_DEFAULTED as terminal would end the span twice (or find nothing the
        // second time) -- this is exactly the class of mistake JoinWaitDurationTest's first draft
        // made for a different reason, so it is asserted here directly rather than assumed.
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(traversalEvent(1, t0, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        bridge.accept(nodeEvent(2, t0.plusMillis(10), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_STARTED, "unknown-node", "node processing started", false));
        bridge.accept(nodeEvent(3, t0.plusMillis(20), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_DEFAULTED, "unknown-node", "unknown behavior executed as pass-through",
                true));
        bridge.accept(nodeEvent(4, t0.plusMillis(30), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_COMPLETED, "unknown-node", "outcome=continue", true));
        bridge.accept(traversalEvent(5, t0.plusMillis(40), processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_COMPLETED, "done"));

        List<SpanData> nodeSpans = spans.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("ravenroot.node")).toList();
        assertEquals(1, nodeSpans.size(),
                "NODE_DEFAULTED must not end the node span a following NODE_COMPLETED is about to end");
        List<EventData> events = nodeSpans.get(0).getEvents();
        assertTrue(events.stream().anyMatch(e -> e.getName().equals("ravenroot.node.defaulted")),
                "the defaulted marker must survive as a span event rather than being lost");
    }

    @Test
    void nodeBypassedIsATerminalSuccessfulSpanAndDurationOutcome() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(nodeEvent(1, t0, processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_STARTED, "structural", "node processing started", false));
        bridge.accept(nodeEvent(2, t0.plusMillis(25), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_BYPASSED, "structural", "incoming command=passthrough", false));

        List<SpanData> nodeSpans = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("ravenroot.node")).toList();
        assertEquals(1, nodeSpans.size());
        assertEquals(StatusCode.OK, nodeSpans.getFirst().getStatus().getStatusCode());
        MetricData duration = onlyMetric(metrics, "ravenroot.node.duration");
        var point = duration.getHistogramData().getPoints().iterator().next();
        assertEquals(1, point.getCount());
        assertEquals(0.025, point.getSum(), 0.001);
        assertEquals("NODE_BYPASSED", point.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.event_type")));
    }

    @Test
    void repeatedBypassAndALaterCompletionCannotDoubleCloseOrCreateCompletedTelemetry() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(nodeEvent(1, t0, processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_STARTED, "structural", "node processing started", false));
        bridge.accept(nodeEvent(2, t0.plusMillis(10), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_BYPASSED, "structural", "incoming command=passthrough", false));
        bridge.accept(nodeEvent(3, t0.plusMillis(20), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_BYPASSED, "structural", "duplicate", false));
        bridge.accept(nodeEvent(4, t0.plusMillis(30), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_COMPLETED, "structural", "late completion", false));

        UUID nextAttempt = UUID.randomUUID();
        bridge.accept(nodeEvent(5, t0.plusMillis(40), processInstanceId, traversalId, invocationId, nextAttempt,
                ExecutionEventType.NODE_STARTED, "next", "node processing started", false));
        bridge.accept(nodeEvent(6, t0.plusMillis(50), processInstanceId, traversalId, invocationId, nextAttempt,
                ExecutionEventType.NODE_BYPASSED, "next", "incoming command=passthrough", false));

        List<SpanData> nodeSpans = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("ravenroot.node")).toList();
        assertEquals(2, nodeSpans.size(), "every terminal bypass removes its pending span exactly once");
        MetricData duration = onlyMetric(metrics, "ravenroot.node.duration");
        List<? extends io.opentelemetry.sdk.metrics.data.HistogramPointData> points =
                duration.getHistogramData().getPoints().stream().toList();
        assertEquals(1, points.size(), "both bypasses share the only terminal outcome series");
        assertEquals(2, points.getFirst().getCount(), "duplicates and a late completion must not add duration");
        assertEquals("NODE_BYPASSED", points.getFirst().getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.event_type")));
        assertFalse(points.stream().anyMatch(point -> "NODE_COMPLETED".equals(point.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.event_type")))));
        assertFalse(points.stream().anyMatch(point -> "NODE_DEFAULTED".equals(point.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.event_type")))));
    }

    @Test
    void joinSatisfiedRecordsTheDurationHistogramAndNeverRecordsANonJoinEventThere() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(traversalEvent(1, t0, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        bridge.accept(joinEvent(2, t0.plusMillis(500), processInstanceId, traversalId, "join",
                ExecutionEventType.JOIN_SATISFIED, "quorum=2 arrived=2", Duration.ofMillis(500)));
        bridge.accept(traversalEvent(3, t0.plusMillis(510), processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_COMPLETED, "done"));

        MetricData joinWait = onlyMetric(metrics, "ravenroot.join.wait");
        var point = joinWait.getHistogramData().getPoints().iterator().next();
        assertEquals(1, point.getCount(), "only the JOIN_SATISFIED event may contribute a data point here");
        assertEquals(0.5, point.getSum(), 0.001);
    }

    @Test
    void executionsActiveGaugeReflectsOpenTraversalsOnly() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        assertEquals(0, activeExecutionsValue());
        bridge.accept(traversalEvent(1, t0, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        assertEquals(1, activeExecutionsValue());
        bridge.accept(traversalEvent(2, t0.plusSeconds(1), processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_COMPLETED, "done"));
        assertEquals(0, activeExecutionsValue());
    }

    /** ADR 0021 D5's identity half, reaching real span attributes. */
    @Test
    void deploymentAndWorkloadIdentityReachTraversalAndNodeSpanAttributes() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");
        String deploymentId = "orders";
        String workloadId = traversalId.toString();

        bridge.accept(new ExecutionEvent(1, t0, TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId, traversalId,
                null, null, ExecutionEventType.EXECUTION_STARTED, null, 0, false, "execution accepted", null, null,
                null, deploymentId, workloadId));
        bridge.accept(new ExecutionEvent(2, t0.plusMillis(10), TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, invocationId, attemptId, ExecutionEventType.NODE_STARTED, "review", 0, false,
                "node processing started", null, null, null, deploymentId, workloadId));
        bridge.accept(new ExecutionEvent(3, t0.plusMillis(20), TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, invocationId, attemptId, ExecutionEventType.NODE_COMPLETED, "review", 0, false,
                "outcome=continue", null, null, null, deploymentId, workloadId));
        bridge.accept(new ExecutionEvent(4, t0.plusMillis(30), TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, null, null, ExecutionEventType.EXECUTION_COMPLETED, null, 0, false, "done", null, null,
                null, deploymentId, workloadId));

        List<SpanData> finished = spans.getFinishedSpanItems();
        SpanData traversalSpan = finished.stream().filter(s -> s.getName().equals("ravenroot.execution"))
                .findFirst().orElseThrow();
        SpanData nodeSpan = finished.stream().filter(s -> s.getName().equals("ravenroot.node"))
                .findFirst().orElseThrow();

        var deploymentIdKey = io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.deployment_id");
        var workloadIdKey = io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.workload_id");
        assertEquals(deploymentId, traversalSpan.getAttributes().get(deploymentIdKey));
        assertEquals(workloadId, traversalSpan.getAttributes().get(workloadIdKey));
        assertEquals(deploymentId, nodeSpan.getAttributes().get(deploymentIdKey));
        assertEquals(workloadId, nodeSpan.getAttributes().get(workloadIdKey));
    }

    /** A one-shot/playground event's absence must reach the span as an empty attribute, not a lie. */
    @Test
    void anAbsentDeploymentAndWorkloadIdentityReachTheSpanAsEmptyRatherThanBeingOmitted() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(traversalEvent(1, t0, processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_STARTED, "execution accepted"));
        bridge.accept(traversalEvent(2, t0.plusMillis(10), processInstanceId, traversalId,
                ExecutionEventType.EXECUTION_COMPLETED, "done"));

        SpanData span = spans.getFinishedSpanItems().get(0);
        assertEquals("", span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.deployment_id")));
        assertEquals("", span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("ravenroot.workload_id")));
    }

    private long activeExecutionsValue() {
        MetricData gauge = onlyMetric(metrics, "ravenroot.executions.active");
        return gauge.getLongGaugeData().getPoints().iterator().next().getValue();
    }

    private static MetricData onlyMetric(InMemoryMetricReader reader, String name) {
        List<MetricData> matches = reader.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name)).toList();
        assertEquals(1, matches.size(), () -> "expected exactly one metric named " + name);
        return matches.get(0);
    }

    private static ExecutionEvent traversalEvent(long sequence, Instant occurredAt, UUID processInstanceId,
                                                 UUID traversalId, ExecutionEventType type, String detail) {
        return new ExecutionEvent(sequence, occurredAt, TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, null, null, type, null, 0, false, detail, null);
    }

    private static ExecutionEvent nodeEvent(long sequence, Instant occurredAt, UUID processInstanceId,
                                            UUID traversalId, UUID invocationId, UUID attemptId,
                                            ExecutionEventType type, String nodeId, String detail,
                                            boolean fallback) {
        return new ExecutionEvent(sequence, occurredAt, TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, invocationId, attemptId, type, nodeId, 0, fallback, detail, null);
    }

    private static ExecutionEvent joinEvent(long sequence, Instant occurredAt, UUID processInstanceId,
                                            UUID traversalId, String nodeId, ExecutionEventType type,
                                            String detail, Duration joinWaitDuration) {
        return new ExecutionEvent(sequence, occurredAt, TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, null, null, type, nodeId, 0, false, detail, joinWaitDuration);
    }
}
