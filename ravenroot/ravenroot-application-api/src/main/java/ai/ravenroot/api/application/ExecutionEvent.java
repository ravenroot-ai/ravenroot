package ai.ravenroot.api.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import ai.ravenroot.api.application.RuntimeActivityData.OutputProjection;
import ai.ravenroot.api.application.RuntimeActivityData.TextProjection;

/**
 * Immutable event that can be serialized by any external adapter.
 *
 * <p>{@code tenantId} and {@code requestId} carry the SEC-07 identity across the event boundary: an
 * event is evidence about work done on somebody's behalf, and an event that cannot say whose work it
 * was cannot be correlated with the authorization decision that permitted it. {@code requestId} is
 * the same value that appears on {@link ai.ravenroot.api.security.AuthorizationAuditEvent} and
 * {@link ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent}, which is what makes an end-to-end
 * audit trace joinable without a separate correlation store.</p>
 *
 * <p>Neither field is a filtering authority. {@link AuthorizedRavenrootApplication} decides
 * observability from its own ownership record, which lives inside the reference monitor, rather than
 * from a field on an event produced by the delegate it wraps. These fields are evidence, not access
 * control.</p>
 *
 * @param activeInstances <strong>how many runtime instances of this node's actor are alive right now</strong>
 * — the workload the node is carrying <em>as a role</em>, which is the
 * question the elastic view exists to answer. For the default {@code WORKER}
 * nature this is the number of live worker instances, so ten concurrent
 * invocations read 10. For a nature that is resident by contract it is
 * <strong>always 1</strong> however much traffic crosses the node, because
 * {@code GraphRunner} spawns exactly one actor for it at construction and every
 * arrival shares that one — a singleton passed through a thousand times is still
 * one instance.
 * <p>Previously this component carried {@link #inFlightArrivals} instead, and the
 * elastic view rendered that as "N active". The two happen to hold equal values
 * for a {@code WORKER} node, which is why the mislabelling went unnoticed; they
 * disagree for every resident node under concurrent load, which is what makes
 * them different quantities rather than two names for one. <b>Meaningful only on
 * node-scoped events</b>; traversal-level and join events carry 0, the same
 * convention this component has always followed.</p>
 * @param inFlightArrivals <strong>how many arrivals at this node have started and not yet finished</strong>
 * — the queue depth, counted by {@link ExecutionEventType#NODE_STARTED}
 * and uncounted by each terminal node event. This is the "count of passages"
 * the documented contract asked to keep as <em>a different number</em>, and it is
 * deliberately named so it cannot be read as the instance count: for a resident
 * node ten arrivals waiting on one actor read 10 here and 1 in
 * {@code activeInstances}, and reporting either under the other's name is the
 * conflation the separate fields prevent. Same node-scoped convention: 0 where there is no node.
 * @param detail a diagnostic string for the person reading their own run. <strong>It is bounded and
 * nothing more</strong>. On {@link ExecutionEventType#EXECUTION_FAILED} and
 * {@link ExecutionEventType#NODE_FAILED} it is the deepest cause's
 * {@code getMessage()} verbatim, and an exception message routinely quotes the value
 * that caused it, so it may carry payload fragments. On
 * {@link ExecutionEventType#NODE_COMPLETED} it is {@code "outcome=" + outcome}, where
 * the outcome comes from graph-authored node properties, so part of the string is
 * chosen by whoever wrote the graph. There is no redaction anywhere in core, and none
 * is possible here: by this point the value is flat text, and no lexical rule separates
 * a quoted payload from a legitimate diagnostic. Redaction is only achievable at the
 * site that holds the secret and knows which substring it is — see
 * {@code MailExecutionEventSanitizationTest}, which proves that boundary for the mail
 * extension and for nothing else.
 * <p>What this means for a consumer: this is a trusted in-process/server diagnostic,
 * not browser copy. HTTP adapters never copy this field; authenticated diagnostics use
 * the separate already-safe {@code authorMessage} projection. A log aggregator, alerting webhook, support bundle
 * or model provider that consumes this field still inherits an unredacted channel.
 * {@link #MAX_DETAIL_LENGTH} is the only property enforced here, because a length can be
 * enforced without understanding the text; treat it as a bound on damage, never as a
 * sanitization.</p>
 * @param joinWaitDuration how long the fan-in join waited between opening and settling — the elapsed
 * time between {@code JoinRecord.openedAt()} and {@code JoinRecord.settledAt()}
 *. Present only on {@link ExecutionEventType#JOIN_SATISFIED} and
 * {@link ExecutionEventType#JOIN_FAILED} events; {@code null} on every other
 * event type, including {@link ExecutionEventType#JOIN_ARRIVAL_DISCARDED}. A
 * consumer must treat {@code null} as <strong>not measured</strong>, never as
 * zero: a metric or histogram that silently records zero for every non-join
 * event would be indistinguishable from a real population of instant joins,
 * wrong in the direction that looks healthy.
 * @param processingDuration monotonic elapsed processing time for one node attempt. Present only on
 * {@link ExecutionEventType#NODE_COMPLETED},
 * {@link ExecutionEventType#NODE_BYPASSED} and
 * {@link ExecutionEventType#NODE_FAILED}; {@code null} on every other event
 * and on an orphan terminal event whose matching start is no longer retained.
 * Never negative. A consumer must treat {@code null} as not measured, not zero.
 * @param deploymentId the owning long-lived deployment's identity (ADR 0021 D5), or
 * {@code null} for a one-shot/playground submission that never opened a
 * deployment domain. Identity channel only: logs and spans carry it; a metric
 * label never does (unbounded — one series per deployment ever activated).
 * @param workloadId the unit-of-work identity ADR 0021 D3's {@code (deploymentId, workloadId)}
 * sharding key names, or {@code null} outside a deployment. Constant for the whole
 * traversal it belongs to — every event that traversal produces carries the same
 * value, which is what makes it usable to correlate one item's activity back to the
 * graph regardless of which pod eventually serves it. Identity channel only, same
 * as {@code deploymentId}.
 * @param publicReason <strong>the one classifier of what happened that is safe to publish</strong>
 *. {@code detail} above is a trusted in-process diagnostic and cannot
 * cross an HTTP boundary; the consequence, until this component existed, was that
 * a public surface had the event type and nothing else, so every
 * {@link ExecutionEventType#NODE_COMPLETED} rendered as one fixed sentence
 * regardless of which outcome the node actually routed — and that sentence said
 * <em>successfully</em> for a node whose outcome was {@code failed}. This field
 * exists so a public surface can stop guessing.
 * <p><b>It is a classifier, never prose.</b> At most
 * {@link #MAX_PUBLIC_REASON_LENGTH} characters, and only letters, digits and
 * {@code . _ - :} — a value that does not conform becomes {@code null} rather
 * than being trimmed into conformance, because a mangled classifier is
 * indistinguishable from a real one. That character class is the whole safety
 * argument: an exception message, a payload fragment, or any sentence carrying
 * spaces or ordinary punctuation cannot survive it, so this component cannot
 * become a second {@code detail} through a later caller passing the wrong string.
 * Enforced in the compact constructor for the reason {@code detail}'s bound is:
 * the compatibility constructors below are the paths that would bypass it.</p>
 * <p>What each event type puts here: {@code NODE_COMPLETED} the routed outcome
 * ({@code continue} when the node authored none); {@code NODE_FAILED} and
 * {@code EXECUTION_FAILED} the deepest cause's <em>simple class name</em>, which
 * is a Java type name written in some source file and therefore cannot carry a
 * payload the way {@link Throwable#getMessage()} can; {@code JOIN_FAILED} the
 * join's own failure reason. {@code null} elsewhere — absent, not a placeholder,
 * for the reason {@code nodeCatalogKey} states.</p>
 * @param authorMessage normalized, targeted-redacted, UTF-8-bounded failure text for the authenticated
 * author diagnostics view; absent on non-failure events and never durable or eligible
 * for assistant/provider projection
 * @param authorOutput normalized, targeted-redacted, bounded output emitted by the trusted built-in
 * {@code log} action; separate from traversal/final payload and absent for every other node
 * @param edgeId stable authored or deterministic GraphML edge identity. Required only on
 * {@link ExecutionEventType#EDGE_TRAVERSED}; absent on every other event
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param fallback whether the node used its configured fallback behavior
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
 * @param attemptOrdinal the one-based ordinal of the attempt this event is about: {@code 1} an
 * initial attempt, greater than one an orchestration retry, {@code 0} an event above attempt scope.
 * This is the component that lets an event stream and a metric tell an initial attempt from a retry
 * without correlating two events, and it is the same ordinal the durable attempt history carries
 * @param connectorAttempts how many times a connector attempted the underlying operation inside this
 * one orchestration attempt, or {@link ai.ravenroot.api.execution.ConnectorRetryReport#NOT_REPORTED}
 * when the node reported nothing. Together with {@code attemptOrdinal} it separates the three things
 * that otherwise look alike: an initial attempt, a retry the orchestrator made, and retries a
 * connector made inside one attempt without producing a durable attempt at all
 */
public record ExecutionEvent(
        long sequence,
        Instant occurredAt,
        String tenantId,
        String requestId,
        String engineId,
        String graphVersion,
        UUID processInstanceId,
        UUID traversalId,
        UUID invocationId,
        UUID attemptId,
        ExecutionEventType type,
        String nodeId,
        int activeInstances,
        boolean fallback,
        String detail,
        Duration joinWaitDuration,
        Duration processingDuration,
        String nodeCatalogKey,
        String deploymentId,
        String workloadId,
        int inFlightArrivals,
        String publicReason,
        TextProjection authorMessage,
        OutputProjection authorOutput,
        String edgeId,
        int attemptOrdinal,
        int connectorAttempts) {

    /**
 * Bound on {@code detail}, marker included, so an event cannot become an unbounded text sink.
 *
 * <p>512 matches {@link NodeAttempt#MAX_PARK_CAUSE_LENGTH}, the other operator-facing diagnostic
 * cause in this package, rather than inventing a second number for the same kind of value.</p>
 */
    public static final int MAX_DETAIL_LENGTH = 512;

    /**
 * Bound on {@code publicReason}, deliberately far shorter than {@link #MAX_DETAIL_LENGTH}.
 *
 * <p>The two are not the same kind of value and must not share a number. {@code detail} is prose,
 * and 512 there is a bound on damage; this is a classifier, and 64 is the length past which a
 * token has stopped being one. A generous bound here would be an invitation to put a sentence
 * in it.</p>
 */
    public static final int MAX_PUBLIC_REASON_LENGTH = 64;

    /**
 * The outcome a node routes when its author declared none — the one value that means plain
 * success.
 *
 * <p>This is the same string as {@code GraphEdge.DEFAULT_OUTCOME} in core, and it is restated
 * here rather than imported because the dependency runs the other way: core depends on this
 * module, so this module cannot name a graph type. The duplication is therefore structural, not
 * an oversight — and it is <b>asserted equal by {@code ExecutionMonitorPublicReasonTest} in
 * core</b>, which can see both. A silent divergence would make every default-outcome completion
 * render as a named non-default outcome, which is a false statement of the same family this
 * component exists to end.</p>
 */
    public static final String DEFAULT_ROUTED_OUTCOME = "continue";

    /**
 * {@code publicReason} for a {@link ExecutionEventType#NODE_BYPASSED} the traversal imposed: an
 * inbound {@code command=passthrough}, or a whole submission running in play/test mode.
 *
 * <p>Published for the same reason as the routed outcome. {@link #detail()} carries
 * the human sentence and <strong>never crosses an HTTP boundary</strong>, so before this constant
 * a public surface saw {@code NODE_BYPASSED} and nothing else — and two different
 * facts share that type, "nothing else" stopped being merely thin and became ambiguous. A reader
 * could not tell "this whole run is a rehearsal" from "this one node is switched off in the
 * saved document", which are different things to act on.</p>
 */
    public static final String BYPASS_REASON_COMMAND = "command.passthrough";

    /**
 * {@code publicReason} for a {@link ExecutionEventType#NODE_BYPASSED} the graph's author wrote on
 * that one node, with {@code NodeBypassProperty}'s {@code execution.bypass}. See
 * {@link #BYPASS_REASON_COMMAND} for why this is published at all.
 */
    public static final String BYPASS_REASON_AUTHORED = "authored";

    /**
 * Appended to a {@code detail} that was cut, so a truncated string is never readable as a
 * complete message. Plain ASCII: this value is read in log lines and inside JSON, and a
 * single-character ellipsis is easy to miss in both and easy to mangle across encodings.
 *
 * <p>The text itself comes from {@code DiagnosticText}, shared with
 * {@link NodeAttempt#PARK_CAUSE_TRUNCATION_MARKER}, so the two diagnostics of this package cannot
 * drift into marking truncation differently. It remains a compile-time constant, so callers that
 * already inlined the literal are unaffected.</p>
 */
    public static final String DETAIL_TRUNCATION_MARKER = DiagnosticText.TRUNCATION_MARKER;

    /** Validates and normalizes every event component at the public boundary. */
public ExecutionEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId cannot be blank");
        }
        engineId = engineId == null ? "" : engineId;
        graphVersion = graphVersion == null ? "" : graphVersion;
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        // Bounded here rather than at the producer: ExecutionMonitor builds every event core
        // emits, but the six compatibility constructors below let an adapter build one without going
        // through it, and the compact constructor is the only point none of them can bypass.
        detail = bound(detail == null ? "" : detail);
        // Same placement argument as detail's bound directly above, for a different property:
        // detail is bounded here because the six compatibility constructors below cannot bypass the
        // compact one, and publicReason is character-checked here for exactly that reason. A
        // non-conforming value becomes null -- see the component's Javadoc on why this rejects
        // rather than repairs.
        publicReason = conformingPublicReason(publicReason);
        // Absent, never blank: this value becomes a metric label, and "" would be a series
        // that looks like a node type nobody can find in the catalog.
        nodeCatalogKey = nodeCatalogKey == null || nodeCatalogKey.isBlank() ? null : nodeCatalogKey;
        boolean failureEvent = type == ExecutionEventType.NODE_FAILED || type == ExecutionEventType.JOIN_FAILED
                || type == ExecutionEventType.EXECUTION_FAILED;
        if (!failureEvent) authorMessage = null;
        if (type != ExecutionEventType.NODE_COMPLETED || !"log".equals(nodeCatalogKey)) authorOutput = null;
        if (type == ExecutionEventType.EDGE_TRAVERSED) {
            edgeId = StableEdgeId.requireValid(edgeId);
        } else {
            edgeId = null;
        }
        // deploymentId/workloadId are never metric labels (see the field Javadoc), so the "blank
        // becomes null" discipline here is about honesty in logs/spans, not cardinality: absence must
        // stay absence rather than becoming a searchable-looking empty string.
        deploymentId = deploymentId == null || deploymentId.isBlank() ? null : deploymentId;
        workloadId = workloadId == null || workloadId.isBlank() ? null : workloadId;
        if (processingDuration != null) {
            if (processingDuration.isNegative()) {
                throw new IllegalArgumentException("processingDuration cannot be negative");
            }
            if (type != ExecutionEventType.NODE_COMPLETED && type != ExecutionEventType.NODE_BYPASSED
                    && type != ExecutionEventType.NODE_FAILED
                    // A retried attempt has ENDED -- that is precisely what the event says -- so it
                    // has a processing duration in exactly the sense the other three do. Excluding it
                    // would make the duration histogram silently omit every attempt that was retried,
                    // which is the population an operator investigating latency is looking for.
                    && type != ExecutionEventType.NODE_RETRY_SCHEDULED) {
                throw new IllegalArgumentException(
                        "processingDuration is only valid for terminal node events");
            }
        }
        if (type == ExecutionEventType.EDGE_TRAVERSED) {
            EdgeTraversalWireBudget.requireLiveProjection(tenantId, requestId, engineId, graphVersion,
                    nodeId, publicReason, detail, nodeCatalogKey, deploymentId, workloadId);
        }
        // Both counts are refused rather than clamped when negative. Clamping to zero would turn an
        // unreadable value into the exact value that means "not stated", so a producer bug would be
        // indistinguishable from a producer that correctly said nothing -- which is the distinction
        // both components exist to carry.
        if (attemptOrdinal < 0) {
            throw new IllegalArgumentException("attemptOrdinal cannot be negative: " + attemptOrdinal);
        }
        if (connectorAttempts < 0) {
            throw new IllegalArgumentException("connectorAttempts cannot be negative: " + connectorAttempts);
        }
    }

    /**
 * Compatibility constructor preserving the 25-component shape published before attempt ordinals and
 * connector-retry reporting.
 *
 * <p>Both new components default to {@code 0}, and that default is honest rather than a fabricated
 * measurement, by the argument the {@code inFlightArrivals} overload below already makes. For
 * {@code attemptOrdinal} zero reads "above attempt scope", which is what an adapter or a test with no
 * attempt in hand is entitled to say; for {@code connectorAttempts} zero is
 * {@link ai.ravenroot.api.execution.ConnectorRetryReport#NOT_REPORTED}, which is silence and not a
 * claim that a connector attempted exactly once. Defaulting {@code attemptOrdinal} to {@code 1}
 * instead was considered and refused: it would make every event built outside
 * {@code ExecutionMonitor} assert that it describes an initial attempt, which is precisely the claim
 * that must not be manufactured, since a retry is the case a reader is looking for.</p>
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
* @param deploymentId owning deployment identity, or {@code null} outside a deployment
* @param workloadId deployment-scoped unit-of-work identity, or {@code null} outside a deployment
* @param inFlightArrivals number of arrivals started at this node and not yet finished
* @param publicReason bounded public classifier, or {@code null} when none applies
* @param authorMessage bounded failure text for the authenticated author view
* @param authorOutput bounded output from the trusted built-in log action
* @param edgeId stable GraphML edge identity, required only on edge-traversal events
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration, String nodeCatalogKey, String deploymentId,
                          String workloadId, int inFlightArrivals, String publicReason,
                          TextProjection authorMessage, OutputProjection authorOutput, String edgeId) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, nodeCatalogKey, deploymentId, workloadId, inFlightArrivals, publicReason,
                authorMessage, authorOutput, edgeId, 0, 0);
    }

    /**
 * Compatibility constructor preserving the 24-component shape published before edge traversal.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
* @param deploymentId owning deployment identity, or {@code null} outside a deployment
* @param workloadId deployment-scoped unit-of-work identity, or {@code null} outside a deployment
* @param inFlightArrivals number of arrivals started at this node and not yet finished
* @param publicReason bounded public classifier, or {@code null} when none applies
* @param authorMessage bounded failure text for the authenticated author view
* @param authorOutput bounded output from the trusted built-in log action
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration, String nodeCatalogKey, String deploymentId,
                          String workloadId, int inFlightArrivals, String publicReason,
                          TextProjection authorMessage, OutputProjection authorOutput) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, nodeCatalogKey, deploymentId, workloadId, inFlightArrivals, publicReason,
                authorMessage, authorOutput, null);
    }

    /**
 * Compatibility constructor preserving the canonical shape before {@code inFlightArrivals}.
 * Additive, not breaking, for the reason the {@code joinWaitDuration} overload below spells out.
 *
 * <p>{@code inFlightArrivals} defaults to {@code 0}, and that default is honest rather than a
 * fabricated measurement: the arrival count is maintained by {@code ExecutionMonitor}, which builds
 * every event core emits through the canonical constructor. An event built through this overload
 * came from an adapter or a test that has no arrival counter to report from, and 0 is what this
 * component already means on every event with no node — it is not a claim that a node was idle.
 * <b>It deliberately does not default to {@code activeInstances}</b>: making the two components
 * mirror each other by default is precisely the conflation these separate fields prevent.</p>
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
* @param deploymentId owning deployment identity, or {@code null} outside a deployment
* @param workloadId deployment-scoped unit-of-work identity, or {@code null} outside a deployment
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration, String nodeCatalogKey, String deploymentId,
                          String workloadId) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, nodeCatalogKey, deploymentId, workloadId, 0, null, null, null);
    }

    /**
 * Compatibility constructor preserving the canonical shape before {@code publicReason}.
 *
 * <p>{@code null} is the honest default rather than a degraded one: an event built through this
 * overload came from an adapter or a test with no classifier to report, and the component's
 * contract already reads absence there. It deliberately does <b>not</b> derive one from
 * {@code detail} — a default that parsed the diagnostic would reintroduce, through a path nobody
 * reads, exactly the disclosure this component exists to avoid.</p>
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
* @param deploymentId owning deployment identity, or {@code null} outside a deployment
* @param workloadId deployment-scoped unit-of-work identity, or {@code null} outside a deployment
* @param inFlightArrivals number of arrivals started at this node and not yet finished
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration, String nodeCatalogKey, String deploymentId,
                          String workloadId, int inFlightArrivals) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, nodeCatalogKey, deploymentId, workloadId, inFlightArrivals, null, null, null);
    }

    /**
 * Compatibility constructor preserving the canonical shape before author-visible log output.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
* @param deploymentId owning deployment identity, or {@code null} outside a deployment
* @param workloadId deployment-scoped unit-of-work identity, or {@code null} outside a deployment
* @param inFlightArrivals number of arrivals started at this node and not yet finished
* @param publicReason bounded public classifier, or {@code null} when none applies
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration, String nodeCatalogKey, String deploymentId,
                          String workloadId, int inFlightArrivals, String publicReason) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, nodeCatalogKey, deploymentId, workloadId, inFlightArrivals, publicReason,
                null, null);
    }

    /**
 * Compatibility constructor preserving the canonical shape before {@code deploymentId}/
 * {@code workloadId} (ADR 0021 D5). Both default to {@code null} — a
 * one-shot/playground submission never has either, and this is also the shape every event
 * predating deployments themselves was already constructed with.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration, String nodeCatalogKey) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, nodeCatalogKey, null, null);
    }

    /**
 * Compatibility constructor preserving the canonical shape before {@code nodeCatalogKey}
 * (ADR 0021 D5). {@code nodeCatalogKey} defaults to {@code null} — absent, not a placeholder.
 *
 * <p>A synthetic value such as {@code "unknown"} would be worse than absence here, because this
 * component becomes a <em>metric label</em>: a placeholder would be indistinguishable from a real
 * catalog key in every dashboard and alert built on it. Absence is representable; a lie is not.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
* @param processingDuration monotonic node-processing duration, or {@code null} when not measured
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration,
                          Duration processingDuration) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration,
                processingDuration, null);
    }

    /**
 * Compatibility constructor preserving the canonical shape before {@code processingDuration}
 *. Existing source callers retain their exact argument list; the new measurement is absent
 * because an adapter constructing an event after the fact has no monotonic start reading from which
 * to derive it honestly.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
* @param joinWaitDuration elapsed join wait, or {@code null} when not measured
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail, Duration joinWaitDuration) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, joinWaitDuration, null,
                null);
    }

    /**
 * Compatibility alias: legacy execution identity is exactly traversal identity.
* @return the traversal identity carried by this event
 */
    public UUID executionId() {
        return traversalId;
    }

    /**
 * Caps {@code detail} at {@link #MAX_DETAIL_LENGTH} characters including the marker.
 *
 * <p>This bounds the value; it does not sanitize it. See the {@code detail} parameter's own
 * documentation for what the string may still contain and who may safely receive it.</p>
 *
 * <p>The rule itself lives in {@code DiagnosticText}, shared with
 * {@link NodeAttempt#parkCause()}. The cut never lands between a high and a low surrogate:
 * splitting a pair would leave a lone surrogate that has no valid UTF-8 encoding, and the two
 * places this value goes next — the SSE and {@code /v1/events/recent} JSON, and the structured log
 * line — would each have to invent a replacement for it.</p>
 */
    private static String bound(String detail) {
        return DiagnosticText.bounded(detail, MAX_DETAIL_LENGTH, DETAIL_TRUNCATION_MARKER);
    }

    /**
 * Returns {@code publicReason} when it is a classifier, and {@code null} when it is anything else.
 *
 * <p>Rejects rather than repairs. Truncating an over-long value, or stripping the characters that
 * do not belong, would yield a string that reads like a legitimate classifier and is not one —
 * and the surface that renders this field has no way to tell those apart. Absence is
 * representable there; a plausible-looking fabrication is not.</p>
 *
 * <p>The permitted set is letters, digits, {@code .}, {@code _}, {@code -} and {@code :}: enough
 * for an outcome name, a Java simple class name and a join reason, and not enough for a sentence.
 * ASCII-only by intent — a classifier that needed more would be prose.</p>
 */
    private static String conformingPublicReason(String publicReason) {
        if (publicReason == null || publicReason.isEmpty()
                || publicReason.length() > MAX_PUBLIC_REASON_LENGTH) {
            return null;
        }
        for (int index = 0; index < publicReason.length(); index++) {
            char character = publicReason.charAt(index);
            boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-' || character == ':';
            if (!permitted) {
                return null;
            }
        }
        return publicReason;
    }

    /**
 * Compatibility constructor preserving {@code ExecutionEvent}'s canonical shape before
 * {@code joinWaitDuration}. Additive, not breaking: a record's canonical constructor
 * changes arity when a component is added, which is a binary break as well as a source one for
 * any caller built against the older class file, not only an uncompiled one. This overload keeps
 * both by giving the pre-PLAT-01 15-argument shape its own constructor rather than only relying on
 * source-level default-argument sugar Java does not have. {@code joinWaitDuration} defaults to
 * {@code null} — absent, not zero, per this class's own field-level warning.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId, UUID invocationId,
                          UUID attemptId, ExecutionEventType type, String nodeId, int activeInstances,
                          boolean fallback, String detail) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, traversalId,
                invocationId, attemptId, type, nodeId, activeInstances, fallback, detail, null, null, null);
    }

    /**
 * Compatibility constructor for the attempt1 event shape.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param processInstanceId identity of the process instance that owns the event
* @param executionId legacy traversal identity retained for source compatibility
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID processInstanceId, UUID executionId, UUID invocationId,
                          ExecutionEventType type, String nodeId, int activeInstances, boolean fallback,
                          String detail) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, processInstanceId, executionId,
                invocationId, null, type, nodeId, activeInstances, fallback, detail, null, null, null);
    }

    /**
 * Compatibility constructor for adapters that produce an event outside a node invocation.
* @param sequence producer-assigned sequence in the live event stream
* @param occurredAt time at which the event occurred
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param executionId legacy traversal identity retained for source compatibility
* @param type execution event type
* @param nodeId graph node identity associated with the operation or event
* @param activeInstances number of live runtime instances serving this node
* @param fallback whether the node used its configured fallback behavior
* @param detail bounded trusted diagnostic text
 */
    public ExecutionEvent(long sequence, Instant occurredAt, String tenantId, String requestId, String engineId,
                          String graphVersion, UUID executionId, ExecutionEventType type, String nodeId,
                          int activeInstances, boolean fallback, String detail) {
        this(sequence, occurredAt, tenantId, requestId, engineId, graphVersion, executionId, executionId, null,
                null, type, nodeId, activeInstances, fallback, detail, null, null, null);
    }
}
