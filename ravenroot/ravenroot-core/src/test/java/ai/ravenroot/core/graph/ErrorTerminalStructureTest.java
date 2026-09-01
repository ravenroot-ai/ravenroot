package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ERROR} as a node kind, and how many of them a graph may hold.
 *
 * <h2>Optional but bounded</h2>
 * <p>{@code ERROR} is a distinct node kind but is not required in every graph. Its cardinality is
 * zero or one, and this class changed in exactly two places: the "no error terminal" case flipped
 * from refusal to acceptance, and the three-violations case became two. Everything else in the
 * error-terminal model — the kind surviving the parser, the round trip, the refusal of an unknown
 * kind, the ceiling of one — is untouched, because none of it rested on the obligation.</p>
 *
 * <h2>Which of these are mutation-proven, and which are regression guards</h2>
 * <p>Both classes of test are worth keeping, but only mutation-proven tests demonstrate that the code
 * under them is load-bearing.</p>
 *
 * <p><strong>Red against a defect in this codebase, reproducible by restoring it:</strong></p>
 * <ul>
 *   <li>{@link #refusesAKindItDoesNotKnowInsteadOfSilentlyRunningItAsAPassthrough()} — restore
 *       {@code GraphManager.nodeKind}'s {@code catch (IllegalArgumentException ignored)} and this
 *       reports {@code Expected GraphValidationException to be thrown, but nothing was thrown}. A kind
 *       nobody declared was read as a passage, so a document whose author meant something the runtime
 *       never learned ran anyway.</li>
 *   <li>{@link #refusesAGraphWithTwoErrorTerminals()} — remove the error-cardinality rule from
 *       {@code GraphDefinition.validate()}, or raise {@code MAX_ERROR_NODES}, and it goes red. This is
 *       the half of the rule that outlived the obligation, and the half a correctness property in
 *       {@code GraphRunner} depends on.</li>
 * </ul>
 *
 * <p><strong>Reversal guards, red against the removed minimum:</strong>
 * {@link #acceptsAGraphWithNoErrorTerminal()} and
 * {@link #reportsTheSurvivingMinimalStructureViolationsTogether()}. Restore
 * {@code MIN_ERROR_NODES = 1} and both go red — the first with the refusal it exists to say is gone,
 * the second with a third violation back in the list. They are the mirror image of the two tests that
 * stood here before, kept in the same shape so the diff reads as a reversal rather than as a
 * deletion.</p>
 *
 * <p><strong>Regression guards, not mutation-proven:</strong>
 * {@link #parsingPreservesADeclaredErrorTerminalInsteadOfDegradingItToAPassthrough()} and
 * {@link #aRoundTripThroughTheFormatPreservesTheErrorTerminal()}. These were red before {@code ERROR}
 * existed as an enum constant — {@code expected: <ERROR> but was: <PASSTHROUGH>}, the silent
 * degradation the terminal model prevents — but that state is not a mutation of this codebase, it is the
 * codebase before the type. Restoring the {@code catch} alone leaves them <em>green</em>, and can
 * only leave them green: {@code NodeKind.valueOf("ERROR")} now succeeds, so the {@code catch} is
 * never entered on this input. What they still buy is the round trip itself — that the constant is
 * written to the document, read back, and not collapsed into a neighbouring kind by some later change
 * to the parser or the writer. That is worth a test; it is not proof that any particular line is
 * load-bearing.</p>
 *
 * <p>{@link #stillInfersTheKindWhenTheDocumentDeclaresNoneAtAll()} is a control and is green
 * throughout by design: the refusal above is of a kind that was <em>declared and is unknown</em>,
 * never of a kind that was not declared, which GraphML authors legitimately omit and which
 * {@code defaults-and-scopes.graphml} exercises. If it ever goes red, the refusal has widened past
 * what it was meant to catch.</p>
 */
class ErrorTerminalStructureTest {

    /** The minimal structure: one start, one passage, one error terminal, one end. */
    private static GraphDefinition minimal() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.passthrough("dosomething"),
                GraphNode.error("error"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "dosomething"),
                GraphEdge.to("dosomething", "end"),
                new GraphEdge("dosomething", "error", null,
                        java.util.Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE))));
    }

    private static byte[] graphMl(String nodes, String edges) {
        return ("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                """ + nodes + edges + """
                  </graph>
                </graphml>
                """).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsingPreservesADeclaredErrorTerminalInsteadOfDegradingItToAPassthrough() {
        byte[] source = graphMl("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="end"><data key="kind">END</data></node>
                """, """
                    <edge id="e1" source="start" target="end"/>
                    <edge id="e2" source="start" target="error"/>
                """);

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals(NodeKind.ERROR, manager.definition().node("error").kind(),
                    "a declared error terminal must survive the parser, not arrive as a passage");
        }
    }

    @Test
    void aRoundTripThroughTheFormatPreservesTheErrorTerminal() {
        var output = new ByteArrayOutputStream();
        try (var written = GraphManager.from(minimal())) {
            written.writeGraphMl(output);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("ERROR"),
                () -> "the exported document does not even name the kind: " + output);

        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(output.toByteArray()))) {
            var definition = reread.definition();
            assertEquals(NodeKind.ERROR, definition.node("error").kind());
            assertEquals(NodeKind.END, definition.node("end").kind());
            assertEquals(NodeKind.PASSTHROUGH, definition.node("dosomething").kind());
        }
    }

    @Test
    void refusesAKindItDoesNotKnowInsteadOfSilentlyRunningItAsAPassthrough() {
        byte[] source = graphMl("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="mystery"><data key="kind">SUBGRAPH</data></node>
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="end"><data key="kind">END</data></node>
                """, """
                    <edge id="e1" source="start" target="mystery"/>
                    <edge id="e2" source="mystery" target="end"/>
                    <edge id="e3" source="mystery" target="error"/>
                """);

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var refusal = assertThrows(GraphValidationException.class, manager::definition);
            assertEquals(List.of("Node 'mystery' declares an unknown kind 'SUBGRAPH'; "
                            + "the known kinds are START, PASSTHROUGH, BEHAVIOR, END, ERROR"),
                    refusal.violations());
        }
    }

    @Test
    void stillInfersTheKindWhenTheDocumentDeclaresNoneAtAll() {
        byte[] source = graphMl("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="undeclared"/>
                    <node id="named"><data key="behavior">template</data></node>
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="end"><data key="kind">END</data></node>
                """, """
                    <edge id="e1" source="start" target="undeclared"/>
                    <edge id="e2" source="undeclared" target="named"/>
                    <edge id="e3" source="named" target="end"/>
                    <edge id="e4" source="named" target="error"/>
                """);

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var definition = manager.definition();
            assertEquals(NodeKind.PASSTHROUGH, definition.node("undeclared").kind(),
                    "an absent kind is not an unknown kind: GraphML lets an author omit it");
            assertEquals(NodeKind.BEHAVIOR, definition.node("named").kind());
        }
    }

    /**
     * The assertion that reversed when the minimum became zero: this graph was previously refused and
     * is accepted now.
     *
     * <p>Written as a positive assertion about the resulting definition rather than as "no exception
     * was thrown", so that it says what the graph <em>is</em> — a valid two-node workflow whose author
     * chose not to route failures anywhere — instead of only what did not happen.</p>
     */
    @Test
    void acceptsAGraphWithNoErrorTerminal() {
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.end("end")), List.of(GraphEdge.to("start", "end")));

        assertEquals(2, definition.nodes().size());
        assertTrue(definition.nodes().stream().noneMatch(node -> node.kind() == NodeKind.ERROR),
                "the graph under test must genuinely declare no error terminal");
    }

    /**
     * The retained half of the rule, whose reason is not the former obligation: a second error
     * terminal would give {@code GraphRunner.ExecutionState.errorTerminalPayload} — one field per
     * terminal <em>kind</em> — two concurrent writers that none of the three mechanisms enumerated
     * there covers. That is a correctness matter, independent of whether the terminal is required at
     * all, which is why lifting the floor left the ceiling exactly where it was.
     */
    @Test
    void refusesAGraphWithTwoErrorTerminals() {
        var refusal = assertThrows(GraphValidationException.class, () -> new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.error("first"),
                GraphNode.error("second"),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "end"),
                GraphEdge.to("start", "first"),
                GraphEdge.to("start", "second"))));

        assertEquals(List.of("A graph must contain at most one error node, and 2 are declared: first, second"),
                refusal.violations());
    }

    /**
     * The start and end rules survive both adding the error-terminal rule beside them and removing
     * that third one's floor away: a graph with neither terminal still says so twice.
     *
     * <p>This test counts two violations because the error rule remains in the family but its floor is zero:
     * so a graph declaring no error terminal no longer contributes a violation while a graph declaring
     * two still does. Pinning the <em>exact list</em> rather than a subset is what makes that
     * legible: asserting only "contains the start violation" would show nothing about the error rule.</p>
     */
    @Test
    void reportsTheSurvivingMinimalStructureViolationsTogether() {
        var refusal = assertThrows(GraphValidationException.class, () -> new GraphDefinition(
                List.of(GraphNode.passthrough("lonely")), List.of()));

        assertEquals(List.of(
                        "A graph must contain exactly one start node",
                        "A graph must contain exactly one end node"),
                refusal.violations());
    }

    /** The minimal structure is accepted as a whole, so the rules above cannot be vacuously strict. */
    @Test
    void acceptsTheMinimalStructure() {
        assertEquals(4, minimal().nodes().size());
    }
}
