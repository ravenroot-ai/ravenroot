package ai.ravenroot.core.graph;

import ai.ravenroot.core.graph.GraphMlProfileReport.DeclaredKey;
import ai.ravenroot.core.graph.GraphMlProfileReport.Disposition;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code GraphManager.validateGraphMl} says a document declared.
 *
 * <p>Every assertion here is about the same property: that a construct which crosses profile 0
 * without being interpreted is <em>named</em> rather than merely tolerated. The corpus already pins
 * that such constructs survive a round trip; what it could not pin is whether anyone is told.</p>
 */
class GraphMlProfileReportTest {

    @Test
    void separatesTheExecutableVocabularyFromEverythingElseItKept() {
        var report = validate(fixture("accepted/canonical-minimal.graphml"));

        assertEquals(GraphMlProfileReport.PROFILE, report.profile());
        assertEquals(3, report.nodes());
        assertEquals(1, report.edges());
        assertEquals(
                Map.of("n-kind", Disposition.INTERPRETED,
                        "e-outcome", Disposition.INTERPRETED,
                        "n-owner", Disposition.PRESERVED,
                        "e-trace", Disposition.PRESERVED),
                dispositions(report));
        // The two the runtime never reads are exactly the two a caller would otherwise never hear
        // about, so the summary count has to agree with the per-key lines.
        assertEquals(2, report.uninterpretedKeys().size());
    }

    /**
     * The disposition follows the effective property name, not the key id. {@code node-owner} and
     * {@code edge-owner} both declare {@code attr.name="owner"} and are preserved; {@code kind} and
     * {@code outcome} are interpreted although nothing in their ids says so.
     */
    @Test
    void classifiesByEffectivePropertyNameAndCountsWhatNeverReachesThePropertyGraph() {
        var report = validate(fixture("accepted/complex-extensions.graphml"));

        assertEquals(Disposition.INTERPRETED, key(report, "kind").disposition());
        assertEquals(Disposition.INTERPRETED, key(report, "outcome").disposition());
        assertEquals(Disposition.PRESERVED, key(report, "node-owner").disposition());
        assertEquals(Disposition.PRESERVED, key(report, "edge-owner").disposition());
        // yfiles.type keys declare no attr.name, so their effective name is the id itself: unknown,
        // preserved, and carrying an opaque subtree rather than a scalar.
        assertEquals(1, key(report, "node-graphics").opaqueValues());
        assertEquals(1, key(report, "edge-graphics").opaqueValues());
        assertEquals(2, report.opaqueDataValues());
        // y:ShapeNode, y:Geometry, y:NodeLabel, y:Shape, y:PolyLineEdge, y:Path, viz:annotation.
        assertEquals(7, report.foreignNamespaceElements());
    }

    @Test
    void namesTheScopesThatAreDeclaredButHaveNoPlaceInAPropertyGraph() {
        var report = validate("""
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
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(Disposition.DECLARED_OUTSIDE_PROPERTY_GRAPH, key(report, "d1").disposition());
        assertEquals(Disposition.DECLARED_OUTSIDE_PROPERTY_GRAPH, key(report, "d12").disposition());
        assertEquals(Disposition.INTERPRETED, key(report, "kind").disposition());
    }

    /** FIX-01's import-only handles are reported, because the author cannot look one up. */
    @Test
    void countsTheEdgeIdentitiesTheImportHadToInvent() {
        var report = validate(fixture("accepted/optional-edge-ids.graphml"));

        assertEquals(3, report.edges());
        assertEquals(2, report.synthesizedEdgeIds());
    }

    /**
     * The gap SEC-09 leaves open, pinned rather than described.
     *
     * <p>{@code GraphManager#rejectReservedProperties} walks the imported vertices and edges, so the
     * reserved-namespace refusal only fires where a {@code ravenroot.*} key actually became a
     * property. At graph scope nothing becomes a vertex or edge property, so the document is
     * accepted and the declaration is preserved untouched — which is the version-skew failure mode
     * SEC-09's Javadoc says the reservation exists to prevent, occurring at the one placement a
     * format marker would most naturally use. Graph scope does not inherit the reserved-property
     * refusal applied to vertices and edges.</p>
     *
     * <p>Closing this changes the SEC-09 contract and is outside this report's scope. What this report can do,
     * and does, is stop it being <em>silent</em>: the report names it, so the operator running
     * {@code validate} sees it even though the import did not object.</p>
     */
    @Test
    void reportsAReservedKeyThatIngestDidNotRefuseInsteadOfLettingItPassAsOrdinary() {
        byte[] source = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="fv" for="graph" attr.name="ravenroot.format.version" attr.type="int"/>
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <data key="fv">1</data>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e" source="start" target="end"/>
                  </graph>
                </graphml>
                """.getBytes(StandardCharsets.UTF_8);

        // Accepted today. This assertion is the pin: if a later change makes ingest refuse it, this
        // test fails and the new refusal has to be acknowledged rather than absorbed.
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            assertEquals(3, manager.nodeCount());
        }
        var report = validate(source);
        assertEquals(Disposition.RESERVED_NOT_REFUSED, key(report, "fv").disposition());
        assertEquals(List.of("fv"),
                report.keysWith(Disposition.RESERVED_NOT_REFUSED).stream().map(DeclaredKey::id).toList());
    }

    /**
     * The verdict of {@code validate} and the verdict of an import are the same verdict.
     *
     * <p>A validation command that certifies a document the runtime then refuses is worse than no
     * command, so this runs the whole rejected corpus through both entry points and requires the
     * same refusal from each. It also means a new rejected fixture is covered here without anyone
     * remembering to add it.</p>
     */
    @TestFactory
    Stream<DynamicTest> refusesExactlyWhatAnImportRefuses() {
        return rejectedCorpus().map(name -> DynamicTest.dynamicTest(name, () -> {
            byte[] source = fixture("rejected/" + name);
            var importFailure = assertThrows(RuntimeException.class,
                    () -> GraphManager.readGraphMl(new ByteArrayInputStream(source)));
            var validateFailure = assertThrows(RuntimeException.class,
                    () -> GraphManager.validateGraphMl(new ByteArrayInputStream(source)));

            assertEquals(importFailure.getClass(), validateFailure.getClass());
            assertEquals(importFailure.getMessage(), validateFailure.getMessage());
            // The incident id differs per rejection by design, so what is compared is the labelled
            // diagnostic content, which is what an operator actually reads.
            assertEquals(((GraphMlRejectionDetail) importFailure).diagnosticDetail(),
                    ((GraphMlRejectionDetail) validateFailure).diagnosticDetail());
        }));
    }

    /**
     * {@code validateGraphMl} used to answer only "did this parse and import", never "is this
     * a graph {@code GraphManager.definition()} would accept" -- no semantic rule
     * {@code GraphDefinition.validate()} enforces (terminal cardinality) and no semantic rule
     * {@code GraphManager} enforces while building nodes (an unknown kind, a {@code BEHAVIOR} node
     * with no name) was ever consulted, so a document missing its start node or naming a kind
     * Ravenroot does not know came back {@code report.valid() == true}. A dangling edge is not an
     * example here: it is refused earlier still, by the import itself, so it never reaches
     * {@code validateGraphMl} with a profile to attach a violation to in the first place --
     * {@link #refusesExactlyWhatAnImportRefuses()} above already covers it as the rejection it is.
     *
     * <p>The zero-or-one rule makes a document with no error terminal legitimate, so it cannot
     * demonstrate semantic invalidity. Two declared error terminals exercise the retained half of
     * the rule -- the ceiling, not
     * the floor -- and stays refused for a reason independent of the obligation (see
     * {@link GraphDefinition}'s {@code MAX_ERROR_NODES} Javadoc).</p>
     *
     * <p>The third document covers a node declaring {@code kind=BEHAVIOR} without a
     * {@code behavior} name is refused by {@link GraphNode}'s own constructor with a plain
     * {@link IllegalArgumentException}, not a {@link GraphValidationException} -- a different
     * exception type that a {@link GraphValidationException}-only path does not catch, so it
     * escaped {@code validateGraphMl} entirely and collapsed the verdict to {@code rejected} with no
     * profile at all. {@link #neverThrowsForAnyDocumentAnImportAccepts()}
     * below pins the general property this specific case is an instance of.</p>
     */
    @Test
    void namesTheSemanticViolationOfADocumentThatParsesButIsNotAValidGraph() {
        var unknownKindReport = validate(UNKNOWN_KIND);
        assertFalse(unknownKindReport.valid(), "an undeclared-and-unknown kind is not a valid graph");
        assertEquals(List.of("Node 'mystery' declares an unknown kind 'SUBGRAPH'; "
                        + "the known kinds are START, PASSTHROUGH, BEHAVIOR, END, ERROR"),
                unknownKindReport.violations());
        // The profile stays readable next to the negative verdict.
        assertEquals(3, unknownKindReport.nodes());
        assertEquals(2, unknownKindReport.edges());

        var twoErrorTerminalsReport = validate(TWO_ERROR_TERMINALS);
        assertFalse(twoErrorTerminalsReport.valid(), "a graph may declare at most one error terminal");
        assertEquals(List.of("A graph must contain at most one error node, and 2 are declared: first, second"),
                twoErrorTerminalsReport.violations());
        assertEquals(4, twoErrorTerminalsReport.nodes());
        assertEquals(3, twoErrorTerminalsReport.edges());

        var behaviorWithoutNameReport = validate(BEHAVIOR_WITHOUT_NAME);
        assertFalse(behaviorWithoutNameReport.valid(), "a behavior node must declare its name");
        assertEquals(List.of("Node 'mystery' declares kind 'BEHAVIOR' without a behavior name"),
                behaviorWithoutNameReport.violations());
        assertEquals(3, behaviorWithoutNameReport.nodes());
        assertEquals(2, behaviorWithoutNameReport.edges());
    }

    /** A structurally valid document reports no violations: the new check must not be vacuous. */
    @Test
    void reportsNoViolationsForAStructurallyValidDocument() {
        var report = validate(fixture("accepted/canonical-minimal.graphml"));

        assertTrue(report.valid());
        assertEquals(List.of(), report.violations());
    }

    private static final byte[] UNKNOWN_KIND = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="mystery"><data key="kind">SUBGRAPH</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="mystery"/>
                <edge id="e2" source="mystery" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);

    private static final byte[] TWO_ERROR_TERMINALS = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="first"><data key="kind">ERROR</data></node>
                <node id="second"><data key="kind">ERROR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="end"/>
                <edge id="e2" source="start" target="first"/>
                <edge id="e3" source="start" target="second"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * A {@code kind=BEHAVIOR} declaration with no {@code behavior} name
     * -- accepted by import exactly like the rest of this corpus, refused only when
     * {@code GraphManager} tries to build a node out of it.
     */
    private static final byte[] BEHAVIOR_WITHOUT_NAME = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="mystery"><data key="kind">BEHAVIOR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="mystery"/>
                <edge id="e2" source="mystery" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * General property: for every document import accepts, {@code validate}
     * must not throw -- not merely for the specific documents this test file happens to construct.
     * One caught exception type proved insufficient: {@link GraphNode}'s own constructor
     * raises a different one for a behavior node missing its name, and that document sailed through
     * import exactly like the rest of this corpus, then climbed out of {@code validateGraphMl}
     * uncaught. The accepted corpus plus that case form the starting set; run together
     * so a future fixture added to the corpus is covered here without anyone remembering to add it.
     */
    @TestFactory
    Stream<DynamicTest> neverThrowsForAnyDocumentAnImportAccepts() {
        return Stream.concat(
                acceptedCorpus().map(name -> DynamicTest.dynamicTest(name, () ->
                        assertValidateDoesNotThrowAndAgreesWithImport(fixture("accepted/" + name)))),
                Stream.of(DynamicTest.dynamicTest("behavior node without a name", () ->
                        assertValidateDoesNotThrowAndAgreesWithImport(BEHAVIOR_WITHOUT_NAME))));
    }

    private static void assertValidateDoesNotThrowAndAgreesWithImport(byte[] source) {
        long nodes;
        long edges;
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            nodes = manager.nodeCount();
            edges = manager.edgeCount();
        }
        var report = assertDoesNotThrow(() -> validate(source),
                "an import-accepted document must never make validateGraphMl throw");
        assertEquals(nodes, report.nodes());
        assertEquals(edges, report.edges());
    }

    /** And it accepts exactly what an import accepts, with counts taken from the graph itself. */
    @TestFactory
    Stream<DynamicTest> acceptsExactlyWhatAnImportAccepts() {
        return acceptedCorpus().map(name -> DynamicTest.dynamicTest(name, () -> {
            byte[] source = fixture("accepted/" + name);
            long nodes;
            long edges;
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
                nodes = manager.nodeCount();
                edges = manager.edgeCount();
            }
            var report = validate(source);

            assertEquals(nodes, report.nodes());
            assertEquals(edges, report.edges());
            assertEquals(GraphMlProfileReport.PROFILE, report.profile());
            assertTrue(report.keys().stream().allMatch(key -> key.disposition() != null));
        }));
    }

    /**
     * Which accepted fixtures are also <em>semantically</em> valid, and which one is deliberately not
     * under declared-join semantics.
     *
     * <p>{@link #neverThrowsForAnyDocumentAnImportAccepts} and
     * {@link #acceptsExactlyWhatAnImportAccepts} both run every accepted fixture through
     * {@code validateGraphMl}, which computes semantic violations — and neither has
     * ever asserted the verdict. That made this file a green suite that could absorb a change in what
     * a document <em>means</em> without noticing, which is exactly what declared-join semantics did to
     * {@code topology.graphml}: its {@code worker} node carries a self-loop, so its distinct
     * predecessors are {@code {start, worker}}, so under the inferred-join semantics that fixture
     * declares it is a join whose branch set includes itself — statically unsatisfiable, and refused
     * at load.</p>
     *
     * <p>The fixture stays as it is, and stays in {@code accepted/}, because "accepted" here means
     * <em>import</em> accepted and that is still true: it parses, it imports, it exports byte for
     * byte, and it is the only fixture pinning parallel edge ids, a self-loop and edge-before-node
     * ordering. Fixtures which exist to prove parsing stay as they
     * are. What changes is that the changed verdict is now written down here instead of being
     * invisible.</p>
     *
     * <p>Kept as a named set rather than a boolean on each entry so that adding a fixture without
     * touching this map lands in the valid group, which is the safe default: a new fixture that is
     * accidentally invalid fails loudly instead of being silently excused.</p>
     */
    private static final java.util.Set<String> SEMANTICALLY_INVALID_ACCEPTED_FIXTURES =
            java.util.Set.of("topology.graphml");

    /**
     * The assertion the two factories above were missing: what {@code validate} actually says about
     * each accepted fixture, not merely that it says it without throwing.
     */
    @TestFactory
    Stream<DynamicTest> reportsTheSemanticVerdictEachAcceptedFixtureEarns() {
        return acceptedCorpus().map(name -> DynamicTest.dynamicTest(name, () -> {
            var report = validate(fixture("accepted/" + name));
            if (SEMANTICALLY_INVALID_ACCEPTED_FIXTURES.contains(name)) {
                assertFalse(report.valid(),
                        name + " is recorded as semantically invalid; if it has become valid again the "
                                + "recorded reason no longer holds and this set must be updated "
                                + "deliberately");
                assertTrue(report.violations().stream()
                                .anyMatch(violation -> violation.contains("branches include itself")),
                        report.violations().toString());
            } else {
                assertTrue(report.valid(),
                        name + " must stay semantically valid: " + report.violations());
            }
        }));
    }

    private static Stream<String> acceptedCorpus() {
        return Stream.of("canonical-minimal.graphml", "scalar-types.graphml",
                "complex-extensions.graphml", "defaults-and-scopes.graphml", "topology.graphml",
                "optional-edge-ids.graphml");
    }

    private static Stream<String> rejectedCorpus() {
        return Stream.of("ambiguous-key.graphml", "complex-canonical-collision.graphml",
                "dangling-edge.graphml", "doctype-entity.graphml", "duplicate-edge-id.graphml",
                "foreign-namespace-data-collision.graphml", "invalid-scalar.graphml",
                "late-key.graphml", "nested-graph.graphml", "orphan-data.graphml",
                "reserved-format-version.graphml");
    }

    private static GraphMlProfileReport validate(byte[] source) {
        return GraphManager.validateGraphMl(new ByteArrayInputStream(source));
    }

    private static Map<String, Disposition> dispositions(GraphMlProfileReport report) {
        return report.keys().stream()
                .collect(java.util.stream.Collectors.toMap(DeclaredKey::id, DeclaredKey::disposition));
    }

    private static DeclaredKey key(GraphMlProfileReport report, String id) {
        var found = report.keys().stream().filter(key -> key.id().equals(id)).findFirst().orElse(null);
        assertNotNull(found, "no declared key " + id + " in " + report.keys());
        return found;
    }

    private static byte[] fixture(String path) {
        try (var input = GraphMlProfileReportTest.class.getResourceAsStream("/graphml-corpus/" + path)) {
            if (input == null) {
                throw new IllegalStateException("Missing GraphML corpus fixture " + path);
            }
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read GraphML corpus fixture " + path, exception);
        }
    }
}
