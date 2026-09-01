package ai.ravenroot.core.graph;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostile content submitted in a GraphML document must not come back out in the rejection (FIX-03).
 *
 * <p>The leak positions are key names, scalar types and ids. Each document below plants
 * the same marker in one of them and asserts it does not reappear in the message the caller receives.
 * The marker is checked case-insensitively because two of the paths that carry it — the scalar type
 * and the reserved-namespace check — lower-case before comparing, and a leak that arrives
 * lower-cased is still a leak.</p>
 *
 * <p>This test deliberately uses nothing but {@link Throwable#getMessage()}, so it demonstrates the
 * leak directly without depending on the typed rejection accessors. A test written only against
 * those accessors could not establish that the legacy message surface leaked. {@code
 * GraphMlRejectionPolicyTest} covers the mechanism that makes it pass.</p>
 */
class GraphMlRejectionSanitisationTest {

    /**
     * High-entropy and lower-case. Lower-case because {@code attr.type} and reserved property names
     * are normalised with {@code toLowerCase} before they are reported, so an upper-case marker would
     * miss a real leak of the normalised form.
     */
    private static final String MARKER = "pwned-7f3a91c2";

    @TestFactory
    Stream<DynamicTest> hostileContentNeverReappearsInARejection() {
        var documents = new LinkedHashMap<String, String>();

        // --- scalar types (the representative example) --------------------------------------
        documents.put("hostile scalar type", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="%s"/>
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """.formatted(MARKER));
        documents.put("hostile value against a declared scalar type", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="int"/>
                  <graph id="g" edgedefault="directed">
                    <node id="n0"><data key="k0">%s</data></node>
                  </graph>
                </graphml>
                """.formatted(MARKER));

        // --- key names ---------------------------------------------------------------------
        documents.put("hostile key id", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="%s" for="node" attr.name="a" attr.type="string"/>
                  <key id="%s" for="node" attr.name="b" attr.type="string"/>
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """.formatted(MARKER, MARKER));
        documents.put("hostile property name", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="%s" attr.type="string"/>
                  <key id="k1" for="node" attr.name="%s" attr.type="string"/>
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """.formatted(MARKER, MARKER));
        documents.put("hostile key scope", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="%s" attr.name="a" attr.type="string"/>
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """.formatted(MARKER));
        documents.put("hostile undeclared data key", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed">
                    <node id="n0"><data key="%s">v</data></node>
                  </graph>
                </graphml>
                """.formatted(MARKER));
        documents.put("hostile reserved property name", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="ravenroot.%s" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="n0"><data key="k0">v</data></node>
                  </graph>
                </graphml>
                """.formatted(MARKER));

        // --- ids ----------------------------------------------------------------------------
        documents.put("hostile node id", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed">
                    <node id="%s"/><node id="%s"/>
                  </graph>
                </graphml>
                """.formatted(MARKER, MARKER));
        documents.put("hostile edge id", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed">
                    <node id="n0"/><node id="n1"/>
                    <edge id="%s" source="n0" target="n1"/>
                    <edge id="%s" source="n1" target="n0"/>
                  </graph>
                </graphml>
                """.formatted(MARKER, MARKER));
        documents.put("hostile graph id", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="%s" edgedefault="undirected"><node id="n0"/></graph>
                </graphml>
                """.formatted(MARKER));
        documents.put("hostile edge endpoint", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed">
                    <node id="n0"/>
                    <edge id="e0" source="n0" target="%s"/>
                  </graph>
                </graphml>
                """.formatted(MARKER));
        documents.put("hostile id on an edge that refuses directedness", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed">
                    <node id="n0"/><node id="n1"/>
                    <edge id="%s" source="n0" target="n1" directed="false"/>
                  </graph>
                </graphml>
                """.formatted(MARKER));
        documents.put("hostile id on a duplicated data key", """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="a" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="%s"><data key="k0">x</data><data key="k0">y</data></node>
                  </graph>
                </graphml>
                """.formatted(MARKER));

        return documents.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            String message = rejectionMessage(entry.getValue());
            assertFalse(message.toLowerCase(java.util.Locale.ROOT).contains(MARKER),
                    "the rejection returned submitted content to the caller: " + message);
        }));
    }

    /**
     * The mirror case, and the other half of the FIX-01 sanitisation check.
     *
     * <p>An id-less edge is given a {@code ravenroot-synthesized-edge-N} handle so the rest of the
     * import has something to address it by. It is an internal artefact the author never wrote and
     * cannot look up, so naming it in a rejection disclosed internal state outward, exactly as the
     * cases above disclose submitted state back. One policy covers both directions, so neither
     * appears.</p>
     */
    @Test
    void aSynthesizedEdgeIdentityNeverReachesTheCaller() {
        String message = rejectionMessage("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="edge" attr.name="ravenroot.security.subject" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="n0"/><node id="n1"/>
                    <edge source="n0" target="n1"><data key="k0">root</data></edge>
                  </graph>
                </graphml>
                """);
        assertFalse(message.contains(GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX),
                "the rejection disclosed an internal synthesized identity: " + message);
    }

    /**
     * The placeholder the compatibility layer used for an id-less element is gone rather than
     * retained, because nothing is rendered into a public message for it to stand in for. Pinned so
     * that a future edit reintroducing element identity into a message is noticed.
     */
    @Test
    void noRejectionStillDescribesAnElementAsUnnamed() {
        String message = rejectionMessage("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph edgedefault="undirected"><node id="n0"/></graph>
                </graphml>
                """);
        assertFalse(message.contains("<unnamed>"), message);
    }

    /** Rejection stays a rejection: sanitising the message must not make a bad document acceptable. */
    @Test
    void everySanitisedDocumentIsStillRefused() {
        Map<String, String> stillRejected = Map.of(
                "unsupported scalar type", """
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                          <key id="k0" for="node" attr.name="p" attr.type="%s"/>
                          <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                        </graphml>
                        """.formatted(MARKER),
                "reserved property", """
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                          <key id="k0" for="node" attr.name="ravenroot.%s" attr.type="string"/>
                          <graph id="g" edgedefault="directed">
                            <node id="n0"><data key="k0">v</data></node>
                          </graph>
                        </graphml>
                        """.formatted(MARKER));
        stillRejected.forEach((name, xml) ->
                assertTrue(rejectionMessage(xml).startsWith("GraphML"), name));
    }

    private static String rejectionMessage(String graphMl) {
        var rejection = assertThrows(IllegalArgumentException.class,
                () -> GraphManager.readGraphMl(
                        new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8))).close());
        return String.valueOf(rejection.getMessage());
    }
}
