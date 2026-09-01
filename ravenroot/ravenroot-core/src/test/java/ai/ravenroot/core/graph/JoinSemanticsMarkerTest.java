package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The document half: how the semantics marker travels, and what is refused at load.
 *
 * <p>The runtime half — that an undeclared fan-in actually runs once per arrival while a declared
 * one still coordinates — is {@code ExplicitJoinSemanticsTest}. Split because the two answer
 * different questions with different evidence: this class asks what a <em>document</em> says and
 * what loading it does, and never starts a traversal.</p>
 */
class JoinSemanticsMarkerTest {

    /**
     * The cyclic legacy drawing: {@code Start -> Delay}, {@code Delay -> Delay},
     * {@code Delay -> SecondDelay}, both delays to {@code End}, and a {@code failed} outcome edge to
     * an error terminal. No marker, because it was drawn before one existed.
     */
    private static final String OWNERS_DRAWING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kkind" for="node" attr.name="kind" attr.type="string"/>
              <key id="koutcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="owners-drawing" edgedefault="directed">
                <node id="start"><data key="kkind">START</data></node>
                <node id="delay"><data key="kkind">PASSTHROUGH</data></node>
                <node id="secondDelay"><data key="kkind">PASSTHROUGH</data></node>
                <node id="error"><data key="kkind">ERROR</data></node>
                <node id="end"><data key="kkind">END</data></node>
                <edge id="e1" source="start" target="delay"><data key="koutcome">continue</data></edge>
                <edge id="e2" source="delay" target="delay"><data key="koutcome">repeat</data></edge>
                <edge id="e3" source="delay" target="secondDelay"><data key="koutcome">continue</data></edge>
                <edge id="e4" source="delay" target="end"><data key="koutcome">done</data></edge>
                <edge id="e5" source="secondDelay" target="end"><data key="koutcome">continue</data></edge>
                <edge id="e6" source="delay" target="error"><data key="koutcome">failed</data></edge>
              </graph>
            </graphml>
            """;

    private static String withMarker(String document) {
        return document
                .replace("<key id=\"kkind\"",
                        "<key id=\"kjs\" for=\"graph\" attr.name=\"" + JoinSemantics.MARKER_PROPERTY
                                + "\" attr.type=\"string\"/>\n  <key id=\"kkind\"")
                .replace("edgedefault=\"directed\">",
                        "edgedefault=\"directed\">\n    <data key=\"kjs\">" + JoinSemantics.DECLARED + "</data>");
    }

    private static GraphManager read(String document) {
        return GraphManager.readGraphMl(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ the marker reaches the core

    /**
     * Without graph-scoped marker capture, this exact document imports cleanly, is reported as a
     * {@code PRESERVED} key and exports back byte for byte, while
     * {@code definition().properties()} is empty — TinkerPop's {@code GraphMLReader} builds a graph
     * out of vertices and edges and has nowhere to put a datum belonging to the graph itself, so it
     * drops it in silence. A marker nothing downstream can read versions nothing.
     */
    @Test
    void aGraphScopedDataReachesTheDefinitionInsteadOfBeingDroppedByTheReader() {
        try (var manager = read(withMarker(OWNERS_DRAWING))) {
            assertEquals(JoinSemantics.DECLARED,
                    manager.definition().properties().get(JoinSemantics.MARKER_PROPERTY),
                    "the graph-level marker must survive the import, or nothing versions the semantics");
            assertTrue(JoinSemantics.declaredJoinsOnly(manager.definition()));
        }
    }

    /** A graph-scoped {@code <key>} default is a declaration too, with the explicit value winning. */
    @Test
    void aGraphScopedKeyDefaultSuppliesTheMarkerAndAnExplicitValueOverridesIt() {
        String defaulted = OWNERS_DRAWING.replace("<key id=\"kkind\"",
                "<key id=\"kjs\" for=\"graph\" attr.name=\"" + JoinSemantics.MARKER_PROPERTY
                        + "\" attr.type=\"string\"><default>" + JoinSemantics.DECLARED
                        + "</default></key>\n  <key id=\"kkind\"");
        try (var manager = read(defaulted)) {
            assertTrue(JoinSemantics.declaredJoinsOnly(manager.definition()));
        }
    }

    /**
     * Verbatim export is what makes "touch no stored byte" true rather than intended: an imported
     * document is written back exactly as it arrived, marker and all.
     */
    @Test
    void anImportedMarkerIsExportedVerbatim() {
        String source = withMarker(OWNERS_DRAWING);
        try (var manager = read(source)) {
            var output = new ByteArrayOutputStream();
            manager.writeGraphMl(output);
            assertEquals(source, output.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * The known gap, pinned rather than left to be discovered. TinkerPop's {@code GraphMLWriter}
     * serialises vertices and edges, so a definition assembled in Java and exported through it loses
     * its graph-level properties. No production path does that — the editor authors GraphML text and
     * every server ingress is an imported document — but the day one does, this test names it
     * instead of the marker quietly disappearing.
     */
    @Test
    void aDefinitionBuiltInJavaKeepsItsMarkerInMemoryAndLosesItOnGraphMlWriterExport() {
        var definition = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
        try (var manager = GraphManager.from(definition)) {
            assertTrue(JoinSemantics.declaredJoinsOnly(manager.definition()),
                    "the marker must round-trip in memory");
            var output = new ByteArrayOutputStream();
            manager.writeGraphMl(output);
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(JoinSemantics.MARKER_PROPERTY),
                    "if GraphMLWriter ever learns to write graph-level data, this expectation is the "
                            + "thing to update, deliberately");
        }
    }

    /**
     * A graph-scoped reserved key is excluded from the interpreted properties, not refused.
     *
     * <p>{@code GraphMlProfileReportTest.reportsAReservedKeyThatIngestDidNotRefuseInsteadOfLetting
     * ItPassAsOrdinary} pins, deliberately, that such a key is accepted today: SEC-09's refusal walks
     * vertices and edges, so it never fires at graph scope, and closing that is a SEC-09 decision
     * rather than this marker's. The marker channel must not widen the gap — a
     * {@code ravenroot.} key remains inert.</p>
     */
    @Test
    void aReservedGraphLevelPropertyStaysAcceptedAndNeverBecomesInterpretedState() {
        String reserved = withMarker(OWNERS_DRAWING)
                .replace("<key id=\"kkind\"",
                        "<key id=\"kbad\" for=\"graph\" attr.name=\"ravenroot.tenant\" attr.type=\"string\"/>\n  <key id=\"kkind\"")
                .replace("edgedefault=\"directed\">",
                        "edgedefault=\"directed\">\n    <data key=\"kbad\">other</data>");
        try (var manager = read(reserved)) {
            var properties = manager.definition().properties();
            assertFalse(properties.containsKey("ravenroot.tenant"),
                    "a reserved graph-scoped key must not become readable graph state");
            assertEquals(JoinSemantics.DECLARED, properties.get(JoinSemantics.MARKER_PROPERTY),
                    "and excluding it must not cost the document its other graph-level properties");
        }
    }

    // --------------------------------------------------------------- unsatisfiable self-branch, named at load

    /**
     * This test uses the exact drawing that exposes the defect. Without the marker the
     * self-edge still makes {@code delay} its own second predecessor and still infers a join over
     * {@code {delay, start}} — and that join waits for its own completion. It is now a load error
     * with a name instead of a traversal that never terminates.
     */
    @Test
    void theOwnersDrawingIsRefusedAtLoadWithTheSelfInclusionRefusal() {
        try (var manager = read(OWNERS_DRAWING)) {
            var refusal = assertThrows(GraphValidationException.class, manager::definition);
            assertTrue(refusal.violations().stream().anyMatch(v -> v.contains("branches include itself")),
                    refusal.violations().toString());
            assertTrue(refusal.violations().stream().anyMatch(v -> v.contains("'delay'")),
                    "the refusal must name the node the author drew: " + refusal.violations());
        }
    }

    /**
     * The same refusal on the inspection channel, which is where an author actually meets it —
     * refusing only inside {@code GraphRunner}'s constructor would report it after the document was
     * accepted, hashed and recorded.
     */
    @Test
    void theSameRefusalIsReportedByInspectionRatherThanOnlyThrownByExecution() {
        var report = GraphManager.validateGraphMl(
                new ByteArrayInputStream(OWNERS_DRAWING.getBytes(StandardCharsets.UTF_8)));
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("branches include itself")),
                report.violations().toString());
    }

    /**
     * The other direction: under the marker the same self-edge is
     * repetition. {@code delay} has two predecessors and declares nothing, so it is not a join, so
     * there is no self-inclusion to refuse and the document loads.
     */
    @Test
    void theSameDrawingLoadsUnderTheMarkerBecauseTheSelfLoopIsRepetition() {
        try (var manager = read(withMarker(OWNERS_DRAWING))) {
            var definition = manager.definition();
            assertFalse(JoinSemantics.isJoin(definition, definition.node("delay")),
                    "an undeclared multi-predecessor node is not a join in a marker-present document");
            assertTrue(manager.semanticViolations().isEmpty(), manager.semanticViolations().toString());
        }
    }

    /** A declared join that includes itself is refused under the marker too: declaring it cannot make it satisfiable. */
    @Test
    void aDeclaredSelfIncludingJoinIsRefusedUnderTheMarkerAsWell() {
        String declared = withMarker(OWNERS_DRAWING).replace(
                "<node id=\"delay\"><data key=\"kkind\">PASSTHROUGH</data></node>",
                "<node id=\"delay\"><data key=\"kkind\">PASSTHROUGH</data>"
                        + "<data key=\"kjp\">all</data></node>")
                .replace("<key id=\"kkind\"",
                        "<key id=\"kjp\" for=\"node\" attr.name=\"" + JoinSemantics.POLICY_PROPERTY
                                + "\" attr.type=\"string\"/>\n  <key id=\"kkind\"");
        try (var manager = read(declared)) {
            var refusal = assertThrows(GraphValidationException.class, manager::definition);
            assertTrue(refusal.violations().stream().anyMatch(v -> v.contains("branches include itself")),
                    refusal.violations().toString());
        }
    }

    /** Repetition with no way out is an infinite loop by construction, in either semantics version. */
    @Test
    void aSelfLoopWithNoOtherOutgoingEdgeIsRefused() {
        var nodes = List.of(GraphNode.start("start"), GraphNode.passthrough("spin"), GraphNode.end("end"));
        var edges = List.of(GraphEdge.to("start", "spin"), new GraphEdge("spin", "spin", "repeat"));
        var withoutMarker = assertThrows(GraphValidationException.class,
                () -> new GraphDefinition(nodes, edges));
        assertTrue(withoutMarker.violations().stream().anyMatch(v -> v.contains("no other outgoing")),
                withoutMarker.violations().toString());

        var withMarker = assertThrows(GraphValidationException.class,
                () -> new GraphDefinition(nodes, edges,
                        Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED)));
        assertTrue(withMarker.violations().stream().anyMatch(v -> v.contains("no other outgoing")),
                withMarker.violations().toString());
    }

    /** The complement: a self-loop that can leave is accepted, which is what makes repetition usable. */
    @Test
    void aSelfLoopWithAnExitIsAccepted() {
        new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("spin"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "spin"), new GraphEdge("spin", "spin", "repeat"),
                        GraphEdge.to("spin", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
    }

    // ------------------------------------------------------------------------------- `each` and typos

    @Test
    void eachIsReportedAsRedundantUnderTheMarkerAndNeverRefused() {
        var definition = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("b0", "b0"),
                        GraphNode.behavior("b1", "b1"),
                        new GraphNode("merge", NodeKind.PASSTHROUGH, null,
                                Map.of(JoinSemantics.POLICY_PROPERTY, JoinSemantics.EACH_POLICY)),
                        GraphNode.end("end")),
                List.of(GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                        GraphEdge.to("b0", "merge"), GraphEdge.to("b1", "merge"),
                        GraphEdge.to("merge", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));

        assertTrue(JoinSemantics.redundancies(definition).stream()
                        .anyMatch(notice -> notice.contains("'merge'") && notice.contains("redundant")),
                JoinSemantics.redundancies(definition).toString());
    }

    /**
     * The channel, not just the computation: an author meets a redundancy through {@code validate},
     * and it must arrive beside the violations without becoming one — the document stays valid.
     */
    @Test
    void aRedundantEachIsReportedByInspectionWithoutMakingTheDocumentInvalid() {
        String document = withMarker("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kkind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="kjp" for="node" attr.name="joinPolicy" attr.type="string"/>
                  <graph id="redundant-each" edgedefault="directed">
                    <node id="start"><data key="kkind">START</data></node>
                    <node id="b0"><data key="kkind">PASSTHROUGH</data></node>
                    <node id="b1"><data key="kkind">PASSTHROUGH</data></node>
                    <node id="merge"><data key="kkind">PASSTHROUGH</data><data key="kjp">each</data></node>
                    <node id="end"><data key="kkind">END</data></node>
                    <edge id="e1" source="start" target="b0"/>
                    <edge id="e2" source="start" target="b1"/>
                    <edge id="e3" source="b0" target="merge"/>
                    <edge id="e4" source="b1" target="merge"/>
                    <edge id="e5" source="merge" target="end"/>
                  </graph>
                </graphml>
                """);
        var report = GraphManager.validateGraphMl(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
        assertTrue(report.valid(), report.violations().toString());
        assertEquals(List.of(), report.violations());
        assertTrue(report.redundancies().stream().anyMatch(notice -> notice.contains("'merge'")),
                report.redundancies().toString());
    }

    @Test
    void eachIsNotRedundantWithoutTheMarkerBecauseThereItStillChangesTheReading() {
        var definition = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("b0", "b0"),
                        GraphNode.behavior("b1", "b1"),
                        new GraphNode("merge", NodeKind.PASSTHROUGH, null,
                                Map.of(JoinSemantics.POLICY_PROPERTY, JoinSemantics.EACH_POLICY)),
                        GraphNode.end("end")),
                List.of(GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                        GraphEdge.to("b0", "merge"), GraphEdge.to("b1", "merge"),
                        GraphEdge.to("merge", "end")));
        assertEquals(List.of(), JoinSemantics.redundancies(definition));
    }

    /** A marker value that is not the one word keeps the legacy reading, and says so. */
    @Test
    void aMarkerValueThatSelectsNothingIsReportedRatherThanGuessedAt() {
        var definition = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")),
                Map.of(JoinSemantics.MARKER_PROPERTY, "explicit"));
        assertFalse(JoinSemantics.declaredJoinsOnly(definition));
        assertTrue(JoinSemantics.redundancies(definition).stream()
                .anyMatch(notice -> notice.contains("selects nothing")));
    }

    // ------------------------------------------------------------------- migration and the graph hash

    /**
     * The hash is a graph's address, so a document with no graph-level property must encode to
     * exactly the bytes it encoded before the marker existed. An unconditional empty section would
     * have re-addressed every version already recorded.
     */
    @Test
    void aGraphWithNoGraphLevelPropertyHashesToTheValuePinnedBeforeThisChange() {
        var definition = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")));
        // Computed by running GraphCanonicalForm.sha256 on this same graph against dev at 28af5d9,
        // before graph-level properties were retained. A literal, not a re-derivation: a test that recomputes the
        // expectation from the code under test cannot detect the code changing.
        assertEquals("7482fa074967d693c481b4c74cf50051d8b87e8bdc81d1b36a4195b6056bc7c2",
                GraphCanonicalForm.sha256(definition),
                "a graph with no graph-level property must preserve its earlier hash");
        assertEquals(GraphCanonicalForm.sha256(definition),
                GraphCanonicalForm.sha256(new GraphDefinition(
                        List.of(GraphNode.start("start"), GraphNode.end("end")),
                        List.of(GraphEdge.to("start", "end")), Map.of())),
                "an explicitly empty property map and no map at all must be the same graph");
    }

    /**
     * The other side of that pin, and the limit of the claim it makes.
     *
     * <p>The preservation above covers the <em>empty</em> case only. A document that already carried
     * some other graph-level {@code <data>} previously had it dropped — nothing read it — and now
     * has it, so its digest moves. Asserted rather than left implicit, because claiming the wider
     * behavior in {@link GraphCanonicalForm} would overstate what the code delivers. The changed
     * digest is accepted and safe because this
     * digest is process-local: the persisted version pin is taken over the raw document bytes, which
     * the marker does not change.</p>
     */
    @Test
    void anUnrelatedGraphLevelPropertyAlsoMovesTheDigestAndThatIsTheLimitOfThePin() {
        var nodes = List.of(GraphNode.start("start"), GraphNode.end("end"));
        var edges = List.of(GraphEdge.to("start", "end"));
        assertNotEquals(
                GraphCanonicalForm.sha256(new GraphDefinition(nodes, edges)),
                GraphCanonicalForm.sha256(new GraphDefinition(nodes, edges, Map.of("title", "payroll"))),
                "a graph-level property this runtime does not interpret is still part of the graph");
    }

    /** And a document that carries the marker is a different graph, which is what migration needs. */
    @Test
    void theMarkerChangesTheHash() {
        var legacy = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "end")));
        var migrated = JoinSemantics.migrate(legacy);
        assertNotEquals(GraphCanonicalForm.sha256(legacy), GraphCanonicalForm.sha256(migrated));
    }

    /**
     * Migration records what the document already meant. An ordinary fan-in gets {@code all}; the
     * error terminal gets {@code joinQuorum=1} and never {@code each}, because {@code each} would
     * remove the terminal's single firing rather than record it.
     */
    @Test
    void migrationMaterialisesTheEffectivePolicyAndStampsTheMarker() {
        var legacy = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("b0", "b0"),
                        GraphNode.behavior("b1", "b1"), GraphNode.passthrough("merge"),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                        GraphEdge.to("b0", "merge"), GraphEdge.to("b1", "merge"),
                        GraphEdge.to("merge", "end"),
                        new GraphEdge("b0", "error", "failed"), new GraphEdge("b1", "error", "failed")));

        var migrated = JoinSemantics.migrate(legacy);

        assertEquals("all", migrated.node("merge").properties().get(JoinSemantics.POLICY_PROPERTY));
        assertEquals("1", migrated.node("error").properties().get(JoinSemantics.QUORUM_PROPERTY));
        assertFalse(migrated.node("error").properties().containsValue(JoinSemantics.EACH_POLICY));
        assertTrue(JoinSemantics.declaredJoinsOnly(migrated));
        assertEquals(Map.of(), migrated.node("b0").properties(), "a non-join is left alone");
    }

    /** An author's own words win, and running migration twice changes nothing the second time. */
    @Test
    void migrationLeavesAuthoredPoliciesAloneAndIsIdempotent() {
        var legacy = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("b0", "b0"),
                        GraphNode.behavior("b1", "b1"),
                        new GraphNode("merge", NodeKind.PASSTHROUGH, null,
                                Map.of(JoinSemantics.QUORUM_PROPERTY, "1")),
                        GraphNode.end("end")),
                List.of(GraphEdge.to("start", "b0"), GraphEdge.to("start", "b1"),
                        GraphEdge.to("b0", "merge"), GraphEdge.to("b1", "merge"),
                        GraphEdge.to("merge", "end")));

        var migrated = JoinSemantics.migrate(legacy);
        assertEquals("1", migrated.node("merge").properties().get(JoinSemantics.QUORUM_PROPERTY));
        assertFalse(migrated.node("merge").properties().containsKey(JoinSemantics.POLICY_PROPERTY));
        assertEquals(GraphCanonicalForm.sha256(migrated),
                GraphCanonicalForm.sha256(JoinSemantics.migrate(migrated)));
    }
}
