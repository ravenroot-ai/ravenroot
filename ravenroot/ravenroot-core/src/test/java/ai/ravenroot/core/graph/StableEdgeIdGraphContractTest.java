package ai.ravenroot.core.graph;

import ai.ravenroot.api.application.StableEdgeId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableEdgeIdGraphContractTest {

    @Test
    void programmaticEdgeAcceptsTheExactBoundAndRejectsOneByteMore() {
        String exact = "x".repeat(StableEdgeId.MAX_UTF8_BYTES);

        assertEquals(exact, new GraphEdge("start", "end", "continue", Map.of(), exact).id());
        assertThrows(IllegalArgumentException.class,
                () -> new GraphEdge("start", "end", "continue", Map.of(), exact + "x"));
    }

    @Test
    void graphMlManagerAcceptsTheExactBoundAndRejectsOneByteMore() {
        String exact = "x".repeat(StableEdgeId.MAX_UTF8_BYTES);

        try (var manager = read(graphMl(exact))) {
            assertEquals(exact, manager.definition().edges().getFirst().id());
        }
        assertThrows(GraphMlCompatibilityException.class, () -> read(graphMl(exact + "x")));
    }

    @Test
    void programmaticAndGraphMlEdgesPreserveWhitespaceAsExactDistinctIdentity() {
        assertEquals(" edge ", new GraphEdge("start", "end", "continue", Map.of(), " edge ").id());
        assertThrows(IllegalArgumentException.class,
                () -> new GraphEdge("start", "end", "continue", Map.of(), "   "));

        String graphMl = graphMlWithExactDistinctIds();
        try (var manager = read(graphMl)) {
            assertEquals(java.util.Set.of("edge", " edge "), manager.definition().edges().stream()
                    .map(GraphEdge::id).filter(id -> id.trim().equals("edge"))
                    .collect(java.util.stream.Collectors.toSet()));
            var output = new ByteArrayOutputStream();
            manager.writeGraphMl(output);
            String exported = output.toString(StandardCharsets.UTF_8);
            assertTrue(exported.contains("id=\"edge\""), exported);
            assertTrue(exported.contains("id=\" edge \""), exported);
        }
    }

    @Test
    void graphMlRejectsAnExplicitWhitespaceOnlyIdentityRatherThanSynthesizingOne() {
        assertThrows(GraphMlCompatibilityException.class, () -> read(graphMl("   ")));
    }

    private static GraphManager read(String graphMl) {
        return GraphManager.readGraphMl(new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8)));
    }

    private static String graphMl(String edgeId) {
        return """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="workflow" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="%s" source="start" target="end">
                      <data key="outcome">continue</data>
                    </edge>
                  </graph>
                </graphml>
                """.formatted(edgeId);
    }

    private static String graphMlWithExactDistinctIds() {
        return """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="workflow" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="left"><data key="kind">PASSTHROUGH</data></node>
                    <node id="right"><data key="kind">PASSTHROUGH</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="edge" source="start" target="left">
                      <data key="outcome">continue</data>
                    </edge>
                    <edge id=" edge " source="start" target="right">
                      <data key="outcome">continue</data>
                    </edge>
                    <edge id="left-end" source="left" target="end"/>
                    <edge id="right-end" source="right" target="end"/>
                  </graph>
                </graphml>
                """;
    }
}
