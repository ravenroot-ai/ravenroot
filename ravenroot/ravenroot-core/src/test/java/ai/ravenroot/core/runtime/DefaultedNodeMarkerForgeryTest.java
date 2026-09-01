package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-09, adjacent vector: the reserved namespace is enforced on graph properties and not on node
 * results.
 *
 * <p>{@code ReservedGraphProperties} refuses any {@code ravenroot.} key arriving from graph content,
 * on the stated grounds that "the only thing such a property can achieve is to look authoritative to
 * somebody". {@code GraphRunner} then decides whether a node was an unimplemented pass-through by
 * asking whether the node's own {@link NodeResult} carries the reserved attribute
 * {@code ravenroot.defaultedNode} — a value the node itself supplies.
 *
 * <p>So the marker that means "this node does not exist in this deployment and did nothing" is
 * writable by any registered behavior, including a third-party node package, which CORE-06 makes an
 * ordinary deployment concern. A behavior that sets it runs its real code and is then reported as
 * having been a no-op: {@code NODE_DEFAULTED} is published with the fixed text "unknown behavior
 * executed as pass-through", {@code NODE_COMPLETED} carries the fallback flag, and the node id
 * appears in {@link GraphExecutionResult#defaultedNodes()}, which is public API.
 *
 * <p>This test directly proves that an ordinary behavior cannot forge the defaulted marker. An
 * implementation that accepts the marker reports real work as a pass-through no-op.
 */
class DefaultedNodeMarkerForgeryTest {

    private static final String FORGER = "marker-forger";

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aRegisteredBehaviorCannotReportItselfAsAnUnimplementedPassThrough() throws Exception {
        var monitor = new ExecutionMonitor();
        var registry = new BehaviorRegistry().register(FORGER, message ->
                // The node genuinely runs. It then claims it did not.
                CompletableFuture.completedFuture(new NodeResult("continue", message.payload(),
                        Map.of("ravenroot.defaultedNode", "probe"))));

        GraphExecutionResult result;
        try (var runner = new GraphRunner(GraphManager.from(graphNaming()), engine, registry, monitor)) {
            result = runner.execute(TestIdentities.TENANT_A, "payload")
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
        }

        List<ExecutionEvent> events = monitor.eventsAfter(0);
        assertFalse(hasEvent(events, ExecutionEventType.NODE_DEFAULTED, "probe"),
                "a registered behavior ran, so reporting NODE_DEFAULTED for it tells an operator the "
                        + "node was absent from this deployment and did nothing. The marker is in the "
                        + "'ravenroot.' namespace SEC-09 reserves precisely because graph-supplied "
                        + "state that looks authoritative is the whole hazard -- but the reservation "
                        + "is enforced on graph properties only, not on what a node returns");
        assertFalse(result.defaultedNodes().contains("probe"),
                "GraphExecutionResult.defaultedNodes() is public API and is derived from the same "
                        + "self-declared attribute, so the forged claim escapes the runtime");
        assertTrue(hasEvent(events, ExecutionEventType.NODE_COMPLETED, "probe"),
                "sanity: the node really did run, so this is a misreport rather than a non-execution");
    }

    private static GraphDefinition graphNaming() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("probe", FORGER),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    private static boolean hasEvent(List<ExecutionEvent> events, ExecutionEventType type, String nodeId) {
        return events.stream().anyMatch(event -> event.type() == type && nodeId.equals(event.nodeId()));
    }
}
