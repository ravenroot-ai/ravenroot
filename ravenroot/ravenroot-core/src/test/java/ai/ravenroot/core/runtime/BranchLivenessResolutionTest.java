package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
     * A repeated resolution reports a declined fan-in branch once, not once per invocation.
     *
     * <p>{@code E} runs twice under {@code joinPolicy=each}. The first invocation is allowed to
     * finish routing before the second one completes, while {@code P2} stays parked until both
     * routes have crossed {@code T}. This keeps quorum-2 join {@code S} open while both resolutions
     * are observed, without timing guesses or a contended store. If liveness reports {@code E}'s
     * declined branch twice, the second report reaches the still-open join as a duplicate and emits
     * {@code JOIN_ARRIVAL_DISCARDED}.
     */
    @Test
    void aRepeatedResolutionDoesNotReportTheSameDeclinedBranchTwice() throws Exception {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("A"), GraphNode.passthrough("B"),
                        GraphNode.passthrough("P"),
                        GraphNode.behavior("P2", "gated-p2"),
                        new GraphNode("E", NodeKind.BEHAVIOR, "gated-e", Map.of("joinPolicy", "each")),
                        GraphNode.behavior("T", "route-probe"),
                        new GraphNode("S", NodeKind.BEHAVIOR, "log", Map.of("joinQuorum", "2")),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "A"), GraphEdge.to("start", "B"),
                        GraphEdge.to("start", "P"), GraphEdge.to("start", "P2"),
                        GraphEdge.to("A", "E"), GraphEdge.to("B", "E"),
                        GraphEdge.to("E", "T"), new GraphEdge("E", "S", "never"),
                        GraphEdge.to("P", "S"), GraphEdge.to("P2", "S"),
                        GraphEdge.to("S", "end"), GraphEdge.to("T", "end")));

        var firstRouteCompleted = new CompletableFuture<NodeResult>();
        var bothRoutesCompleted = new CompletableFuture<NodeResult>();
        var eInvocations = new AtomicInteger();
        var tInvocations = new AtomicInteger();
        var registry = new BehaviorRegistry()
                .register("gated-e", message -> eInvocations.incrementAndGet() == 1
                        ? CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()))
                        : firstRouteCompleted.thenApply(ignored -> NodeResult.continueWith(message.payload())))
                .register("route-probe", message -> {
                    if (tInvocations.incrementAndGet() == 1) {
                        firstRouteCompleted.complete(NodeResult.continueWith(message.payload()));
                    } else {
                        bothRoutesCompleted.complete(NodeResult.continueWith(message.payload()));
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                })
                .register("gated-p2", message -> bothRoutesCompleted.thenApply(
                        ignored -> NodeResult.continueWith(message.payload())));
        var discards = new CopyOnWriteArrayList<String>();
        var store = new InMemoryJoinStore();
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        try (var subscription = monitor.subscribe(event -> {
                 if (event.type() == ExecutionEventType.JOIN_ARRIVAL_DISCARDED) {
                     discards.add(event.nodeId() + " " + event.detail());
                 }
             });
             var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException settled) {
            // A traversal that fails is still evidence; this assertion is about what was reported.
        } finally {
            engine.close();
            store.close();
        }

        assertEquals(2, eInvocations.get(), "E must resolve once per predecessor");
        assertEquals(2, tInvocations.get(), "both E resolutions must finish routing before P2 arrives");
        assertEquals(List.of(), discards.stream().filter(d -> d.contains("branch=E")).toList(),
                "E declines S on every resolution, but the branch is dead once, not once per run. A "
                        + "duplicate discard means the declined-branch report fired per invocation "
                        + "instead of per predecessor. Discards seen: " + discards);
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

}
