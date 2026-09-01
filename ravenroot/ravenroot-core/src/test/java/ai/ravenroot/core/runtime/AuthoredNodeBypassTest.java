package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authored per-node bypass flag: {@code execution.bypass=true} on one node means that node
 * does not execute and everything else does.
 *
 * <h2>Why this is not {@code NodeCommandRoutingTest}</h2>
 * <p>That class pins the <em>command</em> bypass, whose defining property is that it is sticky —
 * {@code passthroughIsAStickySafetyCeilingOverLaterEdgeCommands} asserts that a node reached under
 * {@code command=passthrough} pulls every downstream node under the same ceiling. This class pins the
 * opposite property for a different mechanism: an authored bypass ends at the node that declares it,
 * and its successors execute normally. Keeping them apart keeps the two claims legible; a reader who
 * finds "bypass" in one file is not left to work out which of the two rules a given test is about.
 * The sticky test is untouched by authored bypass and must stay that way.</p>
 *
 * <h2>The property name is a literal here, deliberately</h2>
 * <p>{@code "execution.bypass"} is written out rather than read from {@link NodeBypassProperty#NAME}
 * in the graph fixtures below. The constant is what the runtime and the editor share, so a test that
 * only ever used the constant would keep passing if its <em>value</em> changed — and that value is on
 * the wire, in every saved document and every exported GraphML file. One test asserts the constant
 * equals the literal ({@link #theWellKnownNameIsTheOneOnTheWire()}); the rest use the literal, so a
 * rename shows up as a red test rather than as a silently migrated format.</p>
 */
class AuthoredNodeBypassTest {
    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void theWellKnownNameIsTheOneOnTheWire() {
        assertEquals("execution.bypass", NodeBypassProperty.NAME);
    }

    /**
     * The basic contract: {@code start -> a -> b -> c -> end} with {@code b}
     * switched off. {@code a} and {@code c} run, {@code b} does not, {@code end} is reached, and the
     * payload crosses {@code b} unchanged.
     *
     * <p>{@code b}'s factory counter is asserted too, and it is not a redundant restatement of "the
     * action did not run": the motivating case is a node that <em>cannot</em> be provisioned —
     * an AI node with no adapter configured, an artifact that does not exist yet. Creating the factory
     * and then declining to invoke it would still parse credentials and load the missing thing, which
     * is exactly what the author switched the node off to avoid. This is the same argument
     * {@code GraphRunner#runtimeNode} already makes for the command bypass, one line above where the
     * authored bypass now sits.</p>
     */
    @Test
    void anAuthoredBypassSkipsOnlyItsOwnNodeAndCarriesThePayloadThrough() throws Exception {
        var aRuns = new AtomicInteger();
        var bCreates = new AtomicInteger();
        var bRuns = new AtomicInteger();
        var cRuns = new AtomicInteger();
        var seenByC = new AtomicReference<Object>();

        var registry = new BehaviorRegistry()
                .registerFactory(factory("aBehavior", new AtomicInteger(), message -> {
                    aRuns.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith("crossed-a"));
                }))
                .registerFactory(factory("bBehavior", bCreates, message -> {
                    bRuns.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith("b-must-not-produce-this"));
                }))
                .registerFactory(factory("cBehavior", new AtomicInteger(), message -> {
                    cRuns.incrementAndGet();
                    seenByC.set(message.payload());
                    return CompletableFuture.completedFuture(NodeResult.continueWith("crossed-c"));
                }));

        var monitor = new ExecutionMonitor();
        try (var runner = new GraphRunner(GraphManager.from(chainWithBypassedB()), engine, registry, monitor)) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "original")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, aRuns.get(), "the node before the switched-off one must execute");
            assertEquals(0, bRuns.get(), "the switched-off node must not execute");
            assertEquals(0, bCreates.get(), "the switched-off node must not even be constructed");
            assertEquals(1, cRuns.get(), "the node after the switched-off one must execute");

            assertEquals("crossed-a", seenByC.get(),
                    "the payload must cross the switched-off node unchanged");
            assertEquals("crossed-c", result.payload());
            assertTrue(result.visitedNodes().contains("end"), "end must be reached");

            assertEquals(Set.of("b"), result.bypassedNodes(),
                    "only the node the author switched off is bypassed -- an authored bypass is not sticky");
            assertTrue(result.defaultedNodes().isEmpty(),
                    "an authored bypass is not a defaulted node: nothing about the catalog failed here");

            ExecutionEvent bypass = monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_BYPASSED)
                    .reduce((first, second) -> {
                        throw new AssertionError("expected exactly one NODE_BYPASSED event");
                    })
                    .orElseThrow(() -> new AssertionError("no NODE_BYPASSED event was published"));
            assertEquals("b", bypass.nodeId());
            assertEquals("authored bypass", bypass.detail(),
                    "the author's own switch must be distinguishable from the command/test-mode bypass");

            // The half that actually reaches a browser. `detail` is an in-process diagnostic and is
            // never serialized on either event route, so an activity panel can only see the
            // difference through publicReason and the sentence derived from it. Asserted here, next
            // to the detail, precisely so the two cannot drift
            // apart -- a change that renamed one and not the other would leave this test half-red
            // rather than silently green.
            assertEquals(ai.ravenroot.api.application.ExecutionEvent.BYPASS_REASON_AUTHORED,
                    bypass.publicReason());
            assertEquals("Node was bypassed: the graph author switched this node off.",
                    ai.ravenroot.api.application.PublicExecutionDescription.forType(
                            bypass.type(), bypass.publicReason()));
        }
    }

    /**
     * The negative control for the event half: the command bypass keeps the detail and now gains the
     * other classifier, so a panel showing the derived sentence tells "this whole run is a rehearsal"
     * apart from "this one node is switched off". Runs the sticky-ceiling shape rather than
     * {@code TEST_PASSTHROUGH}, to prove the classifier keys on the delivered command and not on the
     * submission policy.
     */
    @Test
    void theCommandBypassKeepsItsOwnDetailAndClassifier() throws Exception {
        var registry = new BehaviorRegistry().registerFactory(factory("workerBehavior", new AtomicInteger(),
                message -> CompletableFuture.completedFuture(NodeResult.continueWith("changed"))));
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("worker", "workerBehavior"),
                GraphNode.error("error"),
                GraphNode.end("end")),
                List.of(
                        new GraphEdge("start", "worker", "continue", Map.of("command", "passthrough")),
                        GraphEdge.to("worker", "end")));

        var monitor = new ExecutionMonitor();
        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, monitor)) {
            runner.execute(TestIdentities.TENANT_A, "original").toCompletableFuture().get(10, TimeUnit.SECONDS);

            ExecutionEvent bypass = monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_BYPASSED)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no NODE_BYPASSED event was published"));
            assertEquals("incoming command=passthrough", bypass.detail(),
                    "the original command-bypass detail is on the wire for log readers and must not change");
            assertEquals(ai.ravenroot.api.application.ExecutionEvent.BYPASS_REASON_COMMAND,
                    bypass.publicReason());
            assertEquals("Node was bypassed: the traversal was not executing node behaviours.",
                    ai.ravenroot.api.application.PublicExecutionDescription.forType(
                            bypass.type(), bypass.publicReason()));
        }
    }

    /**
     * The flag names a behaviour to skip, so a node that has none is a
     * statement with no referent rather than a harmless no-op. Refused at construction, before any
     * actor exists, on the {@link NodeRuntimeNatureValidator} precedent — {@link BehaviorPropertySchema}
     * returns early on exactly these node kinds, which is why this cannot live there.
     *
     * <p>The node ids here are {@code n1}/{@code n2}/{@code n3} rather than the usual
     * {@code start}/{@code end}/{@code error}, because the last assertion is a substring check against
     * the public message — and that message legitimately contains the English words "start", "end" and
     * "error" while explaining the rule. With the conventional ids the check passes or fails on the
     * prose rather than on whether a node id leaked, which is not the property being tested.</p>
     */
    @Test
    void theFlagIsRefusedOnTerminalsAndOnStart() {
        for (GraphNode offender : List.of(
                new GraphNode("n1", NodeKind.START, null, Map.of("execution.bypass", "true")),
                new GraphNode("n2", NodeKind.END, null, Map.of("execution.bypass", "true")),
                new GraphNode("n3", NodeKind.ERROR, null, Map.of("execution.bypass", "true")))) {
            var nodes = new java.util.ArrayList<GraphNode>(List.of(GraphNode.start("n1"),
                    GraphNode.end("n2"), GraphNode.error("n3")));
            nodes.replaceAll(node -> node.id().equals(offender.id()) ? offender : node);

            var graph = new GraphDefinition(nodes, List.of(GraphEdge.to("n1", "n2")));
            NodeBypassException refusal = assertThrows(NodeBypassException.class,
                    () -> new GraphRunner(GraphManager.from(graph), engine, new BehaviorRegistry(),
                            new ExecutionMonitor()));

            assertEquals(NodeBypassException.Reason.DECLARED_ON_NON_BEHAVIOR_NODE, refusal.reason());
            assertEquals(offender.id(), refusal.diagnosticDetail().get("nodeId"));
            assertFalse(refusal.getMessage().contains(offender.id()),
                    "graph-derived text stays out of the public message, as NodeRuntimeNatureException requires");
        }
    }

    /**
     * A value that is neither {@code true} nor {@code false} refuses the graph instead of being read
     * as "not bypassed". Reading it as {@code false} would execute a node the author believed was
     * switched off — quietly, on the strength of a typo — which is the one failure this flag exists
     * to prevent. Same argument as {@code NodeRuntimeNatureException.Reason.UNKNOWN_NATURE}: refused
     * rather than defaulted, because defaulting turns a typo into a silent change of behaviour.
     */
    @Test
    void aValueThatIsNeitherTrueNorFalseRefusesTheGraph() {
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("b", NodeKind.BEHAVIOR, "bBehavior", Map.of("execution.bypass", "yes")),
                GraphNode.error("error"),
                GraphNode.end("end")),
                List.of(GraphEdge.to("start", "b"), GraphEdge.to("b", "end")));
        var registry = new BehaviorRegistry().registerFactory(factory("bBehavior", new AtomicInteger(),
                message -> CompletableFuture.completedFuture(NodeResult.continueWith("ran"))));

        NodeBypassException refusal = assertThrows(NodeBypassException.class,
                () -> new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor()));

        assertEquals(NodeBypassException.Reason.UNPARSEABLE_VALUE, refusal.reason());
        assertEquals("yes", refusal.diagnosticDetail().get("declaredBypass"));
        assertFalse(refusal.getMessage().contains("yes"),
                "the offending value stays in the diagnostic map, never in the public message");
    }

    /**
     * The consequence the author must be told about, proven rather than described: a
     * <em>decision</em> node that is switched off does not choose. It emits
     * {@code GraphEdge.DEFAULT_OUTCOME}, so it takes the {@code continue} edge and its named branches
     * are not merely not-selected-this-run — they were never candidates.
     *
     * <p>The runtime declares that in {@code GraphExecutionResult#untakenEdges()}, which is the same
     * report already used for the command bypass. Reusing it is the point: an author reading a run has
     * one place to look for "which edges did this bypass remove", regardless of which of the two
     * bypasses removed them.</p>
     */
    @Test
    void aSwitchedOffDecisionNodeTakesTheDefaultEdgeAndNamesTheBranchesItDidNotTake() throws Exception {
        var approvedRuns = new AtomicInteger();
        var afterRuns = new AtomicInteger();
        var registry = new BehaviorRegistry()
                .registerFactory(factory("gate", new AtomicInteger(), message ->
                        CompletableFuture.completedFuture(new NodeResult("approved", "gated", Map.of()))))
                .registerFactory(factory("approvedBehavior", new AtomicInteger(), message -> {
                    approvedRuns.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith("approved-out"));
                }))
                .registerFactory(factory("afterBehavior", new AtomicInteger(), message -> {
                    afterRuns.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith("after-out"));
                }));

        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("gate", NodeKind.BEHAVIOR, "gate", Map.of("execution.bypass", "true")),
                GraphNode.behavior("approvedNode", "approvedBehavior"),
                GraphNode.behavior("after", "afterBehavior"),
                GraphNode.error("error"),
                // joinQuorum=1, the "quorum of one" shape an if/else convergence needs whether or not
                // anything is switched off: exactly one of the two branches ever arrives. Measured
                // the hard way while writing this test -- with the default quorum of 2 the run fails
                // with JoinFailureException ("quorum 2 of 2 ... notTaken=[approvedNode]"), which is
                // correct and is the pre-existing rule, not something authored bypass introduces. It is called
                // out in the user documentation because switching a decision node off is a plausible
                // way to meet it for the first time.
                new GraphNode("end", NodeKind.END, null, Map.of("joinQuorum", "1"))),
                List.of(
                        GraphEdge.to("start", "gate"),
                        new GraphEdge("gate", "approvedNode", "approved"),
                        GraphEdge.to("gate", "after"),
                        GraphEdge.to("after", "end"),
                        GraphEdge.to("approvedNode", "end")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor())) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "in")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, approvedRuns.get(),
                    "the named branch is not taken: a node that did not run did not choose it");
            assertEquals(1, afterRuns.get(), "the default branch is taken and executes for real");
            assertEquals("after-out", result.payload());
            assertEquals(Set.of("gate"), result.bypassedNodes());
            assertEquals(Set.of("gate->approvedNode [outcome=approved]"), result.untakenEdges(),
                    "the branch the author lost by switching the node off must be named");
        }
    }

    /**
     * The boundary of the feature, pinned rather than described: <b>the flag skips execution, not
     * validation.</b>
     *
     * <p>A catalogued behavior with a {@code required} property left blank refuses the whole graph
     * even when the node carrying it is switched off. {@link BehaviorPropertySchema} runs first in
     * {@code GraphRunner}'s constructor — before {@code NodeBypassValidator}, before
     * {@code authoredBypassNodes} is even computed — and it has exactly one exemption, CORE-07's
     * {@code namesNoAdapter}, which is driven by {@link
     * ai.ravenroot.api.catalog.NodePropertyDescriptor#adapterBinding()} and knows nothing about this
     * flag.</p>
     *
     * <p><b>This is not a regression and the negative control below is what proves it</b>: the same
     * graph without the flag fails identically, so the flag neither causes nor worsens the refusal.
     * What it does is bound the motivating case. "An AI node with no adapter configured" is covered
     * — that is precisely what {@code namesNoAdapter} exempts. "A programmable artifact not created
     * yet" is not, whenever the gap shows up as an empty required property that is not an adapter
     * binding. The author's remedy is a placeholder value in that property; the flag cannot supply
     * one, because a graph the runtime refuses never reaches the runner that would read the flag.</p>
     *
     * <p>Written as a characterization test on purpose. A future contract may decide whether a
     * switched-off node should be exempted from required-property validation as well; if the answer
     * is yes, this test inverts visibly instead of being quietly deleted, as does the residency
     * refusal in {@code NodeRuntimeNatureValidator}.</p>
     */
    @Test
    void theFlagSkipsExecutionButNotRequiredPropertyValidation() {
        var descriptor = new NodeTypeDescriptor("aiBehavior", "AI", "Test", "", "actor", false,
                // required, and NOT an adapter binding -- so CORE-07's namesNoAdapter exemption does
                // not apply. That is the whole point: the exemption that rescues the unconfigured-
                // adapter case does not reach this one.
                List.of(new ai.ravenroot.api.catalog.NodePropertyDescriptor("model", "Model",
                        ai.ravenroot.api.catalog.NodePropertyType.STRING, true, "", "", List.of())),
                Set.of());
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public NodeHandler create(GraphNode node) {
                throw new AssertionError("the graph is refused before any factory is created");
            }
        });

        BehaviorPropertySchema.BehaviorPropertyException refusal = assertThrows(
                BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> new GraphRunner(GraphManager.from(aiChain(Map.of("execution.bypass", "true"))),
                        engine, registry, new ExecutionMonitor()));
        assertTrue(refusal.getMessage().contains("is required by behavior 'aiBehavior'"),
                "the refusal is the ordinary required-property one, unchanged by the flag: "
                        + refusal.getMessage());

        // Negative control: without the flag the identical graph fails identically. If this half ever
        // went green while the half above stayed red, the flag would be the source of the refusal;
        // required-property validation must instead reject both graphs identically.
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> new GraphRunner(GraphManager.from(aiChain(Map.of())),
                        engine, registry, new ExecutionMonitor()));
    }

    /** {@code start -> ai -> end}, where {@code ai} leaves its required {@code model} property blank. */
    private static GraphDefinition aiChain(Map<String, Object> aiProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("ai", NodeKind.BEHAVIOR, "aiBehavior", aiProperties),
                GraphNode.error("error"),
                GraphNode.end("end")),
                List.of(GraphEdge.to("start", "ai"), GraphEdge.to("ai", "end")));
    }

    /**
     * No descriptor may declare the platform-owned key,
     * because the descriptor's own type/allowed values and the platform's {@code true}/{@code false}
     * would then be two authorities over one name. Same refusal, and the same reason, as
     * {@code NodeRuntimeNatureProperty#validateShape}.
     */
    @Test
    void noBehaviorDescriptorMayDeclareThePlatformOwnedKey() {
        var descriptor = new NodeTypeDescriptor("greedy", "Greedy", "Test", "", "actor", false,
                List.of(new ai.ravenroot.api.catalog.NodePropertyDescriptor("execution.bypass", "Bypass",
                        ai.ravenroot.api.catalog.NodePropertyType.BOOLEAN, false, "", "false",
                        List.of("true", "false"))),
                Set.of());

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> ai.ravenroot.api.catalog.NodeTypeDescriptorValidator.validate(descriptor));
        assertTrue(refusal.getMessage().contains("execution.bypass"));
        assertTrue(refusal.getMessage().contains("platform-owned"));
    }

    /** {@code start -> a -> b -> c -> end}, with {@code b} carrying the authored bypass flag. */
    private static GraphDefinition chainWithBypassedB() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("a", "aBehavior"),
                new GraphNode("b", NodeKind.BEHAVIOR, "bBehavior", Map.of("execution.bypass", "true")),
                GraphNode.behavior("c", "cBehavior"),
                GraphNode.error("error"),
                GraphNode.end("end")),
                List.of(
                        GraphEdge.to("start", "a"),
                        GraphEdge.to("a", "b"),
                        GraphEdge.to("b", "c"),
                        GraphEdge.to("c", "end")));
    }

    private static NodeBehaviorFactory factory(String behavior, AtomicInteger creates, NodeHandler handler) {
        return new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(behavior, behavior, "Test", "", "actor", false,
                        List.of(), Set.of());
            }

            @Override
            public NodeHandler create(GraphNode node) {
                creates.incrementAndGet();
                return handler;
            }
        };
    }
}
