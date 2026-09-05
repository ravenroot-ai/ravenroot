package ai.ravenroot.observability.otel;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redaction boundary and SEC-14 trace end-to-end evidence, tested against the real pipeline
 * rather than hand-built {@code ExecutionEvent}s.
 *
 * <p>Extends {@code MailExecutionEventSanitizationTest}'s pattern (real {@code GraphRunner}, a
 * hostile payload/failure, asserted against the final observable surface) one hop further, into
 * this module's own translation: the surface under test is spans and metrics, not
 * {@code ExecutionEvent} itself, which {@code ravenroot-core} already covers. The SEC-14 boundary
 * is this: the translation from
 * {@link ai.ravenroot.api.application.ExecutionEvent} and {@link SecurityContext} into span
 * attributes, metric labels and log-correlation fields never emits more than the event already
 * exposes -- it must not reach past the event into the raw {@code NodeMessage} payload or a raw
 * exception message. Everything not reachable through {@code ExecutionMonitor} is outside this
 * class's boundary.</p>
 */
class TelemetryEndToEndTest {
    private static final String PAYLOAD_SENTINEL = "payload-secret-sentinel";
    private static final String FAILURE_SENTINEL = "raw-exception-secret-sentinel";

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

    @AfterEach
    void shutdown() {
        tracerProvider.shutdown();
        meterProvider.shutdown();
    }

    /**
     * The trace-end-to-end documented requirements artifact: a real traversal, through the real {@link GraphRunner} and
     * a real {@link ExecutionMonitor}, captured by an in-memory {@code SpanExporter}. Also proves
     * the payload half of the redaction boundary: {@code NodeMessage.payload()} carries the
     * sentinel, and it never reaches a span or a metric, because nothing in this module's
     * translation reads a payload at all -- only {@code ExecutionEvent}'s own fields, none of which
     * is the payload.
     */
    @Test
    void aRealTraversalProducesATraceEndToEndAndNeverSurfacesTheRawPayload() throws Exception {
        var monitor = new ExecutionMonitor();
        var bridge = new TelemetryBridge(openTelemetry);
        try (var unsubscribe = monitor.subscribe(bridge);
             var engine = new SameThreadEngine();
             var runner = new GraphRunner(linearGraph("record"), engine,
                     new BehaviorRegistry().register("record", TelemetryEndToEndTest::echo), monitor)) {
            var security = new SecurityContext("request-1", "tenant-a", "alice", PrincipalType.USER,
                    "urn:ravenroot:test");
            var result = runner.execute(security, Map.of("secret", PAYLOAD_SENTINEL))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.visitedNodes().contains("middle"), "the traversal must have actually run");
        } finally {
            bridge.close();
        }

        List<SpanData> finished = spans.getFinishedSpanItems();
        // The trace: one root (traversal) span with three child (node) spans underneath it,
        // sharing one trace id -- the trace itself is the documented "trace end-to-end" evidence.
        assertEquals(4, finished.size());
        String traceId = finished.get(0).getSpanContext().getTraceId();
        assertTrue(finished.stream().allMatch(span -> span.getSpanContext().getTraceId().equals(traceId)),
                "every span in one traversal must share one trace id");

        for (SpanData span : finished) {
            String rendered = span.getAttributes().toString();
            assertFalse(rendered.contains(PAYLOAD_SENTINEL),
                    () -> "span '" + span.getName() + "' exposes the raw NodeMessage payload: " + rendered);
        }
        for (MetricData metric : metrics.collectAllMetrics()) {
            for (PointData point : metric.getData().getPoints()) {
                assertFalse(point.getAttributes().toString().contains(PAYLOAD_SENTINEL),
                        () -> "metric '" + metric.getName() + "' exposes the raw NodeMessage payload");
            }
        }
    }

    /**
     * The failure half. A node behavior that lets a raw exception escape (unlike the mail
     * extension's behaviors, which sanitize before throwing -- {@code MailExecutionEventSanitizationTest}
     * covers that discipline) is a defect in {@code ravenroot-core}'s own
     * {@code ExecutionMonitor.publish}, which takes the root cause's message as {@code detail}
     * verbatim; that boundary is core's, proven there, not re-litigated here. What this class owns
     * is narrower and is what it asserts: given that {@code detail} legitimately carries the
     * sentinel (the control below proves it reaches a span, exactly as designed), the bridge's own
     * translation must still never let it reach a metric label -- the same guarantee
     * {@code CardinalityAllowlistTest} proves structurally, reproduced here against the real
     * pipeline rather than a hand-built event.
     */
    @Test
    void aRawExceptionMessageReachesTheSpanButNeverAMetricLabel() throws Exception {
        var monitor = new ExecutionMonitor();
        var bridge = new TelemetryBridge(openTelemetry);
        try (var unsubscribe = monitor.subscribe(bridge);
             var engine = new SameThreadEngine();
             var runner = new GraphRunner(linearGraph("explode"), engine,
                     new BehaviorRegistry().register("explode", TelemetryEndToEndTest::explode), monitor)) {
            var security = new SecurityContext("request-1", "tenant-a", "alice", PrincipalType.USER,
                    "urn:ravenroot:test");
            assertThrows(Exception.class, () -> runner.execute(security, Map.of("ok", true))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            bridge.close();
        }

        boolean sentinelOnASpan = spans.getFinishedSpanItems().stream()
                .anyMatch(span -> String.valueOf(span.getAttributes()
                        .get(AttributeKey.stringKey("ravenroot.detail"))).contains(FAILURE_SENTINEL));
        assertTrue(sentinelOnASpan, "control failed: the sentinel never reached any span, so the metric "
                + "assertion below proves nothing");

        for (MetricData metric : metrics.collectAllMetrics()) {
            for (PointData point : metric.getData().getPoints()) {
                assertFalse(point.getAttributes().toString().contains(FAILURE_SENTINEL),
                        () -> "metric '" + metric.getName() + "' carries the raw exception message");
            }
        }
    }

    /**
     * The span-ended half of the cross-module cancellation proof (the admission-slot and audit-row
     * halves are {@code CancellationConsistencyIntegrationTest} in {@code ravenroot-server}, the only
     * module that can see both {@code ActiveExecutionRegistry} and {@code AuditTrailExecutionSink}).
     * A real {@link GraphRunner} traversal, cancelled from inside its own dispatched node -- not a
     * synthetic {@code ExecutionEvent} handed straight to the bridge, which
     * {@code TelemetryBridgeSpanAndMetricTest} already covers and which cannot prove the runner
     * actually reaches {@code EXECUTION_CANCELLED} for a real cancellation the way this does.
     *
     * <p><b>Why the cancel call sits inside the node's own behavior.</b>
     * {@code GraphRunner.cancelTraversal} does not preempt a dispatch already in flight -- it refuses
     * the traversal's <em>next</em> hop. {@link SameThreadEngine} dispatches synchronously with no
     * concurrency to race, so calling {@code cancelTraversal} from a separate thread while
     * {@code execute()} is running would never overlap a dispatch at all. Calling it from inside
     * {@code node}'s own behavior, synchronously, before that behavior returns its own (otherwise
     * ordinary) {@code continue} outcome, is what puts a real refusal on the very next hop -- the
     * edge to {@code end} -- reproducing {@code RunawayLoopCancellationTest}'s model on the simplest
     * graph that can show it, with no loop and no join-policy semantics needed.</p>
     */
    @Test
    void aRealCancellationEndsTheTraversalSpanThroughARealGraphRunner() throws Exception {
        var monitor = new ExecutionMonitor();
        var bridge = new TelemetryBridge(openTelemetry);
        UUID traversalId = UUID.randomUUID();
        // Boxed so the behavior closure below can call back into the runner that will hold it --
        // the runner does not exist yet when the behavior is registered.
        var runnerBox = new GraphRunner[1];

        try (var unsubscribe = monitor.subscribe(bridge);
             var engine = new SameThreadEngine();
             var runner = new GraphRunner(linearGraph("node"), engine,
                     new BehaviorRegistry().register("node", message -> {
                         runnerBox[0].cancelTraversal(traversalId);
                         return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                     }), monitor)) {
            runnerBox[0] = runner;
            var security = new SecurityContext("request-1", "tenant-a", "alice", PrincipalType.USER,
                    "urn:ravenroot:test");
            assertThrows(Exception.class, () -> runner.execute(security, traversalId, Map.of("ok", true),
                            "embedded")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS),
                    "a traversal refused at its next hop must not resolve as a success");
        } finally {
            bridge.close();
        }

        List<SpanData> traversalSpans = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("ravenroot.execution")).toList();
        assertEquals(1, traversalSpans.size(),
                "regression: a cancelled traversal's span must be ended, not leaked open forever -- "
                        + "TelemetryBridge previously matched only EXECUTION_COMPLETED/EXECUTION_FAILED "
                        + "and left EXECUTION_CANCELLED's span open");
        assertEquals(io.opentelemetry.api.trace.StatusCode.UNSET,
                traversalSpans.get(0).getStatus().getStatusCode(),
                "a cancellation is neither the success EXECUTION_COMPLETED reports nor the error "
                        + "EXECUTION_FAILED reports");
    }

    private static GraphManager linearGraph(String middleNodeBehavior) {
        return GraphManager.from(new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("middle", middleNodeBehavior),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "middle"), GraphEdge.to("middle", "end"))));
    }

    private static CompletionStage<NodeResult> echo(NodeMessage message) {
        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }

    private static CompletionStage<NodeResult> explode(NodeMessage message) {
        return CompletableFuture.failedFuture(new RuntimeException(FAILURE_SENTINEL));
    }

    /** Minimal same-thread {@link ExecutionEngine}: enough to run a linear, non-joining graph
     * synchronously. Not a conformance adapter -- {@code ExecutionEngineContract} in
     * ravenroot-engine-testkit owns that; this exists only to make one traversal happen. */
    private static final class SameThreadEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile EngineState state = EngineState.RUNNING;

        @Override
        public String id() {
            return "same-thread-test-engine";
        }

        @Override
        public java.util.Set<EngineCapability> capabilities() {
            return java.util.Set.of();
        }

        @Override
        public Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public EngineState state() {
            return state;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            if (node == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown node"));
            }
            try {
                return node.onMessage(message, context(target));
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.ofNullable(nodes.get(target))
                    .map(ignored -> new NodeStatus(target, ai.ravenroot.api.execution.NodeLifecycleState.RUNNING,
                            null, 0));
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            state = EngineState.DRAINING;
            nodes.clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            state = EngineState.CLOSED;
            nodes.clear();
        }

        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override
                public NodeRef self() {
                    return ref;
                }

                @Override
                public Scheduler scheduler() {
                    return SameThreadEngine.this.scheduler();
                }

                @Override
                public Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public CancellationSignal cancellation() {
                    return new CancellationSignal() {
                        @Override
                        public boolean cancelled() {
                            return false;
                        }

                        @Override
                        public void onCancel(Runnable listener) {
                        }
                    };
                }
            };
        }
    }
}
