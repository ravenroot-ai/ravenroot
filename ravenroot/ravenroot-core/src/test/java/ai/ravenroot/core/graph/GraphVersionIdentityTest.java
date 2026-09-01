package ai.ravenroot.core.graph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The two graph identifiers are different things, and this test exists to keep them that way.
 *
 * <p>{@code graphVersion} — the value in the acceptance response, in execution events, in structured
 * logs, in the README, in the tutorial and in five ADRs — is the <b>raw-byte submission hash</b>:
 * a fingerprint of the exact bytes a client submitted. ADR 0006's glossary states it is expressly
 * "not a semantic version" and not a graph-definition id or revision.</p>
 *
 * <p>ARC-02 adds a <b>canonical semantic hash</b> and a logical {@code (graphId, versionId)} identity.
 * These are <b>new and additive</b>. They answer a different question: not "which bytes arrived" but
 * "which definition is this, regardless of how it was serialised".</p>
 *
 * <p>These assertions prevent redefining {@code graphVersion} to mean the semantic hash. Doing so would retroactively change the
 * meaning of every event already recorded and every client's correlation key without a
 * compatibility policy for that semantic redefinition. The tests below pin the observable difference:
 * two byte-different documents that mean the same graph must agree semantically and disagree
 * byte-wise. Any change that collapsed the two identifiers would have to break one of these.</p>
 */
class GraphVersionIdentityTest {

    /** Same executable graph, different bytes: reordered declarations, attribute order, a comment. */
    private static final String FIRST = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="workflow" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="route" source="start" target="end">
                  <data key="outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    private static final String SECOND = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns"><!-- same executable graph -->
              <key attr.type="string" attr.name="outcome" for="edge" id="outcome"/>
              <key attr.type="string" attr.name="kind" for="node" id="kind"/>
              <graph edgedefault="directed" id="workflow">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="start"><data key="kind">START</data></node>
                <edge target="end" source="start" id="route"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void theCanonicalHashIgnoresSerialisationWhileTheSubmissionHashDoesNot() {
        try (var first = GraphManager.readGraphMl(stream(FIRST));
             var second = GraphManager.readGraphMl(stream(SECOND))) {

            String firstCanonical = GraphVersionSnapshot.submission(first.definition()).canonicalHash();
            String secondCanonical = GraphVersionSnapshot.submission(second.definition()).canonicalHash();
            assertEquals(firstCanonical, secondCanonical,
                    "the canonical hash is semantic: the same graph serialised differently is the same version");

            String firstSubmission = rawByteHash(FIRST);
            String secondSubmission = rawByteHash(SECOND);
            assertNotEquals(firstSubmission, secondSubmission,
                    "the submission hash is a raw-byte fingerprint: different bytes are a different submission");

            // The load-bearing assertion. If graphVersion were ever redefined to be the canonical
            // hash, these two would become the same value and this would fail.
            assertNotEquals(firstSubmission, firstCanonical,
                    "graphVersion (raw-byte submission hash) and the canonical semantic hash "
                            + "must remain distinct identifiers");
        }
    }

    @Test
    void theExecutionPinCarriesTheLogicalIdentityAsAnAdditiveField() {
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.error("error"), GraphNode.end("end")), List.of(GraphEdge.to("start", "end")));

        var published = GraphVersionSnapshot.create(new GraphVersionKey("orders", "3"), definition);
        var pin = GraphExecutionPin.from(published);

        assertEquals("orders", pin.metadata().key().graphId());
        assertEquals("3", pin.metadata().key().versionId());
        // Logical identity and content identity are independent: the same bytes published under a
        // different versionId are a different version of the same graph, with the same content hash.
        var republished = GraphVersionSnapshot.create(new GraphVersionKey("orders", "4"), definition);
        assertEquals(published.canonicalHash(), republished.canonicalHash());
        assertNotEquals(published.key(), republished.key());
    }

    @Test
    void theSubmissionPathIsContentAddressedRatherThanNamed() {
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("worker", NodeKind.BEHAVIOR, "w", Map.of("retries", 3)),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "worker"),
                GraphEdge.to("worker", "end")));

        var snapshot = GraphVersionSnapshot.submission(definition);

        // An inline submission has no author-supplied identity, so the content hash is the identity.
        assertEquals("submission", snapshot.key().graphId());
        assertEquals(snapshot.canonicalHash(), snapshot.key().versionId());
    }

    /**
     * The canonical hash of a fixed definition, pinned to a literal.
     *
     * <p>This looks like an over-specified test and is not. The in-memory reordering tests around it
     * cannot catch the failure this one exists for, because they run inside a single JVM.
     * {@link GraphDefinition} holds its nodes in a {@code Map.copyOf}, and the iteration order of
     * {@code java.util.ImmutableCollections} is deliberately randomised <em>per JVM invocation</em>
     * through its {@code SALT}. So within one test run the node order is fixed no matter what, and a
     * canonical form that had stopped normalising node order would still produce a stable hash for
     * the whole run — every reordering test would pass while the encoding was broken.</p>
     *
     * <p>Across a restart it would not. The same graph would canonicalise to a different hash after a
     * process restart, which for a content-addressed version identifier means a published version
     * silently ceasing to match itself. Measured directly while writing this test: the same
     * {@code Map.copyOf} key set iterates in a different order on most JVM starts.</p>
     *
     * <p>A literal expected value is the only assertion in-process that depends on the encoding rather
     * than on this JVM's iteration order. If this fails, either the canonical encoding changed — a
     * breaking change to version identity, needing a migration rather than a new constant — or the
     * definition below changed. The two are not the same failure and must not be resolved the same
     * way, so whoever updates the literal says which one it was.</p>
     *
     * <h2>Why the literal includes an error terminal</h2>
     * <p>It was the second case. {@code ERROR} became part of a graph's minimal structure, so this
     * fixture gained a fourth node and its content hash necessarily moved with it; the encoding is
     * untouched, which {@code git diff --stat dev..HEAD -- ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphCanonicalForm.java}
     * reports as no change to that file at all. The phase principle is what makes this a fixture edit
     * rather than a migration: nothing outside this repository has published a version identifier
     * that this could invalidate.</p>
     */
    @Test
    void theCanonicalEncodingIsPinnedSoItCannotDriftBetweenProcesses() {
        var definition = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("worker", NodeKind.BEHAVIOR, "normalize",
                        Map.of("attempts", 3L, "mode", "strict")),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "worker"),
                GraphEdge.to("worker", "end")));

        assertEquals("d483fbd79df535c892d3bbdac674123dba9990d7772de7fbf8de2b70531f42cf",
                GraphCanonicalForm.sha256(definition),
                "the canonical encoding changed; this is a breaking change to graph version identity");
    }

    private static ByteArrayInputStream stream(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }

    /** Exactly how {@code DefaultRavenrootApplication} derives {@code graphVersion}. */
    private static String rawByteHash(String document) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(document.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
