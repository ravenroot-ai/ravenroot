package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GraphReleaseMetadataTest {
    private static final byte[] GRAPH = ("""
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:y="http://www.yworks.com/xml/graphml">
              <key id="presentation" for="graph" attr.name="ravenroot.ui.visualGroups" attr.type="string"/>
              <graph id="G" edgedefault="directed"><data key="presentation">{}</data><node id="n1"><data key="unknown"><y:ShapeNode/></data></node></graph>
            </graphml>
            """).getBytes(StandardCharsets.UTF_8);

    @Test void rewritesAuthoredIdentityWhilePreservingExtensionAndPresentationContent() {
        var rewritten = GraphReleaseMetadata.assign(GRAPH, "orders", 7);
        assertEquals(new GraphReleaseMetadata.Metadata("orders", 7), GraphReleaseMetadata.read(rewritten.graphMl()));
        String xml = new String(rewritten.graphMl(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("ravenroot.ui.visualGroups"));
        assertTrue(xml.contains("ShapeNode"));
        assertTrue(xml.contains("presentation"));
    }

    @Test void refusesAKeyIdCollisionInsteadOfRetargetingAuthoredMetadata() {
        byte[] graph = new String(GRAPH, StandardCharsets.UTF_8)
                .replace("id=\"presentation\"", "id=\"ravenroot-authoring-graph-id\"")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                assertThrows(GraphAuthoringException.class,
                        () -> GraphReleaseMetadata.assign(graph, "orders", 1)).failure());
    }

    @Test void refusesPartialOrNonPositiveMetadata() {
        byte[] partial = new String(GraphReleaseMetadata.assign(GRAPH, "orders", 1).graphMl(), StandardCharsets.UTF_8)
                .replace(">1</data>", ">0</data>").getBytes(StandardCharsets.UTF_8);
        assertEquals(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                assertThrows(GraphAuthoringException.class, () -> GraphReleaseMetadata.read(partial)).failure());
    }

    @Test void authoredVersionUsesTheSharedExactSafeIntegerCeiling() {
        var maximum = GraphReleaseMetadata.assign(GRAPH, "orders", GraphReleaseMetadata.MAX_RELEASE_VERSION);
        assertEquals(GraphReleaseMetadata.MAX_RELEASE_VERSION,
                GraphReleaseMetadata.read(maximum.graphMl()).releaseVersion());
        assertEquals(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                assertThrows(GraphAuthoringException.class,
                        () -> GraphReleaseMetadata.assign(GRAPH, "orders",
                                GraphReleaseMetadata.MAX_RELEASE_VERSION + 1)).failure());
        String accepted = new String(GraphReleaseMetadata.assign(GRAPH, "orders", 1).graphMl(),
                StandardCharsets.UTF_8);
        for (String invalid : new String[] {"01", "+1", "1.0", "1e0", "9007199254740992",
                "9223372036854775808"}) {
            byte[] bytes = accepted.replace(">1</data>", ">" + invalid + "</data>")
                    .getBytes(StandardCharsets.UTF_8);
            assertEquals(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                    assertThrows(GraphAuthoringException.class, () -> GraphReleaseMetadata.read(bytes)).failure(), invalid);
        }
    }
}
