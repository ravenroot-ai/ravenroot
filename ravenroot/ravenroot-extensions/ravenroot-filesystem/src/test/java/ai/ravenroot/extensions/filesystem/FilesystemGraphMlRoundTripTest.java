package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FilesystemGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileRelativePathAndTightening() throws Exception {
        Map<String, Object> properties = Map.of(
                "filesystemProfile", "workspace",
                "path", "documents/report.txt",
                "encoding", "utf-8",
                "mode", "replace",
                "maxBytes", "4096",
                "deadlineMs", "750",
                RecoveryRepeatabilityProperty.NAME, RecoveryRepeatabilityProperty.NOT_REPEATABLE);
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("write", NodeKind.BEHAVIOR, FilesystemWriteNodeBehavior.BEHAVIOR, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output);
            xml = output.toByteArray();
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertFalse(text.contains("/srv/ravenroot/files"));
        assertFalse(text.matches("(?s).*(canonicalAbsoluteRoot|maxConcurrency|allowedRelativeGlobs).*"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            GraphNode node = reread.definition().node("write");
            assertEquals(FilesystemWriteNodeBehavior.BEHAVIOR, node.behavior());
            assertEquals(properties, node.properties());
        }
    }
}
