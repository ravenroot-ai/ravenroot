package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One policy that both layers <em>must</em> use (FIX-03).
 *
 * <p>A policy declared in a single place is a stronger guarantee than two
 * implementations that currently agree — the previous code had two implementations that had
 * stopped agreeing. So the obligation is enforced rather than described: the constructors are
 * package-private and only {@link GraphMlRejection} calls them, and {@link #noLayerBypassesThePolicy()}
 * fails if a layer starts building its own.</p>
 */
class GraphMlRejectionPolicyTest {

    private static final String MARKER = "pwned-7f3a91c2";

    /**
     * The anti-bypass gate.
     *
     * <p>Both exception constructors are package-private, so nothing outside {@code
     * ai.ravenroot.core.graph} can construct one. That leaves exactly one hole: a class inside the
     * package that constructs one directly and writes its own message, which is precisely how the
     * asymmetry arose. This closes it — a rejection built anywhere but in the policy fails the
     * build.</p>
     *
     * <p>Behavioural tests cannot cover this on their own: they only see the rejection paths they
     * happen to exercise, whereas a new path added tomorrow with an interpolated message would go
     * unnoticed until someone submitted the document that reached it.</p>
     */
    @Test
    void noLayerBypassesThePolicy() throws IOException {
        Path graph = Path.of("src/main/java/ai/ravenroot/core/graph");
        List<String> layers =
                List.of("SecureGraphMlParser.java", "GraphMlDocument.java", "GraphManager.java");
        for (String layer : layers) {
            Path source = graph.resolve(layer);
            assertTrue(Files.isRegularFile(source),
                    "the guard cannot pass by failing to find " + layer + " at " + source.toAbsolutePath());
            String text = Files.readString(source);
            assertFalse(text.contains("new GraphMlCompatibilityException("),
                    layer + " builds a rejection without going through GraphMlRejection");
            assertFalse(text.contains("new GraphMlParseException("),
                    layer + " builds a rejection without going through GraphMlRejection");
        }
    }

    /** The detail is not lost; it is moved to a channel the caller cannot observe. */
    @Test
    void theDetailRemovedFromTheMessageSurvivesOnTheDiagnosticChannel() {
        var rejection = assertThrows(GraphMlCompatibilityException.class, () -> read("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="%s"/>
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """.formatted(MARKER)));

        assertEquals("GraphML key declares an unsupported scalar type", rejection.getMessage());
        assertEquals("k0", rejection.diagnosticDetail().get("keyId"));
        assertEquals(MARKER, rejection.diagnosticDetail().get("declaredType"));
        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
    }

    /** Both layers expose the same shape, which is what lets one adapter branch serve both. */
    @Test
    void bothLayersReportThroughTheSameContract() {
        GraphMlRejectionDetail security = assertThrows(GraphMlParseException.class, () -> read("""
                <!DOCTYPE graphml [<!ENTITY x "y">]>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """));
        GraphMlRejectionDetail compatibility = assertThrows(GraphMlCompatibilityException.class,
                () -> read("""
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                          <graph id="g" edgedefault="undirected"><node id="n0"/></graph>
                        </graphml>
                        """));

        for (GraphMlRejectionDetail rejection : List.of(security, compatibility)) {
            assertTrue(rejection.incidentId().matches("[0-9a-f]{16}"),
                    "incident handle: " + rejection.incidentId());
            assertTrue(GraphMlRejection.declaredPublicMessages()
                            .contains(((Throwable) rejection).getMessage()),
                    "undeclared message: " + ((Throwable) rejection).getMessage());
        }
        assertNotEquals(security.incidentId(), compatibility.incidentId());
    }

    /**
     * The handle is per-rejection, so two submissions of the same document are distinguishable in
     * the audit stream. It is also unpredictable, so a caller cannot assert one it did not receive.
     */
    @Test
    void everyRejectionMintsItsOwnIncidentHandle() {
        String document = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="undirected"><node id="n0"/></graph>
                </graphml>
                """;
        var first = assertThrows(GraphMlCompatibilityException.class, () -> read(document));
        var second = assertThrows(GraphMlCompatibilityException.class, () -> read(document));
        assertEquals(first.getMessage(), second.getMessage());
        assertNotEquals(first.incidentId(), second.incidentId());
    }

    /**
     * A detail is written into a JSON-lines audit record, so a value carrying a newline or a quote
     * would be able to forge a second record. Bounded and escaped at the source, independently of
     * whatever the sink does.
     */
    @Test
    void aDetailCannotForgeItsOwnAuditRecord() {
        String hostile = "a\nb\"c\r\n{\"event\":\"forged\"}";
        var rejection = assertThrows(GraphMlCompatibilityException.class, () -> read("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="int"/>
                  <graph id="g" edgedefault="directed">
                    <node id="n0"><data key="k0">%s</data></node>
                  </graph>
                </graphml>
                """.formatted(hostile.replace("\"", "&quot;"))));

        String value = rejection.diagnosticDetail().get("value");
        assertFalse(value.contains("\n"), value);
        assertFalse(value.contains("\r"), value);

        String oversized = "x".repeat(400);
        var truncated = assertThrows(GraphMlCompatibilityException.class, () -> read("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="int"/>
                  <graph id="g" edgedefault="directed">
                    <node id="n0"><data key="k0">%s</data></node>
                  </graph>
                </graphml>
                """.formatted(oversized)));
        assertTrue(truncated.diagnosticDetail().get("value").endsWith("<truncated>"),
                truncated.diagnosticDetail().get("value"));
    }

    /**
     * Every sentence the policy can produce is free of substitution slots once rendered, and no
     * sentence smuggles a placeholder that a future edit could fill from the document.
     */
    @Test
    void everyDeclaredMessageIsFullyResolvedServerAuthoredText() {
        var declared = GraphMlRejection.declaredPublicMessages();
        assertFalse(declared.isEmpty());
        for (String message : declared) {
            assertFalse(message.contains("%s"), message);
            assertFalse(message.isBlank());
            assertTrue(message.equals(message.strip()), message);
        }
        assertFalse(declared.stream()
                        .anyMatch(message -> message.toLowerCase(Locale.ROOT).contains(MARKER)),
                "the declared vocabulary must be authored here, not derived from any document");
    }

    private static GraphManager read(String graphMl) {
        return GraphManager.readGraphMl(new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8)));
    }
}
