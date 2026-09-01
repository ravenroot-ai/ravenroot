package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCommandRoutingTest {
    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void testPolicyBypassesFactoriesAndActionsAndReportsTheDistinctOutcome() throws Exception {
        var creates = new AtomicInteger();
        var actions = new AtomicInteger();
        var registry = new BehaviorRegistry().registerFactory(factory("worker", Set.of(), creates, message -> {
            actions.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("changed"));
        }));
        var monitor = new ExecutionMonitor();

        try (var runner = new GraphRunner(GraphManager.from(chain("worker", Map.of())), engine, registry, monitor,
                ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "original")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("original", result.payload());
            assertEquals(0, creates.get(), "Play/Test must not initialize a node factory");
            assertEquals(0, actions.get(), "Play/Test must not invoke a node action");
            assertEquals(Set.of("start", "worker", "end"), result.bypassedNodes());
            assertTrue(result.defaultedNodes().isEmpty());
            assertEquals(3, monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_BYPASSED).count());
            assertEquals(0, monitor.eventsAfter(0).stream()
                    .filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED).count());
        }
    }

    @Test
    void anAdmittedNamedCommandReachesTheHandlerAndCanReturnToProcess() throws Exception {
        var creates = new AtomicInteger();
        var seen = new AtomicReference<NodeCommand>();
        var registry = new BehaviorRegistry().registerFactory(factory("corrector", Set.of("correggi"), creates,
                message -> {
                    seen.set(message.command());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            NodeResult.continueWith(message.payload() + "-corrected"));
                }));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("corrector", "corrector"), GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("start", "corrector", "correggi"), edge("corrector", "end", "process")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor())) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "draft")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(NodeCommand.application("correggi"), seen.get());
            assertEquals("draft-corrected", result.payload());
            assertEquals(1, creates.get());
            assertTrue(result.bypassedNodes().isEmpty());
        }
    }

    @Test
    void anUnadmittedNamedCommandFailsBeforeFactoryCreation() {
        var creates = new AtomicInteger();
        var registry = new BehaviorRegistry().registerFactory(factory("corrector", Set.of(), creates,
                message -> java.util.concurrent.CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload()))));

        NodeCommandAdmissionException failure = assertThrows(NodeCommandAdmissionException.class,
                () -> new GraphRunner(GraphManager.from(chain("corrector", Map.of("command", "correggi"))),
                        engine, registry, new ExecutionMonitor()));

        assertEquals("worker", failure.nodeId());
        assertEquals("correggi", failure.command());
        assertEquals(0, creates.get());
    }

    @Test
    void passthroughIsAStickySafetyCeilingOverLaterEdgeCommands() throws Exception {
        var actions = new AtomicInteger();
        var registry = new BehaviorRegistry().register("worker", message -> {
            actions.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("changed"));
        });
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("worker", "worker"), GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("start", "worker", "passthrough"), edge("worker", "end", "process")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor())) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "original")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals("original", result.payload());
            assertEquals(0, actions.get());
            assertEquals(Set.of("worker", "end"), result.bypassedNodes());
        }
    }

    /**
     * Measured characterization of the current reporting gap. In {@code TEST_PASSTHROUGH}, a bypassed
     * node's {@code NodeResult} is hardcoded to
     * outcome {@code "continue"} ({@code GraphRunner#runtimeNode}), so routing's own lookup —
     * {@code graph.nextEdges(node.id(), result.outcome())} -- is always {@code nextEdges(node.id(),
     * "continue")} ({@code GraphRunner#run}, the call this test pins). The same routing step also
     * carries a fallback that retries the lookup with {@code "continue"} when the outcome was
     * something else and matched no edge -- but that fallback does no work for a bypassed node: it
     * would search for the exact string the primary lookup already used, so it changes nothing
     * observable here. Confirmed by mutation, not assumed: removing that fallback's own guard leaves
     * this test green, because both lookups it could ever perform are identical on this graph. A node
     * whose every outgoing edge carries an explicit, non-{@code continue} outcome therefore has no
     * edge selected in test mode for the plain reason that none is named {@code "continue"} -- and
     * everything behind the branch point is never visited.
     *
     * <p>This is a branch that ran out of edges outside END, reached here not because a node produced
     * an outcome no edge matches, but
     * because a bypassed node's outcome is always {@code "continue"} and neither of the branch point's edges
     * is named that. The traversal still completes and carries the dead-ended branch's payload, per
     * the traversal contract.</p>
     *
     * <p>This test pins the current, measured behavior with an executed graph rather than merely quoted
     * source, so a future semantic change must update the assertion deliberately.</p>
     */
    @Test
    void branchPointBehindOnlyCustomOutcomeEdgesIsUnreachableInTestPassthrough() throws Exception {
        var creates = new AtomicInteger();
        var registry = new BehaviorRegistry()
                .registerFactory(factory("gate", Set.of(), creates, message ->
                        java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("gated"))))
                .registerFactory(factory("approvedNode", Set.of(), creates, message ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith("approved-out"))))
                .registerFactory(factory("rejectedNode", Set.of(), creates, message ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith("rejected-out"))));

        // A realistic if/else-converge shape: gate picks exactly one of two mutually exclusive custom
        // outcomes in production, and both branches converge on "end" -- joinQuorum=1 (the same
        // "quorum of one" pattern used for a 3-way fan-in) so a real run over either branch
        // alone completes. Neither branch nor "end" carries any edge whose outcome is "continue".
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("gate", "gate"),
                GraphNode.behavior("approvedNode", "approvedNode"),
                GraphNode.behavior("rejectedNode", "rejectedNode"),
                GraphNode.error("error"),
                new GraphNode("end", NodeKind.END, null, Map.of("joinQuorum", "1"))),
                List.of(
                        GraphEdge.to("start", "gate"),
                        new GraphEdge("gate", "approvedNode", "approved"),
                        new GraphEdge("gate", "rejectedNode", "rejected"),
                        GraphEdge.to("approvedNode", "end"),
                        GraphEdge.to("rejectedNode", "end")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "in")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Only the trunk is bypassed: start and gate. Neither branch behind the branch point, nor "end"
            // itself, is ever reached -- measured, not inferred from reading GraphRunner.java.
            assertEquals(Set.of("start", "gate"), result.bypassedNodes());
            assertEquals(Set.of("start", "gate"), result.visitedNodes());
            assertEquals(0, creates.get(), "TEST_PASSTHROUGH must not create any node factory");

            // Graft 3 (traversal-verdict.md): the branch ran out of edges outside END, so the
            // traversal COMPLETES and carries the dead-ended branch's payload, not null and not the
            // payload of a node the traversal never reached.
            assertEquals("in", result.payload());

            // The passthrough result declares which outgoing edges it did not take, instead of leaving
            // the branch point to be inferred from bypassedNodes()
            // stopping early. See untakenEdgesUnderBypassAreNamedInTheResult for the dedicated,
            // two-part proof (naming + negative control); asserted here too because this is the exact
            // bypass-reporting shape under test.
            assertEquals(Set.of("gate->approvedNode [outcome=approved]", "gate->rejectedNode [outcome=rejected]"),
                    result.untakenEdges());
        }
    }

    /**
     * Bypass reporting is proven as two tests: naming (this one) and a negative control ({@link
     * #untakenEdgesIsEmptyWhenEveryBypassedEdgeIsAlreadyNamedContinue()}) that shows the field does
     * not fire when there is nothing to report, so a caller cannot mistake an always-populated set for
     * a real declaration.
     *
     * <p>Reuses {@link #branchPointBehindOnlyCustomOutcomeEdgesIsUnreachableInTestPassthrough}'s graph
     * verbatim, so the behavior is checked on the exact shape that test already builds rather than
     * on a new one invented for this method. Kept as its own test regardless of the assertion already
     * living there, because a coverage-tool argument ("it's already asserted") is not the same claim
     * as "there exists a test with a matching name and purpose"; the two are not
     * interchangeable.</p>
     *
     * <p>Verified by mutation, not merely by reading the diff: with the {@code if (bypassed)} block in
     * {@code GraphRunner#run} that populates {@code state.untakenEdges} commented out, this test goes
     * red on this exact assertion (expected two entries, {@code result.untakenEdges()} empty) while
     * every other test in this class stays green -- the mutation this class's own characterization
     * test does not, and cannot, catch on its own.</p>
     */
    @Test
    void untakenEdgesUnderBypassAreNamedInTheResult() throws Exception {
        var registry = new BehaviorRegistry()
                .registerFactory(factory("gate", Set.of(), new AtomicInteger(), message ->
                        java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("gated"))))
                .registerFactory(factory("approvedNode", Set.of(), new AtomicInteger(), message ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith("approved-out"))))
                .registerFactory(factory("rejectedNode", Set.of(), new AtomicInteger(), message ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith("rejected-out"))));
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("gate", "gate"),
                GraphNode.behavior("approvedNode", "approvedNode"),
                GraphNode.behavior("rejectedNode", "rejectedNode"),
                GraphNode.error("error"),
                new GraphNode("end", NodeKind.END, null, Map.of("joinQuorum", "1"))),
                List.of(
                        GraphEdge.to("start", "gate"),
                        new GraphEdge("gate", "approvedNode", "approved"),
                        new GraphEdge("gate", "rejectedNode", "rejected"),
                        GraphEdge.to("approvedNode", "end"),
                        GraphEdge.to("rejectedNode", "end")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "in")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(Set.of("gate->approvedNode [outcome=approved]", "gate->rejectedNode [outcome=rejected]"),
                    result.untakenEdges(), "both edges behind the branch point must be named, exactly");
        }
    }

    /**
     * The negative control: {@link GraphExecutionResult#untakenEdges()} must be empty
     * on a passthrough run whose bypassed nodes carry no edge but the default {@code "continue"} one
     * -- the ordinary chain shape {@link #testPolicyBypassesFactoriesAndActionsAndReportsTheDistinctOutcome()}
     * already runs. Pairs with {@link #untakenEdgesUnderBypassAreNamedInTheResult()}: that test shows
     * the field fires when it should, this one shows it does not fire when it should not, so neither a
     * hardcoded non-empty set nor an always-empty stub could pass both.
     */
    @Test
    void untakenEdgesIsEmptyWhenEveryBypassedEdgeIsAlreadyNamedContinue() throws Exception {
        var registry = new BehaviorRegistry().registerFactory(factory("worker", Set.of(), new AtomicInteger(),
                message -> java.util.concurrent.CompletableFuture.completedFuture(
                        NodeResult.continueWith("changed"))));

        try (var runner = new GraphRunner(GraphManager.from(chain("worker", Map.of())), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "original")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(Set.of("start", "worker", "end"), result.bypassedNodes());
            assertTrue(result.untakenEdges().isEmpty(),
                    "no bypassed node in this graph has any non-continue edge to under-report");
        }
    }

    /**
     * {@code untakenEdges} is keyed on the delivered
     * message's own command directive, not on the submission's {@code ExecutionPolicy}, and an
     * ordinary edge can carry {@code command=passthrough} under {@code STANDARD} just as well as a
     * {@code TEST_PASSTHROUGH} submission hardcodes it from {@code start}. The fixture uses
     * {@code policy=STANDARD}, {@code bypassed=[gate]}, and both of gate's edges
     * named, because gate's own outcome under bypass is still hardcoded to {@code "continue"} and
     * neither {@code approved} nor {@code rejected} is that string -- the STANDARD policy changes
     * nothing about why the edges go untaken, only how gate came to be bypassed at all.
     *
     * <p>This also corrects false prose on this class's characterization test:
     * "empty on a clean production run" and "stays empty on every ordinary run" were not qualified by
     * WHY a node is bypassed, and this graph is the counterexample -- STANDARD, one individually
     * bypassed node, non-empty {@code untakenEdges}. The corrected sentences on {@link
     * GraphExecutionResult#untakenEdges()}, {@link ai.ravenroot.api.application.ExecutionOutcome
     * #untakenEdges()} and this class's own characterization test scope the claim to "no bypassed node
     * has a non-continue edge", which this test does not contradict -- it is the other premise
     * (STANDARD implies nothing bypassed) that was false, not that one.</p>
     */
    @Test
    void untakenEdgesFireUnderStandardPolicyWhenAnEdgeIndividuallyBypassesItsTarget() throws Exception {
        var approvedRuns = new AtomicInteger();
        var rejectedRuns = new AtomicInteger();
        var registry = new BehaviorRegistry()
                // "gate" is never invoked -- it is bypassed by the edge into it -- so no factory is
                // registered for it at all, the same way the sticky-ceiling test above registers none
                // for "worker" while it stays under the passthrough command.
                .registerFactory(factory("approvedNode", Set.of(), approvedRuns, message ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith("approved-out"))))
                .registerFactory(factory("rejectedNode", Set.of(), rejectedRuns, message ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith("rejected-out"))));
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("gate", "gate"),
                GraphNode.behavior("approvedNode", "approvedNode"),
                GraphNode.behavior("rejectedNode", "rejectedNode"),
                GraphNode.error("error"),
                new GraphNode("end", NodeKind.END, null, Map.of("joinQuorum", "1"))),
                List.of(
                        edge("start", "gate", "passthrough"),
                        new GraphEdge("gate", "approvedNode", "approved"),
                        new GraphEdge("gate", "rejectedNode", "rejected"),
                        GraphEdge.to("approvedNode", "end"),
                        GraphEdge.to("rejectedNode", "end")));

        // No ExecutionPolicy argument: the four-argument GraphRunner constructor defaults to
        // STANDARD, the same default passthroughIsAStickySafetyCeilingOverLaterEdgeCommands above
        // relies on.
        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor())) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "in")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Measured, not assumed from the TEST_PASSTHROUGH shape above: under STANDARD, "start"
            // itself is never bypassed -- only the node an edge individually names -- because the
            // initial command dispatched to "start" is PROCESS, not PASSTHROUGH; TEST_PASSTHROUGH is
            // the only policy that hardcodes the bypass from "start" itself. This is exactly the
            // Measured fixture: policy=STANDARD, bypassed=[gate].
            assertEquals(Set.of("gate"), result.bypassedNodes(),
                    "only the individually-bypassed node is bypassed under STANDARD -- "
                            + "approvedNode/rejectedNode were never even candidates, gate's own "
                            + "hardcoded outcome dead-ends before either edge can be selected");
            assertEquals(0, approvedRuns.get());
            assertEquals(0, rejectedRuns.get());
            assertEquals(Set.of("gate->approvedNode [outcome=approved]", "gate->rejectedNode [outcome=rejected]"),
                    result.untakenEdges(),
                    "untakenEdges must fire here even though the submission policy is STANDARD");
        }
    }

    @Test
    void fanOutDeliversEachTargetsOwnCommand() throws Exception {
        var left = new AtomicReference<NodeCommand>();
        var right = new AtomicReference<NodeCommand>();
        var registry = new BehaviorRegistry()
                .registerFactory(factory("left", Set.of(), new AtomicInteger(), message -> {
                    left.set(message.command());
                    return java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("left"));
                }))
                .registerFactory(factory("right", Set.of("correggi"), new AtomicInteger(), message -> {
                    right.set(message.command());
                    return java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("right"));
                }));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("left", "left"), GraphNode.behavior("right", "right"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("start", "left", "process"), edge("start", "right", "correggi"),
                GraphEdge.to("left", "end"), GraphEdge.to("right", "end")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor())) {
            runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        assertEquals(NodeCommand.PROCESS, left.get());
        assertEquals(NodeCommand.application("correggi"), right.get());
    }

    @Test
    void conflictingCommandsForTheSameFanOutTargetAreRefusedBeforeThatTargetRuns() {
        var actions = new AtomicInteger();
        var registry = new BehaviorRegistry().registerFactory(factory("worker", Set.of("correggi"),
                new AtomicInteger(), message -> {
                    actions.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("changed"));
                }));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                GraphNode.behavior("worker", "worker"), GraphNode.error("error"), GraphNode.end("end")), List.of(
                edge("start", "worker", "process"), edge("start", "worker", "correggi"),
                GraphEdge.to("worker", "end")));

        try (var runner = new GraphRunner(GraphManager.from(graph), engine, registry, new ExecutionMonitor())) {
            Exception failure = assertThrows(Exception.class,
                    () -> runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                            .get(10, TimeUnit.SECONDS));
            assertInstanceOf(NodeCommandConflictException.class, failure.getCause());
        }
        assertEquals(0, actions.get(), "an ambiguous delivery must never reach the target action");
    }

    @Test
    void testPassthroughNeverConstructsOrInvokesBuiltinsOrAnSdkPackage() throws Exception {
        var constructions = new AtomicInteger();
        var actions = new AtomicInteger();
        // Names, not the real factories: every entry below is registered as a probe stub. The list
        // still carries "mail.send", which is an extension rather than a built-in, for the same
        // reason -- what is under test is that passthrough constructs and invokes NOTHING, whatever
        // is registered. "llm-prompt" and "agent" were dropped when they left the core, to
        // keep this list from reading as a catalog it is not.
        List<String> builtins = List.of("delay", "mail.send", "template", "cel-transform", "log", "http-request",
                "program");
        var registry = new BehaviorRegistry();
        builtins.forEach(name -> registry.registerFactory(factory(name, Set.of(), constructions, message -> {
            actions.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(NodeResult.continueWith("changed"));
        })));
        var packageConstructions = new AtomicInteger();
        var packageActions = new AtomicInteger();
        NodePackages.register(registry, probePackage("plugin-probe", packageConstructions, packageActions));

        var nodes = new java.util.ArrayList<GraphNode>();
        nodes.add(GraphNode.start("start"));
        builtins.forEach(name -> nodes.add(GraphNode.behavior(name, name)));
        nodes.add(GraphNode.behavior("plugin", "plugin-probe"));
        // No ERROR node. It used to sit in this chain between `plugin` and `end` when one was
        // mandatory; the floor is now zero and nothing here ever needed it. A
        // bare edge into an ERROR node is the unhandled-failure route, and under passthrough every
        // node answers `continue`, so leaving it in the chain would sever the chain at `plugin` and
        // this test would assert over a traversal that stopped early -- while looking like a failure
        // of passthrough, which is what it is actually about.
        nodes.add(GraphNode.end("end"));
        var edges = new java.util.ArrayList<GraphEdge>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            edges.add(GraphEdge.to(nodes.get(index).id(), nodes.get(index + 1).id()));
        }

        try (var runner = new GraphRunner(GraphManager.from(new GraphDefinition(nodes, edges)), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), ExecutionPolicy.TEST_PASSTHROUGH)) {
            GraphExecutionResult result = runner.execute(TestIdentities.TENANT_A, "original")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("original", result.payload());
            assertEquals(nodes.stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet()),
                    result.bypassedNodes());
        }
        assertEquals(0, constructions.get());
        assertEquals(0, actions.get());
        assertEquals(0, packageConstructions.get());
        assertEquals(0, packageActions.get());
    }

    private static GraphDefinition chain(String behavior, Map<String, Object> firstEdgeProperties) {
        return new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.behavior("worker", behavior),
                GraphNode.error("error"), GraphNode.end("end")), List.of(new GraphEdge("start", "worker", "continue", firstEdgeProperties),
                GraphEdge.to("worker", "end")));
    }

    private static GraphEdge edge(String source, String target, String command) {
        return new GraphEdge(source, target, "continue", Map.of("command", command));
    }

    private static NodeBehaviorFactory factory(String behavior, Set<String> commands, AtomicInteger creates,
                                               NodeHandler handler) {
        return new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(behavior, behavior, "Test", "", "actor", false,
                        List.of(), Set.of(), null, Set.of(), commands);
            }

            @Override
            public NodeHandler create(GraphNode node) {
                creates.incrementAndGet();
                return handler;
            }
        };
    }

    private static NodePackage probePackage(String behavior, AtomicInteger constructions, AtomicInteger actions) {
        return new NodePackage() {
            @Override public String id() { return "ai.ravenroot.test.plugin-probe"; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() {
                return List.of(new NodeBehavior() {
                    @Override public NodeTypeDescriptor descriptor() {
                        return new NodeTypeDescriptor(behavior, "Plugin probe", "Test", "", "actor", false,
                                List.of(), Set.of());
                    }
                    @Override public NodeAction create(NodeConfiguration configuration) {
                        constructions.incrementAndGet();
                        return message -> {
                            actions.incrementAndGet();
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    NodeResult.continueWith("changed"));
                        };
                    }
                });
            }
        };
    }
}
