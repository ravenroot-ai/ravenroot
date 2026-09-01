package ai.ravenroot.core.graph;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMlCorpusTest {
    private static final List<String> ACCEPTED = List.of(
            "canonical-minimal.graphml",
            "scalar-types.graphml",
            "complex-extensions.graphml",
            "defaults-and-scopes.graphml",
            "topology.graphml",
            "optional-edge-ids.graphml",
            "authored-bypass.graphml");

    @TestFactory
    Stream<DynamicTest> acceptedCorpusIsSemanticallyLossless() {
        return ACCEPTED.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
            byte[] source = fixture("accepted/" + name);
            var output = new ByteArrayOutputStream();

            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
                manager.writeGraphMl(output);
            }

            assertEquals(xmlSemantics(source), xmlSemantics(output.toByteArray()));
        }));
    }

    /**
     * the authored bypass flag survives export and re-import.
     *
     * <p>{@code authored-bypass.graphml} is in {@link #ACCEPTED} above, so the XML-level round trip is
     * already asserted lossless by {@code acceptedCorpusIsSemanticallyLossless}. That is necessary and
     * not sufficient, which is why this test exists as well: a document can round-trip byte-for-byte
     * while the runtime reads nothing from it. What is asserted here is that the property graph in
     * between — the thing {@code GraphRunner} actually consults — answers "switched off" for the node
     * that declares the flag and "not switched off" for its neighbour, <em>after</em> a full write and
     * re-read rather than on first parse.</p>
     *
     * <p>The fixture types the key as {@code boolean}, so this also pins that
     * {@code NodeBypassProperty.parse} handles the {@link Boolean} a typed exporter produces and not
     * only the string every runtime test uses. It carries a second node with the same behavior and no
     * flag, so "copied the property onto every node" fails here rather than passing as a round trip.
     * </p>
     *
     * <p>The fixture has no XML comment because a comment anywhere inside {@code <graphml>} makes the
     * document fail to load as {@code GRAPHML_MALFORMED_XML} / "GraphML document is not well formed".
     * The identical file parses when the comment is removed, which isolates the refusal.
     * {@code SecureGraphMlParser} handles {@code COMMENT} events in its pre-scan, so the refusal comes
     * from the reader after it. That is a pre-existing limitation unrelated to authored bypass;
     * authored-bypass handling does not affect it.</p>
     */
    @Test
    void theAuthoredBypassFlagSurvivesAWriteAndReReadAsSomethingTheRuntimeCanRead() {
        var output = new ByteArrayOutputStream();
        try (var manager = accepted("authored-bypass.graphml")) {
            manager.writeGraphMl(output);
        }

        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(output.toByteArray()))) {
            var definition = reread.definition();
            assertInstanceOf(Boolean.class,
                    definition.node("switched-off").properties().get("execution.bypass"),
                    "a boolean-typed key must survive the round trip as a Boolean, not as a String");
            assertTrue(ai.ravenroot.api.catalog.NodeBypassProperty
                    .isBypassed(definition.node("switched-off").properties()));
            assertFalse(ai.ravenroot.api.catalog.NodeBypassProperty
                    .isBypassed(definition.node("still-running").properties()),
                    "the flag must not spread to a node that did not declare it");
            assertEquals(NodeKind.BEHAVIOR, definition.node("switched-off").kind());
            assertEquals("http-request", definition.node("switched-off").behavior(),
                    "a switched-off node keeps its behavior name: it is skipped, not erased");
        }
    }

    @Test
    void retainsTypedUnknownNodeAndEdgeProperties() {
        try (var manager = accepted("scalar-types.graphml")) {
            Map<String, Object> node = manager.definition().node("start").properties();
            Map<String, Object> edge = manager.definition().edges().getFirst().properties();

            assertEquals("  lexical whitespace retained  ", node.get("custom.string"));
            assertInstanceOf(Boolean.class, node.get("custom.boolean"));
            assertInstanceOf(Integer.class, node.get("custom.int"));
            assertInstanceOf(Long.class, node.get("custom.long"));
            assertInstanceOf(Float.class, node.get("custom.float"));
            assertInstanceOf(Double.class, node.get("custom.double"));
            assertInstanceOf(Boolean.class, edge.get("custom.boolean"));
            assertInstanceOf(Integer.class, edge.get("custom.int"));
            assertInstanceOf(Long.class, edge.get("custom.long"));
            assertInstanceOf(Float.class, edge.get("custom.float"));
            assertInstanceOf(Double.class, edge.get("custom.double"));
        }
    }

    @Test
    void resolvesDefaultsInPropertyGraphWithoutDestroyingAbsentVsExplicitXml() {
        byte[] source = fixture("accepted/defaults-and-scopes.graphml");
        var output = new ByteArrayOutputStream();
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var definition = manager.definition();
            assertEquals(NodeKind.PASSTHROUGH, definition.node("worker").kind());
            assertEquals("node-default", definition.node("worker").properties().get("owner"));
            assertEquals("eu-central", definition.node("worker").properties().get("region"));
            assertEquals("continue",
                    manager.query(graph -> graph.E("default-edge").values(GraphManager.OUTCOME).next()));
            assertEquals("edge-default",
                    manager.query(graph -> graph.E("default-edge").values("owner").next()));
            assertEquals("eu-central",
                    manager.query(graph -> graph.E("default-edge").values("region").next()));
            assertEquals("done",
                    manager.query(graph -> graph.E("explicit-edge").values(GraphManager.OUTCOME).next()));
            manager.writeGraphMl(output);
        }

        String exported = output.toString(StandardCharsets.UTF_8);
        assertTrue(exported.contains("<node id=\"worker\"/>"));
        assertTrue(exported.contains("<edge id=\"default-edge\" source=\"start\" target=\"worker\"/>"));
        assertEquals(xmlSemantics(source), xmlSemantics(output.toByteArray()));
    }

    @Test
    void retainsOpaqueNodeAndEdgeExtensionsWithoutFlatteningThemIntoProperties() {
        byte[] source = fixture("accepted/complex-extensions.graphml");
        var output = new ByteArrayOutputStream();
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals("workflow-team", manager.definition().node("start").properties().get("owner"));
            assertFalse(manager.definition().node("start").properties().containsKey("node-graphics"));
            assertEquals("routing-team", manager.definition().edges().getFirst().properties().get("owner"));
            assertFalse(manager.definition().edges().getFirst().properties().containsKey("edge-graphics"));
            manager.writeGraphMl(output);
        }

        assertEquals(xmlSemantics(source), xmlSemantics(output.toByteArray()));
        String exported = output.toString(StandardCharsets.UTF_8);
        assertTrue(exported.contains("<![CDATA[Start <sanitized>]]>"));
        assertTrue(exported.contains("<viz:annotation importance=\"high\">preserve me</viz:annotation>"));
    }

    @Test
    void acceptsYFilesInertPortDeclarationsAndGraphmlScopedResources() {
        byte[] source = ("""
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns"
                         xmlns:y="http://www.yworks.com/xml/graphml">
                  <key id="d1" for="port" yfiles.type="portgraphics"/>
                  <key id="d12" for="graphml" yfiles.type="resources"/>
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e" source="start" target="end"/>
                  </graph>
                  <data key="d12"><y:Resources/></data>
                </graphml>
                """).getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals(3, manager.nodeCount());
            assertEquals(1, manager.edgeCount());
            assertEquals(NodeKind.START, manager.start().kind());
            assertFalse(manager.definition().node("start").properties().containsKey("d1"));
            assertFalse(manager.definition().node("start").properties().containsKey("d12"));
            manager.writeGraphMl(output);
        }

        assertEquals(xmlSemantics(source), xmlSemantics(output.toByteArray()));
        String exported = output.toString(StandardCharsets.UTF_8);
        assertTrue(exported.contains("for=\"port\""));
        assertTrue(exported.contains("for=\"graphml\""));
        assertTrue(exported.contains("<y:Resources/>"));
    }

    @Test
    void retainsParallelEdgeIdsSelfLoopAndEdgeBeforeNodeOrdering() {
        try (var manager = accepted("topology.graphml")) {
            assertEquals(3, manager.nodeCount());
            assertEquals(4, manager.edgeCount());
            assertEquals(
                    List.of("finish", "parallel-a", "parallel-b", "self-loop"),
                    manager.query(graph -> graph.E().id().toList()).stream()
                            .map(Object::toString).sorted().toList());
            var selfLoop = manager.query(graph -> graph.E("self-loop").next());
            assertEquals(selfLoop.outVertex(), selfLoop.inVertex());
        }
    }

    /**
     * GraphML requires {@code id} on {@code <node>} and leaves it optional on
     * {@code <edge>} — {@code graphml-structure.xsd} declares
     * {@code <xs:attribute name="id" type="xs:NMTOKEN" use="required">} inside {@code node.type} and
     * {@code <xs:attribute name="id" type="xs:NMTOKEN" >} inside {@code edge.type}, and the DTD
     * spells the same distinction as {@code #REQUIRED} against {@code #IMPLIED}.
     */
    @Test
    void acceptsEdgesWithoutIdsAndKeepsTheirIdentityDistinctFromExplicitIds() {
        byte[] source = fixture("accepted/optional-edge-ids.graphml");
        // The fixture deliberately declares the first synthesized candidate as an author id, so the
        // collision skip is exercised rather than assumed. Pin that here: a change to the prefix
        // that silently made the fixture ordinary must fail loudly instead.
        assertTrue(new String(source, StandardCharsets.UTF_8)
                .contains("id=\"" + GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX + "0\""));

        var output = new ByteArrayOutputStream();
        List<String> edgeIds;
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals(3, manager.edgeCount());
            edgeIds = manager.query(graph -> graph.E().id().toList()).stream()
                    .map(Object::toString).sorted().toList();
            // The author id occupies candidate 0, so the two unnamed edges take 1 and 2. Three
            // distinct ids also means the synthesized ones neither collided with the explicit id nor
            // with each other.
            assertEquals(
                    List.of(GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX + "0",
                            GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX + "1",
                            GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX + "2"),
                    edgeIds);

            var byRoute = new java.util.HashMap<String, GraphEdge>();
            manager.definition().edges()
                    .forEach(edge -> byRoute.put(edge.source() + "->" + edge.target(), edge));
            // Each unnamed edge resolves key defaults in its own right. Sharing one identity across
            // unnamed edges would let the explicit 'owner' on worker->end suppress the default on
            // start->worker, which is the silent corruption this asserts against.
            assertEquals("edge-default", byRoute.get("start->worker").properties().get("owner"));
            assertEquals("continue", byRoute.get("start->worker").outcome());
            assertEquals("explicit-owner", byRoute.get("worker->end").properties().get("owner"));
            assertEquals("continue", byRoute.get("worker->end").outcome());
            assertEquals("edge-default", byRoute.get("start->end").properties().get("owner"));
            assertEquals("shortcut", byRoute.get("start->end").outcome());

            manager.writeGraphMl(output);
        }

        // Synthesized identity is an import-side handle, never an edit to the document. The export
        // still reproduces the unnamed edges exactly as the author wrote them.
        String exported = output.toString(StandardCharsets.UTF_8);
        assertFalse(exported.contains(GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX + "1"));
        assertFalse(exported.contains(GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX + "2"));
        assertTrue(exported.contains("<edge source=\"start\" target=\"worker\"/>"));
        assertEquals(xmlSemantics(source), xmlSemantics(output.toByteArray()));

        // Identity is a pure function of the bytes, so a second import of the same document assigns
        // the same ids; GraphManager compares imported edge ids to detect a post-import mutation.
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals(edgeIds, reread.query(graph -> graph.E().id().toList()).stream()
                    .map(Object::toString).sorted().toList());
        }
    }

    @TestFactory
    Stream<DynamicTest> rejectedCorpusFailsExplicitly() {
        // FIX-03 split each of these in two. The public message is now the declared sentence and
        // nothing else; the fragment of the document that used to be spliced into it — 'Owner',
        // 'shared', 'kind', 'not-an-integer' — is asserted on the diagnostic channel instead. Pinning
        // both halves is what keeps this test evidence that the content moved rather than evidence
        // that it stopped being reported.
        Map<String, String> compatibilityRejected = Map.of(
                "ambiguous-key.graphml", "Ambiguous GraphML property name across two keys in the same scope",
                "duplicate-edge-id.graphml", "Duplicate GraphML edge id",
                "complex-canonical-collision.graphml", "Complex GraphML data collides with an executable property",
                "invalid-scalar.graphml", "GraphML scalar value does not match the type declared by its key",
                "late-key.graphml", "GraphML key declarations must precede the graph",
                "nested-graph.graphml", "Nested GraphML graphs are not supported",
                "orphan-data.graphml", "GraphML data must belong directly to a graph, node or edge");
        Map<String, Map.Entry<String, String>> compatibilityDetail = Map.of(
                "ambiguous-key.graphml", Map.entry("propertyName", "Owner"),
                "duplicate-edge-id.graphml", Map.entry("edgeId", "shared"),
                "complex-canonical-collision.graphml", Map.entry("propertyName", "kind"),
                "invalid-scalar.graphml", Map.entry("value", "not-an-integer"));
        // SecureGraphMlParser is the outer boundary and validates untrusted bytes before any DOM
        // parser sees them, so it owns the rejection of these three documents and classifies them.
        // The outcome is unchanged - they were rejected before and are rejected now; only the
        // sibling exception that reports it moved from the compatibility layer to the security layer.
        Map<String, GraphMlParseException.Reason> securityRejected = Map.of(
                "doctype-entity.graphml", GraphMlParseException.Reason.UNSAFE_XML,
                "dangling-edge.graphml", GraphMlParseException.Reason.INVALID_GRAPH,
                // Shared with ravenroot-ui/test/graphml-corpus.test.js, which asserts the UI
                // parser now refuses the same document for the same reason.
                "foreign-namespace-data-collision.graphml", GraphMlParseException.Reason.INVALID_GRAPH,
                // INT-05; guard parity. Rejected by both sides for the same reason and
                // with the same public message: ravenroot-ui/test/graphml-corpus.test.js pins the
                // refusal via the 'reserved-format-version.graphml' entry in its shared `rejected`
                // map. A reserved key declared for="graph" is still accepted on both sides today --
                // mirrored, not divergent. Both sides must change together if a
                // format-version marker is introduced. See the corpus README.
                "reserved-format-version.graphml", GraphMlParseException.Reason.INVALID_GRAPH);
        Map<String, String> securityMessages = Map.of(
                "doctype-entity.graphml", "GraphML DTD and entity declarations are not allowed",
                "dangling-edge.graphml", "GraphML edges must reference declared nodes",
                "foreign-namespace-data-collision.graphml",
                "GraphML extensions cannot redefine interpreted element names",
                "reserved-format-version.graphml",
                "declares a property in Ravenroot's reserved namespace");

        Stream<DynamicTest> compatibilityTests = compatibilityRejected.entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    var exception = assertThrows(GraphMlCompatibilityException.class,
                            () -> GraphManager.readGraphMl(new ByteArrayInputStream(
                                    fixture("rejected/" + entry.getKey()))));
                    assertEquals(entry.getValue(), exception.getMessage());
                    var expectedDetail = compatibilityDetail.get(entry.getKey());
                    if (expectedDetail != null) {
                        assertEquals(expectedDetail.getValue(),
                                exception.diagnosticDetail().get(expectedDetail.getKey()),
                                "the detail must survive off the message");
                        assertFalse(exception.getMessage().contains(expectedDetail.getValue()),
                                "and must not survive on it");
                    }
                }));
        Stream<DynamicTest> securityTests = securityRejected.entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    var exception = assertThrows(GraphMlParseException.class,
                            () -> GraphManager.readGraphMl(new ByteArrayInputStream(
                                    fixture("rejected/" + entry.getKey()))));
                    assertEquals(entry.getValue(), exception.reason());
                    assertTrue(exception.getMessage().contains(securityMessages.get(entry.getKey())),
                            exception.getMessage());
                }));
        return Stream.concat(compatibilityTests, securityTests);
    }

    @Test
    void rejectsWrongNamespaceAndDoctype() {
        String wrongNamespace = """
                <graphml xmlns="urn:not-graphml"><graph id="g" edgedefault="directed"/></graphml>
                """;
        // Both halves of this test feed documents the security layer now owns: a non-canonical root
        // and a DTD. Still rejected, reclassified from the compatibility layer to SecureGraphMlParser.
        var namespaceError = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(stream(wrongNamespace)));
        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, namespaceError.reason());
        assertTrue(namespaceError.getMessage()
                .contains("GraphML document must have a canonical graphml root element"));

        String doctype = """
                <!DOCTYPE graphml [<!ENTITY local "value">]>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed"/>
                </graphml>
                """;
        var doctypeError = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(stream(doctype)));
        assertEquals(GraphMlParseException.Reason.UNSAFE_XML, doctypeError.reason());
        assertTrue(doctypeError.getMessage().contains("GraphML DTD and entity declarations are not allowed"));
    }

    /**
     * These two tests assert CORE-01's export guard: once the imported graph has actually been
     * changed, exporting must refuse rather than ambiguously blend graph changes with preserved XML.
     *
     * <p>Under ARC-05 the traversal handed out by {@code query()} carries
     * {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.ReadOnlyStrategy},
     * so the mutating steps these tests used to issue are now refused before they can establish the
     * precondition. The mutation is therefore applied through {@code getGraph()} — the escape the
     * strategy does not close. What is asserted is unchanged; only how the graph is dirtied changed.
     * That the escape still exists is precisely why the export guard is still needed, so these tests
     * now also pin the escape open: if {@code getGraph()} were ever closed, they must be revisited
     * rather than silently passing for some other reason.</p>
     */
    @Test
    void refusesAmbiguousExportAfterTraversalMutation() {
        try (var manager = accepted("complex-extensions.graphml")) {
            // Control: before the mutation the export succeeds, so the refusal below is caused by the
            // mutation and not by the fixture being unexportable to begin with.
            manager.writeGraphMl(new ByteArrayOutputStream());

            mutateThroughEscape(manager, graph -> graph.V("start").property("owner", "mutated").iterate());

            var error = assertThrows(IllegalStateException.class,
                    () -> manager.writeGraphMl(new ByteArrayOutputStream()));
            assertTrue(error.getMessage().contains("mutated through a traversal"), error.getMessage());
        }
    }

    @Test
    void refusesAmbiguousExportAfterTopologyAddOrRemove() {
        try (var manager = accepted("topology.graphml")) {
            manager.writeGraphMl(new ByteArrayOutputStream());

            mutateThroughEscape(manager, graph -> graph.addV("ravenroot-node")
                    .property(org.apache.tinkerpop.gremlin.structure.T.id, "added")
                    .iterate());

            var error = assertThrows(IllegalStateException.class,
                    () -> manager.writeGraphMl(new ByteArrayOutputStream()));
            assertTrue(error.getMessage().contains("mutated through a traversal"), error.getMessage());
        }

        try (var manager = accepted("topology.graphml")) {
            manager.writeGraphMl(new ByteArrayOutputStream());

            mutateThroughEscape(manager, graph -> graph.V("worker").drop().iterate());

            var error = assertThrows(IllegalStateException.class,
                    () -> manager.writeGraphMl(new ByteArrayOutputStream()));
            assertTrue(error.getMessage().contains("mutated through a traversal"), error.getMessage());
        }
    }

    /**
     * The same mutation the strategy refuses, applied through the {@code getGraph()} escape so it
     * really lands on the manager's graph. Asserts that the strategy would have refused it, which is
     * what keeps these tests honest: the precondition is established by the escape, not by the
     * strategy having quietly stopped working.
     */
    private static void mutateThroughEscape(
            GraphManager manager,
            java.util.function.Consumer<org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource> mutation) {
        assertThrows(
                org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException.class,
                () -> manager.query(graph -> {
                    mutation.accept(graph);
                    return null;
                }),
                "the guarded traversal must still refuse this mutation");

        manager.query(graph -> {
            mutation.accept(graph.getGraph().traversal());
            return null;
        });
    }

    private static GraphManager accepted(String name) {
        return GraphManager.readGraphMl(new ByteArrayInputStream(fixture("accepted/" + name)));
    }

    private static byte[] fixture(String path) {
        try (var input = GraphMlCorpusTest.class.getResourceAsStream("/graphml-corpus/" + path)) {
            if (input == null) {
                throw new IllegalStateException("Missing GraphML corpus fixture " + path);
            }
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read GraphML corpus fixture " + path, exception);
        }
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Structural XML oracle: prefixes, formatting whitespace and attribute order are normalized;
     * namespace URIs, ordered content, non-formatting text, comments and attributes are retained.
     */
    private static String xmlSemantics(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newDefaultNSInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            var result = new StringBuilder();
            appendNode(document.getDocumentElement(), result, false);
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot build XML semantic snapshot", exception);
        }
    }

    private static void appendNode(Node node, StringBuilder result, boolean preserveWhitespace) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE -> {
                var element = (Element) node;
                result.append('<').append(expandedName(element));
                var attributes = new ArrayList<Node>();
                for (int index = 0; index < element.getAttributes().getLength(); index++) {
                    Node attribute = element.getAttributes().item(index);
                    if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                        attributes.add(attribute);
                    }
                }
                attributes.sort(Comparator.comparing(GraphMlCorpusTest::expandedName));
                for (Node attribute : attributes) {
                    result.append('[').append(expandedName(attribute)).append('=')
                            .append(attribute.getNodeValue()).append(']');
                }
                result.append('>');
                boolean insideData = preserveWhitespace
                        || GraphMlDocument.GRAPHML_NAMESPACE.equals(element.getNamespaceURI())
                        && ("data".equals(element.getLocalName()) || "default".equals(element.getLocalName()));
                for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                    appendNode(child, result, insideData);
                }
                result.append("</").append(expandedName(element)).append('>');
            }
            case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                if (preserveWhitespace || !node.getNodeValue().isBlank()) {
                    result.append("#text(").append(node.getNodeValue()).append(')');
                }
            }
            case Node.COMMENT_NODE -> result.append("#comment(").append(node.getNodeValue()).append(')');
            default -> {
                // Processing instructions and document formatting are not GraphML semantics.
            }
        }
    }

    private static String expandedName(Node node) {
        String namespace = node.getNamespaceURI() == null ? "" : node.getNamespaceURI();
        String localName = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        return '{' + namespace + '}' + localName;
    }
}
