package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A live node that declines a fan-in successor must report that branch as not taken.
 *
 * <h2>The defect</h2>
 * <p>{@code notTaken} is emitted only when the <em>branch node itself is dead</em>. A node that is
 * alive and simply routed elsewhere is not dead, so nothing tells the fan-in that this branch will
 * never arrive, and the quorum waits for an arrival that cannot exist.
 *
 * <p>The arithmetic, precisely: {@code closesLastPathTo(S)} takes {@code S}'s liveness counter from
 * two to one and does not reach zero, because {@code P} is still a valid path to it. So {@code S} is
 * correctly judged alive — and the branch from {@code D}, which is separately and permanently dead,
 * is never reported at all. Node liveness and branch liveness are different questions, and only the
 * first is being asked.
 *
 * <h2>Any conditional route into a fan-in, not a special policy</h2>
 * <p>This was first observed with {@code joinPolicy=each}, which turned out to be incidental: the two
 * probes that established it were {@code plain -> HUNG} and {@code each -> HUNG}. What is needed is a
 * node with a choice of successors and a fan-in downstream of the one it does not take — one of the
 * most ordinary shapes there is.
 *
 * <h2>Why it asserts a failure rather than a success</h2>
 * <p>The hang only exists when the quorum actually needs the declined branch; with a quorum that
 * {@code P} alone satisfies, the traversal completes today and there is nothing to catch. So a
 * two-branch {@code all} join is the shape under test, and once the declined branch is reported the
 * correct outcome is that the join is <em>impossible</em> — the same outcome a dead branch already
 * produces. Fixed means failing with a join verdict; broken means never settling.
 *
 * <p>Asserting merely "did not succeed" would pass in both states, which is the trap this repository
 * has now paid for twice. The assertion is on the exception type, and the bound is explicit so a
 * regression fails the suite instead of stalling the reactor.
 */
class DeclinedFanInBranchTest {

    private static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(3);
    private static final Duration SETTLES_OR_FAILS = Duration.ofSeconds(30);

    @Test
    void aLiveNodeThatDeclinesAFanInReportsTheBranchInsteadOfLeavingItWaiting() {
        // start -> D and start -> P.  D routes to X and declines S.  S is a fan-in over {D, P}.
        // P arrives; the branch from D never can. Nothing is dead, so nothing is reported today.
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("D"), GraphNode.passthrough("P"),
                        GraphNode.passthrough("X"), GraphNode.passthrough("S"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "D"), GraphEdge.to("start", "P"),
                        GraphEdge.to("D", "X"),
                        new GraphEdge("D", "S", "never"),
                        GraphEdge.to("P", "S"),
                        GraphEdge.to("S", "end"), GraphEdge.to("X", "end")));

        assertTimeoutPreemptively(SETTLES_OR_FAILS, () -> {
            var failure = assertThrows(ExecutionException.class, () -> execute(graph),
                    "the traversal must settle. Hanging here is the defect: the fan-in is waiting on "
                            + "a branch whose predecessor is alive and went elsewhere, so nothing will "
                            + "ever report it and nothing will ever fail");
            assertInstanceOf(JoinFailureException.class, rootCause(failure),
                    "the declined branch makes the two-branch all-join impossible, which is a join "
                            + "verdict -- the same outcome a dead branch already produces. Got: "
                            + rootCause(failure));
        });
    }

    /**
     * The same shape with {@code joinPolicy=each} on the declining node, kept because that is how the
     * defect was found and because it must not regress separately.
     *
     * <p>It adds a second predecessor so the {@code each} node really does resolve more than once,
     * which is the only thing {@code each} contributes here. The outcome must be identical to the
     * plain case; divergence would mean only one surface was corrected.
     */
    @Test
    void theSameHoldsWhenTheDecliningNodeResolvesOncePerArrival() {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("A2"), GraphNode.passthrough("P"),
                        new GraphNode("D", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "log",
                                java.util.Map.of("joinPolicy", "each")),
                        GraphNode.passthrough("X"), GraphNode.passthrough("S"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "D"), GraphEdge.to("start", "A2"),
                        GraphEdge.to("start", "P"), GraphEdge.to("A2", "D"),
                        GraphEdge.to("D", "X"),
                        new GraphEdge("D", "S", "never"),
                        GraphEdge.to("P", "S"),
                        GraphEdge.to("S", "end"), GraphEdge.to("X", "end")));

        assertTimeoutPreemptively(SETTLES_OR_FAILS, () -> {
            var failure = assertThrows(ExecutionException.class, () -> execute(graph),
                    "an each node that declines a fan-in must settle it too");
            assertInstanceOf(JoinFailureException.class, rootCause(failure),
                    "Got: " + rootCause(failure));
        });
    }

    /**
     * The control: when the branch really is taken, nothing is reported dead and the join completes.
     *
     * <p>Without this, an implementation that reported every fan-in successor as not taken -- taken ones included
     * -- would pass both cases above while breaking every working diamond in the product.
     */
    @Test
    void aTakenFanInBranchIsNotReportedAsDeclined() throws Exception {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("D"), GraphNode.passthrough("P"),
                        GraphNode.passthrough("S"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "D"), GraphEdge.to("start", "P"),
                        GraphEdge.to("D", "S"), GraphEdge.to("P", "S"),
                        GraphEdge.to("S", "end")));

        assertTimeoutPreemptively(SETTLES_OR_FAILS, () -> execute(graph));
    }

    // ------------------------------------------------------------------ fixtures

    private static void execute(GraphDefinition graph) throws Exception {
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, new BehaviorRegistry(), monitor,
                     ExecutionIdentitySource.randomUuids(), SHUTDOWN_BOUND)) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
        } finally {
            engine.close();
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
