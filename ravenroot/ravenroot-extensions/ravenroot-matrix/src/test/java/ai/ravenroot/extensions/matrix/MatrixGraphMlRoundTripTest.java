package ai.ravenroot.extensions.matrix;

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

import static org.junit.jupiter.api.Assertions.*;

class MatrixGraphMlRoundTripTest {
    @Test void roundTripsOnlyOpaqueProfileAndTighteningProperties() throws Exception {
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("sync", NodeKind.BEHAVIOR, MatrixBehaviorDescriptors.SYNC,
                        Map.of("matrixProfile", MatrixTestSupport.PROFILE, "maxEventsPerSync", "5")),
                new GraphNode("send", NodeKind.BEHAVIOR, MatrixBehaviorDescriptors.SEND,
                        Map.of("matrixProfile", MatrixTestSupport.PROFILE, "roomId", MatrixTestSupport.ROOM)),
                GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains("matrix-access-token")); assertFalse(serialized.contains("matrix.example.org"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals(MatrixBehaviorDescriptors.SYNC, reread.definition().node("sync").behavior());
            assertEquals(MatrixBehaviorDescriptors.SEND, reread.definition().node("send").behavior());
            assertEquals(MatrixTestSupport.PROFILE, reread.definition().node("sync").properties().get("matrixProfile"));
        }
    }
}
