package ai.ravenroot.core.graph;

import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nature survives GraphML, and the namespace choice that makes it possible.
 *
 * <h2>Why the unreserved name was not a preference</h2>
 * <p>ADR 0024 §2 requires that graph content may choose an allowed nature. The {@code ravenroot.}
 * namespace is refused from graph content outright, so a reserved key could not have carried a value
 * an author is meant to supply — the first test below is what makes that a demonstrated constraint
 * rather than a design opinion.</p>
 */
class NodeRuntimeNatureGraphMlTest {

    // ------------------------------------------------------------------ the namespace boundary

    @Test
    void aReservedNatureKeyWouldBeRefusedAtIngest() {
        // Demonstrates why NodeRuntimeNatureProperty.NAME is unreserved. Had the nature lived under
        // ravenroot.*, every graph declaring one would be refused as forged platform state, and the
        // ADR's "graph content may choose an allowed nature" would be unimplementable.
        byte[] document = graphMl("ravenroot.nature", "SOURCE");

        var failure = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(new ByteArrayInputStream(document)));
        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, failure.reason());
    }

    @Test
    void theUnreservedNatureKeyIsAdmitted() {
        // The matching admission, so the test above is a statement about the namespace rather than
        // about GraphML rejecting node properties in general.
        try (var manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(graphMl(NodeRuntimeNatureProperty.NAME, "SOURCE")))) {
            assertEquals("SOURCE",
                    manager.definition().node("probe").properties().get(NodeRuntimeNatureProperty.NAME));
        }
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void surviveTheRoundTripByteForByte() {
        // An ordinary unreserved property is preserved by the same lossless contract SEC-09 pins for
        // every other unknown property. Asserted rather than assumed: "it comes for free" is the kind
        // of claim that stops being true the moment someone adds a normalisation step.
        byte[] source = graphMl(NodeRuntimeNatureProperty.NAME, "SOURCE");
        var output = new ByteArrayOutputStream();

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            manager.writeGraphMl(output);
        }

        assertArrayEquals(source, output.toByteArray());
    }

    @Test
    void natureAndConcurrencySurviveOneRoundTripTogether() {
        byte[] source = graphMlWithRuntimeProperties();
        var output = new ByteArrayOutputStream();
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals("TRAVERSAL", manager.definition().node("probe").properties()
                    .get(NodeRuntimeNatureProperty.NAME));
            assertEquals(1, manager.definition().node("probe").properties()
                    .get(NodeRuntimeMaxConcurrencyProperty.NAME));
            manager.writeGraphMl(output);
        }
        assertArrayEquals(source, output.toByteArray());
    }

    @Test
    void isNotMaterialisedIntoAGraphThatDeclaredNothing() {
        // The effective default stays computed, never written back. Materialising it would change the
        // document on import and break the round trip BehaviorPropertySchema and GraphMlCorpusTest
        // both hold, for a value any reader can derive from the catalog.
        byte[] source = graphMlWithoutNature();
        var output = new ByteArrayOutputStream();

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertFalse(manager.definition().node("probe").properties()
                            .containsKey(NodeRuntimeNatureProperty.NAME),
                    "an absent nature must stay absent in the parsed definition");
            manager.writeGraphMl(output);
        }

        assertArrayEquals(source, output.toByteArray());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(NodeRuntimeNatureProperty.NAME));
    }

    // ------------------------------------------------------------------ versioning

    @Test
    void changingTheNatureIsABreakingGraphChange() {
        // A declaration that changes which lifecycle a node gets is not a compatible edit. This holds
        // by construction because the nature is an ordinary node property and GraphSemanticDiff
        // compares whole nodes -- recorded here so a future change that moved the nature out of the
        // property map cannot quietly make it a compatible one.
        GraphDefinition before = definition(Map.of());
        GraphDefinition after = definition(Map.of(NodeRuntimeNatureProperty.NAME, "SOURCE"));

        GraphCompatibilityReport report = GraphCompatibilityReport.analyze(before, after);

        assertTrue(report.diff().changedNodeIds().contains("probe"));
        assertFalse(report.backwardCompatible());
        assertFalse(report.forwardCompatible());
        assertFalse(GraphCompatibilityPolicy.BACKWARD.accepts(report));
    }

    @Test
    void aGraphThatDeclaresNoNatureKeepsItsCanonicalHash() {
        // The load-bearing compatibility fact: runtime nature re-addresses nothing. Every graph already recorded
        // hashes exactly as it did before, because nothing was added to its node encoding.
        assertEquals(GraphCanonicalForm.sha256(definition(Map.of())),
                GraphCanonicalForm.sha256(definition(Map.of())));
        assertNotEquals(GraphCanonicalForm.sha256(definition(Map.of())),
                GraphCanonicalForm.sha256(definition(Map.of(NodeRuntimeNatureProperty.NAME, "SOURCE"))));
    }

    // ------------------------------------------------------------------ fixtures

    private static GraphDefinition definition(Map<String, Object> probeProperties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, "probe-behavior", probeProperties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    private static byte[] graphMl(String natureKey, String natureValue) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="nature" for="node" attr.name="%s" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="probe">
                      <data key="kind">BEHAVIOR</data>
                      <data key="behavior">probe-behavior</data>
                      <data key="nature">%s</data>
                    </node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="probe"/>
                    <edge id="e2" source="probe" target="end"/>
                  </graph>
                </graphml>
                """).formatted(natureKey, natureValue).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] graphMlWithoutNature() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="probe">
                      <data key="kind">BEHAVIOR</data>
                      <data key="behavior">probe-behavior</data>
                    </node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="probe"/>
                    <edge id="e2" source="probe" target="end"/>
                  </graph>
                </graphml>
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] graphMlWithRuntimeProperties() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="nature" for="node" attr.name="runtime.nature" attr.type="string"/>
                  <key id="concurrency" for="node" attr.name="runtime.maxConcurrency" attr.type="int"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="probe">
                      <data key="kind">BEHAVIOR</data>
                      <data key="behavior">probe-behavior</data>
                      <data key="nature">TRAVERSAL</data>
                      <data key="concurrency">1</data>
                    </node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="probe"/>
                    <edge id="e2" source="probe" target="end"/>
                  </graph>
                </graphml>
                """.getBytes(StandardCharsets.UTF_8);
    }
}
