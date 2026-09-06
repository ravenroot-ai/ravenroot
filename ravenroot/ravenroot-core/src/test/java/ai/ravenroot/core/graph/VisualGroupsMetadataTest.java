package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualGroupsMetadataTest {
    @Test
    void scalarVisualMetadataPreservesTheExecutableDefinitionAndRoundTrip() throws Exception {
        byte[] bytes;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/graphml-corpus/accepted/visual-groups.graphml"))) {
            bytes = input.readAllBytes();
        }
        var source = new String(bytes, StandardCharsets.UTF_8);
        var withoutPresentation = source
                .replaceAll("(?m)^.*<key id=\"groups\"[^>]*/>\\s*", "")
                .replaceAll("(?m)^.*<data key=\"groups\">[^<]*</data>\\s*", "")
                .getBytes(StandardCharsets.UTF_8);
        var report = GraphManager.validateGraphMl(new ByteArrayInputStream(bytes));
        var plainReport = GraphManager.validateGraphMl(new ByteArrayInputStream(withoutPresentation));
        assertTrue(report.violations().isEmpty(), report.violations().toString());
        assertEquals(plainReport.violations(), report.violations());
        assertEquals(5, report.nodes());
        assertEquals(4, report.edges());
        try (var grouped = GraphManager.readGraphMl(new ByteArrayInputStream(bytes));
             var plain = GraphManager.readGraphMl(new ByteArrayInputStream(withoutPresentation))) {
            var definition = grouped.definition();
            assertEquals(List.copyOf(plain.definition().nodes()), List.copyOf(definition.nodes()));
            assertEquals(plain.definition().edges(), definition.edges());
            assertEquals("passthrough", definition.node("work").behavior());
            assertTrue(definition.properties().get("ravenroot.ui.visualGroups").toString().contains("group-work"));
            assertEquals(true, definition.edges().stream().filter(edge -> edge.id().equals("start-work"))
                    .findFirst().orElseThrow().properties().get("parallel"));
            assertEquals(plain.definition().edges().stream().map(GraphEdge::id).toList(),
                    definition.edges().stream().map(GraphEdge::id).toList());
            var output = new ByteArrayOutputStream();
            grouped.writeGraphMl(output);
            assertArrayEquals(bytes, output.toByteArray());
        }
    }
}
