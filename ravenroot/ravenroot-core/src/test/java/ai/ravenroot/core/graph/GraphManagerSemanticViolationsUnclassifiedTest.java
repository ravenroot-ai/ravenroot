package ai.ravenroot.core.graph;

import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Both disclosure protections are asserted here: no internal exception message
 * reaches a remote surface as a document violation, <em>and</em> the real exception is registered
 * where an operator can read it. {@link GraphManager#semanticViolations()} keeps a plain
 * {@code IllegalArgumentException} net for any invariant enforced by {@link GraphNode} or
 * {@link GraphEdge} that does not already produce a {@link GraphValidationException}. The net exists
 * specifically for a state its own public surface
 * excludes by construction, so it cannot be verified through that surface — it has to be verified by
 * building the excluded state directly and watching the real catch handle it.
 *
 * <h2>Why no GraphML document can reach it, verified rather than assumed</h2>
 * <p>Every id-bearing element this project's import path accepts is validated non-blank before
 * {@link GraphManager} ever builds a {@link Vertex} from it:</p>
 * <ul>
 *   <li>{@code SecureGraphMlParser}'s structural scan refuses an empty {@code id} attribute on a
 *       {@code <node>} before the document is even handed to a DOM parser.</li>
 *   <li>{@link GraphMlDocument}'s own {@code requiredId} trims the attribute and refuses it empty —
 *       trimmed, so a whitespace-only id (which {@code SecureGraphMlParser}'s untrimmed check alone
 *       would let through) is refused here, one layer before {@link GraphManager} runs at all, as a
 *       sanitised {@link GraphMlCompatibilityException}.</li>
 * </ul>
 * <p>{@link GraphManager#from(GraphDefinition)}, the one non-GraphML construction path, cannot reach
 * it either: every vertex it creates comes from an already-built {@link GraphNode}, whose own
 * canonical constructor enforces the identical non-blank-id invariant before {@code from} ever reads
 * the id. There is therefore no way to reach this net through {@link GraphManager}'s public surface
 * today — confirmed by reading every id-accepting call site above, not inferred from a search
 * returning nothing.
 *
 * <h2>What this test does instead</h2>
 * <p>It reaches around that public surface on purpose: it opens a {@link TinkerGraph} directly and
 * adds a vertex with a whitespace-only id — something no path this project exposes will ever do for
 * you — then builds a {@link GraphManager} over it through the package-private constructor every
 * public factory method already uses (same package, no reflection needed). That demonstrates the case
 * by construction in place of a document that cannot exist: {@link GraphNode}'s own
 * canonical constructor throws the real, unmocked {@link IllegalArgumentException} inside the real
 * catch block, and this test checks both of that catch block's effects — the caller-visible one and
 * the operator-visible one — rather than resting on the reachability analysis above by itself.</p>
 */
class GraphManagerSemanticViolationsUnclassifiedTest {

    @Test
    void semanticViolationsReplacesAnUncategorizedExceptionsRawMessageWithTheFixedOneAndLogsTheRealOne() {
        TinkerGraph graph = TinkerGraph.open();
        Vertex start = graph.addVertex(T.id, "start", T.label, "ravenroot-node");
        start.property(GraphManager.KIND, "START");
        // Three spaces: GraphMlDocument.requiredId() and SecureGraphMlParser both refuse this id
        // before GraphManager ever sees it (see class javadoc) -- no GraphML document can produce
        // this vertex. Built directly on the TinkerGraph API to reach GraphNode's own "non-blank id"
        // refusal, which nothing reachable from GraphManager's public surface can trigger.
        Vertex blankId = graph.addVertex(T.id, "   ", T.label, "ravenroot-node");
        blankId.property(GraphManager.KIND, "END");
        start.addEdge("ravenroot-edge", blankId).property(GraphManager.OUTCOME, "continue");

        // The class logger backing GraphManager's System.Logger: with no logging binding on the
        // reactor's classpath (verified for the running server/CLI, not merely assumed here),
        // System.getLogger delegates to java.util.logging under the exact same name, so attaching a
        // Handler here observes exactly what an operator's own console/file redirection would.
        Logger jul = Logger.getLogger("ai.ravenroot.core.graph.GraphManager");
        var captured = new ArrayList<LogRecord>();
        Handler probe = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(probe);
        try (GraphManager manager = new GraphManager(graph)) {
            List<String> violations = manager.semanticViolations();

            // Conjunct 1: the net caught GraphNode's plain IllegalArgumentException ("A graph node
            // must have a non-blank id") and did not let its raw message become the returned
            // violation -- that message would otherwise reach POST /v1/graphs/inspect's JSON body
            // verbatim, as a document violation indistinguishable from a real one.
            assertEquals(List.of(GraphManager.UNCLASSIFIED_VIOLATION_MESSAGE), violations);

            // Conjunct 2: the real exception was registered where an operator can read it. Deleting
            // the LOGGER.log(...) call at the catch site leaves conjunct 1 above green and would
            // silently swallow the exception. The returned message would remain protected, but the
            // operator would lose the only record of the real cause. This assertion detects that loss.
            assertEquals(1, captured.size(), "exactly one record should be published for this failure");
            LogRecord record = captured.get(0);
            assertEquals(Level.WARNING, record.getLevel());
            assertNotNull(record.getThrown(), "the real exception must travel with the log record");
            assertSame(IllegalArgumentException.class, record.getThrown().getClass(),
                    "must be the plain IllegalArgumentException GraphNode threw, not some other type");
            assertEquals("A graph node must have a non-blank id", record.getThrown().getMessage());
        } finally {
            jul.removeHandler(probe);
        }
    }
}
