package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
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
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one shape where the durable diary and the live event stream really do disagree about how
 * many times a node ran.
 *
 * <h2>Why the success-path answer does not extend to here</h2>
 * <p>{@code RepeatedTerminalDurableDiaryTest} measured both registers on two topologies that enter a
 * terminal twice and finds them in full agreement. That result holds <strong>on the success path</strong>
 * and for a structural reason rather than a lucky one: {@code GraphRunner.run} only calls
 * {@code executionCompleted()} after the whole dispatch tree has settled, so by the time the traversal
 * is terminal there is no arrival left that could land afterwards. Agreement is therefore enforced
 * by the completion ordering.</p>
 *
 * <p>On the failure path the reason evaporates, and {@code allOrFirstFailure} says so in as many words:
 * the aggregate "permits {@code FAILED} with work still in flight", so a traversal ends on the first
 * branch that fails while its siblings are still inside their nodes. Those siblings finish, and try to
 * record it, and the three guards on {@code GraphRunner.ExecutionState.terminal} refuse them. This file
 * is the measurement of what that refusal costs, on both registers, on one run.</p>
 *
 * <h2>How the race was removed, because a test that waits is not a measurement</h2>
 * <p>The scenario is a race by nature — a branch must fail <em>while</em> another is unfinished — and
 * building it out of sleeps would produce a flaky test, which is worse than none. Nothing here sleeps
 * and nothing here polls. Both branch behaviours return a {@link CompletableFuture} that the
 * <em>test thread</em> completes, so the ordering is imposed rather than awaited:</p>
 * <ol>
 *   <li>both behaviours count down a latch on entry, and the test waits for both. Entry happens strictly
 *       after {@code ExecutionState.nodeStarted} has returned, so at this point both branches are
 *       durably {@code RUNNING} in the aggregate and both have a {@code NODE_STARTED} row;</li>
 *   <li>the test fails {@code doomed}'s future, then waits for the traversal's own stage to complete
 *       exceptionally. {@code executionFailed()} — which sets {@code terminal} — runs strictly before
 *       that stage completes, so observing the failure <em>is</em> observing the closure;</li>
 *   <li>only then does the test complete {@code survivor}'s future. Every write attempted from here on
 *       is by construction post-closure;</li>
 *   <li>the test waits for {@code late}'s {@code NODE_FAILED} on the monitor's stream. That call sits
 *       after {@code ExecutionState.nodeFailed} in {@code GraphRunner.run}, so seeing it proves the
 *       guarded write was already attempted and skipped — the recorder is still open at that moment
 *       and is closed after, which is what makes the read below a read of the durable residue.</li>
 * </ol>
 * <p>The whole scenario is therefore deterministic without a single timing assumption; the only
 * timeouts present are failure bounds, never synchronisation.</p>
 *
 * <h2>What was measured</h2>
 * <p>The reading method is unchanged and used for the same reason: runner and recorder are closed
 * <em>before</em> anything is read, and everything is read back through {@link ExecutionStore}, so what
 * is measured is the durable record and not an in-memory view of it.</p>
 *
 * <p>The divergence is real and it is not subtle. See
 * {@link #theLiveStreamKeepsWhatTheDiaryDropsForABranchThatOutlivedItsTraversal()} for the counts, and
 * {@link #theRecoverySweepCanTellTheFrozenBranchApartAndStillCanDoNothingWithIt()} for what the
 * existing recovery loop does when handed the residue.</p>
 *
 * <h2>The durable and live paths diverge</h2>
 * <p>The journal receives <strong>nothing</strong> for a surviving branch after closure:
 * {@code record(transitions, events)} is never reached, so the transitions and journalled events are
 * dropped by the same {@code return}. Only {@code ExecutionMonitor}'s live, in-process stream keeps
 * them. {@link #theJournalLosesTheEventsToo()} measures that divergence, and
 * {@link #theDiscardedAlternativeWritesJournalRowsThatResolveToNoNode()} constructs the alternative
 * of publishing the event while skipping the transition so its consequences remain executable.
 * <strong>No behaviour was changed</strong>: whether a branch that outlives its traversal should be
 * recorded at all is outside this test's scope.</p>
 *
 * @see RepeatedTerminalDurableDiaryTest the success-path half of the same question
 */
class SurvivingBranchAfterFailedTraversalTest {

    private static final String TENANT = "tenant-a";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String GRAPH_VERSION = "v1";
    private static final long BOUND_MILLIS = 20_000;

    /**
     * The scenario is deterministic by construction, so one run is already a verdict rather than a
     * sample. Repeated anyway, cheaply, because "deterministic by construction" is a claim about the
     * code above and this is what would catch it being wrong.
     */
    private static final int REPEATS = 10;

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * {@code start -> doomed | survivor -> late}.
     *
     * <p>{@code doomed} fails with no failure route, which is the shortest way to end a traversal:
     * {@code GraphRunner.run} rethrows, {@code allOrFirstFailure} propagates immediately instead of
     * waiting for the sibling, and {@code executionFailed()} closes the traversal. {@code survivor} is
     * the branch still in flight at that moment. {@code late} exists so that the third guard is
     * exercised too: it is dispatched entirely after the closure, so its {@code nodeStarted} is
     * refused as well as its {@code nodeFailed}, and it is the node that appears on one register and
     * not the other at all.</p>
     *
     * <p>{@code end} is present and unreachable. A traversal that fails never reaches a terminal, and
     * wiring one in would only add a node whose absence from both registers proves nothing.</p>
     */
    private static GraphDefinition branchThatOutlivesItsTraversal() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("doomed", "doomed"),
                GraphNode.behavior("survivor", "survivor"),
                GraphNode.behavior("late", "late"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "doomed"),
                GraphEdge.to("start", "survivor"),
                GraphEdge.to("survivor", "late")));
    }

    // --------------------------------------------------------------- the two registers, counted

    /**
     * The count each register reports for the same run, and the node they disagree about.
     *
     * <p>Live stream: four nodes ran — {@code start}, {@code doomed}, {@code survivor}, {@code late} —
     * each with a start and a settle event. Durable diary: three, and one of those three is left
     * unresolved forever. The node they disagree about is {@code late}, which the monitor reports
     * started and failed and which the aggregate and the journal have never heard of.</p>
     *
     * <p>{@code survivor} is the more interesting of the two, because it does not disappear — it
     * <em>freezes</em>. Its {@code NODE_STARTED} was journalled before the closure and its completion
     * was not, so the durable record of a finished process contains an invocation stuck at
     * {@code RUNNING} with a {@code RUNNING} attempt, and a journal row whose settle never arrives.
     * That is the true shape of the divergence, and it is why "the diary undercounts by one" is only
     * half of it. What the recovery loop then does with that residue is measured in
     * {@link #theRecoverySweepCanTellTheFrozenBranchApartAndStillCanDoNothingWithIt()}.</p>
     */
    @Test
    @DisplayName("a branch that outlives a failed traversal is on the live stream and absent from the diary")
    void theLiveStreamKeepsWhatTheDiaryDropsForABranchThatOutlivedItsTraversal() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            Measurement measured = runABranchPastTheClosure();

            assertEquals(Set.of("start", "doomed", "survivor", "late"), measured.nodesOnLiveStream(),
                    () -> "the live stream is the register that keeps everything: if a node stops "
                            + "appearing here the guards have started suppressing the monitor too, "
                            + "which is the opposite of what ExecutionState.terminal promises. Seen: "
                            + measured.describeStream());
            assertEquals(Set.of("start", "doomed", "survivor"), measured.nodesInJournal(),
                    () -> "the diary must be missing exactly 'late' -- the node whose entire lifetime "
                            + "happened after the traversal closed. If 'late' appears here the guards "
                            + "no longer skip post-closure writes and this measurement is stale; if a "
                            + "node other than 'late' is missing, the loss is wider than the closure "
                            + "explains. Journalled: " + measured.describeJournal());

            assertEquals(1, measured.liveStreamCount("late", ExecutionEventType.NODE_STARTED),
                    "the divergent node ran once as far as any operator watching could tell");
            assertEquals(1, measured.liveStreamCount("late", ExecutionEventType.NODE_FAILED),
                    "and it settled once, with its failure fully described on the stream");
            assertEquals(0, measured.journalRowsFor("late"),
                    () -> "and the durable diary records it zero times: the two registers disagree "
                            + "about 'late' by exactly one execution. Journalled: "
                            + measured.describeJournal());
            assertFalse(measured.aggregateNodes().contains("late"),
                    () -> "the aggregate has no invocation for it either, so nothing durable can even "
                            + "resolve the identity the journal would have carried: "
                            + measured.describeAggregate());

            // The frozen branch. Recorded as started, never resolved, in a traversal that is over.
            NodeInvocation survivor = measured.invocationOf("survivor");
            assertEquals(NodeInvocationStatus.RUNNING, survivor.status(),
                    () -> "the surviving branch completed on the live stream and is left RUNNING in "
                            + "the aggregate forever, inside a FAILED traversal. This is the durable "
                            + "residue the divergence leaves behind, and what the recovery loop can "
                            + "and cannot do with it is measured separately in this file: "
                            + measured.describeAggregate());
            assertEquals(List.of(NodeAttemptStatus.RUNNING),
                    survivor.attempts().stream().map(NodeAttempt::status).toList(),
                    "its attempt is frozen with it, for the same reason and in the same write");
            assertEquals(1, measured.liveStreamCount("survivor", ExecutionEventType.NODE_COMPLETED),
                    "while the live stream saw it complete, which is the whole disagreement in one node");
            assertEquals(1, measured.journalRowsFor("survivor"),
                    () -> "and the journal holds exactly its NODE_STARTED, written before the closure, "
                            + "with no settle row that will ever follow it: " + measured.describeJournal());
            assertEquals(Set.of(ExecutionEventType.NODE_STARTED.name()),
                    measured.journalTypesFor("survivor"),
                    "the surviving row is the start, not the completion");

            assertEquals(TraversalStatus.FAILED, measured.traversal().status(),
                    "the premise: the traversal really did reach a terminal status by failure");
            assertEquals(ProcessInstanceStatus.FAILED, measured.stored().state().status(),
                    "and so did the process instance");
        }
    }

    /**
     * The guard drops both durable representations of a post-closure branch.
     *
     * <p>The guard returns before {@code record(transitions, events)} is reached, so both halves of
     * that call are skipped: the post-closure branch contributes <em>zero</em> journal rows as well as
     * zero aggregate transitions. Only the in-process monitor sees its node events.</p>
     *
     * <p>This is asserted separately from the count above because it is a different invariant. The
     * assertion is on the ratio rather than on absolute numbers: whatever the graph, a post-closure
     * node has some events on the live stream and none in the journal.</p>
     *
     * <p>The behaviour is deliberate and outside this test's scope.</p>
     */
    @Test
    @DisplayName("the guards drop the journal events too, not only the aggregate transitions")
    void theJournalLosesTheEventsToo() throws Exception {
        Measurement measured = runABranchPastTheClosure();

        assertTrue(measured.liveStreamCount("late", ExecutionEventType.NODE_STARTED)
                        + measured.liveStreamCount("late", ExecutionEventType.NODE_FAILED) > 0,
                "premise: the post-closure node did reach the monitor, so 'still published' is true "
                        + "of exactly one register");
        assertEquals(0, measured.journalRowsFor("late"),
                () -> "'only the aggregate write is skipped' is measurably false for the journal: a "
                        + "branch dispatched after its traversal closed writes no journal row either, "
                        + "because ExecutionState.nodeStarted returns before record(transitions, events) "
                        + "and that one call carries both. Journalled: " + measured.describeJournal());
        assertEquals(0, measured.aggregateRowsFor("late"),
                "and no aggregate row, which is the half the comment did describe");

        // The journal that remains must still be internally readable: a contiguous stream with every
        // row resolvable to a node. A dropped settle is a gap in the story, never a gap in the log.
        List<Long> sequences = measured.records().stream().map(JournalRecord::streamSequence).toList();
        assertEquals(rangeFromOne(sequences.size()), sequences,
                () -> "the rows that were written must still be contiguous from one: a traversal that "
                        + "closed early must not leave holes in the sequence, or a consumer cannot tell "
                        + "a truncated read from a complete one. Got: " + sequences);
        for (JournalRecord record : measured.records()) {
            EventEnvelope envelope = record.envelope();
            if (envelope.invocationId() == null) {
                continue;
            }
            assertNotNull(measured.nodeByInvocation().get(envelope.invocationId()),
                    () -> "every surviving row must still resolve to a node through the aggregate's "
                            + "InvocationAdded binding; an unresolvable row would mean an event was "
                            + "journalled whose invocation the aggregate never accepted, which is a "
                            + "wider defect than the one measured here: " + envelope.eventType());
        }
    }

    /**
     * The three guards are not written the same way, and the difference is worth naming.
     *
     * <p>{@code nodeCompleted} and {@code nodeFailed} test {@code terminal} as their first statement
     * and return having touched nothing. {@code nodeStarted} does {@code visitedNodes.add(nodeId)}
     * <em>before</em> its guard, so the runner's in-memory set of "nodes that ran" does include a node
     * dispatched after the closure — as does {@code state.defaultedNodes}, {@code state.bypassedNodes}
     * and either terminal-payload field, all written in {@code run()} after the guarded call returned.</p>
     *
     * <p>That looks like a leak and is inert, for one reason only: those fields are read at exactly one
     * place, the {@link GraphExecutionResult} constructed on the success branch of {@code run}, and the
     * guards can only fire after {@code executionFailed()}, which returns a failed stage and no result.
     * So the leaked state has no reader. The property the inertness depends on — a traversal that
     * closed by failure yields no result — is pinned by the shared fixture, and therefore by all five
     * tests in this file: the fixture reads what the stage actually produced, so any of them fails if
     * a result ever appears. This test states the property in its own name and asserts it directly,
     * which is a signpost rather than the only guard. It matters because if the property ever stopped
     * holding, the leak would become an over-count on a public surface, in the opposite direction from
     * the under-count this file measures.</p>
     */
    @Test
    @DisplayName("a failed traversal yields no result, which is what keeps the guards' unguarded prefix inert")
    void aFailedTraversalYieldsNoResultSoTheLeakedVisitedSetHasNoReader() throws Exception {
        Measurement measured = runABranchPastTheClosure();

        assertNotNull(measured.failure(), "premise: the traversal must have failed for the guards to fire");
        assertEquals(1, measured.liveStreamCount("late", ExecutionEventType.NODE_STARTED),
                "premise: a node really was dispatched after the closure, so nodeStarted's unguarded "
                        + "prefix really did add it to visitedNodes");

        // The assertion this test exists for, and the only one in this file that reads the stage's
        // value rather than the store: the object that would have exposed the leaked set was never
        // constructed. Read out of the stage in the fixture, not assumed.
        assertNull(measured.result(),
                () -> "a traversal closed by executionFailed() must yield no GraphExecutionResult. That "
                        + "is the whole of why the unguarded prefixes are inert: visitedNodes, "
                        + "defaultedNodes, bypassedNodes and the terminal payload fields have exactly "
                        + "one reader, and it is the result built on the success branch of run(). If a "
                        + "failed traversal ever starts producing one, 'late' -- which ran and is in "
                        + "neither durable register -- appears on a public surface, and the divergence "
                        + "this file measures reverses into an over-count. Got: " + measured.result());
    }

    // ------------------------------------------- the counterfactual, built rather than remembered

    /**
     * The counterfactual guard variant, constructed so its effects are executable and measurable.
     *
     * <p>{@code ExecutionState.terminal}'s Javadoc argues that publishing the event while skipping the
     * transition would be <em>worse</em> than dropping both, and the argument only counts if the
     * reader can see it happen. This fixture makes the counterfactual rerunnable without changing
     * production code: the discarded design's call
     * is literally {@code record(List.of(), events)}, and {@link ExecutionRecorder#record} is public.
     * The fixture hands it the recorder in exactly the state the guards see — traversal closed, fence
     * still held — and it performs that one call for an invocation the aggregate was never told
     * about, which is what a post-closure branch is.</p>
     *
     * <p>Everything else about the constructed row is <em>correct</em> on purpose: it carries this
     * instance's tenant, process and traversal, and its causation names a real row of this same
     * journal. The only defect left is the one under discussion, so the measurement cannot be
     * explained by anything incidental.</p>
     *
     * <p>What it measures: the store accepts the write — there is no transition, so nothing reaches
     * {@code requireNonTerminal} to refuse it — and the journal grows two rows that resolve, through
     * the {@code InvocationAdded} binding that is the only route a reader has, to <strong>no node at
     * all</strong>. On an append-only log they are unreadable forever. Production avoids that cost by
     * dropping both halves; the constructed variant demonstrates the consequence directly.</p>
     */
    @Test
    @DisplayName("publishing the event without the transition writes journal rows that resolve to no node")
    void theDiscardedAlternativeWritesJournalRowsThatResolveToNoNode() throws Exception {
        UUID orphanInvocation = UUID.randomUUID();
        UUID orphanAttempt = UUID.randomUUID();

        Measurement measured = runABranchPastTheClosure((recorder, key, traversalId, store) -> {
            // A cause that really is in this journal, so the row's only fault is the unbound invocation.
            UUID cause = await(store.readJournal(TENANT, 0, 1_000)).stream()
                    .filter(record -> record.key().equals(key))
                    .map(record -> record.envelope().eventId())
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("the journal was empty before the counterfactual"));
            UUID startedId = UUID.randomUUID();
            recorder.record(List.of(), List.of(
                    orphanEnvelope(recorder, traversalId, ExecutionEventType.NODE_STARTED, startedId,
                            cause, orphanInvocation, orphanAttempt),
                    orphanEnvelope(recorder, traversalId, ExecutionEventType.NODE_FAILED, UUID.randomUUID(),
                            startedId, orphanInvocation, orphanAttempt)));
        });

        List<JournalRecord> orphans = measured.records().stream()
                .filter(record -> orphanInvocation.equals(record.envelope().invocationId()))
                .toList();
        assertEquals(2, orphans.size(),
                () -> "the counterfactual write must have been accepted: a batch carrying only events "
                        + "never reaches the aggregate, so nothing refuses it even on a closed "
                        + "traversal. If this is zero the variant was not built and the rest of this "
                        + "test proves nothing. Journalled: " + measured.describeJournal());
        for (JournalRecord orphan : orphans) {
            assertNull(measured.journalledNode(orphan),
                    () -> "and this is what it costs: the row carries an invocationId the aggregate "
                            + "never accepted, the envelope carries no node id by design, and "
                            + "InvocationAdded is the only binding a reader has -- so the row can never "
                            + "be resolved to a node, on a log that can never be amended. Resolved to: "
                            + measured.journalledNode(orphan));
        }
        assertFalse(measured.aggregateNodes().contains("late"),
                "and the aggregate is unchanged by it, which is the half that makes the row unreadable");
    }

    /** The envelope shape {@code GraphRunner.ExecutionState.envelope} builds, for an unbound invocation. */
    private static EventEnvelope orphanEnvelope(ExecutionRecorder recorder, UUID traversalId,
                                                ExecutionEventType type, UUID eventId, UUID causationId,
                                                UUID invocationId, UUID attemptId) {
        return EventEnvelope.of(eventId, recorder.tenantId(), type.name(), recorder.processInstanceId(),
                traversalId, invocationId, attemptId, causationId, "request-421", GRAPH_VERSION,
                java.time.Instant.now(), OpaquePayload.empty("application/vnd.ravenroot.execution-event"));
    }

    // ------------------------------------------------- what recovery actually does with the residue

    /**
     * The counterfactual guard variant, measured instead of asserted from reading the code.
     *
     * <p>A recovery sweep can distinguish the frozen branch from a crashed worker even though
     * {@code ExecutionRecoveryService.recover} switches on {@code attempt.status()} alone:
     * <strong>"the code does not read the field" is not "the field is not there"</strong>. The
     * discriminator is durable, permanent, and written
     * beside the frozen invocation: the traversal's own status. An ordinary crash leaves it
     * {@code RUNNING}, because nobody ever wrote its failure; this residue is {@code FAILED}.</p>
     *
     * <p>What the residue actually costs is worse than a misclassification, and this test measures it
     * on the <em>real</em> residue rather than on a lookalike:</p>
     * <ol>
     *   <li>the crash-shaped instance is claimed and <strong>parked</strong> — the ordinary, ordered
     *       outcome, an attempt held for a human decision;</li>
     *   <li>the frozen instance is claimed too — neither claim query filters on the traversal's status
     *       — and then <strong>nothing can be done with it</strong>: park and dispatch both write
     *       through the aggregate, and both meet {@code ProcessInstance.requireNonTerminal}. The
     *       exception leaves {@code sweepOnce()} altogether;</li>
     *   <li>so every other item claimed in that tenant's batch is abandoned undecided — which
     *       {@code declarationOf}'s own Javadoc names as the one outcome worse than either decision,
     *       "because nothing records that a decision was owed".</li>
     * </ol>
     *
     * <p><strong>The fault is latent, and saying so is part of the measurement.</strong>
     * {@code sweepOnce()} has no production caller in this repository today — only tests and the
     * absent scheduler. It is one wiring step away, not a live incident, and this test is the record
     * of the current behaviour rather than a demand that it change.</p>
     */
    @Test
    @DisplayName("recovery parks a crashed attempt and aborts on the frozen one, abandoning the rest of the batch")
    void theRecoverySweepCanTellTheFrozenBranchApartAndStillCanDoNothingWithIt() throws Exception {
        Measurement measured = runABranchPastTheClosure();

        // The durable discriminator has both required values: the traversal is FAILED while the
        // surviving attempt remains RUNNING.
        assertEquals(TraversalStatus.FAILED, measured.traversal().status(),
                "the frozen residue's traversal is FAILED, and that is written durably beside the "
                        + "invocation the sweep will claim");
        NodeInvocation survivor = measured.invocationOf("survivor");
        assertEquals(NodeAttemptStatus.RUNNING, survivor.attempts().get(0).status(),
                "premise: the frozen attempt is RUNNING, which is exactly the status the claim query "
                        + "admits and the status recovery treats as the crash ambiguity");

        // (1) The same residue shape with the traversal still RUNNING: an ordinary crash. Its own
        // store, because an ordinary crash has nothing to do with this residue and putting the two in
        // one sweep would measure the abort instead of the park.
        var crashStore = new InMemoryExecutionStore();
        var crashKey = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID crashAttemptId = crashShapedRunningAttempt(crashStore, crashKey, survivor);
        List<RecoveryOutcome> crashOutcomes = sweepOver(crashStore).sweepOnce();
        RecoveryOutcome.Parked parked = crashOutcomes.stream()
                .filter(RecoveryOutcome.Parked.class::isInstance)
                .map(RecoveryOutcome.Parked.class::cast)
                .filter(outcome -> outcome.key().equals(crashKey))
                .findFirst()
                .orElse(null);
        assertNotNull(parked, () -> "a crashed worker's RUNNING attempt, in a traversal still RUNNING, "
                + "must reach an ordered outcome: it is parked for a human. Got: " + crashOutcomes);
        assertEquals(crashAttemptId, parked.attemptId(), "and it is that attempt that was parked");

        // (2) Now the real residue, with one more crash-shaped instance behind it in the same tenant.
        // The store keeps instances in insertion order and the residue was created first, so both are
        // claimed in one batch and the residue is decided first.
        var strandedKey = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID strandedAttemptId = crashShapedRunningAttempt(measured.store(), strandedKey, survivor);
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> sweepOver(measured.store()).sweepOnce(),
                "the frozen attempt is claimable -- neither InMemoryExecutionStore#scheduledAttempts "
                        + "nor SqliteExecutionStore#claimableAttempts filters on the traversal's status "
                        + "-- and once claimed it can be neither dispatched nor parked, because both "
                        + "write through the aggregate and meet requireNonTerminal. If this stops "
                        + "throwing, the residue has become actionable and recovery behavior has changed");
        assertTrue(String.valueOf(refused.getMessage()).contains("terminal")
                        || String.valueOf(refused.getMessage()).contains("FAILED"),
                () -> "the refusal must be the aggregate's own terminal-state guard reaching the "
                        + "caller, not an unrelated fault: " + refused);

        // (3) And the refusal escapes sweepOnce, so the rest of the batch is abandoned rather than
        // decided. The item behind the residue was claimed and then never looked at.
        NodeAttempt stranded = attemptOf(await(measured.store().load(strandedKey)).state(), strandedAttemptId);
        assertEquals(NodeAttemptStatus.RUNNING, stranded.status(),
                () -> "the item behind the residue in the same tenant's batch was never decided: it is "
                        + "still RUNNING rather than PARKED. That is the real cost of the residue -- "
                        + "not a node misread, but a sweep that stops and leaves work with no record "
                        + "that a decision was owed, which declarationOf's own Javadoc calls the "
                        + "outcome worse than either decision. Got: " + stranded);
    }

    private static ExecutionRecoveryService sweepOver(ExecutionStore store) {
        return new ExecutionRecoveryService(store, List.of(TENANT), "recovery-1", 10, TTL,
                RepeatabilityDeclarations.NONE_DECLARED, RecoveryDispatcher.NONE);
    }

    /**
     * A second instance carrying the residue's exact shape — one invocation {@code RUNNING} with one
     * {@code RUNNING} attempt — but with the traversal and the process left {@code RUNNING}, which is
     * what an ordinary crash leaves behind because nobody was alive to write the failure.
     *
     * <p>The transition list is the one {@code GraphRunner.ExecutionState.nodeStarted} writes, in its
     * order, so the two instances differ in the traversal's status and in nothing else.</p>
     */
    private static UUID crashShapedRunningAttempt(ExecutionStore store, ExecutionKey key,
                                                  NodeInvocation shapedLike) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        long revision = createRunningInstance(store, key, traversalId, "start");
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, shapedLike.nodeId(), Set.of(),
                                NodeInvocationStatus.SCHEDULED, List.of(), shapedLike.command())))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        return attemptId;
    }

    private static NodeAttempt attemptOf(ProcessInstance state, UUID attemptId) {
        for (Traversal traversal : state.traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                for (NodeAttempt attempt : invocation.attempts()) {
                    if (attempt.attemptId().equals(attemptId)) {
                        return attempt;
                    }
                }
            }
        }
        throw new AssertionError("no attempt " + attemptId + " in " + state);
    }

    // ------------------------------------------------------------------------ the fixture

    /**
     * One run's residue on both registers, plus the failure that closed the traversal.
     *
     * <p>{@code store} and {@code key} are carried so that
     * {@link #theRecoverySweepCanTellTheFrozenBranchApartAndStillCanDoNothingWithIt()} can submit the
     * <em>real</em> residue to the recovery loop rather than a hand-built lookalike. {@code result} is
     * always {@code null} on this scenario and is carried on purpose — see the third test.</p>
     */
    private record Measurement(ExecutionStore store, ExecutionKey key, StoredProcessInstance stored,
                               List<JournalRecord> records, Map<UUID, String> nodeByInvocation,
                               List<ExecutionEvent> stream, Throwable failure,
                               GraphExecutionResult result) {

        Traversal traversal() {
            return stored.state().traversals().values().iterator().next();
        }

        Set<String> aggregateNodes() {
            return traversal().invocations().values().stream()
                    .map(NodeInvocation::nodeId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        NodeInvocation invocationOf(String nodeId) {
            return traversal().invocations().values().stream()
                    .filter(invocation -> nodeId.equals(invocation.nodeId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no invocation recorded for '" + nodeId
                            + "'; recorded: " + describeAggregate()));
        }

        long aggregateRowsFor(String nodeId) {
            return traversal().invocations().values().stream()
                    .filter(invocation -> nodeId.equals(invocation.nodeId()))
                    .count();
        }

        Set<String> nodesOnLiveStream() {
            var nodes = new LinkedHashSet<String>();
            stream.stream().map(ExecutionEvent::nodeId).filter(java.util.Objects::nonNull).forEach(nodes::add);
            return nodes;
        }

        long liveStreamCount(String nodeId, ExecutionEventType type) {
            return stream.stream()
                    .filter(event -> event.type() == type && nodeId.equals(event.nodeId()))
                    .count();
        }

        /**
         * The journal carries no node id; the aggregate's {@code InvocationAdded} binding is the only
         * route. A row with no {@code invocationId} at all is a traversal-level row and belongs to no
         * node, so it resolves to nothing rather than to a missing binding.
         */
        String journalledNode(JournalRecord record) {
            UUID invocationId = record.envelope().invocationId();
            return invocationId == null ? null : nodeByInvocation.get(invocationId);
        }

        Set<String> nodesInJournal() {
            var nodes = new LinkedHashSet<String>();
            for (JournalRecord record : records) {
                String nodeId = journalledNode(record);
                if (nodeId != null) {
                    nodes.add(nodeId);
                }
            }
            return nodes;
        }

        long journalRowsFor(String nodeId) {
            return records.stream().filter(record -> nodeId.equals(journalledNode(record))).count();
        }

        Set<String> journalTypesFor(String nodeId) {
            return records.stream()
                    .filter(record -> nodeId.equals(journalledNode(record)))
                    .map(record -> record.envelope().eventType())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        String describeAggregate() {
            var described = new LinkedHashMap<String, List<String>>();
            traversal().invocations().values().forEach(invocation -> described
                    .computeIfAbsent(invocation.nodeId(), ignored -> new ArrayList<>())
                    .add(invocation.status() + "/" + invocation.attempts().stream()
                            .map(attempt -> attempt.status().toString()).toList()));
            return "traversal=" + traversal().status() + " " + described;
        }

        String describeJournal() {
            return records.stream()
                    .map(record -> {
                        String nodeId = journalledNode(record);
                        return (nodeId == null ? "-" : nodeId) + ":" + record.envelope().eventType();
                    })
                    .toList().toString();
        }

        String describeStream() {
            return stream.stream()
                    .map(event -> event.nodeId() + ":" + event.type())
                    .toList().toString();
        }
    }

    /**
     * Runs {@link #branchThatOutlivesItsTraversal()} with the ordering imposed from the test thread,
     * then reads both registers back after the runner and the recorder are closed.
     *
     * <p>The wiring is {@code RepeatedTerminalDurableDiaryTest}'s, deliberately unchanged, so that "what
     * the durable diary holds" means the same thing on the failure path as on the success path and the
     * two files can be read against each other.</p>
     */
    private Measurement runABranchPastTheClosure() throws Exception {
        return runABranchPastTheClosure((recorder, key, traversalId, store) -> { });
    }

    /**
     * The write a caller may perform <em>after</em> the traversal closed and <em>before</em> the
     * recorder is closed. The only reason it exists is
     * {@link #theDiscardedAlternativeWritesJournalRowsThatResolveToNoNode()}, which needs the
     * production recorder in exactly that state to reproduce the variant the guards declined.
     */
    @FunctionalInterface
    private interface PostClosureWrite {
        void write(ExecutionRecorder recorder, ExecutionKey key, UUID traversalId, ExecutionStore store);
    }

    private Measurement runABranchPastTheClosure(PostClosureWrite postClosure) throws Exception {
        var store = new InMemoryExecutionStore();
        var joins = new InMemoryJoinStore();
        var monitor = new ExecutionMonitor();
        SecurityContext security = TestIdentities.of(TENANT, "alice");
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);

        var doomedEntered = new CountDownLatch(1);
        var survivorEntered = new CountDownLatch(1);
        var doomedGate = new CompletableFuture<NodeResult>();
        var survivorGate = new CompletableFuture<NodeResult>();
        var lateSettledOnStream = new CountDownLatch(1);
        var seen = new CopyOnWriteArrayList<ExecutionEvent>();

        var behaviors = new BehaviorRegistry()
                .register("doomed", message -> {
                    doomedEntered.countDown();
                    return doomedGate;
                })
                .register("survivor", message -> {
                    survivorEntered.countDown();
                    return survivorGate;
                })
                .register("late", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("late-boom")));

        Throwable failure;
        GraphExecutionResult produced;
        try (var manager = GraphManager.from(branchThatOutlivesItsTraversal());
             var subscription = monitor.subscribe(event -> {
                 seen.add(event);
                 if (event.type() == ExecutionEventType.NODE_FAILED && "late".equals(event.nodeId())) {
                     lateSettledOnStream.countDown();
                 }
             });
             var runner = new GraphRunner(manager, engine, behaviors, monitor,
                     ExecutionIdentitySource.randomUuids(), joins, Clock.systemUTC())) {
            long revision = createRunningInstance(store, key, traversalId, manager.start().id());
            try (var recorder = ExecutionRecorder.open(store, key, "test-worker", TTL, revision)) {
                CompletableFuture<GraphExecutionResult> running = runner
                        .execute(security, processInstanceId, traversalId, "in", GRAPH_VERSION,
                                null, null, recorder)
                        .toCompletableFuture();

                // (1) Both branches are inside their node, so both are durably RUNNING and both have a
                // journalled NODE_STARTED. Waiting here is what makes step (3) provably post-closure.
                assertTrue(doomedEntered.await(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "the failing branch never entered its node, so the scenario was not built");
                assertTrue(survivorEntered.await(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "the surviving branch never entered its node, so nothing was in flight when the "
                                + "traversal closed and this run measures the ordinary failure path instead");

                // (2) Close the traversal through the branch with no failure route. executionFailed()
                // runs before this stage completes, so observing the failure is observing the closure.
                doomedGate.completeExceptionally(new IllegalStateException("doomed-boom"));
                ExecutionException ended = assertThrows(ExecutionException.class,
                        () -> running.get(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "a branch failing with no declared failure route must end the traversal without "
                                + "waiting for its sibling; if this run succeeds, allOrFirstFailure has "
                                + "started waiting for every branch and the case is gone");
                failure = ended.getCause();

                // (3) Everything from here on is post-closure by construction.
                survivorGate.complete(NodeResult.continueWith("survivor-payload"));

                // (4) The monitor call for 'late' sits after the guarded nodeFailed in GraphRunner.run,
                // so this is the signal that both guarded writes have been attempted and skipped.
                assertTrue(lateSettledOnStream.await(BOUND_MILLIS, TimeUnit.MILLISECONDS),
                        "the node dispatched after the closure never settled on the live stream, so the "
                                + "post-closure dispatch this file measures did not happen");

                // Read out of the stage itself rather than assumed: the third test asserts that a
                // traversal closed by failure yields no result, and an assertion against a constant
                // the fixture hard-coded would assert nothing.
                produced = running.isCompletedExceptionally() ? null : running.getNow(null);

                // (5) The counterfactual's write, when a test asks for it. Deliberately here: the
                // traversal is closed and the recorder still holds the fence, which is precisely the
                // state ExecutionState is in when the guards decline.
                postClosure.write(recorder, key, traversalId, store);
            }
        }

        StoredProcessInstance stored = await(store.load(key));
        List<JournalRecord> records = await(store.readJournal(TENANT, 0, 1_000)).stream()
                .filter(record -> record.key().equals(key))
                .toList();
        return new Measurement(store, key, stored, records, nodeByInvocation(stored), List.copyOf(seen),
                failure, produced);
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

    private static Map<UUID, String> nodeByInvocation(StoredProcessInstance stored) {
        var bindings = new HashMap<UUID, String>();
        for (Traversal traversal : stored.state().traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                bindings.put(invocation.invocationId(), invocation.nodeId());
            }
        }
        return Map.copyOf(bindings);
    }

    private static List<Long> rangeFromOne(int size) {
        var expected = new ArrayList<Long>(size);
        for (long value = 1; value <= size; value++) {
            expected.add(value);
        }
        return expected;
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
