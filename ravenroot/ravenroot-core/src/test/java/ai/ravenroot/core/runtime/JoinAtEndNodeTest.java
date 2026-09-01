package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * CORE-03's join accounting excludes only {@code START} and an explicit
 * {@code joinPolicy=each} (JoinSpec#validate, ~L93-114); {@code END} is not on that list, and no
 * property makes it one. The fan-in fixtures one reaches for first do route their branches through a
 * {@code PASSTHROUGH} {@code join} node and only afterwards to a separate {@code end}
 * ({@link JoinMiniGraphs#fanIn} here, {@code ExecutionEngineContract#fanInGraph} in the engine TCK),
 * and the one <em>of those</em> that puts a join directly on END, {@code ExecutionEngineContract
 * #executesOnlyTheFirstArrivalAtAnAnyJoin}, configures {@code joinPolicy=any} (quorum 1) and asserts
 * a single unmerged payload, never reaching the aggregation branch of {@link GraphRunner#merge}.
 *
 * <p><strong>That is not the same as saying nothing reached it.</strong>
 * {@code NodeCommandRoutingTest#fanOutDeliversEachTargetsOwnCommand} (~L143) wires
 * {@code GraphEdge.to("left", "end")} and {@code GraphEdge.to("right", "end")} onto a plain
 * {@code GraphNode.end("end")} with no {@code joinPolicy}, so its quorum defaults to two, and
 * instrumenting {@link GraphRunner#merge} shows it arriving there with both branches
 * ({@code arrivals=2 branches=[left, right]}). What that fixture never does is <em>assert</em>
 * anything about the merged result -- it checks only
 * which command reached {@code left} and {@code right} -- and a mutation that bypasses the merge
 * leaves all of its cases green. <strong>Executing a path without asserting what it produces leaves
 * that mechanism unverified</strong>: this fixture exercises the merge path but provides no evidence
 * about END aggregation.
 *
 * <p>The qualifier is deliberate: three GraphML fixtures do wire a fan-in straight onto {@code end}
 * with no {@code joinPolicy}, and none of them covers this case either, each for its own reason.
 * <ul>
 *   <li>{@code ravenroot-core/src/test/resources/graphml-corpus/accepted/optional-edge-ids.graphml}
 *       gives {@code end} the distinct predecessors {@code worker} and {@code start} (the latter
 *       through a synthesized {@code outcome=shortcut} edge). It is read by parser-level tests --
 *       {@code GraphMlCorpusTest}, {@code GraphMlProfileReportTest} and
 *       {@code SecureGraphMlParserFuzzTest} on the Java side, {@code graphml-corpus.test.js} and
 *       {@code graph-commands.test.js} on the {@code ravenroot-ui} side -- all of which check parsed
 *       structure and never run it through {@link GraphRunner}. Structurally, the two edges leaving
 *       {@code start} declare <em>different</em> outcomes ({@code start -> worker} takes the
 *       undeclared default, {@code start -> end} declares {@code outcome=shortcut}); since one
 *       execution of {@code start} produces exactly one outcome, only the edge matching it is ever
 *       taken, which is exactly why the two differ rather than coincide -- so even an execution
 *       would take only one of the two per run.</li>
 *   <li>{@code ravenroot-ui/public/examples/ravenroot-programmable.graphml} gives {@code end} the
 *       predecessors {@code accepted-log} and {@code rejected-log}, but those are the two mutually
 *       exclusive outcomes of the decision node {@code is-ravenroot}, so only one is ever dispatched
 *       per run; it is also not referenced by any automated Java or UI test, so no run of it exists
 *       at all.</li>
 *   <li>{@code ravenroot-ui/test/fixtures/edge-authoring.graphml} gives {@code end} the predecessors
 *       {@code review} (outcome {@code approved}) and {@code archive} (reached only via
 *       {@code review}'s {@code rejected} outcome), again mutually exclusive per run. It is loaded
 *       only by the Node unit test {@code ravenroot-ui/test/edge-fixture.test.js} (UI-02), which
 *       exercises the JS editor's authoring functions and never invokes the Java {@link GraphRunner}.
 *       It has exactly one literal reader -- reproduce with
 *       {@code grep -rn "fixtures/edge-authoring.graphml" ravenroot/ --include='*.js' --include='*.java'
 *       | grep -v "//" | grep -v JoinAtEndNodeTest} (globs quoted so it runs unchanged in zsh and
 *       bash, unlike an unquoted {@code --include=*.java}). Declared exclusions, both needed: the
 *       first {@code grep -v} drops four lines that only name the path inside a {@code //} comment,
 *       including {@code edge-fixture.test.js}'s own CLI re-verification instructions; the second drops this file,
 *       whose own prose quotes the same path and would otherwise count itself as a reader. What
 *       remains is exactly one line, the {@code resolve(...)} call that binds {@code FIXTURE} at
 *       {@code edge-fixture.test.js:26} -- not one of that file's {@code readFileSync} calls (lines
 *       73, 77, 92, 110), which read through the {@code FIXTURE} variable and never spell the path
 *       out literally, so this grep does not and should not return them.</li>
 * </ul>
 * <p>Bullets one and two are verified true above, but neither is <em>imposed</em>: nothing re-runs
 * their reader/consumer claim, so both carry the risk inherent in any static, unenforced statement.
 * What distinguishes a static claim that stays true
 * from one that quietly doesn't is imposition, not whether the sharing looked intentional or the
 * corpus looked stable: {@code ravenroot-ui/test/pane-focus-contract.test.js} does not list
 * {@code activateDocument}'s callers, it scans {@code app.js} at test time to prove
 * {@code activateDocument} is the only caller of {@code workspace.activate}; {@code
 * ravenroot-server/src/test/java/ai/ravenroot/server/ShippedExampleCorpusTest.java} does not list
 * the shipped examples, it discovers the directory at test time for the same reason, stating
 * explicitly that a hardcoded file list is the defect it exists to prevent from recurring. Making
 * bullets one and two would require equivalent tests that rederive their reader sets; without those
 * tests they remain explicitly static, unenforced claims.</p>
 * <p>Keyword searches cannot certify that every similar comment is current: only a check that
 * rederives each list at test time can impose such a claim. This class therefore makes the narrower,
 * checkable assertion that a two-arrival join at END produces the merged payload below.</p>
 *
 * <p>That gap invites exactly the wrong reading: {@link GraphExecutionResult#payload()} is a
 * single field, so it is tempting to conclude an END with two arrivals can only ever hold the
 * last one to arrive, silently overwriting the other. That would be true if
 * {@link GraphRunner#dispatch} used the triggering arrival's own payload directly instead of
 * routing every {@code JoinDecision.Proceed} (~L684-698) through {@link GraphRunner#merge}. This
 * test pins that it does not: two branches converging on END, with no {@code joinPolicy}
 * configured -- so the quorum defaults to {@code branches.size()}, i.e. {@code all} (JoinSpec,
 * ~L195-196) -- must wait for both arrivals, dispatch END exactly once, and merge both payloads
 * into the result in branch-id order.
 *
 * <p><strong>What this test does and does not isolate.</strong> The branch-id order it observes is
 * guaranteed <em>redundantly</em>, at two levels: {@link ai.ravenroot.api.persistence.JoinRecord
 * #plus} keeps its branch map in a {@code SortedMap}, so {@link JoinCoordinator#evaluate} already
 * hands {@link GraphRunner#merge} a branch-id-ordered list before {@code merge}'s own
 * {@code ordered.sort(...)} (~L1908-1917) ever runs. Forcing arrival timing here (below) proves the
 * traversal genuinely waits for both branches and fires once -- it does not, on its own, prove which
 * of the two levels produces the order, and it stays green under a mutant that deletes {@code
 * merge}'s sort line, because the upstream {@code SortedMap} alone still supplies a correctly
 * ordered list. The layer that isolates {@code merge}'s own sort, with an input built deliberately
 * out of branch-id order so nothing upstream can be supplying it, is
 * {@link GraphRunnerMergeOrderTest}.</p>
 */
class JoinAtEndNodeTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * {@code start} fans out to {@code b0} and {@code b1}, both feeding {@code end} directly --
     * there is no intermediate join node. {@code end} therefore has two distinct predecessors and
     * no configured {@code joinPolicy}, so {@link JoinSpec#validate} treats it as an {@code all}
     * join with quorum 2, exactly like any other fan-in node.
     */
    private static GraphDefinition twoBranchesIntoEnd() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("b0", "b0"),
                GraphNode.behavior("b1", "b1"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "b0"),
                GraphEdge.to("start", "b1"),
                GraphEdge.to("b0", "end"),
                GraphEdge.to("b1", "end")));
    }

    /**
     * {@code b1} -- alphabetically the later branch id -- is made to arrive and complete first;
     * {@code b0} is held until that has happened, then released. This proves the join genuinely
     * waits for both arrivals rather than settling on the first, and that the observable result
     * still reads {@code [b0-result, b1-result]} rather than arrival order. It does not, on its
     * own, prove that the branch-id sort is {@link GraphRunner#merge}'s own doing -- see the class
     * javadoc's "what this test does and does not isolate" for why, and
     * {@link GraphRunnerMergeOrderTest} for the test that isolates it.
     */
    @Test
    void aTwoBranchJoinDirectlyAtEndWaitsMergesAndFiresExactlyOnce() throws Exception {
        var b1Completed = new CompletableFuture<Void>();
        var b0Release = new CompletableFuture<NodeResult>();
        var registry = new BehaviorRegistry();
        registry.register("b0", message -> b0Release);
        registry.register("b1", message -> {
            b1Completed.complete(null);
            return CompletableFuture.completedFuture(NodeResult.continueWith("b1-result"));
        });

        try (var manager = GraphManager.from(twoBranchesIntoEnd());
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            b1Completed.get(5, TimeUnit.SECONDS);
            assertFalse(execution.isDone(),
                    "an all join with two branches must not fire on b1 alone while b0 is still pending");
            b0Release.complete(NodeResult.continueWith("b0-result"));

            var result = execution.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("b0-result", "b1-result"), result.payload(),
                    "both arrivals must be merged in branch-id order -- not just the last one in, "
                            + "which would silently drop b0's contribution");
            assertEquals(Set.of("start", "b0", "b1", "end"), result.visitedNodes(),
                    "end is a Set member, so this documents the single arrival at end as a consequence "
                            + "of the join, not as a claim that a duplicate dispatch would be caught here");
            assertEquals(1, monitor.eventsAfter(0).stream()
                            .filter(event -> "end".equals(event.nodeId()))
                            .filter(event -> event.type() == ExecutionEventType.NODE_STARTED)
                            .count(),
                    "the join must dispatch to end exactly once -- never once per arrival, which "
                            + "visitedNodes alone, being a Set, could not tell apart from a correct join");
        }
    }
}
