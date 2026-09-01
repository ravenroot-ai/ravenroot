package ai.ravenroot.core.graph;

import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A step-based mutation issued through {@link GraphManager#query} must be REFUSED at
 * the call site.
 *
 * <p>The vulnerable behavior was not a silent no-op. A mutating traversal ran to completion, returned no error, and
 * <em>the write landed on the manager's own graph and stayed there</em>: node count 2 before, 1
 * inside the traversal, and 1 afterwards when read back through the manager. The failure mode
 * asserted below is therefore <strong>persistence</strong>, not discard.</p>
 *
 * <p>Each test states the value produced by the vulnerable behavior, so the assertions genuinely
 * separate the two worlds instead of holding in both.</p>
 */
class GraphManagerReadOnlyQueryTest {

    /** An imported manager, which is the only shape that arms CORE-01's export guard. */
    private static GraphManager importedTopology() {
        try (var input = GraphManagerReadOnlyQueryTest.class
                .getResourceAsStream("/graphml-corpus/accepted/topology.graphml")) {
            return GraphManager.readGraphMl(new java.io.ByteArrayInputStream(input.readAllBytes()));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Three nodes: start, the error terminal the fixture includes, and end. */
    private static GraphManager minimalGraph() {
        return GraphManager.from(new GraphDefinition(
                List.of(new GraphNode("start", NodeKind.START, null, Map.of()),
                        GraphNode.error("error"), new GraphNode("end", NodeKind.END, null, Map.of())),
                List.of(new GraphEdge("start", "end", "continue", Map.of()))));
    }

    /**
     * A mutating step is refused when the traversal is iterated. The vulnerable path threw no
     * exception at all.
     */
    @Test
    void refusesAStepBasedMutationIssuedThroughQuery() {
        try (var manager = minimalGraph()) {
            assertEquals(3L, manager.nodeCount(), "control: the unmutated graph has all three nodes");

            assertThrows(VerificationException.class,
                    () -> manager.query(graph -> {
                        graph.V("end").drop().iterate();
                        return null;
                    }),
                    "query() must refuse a mutating step; the vulnerable path threw no exception");
        }
    }

    /**
     * The discriminating assertion: the deletion must not land. A vulnerable implementation reads
     * back 1 because the drop is applied to the manager's real graph; the protected graph reads back 2.
     */
    @Test
    void aRefusedMutationDoesNotReachTheManagersGraph() {
        try (var manager = minimalGraph()) {
            long before = manager.nodeCount();
            assertEquals(3L, before, "control: all three nodes present before the attempt");

            assertThrows(VerificationException.class, () -> manager.query(graph -> {
                graph.V("end").drop().iterate();
                return null;
            }));

            assertEquals(before, manager.nodeCount(),
                    "the refused deletion must leave the graph intact (the vulnerable result was 1: the "
                            + "write persisted on the manager)");
            assertEquals(1L, manager.edgeCount(), "dropping a vertex would have taken its edge too");
        }
    }

    /** Vertex addition is a mutation as much as removal is, and must be refused on the same footing. */
    @Test
    void refusesAVertexAdditionIssuedThroughQuery() {
        try (var manager = minimalGraph()) {
            assertThrows(VerificationException.class, () -> manager.query(graph -> {
                graph.addV("ravenroot-node")
                        .property(org.apache.tinkerpop.gremlin.structure.T.id, "added")
                        .iterate();
                return null;
            }));

            assertEquals(3L, manager.nodeCount(),
                    "the refused addition must not land (it would have made this 4)");
        }
    }

    /** Property writes are refused too, which is what keeps the export guard from being tripped. */
    @Test
    void refusesAPropertyWriteIssuedThroughQuery() {
        try (var manager = minimalGraph()) {
            assertThrows(VerificationException.class, () -> manager.query(graph -> {
                graph.V("start").property("owner", "mutated").iterate();
                return null;
            }));

            assertEquals("{}", manager.definition().nodes().stream()
                            .filter(node -> node.id().equals("start")).findFirst().orElseThrow()
                            .properties().toString(),
                    "the refused property write must not land (the vulnerable result was {owner=mutated})");
        }
    }

    /**
     * The two production callers of {@code query()} are read-only START/END counting
     * ({@code DefaultRavenrootApplication}). Reads must be entirely unaffected by the strategy.
     */
    @Test
    void readOnlyTraversalsStillWork() {
        try (var manager = minimalGraph()) {
            long starts = manager.query(graph ->
                    graph.V().has(GraphManager.KIND, NodeKind.START.name()).count().next());
            long ends = manager.query(graph ->
                    graph.V().has(GraphManager.KIND, NodeKind.END.name()).count().next());
            assertEquals(1L, starts);
            assertEquals(1L, ends);
            assertEquals(3L, manager.nodeCount());
            assertEquals(1L, manager.edgeCount());
            assertEquals("start", manager.start().id());
            assertEquals(List.of("end"), manager.next("start", "continue").stream()
                    .map(GraphNode::id).toList());
            assertEquals(1, manager.predecessorCount("end"));
            assertEquals(3, manager.definition().nodes().size());
        }
    }

    /**
     * The residual escape, asserted rather than merely described. {@code getGraph()} is public and no
     * strategy closes it, so the manager is NOT immutable — and CORE-01's export guard, not the
     * strategy, is what still catches a mutation that arrives this way.
     */
    @Test
    void getGraphRemainsAnUnguardedEscapeThatOnlyTheExportGuardCatches() {
        try (var manager = importedTopology()) {
            manager.query(graph -> {
                graph.getGraph().traversal().V("end").property("owner", "mutated").iterate();
                return null;
            });

            // No refusal happened: the strategy does not reach through getGraph().
            var error = assertThrows(IllegalStateException.class,
                    () -> manager.writeGraphMl(new ByteArrayOutputStream()));
            assertEquals(true, error.getMessage().contains("mutated through a traversal"),
                    "the export guard, not the strategy, is what catches this: " + error.getMessage());
        }
    }
}
