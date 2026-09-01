package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphMlParseException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultRavenrootApplicationGraphMlSecurityTest {
    @Test
    void rejectsUnsafeMalformedOversizedAndStructurallyInvalidGraphsBeforeSpawningNodes() {
        var engine = new CountingExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
        var rejectedDocuments = List.of(
                """
                        <!DOCTYPE graphml>
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns"/>
                        """.getBytes(StandardCharsets.UTF_8),
                """
                        <!DOCTYPE graphml [<!ENTITY value "expanded">]>
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                          <graph id="g" edgedefault="directed"><node id="n">&value;</node></graph>
                        </graphml>
                        """.getBytes(StandardCharsets.UTF_8),
                "<graphml".getBytes(StandardCharsets.UTF_8),
                """
                        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                          <graph id="g" edgedefault="directed">
                            <node id="declared"/>
                            <edge source="declared" target="missing"/>
                          </graph>
                        </graphml>
                        """.getBytes(StandardCharsets.UTF_8),
                new byte[GraphMlLimits.DEFAULTS.maxBytes() + 1]);

        for (byte[] document : rejectedDocuments) {
            assertThrows(GraphMlParseException.class, () -> application.startGraphMl(
                    TestIdentities.TENANT_A, java.util.UUID.randomUUID(),
                    new ByteArrayInputStream(document), "payload"));
        }
        assertEquals(0, engine.spawnCount.get());
        application.close();
    }

    /**
     * Before semantic inspection, {@code inspectGraphMl} counted {@code START}/{@code END}
     * vertices by raw property and never built a {@code GraphDefinition}, so this document -- an
     * unknown node kind, otherwise well-formed -- came back {@code startNodes=1, endNodes=1} with no
     * way to tell it apart from a valid graph. {@code GraphSummary} carried no field that could have
     * said otherwise.
     */
    @Test
    void inspectGraphMlNamesAnUnknownNodeKindInsteadOfOnlyCountingStartAndEnd() {
        var engine = new CountingExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
        byte[] document = """
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

        var summary = application.inspectGraphMl(new ByteArrayInputStream(document));

        assertEquals(1, summary.startNodes());
        assertEquals(1, summary.endNodes());
        assertEquals(false, summary.valid(), "an undeclared-and-unknown kind is not a valid graph");
        assertEquals(List.of("Node 'mystery' declares an unknown kind 'SUBGRAPH'; "
                        + "the known kinds are START, PASSTHROUGH, BEHAVIOR, END, ERROR"),
                summary.violations());
        application.close();
    }

    /**
     * The second semantic-inspection document, re-measured for the current rules: "no
     * error terminal" is a legitimate graph now, so a surplus of error terminals -- the half of the
     * retained cardinality ceiling -- takes its place.
     */
    @Test
    void inspectGraphMlNamesASurplusOfErrorTerminalsInsteadOfOnlyCountingStartAndEnd() {
        var engine = new CountingExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
        byte[] document = """
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

        var summary = application.inspectGraphMl(new ByteArrayInputStream(document));

        assertEquals(1, summary.startNodes());
        assertEquals(1, summary.endNodes());
        assertEquals(false, summary.valid(), "a graph may declare at most one error terminal");
        assertEquals(List.of("A graph must contain at most one error node, and 2 are declared: first, second"),
                summary.violations());
        application.close();
    }

    /** A structurally valid document reports no violations: the new check must not be vacuous. */
    @Test
    void inspectGraphMlReportsNoViolationsForAStructurallyValidDocument() {
        var engine = new CountingExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
        byte[] document = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="start"><data key="kind">START</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="end"/>
                  </graph>
                </graphml>
                """.getBytes(StandardCharsets.UTF_8);

        var summary = application.inspectGraphMl(new ByteArrayInputStream(document));

        assertEquals(true, summary.valid());
        assertEquals(List.of(), summary.violations());
        application.close();
    }

    /**
     * A node declaring {@code kind=BEHAVIOR} without a {@code behavior}
     * name is refused by {@code GraphNode}'s own constructor with a plain
     * {@code IllegalArgumentException}, not the {@code GraphValidationException} handled by a narrow
     * {@code semanticViolations()} catch. Catching only that type throws for this document and loses
     * every count. {@code inspectGraphMl} must not throw
     * for this document; it must report it as a violation like any other.
     */
    @Test
    void inspectGraphMlNamesABehaviorNodeWithoutANameInsteadOfThrowing() {
        var engine = new CountingExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
        byte[] document = """
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

        var summary = assertDoesNotThrow(() -> application.inspectGraphMl(new ByteArrayInputStream(document)));

        assertEquals(false, summary.valid());
        assertEquals(List.of("Node 'mystery' declares kind 'BEHAVIOR' without a behavior name"),
                summary.violations());
        // The specific regression: the counts must still be there, not lost to a throw.
        assertEquals(3, summary.nodes());
        assertEquals(2, summary.edges());
        assertEquals(1, summary.startNodes());
        assertEquals(1, summary.endNodes());
        application.close();
    }

    private static final class CountingExecutionEngine implements ExecutionEngine {
        private final AtomicInteger spawnCount = new AtomicInteger();

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public Set<EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            spawnCount.incrementAndGet();
            return new NodeRef(logicalName);
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            return CompletableFuture.completedFuture(null);
        }


        @Override
        public EngineState state() {
            return EngineState.RUNNING;
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.of(StubEngineLifecycle.running(target));
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
