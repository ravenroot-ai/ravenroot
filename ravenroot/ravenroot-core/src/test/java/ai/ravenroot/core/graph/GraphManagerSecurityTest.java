package ai.ravenroot.core.graph;

import com.sun.net.httpserver.HttpServer;
import javax.xml.stream.XMLInputFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class GraphManagerSecurityTest {
    private static final int SMALL_STRING_LIMIT = 64;
    private static final GraphMlLimits SMALL_LIMITS =
            new GraphMlLimits(4096, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4);

    private static final List<String> UNSAFE_RESOURCES = List.of(
            "/security/graphml/internal-entity.graphml",
            "/security/graphml/external-entity.graphml",
            "/security/graphml/entity-expansion.graphml",
            "/security/graphml/external-dtd.graphml");

    static List<String> unsafeResources() {
        return UNSAFE_RESOURCES;
    }

    @ParameterizedTest
    @MethodSource("unsafeResources")
    void rejectsDtdEntitiesAndExpansionBeforeGraphMaterialization(String resource) throws Exception {
        try (var input = getClass().getResourceAsStream(resource)) {
            var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(input));
            assertEquals(GraphMlParseException.Reason.UNSAFE_XML, rejection.reason());
            assertFalse(rejection.getMessage().contains("invalid.example"));
        }
    }

    /**
     * Classification must follow constructs the parser itself identified, never the JDK's diagnostic
     * text. Those messages are localized, so before FIX-06 the premature EOF in truncated.graphml was
     * reported as "XML document structures must start and end within the same entity": the substring
     * match on ENTITY made the same bytes UNSAFE_XML under en-US and de-DE but MALFORMED_XML under
     * it-IT and ja-JP. This test asserts only the classification, never the wording, so it also holds
     * on a JDK that ships no translations for these locales.
     */
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "it-IT", "de-DE", "ja-JP"})
    void classifiesRejectionsIdenticallyInEveryLocale(String languageTag) {
        Locale restore = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            assertReason(GraphMlParseException.Reason.MALFORMED_XML,
                    "/security/graphml/truncated.graphml");
            for (String unsafe : UNSAFE_RESOURCES) {
                assertReason(GraphMlParseException.Reason.UNSAFE_XML, unsafe);
            }
        } finally {
            Locale.setDefault(restore);
        }
    }

    /**
     * An entity reference with no DTD at all still reaches the parser as an ENTITY_REFERENCE event,
     * so it is refused structurally rather than because the JDK happened to describe it. Predefined
     * entities and numeric character references need no declaration and must stay accepted.
     */
    @Test
    void refusesUndeclaredEntityReferencesButAcceptsPredefinedAndNumericOnes() {
        String undeclared = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="v" for="node" attr.name="v" attr.type="string"/>
                  <graph id="g" edgedefault="directed"><node id="n"><data key="v">&payload;</data></node></graph>
                </graphml>
                """;
        var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(undeclared.getBytes(StandardCharsets.UTF_8))));
        assertEquals(GraphMlParseException.Reason.UNSAFE_XML, rejection.reason());

        String predefined = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="v" for="node" attr.name="v" attr.type="string"/>
                  <graph id="g" edgedefault="directed"><node id="n"><data key="v">&amp;&lt;&#38;</data></node></graph>
                </graphml>
                """;
        try (var manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(predefined.getBytes(StandardCharsets.UTF_8)))) {
            assertEquals(1, manager.nodeCount());
        }
    }

    /**
     * Pins the JDK behaviour the UNSAFE_XML classification now rests on: with SUPPORT_DTD disabled
     * the reader still emits a DTD event for every DOCTYPE form and every encoding, which is what
     * lets the parser refuse it structurally instead of reading a message. If a future JDK hard-throws
     * instead of emitting the event, the document stays rejected but would silently be relabelled
     * MALFORMED_XML - this test is the tripwire that makes that a loud failure rather than a drift.
     */
    @Test
    void pinsDtdRefusalAcrossEveryDoctypeFormAndEncoding() {
        var doctypes = List.of(
                "<!DOCTYPE graphml>",
                "<!DOCTYPE graphml [<!ENTITY p \"x\">]>",
                "<!DOCTYPE graphml SYSTEM \"https://invalid.example/g.dtd\">",
                "<!DOCTYPE graphml PUBLIC \"-//x//DTD g//EN\" \"https://invalid.example/g.dtd\">");
        for (String doctype : doctypes) {
            String xml = doctype + """
                    <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                      <graph id="g" edgedefault="directed"><node id="n"/></graph>
                    </graphml>
                    """;
            assertReasonOf(GraphMlParseException.Reason.UNSAFE_XML,
                    xml.getBytes(StandardCharsets.UTF_8), doctype + " as UTF-8");
            assertReasonOf(GraphMlParseException.Reason.UNSAFE_XML,
                    withBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                            xml.getBytes(StandardCharsets.UTF_8)), doctype + " as UTF-8 with BOM");
            assertReasonOf(GraphMlParseException.Reason.UNSAFE_XML,
                    withBom(new byte[]{(byte) 0xFF, (byte) 0xFE},
                            xml.getBytes(StandardCharsets.UTF_16LE)), doctype + " as UTF-16LE");
            assertReasonOf(GraphMlParseException.Reason.UNSAFE_XML,
                    withBom(new byte[]{(byte) 0xFE, (byte) 0xFF},
                            xml.getBytes(StandardCharsets.UTF_16BE)), doctype + " as UTF-16BE");
        }
    }

    /**
     * Deliberate, not incidental. An entity reference in element content reaches the parser as an
     * ENTITY_REFERENCE event, so it is positively identified and refused as UNSAFE_XML. The same
     * reference in an attribute value makes the JDK abandon the start-tag before any event is
     * delivered, so the parser never observes an entity at all and the document is simply not
     * well-formed. FIX-06 classifies that case as MALFORMED_XML: an undeclared reference
     * carries no declaration and so cannot be an XXE, and the only way to label it UNSAFE_XML would
     * be to guess lexically over attacker-controlled bytes, reintroducing the same defect class.
     * Do not "correct" this asymmetry into a heuristic.
     */
    @Test
    void classifiesEntityReferenceByObservedPositionNotByGuessing() {
        String inContent = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed"><node id="n">&payload;</node></graph>
                </graphml>
                """;
        String inAttribute = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed"><node id="&payload;"/></graph>
                </graphml>
                """;
        assertReasonOf(GraphMlParseException.Reason.UNSAFE_XML,
                inContent.getBytes(StandardCharsets.UTF_8), "entity reference in element content");
        assertReasonOf(GraphMlParseException.Reason.MALFORMED_XML,
                inAttribute.getBytes(StandardCharsets.UTF_8), "entity reference in attribute value");
    }

    /**
     * The content-position ENTITY_REFERENCE detection depends on entity replacement staying disabled,
     * which is NOT the JDK default. setRequired guards against the property being unsupported, not
     * against it being changed, so pin the value itself.
     */
    @Test
    void pinsTheParserPropertiesThatMakeUnsafeConstructsObservable() {
        var factory = SecureGraphMlParser.secureInputFactory();
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES));
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.SUPPORT_DTD));
        assertEquals(Boolean.FALSE, factory.getProperty("javax.xml.stream.isSupportingExternalEntities"));
    }

    /** Parser diagnostics embed attacker-supplied names, so no rejection may carry them as a cause. */
    @Test
    void rejectionsNeverCarryParserDiagnosticsAsACause() {
        for (String resource : UNSAFE_RESOURCES) {
            try (var input = GraphManagerSecurityTest.class.getResourceAsStream(resource)) {
                var rejection = assertThrows(GraphMlParseException.class,
                        () -> GraphManager.readGraphMl(input));
                assertNull(rejection.getCause(), resource);
            } catch (IOException error) {
                throw new AssertionError("Cannot read " + resource, error);
            }
        }
        String badEncoding = "<?xml version=\"1.0\" encoding=\"NO-SUCH-ENC\"?><graphml/>";
        var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(badEncoding.getBytes(StandardCharsets.UTF_8))));
        assertNull(rejection.getCause());
        assertFalse(rejection.getMessage().contains("NO-SUCH-ENC"));
    }

    private static byte[] withBom(byte[] bom, byte[] body) {
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private static void assertReasonOf(GraphMlParseException.Reason expected, byte[] xml, String detail) {
        var rejection = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(new ByteArrayInputStream(xml)), detail);
        assertEquals(expected, rejection.reason(), detail + " in locale " + Locale.getDefault());
    }

    private static void assertReason(GraphMlParseException.Reason expected, String resource) {
        try (var input = GraphManagerSecurityTest.class.getResourceAsStream(resource)) {
            var rejection = assertThrows(GraphMlParseException.class,
                    () -> GraphManager.readGraphMl(input));
            assertEquals(expected, rejection.reason(), resource + " in locale " + Locale.getDefault());
        } catch (IOException error) {
            throw new AssertionError("Cannot read " + resource, error);
        }
    }

    @Test
    void rejectsMalformedInputWithAStableSanitizedError() throws Exception {
        try (var input = getClass().getResourceAsStream("/security/graphml/truncated.graphml")) {
            var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(input));
            assertEquals(GraphMlParseException.Reason.MALFORMED_XML, rejection.reason());
            assertEquals("GraphML document is not well formed", rejection.getMessage());
        }
    }

    @Test
    void rejectsCompressedExpansionPayloadsWithoutDecompressingThem() throws Exception {
        byte[] expanded = graphMl(1, 0, 1, "x".repeat(SMALL_LIMITS.maxBytes() * 2))
                .getBytes(StandardCharsets.UTF_8);
        byte[] gzip = gzip(expanded);
        byte[] zip = zip(expanded);
        assertTrue(gzip.length < SMALL_LIMITS.maxBytes());
        assertTrue(zip.length < SMALL_LIMITS.maxBytes());

        for (byte[] archive : new byte[][]{gzip, zip}) {
            var rejection = assertThrows(GraphMlParseException.class,
                    () -> GraphManager.readGraphMl(new ByteArrayInputStream(archive), SMALL_LIMITS));
            assertEquals(GraphMlParseException.Reason.COMPRESSED_ARCHIVE, rejection.reason());
            assertEquals("GraphML document was refused because it is a compressed archive, not an XML "
                    + "document", rejection.getMessage());
        }
    }

    @Test
    void rejectsExternalEntitiesWithoutRequestingTheResource() throws Exception {
        var requests = new AtomicInteger();
        var resourceServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resourceServer.createContext("/secret", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 6);
            try (var response = exchange.getResponseBody()) {
                response.write("secret".getBytes(StandardCharsets.UTF_8));
            }
        });
        resourceServer.start();
        try {
            String xml = """
                    <?xml version="1.0"?>
                    <!DOCTYPE graphml [<!ENTITY xxe SYSTEM "http://127.0.0.1:%d/secret">]>
                    <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                      <graph id="unsafe" edgedefault="directed"><node id="n">&xxe;</node></graph>
                    </graphml>
                    """.formatted(resourceServer.getAddress().getPort());
            var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            assertEquals(GraphMlParseException.Reason.UNSAFE_XML, rejection.reason());
            assertEquals(0, requests.get());
        } finally {
            resourceServer.stop(0);
        }
    }

    @Test
    void readsNoMoreThanByteLimitPlusOne() {
        var input = new CountingInputStream(new byte[SMALL_LIMITS.maxBytes() + 100]);
        var rejection = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(input, SMALL_LIMITS));
        assertEquals(GraphMlParseException.Reason.DOCUMENT_TOO_LARGE, rejection.reason());
        assertEquals(SMALL_LIMITS.maxBytes() + 1, input.bytesRead);
    }

    @Test
    void acceptsDocumentExactlyAtByteLimitAndRejectsOneByteOver() {
        byte[] valid = graphMl(1, 0, 0, "ok").getBytes(StandardCharsets.UTF_8);
        var exact = new GraphMlLimits(valid.length, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4);
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(valid), exact)) {
            assertEquals(1, manager.nodeCount());
        }
        var under = new GraphMlLimits(valid.length - 1, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4);
        var rejection = assertThrows(GraphMlParseException.class,
                () -> GraphManager.readGraphMl(new ByteArrayInputStream(valid), under));
        assertEquals(GraphMlParseException.Reason.DOCUMENT_TOO_LARGE, rejection.reason());
    }

    @Test
    void enforcesNodeEdgePropertyKeyDepthAndStringLimits() {
        assertLimit("node count", graphMl(4, 0, 0, "ok"),
                new GraphMlLimits(4096, 3, 4, 5, 8, SMALL_STRING_LIMIT, 4));
        assertLimit("edge count", graphMl(2, 4, 0, "ok"),
                new GraphMlLimits(4096, 3, 3, 5, 8, SMALL_STRING_LIMIT, 4));
        assertLimit("property count", graphMl(1, 0, 5, "ok"),
                new GraphMlLimits(4096, 3, 3, 4, 8, SMALL_STRING_LIMIT, 5));
        assertLimit("key count", graphMl(1, 0, 0, "ok", 5),
                new GraphMlLimits(4096, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4));
        assertLimit("XML depth", nestedExtension(9),
                new GraphMlLimits(4096, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4));
        assertLimit("text value", graphMl(1, 0, 1, "x".repeat(SMALL_STRING_LIMIT + 1)),
                new GraphMlLimits(4096, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4));
    }

    @Test
    void enforcesNamespaceAndProcessingInstructionStringLimits() {
        String namespaceAtLimit = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:x="urn:%s">
                  <graph id="g" edgedefault="directed"><node id="n"/></graph>
                </graphml>
                """.formatted("x".repeat(SMALL_STRING_LIMIT - "urn:".length()));
        assertAccepted(namespaceAtLimit, SMALL_LIMITS);
        String namespaceOverLimit = namespaceAtLimit.replace(
                "x".repeat(SMALL_STRING_LIMIT - "urn:".length()),
                "x".repeat(SMALL_STRING_LIMIT - "urn:".length() + 1));
        assertLimit("namespace URI", namespaceOverLimit, SMALL_LIMITS);

        String processingInstructionAtLimit = """
                <?oversized %s?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed"><node id="n"/></graph>
                </graphml>
                """.formatted("x".repeat(SMALL_STRING_LIMIT));
        assertAccepted(processingInstructionAtLimit, SMALL_LIMITS);
        String processingInstructionOverLimit = processingInstructionAtLimit.replace(
                "x".repeat(SMALL_STRING_LIMIT), "x".repeat(SMALL_STRING_LIMIT + 1));
        assertLimit("processing instruction data", processingInstructionOverLimit, SMALL_LIMITS);
    }

    /**
     * FIX-01 relaxed a required attribute in the compatibility layer. The security layer's
     * edge budget must be indifferent to it: SecureGraphMlParser counts every {@code <edge>} start
     * element whether or not the author gave it an id, so an id-less document can neither slip past
     * the ceiling nor be undercounted below it.
     */
    @Test
    void countsEdgesWithoutIdsAgainstTheSameEdgeBudget() {
        var limits = new GraphMlLimits(4096, 3, 3, 5, 8, SMALL_STRING_LIMIT, 4);
        assertLimit("edge count", edgesWithoutIds(2, 4), limits);
        try (var manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(edgesWithoutIds(2, 3).getBytes(StandardCharsets.UTF_8)),
                limits)) {
            assertEquals(3, manager.edgeCount());
        }

        int ceiling = GraphMlLimits.DEFAULTS.maxEdges();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(
                    edgesWithoutIds(2, ceiling).getBytes(StandardCharsets.UTF_8)))) {
                assertEquals(ceiling, manager.edgeCount());
            }
            assertLimit("edge count", edgesWithoutIds(2, ceiling + 1), GraphMlLimits.DEFAULTS);
        });
    }

    @Test
    void materializesACombinedGraphAtTheDefaultTopologyBudgetsWithinBoundedTime() {
        byte[] xml = graphMlAtDefaultTopologyBudgets().getBytes(StandardCharsets.UTF_8);
        assertTrue(xml.length <= GraphMlLimits.DEFAULTS.maxBytes());
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
                assertEquals(GraphMlLimits.DEFAULTS.maxNodes(), manager.nodeCount());
                assertEquals(GraphMlLimits.DEFAULTS.maxEdges(), manager.edgeCount());
                long propertyCount = manager.query(graph -> graph.V().properties().count().next());
                assertEquals((long) GraphMlLimits.DEFAULTS.maxProperties(), propertyCount);
            }
        });
    }

    /**
     * This fixture deliberately leaves the document <em>without</em> an error terminal, unlike every other
     * graph in the repository. Its subject is SEC-08's node/edge/property ceilings, and the limits
     * below are set to exactly what the document declares ({@code maxNodes = 2}) so that "accepted at
     * the limit" is asserted rather than assumed. A third node would either breach the ceiling or
     * force the ceiling up, and either way this would stop measuring the limit. Nothing here builds a
     * {@code GraphDefinition}, so the minimal-structure rule is never reached: this is a parser
     * fixture, not a graph anyone runs.
     *
     * <p>The explanation sits here rather than inside the document on purpose. Written as an XML
     * comment it was rejected by {@code SecureGraphMlParser} with "GraphML text value exceeds the
     * configured limit" — the {@code SMALL_STRING_LIMIT} this fixture pins counts comment text too,
     * which is itself the guard doing its job.</p>
     */
    @Test
    void acceptsNearLimitGraphAndPreservesInertExtensionMarkup() {
        String xml = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:x="urn:test">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="opaque" for="node" attr.name="opaque" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="a"><data key="kind">START</data><x:box x:flag="yes">kept</x:box></node>
                    <node id="b"><data key="kind">END</data></node>
                    <edge id="e" source="a" target="b"/>
                  </graph>
                </graphml>
                """;
        var limits = new GraphMlLimits(xml.getBytes(StandardCharsets.UTF_8).length,
                2, 1, 3, 6, SMALL_STRING_LIMIT, 2);
        try (var manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), limits)) {
            assertEquals(2, manager.nodeCount());
            assertEquals(1, manager.edgeCount());
        }
        try (var document = GraphManager.readGraphMlDocument(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))) {
            assertEquals(xml, new String(document.bytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void documentSnapshotIsBoundedAndDefensivelyCopied() {
        byte[] input = graphMl(1, 0, 0, "ok").getBytes(StandardCharsets.UTF_8);
        try (var document = GraphManager.readGraphMlDocument(new ByteArrayInputStream(input))) {
            byte[] first = document.bytes();
            first[0] = 'X';
            assertFalse(first[0] == document.bytes()[0]);
            assertEquals(1, document.manager().nodeCount());
        }
    }

    @Test
    void rejectsEdgesThatWouldSynthesizeVerticesBeyondTheNodeBudget() {
        String xml = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="g" edgedefault="directed">
                    <node id="declared"/>
                    <edge id="e" source="undeclared-a" target="undeclared-b"/>
                  </graph>
                </graphml>
                """;
        var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), SMALL_LIMITS));
        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
    }

    @Test
    void rejectsForeignNamespaceRootAndCoreNameCollisionsInExtensions() {
        String foreignRoot = """
                <evil:graphml xmlns:evil="urn:attacker">
                  <evil:graph id="g" edgedefault="directed"><evil:node id="shadow"/></evil:graph>
                </evil:graphml>
                """;
        String collidingExtension = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:x="urn:extension">
                  <graph id="g" edgedefault="directed">
                    <node id="real"><x:node id="shadow"/></node>
                  </graph>
                </graphml>
                """;
        for (String xml : new String[]{foreignRoot, collidingExtension}) {
            var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
        }
    }

    @Test
    void rejectsCanonicalStructuralElementsInsideOpaqueExtensions() {
        String xml = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:x="urn:extension">
                  <graph id="g" edgedefault="directed">
                    <node id="real"><x:box><node id="injected"/></x:box></node>
                  </graph>
                </graphml>
                """;
        var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        assertEquals(GraphMlParseException.Reason.INVALID_GRAPH, rejection.reason());
    }

    @Test
    void boundsTotalElementsAttributesAndNamespaceDeclarations() {
        String xml = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:x="urn:x">
                  <graph id="g" edgedefault="directed"><node id="n"><x:meta flag="yes"/></node></graph>
                </graphml>
                """;
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        assertLimit("element count", xml,
                new GraphMlLimits(bytes.length, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4, 3, 20, 10));
        assertLimit("attribute count", xml,
                new GraphMlLimits(bytes.length, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4, 10, 3, 10));
        assertLimit("namespace declaration count", xml,
                new GraphMlLimits(bytes.length, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4, 10, 20, 1));
    }

    @Test
    void rejectsUnsafeCallerSuppliedLimitsBeforeAllocatingParserState() {
        assertThrows(IllegalArgumentException.class,
                () -> new GraphMlLimits(4096, 3, 3, 4, Integer.MAX_VALUE, SMALL_STRING_LIMIT, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new GraphMlLimits(Integer.MAX_VALUE, 3, 3, 4, 8, SMALL_STRING_LIMIT, 4));
    }

    @Test
    void deterministicMutationCorpusNeverEscapesTypedFailuresOrHangs() {
        byte[] seed = graphMl(2, 1, 1, "ok").getBytes(StandardCharsets.UTF_8);
        var typedRejections = new java.util.concurrent.atomic.AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var random = new Random(0x5EC08);
            byte[] mutationAlphabet = "<>/='\"&; az09\n".getBytes(StandardCharsets.US_ASCII);
            for (int sample = 0; sample < 250; sample++) {
                byte[] mutation = seed.clone();
                int changes = 1 + random.nextInt(4);
                for (int change = 0; change < changes; change++) {
                    mutation[random.nextInt(mutation.length)] =
                            mutationAlphabet[random.nextInt(mutationAlphabet.length)];
                }
                try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(mutation), SMALL_LIMITS)) {
                    assertTrue(manager.nodeCount() <= SMALL_LIMITS.maxNodes());
                    assertTrue(manager.edgeCount() <= SMALL_LIMITS.maxEdges());
                } catch (GraphMlParseException | GraphMlCompatibilityException expected) {
                    // Both typed rejection paths, checked identically because after FIX-03 they are
                    // the same contract: the security layer refuses bytes, the compatibility layer
                    // refuses documents that cleared the limits but cannot be mapped.
                    //
                    // This used to be a prefix check, which the message change would have made
                    // trivially satisfiable by widening the prefix list. Asserting membership of the
                    // declared set is strictly stronger: a prefix check passes for any message that
                    // merely begins the right way, including one that continues with a fragment of
                    // the mutated document, whereas an exact member of GraphMlRejection's declared
                    // vocabulary cannot contain document content at all. So this now enforces both
                    // "no failure escapes untyped" and "no message escapes undeclared", over 250
                    // mutations, on every rejection path the corpus reaches.
                    var rejection = (GraphMlRejectionDetail) expected;
                    assertTrue(DECLARED_MESSAGES.contains(expected.getMessage()),
                            "undeclared rejection message: " + expected.getMessage());
                    assertNotNull(rejection.reason(), expected.getMessage());
                    assertNotNull(rejection.incidentId(), expected.getMessage());
                    typedRejections.incrementAndGet();
                }
            }
        });
        // A corpus that stopped producing rejections would satisfy every assertion above without
        // exercising any of them. Pinned so the fuzzer cannot pass by having become vacuous.
        // Measured at 247 of 250 mutations on this seed and this fixed RNG seed, so the assertions
        // above are exercised rather than skipped. Pinned as a floor so the fuzzer cannot pass by
        // having quietly stopped rejecting anything.
        assertTrue(typedRejections.get() > 200,
                "the mutation corpus produced only " + typedRejections.get()
                        + " rejections, so it has stopped asserting what it claims to");
    }

    private static final java.util.Set<String> DECLARED_MESSAGES =
            GraphMlRejection.declaredPublicMessages();

    private static void assertLimit(String name, String xml, GraphMlLimits limits) {
        var rejection = assertThrows(GraphMlParseException.class, () -> GraphManager.readGraphMl(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), limits));
        assertEquals(GraphMlParseException.Reason.RESOURCE_LIMIT, rejection.reason());
        assertTrue(rejection.getMessage().contains(name));
    }

    private static void assertAccepted(String xml, GraphMlLimits limits) {
        try (var manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), limits)) {
            assertEquals(1, manager.nodeCount());
        }
    }

    /** GraphML leaves the edge id optional; these edges deliberately carry none. */
    private static String edgesWithoutIds(int nodes, int edges) {
        var xml = new StringBuilder("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">")
                .append("<graph id=\"g\" edgedefault=\"directed\">");
        for (int node = 0; node < nodes; node++) {
            xml.append("<node id=\"n").append(node).append("\"/>");
        }
        for (int edge = 0; edge < edges; edge++) {
            xml.append("<edge source=\"n0\" target=\"n1\"/>");
        }
        return xml.append("</graph></graphml>").toString();
    }

    private static String graphMl(int nodes, int edges, int properties, String value) {
        return graphMl(nodes, edges, properties, value, Math.max(1, properties));
    }

    private static String graphMl(int nodes, int edges, int properties, String value, int keys) {
        var xml = new StringBuilder("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">");
        for (int key = 0; key < keys; key++) {
            xml.append("<key id=\"k").append(key).append("\" for=\"node\" attr.name=\"p")
                    .append(key).append("\" attr.type=\"string\"/>");
        }
        xml.append("<graph id=\"g\" edgedefault=\"directed\">");
        for (int node = 0; node < nodes; node++) {
            xml.append("<node id=\"n").append(node).append("\">");
            for (int property = 0; property < properties && node == 0; property++) {
                xml.append("<data key=\"k").append(property % keys).append("\">")
                        .append(value).append("</data>");
            }
            xml.append("</node>");
        }
        for (int edge = 0; edge < edges; edge++) {
            xml.append("<edge id=\"e").append(edge).append("\" source=\"n0\" target=\"n1\"/>");
        }
        return xml.append("</graph></graphml>").toString();
    }

    private static String nestedExtension(int depth) {
        return "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"><graph id=\"g\" edgedefault=\"directed\">"
                + "<node id=\"n\"><data key=\"unknown\">" + "<x>".repeat(depth)
                + "ok" + "</x>".repeat(depth) + "</data></node></graph></graphml>";
    }

    private static String graphMlAtDefaultTopologyBudgets() {
        int propertyKeys = GraphMlLimits.DEFAULTS.maxProperties() / GraphMlLimits.DEFAULTS.maxNodes();
        var xml = new StringBuilder("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">");
        for (int key = 0; key < propertyKeys; key++) {
            xml.append("<key id=\"k").append(key).append("\" for=\"node\" attr.name=\"p")
                    .append(key).append("\" attr.type=\"string\"/>");
        }
        xml.append("<graph id=\"g\" edgedefault=\"directed\">");
        for (int node = 0; node < GraphMlLimits.DEFAULTS.maxNodes(); node++) {
            xml.append("<node id=\"n").append(node).append("\">");
            for (int key = 0; key < propertyKeys; key++) {
                xml.append("<data key=\"k").append(key).append("\">v</data>");
            }
            xml.append("</node>");
        }
        for (int edge = 0; edge < GraphMlLimits.DEFAULTS.maxEdges(); edge++) {
            // Kept id-bearing on purpose after FIX-01 made the id optional. This generator
            // pins materialization cost at the ceiling, and the id-bearing shape is the heavier one:
            // more bytes, one more attribute per edge against maxAttributes, and a full edge-id set
            // in GraphMlDocument.validateTopology. The id-less shape has its own coverage in
            // countsEdgesWithoutIdsAgainstTheSameEdgeBudget rather than replacing this one.
            xml.append("<edge id=\"e").append(edge).append("\" source=\"n0\" target=\"n1\"/>");
        }
        return xml.append("</graph></graphml>").toString();
    }

    private static byte[] gzip(byte[] input) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        }
        return output.toByteArray();
    }

    private static byte[] zip(byte[] input) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("graph.graphml"));
            zip.write(input);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static final class CountingInputStream extends InputStream {
        private final byte[] bytes;
        private int offset;
        private int bytesRead;

        private CountingInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            if (offset == bytes.length) {
                return -1;
            }
            bytesRead++;
            return bytes[offset++];
        }

        @Override
        public int read(byte[] target, int start, int length) throws IOException {
            if (offset == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, target, start, count);
            offset += count;
            bytesRead += count;
            return count;
        }
    }
}
