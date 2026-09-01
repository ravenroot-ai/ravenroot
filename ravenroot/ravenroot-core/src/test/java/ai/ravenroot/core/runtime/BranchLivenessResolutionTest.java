package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A predecessor resolves once for the purpose of proving a node dead, however many times it runs.
 *
 * <p>{@code closesLastPathTo} decremented a counter seeded from the graph's distinct predecessor
 * count, once per <em>resolution</em>. That is equivalent to counting predecessors only while every
 * predecessor resolves exactly once, which is false. A node resolves once per arrival under
 * {@code joinPolicy=each}, and once per child when
 * data-parallel fan-out produces children. Two resolutions of one predecessor drove the counter to zero and
 * marked a node dead while another predecessor was still a live path to it.
 *
 * <p>The consequence is not cosmetic: a node wrongly in {@code dead} has its fan-in branches reported
 * {@code NOT_TAKEN}, and a spurious {@code NOT_TAKEN} on a join with a quorum above one is fatal
 * immediately and cannot be repaired by the real arrival that follows.
 */
class BranchLivenessResolutionTest {

    /**
     * A node with a still-live predecessor is not reported dead, however often another predecessor
     * resolves.
     *
     * <p>{@code E} is {@code each} with two predecessors, so it resolves twice, and declines {@code S}
     * on both. {@code S} also has {@code P} — reached through a longer chain so that both of
     * {@code E}'s resolutions land before {@code P} dispatches, which is what the {@code dispatched}
     * guard would otherwise mask. {@code S} feeds join {@code J}, so if {@code S} is wrongly declared
     * dead the store records {@code J}'s {@code S} branch as {@code NOT_TAKEN} — the exact write this
     * asserts is absent.
     */
    @Test
    void aNodeWithALivePredecessorIsNeverReportedDead() throws Exception {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("A"), GraphNode.passthrough("B"),
                        GraphNode.passthrough("P"), GraphNode.passthrough("P2"),
                        GraphNode.passthrough("P3"), GraphNode.passthrough("P4"),
                        GraphNode.passthrough("Q"),
                        new GraphNode("E", NodeKind.BEHAVIOR, "log", Map.of("joinPolicy", "each")),
                        GraphNode.passthrough("T"),
                        new GraphNode("S", NodeKind.BEHAVIOR, "log", Map.of("joinPolicy", "each")),
                        GraphNode.passthrough("J"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "A"), GraphEdge.to("start", "B"),
                        GraphEdge.to("start", "P"), GraphEdge.to("start", "Q"),
                        GraphEdge.to("A", "E"), GraphEdge.to("B", "E"),
                        GraphEdge.to("E", "T"), new GraphEdge("E", "S", "never"),
                        GraphEdge.to("P", "P2"), GraphEdge.to("P2", "P3"),
                        GraphEdge.to("P3", "P4"), GraphEdge.to("P4", "S"),
                        GraphEdge.to("S", "J"), GraphEdge.to("Q", "J"),
                        GraphEdge.to("J", "end"), GraphEdge.to("T", "end")));

        var writes = new CopyOnWriteArrayList<String>();
        run(graph, writes, new CopyOnWriteArrayList<>());

        assertTrue(writes.stream().noneMatch(w -> w.equals("J/S=NOT_TAKEN")),
                "S has a live predecessor (P) and must never be reported dead. A NOT_TAKEN for J's S "
                        + "branch means E's two resolutions drove S's liveness counter to zero on "
                        + "their own. Writes seen: " + writes);
    }

    /**
     * The over-broad-correction boundary for declined fan-in branches.
     *
     * <p>Making the counter idempotent per predecessor changes which successors reach the declined
     * fan-in branch: a repeated resolution no longer closes the last path, so it falls into the "declined"
     * path instead — and would report the same declined branch once per resolution. The full suite
     * does <b>not</b> catch that: 936 tests pass against an implementation with exactly this flaw. This
     * assertion rejects that over-broad correction directly.
     *
     * <p>{@code E} declines fan-in {@code S} on both resolutions. The branch must be reported once;
     * a second report is a duplicate and surfaces as a {@code JOIN_ARRIVAL_DISCARDED} event.
     */
    @Test
    void aRepeatedResolutionDoesNotReportTheSameDeclinedBranchTwice() throws Exception {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("A"), GraphNode.passthrough("B"),
                        GraphNode.passthrough("P"),
                        new GraphNode("E", NodeKind.BEHAVIOR, "log", Map.of("joinPolicy", "each")),
                        GraphNode.passthrough("T"),
                        new GraphNode("S", NodeKind.BEHAVIOR, "log", Map.of("joinPolicy", "all")),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "A"), GraphEdge.to("start", "B"),
                        GraphEdge.to("start", "P"),
                        GraphEdge.to("A", "E"), GraphEdge.to("B", "E"),
                        GraphEdge.to("E", "T"), new GraphEdge("E", "S", "never"),
                        GraphEdge.to("P", "S"),
                        GraphEdge.to("S", "end"), GraphEdge.to("T", "end")));

        var discards = new CopyOnWriteArrayList<String>();
        run(graph, new CopyOnWriteArrayList<>(), discards);

        assertEquals(List.of(), discards.stream().filter(d -> d.contains("branch=E")).toList(),
                "E declines S on every resolution, but the branch is dead once, not once per run. A "
                        + "duplicate discard means the declined-branch report fires per resolution "
                        + "instead of per predecessor. Discards seen: " + discards);
    }

    /**
     * The same over-broad-correction boundary as
     * {@link #aRepeatedResolutionDoesNotReportTheSameDeclinedBranchTwice}, caught a different way.
     *
     * <p>That test observes {@code JOIN_ARRIVAL_DISCARDED}, and two shapes of it were tried and
     * found not to fire: a two-branch {@code all} join, and the same with the policy spelled out
     * explicitly. Both fail the join outright on {@code E}'s very first decline -- quorum
     * unreachable -- so the join is already {@code FAILED} by the time the second, redundant
     * report arrives. {@link JoinCoordinator#attemptSettle} answers a branch reported against an
     * already-terminal record without publishing anything, so the duplicate is real and silent.
     *
     * <p>This test gives the join slack instead: {@code S} needs 2 of its 3 branches ({@code joinQuorum=2}
     * over {@code E, P, P2}), so a single real arrival dispatches it -- {@code BranchLiveness} marks a
     * successor {@code dispatched} the moment some predecessor decides to send to it, independently of
     * whether the join itself has enough to proceed -- without also satisfying the join outright the
     * way a plain {@code any} (quorum 1) join would. An {@code any} join does not work here: for
     * quorum 1 a single real arrival both dispatches {@code S} <em>and</em>
     * settles its record as {@code SATISFIED} in the same call, so a decline that lands after gives
     * {@code JoinCoordinator} an already-terminal record and is silently discarded as {@code LATE} --
     * indistinguishable, from this test's point of view, from the record never having been open in the
     * first place. {@code S} stays open here until the second real branch arrives, which is what gives
     * the redundant, second decline something to land on.
     *
     * <p>What this test asserts is not "no duplicate event" -- events already proved unreliable here --
     * but a fact about what the store was asked to persist: the recording store below diffs each write
     * against the record <em>that same write's own read</em> produced, rather than logging
     * {@code desired.branches()} cumulatively. A branch already present in <em>every</em> write from the
     * moment it is first added -- which is what a cumulative log would show either way, correct or
     * broken -- is not usable evidence. Whether that read is stale (because it raced a sibling settle
     * that has not yet landed) is exactly the fact this test needs: a genuinely single report can be
     * re-attempted on a stale read at most once per redelivery, but two full attempts that each
     * independently conclude "S/E is new" is two <em>reports</em>. The {@code closesLastPathTo}
     * mutant produces both reports; production's predecessor-id set accepts the first resolution and
     * treats the second as already settled. {@link DiffingJoinStore} widens the window
     * between a caller's read and its write so that interleaving is reached deterministically rather
     * than occasionally, the same technique {@link ContendedJoinStore} already documents for the
     * store's own lost-update race -- see that class's javadoc for why the widening has to be scoped
     * to attempts that are themselves fresh, rather than to every write {@code S} receives.
     *
     * <p>{@code P} is reached directly from {@code start}, one hop, while {@code E} needs a four-hop
     * chain to each of its two predecessors before either can decline {@code S}, and {@code E} is
     * {@code each} so it runs once per predecessor. That gap is not decorative: it is what makes
     * {@code S} reliably {@code dispatched} before <em>either</em> of {@code E}'s declines happens,
     * which is what routes both declines through {@code BranchLiveness}'s immediate-report path.
     * Without it, a redundant pair that both land before any real arrival queues instead in
     * {@code BranchLiveness.held} and is released together in one synchronous batch when {@code S}
     * finally dispatches -- and a batch releases sequentially on one thread, so the second entry's own
     * read always observes the first entry's write. That interleaving is invisible to a store diff by
     * construction, not because nothing is wrong: it produced roughly one miss in seven with a one-hop
     * gap, before the gap was widened to four.
     */
    @Test
    void aRepeatedResolutionDoesNotReportTheSameDeclinedBranchTwiceEvenWithSlack() throws Exception {
        // E (each, two predecessors A and B, each reached via a four-hop chain) declines fan-in S.
        // S needs 2 of its 3 branches {E, P, P2} (joinQuorum=2). P is one hop from start, so it
        // dispatches S -- as far as BranchLiveness is concerned -- well before E's
        // four-hop-plus-each resolutions can complete, but a single arrival is not enough to satisfy
        // a quorum of 2 by itself. P2 is also one hop, and would ordinarily be free to arrive just as
        // early and satisfy the join before E ever declines; store below holds its write back until
        // E's decline has had a first chance to land (see GateArrivalUntilContendedJoinStore),
        // instead of trying to force that ordering purely from graph shape, which turned out not to
        // be reliable enough -- see the class javadoc above for what was tried and why it wasn't.
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"),
                        GraphNode.passthrough("PreA1"), GraphNode.passthrough("PreA2"),
                        GraphNode.passthrough("PreA3"), GraphNode.passthrough("PreB1"),
                        GraphNode.passthrough("PreB2"), GraphNode.passthrough("PreB3"),
                        GraphNode.passthrough("A"), GraphNode.passthrough("B"),
                        GraphNode.passthrough("P"), GraphNode.passthrough("P2"),
                        new GraphNode("E", NodeKind.BEHAVIOR, "log", Map.of("joinPolicy", "each")),
                        GraphNode.passthrough("T"),
                        new GraphNode("S", NodeKind.BEHAVIOR, "log", Map.of("joinQuorum", "2")),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "PreA1"), GraphEdge.to("PreA1", "PreA2"),
                        GraphEdge.to("PreA2", "PreA3"), GraphEdge.to("PreA3", "A"),
                        GraphEdge.to("start", "PreB1"), GraphEdge.to("PreB1", "PreB2"),
                        GraphEdge.to("PreB2", "PreB3"), GraphEdge.to("PreB3", "B"),
                        GraphEdge.to("start", "P"), GraphEdge.to("start", "P2"),
                        GraphEdge.to("A", "E"), GraphEdge.to("B", "E"),
                        GraphEdge.to("E", "T"), new GraphEdge("E", "S", "never"),
                        GraphEdge.to("P", "S"), GraphEdge.to("P2", "S"),
                        GraphEdge.to("S", "end"), GraphEdge.to("T", "end")));

        var freshReports = new CopyOnWriteArrayList<String>();
        var diffingStore = new DiffingJoinStore(new InMemoryJoinStore(), freshReports, "E", 2);
        var store = new GateArrivalUntilContendedJoinStore(diffingStore, diffingStore, "P2");
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, new BehaviorRegistry(), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException settled) {
            // A traversal that fails is still evidence; this assertion is about what was reported.
        } finally {
            engine.close();
            store.close();
        }

        assertEquals(1, freshReports.stream().filter(r -> r.equals("S/E=NOT_TAKEN")).count(),
                "E declines S on every resolution, but the branch is dead once, not once per run. "
                        + "Two writes that each independently read S without an E branch and then "
                        + "wrote one in means the branch was reported twice, not deduplicated once. "
                        + "Fresh reports seen: " + freshReports);
    }

    // ------------------------------------------------------------------ fixtures

    private static void run(GraphDefinition graph, List<String> writes, List<String> discards)
            throws Exception {
        var store = new RecordingJoinStore(new InMemoryJoinStore(), writes);
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        try (var subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED) {
                     discards.add(event.nodeId() + " " + event.detail());
                 }
             });
             var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, new BehaviorRegistry(), monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException settled) {
            // A traversal that fails is still evidence; these assertions are about what was reported.
        } finally {
            engine.close();
            store.close();
        }
    }

    /** Records each branch outcome as it is written, so a spurious dead is visible as a fact. */
    private static final class RecordingJoinStore implements JoinStore {
        private final JoinStore delegate;
        private final List<String> writes;

        private RecordingJoinStore(JoinStore delegate, List<String> writes) {
            this.delegate = delegate;
            this.writes = writes;
        }

        @Override public boolean durable() { return delegate.durable(); }
        @Override public CompletionStage<Optional<JoinRecord>> load(JoinKey key) { return delegate.load(key); }

        @Override public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
            desired.branches().forEach((branch, outcome) ->
                    writes.add(desired.key().joinNodeId() + "/" + branch + "=" + outcome));
            return delegate.compareAndSet(desired);
        }

        @Override public CompletionStage<Boolean> discard(JoinKey key) { return delegate.discard(key); }
        @Override public CompletionStage<List<JoinRecord>> openJoins(String t) { return delegate.openJoins(t); }
        @Override public CompletionStage<Long> recordCount(String t) { return delegate.recordCount(t); }
        @Override public CompletionStage<Long> purgeSettledBefore(String t, Instant c) {
            return delegate.purgeSettledBefore(t, c);
        }
        @Override public void close() { delegate.close(); }
    }

    /**
     * Diffs each {@code compareAndSet} against the record <em>that same caller's own {@code load}</em>
     * returned, instead of logging {@code desired.branches()} cumulatively like {@link RecordingJoinStore}
     * does -- and widens the window between that {@code load} and this {@code compareAndSet} for
     * whichever attempts turn out to freshly report {@code contendedBranch}, the same technique
     * {@link ContendedJoinStore} already documents for the store's own lost-update race, so a second,
     * redundant attempt is reached deterministically instead of only when the scheduler happens to
     * interleave the two on its own.
     *
     * <h2>Why a diff, not a cumulative log</h2>
     * <p>A cumulative log cannot tell "written once, then carried forward unchanged in every later
     * snapshot" apart from "written twice" -- a branch that is genuinely reported once appears in
     * every subsequent write of that record simply because {@link JoinRecord#branches()} is
     * cumulative, so counting occurrences in a flat log is not usable evidence either way. Diffing
     * against the record this specific attempt read is: a branch that was already present in that
     * read is not new to this attempt, however many prior writes the log holds; a branch that is
     * newly present relative to that read is what this attempt believes it is the first to report.
     * Two attempts that each independently believe that is two reports. The {@code closesLastPathTo}
     * mutant produces both; production's predecessor-id set makes the second resolution idempotent.
     *
     * <h2>Why the barrier is scoped to freshness, not to a branch key</h2>
     * <p>A barrier keyed on "does {@code desired} contain {@code contendedBranch}" is too broad: once
     * that branch is first written, every <em>later</em> write of the same join record also contains
     * it, simply because the map is cumulative -- so an unrelated arrival such as {@code P2}'s own
     * write would also queue at the barrier and could pair with the redundant attempt instead of the
     * first one, releasing both without ever forcing the interleaving this test needs. Gating the
     * barrier on this attempt's <em>own freshness check</em> -- the same one that produces
     * {@code freshReports} -- means only attempts that would themselves report {@code contendedBranch}
     * as new ever wait for each other.
     */
    private static final class DiffingJoinStore implements JoinStore {
        private final JoinStore delegate;
        private final List<String> freshReports;
        private final String contendedBranch;
        private final CyclicBarrier beforeContendedWrite;
        /** Counted down the first time an attempt freshly reports {@code contendedBranch}. */
        private final CountDownLatch contendedBranchFirstSeen = new CountDownLatch(1);
        /** What the most recent {@code load()} for a key returned -- that key's "previous record". */
        private final Map<JoinKey, Map<String, String>> lastLoaded = new HashMap<>();

        private DiffingJoinStore(JoinStore delegate, List<String> freshReports, String contendedBranch,
                                 int contenders) {
            this.delegate = delegate;
            this.freshReports = freshReports;
            this.contendedBranch = contendedBranch;
            this.beforeContendedWrite = new CyclicBarrier(contenders);
        }

        /**
         * Waits until some attempt has freshly reported {@code contendedBranch}, or {@code timeout}
         * elapses. Lets a caller such as {@link GateArrivalUntilContendedJoinStore} hold an unrelated
         * branch's own arrival back until the contended branch has genuinely had its first chance to
         * land, instead of guessing at that timing from graph shape (hop counts, node kinds), which is
         * unreliable because {@code TEST_PASSTHROUGH} makes
         * every hop near-instantaneous, so small scheduler variance was enough to flip the outcome.
         */
        boolean awaitContendedBranchFirstSeen(Duration timeout) throws InterruptedException {
            return contendedBranchFirstSeen.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override public boolean durable() { return delegate.durable(); }

        @Override public CompletionStage<Optional<JoinRecord>> load(JoinKey key) {
            return delegate.load(key).thenApply(found -> {
                var snapshot = new HashMap<String, String>();
                found.ifPresent(record -> record.branches().forEach((b, o) -> snapshot.put(b, o.name())));
                synchronized (this) {
                    lastLoaded.put(key, snapshot);
                }
                return found;
            });
        }

        @Override public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
            boolean freshContendedBranch = false;
            synchronized (this) {
                Map<String, String> priorRecord = lastLoaded.getOrDefault(desired.key(), Map.of());
                for (var entry : desired.branches().entrySet()) {
                    if (!entry.getValue().name().equals(priorRecord.get(entry.getKey()))) {
                        freshReports.add(desired.key().joinNodeId() + "/" + entry.getKey()
                                + "=" + entry.getValue());
                        freshContendedBranch |= entry.getKey().equals(contendedBranch);
                    }
                }
            }
            if (freshContendedBranch) {
                contendedBranchFirstSeen.countDown();
                try {
                    // Bounded so a genuinely single (correct-fix) attempt does not hang the test: it
                    // waits out the bound once, finds no second party, and proceeds alone.
                    beforeContendedWrite.await(2, TimeUnit.SECONDS);
                } catch (Exception released) {
                    beforeContendedWrite.reset();
                }
            }
            return delegate.compareAndSet(desired);
        }

        @Override public CompletionStage<Boolean> discard(JoinKey key) { return delegate.discard(key); }
        @Override public CompletionStage<List<JoinRecord>> openJoins(String t) { return delegate.openJoins(t); }
        @Override public CompletionStage<Long> recordCount(String t) { return delegate.recordCount(t); }
        @Override public CompletionStage<Long> purgeSettledBefore(String t, Instant c) {
            return delegate.purgeSettledBefore(t, c);
        }
        @Override public void close() { delegate.close(); }
    }

    /**
     * Holds one branch's own {@code compareAndSet} back until {@code contended}'s branch has had its
     * first genuine chance to land, plus a short settle window afterward -- deterministic in what it
     * waits FOR, even though the wait itself is a bounded sleep rather than an exact signal for "no
     * more contended attempts are coming".
     *
     * <p>Graph shape alone cannot enforce this ordering. Putting {@code P2} several hops further from
     * {@code start} than {@code E} does not ensure that the longer path arrives later under
     * {@code TEST_PASSTHROUGH}, where every hop is a near-instantaneous continuation: relative
     * scheduler timing decided the actual order, not topology, and the same run that was reliable in
     * isolation missed roughly 3 times in 10 once this class's other two tests ran first in the same
     * JVM and changed the pool's warm-up state. Gating on an explicit signal from
     * {@link DiffingJoinStore} removes that dependency instead of trying to out-guess it.
     */
    private static final class GateArrivalUntilContendedJoinStore implements JoinStore {
        private static final Duration CONTENDED_BRANCH_BOUND = Duration.ofSeconds(3);
        private static final Duration SETTLE_WINDOW = Duration.ofMillis(400);

        private final JoinStore delegate;
        private final DiffingJoinStore signal;
        private final String gatedBranch;

        private GateArrivalUntilContendedJoinStore(JoinStore delegate, DiffingJoinStore signal, String gatedBranch) {
            this.delegate = delegate;
            this.signal = signal;
            this.gatedBranch = gatedBranch;
        }

        @Override public boolean durable() { return delegate.durable(); }
        @Override public CompletionStage<Optional<JoinRecord>> load(JoinKey key) { return delegate.load(key); }

        @Override public CompletionStage<JoinRecord> compareAndSet(JoinRecord desired) {
            if (desired.branches().containsKey(gatedBranch)) {
                try {
                    // Bounded even on the wait-FOR side: if the contended branch never reports at all
                    // (a differently broken run) this must not hang the test forever either.
                    signal.awaitContendedBranchFirstSeen(CONTENDED_BRANCH_BOUND);
                    Thread.sleep(SETTLE_WINDOW.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.compareAndSet(desired);
        }

        @Override public CompletionStage<Boolean> discard(JoinKey key) { return delegate.discard(key); }
        @Override public CompletionStage<List<JoinRecord>> openJoins(String t) { return delegate.openJoins(t); }
        @Override public CompletionStage<Long> recordCount(String t) { return delegate.recordCount(t); }
        @Override public CompletionStage<Long> purgeSettledBefore(String t, Instant c) {
            return delegate.purgeSettledBefore(t, c);
        }
        @Override public void close() { delegate.close(); }
    }
}
