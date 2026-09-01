package ai.ravenroot.observability.otel;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAT-01 redaction boundary under SEC-14: this class owns redaction only at the translation from
 * {@link ExecutionEvent} into spans/metrics; the general "logging and telemetry privacy-safe"
 * sweep is outside this boundary. {@code event.detail()} is <strong>not</strong> proven free of
 * secret material upstream: the only such proof,
 * {@code MailExecutionEventSanitizationTest}, lives in {@code ravenroot-extensions/ravenroot-mail}
 * and binds the mail behavior alone, not core and not any other behavior. This class's obligation is
 * unchanged and is narrower than that claim was &mdash; that the bridge's own translation
 * does not <em>widen</em> what a sensitive-looking string can reach: it may appear on a span
 * attribute (spans are not pre-aggregated the way metric series are), and it must never appear as a
 * metric label, which is both a cardinality violation and a values-in-label-name privacy leak in a
 * metrics backend that would otherwise never see it.
 */
class TelemetryBridgeRedactionTest {
    private static final String SENTINEL = "credential-secret-sentinel";

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
    void aSentinelInDetailNeverReachesAMetricLabelEvenThoughItIsExpectedOnTheSpan() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        bridge.accept(new ExecutionEvent(1, t0, "tenant-a", "request-1", "test", "v1", processInstanceId,
                traversalId, null, null, ExecutionEventType.EXECUTION_STARTED, null, 0, false,
                "execution accepted", null));
        bridge.accept(new ExecutionEvent(2, t0.plusMillis(1), "tenant-a", "request-1", "test", "v1",
                processInstanceId, traversalId, invocationId, attemptId, ExecutionEventType.NODE_FAILED, "mail", 0,
                false, SENTINEL, null));
        bridge.accept(new ExecutionEvent(3, t0.plusMillis(2), "tenant-a", "request-1", "test", "v1",
                processInstanceId, traversalId, null, null, ExecutionEventType.EXECUTION_FAILED, null, 0, false,
                SENTINEL, null));

        // Expected: event.detail() is a span attribute by design (spans are not pre-aggregated).
        // It is NOT sanitized upstream -- the sentinel really does reach the span, which is
        // exactly why this works as the control, proving the sentinel reaches the bridge at all
        // rather than the test being vacuous. The bridge's obligation is that it does not reach a
        // metric label, not that it was clean on arrival.
        boolean sentinelOnASpan = spans.getFinishedSpanItems().stream()
                .anyMatch(span -> String.valueOf(span.getAttributes().get(AttributeKey.stringKey("ravenroot.detail")))
                        .contains(SENTINEL));
        assertTrue(sentinelOnASpan, "control failed: the sentinel never reached any span attribute, so this "
                + "test proves nothing about the metric side either");

        // The actual assertion: no metric attribute value anywhere carries the sentinel string.
        for (MetricData metric : metrics.collectAllMetrics()) {
            for (PointData point : metric.getData().getPoints()) {
                String rendered = point.getAttributes().toString();
                assertFalse(rendered.contains(SENTINEL),
                        () -> "metric '" + metric.getName() + "' carries the sentinel in its attributes: "
                                + rendered + " -- detail must never reach a metric label");
            }
        }
    }

    @Test
    void theBridgeNeverConstructsAMetricAttributeFromDetail() {
        // Structural check, not behavioural: TelemetryBridge.METRIC_LABEL_ALLOWLIST is the exhaustive
        // set of keys any metric attribute may use, and "ravenroot.detail" (the span-only attribute
        // key) is not a member. CardinalityAllowlistTest already proves every recorded attribute is
        // drawn from the allowlist; this asserts the allowlist itself excludes the redaction-relevant
        // key, so the two tests together cover both "the set is small" and "detail is not in it".
        assertFalse(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(AttributeKey.stringKey("ravenroot.detail")));
        assertFalse(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(AttributeKey.stringKey("ravenroot.node_id")));
        assertFalse(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(AttributeKey.stringKey("ravenroot.tenant_id")));
    }
}
