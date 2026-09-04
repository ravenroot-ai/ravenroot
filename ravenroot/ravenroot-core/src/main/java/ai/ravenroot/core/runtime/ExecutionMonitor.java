package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.EdgeTraversalWireBudget;
import ai.ravenroot.api.application.StableEdgeId;
import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.application.RuntimeActivityData.OutputProjection;
import ai.ravenroot.api.application.RuntimeActivityData.TextProjection;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.execution.ConnectorRetryReport;
import ai.ravenroot.api.execution.NodeActionDiagnostic;
import ai.ravenroot.core.graph.GraphEdge;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** In-memory live counters designed to be exposed by adapters without leaking engine types. */
public final class ExecutionMonitor {
    private static final int HISTORY_LIMIT = 2_048;

    /**
     * The attempt ordinal reported when this monitor has no attempt in hand: an event above attempt
     * scope, or a settle path for an attempt whose start it never saw.
     *
     * <p>Deliberately not {@code 1}. One means "this is the initial attempt", which is a claim, and a
     * reader distinguishing an initial attempt from a retry must not have that claim manufactured for
     * them out of the monitor's own ignorance.</p>
     */
    static final int UNSTATED_ORDINAL = 0;

    /**
     * The {@code NODE_BYPASSED} detail for a bypass the traversal imposed: an inbound
     * {@code command=passthrough}, or the whole submission running under {@code TEST_PASSTHROUGH}.
     * Unchanged character for character from its original wire form.
     */
    static final String COMMAND_BYPASS_DETAIL = "incoming command=passthrough";

    /**
     * The {@code NODE_BYPASSED} detail for the author's own per-node switch (
     * {@code NodeBypassProperty}). Distinct from {@link #COMMAND_BYPASS_DETAIL} because the two are
     * different facts under one event type: "this run is not executing anything" against "this one
     * node is switched off in the document, and the rest of the graph is running for real".
     */
    static final String AUTHORED_BYPASS_DETAIL = "authored bypass";

    private final AtomicInteger activeExecutions = new AtomicInteger();
    /**
     * Arrivals in flight per node: incremented when a node starts, decremented when it settles.
     *
     * <p>Renamed from {@code activeNodes} because the old name was the whole misunderstanding in one
     * word. This is a <em>queue depth</em>, not a population of actors: for a node whose nature is
     * resident, ten concurrent arrivals waiting on the one shared actor count 10 here while exactly one
     * instance exists. It feeds {@link ExecutionEvent#inFlightArrivals()}, which is the second number
     * exposed alongside the instance count, and it no longer feeds
     * {@link ExecutionEvent#activeInstances()} — that one is measured by the caller, from the runner's
     * instance registry, and passed in.
     */
    private final ConcurrentHashMap<String, AtomicInteger> arrivalsInFlight = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<ExecutionEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<ExecutionEvent> history = new ArrayDeque<>();
    private final ConcurrentHashMap<UUID, AttemptStart> attemptStarts = new ConcurrentHashMap<>();
    private final LongSupplier monotonicNanos;

    /** Uses the JVM's monotonic elapsed-time source for node processing measurements. */
    public ExecutionMonitor() {
        this(System::nanoTime);
    }

    /**
     * Creates a monitor with an explicit monotonic nanosecond source. The supplier is injectable so
     * elapsed-time behavior is deterministic in tests and never depends on wall-clock adjustment.
     */
    public ExecutionMonitor(LongSupplier monotonicNanos) {
        this.monotonicNanos = java.util.Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    void executionStarted(ExecutionIdentity identity) {
        activeExecutions.incrementAndGet();
        publish(identity, ExecutionEventType.EXECUTION_STARTED, null, 0, false, "execution accepted");
    }

    /**
     * Publishes the traversal's terminal success, saying in the same breath whether anything failed
     * on the way.
     *
     * <p>{@code handledFailureNodes} is the set {@code GraphRunner} already computes for
     * {@code GraphExecutionResult.handledFailureNodes()}, passed in rather than recomputed: the
     * monitor holds no lifecycle and could not derive it. It is normally empty, and an empty set
     * produces the unchanged {@code "execution completed"} detail, so a clean run's event is
     * byte-identical to what it published before.</p>
     *
     * <h2>Why the detail, and why this event at all</h2>
     * <p>A node failure does not necessarily fail the traversal, and that is deliberate. <b>Two</b>
     * mechanisms survive one, and both are correct: a join that still meets its quorum absorbs the
     * failure of one of its branches (CORE-03), and a declared or defaulted failure route carries
     * the branch onward. Which of the two applied is not this class's business; that
     * the run suffered a fault at all <em>is</em>. The event therefore records the fact without
     * trying to classify which recovery mechanism applied.</p>
     *
     * <p>Previously the <em>only</em> carrier of that fact was {@code GraphExecutionResult}: the
     * result object knew, and the event stream did not. For example, two {@code program} nodes failed
     * with an empty tool allowlist, each published its own {@code NODE_FAILED}, and the run then
     * published {@code "execution completed"} — so a reader with the log and not the result object
     * concluded the run had worked. An execution that reports success after every node that did
     * something failed is the worst thing to ship, because it is not read as a bug; it is read as a
     * pass.</p>
     *
     * <p>{@code detail} is the channel rather than a new record component because
     * {@link ai.ravenroot.api.application.ExecutionEvent} is a published record whose shape is pinned
     * by a binary-compatibility test, and because this is exactly what that field is for: a bounded
     * diagnostic for the person reading their own run. The node ids come from the graph's author, so
     * they are already the same class of value {@code NODE_COMPLETED}'s {@code "outcome="} detail
     * carries. The length bound is unchanged and needs nothing here: it is applied by
     * {@link ai.ravenroot.api.application.ExecutionEvent}'s own compact constructor, which caps
     * {@code detail} at {@code MAX_DETAIL_LENGTH} characters and marks the cut, so a graph with more
     * failed nodes than 512 characters of ids yields a truncated list rather than an unbounded
     * event.</p>
     */
    void executionCompleted(ExecutionIdentity identity, Set<String> handledFailureNodes) {
        activeExecutions.decrementAndGet();
        discardAttemptStarts(identity.traversalId());
        publish(identity, ExecutionEventType.EXECUTION_COMPLETED, null, 0, false,
                completionDetail(handledFailureNodes));
    }

    /**
     * {@code "execution completed"} when nothing failed, and otherwise a sentence that says a failure
     * occurred, how many, and on which nodes.
     *
     * <p>The count is stated separately from the list because the list is what gets truncated when a
     * graph has more failed nodes than {@code MAX_DETAIL_LENGTH} allows: a reader who sees the tail
     * cut off still has the number, and a number that disagrees with a visibly-truncated list is
     * readable as truncation rather than as a miscount.</p>
     */
    private static String completionDetail(Set<String> handledFailureNodes) {
        if (handledFailureNodes == null || handledFailureNodes.isEmpty()) {
            return "execution completed";
        }
        return "execution completed with " + handledFailureNodes.size() + " handled node failure(s): "
                + String.join(", ", handledFailureNodes);
    }

    void executionFailed(ExecutionIdentity identity, Throwable error) {
        activeExecutions.decrementAndGet();
        discardAttemptStarts(identity.traversalId());
        publish(identity, null, null, ExecutionEventType.EXECUTION_FAILED, null, 0, false, message(error),
                null, null, 0, failureClass(error));
    }

    /**
     * @param liveInstances how many runtime instances of this node's actor are alive, measured by the
     *                      caller at the moment of the call. The monitor cannot derive this: the
     *                      instances live in {@code GraphRunner}'s {@code WorkerInstanceRegistry} for the
     *                      default nature and in its resident-actor map for the others, and neither is
     *                      visible from here. Passing it in is what keeps the reported number a
     *                      measurement rather than the inference from arrivals that it used to be.
     */
    void nodeStarted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                     int liveInstances) {
        nodeStarted(identity, nodeId, invocationId, attemptId, liveInstances, UNSTATED_ORDINAL);
    }

    /**
     * The attempt-aware start, which is the one the runner uses.
     *
     * <p>{@code attemptOrdinal} is recorded here and read back by every settle path for this attempt,
     * so a retry's completion, failure or further retry reports the ordinal its own start reported
     * without any of them being given the number again.</p>
     *
     * @param attemptOrdinal the one-based ordinal of this attempt: {@code 1} for an initial attempt
     *                       and greater for an orchestration retry, or {@link #UNSTATED_ORDINAL} from
     *                       a caller that has no attempt history in hand
     */
    void nodeStarted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                     int liveInstances, int attemptOrdinal) {
        if (attemptId != null) {
            attemptStarts.put(attemptId,
                    new AttemptStart(identity.traversalId(), monotonicNanos.getAsLong(), attemptOrdinal));
        }
        int arrivals = increment(nodeId);
        publish(identity, invocationId, attemptId, ExecutionEventType.NODE_STARTED, nodeId, liveInstances, false,
                "node processing started", null, null, arrivals, null, null, null,
                attemptOrdinal, ConnectorRetryReport.NOT_REPORTED);
    }

    /**
     * Publishes the orchestrator's decision to make a further durable attempt.
     *
     * <p>Named for the decision and not for the failure, because the failure is not the event: an
     * attempt that failed and will be retried settles once, here, instead of publishing
     * {@code NODE_FAILED} and then contradicting it. See
     * {@link ExecutionEventType#NODE_RETRY_SCHEDULED} for why that replacement is the shape rather
     * than a second event, and note the arrival is decremented and the attempt timing finished
     * exactly as {@link #nodeFailed} does — this attempt is over, and the retry's own
     * {@code NODE_STARTED} increments again.</p>
     *
     * @param identity          the traversal-scoped identity every event carries
     * @param nodeId            the node whose attempt failed
     * @param invocationId      the invocation the failed attempt belongs to
     * @param attemptId         the attempt that failed, never the one being scheduled
     * @param nextOrdinal       the ordinal the scheduled retry will carry
     * @param delay             how long the runtime will wait before that retry
     * @param classification    the failure's retry classification, as a bounded classifier token.
     *                          It occupies {@code publicReason} rather than the failure's class name,
     *                          which {@code NODE_FAILED} puts there: on a retry the classification is
     *                          the fact that explains the decision, and the class name is carried in
     *                          the diagnostic beside it rather than lost
     * @param error             the failure this attempt produced, whose deepest cause's class and
     *                          message become the event's diagnostic and its author-facing text
     * @param connectorAttempts connector-level attempts inside the failed attempt, or
     *                          {@link ConnectorRetryReport#NOT_REPORTED}
     * @param liveInstances     live runtime instances of this node, measured by the caller
     */
    void nodeRetryScheduled(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                            int nextOrdinal, Duration delay, String classification, Throwable error,
                            int connectorAttempts, int liveInstances) {
        int arrivals = decrement(nodeId);
        int failedOrdinal = ordinalOf(attemptId);
        // The retry facts lead and the failure text follows, deliberately. ExecutionEvent bounds
        // `detail` at 512 chars with a visible truncation marker, and a node whose exception message
        // is a page of text would otherwise push the ordinal and the delay off the end -- so the two
        // facts that are always short and always wanted are the two that always survive.
        String detail = "retrying as attempt " + nextOrdinal + " after " + delay.toMillis() + "ms: "
                + failureClass(error) + ": " + message(error);
        publish(identity, invocationId, attemptId, ExecutionEventType.NODE_RETRY_SCHEDULED, nodeId,
                liveInstances, false, detail,
                null, finishAttempt(attemptId), arrivals, classification, null, null,
                failedOrdinal, connectorAttempts);
    }

    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                       boolean fallback, String outcome, int liveInstances) {
        nodeCompleted(identity, nodeId, invocationId, attemptId, fallback,
                "unknown behavior executed as pass-through", outcome, liveInstances, null);
    }

    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                       boolean fallback, String outcome, int liveInstances,
                       NodeActionDiagnostic actionDiagnostic) {
        nodeCompleted(identity, nodeId, invocationId, attemptId, fallback, outcome, liveInstances,
                actionDiagnostic, ConnectorRetryReport.NOT_REPORTED);
    }

    /**
     * The runner's completion path: a typed author-display action plus the connector's own report.
     *
     * @param connectorAttempts connector-level attempts inside this orchestration attempt, taken from
     *                          {@link ai.ravenroot.api.execution.NodeResult#connectorAttempts()}, or
     *                          {@link ConnectorRetryReport#NOT_REPORTED} when the node said nothing
     */
    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                       boolean fallback, String outcome, int liveInstances,
                       NodeActionDiagnostic actionDiagnostic, int connectorAttempts) {
        nodeCompleted(identity, nodeId, invocationId, attemptId, fallback,
                "unknown behavior executed as pass-through", outcome, liveInstances,
                actionDiagnostic == null || actionDiagnostic.kind() != NodeActionDiagnostic.Kind.LOG
                        ? null : actionDiagnostic.output(),
                connectorAttempts);
    }

    /**
     * The command bypass: this node was reached under {@code command=passthrough}, or the whole
     * traversal is running under {@code TEST_PASSTHROUGH}. Kept as its own overload, with the detail
     * string it has always published, so every existing caller and every consumer parsing that string
     * is unaffected by the authored per-node switch.
     */
    void nodeBypassed(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                      int liveInstances) {
        nodeBypassed(identity, nodeId, invocationId, attemptId, liveInstances, false);
    }

    /**
     * One {@code NODE_BYPASSED} event, saying <em>which</em> of the two bypasses this was.
     *
     * <p>The event type is deliberately not split. {@code NODE_BYPASSED} already means "a completion
     * the framework qualifies", which is true of both facts, and a second type would force every
     * consumer — the activity panel, the telemetry bridge, the journal — to learn a new constant to
     * keep reporting what it already reports. What differs is the <em>reason</em>, so the reason is
     * what the detail carries: {@value #COMMAND_BYPASS_DETAIL} for a bypass the traversal imposed, and
     * {@value #AUTHORED_BYPASS_DETAIL} for one the graph's author wrote on that single node. A panel
     * that shows neither is no worse off than before; one that shows the detail now distinguishes
     * "this run is a test" from "this node is switched off in the document", which are different
     * things to see and act on.</p>
     *
     * <p>Two channels carry the distinction and they are derived from one boolean here, on purpose.
     * {@code detail} is the in-process diagnostic and <strong>never leaves the process</strong> —
     * {@code RavenrootServer} does not serialize it on either event route, by design, because it can
     * hold exception text elsewhere. So {@code detail} alone would distinguish the two bypasses for a
     * log reader and for nobody else, while the activity panel must show the difference to a reader
     * on the far side of that boundary. {@code publicReason} is the component that does cross it: a
     * character-restricted classifier that {@code PublicExecutionDescription}
     * turns into a source-authored sentence. Taking a {@code boolean} rather than two strings is what
     * makes it impossible for a caller to pair the authored detail with the command classifier.</p>
     *
     * @param authoredByGraph {@code true} when the graph's author switched this node off with
     *                        {@code execution.bypass}; {@code false} for the command/test-mode bypass
     */
    void nodeBypassed(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                      int liveInstances, boolean authoredByGraph) {
        int arrivals = decrement(nodeId);
        int ordinal = ordinalOf(attemptId);
        publish(identity, invocationId, attemptId, ExecutionEventType.NODE_BYPASSED, nodeId, liveInstances, false,
                authoredByGraph ? AUTHORED_BYPASS_DETAIL : COMMAND_BYPASS_DETAIL,
                null, finishAttempt(attemptId), arrivals,
                authoredByGraph ? ExecutionEvent.BYPASS_REASON_AUTHORED : ExecutionEvent.BYPASS_REASON_COMMAND,
                // A bypassed node never ran a connector, so nothing is reported rather than one.
                null, null, ordinal, ConnectorRetryReport.NOT_REPORTED);
    }

    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                       boolean fallback, String fallbackDetail, String outcome, int liveInstances,
                       OutputProjection output) {
        nodeCompleted(identity, nodeId, invocationId, attemptId, fallback, fallbackDetail, outcome,
                liveInstances, output, ConnectorRetryReport.NOT_REPORTED);
    }

    /**
     * The completion that also reports what the node's connector did inside this one attempt.
     *
     * @param connectorAttempts connector-level attempts within this orchestration attempt, or
     *                          {@link ConnectorRetryReport#NOT_REPORTED} when the node said nothing.
     *                          It is carried on the completion and on the defaulted row beside it,
     *                          because both describe the same attempt and a reader filtering on
     *                          either must see the same number
     */
    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                       boolean fallback, String fallbackDetail, String outcome, int liveInstances,
                       OutputProjection output, int connectorAttempts) {
        int arrivals = decrement(nodeId);
        int ordinal = ordinalOf(attemptId);
        Duration processingDuration = finishAttempt(attemptId);
        if (fallback) {
            publish(identity, invocationId, attemptId, ExecutionEventType.NODE_DEFAULTED, nodeId, liveInstances,
                    true, fallbackDetail, null, null, arrivals, null, null, null, ordinal, connectorAttempts);
        }
        // The same outcome, twice, for two different readers. The diagnostic keeps its
        // "outcome=" prose shape because logs and existing parsers read it; the classifier is the bare
        // token, because the public surface renders it into a sentence and a prefix would appear
        // inside that sentence.
        String routedOutcome = outcome == null ? GraphEdge.DEFAULT_OUTCOME : outcome;
        publish(identity, invocationId, attemptId, ExecutionEventType.NODE_COMPLETED, nodeId, liveInstances,
                fallback, "outcome=" + routedOutcome, null, processingDuration,
                arrivals, routedOutcome,
                "log".equals(identity.catalogKeyFor(nodeId)) ? output : null, null, ordinal, connectorAttempts);
    }

    void nodeFailed(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId, Throwable error,
                    int liveInstances) {
        nodeFailed(identity, nodeId, invocationId, attemptId, error, liveInstances,
                ConnectorRetryReport.NOT_REPORTED);
    }

    /**
     * The failure that also reports what the node's connector did inside this one attempt.
     *
     * <p>This is the terminal failure of a visit: an attempt whose failure the retry policy accepted
     * publishes {@link ExecutionEventType#NODE_RETRY_SCHEDULED} instead, so the count of these stays
     * the count of node visits that actually failed.</p>
     *
     * @param connectorAttempts connector-level attempts within this orchestration attempt, or
     *                          {@link ConnectorRetryReport#NOT_REPORTED} when the node said nothing
     */
    void nodeFailed(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId, Throwable error,
                    int liveInstances, int connectorAttempts) {
        int arrivals = decrement(nodeId);
        int ordinal = ordinalOf(attemptId);
        publish(identity, invocationId, attemptId, ExecutionEventType.NODE_FAILED, nodeId, liveInstances, false,
                message(error), null, finishAttempt(attemptId), arrivals, failureClass(error),
                null, null, ordinal, connectorAttempts);
    }

    /**
     * Publishes the authoritative observation made at successor dispatch. The source invocation and
     * attempt are retained for correlation; no payload, diagnostic or graph property crosses this
     * boundary. {@code publicReason} carries the already-restricted routed outcome while the exact
     * authored/synthesized edge handle has its own field.
     */
    void edgeTraversed(ExecutionIdentity identity, String edgeId, String sourceNodeId,
                       UUID sourceInvocationId, UUID sourceAttemptId, String outcome) {
        String routedOutcome = outcome == null ? GraphEdge.DEFAULT_OUTCOME : outcome;
        publish(identity, sourceInvocationId, sourceAttemptId, ExecutionEventType.EDGE_TRAVERSED,
                sourceNodeId, 0, false, "edge traversed", null, null, 0,
                routedOutcome, null, edgeId);
    }

    /**
     * Refuses an observation that cannot fit every live, durable and structured-log projection.
     * Called before the runner appends the durable row, so an invalid live field cannot leave a
     * durable traversal behind and then fail while publishing it.
     */
    void requireEdgeTraversalAccepted(ExecutionIdentity identity, String edgeId,
                                      String sourceNodeId, String outcome) {
        StableEdgeId.requireValid(edgeId);
        String routedOutcome = outcome == null ? GraphEdge.DEFAULT_OUTCOME : outcome;
        EdgeTraversalWireBudget.requireLiveProjection(identity.security().tenantId(),
                identity.security().requestId(), identity.engineId(), identity.graphVersion(),
                sourceNodeId, routedOutcome, "edge traversed", identity.catalogKeyFor(sourceNodeId),
                identity.deploymentId(), identity.workloadId());
        EdgeTraversalWireBudget.requireDurableProjection(ExecutionEventType.EDGE_TRAVERSED.name(),
                identity.graphVersion(), sourceNodeId);
    }

    /**
     * Overloads for callers with no instance registry in view — tests of this class's own timing and
     * catalog behaviour, and adapters that are not a {@code GraphRunner}.
     *
     * <p>They report <b>0</b> live instances, which is the same thing {@code activeInstances} has always
     * meant on an event whose producer had no node population to report. They deliberately do not fall
     * back to the arrival count: a default that made the two numbers mirror each other would quietly
     * reinstate the conflation, and it would do so on exactly the paths nobody is looking at.
     */
    void nodeStarted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId) {
        nodeStarted(identity, nodeId, invocationId, attemptId, 0);
    }

    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                       boolean fallback, String outcome) {
        nodeCompleted(identity, nodeId, invocationId, attemptId, fallback, outcome, 0);
    }

    void nodeFailed(ExecutionIdentity identity, String nodeId, UUID invocationId, UUID attemptId,
                    Throwable error) {
        nodeFailed(identity, nodeId, invocationId, attemptId, error, 0);
    }

    /**
     * A fan-in join fired (CORE-03). {@code arrived} may exceed {@code quorum} when several branches
     * land together; the event reports what actually satisfied the join rather than the configured
     * threshold alone, because "quorum 2 met by 3 branches" and "quorum 2 met by 2" are different
     * facts about the graph's timing.
     */
    /**
     * @param joinWaitDuration elapsed time between the join opening and this settle (PLAT-01),
     *                         required and never {@code null} — a join that satisfied has an
     *                         {@code openedAt} and a {@code settledAt} by construction
     */
    void joinSatisfied(ExecutionIdentity identity, String nodeId, int quorum, long arrived,
                       Duration joinWaitDuration) {
        publish(identity, null, null, ExecutionEventType.JOIN_SATISFIED, nodeId, 0, false,
                "quorum=" + quorum + " arrived=" + arrived,
                java.util.Objects.requireNonNull(joinWaitDuration, "joinWaitDuration"));
    }

    /**
     * A fan-in join is retaining more iterations of state than the runtime expects.
     *
     * <p>Not a ceiling, not a refusal and not a failure — nothing about the traversal changes when
     * this is published. A join that re-arms keeps one bucket of correlated arrivals per lap and never
     * compacts the buckets that have already fired, because ADR 0019's insert-only invariant forbids
     * it; so a cycle that goes round many times grows that state without any single arrival looking
     * wrong. This is what makes the growth countable. Whether the runtime should also bound it remains
     * unspecified.
     *
     * <p>It counts iterations <em>retained</em> rather than iterations <em>waiting</em>. The latter is
     * provably never more than one — see {@code JoinCoordinator.reportIterationBacklog} for the
     * argument — so a threshold over it could never be crossed by an executing graph.
     *
     * <p>No {@code joinWaitDuration}: this is not a settlement, so there is no interval between an
     * opening and a settling to report, and inventing one would make the PLAT-01 histogram measure two
     * different things.
     *
     * @param iterations iterations of this join whose arrivals are still held, at the crossing
     * @param threshold  the internal default that was crossed; carried in the detail so a reader is
     *                   not left comparing {@code iterations} against a number only the source knows
     */
    void joinIterationBacklog(ExecutionIdentity identity, String nodeId, int iterations, int threshold) {
        publish(identity, null, null, ExecutionEventType.JOIN_ITERATION_BACKLOG, nodeId, 0, false,
                "iterations=" + iterations + " threshold=" + threshold);
    }

    /** A branch arrival at a fan-in join changed nothing (CORE-03). Never a failure. */
    void joinArrivalDiscarded(ExecutionIdentity identity, String nodeId, String branchId, String reason) {
        publish(identity, null, null, ExecutionEventType.JOIN_ARRIVAL_DISCARDED, nodeId, 0, false,
                "branch=" + branchId + " reason=" + reason);
    }

    /**
     * A fan-in join gave up (CORE-03).
     *
     * @param joinWaitDuration elapsed time between the join opening and this settle (PLAT-01),
     *                         required and never {@code null} — a join that failed has an
     *                         {@code openedAt} and a {@code settledAt} by construction
     */
    void joinFailed(ExecutionIdentity identity, String nodeId, JoinFailureException failure,
                    Duration joinWaitDuration) {
        publish(identity, null, null, ExecutionEventType.JOIN_FAILED, nodeId, 0, false,
                "reason=" + failure.reason() + " arrived=" + failure.arrived()
                        + " failed=" + failure.failed() + " outstanding=" + failure.outstanding(),
                java.util.Objects.requireNonNull(joinWaitDuration, "joinWaitDuration"), null, 0,
                // The reason alone, not the counts beside it in the diagnostic: those are numbers
                // about this join's population and belong to the operator's diagnostic, whereas the
                // reason is the one fact that changes what the public sentence should say.
                failure.reason() == null ? null : failure.reason().name());
    }

    void nodeStarted(ExecutionIdentity identity, String nodeId, UUID invocationId) {
        nodeStarted(identity, nodeId, invocationId, invocationId);
    }

    void nodeCompleted(ExecutionIdentity identity, String nodeId, UUID invocationId, boolean fallback,
                       String outcome) {
        nodeCompleted(identity, nodeId, invocationId, invocationId, fallback, outcome);
    }

    void nodeFailed(ExecutionIdentity identity, String nodeId, UUID invocationId, Throwable error) {
        nodeFailed(identity, nodeId, invocationId, invocationId, error);
    }

    /**
     * <p><b>{@code activeNodeInstances} on the returned snapshot is the arrival count, not the instance
     * count</b>. The name predates the distinction and is a cross-process wire name — the CLI's
     * {@code RuntimeView} and the remote backend's JSON both read it — so it is documented here rather
     * than renamed. This monitor genuinely cannot report instances
     * from here: they live in {@code GraphRunner}'s registry, which reaches this class only as an
     * argument on a per-event call, and there is no such argument at snapshot time. A caller that needs
     * the number the elastic view shows must read {@link ExecutionEvent#activeInstances()}.
     */
    public RuntimeSnapshot snapshot() {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        arrivalsInFlight.forEach((node, count) -> counts.put(node, count.get()));
        return new RuntimeSnapshot(activeExecutions.get(), counts);
    }

    public List<ExecutionEvent> eventsAfter(long sequence) {
        synchronized (history) {
            return history.stream().filter(event -> event.sequence() > sequence).toList();
        }
    }

    /**
     * The lowest sequence still held in {@link #history}, or empty when nothing has been published yet.
     *
     * <p>This exists so a polling reader can tell <em>nothing happened</em> from <em>events existed and
     * have been evicted</em>. {@link #eventsAfter} alone cannot: this ring drops its oldest entry once
     * {@link #HISTORY_LIMIT} is reached, so a cursor that has fallen behind the floor produces a short
     * list that is indistinguishable from a quiet server. Returning the floor lets the caller compare it
     * against its own cursor and say so explicitly rather than presenting an eviction as continuity —
     * the same honesty {@code RavenrootApplication#durableEventsAfter} gets from its declared
     * {@code JournalTruncated} failure, which the durable path has and this one did not.</p>
     *
     * <p><strong>This monitor always has an answer, so it never returns empty.</strong> That matters
     * because the interface default <em>does</em> return empty, meaning "this implementation cannot say".
     * If an empty ring also reported empty, a fresh server with nothing to report would be
     * indistinguishable from an implementation that does not track retention at all — collapsing
     * "nothing happened" back into "unknown", which is the precise distinction this method exists to
     * preserve.</p>
     *
     * <p>An empty ring reports the current sequence counter rather than nothing. {@link #history} only
     * ever evicts once it is full, so an empty ring means nothing has been published yet: every
     * sequence up to the counter is accounted for by never having existed, and the retained window is
     * complete by vacuity rather than merely unknown.</p>
     */
    public java.util.OptionalLong oldestRetainedSequence() {
        synchronized (history) {
            ExecutionEvent oldest = history.peekFirst();
            return java.util.OptionalLong.of(oldest == null ? eventSequence.get() : oldest.sequence());
        }
    }

    public AutoCloseable subscribe(Consumer<ExecutionEvent> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Execution event listener cannot be null");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Mutates the counter INSIDE the mapping function passed to {@code compute}, not after it returns,
     * matching the sibling counter's atomicity requirement in {@code WorkerInstanceRegistry}.
     * {@code computeIfAbsent(...).incrementAndGet()} used to fetch the counter and mutate it as two
     * SEPARATE atomic steps: for an existing key AT THE HEAD of its bin, {@code computeIfAbsent}'s fast
     * path returns without taking the bin's monitor at all (JDK 21 {@code ConcurrentHashMap}: "check
     * first node without acquiring lock") — so for that case the fetch was a lock-free read and the
     * increment right after it was a lock-free CAS on the {@code AtomicInteger}. (For an existing key
     * further down the bin's chain, {@code computeIfAbsent} takes the monitor anyway, even though it
     * inserts nothing.) A concurrent {@link #decrement} — which mutates inside its own
     * {@code computeIfPresent} and therefore DOES take the bin's monitor — could run between the fetch
     * and the mutation on the head-of-bin fast path and remove the very {@code AtomicInteger} this call
     * was about to increment. The result: a start bumping a counter object the map no longer held a
     * reference to, so the arrival read as absent until the matching completion ran again and self-healed
     * it.
     *
     * <p>{@code WorkerInstanceRegistry#acquire} closes the same lost-counter window and its comment
     * carries the full account and the measurement. Doing the mutation inside {@code compute}'s
     * remapping function closes the window, at a real but small cost — {@code compute} has no
     * lock-free fast path for any existing key, because it must apply an arbitrary function to the
     * current value and then atomically install, replace, or remove the mapping the function returns,
     * and cannot know in advance that the
     * call will not change anything. (This is not about protecting a reader from observing a value about
     * to be discarded — see below: readers never take the monitor and can already observe a mapping
     * mid-removal, by design.) A synthetic harness isolating just these two map primitives (not this
     * class) measured +65 ns per acquire/release pair in the worst case it could construct (one hot key,
     * 8 threads), indistinguishable from noise past a handful of keys. Readers of {@link #snapshot()} are
     * unaffected: they read the {@code AtomicInteger} directly and never call {@code compute}.
     */
    private int increment(String nodeId) {
        var updated = new AtomicInteger();
        arrivalsInFlight.compute(nodeId, (ignored, counter) -> {
            AtomicInteger existing = counter == null ? new AtomicInteger() : counter;
            updated.set(existing.incrementAndGet());
            return existing;
        });
        return updated.get();
    }

    private int decrement(String nodeId) {
        var remaining = new AtomicInteger();
        arrivalsInFlight.computeIfPresent(nodeId, (ignored, counter) -> {
            int value = counter.decrementAndGet();
            remaining.set(Math.max(0, value));
            return value <= 0 ? null : counter;
        });
        return remaining.get();
    }

    /**
     * Removes and settles one attempt exactly once. A missing entry is an orphan terminal event: the
     * notification is still useful and is published with no fabricated duration.
     */
    /**
     * Reads an attempt's recorded ordinal without ending its timing.
     *
     * <p>Separate from {@link #finishAttempt(UUID)}, which removes the entry, so a settle path can
     * read the number and then close the timing in either order without one call destroying the
     * other's input. An attempt this monitor never saw start answers {@link #UNSTATED_ORDINAL} rather
     * than {@code 1}: it did not observe an initial attempt, it observed nothing.</p>
     */
    private int ordinalOf(UUID attemptId) {
        if (attemptId == null) {
            return UNSTATED_ORDINAL;
        }
        AttemptStart started = attemptStarts.get(attemptId);
        return started == null ? UNSTATED_ORDINAL : started.ordinal();
    }

    private Duration finishAttempt(UUID attemptId) {
        if (attemptId == null) {
            return null;
        }
        AttemptStart started = attemptStarts.remove(attemptId);
        if (started == null) {
            return null;
        }
        long elapsed = monotonicNanos.getAsLong() - started.nanoTime();
        return Duration.ofNanos(Math.max(0L, elapsed));
    }

    /** A terminal traversal cannot retain timing state for work that will never report back. */
    private void discardAttemptStarts(UUID traversalId) {
        attemptStarts.entrySet().removeIf(entry -> entry.getValue().traversalId().equals(traversalId));
    }

    private void publish(ExecutionIdentity identity, ExecutionEventType type, String nodeId,
                         int active, boolean fallback, String detail) {
        publish(identity, null, null, type, nodeId, active, fallback, detail);
    }

    /** Every non-join event: {@code joinWaitDuration} is absent, never a manufactured zero. */
    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, null, null);
    }

    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, joinWaitDuration, null);
    }

    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration,
                         Duration processingDuration) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, joinWaitDuration,
                processingDuration, 0);
    }

    /**
     * @param active           live instances of this node's actor, measured by the caller
     * @param inFlightArrivals arrivals started and not yet settled at this node — the other number
     */
    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration,
                         Duration processingDuration, int inFlightArrivals) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, joinWaitDuration,
                processingDuration, inFlightArrivals, null);
    }

    /**
     * The widest overload, and the only one that sets {@code publicReason}.
     *
     * <p>Every other overload defaults it to {@code null}, which is correct rather than lazy: the
     * event types that reach them — started, defaulted, join-satisfied, arrival-discarded — are fully
     * described by their type, and there is no second fact about them for a classifier to carry. The
     * types whose meaning depends on <em>which</em> outcome or <em>which</em> failure occurred pass a
     * value, and each does so from a structured source it already holds, never by re-deriving it from
     * {@code detail}.</p>
     *
     * <p>{@code NODE_BYPASSED} now belongs to that group. The sentence above used to name it among
     * the self-describing types. That was true while the type had
     * one cause; it stopped being true when an author gained the ability to switch a single node off,
     * because the type then covered two facts a reader has to tell apart — see
     * {@link #nodeBypassed(ExecutionIdentity, String, UUID, UUID, int, boolean)}.</p>
     */
    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration,
                         Duration processingDuration, int inFlightArrivals, String publicReason) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, joinWaitDuration,
                processingDuration, inFlightArrivals, publicReason, null);
    }

    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration,
                         Duration processingDuration, int inFlightArrivals, String publicReason,
                         OutputProjection authorOutput) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, joinWaitDuration,
                processingDuration, inFlightArrivals, publicReason, authorOutput, null);
    }

    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration,
                         Duration processingDuration, int inFlightArrivals, String publicReason,
                         OutputProjection authorOutput, String edgeId) {
        publish(identity, invocationId, attemptId, type, nodeId, active, fallback, detail, joinWaitDuration,
                processingDuration, inFlightArrivals, publicReason, authorOutput, edgeId,
                UNSTATED_ORDINAL, ConnectorRetryReport.NOT_REPORTED);
    }

    /**
     * The full overload, and the only one that sets the attempt-scoped counts.
     *
     * <p>Every narrower overload defaults both to "not stated", which is correct rather than lazy for
     * the same reason {@code publicReason} defaults to {@code null}: the events that reach them —
     * execution started and failed, the join family, edge traversal — are not about one attempt, and
     * a fabricated {@code 1} there would assert that they described an initial attempt.</p>
     *
     * @param attemptOrdinal    the failing or running attempt's one-based ordinal, or
     *                          {@link #UNSTATED_ORDINAL}
     * @param connectorAttempts connector-level attempts reported for that attempt, or
     *                          {@link ConnectorRetryReport#NOT_REPORTED}
     */
    private void publish(ExecutionIdentity identity, UUID invocationId, UUID attemptId, ExecutionEventType type,
                         String nodeId, int active, boolean fallback, String detail, Duration joinWaitDuration,
                         Duration processingDuration, int inFlightArrivals, String publicReason,
                         OutputProjection authorOutput, String edgeId, int attemptOrdinal,
                         int connectorAttempts) {
        TextProjection authorMessage = switch (type) {
            // NODE_RETRY_SCHEDULED belongs here because it SETTLES an attempt that failed, and it is
            // the only settlement that attempt ever gets: it replaces NODE_FAILED rather than
            // preceding it. Without it, a node that fails twice and then succeeds leaves no record
            // anywhere of what went wrong -- the two diagnostics an author most needs would be the
            // two the runtime silently dropped, and the successful third attempt would make the
            // execution look untroubled.
            case NODE_FAILED, JOIN_FAILED, EXECUTION_FAILED, NODE_RETRY_SCHEDULED ->
                    RuntimeActivityData.message(detail);
            default -> null;
        };
        var event = new ExecutionEvent(eventSequence.incrementAndGet(), Instant.now(),
                identity.security().tenantId(), identity.security().requestId(), identity.engineId(),
                identity.graphVersion(), identity.processInstanceId(), identity.traversalId(), invocationId, attemptId,
                type, nodeId, active, fallback, detail, joinWaitDuration, processingDuration,
                // Resolve the catalog key where every event already funnels through rather than thread
                // it through fourteen signatures. The map is per-traversal because the graph is,
                // and it rides on the identity JoinCoordinator already carries -- so join events get
                // the dimension for free instead of needing a new dependency.
                identity.catalogKeyFor(nodeId),
                // ADR 0021 D5: constant for the whole identity, so every event
                // this traversal ever produces carries the same value -- that constancy is what makes
                // either usable to correlate an item's activity, not merely to observe that some value
                // was present on one event.
                identity.deploymentId(), identity.workloadId(), inFlightArrivals,
                // The classifier travels beside the diagnostic rather than being extracted from
                // it downstream: every caller below already holds the structured value, and a server
                // that had to parse "outcome=" back out of detail would be one regex away from
                // publishing the diagnostic it is forbidden to publish.
                publicReason, authorMessage, authorOutput, edgeId, attemptOrdinal, connectorAttempts);
        synchronized (history) {
            history.addLast(event);
            while (history.size() > HISTORY_LIMIT) {
                history.removeFirst();
            }
        }
        listeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // Observers cannot break graph execution.
            }
        });
    }

    /**
     * What is remembered between a node attempt's start and its settlement.
     *
     * <p>{@code ordinal} rides here rather than being threaded through every settle-path signature,
     * and that is the same argument the catalog key already uses inside {@link #publish}: the value
     * is established once, at the one point that knows it, and read where it is needed. It also makes
     * the two projections structurally unable to disagree — a completion cannot report an ordinal its
     * own start did not report, because there is only one place the number is written.</p>
     *
     * @param traversalId the traversal whose end discards this entry
     * @param nanoTime    the monotonic reading taken when the attempt started
     * @param ordinal     the one-based attempt ordinal; {@code 1} initial, greater on a retry
     */
    private record AttemptStart(UUID traversalId, long nanoTime, int ordinal) {
    }

    /**
     * The deepest cause's simple class name, as the public classifier for a failure.
     *
     * <p>Walks to the same cause {@link #message(Throwable)} does, and deliberately so: reporting the
     * class of the wrapper while the message describes the cause would give a reader two facts about
     * two different exceptions under one event.</p>
     *
     * <p><b>Why the class and not the message.</b> A simple class name is a Java type name written in
     * a source file. It cannot contain a payload fragment, because nothing at runtime can put one
     * there — which is what makes it publishable under the information-disclosure constraint while
     * {@link Throwable#getMessage()} is not. The authenticated author may receive the separate
     * bounded/redacted {@link RuntimeActivityData.TextProjection}; this classifier remains useful for
     * branching and never becomes prose.</p>
     */
    private static String failureClass(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * The per-execution identity every published event is stamped with. {@code security} is the
     * ingress context (SEC-07); it is supplied once by the caller that started the traversal and is
     * never recomputed from graph content.
     */
    record ExecutionIdentity(ai.ravenroot.api.security.SecurityContext security, String engineId,
                             String graphVersion, UUID processInstanceId, UUID traversalId,
                             java.util.Map<String, String> nodeCatalogKeys, String deploymentId,
                             String workloadId) {
        ExecutionIdentity {
            java.util.Objects.requireNonNull(security, "security");
            nodeCatalogKeys = nodeCatalogKeys == null ? java.util.Map.of()
                    : java.util.Map.copyOf(nodeCatalogKeys);
        }

        /**
         * Compatibility constructor for the shape without {@code deploymentId}/{@code workloadId}
         * (ADR 0021 D5). Both default to {@code null}: a traversal assembled
         * this way never belonged to a deployment.
         */
        ExecutionIdentity(ai.ravenroot.api.security.SecurityContext security, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId,
                          java.util.Map<String, String> nodeCatalogKeys) {
            this(security, engineId, graphVersion, processInstanceId, traversalId, nodeCatalogKeys, null, null);
        }

        /**
         * Compatibility constructor for the shape without the catalog map. A traversal with no
         * map resolves every node to an absent catalog key, which is the honest answer rather than a
         * guess: an identity assembled without a graph has nothing to resolve against.
         */
        ExecutionIdentity(ai.ravenroot.api.security.SecurityContext security, String engineId,
                          String graphVersion, UUID processInstanceId, UUID traversalId) {
            this(security, engineId, graphVersion, processInstanceId, traversalId, java.util.Map.of());
        }

        /**
         * The node's catalog key, or {@code null} when there is none.
         *
         * <p>Absent covers three genuinely different situations and deliberately does not distinguish
         * them, because none of the three is a node type: a traversal-level event with no node at all;
         * a structural node (START, PASSTHROUGH, END) which has no behavior; and a behavior name the
         * {@code BehaviorRegistry} does not know, which is caller-supplied text and therefore exactly
         * the unbounded value that must never reach a metric label.
         */
        String catalogKeyFor(String nodeId) {
            return nodeId == null ? null : nodeCatalogKeys.get(nodeId);
        }

        UUID executionId() {
            return traversalId;
        }
    }
}
