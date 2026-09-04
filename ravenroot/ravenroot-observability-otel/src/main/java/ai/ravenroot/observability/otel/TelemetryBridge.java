package ai.ravenroot.observability.otel;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Translates {@link ExecutionEvent}s into OpenTelemetry spans, metrics and log-correlatable
 * identifiers (PLAT-01). The only thing this class reads is the event itself: it never reaches
 * into a payload, a {@code Throwable}, or anything upstream of {@link ExecutionEvent} construction.
 * That is the boundary this class owns, and its only obligation is not to widen what it exposes,
 * which {@link TelemetryBridgeRedactionTest} checks directly. The broader "logging and
 * telemetry privacy-safe" sweep (SEC-14) covers everything outside this translation.
 *
 * <h2>{@code detail} is not sanitized, and this class used to say it was</h2>
 * <p>This Javadoc previously claimed {@code event.detail()} was "already guaranteed free of secret
 * material by the boundary {@code MailExecutionEventSanitizationTest} proves in
 * {@code ravenroot-core}". That was wrong twice. The test lives in
 * {@code ravenroot-extensions/ravenroot-mail}, not in core; and what it proves is that the
 * <em>mail behavior</em> scrubs secrets from its own exception messages before they propagate. That
 * is a per-behavior boundary, not a global one. No other behavior inherits it, and core performs no
 * redaction of its own, so {@code detail} arrives here carrying the deepest cause's message verbatim
 * &mdash; which routinely quotes the value that caused the failure &mdash; plus graph-authored
 * outcome text. {@link ExecutionEvent#MAX_DETAIL_LENGTH} bounds its length and nothing else.
 *
 * <p>The span attribute below therefore carries an unredacted operator-facing diagnostic, and that
 * is an argued choice rather than an oversight. A span exporter is an operator surface, the same
 * class of consumer as the structured log line and {@code /v1/events}, both of which already carry
 * {@code detail} verbatim by existing contract. Dropping it here would remove the operator's primary
 * diagnostic from traces while leaving the identical value exposed on two other paths &mdash; no
 * confidentiality bought, and "why did this node fail" no longer answerable from a trace. The
 * property is now stated truthfully, so anyone pointing this bridge at a
 * third-party collector decides with the real contract in front of them.
 * <strong>Do not cite this class as evidence that {@code detail} is safe to forward.</strong>
 *
 * <h2>Cardinality</h2>
 * <p>Two kinds of identifier appear on an {@link ExecutionEvent}, and they are never treated the
 * same way here. {@code processInstanceId}, {@code traversalId}, {@code invocationId},
 * {@code attemptId} are unbounded by construction (fresh UUIDs); {@code tenantId} is unbounded
 * across a shared-tenant deployment; {@code nodeId} is a graph-author-chosen free string with no
 * catalog bound &mdash; the one "a reasonable engineer would add without thinking", because it looks
 * like a small, closed set inside any one graph. {@code deploymentId} (ADR 0021 D5) is
 * unbounded across the life of a pod &mdash; one series per deployment ever activated &mdash; and
 * {@code workloadId} is unbounded by construction exactly like {@code traversalId}, which for Phase A
 * it is. <strong>All seven are span attributes only.</strong>
 * Every metric instrument this class registers is labeled from a fixed allowlist of bounded
 * dimensions &mdash; today, only {@link ExecutionEventType} itself, a 10-value enum, and the
 * catalog-bounded node type &mdash; and nothing else. {@link CardinalityAllowlistTest} enumerates
 * every metric this class can produce and fails if any recorded attribute key is outside that
 * allowlist; {@code nodeId} is the specific mutation that test is built to catch, per its own
 * Javadoc, and it now does the same for {@code deploymentId}/{@code workloadId}.</p>
 *
 * <h2>What is not covered here</h2>
 * <p>Active-instance concurrency is not broken down by node type. The node <em>duration</em> metric
 * is: {@link ExecutionEvent} carries {@code nodeCatalogKey} alongside {@code nodeId},
 * and the key is resolved through the {@code BehaviorRegistry}, so its value domain is exactly the
 * installed catalog rather than graph-author text. The gauge is a different case and stays
 * undifferentiated: it is an async callback over live span state, not a per-event record, so a
 * node-type breakdown would need per-type live counters this class does not keep. Retry-count and
 * provider-usage instrumentation are deferred because neither has a data source yet.</p>
 *
 * <h2>For PLAT-02</h2>
 * <p>This module exposes an active-executions gauge ({@code ravenroot.executions.active}, one series,
 * backed by the open-traversal-span count); node/execution/join failure counters, all as one
 * instrument sliced by the bounded {@code ravenroot.event_type} label rather than three separate
 * ones ({@code ravenroot.execution.events} with {@code ravenroot.event_type in
 * (NODE_FAILED, EXECUTION_FAILED, JOIN_FAILED)} is a failure-rate counter for each, queryable
 * without any additional instrumentation); and a join-wait histogram
 * ({@code ravenroot.join.wait}) for latency-style alerting on fan-in joins specifically.</p>
 */
final class TelemetryBridge implements Consumer<ExecutionEvent>, AutoCloseable {

    static final String INSTRUMENTATION_NAME = "ai.ravenroot.observability.otel";

    private static final AttributeKey<String> ATTR_TENANT_ID = AttributeKey.stringKey("ravenroot.tenant_id");
    private static final AttributeKey<String> ATTR_REQUEST_ID = AttributeKey.stringKey("ravenroot.request_id");
    private static final AttributeKey<String> ATTR_ENGINE_ID = AttributeKey.stringKey("ravenroot.engine_id");
    private static final AttributeKey<String> ATTR_GRAPH_VERSION =
            AttributeKey.stringKey("ravenroot.graph_version");
    private static final AttributeKey<String> ATTR_PROCESS_INSTANCE_ID =
            AttributeKey.stringKey("ravenroot.process_instance_id");
    private static final AttributeKey<String> ATTR_TRAVERSAL_ID = AttributeKey.stringKey("ravenroot.traversal_id");
    private static final AttributeKey<String> ATTR_INVOCATION_ID =
            AttributeKey.stringKey("ravenroot.invocation_id");
    private static final AttributeKey<String> ATTR_ATTEMPT_ID = AttributeKey.stringKey("ravenroot.attempt_id");
    private static final AttributeKey<String> ATTR_NODE_ID = AttributeKey.stringKey("ravenroot.node_id");
    private static final AttributeKey<Boolean> ATTR_FALLBACK = AttributeKey.booleanKey("ravenroot.fallback");
    private static final AttributeKey<String> ATTR_DETAIL = AttributeKey.stringKey("ravenroot.detail");
    /**
     * ADR 0021 D5, the identity half: {@code deploymentId} and
     * {@code workloadId} join {@code traversalId}/{@code invocationId}/{@code attemptId} as span
     * attributes ONLY -- both are unbounded (one series per deployment ever activated, and per
     * traversal), which is precisely the reason the aggregation half of D5 exists on a completely
     * different dimension ({@link #METRIC_ATTR_NODE_TYPE}, bounded by the catalog). Neither constant
     * below is added to {@link #METRIC_LABEL_ALLOWLIST}, and neither reaches an
     * {@code Attributes.of(...)} call passed to a metric instrument anywhere in this class --
     * {@link ai.ravenroot.observability.otel.CardinalityAllowlistTest} guards that from outside, the
     * same way it already guards {@code nodeId} and {@code tenantId}.
     */
    private static final AttributeKey<String> ATTR_DEPLOYMENT_ID = AttributeKey.stringKey("ravenroot.deployment_id");
    private static final AttributeKey<String> ATTR_WORKLOAD_ID = AttributeKey.stringKey("ravenroot.workload_id");

    /**
     * The metric cardinality allowlist (PLAT-01). Every attribute key this class ever passes
     * to a metric instrument's {@code record(...)} call must be a member of this set &mdash; span
     * attributes are exempt, since they are not pre-aggregated into a time series the way a metric
     * label is. {@link CardinalityAllowlistTest} enforces this from outside the class, by exercising
     * every metric with many distinct {@code nodeId}/{@code tenantId} values and asserting the
     * resulting series count stays bounded by this set's own combinatorics.
     */
    static final AttributeKey<String> METRIC_ATTR_EVENT_TYPE = AttributeKey.stringKey("ravenroot.event_type");

    /**
     * The node's catalog key (ADR 0021 D5) — the one label-safe node dimension.
     *
     * <p>Bounded by the installed catalog, not by traffic: the value is
     * {@code NodeTypeDescriptor.behavior()} resolved through the {@code BehaviorRegistry}, so an
     * unregistered behavior name — which is graph-author text and unbounded — never reaches here and
     * its events carry no key at all. That resolution happens in {@code GraphRunner}, and it is what
     * makes this entry admissible where {@code ravenroot.node_id} is not.
     *
     * <p>"Bounded" means <em>does not grow with traffic</em>, not <em>fixed at build time</em>: a
     * deployment that installs more node packages has a larger catalog, and one series per installed
     * node type is the intended cost.
     */
    static final AttributeKey<String> METRIC_ATTR_NODE_TYPE = AttributeKey.stringKey("ravenroot.node_type");

    /**
     * How a retried attempt's failure was classified &mdash; the {@code Retryability} vocabulary,
     * lower-cased and hyphenated as the event already carries it.
     *
     * <p>Admissible for the same reason {@link #METRIC_ATTR_NODE_TYPE} is: the value domain is a
     * four-member enum fixed in source, so it does not grow with traffic, with a deployment's
     * installed packages, or with anything a graph author writes. It is the label that makes the
     * retry counter answer the question an operator actually has &mdash; "are we retrying because
     * calls are timing out, or because a store keeps telling us to re-read" &mdash; which a bare
     * count cannot.</p>
     */
    static final AttributeKey<String> METRIC_ATTR_RETRY_CLASSIFICATION =
            AttributeKey.stringKey("ravenroot.retry_classification");

    static final Set<AttributeKey<?>> METRIC_LABEL_ALLOWLIST =
            Set.of(METRIC_ATTR_EVENT_TYPE, METRIC_ATTR_NODE_TYPE, METRIC_ATTR_RETRY_CLASSIFICATION);

    private final Tracer tracer;
    private final LongCounter eventCounter;
    private final LongCounter orchestrationRetries;
    private final LongCounter connectorRetries;
    private final DoubleHistogram nodeDuration;
    private final DoubleHistogram executionDuration;
    private final DoubleHistogram joinWait;

    private final Map<UUID, PendingSpan> traversalSpans = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSpan> nodeSpans = new ConcurrentHashMap<>();

    TelemetryBridge(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.eventCounter = meter.counterBuilder("ravenroot.execution.events")
                .setDescription("Count of ExecutionEvents by type. Bounded: labeled only by "
                        + "ravenroot.event_type, a fixed enum.")
                .build();
        this.orchestrationRetries = meter.counterBuilder("ravenroot.node.retries")
                .setDescription("Count of orchestration-level node retries: one per further durable "
                        + "attempt the retry policy committed. Bounded: labeled by "
                        + "ravenroot.node_type and ravenroot.retry_classification, both fixed value "
                        + "domains. This counts retries the ORCHESTRATOR made -- retries a connector "
                        + "performed inside one attempt are ravenroot.node.connector_retries.")
                .build();
        this.connectorRetries = meter.counterBuilder("ravenroot.node.connector_retries")
                .setDescription("Count of retries a connector performed INSIDE one orchestration "
                        + "attempt, as reported by the node itself. Never inferred: a node that "
                        + "reports nothing contributes nothing, which is distinct from reporting a "
                        + "single attempt. Bounded: labeled only by ravenroot.node_type.")
                .build();
        this.nodeDuration = meter.histogramBuilder("ravenroot.node.duration")
                .setDescription("Node invocation duration, start to terminal outcome (completed or "
                        + "bypassed or failed). Bounded: labeled only by ravenroot.event_type.")
                .setUnit("s")
                .build();
        this.executionDuration = meter.histogramBuilder("ravenroot.execution.duration")
                .setDescription("Traversal duration, start to terminal outcome. Bounded: labeled "
                        + "only by ravenroot.event_type.")
                .setUnit("s")
                .build();
        this.joinWait = meter.histogramBuilder("ravenroot.join.wait")
                .setDescription("Fan-in join wait: elapsed time between the join opening and "
                        + "settling (PLAT-01). Absent join_wait_duration is never "
                        + "recorded as zero -- see ExecutionEvent.joinWaitDuration's own Javadoc. "
                        + "Bounded: labeled only by ravenroot.event_type.")
                .setUnit("s")
                .build();
        // A single, zero-label series: the count of currently-open traversal spans IS the active
        // execution count, so this reuses traversalSpans rather than tracking a second counter
        // that could drift from it.
        meter.gaugeBuilder("ravenroot.executions.active")
                .ofLongs()
                .setDescription("Currently active (started, not yet terminal) process traversals. "
                        + "One series: this is a deliberately coarse, execution-level count, not a "
                        + "per-node-type breakdown -- see this class's own Javadoc, \"What is not "
                        + "covered here\".")
                .buildWithCallback(measurement -> measurement.record(traversalSpans.size()));
    }

    @Override
    public void accept(ExecutionEvent event) {
        eventCounter.add(1, Attributes.of(METRIC_ATTR_EVENT_TYPE, event.type().name()));
        switch (event.type()) {
            case EXECUTION_STARTED -> startTraversal(event);
            case EXECUTION_COMPLETED, EXECUTION_FAILED -> endTraversal(event);
            case NODE_STARTED -> startNode(event);
            case NODE_COMPLETED, NODE_BYPASSED, NODE_FAILED -> {
                endNode(event);
                recordConnectorRetries(event);
            }
            // Ends the failed attempt's span, and it must: the retry runs under a NEW attempt id and
            // opens a span of its own, so leaving this one open would leak an entry in nodeSpans for
            // every retry and never close a trace an operator is reading. The retry's own span then
            // hangs off the same traversal, which is what makes a retry chain visible as a chain.
            case NODE_RETRY_SCHEDULED -> {
                endNode(event);
                recordConnectorRetries(event);
                orchestrationRetries.add(1, retryAttributes(event));
            }
            case NODE_DEFAULTED -> annotateNode(event, "ravenroot.node.defaulted");
            // Annotations on the traversal span rather than a start/end pair of their own. A hold is
            // not a unit of work with a duration this bridge can close: it lasts until an operator
            // ends it, which may be never, and a span left open for an unbounded human interval is
            // one the exporter eventually drops or reports as an error. The traversal span already
            // spans the hold, so marking its start and its release on that span is what makes a gap
            // in the node timeline explainable -- which is the whole reason a reader looks.
            case EXECUTION_PAUSED -> annotateTraversal(event, "ravenroot.execution.paused");
            case EXECUTION_RESUMED -> annotateTraversal(event, "ravenroot.execution.resumed");
            case JOIN_SATISFIED -> annotateJoin(event, "ravenroot.join.satisfied", StatusCode.UNSET);
            case JOIN_FAILED -> annotateJoin(event, "ravenroot.join.failed", StatusCode.ERROR);
            case JOIN_ARRIVAL_DISCARDED -> annotateJoin(event, "ravenroot.join.arrival_discarded", StatusCode.UNSET);
            // UNSET, like the other two non-failures: an iteration backlog is a fact about how fast a
            // cycle is producing relative to how fast its join is satisfied, not an error with which
            // the span should be marked.
            case JOIN_ITERATION_BACKLOG -> annotateJoin(event, "ravenroot.join.iteration_backlog", StatusCode.UNSET);
        }
    }

    private void startTraversal(ExecutionEvent event) {
        Span span = tracer.spanBuilder("ravenroot.execution")
                .setStartTimestamp(event.occurredAt())
                .setAllAttributes(traversalAttributes(event))
                .startSpan();
        traversalSpans.put(event.traversalId(), new PendingSpan(span, event.occurredAt()));
    }

    private void endTraversal(ExecutionEvent event) {
        PendingSpan pending = traversalSpans.remove(event.traversalId());
        if (pending == null) {
            // No matching start -- the bridge was installed mid-traversal, or was restarted. Not a
            // defect in this class: nothing to end, nothing to measure a duration against.
            return;
        }
        Span span = pending.span();
        span.setAttribute(ATTR_DETAIL, event.detail());
        span.setStatus(event.type() == ExecutionEventType.EXECUTION_FAILED ? StatusCode.ERROR : StatusCode.OK);
        span.end(event.occurredAt());
        executionDuration.record(secondsBetween(pending.startedAt(), event.occurredAt()),
                Attributes.of(METRIC_ATTR_EVENT_TYPE, event.type().name()));
    }

    private void startNode(ExecutionEvent event) {
        Span span = tracer.spanBuilder("ravenroot.node")
                .setParent(parentContext(event))
                .setStartTimestamp(event.occurredAt())
                .setAllAttributes(nodeAttributes(event))
                .startSpan();
        nodeSpans.put(event.attemptId(), new PendingSpan(span, event.occurredAt()));
    }

    private void endNode(ExecutionEvent event) {
        PendingSpan pending = nodeSpans.remove(event.attemptId());
        if (pending == null) {
            return;
        }
        Span span = pending.span();
        span.setAttribute(ATTR_DETAIL, event.detail());
        span.setAttribute(ATTR_FALLBACK, event.fallback());
        // A retried attempt's span is ERROR alongside a terminal failure, because the attempt did
        // fail -- what the retry changes is what happens next, not what happened. A trace showing the
        // first two attempts as OK because they were eventually recovered from would hide precisely
        // the latency and the failure an operator opened the trace to find.
        span.setStatus(event.type() == ExecutionEventType.NODE_FAILED
                || event.type() == ExecutionEventType.NODE_RETRY_SCHEDULED
                ? StatusCode.ERROR : StatusCode.OK);
        span.end(event.occurredAt());
        // The node-type dimension. Absent stays absent -- a structural node or an
        // unregistered behavior records without the label rather than under a placeholder, so the
        // series set is exactly the installed catalog and nothing that merely looks like it.
        nodeDuration.record(secondsBetween(pending.startedAt(), event.occurredAt()),
                event.nodeCatalogKey() == null
                        ? Attributes.of(METRIC_ATTR_EVENT_TYPE, event.type().name())
                        : Attributes.of(METRIC_ATTR_EVENT_TYPE, event.type().name(),
                                METRIC_ATTR_NODE_TYPE, event.nodeCatalogKey()));
    }

    /**
     * The label set for one orchestration retry: its classification always, its node type when the
     * catalog resolved one.
     *
     * <p>Absent stays absent, exactly as {@link #endNode} treats it: a structural node or an
     * unregistered behavior records without the node-type label rather than under a placeholder, so
     * the series set stays the installed catalog and nothing that merely looks like it.</p>
     */
    private static Attributes retryAttributes(ExecutionEvent event) {
        String classification = event.publicReason() == null ? "unclassified" : event.publicReason();
        return event.nodeCatalogKey() == null
                ? Attributes.of(METRIC_ATTR_RETRY_CLASSIFICATION, classification)
                : Attributes.of(METRIC_ATTR_RETRY_CLASSIFICATION, classification,
                        METRIC_ATTR_NODE_TYPE, event.nodeCatalogKey());
    }

    /**
     * Records the retries a connector performed inside this one attempt, when it reported any.
     *
     * <p>Counts {@code connectorAttempts - 1}, which is retries rather than attempts, because that is
     * what the counter's name promises and what adds meaningfully across attempts. Nothing is recorded
     * for a report of one, and nothing for
     * {@link ai.ravenroot.api.execution.ConnectorRetryReport#NOT_REPORTED} &mdash; and those two must
     * not be conflated into a zero: a connector that attempted once and a node that said nothing both
     * add zero to a counter, but only the first is a measurement, and a deployment reading this
     * metric has to be able to tell whether its connectors are instrumented at all. The distinction it
     * needs for that lives on the event, not here.</p>
     */
    private void recordConnectorRetries(ExecutionEvent event) {
        int attempts = event.connectorAttempts();
        if (attempts <= 1) {
            return;
        }
        connectorRetries.add(attempts - 1L, event.nodeCatalogKey() == null
                ? Attributes.empty()
                : Attributes.of(METRIC_ATTR_NODE_TYPE, event.nodeCatalogKey()));
    }

    /** NODE_DEFAULTED always precedes NODE_COMPLETED for the same attempt (ExecutionMonitor's own
     * contract) -- it augments the still-open node span rather than closing anything. */
    private void annotateNode(ExecutionEvent event, String eventName) {
        PendingSpan pending = nodeSpans.get(event.attemptId());
        if (pending == null) {
            return;
        }
        pending.span().addEvent(eventName, Attributes.of(ATTR_DETAIL, event.detail()), event.occurredAt());
    }

    /** Joins have no invocation of their own (CORE-03): recorded as an event on the traversal span,
     * plus a bounded-cardinality duration where one is present. */
    /**
     * Records a traversal-level transition on the traversal's own span, if one is open.
     *
     * <p>No node id and no status write: the events this serves carry neither a node nor a failure,
     * and a pause is not an error condition — it is somebody deliberately holding their own work.
     * Marking the span {@code ERROR} for it would put every paused execution into an error dashboard
     * and teach its readers to ignore the signal.</p>
     *
     * <p>A missing traversal span is silently tolerated, exactly as it is for joins: the bridge may
     * have been attached mid-run, and a pause on a traversal whose start it never saw is not a
     * defect in either.</p>
     */
    private void annotateTraversal(ExecutionEvent event, String eventName) {
        PendingSpan traversal = traversalSpans.get(event.traversalId());
        if (traversal != null) {
            traversal.span().addEvent(eventName, Attributes.of(ATTR_DETAIL, event.detail()),
                    event.occurredAt());
        }
    }

    private void annotateJoin(ExecutionEvent event, String eventName, StatusCode statusIfTraversalKnown) {
        AttributesBuilder attributes = Attributes.builder()
                .put(ATTR_NODE_ID, event.nodeId())
                .put(ATTR_DETAIL, event.detail());
        PendingSpan traversal = traversalSpans.get(event.traversalId());
        if (traversal != null) {
            traversal.span().addEvent(eventName, attributes.build(), event.occurredAt());
            if (statusIfTraversalKnown == StatusCode.ERROR) {
                // A join failure does not end the traversal (a later EXECUTION_FAILED does, with its
                // own status write) -- this only records that a failure event was seen, in case the
                // traversal's own terminal event is lost.
                traversal.span().addEvent("ravenroot.join.failed.seen", event.occurredAt());
            }
        }
        // joinWaitDuration is null on every non-settlement event by construction (JOIN_ARRIVAL_DISCARDED
        // included) and required non-null on the two that settle a join (ExecutionMonitor enforces this
        // with Objects.requireNonNull before the event is ever published) -- so this is "record when
        // present", never "default the absence to zero".
        if (event.joinWaitDuration() != null) {
            joinWait.record(event.joinWaitDuration().toNanos() / 1_000_000_000.0,
                    Attributes.of(METRIC_ATTR_EVENT_TYPE, event.type().name()));
        }
    }

    /**
     * The traversal span as parent, when the bridge has one open for this event's traversal.
     * Falls back to a root span when it does not (the bridge was installed mid-traversal, or the
     * traversal's own EXECUTION_STARTED was lost) -- a node span with no parent is still a valid,
     * useful span; a {@link NullPointerException} here would not be.
     */
    private Context parentContext(ExecutionEvent event) {
        PendingSpan traversal = traversalSpans.get(event.traversalId());
        return traversal == null ? Context.root() : Context.root().with(traversal.span());
    }

    private Attributes traversalAttributes(ExecutionEvent event) {
        return Attributes.builder()
                .put(ATTR_TENANT_ID, event.tenantId())
                .put(ATTR_REQUEST_ID, event.requestId())
                .put(ATTR_ENGINE_ID, event.engineId())
                .put(ATTR_GRAPH_VERSION, event.graphVersion())
                .put(ATTR_PROCESS_INSTANCE_ID, event.processInstanceId().toString())
                .put(ATTR_TRAVERSAL_ID, event.traversalId().toString())
                // ADR 0021 D5. Span-only, like every identifier above it;
                // absent (a one-shot/playground traversal) is an empty attribute value, matching how
                // ATTR_INVOCATION_ID/ATTR_ATTEMPT_ID already represent "not applicable here" below.
                .put(ATTR_DEPLOYMENT_ID, event.deploymentId() == null ? "" : event.deploymentId())
                .put(ATTR_WORKLOAD_ID, event.workloadId() == null ? "" : event.workloadId())
                .build();
    }

    private Attributes nodeAttributes(ExecutionEvent event) {
        AttributesBuilder attributes = Attributes.builder()
                .put(ATTR_TENANT_ID, event.tenantId())
                .put(ATTR_REQUEST_ID, event.requestId())
                .put(ATTR_TRAVERSAL_ID, event.traversalId().toString())
                .put(ATTR_INVOCATION_ID, event.invocationId() == null ? "" : event.invocationId().toString())
                .put(ATTR_ATTEMPT_ID, event.attemptId() == null ? "" : event.attemptId().toString())
                .put(ATTR_NODE_ID, event.nodeId() == null ? "" : event.nodeId())
                .put(ATTR_DEPLOYMENT_ID, event.deploymentId() == null ? "" : event.deploymentId())
                .put(ATTR_WORKLOAD_ID, event.workloadId() == null ? "" : event.workloadId());
        return attributes.build();
    }

    private static double secondsBetween(Instant start, Instant end) {
        return Duration.between(start, end).toNanos() / 1_000_000_000.0;
    }

    /**
     * Ends every span still open when the bridge is closed, so an operator sees "unterminated by
     * shutdown" rather than a span that silently never reaches an exporter. Not a durability
     * guarantee: a process crash (as opposed to an orderly close) loses these the same way
     * {@code ExecutionMonitor.activeExecutions} loses its own count on a crash -- this class
     * introduces no new risk relative to what {@code ExecutionMonitor} already accepts.
     */
    @Override
    public void close() {
        endAllWithStatus(traversalSpans, "ravenroot bridge closed with the traversal unterminated");
        endAllWithStatus(nodeSpans, "ravenroot bridge closed with the node invocation unterminated");
    }

    private static void endAllWithStatus(Map<UUID, PendingSpan> spans, String description) {
        List<PendingSpan> remaining = List.copyOf(spans.values());
        spans.clear();
        Instant now = Instant.now();
        for (PendingSpan pending : remaining) {
            pending.span().setStatus(StatusCode.ERROR, description);
            pending.span().end(now);
        }
    }

    private record PendingSpan(Span span, Instant startedAt) {
    }
}
