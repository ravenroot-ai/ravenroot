package ai.ravenroot.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmbeddedSampleTest {

    @Test
    void importsOnlyTheCoreAndBuildsAnEmbeddedGraph() {
        var graph = EmbeddedSample.sampleGraph();

        assertEquals(5, graph.nodes().size());
        assertEquals("input", graph.next("start", "continue").getFirst().id());
        assertEquals("uppercase-text", graph.next("input", "continue").getFirst().behavior());
    }

    @Test
    void executesTheGraphInsideAThirdPartyApplication() throws Exception {
        var result = EmbeddedSample.execute("embedded");

        assertEquals("EMBEDDED", result.payload());
        assertEquals(4, result.visitedNodes().size());
    }
}
