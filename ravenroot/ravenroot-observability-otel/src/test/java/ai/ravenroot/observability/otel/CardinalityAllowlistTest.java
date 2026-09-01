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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The telemetry contract requires spans and metrics with controlled cardinality. This is the
 * standing rule from this run applied literally &mdash; a cardinality guard that cannot fail is
 * worse than no guard, because it licenses the belief that cardinality is bounded. This class
 * exercises {@link TelemetryBridge} with many distinct {@code nodeId} and {@code tenantId} values
 * (the two dimensions flagged in {@code TelemetryBridge}'s own Javadoc as unbounded and easy to add
 * "without thinking") and asserts the number of distinct metric series stays bounded by {@link
 * TelemetryBridge#METRIC_LABEL_ALLOWLIST}'s own combinatorics, never by the number of distinct
 * {@code nodeId}/{@code tenantId} values driven through.
 *
 * <h2>The red proof this class is built on</h2>
 * <p>This guard was run against a deliberately mutated {@code TelemetryBridge} that added
 * {@code nodeId} as a label on {@code nodeDuration.record(...)} &mdash; the exact addition
 * {@code TelemetryBridge}'s own Javadoc calls out as "the one a reasonable engineer would add
 * without thinking". {@link #nodeDurationCardinalityStaysBoundedAcrossManyDistinctNodeIds} failed
 * against that mutation ({@code expected: <1> but was: <200>}, matching the 200 distinct
 * {@code nodeId}s driven through), and passes again on the reverted code. That result demonstrates
 * that the control is capable of failing rather than leaving that property as an unverified claim.</p>
 */
class CardinalityAllowlistTest {
    private static final String TENANT_ID = "tenant-a";
    private static final String REQUEST_ID = "request-1";
    private static final int DISTINCT_VALUES = 200;

    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metrics)
            .build();
    private final OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build();
    private final TelemetryBridge bridge = new TelemetryBridge(openTelemetry);

    @AfterEach
    void shutdown() {
        bridge.close();
        meterProvider.shutdown();
    }

    @Test
    void nodeDurationCardinalityStaysBoundedAcrossManyDistinctNodeIds() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        for (int i = 0; i < DISTINCT_VALUES; i++) {
            String nodeId = "node-" + i + "-" + UUID.randomUUID();
            UUID invocationId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            bridge.accept(nodeEvent(t0, processInstanceId, traversalId, invocationId, attemptId,
                    ExecutionEventType.NODE_STARTED, nodeId));
            bridge.accept(nodeEvent(t0.plusMillis(1), processInstanceId, traversalId, invocationId, attemptId,
                    ExecutionEventType.NODE_COMPLETED, nodeId));
        }

        MetricData nodeDuration = onlyMetric("ravenroot.node.duration");
        assertEquals(1, nodeDuration.getData().getPoints().size(),
                DISTINCT_VALUES + " distinct nodeId values were driven through, but the histogram is "
                        + "labeled only by event type (a single value, NODE_COMPLETED, was ever "
                        + "recorded here) -- more than one series here means nodeId or some other "
                        + "unbounded dimension leaked into a metric label");
    }

    @Test
    void executionEventCounterCardinalityStaysBoundedAcrossManyDistinctTenantIds() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        for (int i = 0; i < DISTINCT_VALUES; i++) {
            // A real NODE_STARTED always carries a real attemptId (GraphRunner mints one from
            // ExecutionIdentitySource before ExecutionMonitor.nodeStarted is ever called) -- a null
            // one here would not exercise the cardinality question this test asks, only crash on an
            // input ExecutionMonitor never actually produces.
            bridge.accept(new ExecutionEvent(i, t0.plusMillis(i), "tenant-" + i, "request-" + i, "test", "v1",
                    processInstanceId, traversalId, UUID.randomUUID(), UUID.randomUUID(),
                    ExecutionEventType.NODE_STARTED, "review", 0, false, "node processing started", null));
        }

        MetricData eventCounter = onlyMetric("ravenroot.execution.events");
        assertEquals(1, eventCounter.getData().getPoints().size(),
                DISTINCT_VALUES + " distinct tenantId values were driven through a NODE_STARTED-only "
                        + "workload, but the counter is labeled only by event type (one value here) -- "
                        + "more than one series means tenantId leaked into a metric label");
    }

    @Test
    void everyRecordedMetricAttributeIsInTheAllowlist() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        bridge.accept(new ExecutionEvent(1, t0, TENANT_ID, REQUEST_ID, "test", "v1", processInstanceId,
                traversalId, null, null, ExecutionEventType.EXECUTION_STARTED, null, 0, false,
                "execution accepted", null));
        bridge.accept(nodeEvent(t0.plusMillis(1), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_STARTED, "review"));
        bridge.accept(nodeEvent(t0.plusMillis(2), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_COMPLETED, "review"));
        bridge.accept(new ExecutionEvent(4, t0.plusMillis(3), TENANT_ID, REQUEST_ID, "test", "v1",
                processInstanceId, traversalId, null, null, ExecutionEventType.JOIN_SATISFIED, "join", 0, false,
                "quorum=1 arrived=1", java.time.Duration.ofMillis(3)));
        bridge.accept(new ExecutionEvent(5, t0.plusMillis(4), TENANT_ID, REQUEST_ID, "test", "v1",
                processInstanceId, traversalId, null, null, ExecutionEventType.EXECUTION_COMPLETED, null, 0, false,
                "done", null));

        for (MetricData metric : metrics.collectAllMetrics()) {
            for (PointData point : metric.getData().getPoints()) {
                for (AttributeKey<?> key : point.getAttributes().asMap().keySet()) {
                    assertTrue(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(key),
                            () -> "metric '" + metric.getName() + "' carries attribute '" + key.getKey()
                                    + "', which is not in TelemetryBridge.METRIC_LABEL_ALLOWLIST -- every "
                                    + "metric attribute this bridge records must be declared there");
                }
            }
        }
    }

    private MetricData onlyMetric(String name) {
        List<MetricData> matches = metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name)).toList();
        assertEquals(1, matches.size(), () -> "expected exactly one metric named " + name);
        return matches.get(0);
    }


    /**
     * The node-type label is bounded by the CATALOG, not by instances. Two hundred distinct
     * {@code nodeId}s spread across three catalog keys must produce three series, not two hundred and
     * not one.
     *
     * <p>Both halves matter. Three rather than two hundred is the cardinality claim. Three rather than
     * one is what stops this test passing against a bridge that silently drops the label — without it
     * the assertion would be satisfied by never recording the dimension at all, which is the failure
     * mode a guard extended for a new label is most likely to acquire.
     */
    @Test
    void nodeDurationSeriesAreBoundedByTheCatalogNotByNodeInstances() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");
        List<String> catalogKeys = List.of("http-request", "cel-transform", "mail.send");

        for (int i = 0; i < DISTINCT_VALUES; i++) {
            String nodeId = "node-" + i + "-" + UUID.randomUUID();
            String catalogKey = catalogKeys.get(i % catalogKeys.size());
            UUID invocationId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            bridge.accept(nodeEvent(t0, processInstanceId, traversalId, invocationId, attemptId,
                    ExecutionEventType.NODE_STARTED, nodeId, catalogKey));
            bridge.accept(nodeEvent(t0.plusMillis(1), processInstanceId, traversalId, invocationId, attemptId,
                    ExecutionEventType.NODE_COMPLETED, nodeId, catalogKey));
        }

        MetricData nodeDuration = onlyMetric("ravenroot.node.duration");
        assertEquals(catalogKeys.size(), nodeDuration.getData().getPoints().size(),
                DISTINCT_VALUES + " distinct nodeId values across " + catalogKeys.size() + " catalog keys "
                        + "must produce exactly " + catalogKeys.size() + " series. More means an "
                        + "instance identifier leaked into a label; fewer means the node-type label is "
                        + "not being recorded at all and this guard is watching nothing");
    }

    /**
     * An absent catalog key records without the label rather than under a placeholder. A structural
     * node and an unregistered behavior both arrive here as {@code null}, and a synthetic value would
     * be a series indistinguishable from a real node type in every dashboard built on it.
     */
    @Test
    void anAbsentCatalogKeyProducesNoNodeTypeLabelRatherThanAPlaceholder() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        bridge.accept(nodeEvent(t0, processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_STARTED, "structural-node", null));
        bridge.accept(nodeEvent(t0.plusMillis(1), processInstanceId, traversalId, invocationId, attemptId,
                ExecutionEventType.NODE_COMPLETED, "structural-node", null));

        MetricData nodeDuration = onlyMetric("ravenroot.node.duration");
        assertEquals(1, nodeDuration.getData().getPoints().size());
        assertTrue(nodeDuration.getData().getPoints().stream()
                        .noneMatch(point -> point.getAttributes().asMap().keySet().stream()
                                .anyMatch(key -> key.getKey().equals("ravenroot.node_type"))),
                "an event with no catalog key must record no node-type label at all, not an empty or "
                        + "synthetic one");
    }

    /**
     * The structural guard, restated for the new label: the allowlist gained exactly one entry, and
     * the identifier that must never be a label is still refused.
     */
    @Test
    void theAllowlistGainedExactlyOneBoundedEntryAndStillRefusesInstanceIdentifiers() {
        assertEquals(2, TelemetryBridge.METRIC_LABEL_ALLOWLIST.size(),
                "the allowlist should carry event_type and node_type and nothing else: "
                        + TelemetryBridge.METRIC_LABEL_ALLOWLIST);
        assertTrue(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(TelemetryBridge.METRIC_ATTR_NODE_TYPE));
        assertFalse(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(AttributeKey.stringKey("ravenroot.node_id")));
        assertFalse(TelemetryBridge.METRIC_LABEL_ALLOWLIST.contains(AttributeKey.stringKey("ravenroot.tenant_id")));
    }

    /**
     * The ADR 0021 D5 guard must not regress: {@code deploymentId} and
     * {@code workloadId} are the identity half, span attributes only, and must never contribute a
     * metric series. {@code everyRecordedMetricAttributeIsInTheAllowlist} above already covers this
     * structurally -- neither key is in {@link TelemetryBridge#METRIC_LABEL_ALLOWLIST}, so either
     * leaking in would fail that test too -- but this drives many distinct values through specifically
     * for this pair, the same shape {@code nodeDurationCardinalityStaysBoundedAcrossManyDistinctNodeIds}
     * already uses for {@code nodeId}, so a future change to either helper method leaves an explicit,
     * on-topic test in place rather than relying on a generic one to happen to still cover it.
     */
    @Test
    void deploymentAndWorkloadIdentityNeverContributeAMetricSeriesAcrossManyDistinctValues() {
        UUID processInstanceId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-10T00:00:00Z");

        for (int i = 0; i < DISTINCT_VALUES; i++) {
            UUID traversalId = UUID.randomUUID();
            String deploymentId = "deployment-" + i;
            String workloadId = traversalId.toString();
            bridge.accept(new ExecutionEvent(i, t0.plusMillis(i), TENANT_ID, REQUEST_ID, "test", "v1",
                    processInstanceId, traversalId, null, null, ExecutionEventType.EXECUTION_STARTED, null, 0, false,
                    "execution accepted", null, null, null, deploymentId, workloadId));
        }

        MetricData eventCounter = onlyMetric("ravenroot.execution.events");
        assertEquals(1, eventCounter.getData().getPoints().size(),
                DISTINCT_VALUES + " distinct deploymentId/workloadId pairs were driven through an "
                        + "EXECUTION_STARTED-only workload, but the counter is labeled only by event type "
                        + "(one value here) -- more than one series means one of the two leaked into a "
                        + "metric label");
        for (MetricData metric : metrics.collectAllMetrics()) {
            for (PointData point : metric.getData().getPoints()) {
                assertFalse(point.getAttributes().asMap().keySet().stream()
                                .anyMatch(key -> key.getKey().equals("ravenroot.deployment_id")
                                        || key.getKey().equals("ravenroot.workload_id")),
                        () -> "metric '" + metric.getName() + "' carries a deployment/workload identity "
                                + "attribute -- both are unbounded and must stay on spans only");
            }
        }
    }

    private static ExecutionEvent nodeEvent(Instant occurredAt, UUID processInstanceId, UUID traversalId,
                                            UUID invocationId, UUID attemptId, ExecutionEventType type,
                                            String nodeId) {
        return nodeEvent(occurredAt, processInstanceId, traversalId, invocationId, attemptId, type, nodeId, null);
    }

    private static ExecutionEvent nodeEvent(Instant occurredAt, UUID processInstanceId, UUID traversalId,
                                            UUID invocationId, UUID attemptId, ExecutionEventType type,
                                            String nodeId, String nodeCatalogKey) {
        return new ExecutionEvent(occurredAt.toEpochMilli(), occurredAt, TENANT_ID, REQUEST_ID, "test", "v1",
                processInstanceId, traversalId, invocationId, attemptId, type, nodeId, 0, false, "detail", null,
                null, nodeCatalogKey);
    }
}
