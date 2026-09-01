package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GraphML may not declare security state (SEC-07).
 *
 * <p>Rejection happens at ingest rather than at node execution. A graph accepted here would already
 * have been hashed into a {@code graphVersion}, recorded in the execution store and partly run before
 * anything objected, which turns a refusable submission into a mid-flight failure.</p>
 *
 * <p>These assertions used to read the offending property name and element id out of
 * {@code getMessage()}. FIX-03 moved both to {@link GraphMlRejectionDetail#diagnosticDetail()},
 * because the property name past the fixed {@code ravenroot.} prefix is text the submitter wrote and
 * the element id may be a synthesized internal handle. The intent is unchanged and is asserted more
 * strictly than before: the rejection must still name what it refused and where, and it must now also
 * be shown not to name it to the caller.</p>
 */
class GraphMlIdentitySpoofingTest {

    @Test
    void rejectsANodeThatDeclaresAReservedSecurityProperty() {
        var rejection = assertThrows(GraphMlParseException.class, () -> read("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k1" for="node" attr.name="kind" attr.type="string"/>
                  <key id="k2" for="node" attr.name="ravenroot.security.tenantId" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="k1">ERROR</data></node>
                    <node id="start">
                      <data key="k1">START</data>
                      <data key="k2">tenant-victim</data>
                    </node>
                  </graph>
                </graphml>
                """));

        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
        assertEquals("ravenroot.security.tenantId",
                rejection.diagnosticDetail().get("reservedProperty"));
        assertEquals("start", rejection.diagnosticDetail().get("elementId"),
                "the rejection must still name the offending element, on the diagnostic channel");
        assertFalse(rejection.getMessage().contains("ravenroot.security.tenantId"));
        assertFalse(rejection.getMessage().contains("start"),
                "and must not name it to the caller");
    }

    @Test
    void rejectsAnEdgeThatDeclaresAReservedSecurityProperty() {
        var rejection = assertThrows(GraphMlParseException.class, () -> read("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k1" for="node" attr.name="kind" attr.type="string"/>
                  <key id="k2" for="edge" attr.name="ravenroot.security.subject" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="k1">ERROR</data></node>
                    <node id="start"><data key="k1">START</data></node>
                    <node id="end"><data key="k1">END</data></node>
                    <edge id="e1" source="start" target="end"><data key="k2">root</data></edge>
                  </graph>
                </graphml>
                """));

        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
        assertEquals("ravenroot.security.subject",
                rejection.diagnosticDetail().get("reservedProperty"));
        assertEquals("e1", rejection.diagnosticDetail().get("elementId"));
        assertFalse(rejection.getMessage().contains("ravenroot.security.subject"));
    }

    @Test
    void rejectsReservedPropertiesRegardlessOfCaseOrSurroundingWhitespace() {
        assertThrows(GraphMlParseException.class, () -> read("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k1" for="node" attr.name="kind" attr.type="string"/>
                  <key id="k2" for="node" attr.name="RavenRoot.Security.TenantId" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="k1">ERROR</data></node>
                    <node id="start">
                      <data key="k1">START</data>
                      <data key="k2">tenant-victim</data>
                    </node>
                  </graph>
                </graphml>
                """));
    }

    /**
     * Supersedes SEC-07's {@code stillAcceptsOrdinaryDomainPropertiesIncludingUnrelatedRavenrootNamespaces}.
     *
     * <p>That test asserted {@code ravenroot.retryPolicy} was <em>accepted</em>, on the reasoning that
     * a wider guard would break the opaque-extension preservation {@code GraphMlCorpusTest} pins. The
     * reasoning was right about preservation and wrong about the axis: preservation is owed to
     * third-party properties, not to Ravenroot's own namespace, and SEC-09 establishes that namespace
     * as operative state a graph may not write. So the guard widens and that assertion is inverted
     * here deliberately rather than deleted, because the reversal is the point.</p>
     */
    @Test
    void refusesTheWholeReservedNamespaceWhilePreservingEveryThirdPartyProperty() {
        var rejection = assertThrows(GraphMlParseException.class, () -> read("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k1" for="node" attr.name="kind" attr.type="string"/>
                  <key id="k2" for="node" attr.name="ravenroot.retryPolicy" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="k1">ERROR</data></node>
                    <node id="start">
                      <data key="k1">START</data>
                      <data key="k2">exponential</data>
                    </node>
                  </graph>
                </graphml>
                """));
        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
        assertEquals("ravenroot.retryPolicy", rejection.diagnosticDetail().get("reservedProperty"));
        assertFalse(rejection.getMessage().contains("ravenroot.retryPolicy"));

        // Everything outside the reserved namespace is preserved, including names that merely look
        // like identity. A bare "tenantId" property reaches no security decision, because identity is
        // never read from graph properties at all.
        try (var manager = read("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k1" for="node" attr.name="kind" attr.type="string"/>
                  <key id="k2" for="node" attr.name="tenantId" attr.type="string"/>
                  <key id="k3" for="node" attr.name="ravenrootish.retryPolicy" attr.type="string"/>
                  <key id="k4" for="node" attr.name="custom.vendor" attr.type="string"/>
                  <key id="k5" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="k1">ERROR</data></node>
                    <node id="start">
                      <data key="k1">START</data>
                      <data key="k2">looks-like-identity-but-is-not-reserved</data>
                      <data key="k3">exponential</data>
                      <data key="k4">acme</data>
                    </node>
                    <node id="end"><data key="k1">END</data></node>
                    <edge id="e1" source="start" target="end"><data key="k5">continue</data></edge>
                  </graph>
                </graphml>
                """)) {
            var start = manager.definition().node("start");
            assertEquals("looks-like-identity-but-is-not-reserved", start.properties().get("tenantId"));
            // The prefix match is on "ravenroot." exactly; a name that merely starts with the same
            // letters is third-party data and must not be swept up.
            assertEquals("exponential", start.properties().get("ravenrootish.retryPolicy"));
            assertEquals("acme", start.properties().get("custom.vendor"));
        }
    }

    private static GraphManager read(String graphMl) {
        return GraphManager.readGraphMl(new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8)));
    }
}
