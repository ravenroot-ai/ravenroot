package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentityKind;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.FailureRouteEdgeProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the <em>durable</em> diary holds when one terminal node completes twice in one traversal.
 *
 * <h2>The gap this closes, stated as it was measured rather than as it was assumed</h2>
 * <p>{@code CyclicTerminalPayloadTest} already establishes that a terminal can be entered twice, by
 * two independent routes, and it asserts two things about it: the {@code ExecutionMonitor}'s
 * <em>live, in-process</em> event stream, and the traversal's <em>result payload</em>. Neither is
 * durable. Nothing in that file constructs an {@code ExecutionStore}, an {@code ExecutionRecorder} or
 * a {@code ProcessInstance}, so nothing there says whether the PERS-01 aggregate and the append-only
 * journal survive the same graphs. This class covers that intersection by composing repeated-terminal
 * topologies with the durable store.</p>
 *
 * <h2>What was found: the diary holds, and the reason is identity, not luck</h2>
 * <p>All three topologies produce a coherent aggregate and a coherent journal. That is not a property of
 * the terminal and not a property of the join configuration — it is a property of
 * {@link ExecutionIdentitySource#nextNodeInvocationId()} being called <em>per dispatch</em>, so two
 * visits to one node are two {@link NodeInvocation}s with two identities rather than one row written
 * twice. {@link NodeInvocation}'s own Javadoc states this ("{@code invocationId} is unique per visit;
 * therefore fan-out, retries and re-entry never overwrite one another merely because nodeId matches")
 * and that sentence is the whole of the answer. Everything downstream inherits it: {@code Traversal}
 * keys invocations by {@code invocationId}, the journal's rows carry {@code invocationId} and not a
 * node id, and {@code DefaultRavenrootApplication.durableEventsAfter} resolves a row's node by joining
 * on that identity into a {@code Map<UUID, String>} — many invocations to one node, never the reverse.
 * </p>
 *
 * <h2>Why {@link #theDurableDiarySurvivesARepeatedTerminalOnlyBecauseIdentityIsPerVisitNotPerNode()}
 * is here and is the load-bearing test</h2>
 * <p>Everything above is a claim about <em>why</em> the first two tests are green, and a claim about a
 * reason is worth nothing while the first two tests would stay green with the reason removed. So the
 * reason is removed, in the one place it is injectable without touching production code: an
 * {@link ExecutionIdentitySource} that mints the terminal's identity <strong>per node</strong> — which
 * is exactly what a runtime written under the assumption "a terminal is entered at most once" would
 * do. The run then fails, and it fails in the aggregate, with {@code Duplicate invocationId}. That
 * makes the assumption a named, pinned, falsifiable thing instead of a sentence in a Javadoc, and it
 * is the sense in which this file is red-before-green: the property has been observed absent.</p>
 *
 * <h2>What this file deliberately does not assert</h2>
 * <p>Which of the two arrivals reaches the result payload. That is a race under {@code each}, it is
 * measured and left unasserted next door in {@code CyclicTerminalPayloadTest}, and re-litigating it
 * here would make a durability test hostage to a scheduler.</p>
 *
 * <h2>The path this file does not reach, and where it is measured</h2>
 * <p>Every topology here <strong>completes</strong>, and that is not incidental to the green result:
 * {@code GraphRunner.run} calls {@code executionCompleted()} only after the whole dispatch tree has
 * settled, so on this path no arrival can land after the traversal closed and the two registers had
 * no opportunity to diverge. On the failure path they do, deliberately —
 * {@code SurvivingBranchAfterFailedTraversalTest} builds a traversal that ends while a branch is still
 * in flight and measures the difference. Read the two together: this file is the half where agreement
 * was structurally guaranteed.</p>
 */
class RepeatedTerminalDurableDiaryTest {

    private static final String TENANT = "tenant-a";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String GRAPH_VERSION = "v1";
    private static final long BOUND_MILLIS = 20_000;

    /**
     * The {@code each} case is a genuine race — two arrivals reach the terminal concurrently — so one
     * green run is one sample, not a verdict. The cycle case is causally serialised and needs no
     * repetition, but is looped with it so the two halves of this file are read the same way.
     */
    private static final int REPEATS = 20;

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    // ------------------------------------------------------------------ topology 1: a cycle

    /**
     * {@code start -> probe -(retry)-> error -> probe}, plus {@code probe -(done)-> end}.
     *
     * <p>Lifted deliberately unchanged from {@code CyclicTerminalPayloadTest#cyclicThroughErrorTerminal},
     * so the two files exercise the <em>same</em> graph and this one cannot quietly succeed on an
     * easier variant. {@code probe} carries {@code joinPolicy=each} because the cycle gives it two
     * distinct predecessors and every such node is otherwise promoted to a fan-in.</p>
     */
    private static GraphDefinition cyclicThroughErrorTerminal() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, "probe", Map.of("joinPolicy", "each")),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"),
                new GraphEdge("probe", "error", "retry", Map.of()),
                GraphEdge.to("error", "probe"),
                new GraphEdge("probe", "end", "done", Map.of())));
    }

    /** Fails twice with {@code retry}, then succeeds with {@code done}: two passes through the terminal. */
    private static BehaviorRegistry twoLapsThenDone() {
        var calls = new AtomicInteger();
        return new BehaviorRegistry().register("probe", message -> {
            int call = calls.incrementAndGet();
            return CompletableFuture.completedFuture(call <= 2
                    ? new NodeResult("retry", "attempt-" + call, Map.of())
                    : new NodeResult("done", "final-" + call, Map.of()));
        });
    }

    @Test
    @DisplayName("a cycle back through a terminal leaves the aggregate and the journal coherent")
    void aCycleThroughATerminalKeepsTheDurableDiaryCoherent() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            Diary diary = runAndRead(cyclicThroughErrorTerminal(), twoLapsThenDone(),
                    ExecutionIdentitySource.randomUuids());
            assertACoherentDiaryWithTwoCompletionsOf("error", diary);
        }
    }

    // ------------------------------------------------------------- topology 2: an each merge

    /**
     * {@code start -> A|B -> merge -(routed)-> error}, with {@code each} on the ordinary merge.
     *
     * <p>Also lifted from {@code CyclicTerminalPayloadTest#eachOnAnOrdinaryMerge}, and the
     * more alarming of the two for this reason: <strong>the terminal declares
     * nothing</strong>. It has one predecessor and no properties, there is no cycle, and it is still
     * entered twice — because an {@code each} node anywhere upstream invokes its successors once per
     * arrival.</p>
     *
     * <p><strong>The mechanism arrives from imports; this particular graph no longer does.</strong>
     * The editor stamps {@code joinPolicy=each} onto every non-{@code START} node with
     * more than one predecessor when it serialises a legacy state-machine document, so an {@code each}
     * node upstream of a terminal is a shape nobody chose — that half is unchanged and is what the
     * test is about. What an import does <em>not</em> write is the named outcome below: a
     * bare edge into an {@code ERROR} node is the unhandled-failure route and is not taken on a
     * success, and a {@code PASSTHROUGH} answers {@code continue} and nothing else, so a successful
     * merge entering the <em>error</em> terminal is only expressible with an outcome the author named
     * and a {@link NodeKind#BEHAVIOR} that can produce it. An imported document would now reach
     * {@code END} through this mechanism, or {@code ERROR} through a declared failure route. So read
     * this graph as a hand-built instance of the import's mechanism, not as a transcript of an import.
     * The named outcome makes this a hand-built instance of the import mechanism rather than a
     * transcript of an import.</p>
     *
     * <p>The merge forwards payload and attributes untouched; that is the whole of the difference from
     * the passthrough it replaced. Kept identical to {@code CyclicTerminalPayloadTest}'s copy on
     * purpose: the two classes read the same run through different projections, and a divergence in
     * the graph would make that comparison meaningless.</p>
     */
    private static GraphDefinition eachOnAnOrdinaryMerge() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("A", "A"),
                GraphNode.behavior("B", "B"),
                new GraphNode("merge", NodeKind.BEHAVIOR, "merge", Map.of("joinPolicy", "each")),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "A"),
                GraphEdge.to("start", "B"),
                GraphEdge.to("A", "merge"),
                GraphEdge.to("B", "merge"),
                new GraphEdge("merge", "error", ROUTED, Map.of())));
    }

    /** @see #eachOnAnOrdinaryMerge() */
    private static final String ROUTED = "routed";

    private static BehaviorRegistry twoDistinguishableLaps() {
        return new BehaviorRegistry()
                .register("A", message -> CompletableFuture.completedFuture(NodeResult.continueWith("A-lap")))
                .register("B", message -> CompletableFuture.completedFuture(NodeResult.continueWith("B-lap")))
                .register("merge", message -> CompletableFuture.completedFuture(
                        new NodeResult(ROUTED, message.payload(), message.attributes())));
    }

    @Test
    @DisplayName("a fan-out into an each merge leaves the aggregate and the journal coherent on an untouched terminal")
    void anEachMergeAboveATerminalKeepsTheDurableDiaryCoherent() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            Diary diary = runAndRead(eachOnAnOrdinaryMerge(), twoDistinguishableLaps(),
                    ExecutionIdentitySource.randomUuids());
            assertACoherentDiaryWithTwoCompletionsOf("error", diary);
        }
    }

    // ------------------------------------------ topology 3: each on the error terminal itself

    /**
     * {@code start -> first|second -(failure route)-> error}, with {@code each} on the terminal.
     *
     * <p>The third fixture {@code CyclicTerminalPayloadTest} already builds, and the only one of the
     * three whose <em>shape in the diary</em> differs: the terminal is entered twice by two
     * <strong>failed</strong> invocations feeding two declared failure routes, rather than by
     * two successful ones. That distinction barely exists on the live stream — a {@code NODE_FAILED}
     * is a {@code NODE_FAILED} — but it is the whole of what the aggregate records differently, so a
     * durable measurement that skipped it would be measuring the join policy and not the diary.</p>
     *
     * <p>It is also the topology where {@code each} sits on the terminal itself rather than upstream
     * of it, which is the arrangement {@code ravenroot-ui/src/graph-document.js} stamps onto imported
     * legacy documents. Lifted unchanged from {@code CyclicTerminalPayloadTest#eachOnTheTerminal} for
     * the same reason as the two above: the two files must exercise one graph, not two similar ones.</p>
     */
    private static GraphDefinition eachOnTheErrorTerminalItself() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("first", "first"),
                GraphNode.behavior("second", "second"),
                new GraphNode("error", NodeKind.ERROR, null, Map.of("joinPolicy", "each")),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "first"),
                GraphEdge.to("start", "second"),
                new GraphEdge("first", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
                new GraphEdge("second", "error", null,
                        Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));
    }

    private static BehaviorRegistry twoFailingBranches() {
        return new BehaviorRegistry()
                .register("first", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-1")))
                .register("second", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("boom-2")));
    }

    @Test
    @DisplayName("two handled failures into an each terminal leave the aggregate and the journal coherent")
    void eachOnTheErrorTerminalKeepsTheDurableDiaryCoherentUnderTwoHandledFailures() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            Diary diary = runAndRead(eachOnTheErrorTerminalItself(), twoFailingBranches(),
                    ExecutionIdentitySource.randomUuids());
            assertACoherentDiaryWithTwoCompletionsOf("error", diary);

            // What is new here relative to the two topologies above: the terminal's two feeders are
            // recorded FAILED and the traversal still completes, because a declared failure route
            // changes what happens next and never what happened.
            Traversal traversal = diary.stored().state().traversals().values().iterator().next();
            for (String feeder : List.of("first", "second")) {
                NodeInvocation invocation = traversal.invocations().values().stream()
                        .filter(candidate -> feeder.equals(candidate.nodeId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no invocation recorded for '" + feeder
                                + "': " + describe(traversal)));
                assertEquals(NodeInvocationStatus.FAILED, invocation.status(),
                        () -> "a handled failure is still a failure in the diary: if this reads "
                                + "COMPLETED the aggregate has started recording the route taken "
                                + "instead of the outcome that happened. Recorded: "
                                + describe(traversal));
                assertEquals(List.of(NodeAttemptStatus.FAILED),
                        invocation.attempts().stream().map(NodeAttempt::status).toList(),
                        () -> "and its single attempt failed with it: " + invocation);
                assertEquals(Set.of(ExecutionEventType.NODE_STARTED.name(),
                                ExecutionEventType.NODE_FAILED.name(),
                                ExecutionEventType.EDGE_TRAVERSED.name()),
                        journalTypesFor(diary, invocation.invocationId()),
                        () -> "both faults must be journalled, not only the one whose payload reached "
                                + "the result -- the discarded arrival is what CyclicTerminalPayloadTest "
                                + "measures on the live stream, and this is the durable half of it");
            }
        }
    }

    private static Set<String> journalTypesFor(Diary diary, UUID invocationId) {
        return diary.records().stream()
                .filter(record -> invocationId.equals(record.envelope().invocationId()))
                .map(record -> record.envelope().eventType())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------- the assumption, named and falsified

    /**
     * The at-most-once assumption, injected on purpose at the node under test.
     *
     * <p>The identity source below is ordinary except for the terminal's <em>second</em> visit, which
     * it answers with the identity it minted for the first. That is the whole of "a terminal is
     * entered at most once, so its identity may be keyed by its node": nothing else changes, no
     * production code is touched, and every other node still gets a fresh identity per dispatch.</p>
     *
     * <p>The run fails in {@code Traversal.addInvocation}, not in the runner or journal. That method
     * is reached through the store's own {@code apply}, so the
     * refusal is the <em>persisted</em> aggregate's, not an in-memory convenience. The diary is
     * therefore not merely compatible with a twice-entered terminal; it actively refuses the shape a
     * runtime holding the assumption would write, which is why the first two tests are green and
     * would not silently stay green if that ever changed.</p>
     *
     * <p>The cycle graph is used because its visit order is causally serialised and so deterministic —
     * {@code start, probe, error, probe, error, probe, end}. Under {@code each} the two arrivals race,
     * and a positional identity source would then be reusing an identity for whichever node happened
     * to be dispatched fifth, which is a different test on every run.</p>
     */
    @Test
    @DisplayName("the diary survives a repeated terminal because invocation identity is per visit, not per node")
    void theDurableDiarySurvivesARepeatedTerminalOnlyBecauseIdentityIsPerVisitNotPerNode() {
        var visits = new ArrayDeque<>(List.of("start", "probe", "error", "probe", "error", "probe", "end"));
        var terminalIdentity = new UUID[1];
        ExecutionIdentitySource terminalKeyedByNode = kind -> {
            if (kind != ExecutionIdentityKind.NODE_INVOCATION) {
                return UUID.randomUUID();
            }
            String visiting = visits.isEmpty() ? "overflow" : visits.poll();
            if (!"error".equals(visiting)) {
                return UUID.randomUUID();
            }
            if (terminalIdentity[0] == null) {
                terminalIdentity[0] = UUID.randomUUID();
            }
            return terminalIdentity[0];
        };

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> runAndRead(cyclicThroughErrorTerminal(), twoLapsThenDone(), terminalKeyedByNode),
                "a runtime that keys the terminal's invocation identity by node rather than by visit "
                        + "must not be able to write a second completion into the diary unnoticed: if "
                        + "this run succeeds, the aggregate has stopped enforcing the identity "
                        + "invariant the two tests above silently depend on");

        assertTrue(String.valueOf(refused.getCause().getMessage()).contains("Duplicate invocationId"),
                () -> "the refusal must come from the identity invariant itself — Traversal.addInvocation "
                        + "rejecting a second row under one invocationId — because that is the named "
                        + "assumption under test. Any other failure means the run died for an unrelated "
                        + "reason and this test proves nothing: " + refused.getCause());
    }

    // ------------------------------------------- the collapse the result surface really does have

    /**
     * The one place a reader would build the at-most-once assumption honestly, named and pinned.
     *
     * <p>{@code GraphExecutionResult.visitedNodes()} is a {@code Set<String>}, so a terminal entered
     * twice appears once and no consumer can tell the two cases apart — and that value is not internal:
     * it reaches the CLI's {@code visited-nodes=} line and the HTTP result's {@code visitedNodes} array
     * unchanged. This is not a defect and this test is not asking for it to change; "which nodes ran" is
     * the question that field answers. What it is, is the exact shape of the previously implicit assumption,
     * living on a <em>public</em> surface with nothing saying so — so the answer is to say so, in
     * {@code GraphExecutionResult.visitedNodes()}'s own Javadoc, and to hold the two surfaces against one
     * traversal here so the sentence cannot rot.</p>
     *
     * <p>The pairing is the assertion. One is a collapse, the other is not, and the second is the one to
     * read when the question is "how many times".</p>
     */
    @Test
    @DisplayName("the result collapses repeat visits to one node id; only the durable diary keeps them apart")
    void theResultCollapsesRepeatVisitsWhileTheDiaryDoesNot() throws Exception {
        Diary diary = runAndRead(eachOnAnOrdinaryMerge(), twoDistinguishableLaps(),
                ExecutionIdentitySource.randomUuids());

        assertEquals(Set.of("start", "A", "B", "merge", "error"), diary.result().visitedNodes(),
                "visitedNodes reports the terminal once however many times it was entered, because it "
                        + "is a set of node ids; if this ever grows a second entry for one node the "
                        + "field has changed meaning and its consumers -- the CLI line and the HTTP "
                        + "array -- have changed with it");

        Traversal traversal = diary.stored().state().traversals().values().iterator().next();
        assertEquals(2, traversal.invocations().values().stream()
                        .filter(invocation -> "error".equals(invocation.nodeId())).count(),
                () -> "the same traversal that reported one visited 'error' recorded two invocations of "
                        + "it durably: that difference IS the answer to 'how many times', and it exists "
                        + "in exactly one of the two surfaces. Recorded: " + describe(traversal));
    }

    // ---------------------------------------------------------------- the coherence checks

    /**
     * Every durable fact that a second completion on one terminal could plausibly have corrupted.
     *
     * <p>Written as one helper applied to both topologies on purpose: the question is whether
     * the diary depends on the <em>route</em> by which a terminal is reached twice, and two topologies
     * checked against two different assertion sets could not answer it.</p>
     */
    private static void assertACoherentDiaryWithTwoCompletionsOf(String terminalNodeId, Diary diary) {
        StoredProcessInstance stored = diary.stored();
        ProcessInstance state = stored.state();

        assertEquals(ProcessInstanceStatus.COMPLETED, state.status(),
                "the process aggregate must reach COMPLETED: a traversal whose terminal completed "
                        + "twice still completed, and a diary left RUNNING would be a stuck instance "
                        + "no recovery sweep can distinguish from a crashed one");
        assertEquals(1, state.traversals().size(), "the fixture runs exactly one traversal");
        Traversal traversal = state.traversals().values().iterator().next();
        assertEquals(TraversalStatus.COMPLETED, traversal.status(), "the traversal must reach COMPLETED");

        List<NodeInvocation> terminalVisits = traversal.invocations().values().stream()
                .filter(invocation -> terminalNodeId.equals(invocation.nodeId()))
                .toList();
        assertEquals(2, terminalVisits.size(),
                () -> "the premise under test is that the terminal was entered twice AND that both "
                        + "visits were recorded durably; " + terminalVisits.size() + " means either the "
                        + "graph stopped exercising the case or one visit is missing from the diary, "
                        + "and the assertions below would prove nothing either way. Recorded: "
                        + describe(traversal));
        assertEquals(2, terminalVisits.stream().map(NodeInvocation::invocationId).distinct().count(),
                "the two visits must be two identities, not one row written twice");
        assertEquals(2, terminalVisits.stream().map(NodeInvocation::parentInvocationIds).distinct().count(),
                () -> "each visit must name the cause that actually dispatched it: two visits sharing "
                        + "one causal parent would mean the second's cause was lost or copied from the "
                        + "first, and the causal chain is the only thing that orders a journal whose "
                        + "rows carry no node id. Recorded: " + describe(traversal));

        for (NodeInvocation visit : terminalVisits) {
            assertEquals(NodeInvocationStatus.COMPLETED, visit.status(),
                    () -> "both visits completed, so both must be COMPLETED in the diary: " + visit);
            assertEquals(List.of(NodeAttemptStatus.COMPLETED),
                    visit.attempts().stream().map(NodeAttempt::status).toList(),
                    () -> "a second visit is a second invocation with its own first attempt, never a "
                            + "second attempt appended to the first visit — attempts are retries of one "
                            + "visit and would make a repeat entry look like a failure that was retried: "
                            + visit);
        }

        // The journal side. Rows carry invocationId and no node id by design, so a repeated terminal is
        // only readable through the invocation-to-node join DefaultRavenrootApplication performs.
        List<JournalRecord> records = diary.records();
        assertFalse(records.isEmpty(), "the run journalled nothing, so every journal assertion is vacuous");
        assertEquals(rangeFromOne(records.size()),
                records.stream().map(JournalRecord::streamSequence).toList(),
                () -> "this instance's stream sequence must be contiguous from one and in read order: a "
                        + "gap or a repeat is how a consumer reconstructing one execution tells a "
                        + "truncated read from a complete one, and two concurrent completions on one "
                        + "node are exactly the case that could interleave it. Got: "
                        + records.stream().map(JournalRecord::streamSequence).toList());

        Map<UUID, String> nodeByInvocation = diary.nodeByInvocation();
        for (JournalRecord record : records) {
            EventEnvelope envelope = record.envelope();
            if (envelope.invocationId() == null) {
                continue;
            }
            assertNotNull(nodeByInvocation.get(envelope.invocationId()),
                    () -> "every journalled invocation must resolve to a node through the aggregate's "
                            + "InvocationAdded binding, which is the only route a reader has to a row's "
                            + "node id; an unresolvable row is a permanently unreadable one on an "
                            + "append-only log: " + envelope.eventType() + " " + envelope.invocationId());
        }

        Set<UUID> terminalIds = terminalVisits.stream().map(NodeInvocation::invocationId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertEquals(terminalIds, journalledIds(records, ExecutionEventType.NODE_STARTED, terminalIds),
                "each visit to the terminal must have its own NODE_STARTED row");
        assertEquals(terminalIds, journalledIds(records, ExecutionEventType.NODE_COMPLETED, terminalIds),
                () -> "each visit to the terminal must have its own NODE_COMPLETED row: one row for two "
                        + "completions would make the journal claim the terminal ran once, which is the "
                        + "exact reading the result payload already cannot correct because it reports a "
                        + "single value either way");
    }

    private static Set<UUID> journalledIds(List<JournalRecord> records, ExecutionEventType type,
                                           Set<UUID> among) {
        var found = new LinkedHashSet<UUID>();
        for (JournalRecord record : records) {
            UUID invocationId = record.envelope().invocationId();
            if (type.name().equals(record.envelope().eventType()) && among.contains(invocationId)) {
                assertTrue(found.add(invocationId),
                        () -> "two " + type + " rows for one invocation identity: " + invocationId);
            }
        }
        return found;
    }

    private static List<Long> rangeFromOne(int size) {
        var expected = new ArrayList<Long>(size);
        for (long value = 1; value <= size; value++) {
            expected.add(value);
        }
        return expected;
    }

    private static String describe(Traversal traversal) {
        var described = new LinkedHashMap<String, List<String>>();
        traversal.invocations().values().forEach(invocation -> described
                .computeIfAbsent(invocation.nodeId(), ignored -> new ArrayList<>())
                .add(invocation.invocationId() + "=" + invocation.status()));
        return described.toString();
    }

    // ------------------------------------------------------------------------ the fixture

    /**
     * One run's durable residue: the aggregate as the store holds it, this instance's journal, and the
     * non-durable result beside them — the last only so that
     * {@link #theResultCollapsesRepeatVisitsWhileTheDiaryDoesNot()} can compare the two surfaces against
     * <em>one</em> traversal rather than against two runs that might have differed.
     */
    private record Diary(StoredProcessInstance stored, List<JournalRecord> records,
                         Map<UUID, String> nodeByInvocation, GraphExecutionResult result) {
    }

    /**
     * Creates the instance, takes its lease and runs one traversal under it — the production wiring, shared
     * with {@code JournalCausationTest} so that "what a real recorder writes" means the same thing in
     * both files.
     */
    private Diary runAndRead(GraphDefinition graph, BehaviorRegistry behaviors,
                             ExecutionIdentitySource identities) throws Exception {
        var store = new InMemoryExecutionStore();
        var joins = new InMemoryJoinStore();
        SecurityContext security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);
        GraphExecutionResult result;

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor(),
                     identities, joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                result = runner.execute(security, processInstanceId, traversalId, "in", GRAPH_VERSION,
                        null, null, recorder).toCompletableFuture()
                        .get(BOUND_MILLIS, TimeUnit.MILLISECONDS);
            }
        }

        StoredProcessInstance stored = await(store.load(key));
        List<JournalRecord> records = await(store.readJournal(TENANT, 0, 1_000)).stream()
                .filter(record -> record.key().equals(key))
                .toList();
        return new Diary(stored, records, nodeByInvocation(stored), result);
    }

    private static long createRunningInstance(ExecutionStore store, ExecutionKey key, UUID traversalId,
                                              String startNodeId) {
        var traversal = new Traversal(traversalId, startNodeId, TraversalStatus.ACCEPTED, Map.of());
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, traversal));
        var created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin(GRAPH_VERSION)))
                .build()));
        return await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build())).revision();
    }

    /**
     * The invocation-to-node binding, built exactly the way
     * {@code DefaultRavenrootApplication.loadInvocationNodeNames} builds it — keyed by
     * {@code invocationId}, so many invocations may map to one node and never the reverse. Rebuilt
     * here rather than reached through the application because the property under test belongs to the
     * shape of the recorded data, not to that class's plumbing.
     */
    private static Map<UUID, String> nodeByInvocation(StoredProcessInstance stored) {
        var bindings = new HashMap<UUID, String>();
        for (Traversal traversal : stored.state().traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                bindings.put(invocation.invocationId(), invocation.nodeId());
            }
        }
        return Map.copyOf(bindings);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
