package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node catalog key on {@link ExecutionEvent} (ADR 0021 D5): what is resolved, and — the
 * part that is a cardinality control rather than a convenience — what deliberately is not.
 *
 * <h2>Why the filter is a gate</h2>
 * <p>This value becomes a metric label. A node's {@code behavior} string is graph-author text, so
 * labelling with it directly would be unbounded and would violate the PLAT-01 rule the allowlist
 * exists to enforce. Resolving through the {@link BehaviorRegistry} makes the label's value domain
 * exactly the installed catalog. {@link #omitsABehaviorTheRegistryDoesNotKnow()} is that gate:
 * deleting the registry lookup and taking {@code node.behavior()} directly turns it red while
 * everything else stays green.
 */
class NodeCatalogKeyTest {
    @Test
    void resolvesRegisteredBehaviorsToTheirCatalogKey() {
        // The STANDARD registry, not a bare one. A bare BehaviorRegistry knows nothing, so this case
        // would be indistinguishable from the unregistered one below and let the test pass for the
        // wrong reason.
        Map<String, String> keys = GraphRunner.resolveCatalogKeys(
                graph(GraphNode.behavior("send", "log")), BehaviorRegistry.standard());

        assertEquals(Map.of("send", "log"), keys,
                "a registered behavior must resolve to the descriptor's own key, which is what bounds "
                        + "the label's value domain to the installed catalog");
    }

    /**
     * The gate. An unregistered behavior name is caller-supplied text; admitting it would make the
     * label unbounded, which is the exact defect the allowlist exists to prevent.
     */
    @Test
    void omitsABehaviorTheRegistryDoesNotKnow() {
        Map<String, String> keys = GraphRunner.resolveCatalogKeys(
                graph(GraphNode.behavior("rogue", "not-a-registered-behavior-" + UUID.randomUUID())),
                BehaviorRegistry.standard());

        assertTrue(keys.isEmpty(),
                "an unregistered behavior must contribute no catalog key. Its name is graph-author "
                        + "text with no catalog bound, so labelling a metric with it would produce one "
                        + "series per distinct string a graph author invents: " + keys);
    }

    /** START, PASSTHROUGH and END are structural: they have no behavior, so they have no key. */
    @Test
    void omitsStructuralNodesWhichHaveNoBehaviorAtAll() {
        Map<String, String> keys = GraphRunner.resolveCatalogKeys(
                new GraphDefinition(List.of(GraphNode.start("start"), GraphNode.passthrough("pass"),
                        GraphNode.error("error"), GraphNode.end("end")),
                        List.of(GraphEdge.to("start", "pass"), GraphEdge.to("pass", "end"))),
                BehaviorRegistry.standard());

        assertTrue(keys.isEmpty(), "structural nodes carry no behavior and must contribute no key: " + keys);
    }

    /**
     * The publish wiring: the key is resolved once, in the single canonical {@code publish}, from the
     * map the identity carries — so node events and join events get it without either call site
     * naming it, and traversal events get {@code null} because they name no node.
     */
    @Test
    void publishesTheResolvedKeyForNodeAndJoinEventsAndNullForTraversalEvents() {
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var monitor = new ExecutionMonitor();
        monitor.subscribe(events::add);
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID(), Map.of("send", "log", "gate", "cel-decision"));

        monitor.executionStarted(identity);
        monitor.nodeStarted(identity, "send", UUID.randomUUID());
        monitor.joinArrivalDiscarded(identity, "gate", "branch-1", "superseded");
        monitor.nodeStarted(identity, "unmapped", UUID.randomUUID());

        assertNull(find(events, ExecutionEventType.EXECUTION_STARTED).nodeCatalogKey(),
                "a traversal-level event names no node, so it carries no catalog key");
        assertEquals("log", find(events, ExecutionEventType.NODE_STARTED).nodeCatalogKey());
        assertEquals("cel-decision", find(events, ExecutionEventType.JOIN_ARRIVAL_DISCARDED).nodeCatalogKey(),
                "join events resolve through the same identity map, which is why JoinCoordinator needed "
                        + "no new dependency to gain the dimension");
        assertNull(events.stream().filter(e -> "unmapped".equals(e.nodeId())).findFirst().orElseThrow()
                        .nodeCatalogKey(),
                "a node absent from the map carries no key rather than a placeholder");
    }

    /** An identity built without a map resolves everything to absent rather than failing. */
    @Test
    void anIdentityWithoutACatalogMapResolvesEveryNodeToAbsent() {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "engine", "v1",
                UUID.randomUUID(), UUID.randomUUID());

        assertNull(identity.catalogKeyFor("send"));
        assertNull(identity.catalogKeyFor(null));
    }

    private static ExecutionEvent find(List<ExecutionEvent> events, ExecutionEventType type) {
        return events.stream().filter(event -> event.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " event was published: " + events));
    }

    private static GraphDefinition graph(GraphNode behavior) {
        return new GraphDefinition(
                List.of(GraphNode.start("start"), behavior, GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", behavior.id()), GraphEdge.to(behavior.id(), "end")));
    }
}
