package ai.ravenroot.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GraphDefinitionLifecycleTest {

    @Test
    void canonicalHashIsStableAcrossCollectionAndPropertyOrder() {
        GraphDefinition canonical = versionOne();
        String expected = GraphCanonicalForm.sha256(canonical);
        var random = new Random(23);

        for (int attempt = 0; attempt < 100; attempt++) {
            var nodes = new ArrayList<>(canonical.nodes());
            var edges = new ArrayList<>(canonical.edges());
            Collections.shuffle(nodes, random);
            Collections.shuffle(edges, random);
            var reordered = nodes.stream().map(node -> new GraphNode(
                    node.id(), node.kind(), node.behavior(), reversed(node.properties()))).toList();

            assertEquals(expected, GraphCanonicalForm.sha256(new GraphDefinition(reordered, edges)));
        }

        var sameContentDifferentVersion = GraphVersionSnapshot.create(
                new GraphVersionKey("orders", "other-label"), canonical);
        assertEquals(expected, sameContentDifferentVersion.canonicalHash());
        assertNotEquals(expected, GraphCanonicalForm.sha256(new GraphDefinition(
                List.of(GraphNode.start("start"),
                        new GraphNode("worker", NodeKind.BEHAVIOR, "normalize",
                                Map.of("attempts", 3L, "mode", "strict")),
                        GraphNode.error("error"), GraphNode.end("end")),
                canonical.edges())));
    }

    @Test
    void semanticDiffAndCompatibilityAreDirectionalAndDeterministic() {
        GraphDefinition before = versionOne();
        GraphDefinition additive = additiveVersion();
        GraphCompatibilityReport forwardRelease = GraphCompatibilityReport.analyze(before, additive);

        assertEquals(List.of("audit"), forwardRelease.diff().addedNodeIds().stream().toList());
        assertTrue(forwardRelease.diff().removedNodeIds().isEmpty());
        assertTrue(forwardRelease.backwardCompatible());
        assertFalse(forwardRelease.forwardCompatible());
        assertTrue(GraphCompatibilityPolicy.BACKWARD.accepts(forwardRelease));
        assertFalse(GraphCompatibilityPolicy.FORWARD.accepts(forwardRelease));

        GraphCompatibilityReport rollbackView = GraphCompatibilityReport.analyze(additive, before);
        assertFalse(rollbackView.backwardCompatible());
        assertTrue(rollbackView.forwardCompatible());
        assertTrue(rollbackView.diagnostics().stream()
                .anyMatch(item -> item.code().equals("BACKWARD_NODE_REMOVED")));

        GraphDefinition changed = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("worker", "other"), GraphNode.error("error"), GraphNode.end("end")),
                before.edges());
        GraphCompatibilityReport incompatible = GraphCompatibilityReport.analyze(before, changed);
        assertFalse(incompatible.backwardCompatible());
        assertFalse(incompatible.forwardCompatible());
        assertEquals(List.of("worker"), incompatible.diff().changedNodeIds().stream().toList());
    }

    @Test
    void lifecyclePublishesImmutableVersionsAndSupportsExplicitRollback() {
        var lifecycle = new GraphDefinitionLifecycle();
        var v1 = new GraphVersionKey("orders", "v1");
        var v2 = new GraphVersionKey("orders", "v2");

        GraphVersionRecord validated = lifecycle.validate(v1, versionOne());
        assertEquals(GraphVersionState.VALIDATED, validated.state());
        assertTrue(lifecycle.find(v1).isEmpty());
        assertEquals(GraphVersionState.PUBLISHED, lifecycle.publish(validated).state());
        assertEquals(GraphVersionState.ACTIVE, lifecycle.activate(v1).state());
        GraphExecutionPin longRunning = lifecycle.pinActive("orders");

        assertThrows(IllegalStateException.class,
                () -> lifecycle.publish(lifecycle.validate(v2, additiveVersion())),
                "subsequent publication must not silently choose a compatibility promise");
        assertThrows(IllegalStateException.class,
                () -> lifecycle.publish(lifecycle.validate(v2, additiveVersion()),
                        v1, GraphCompatibilityPolicy.FORWARD));
        lifecycle.publish(lifecycle.validate(v2, additiveVersion()),
                v1, GraphCompatibilityPolicy.BACKWARD);
        lifecycle.activate(v2);
        assertEquals(v2, lifecycle.pinActive("orders").metadata().key());
        assertEquals(v1, longRunning.metadata().key(), "activation must not rewrite an existing execution pin");
        assertEquals(GraphExecutionDecision.Mode.FINISH,
                GraphExecutionDecision.finish(longRunning).mode());

        assertEquals(GraphVersionState.ACTIVE, lifecycle.activate(v1).state(), "rollback reactivates v1");
        assertEquals(GraphVersionState.RETIRED, lifecycle.retire(v2).state());
        assertThrows(IllegalStateException.class, () -> lifecycle.activate(v2));
        assertThrows(IllegalStateException.class, () -> lifecycle.retire(v1));

        assertThrows(IllegalStateException.class,
                () -> lifecycle.publish(lifecycle.validate(v1, additiveVersion())),
                "a stable version id cannot be rebound to different content");
    }

    @Test
    void migrationRequiresCompleteCompatibleNodeAndRouteMapping() {
        GraphVersionSnapshot source = GraphVersionSnapshot.create(
                new GraphVersionKey("orders", "v1"), versionOne());
        GraphVersionSnapshot renamed = GraphVersionSnapshot.create(
                new GraphVersionKey("orders", "v2"), renamedWorker("normalize"));
        GraphMigrationPlan plan = GraphMigrationPlan.analyze(source, renamed, Map.of("worker", "transformer"));

        assertTrue(plan.migratable(), plan.diagnostics().toString());
        assertEquals("transformer", plan.nodeMapping().get("worker"));
        GraphExecutionPin original = GraphExecutionPin.from(source);
        GraphExecutionDecision decision = GraphExecutionDecision.migrate(original, plan);
        assertEquals(GraphExecutionDecision.Mode.MIGRATE, decision.mode());
        assertEquals(source.metadata(), decision.original().metadata());
        assertEquals(renamed.metadata(), decision.target().metadata());
        assertEquals(source.metadata(), original.metadata(), "migration returns a new pin, never mutates the old one");

        GraphVersionSnapshot changedBehavior = GraphVersionSnapshot.create(
                new GraphVersionKey("orders", "v3"), renamedWorker("other"));
        GraphMigrationPlan rejected = GraphMigrationPlan.analyze(
                source, changedBehavior, Map.of("worker", "transformer"));
        assertFalse(rejected.migratable());
        assertTrue(rejected.diagnostics().stream()
                .anyMatch(item -> item.code().equals("NODE_SEMANTICS_CHANGED")));
        assertThrows(IllegalStateException.class,
                () -> GraphExecutionDecision.migrate(original, rejected));
    }

    @Test
    void migrationDiagnosesMissingRoutesAndCrossGraphTargets() {
        GraphVersionSnapshot source = GraphVersionSnapshot.create(
                new GraphVersionKey("orders", "v1"), versionOne());
        GraphDefinition missingRoute = new GraphDefinition(versionOne().nodes(),
                List.of(GraphEdge.to("start", "worker")));
        GraphVersionSnapshot target = GraphVersionSnapshot.create(
                new GraphVersionKey("other", "v1"), missingRoute);

        GraphMigrationPlan plan = GraphMigrationPlan.analyze(source, target, Map.of());

        assertFalse(plan.migratable());
        assertEquals(List.of("GRAPH_ID_MISMATCH", "ROUTE_NOT_MIGRATABLE"),
                plan.diagnostics().stream().map(GraphMigrationDiagnostic::code).sorted().toList());
    }

    @Test
    void versionMetadataRejectsUnstableIdsAndMutablePropertyValues() {
        assertThrows(IllegalArgumentException.class, () -> new GraphVersionKey(" orders", "v1"));
        GraphDefinition mutable = new GraphDefinition(
                List.of(GraphNode.start("start"),
                        new GraphNode("worker", NodeKind.PASSTHROUGH, null,
                                Map.of("unsafe", new ArrayList<>(List.of("value")))),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "worker"), GraphEdge.to("worker", "end")));
        assertThrows(IllegalArgumentException.class,
                () -> GraphVersionSnapshot.create(new GraphVersionKey("orders", "v1"), mutable));
    }

    private static GraphDefinition versionOne() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("mode", "strict");
        properties.put("attempts", 3);
        return new GraphDefinition(
                List.of(GraphNode.start("start"),
                        new GraphNode("worker", NodeKind.BEHAVIOR, "normalize", properties),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "worker"), GraphEdge.to("worker", "end")));
    }

    private static GraphDefinition additiveVersion() {
        GraphDefinition v1 = versionOne();
        var nodes = new ArrayList<>(v1.nodes());
        nodes.add(GraphNode.behavior("audit", "audit"));
        var edges = new ArrayList<>(v1.edges());
        edges.add(new GraphEdge("worker", "audit", "audit"));
        edges.add(GraphEdge.to("audit", "end"));
        return new GraphDefinition(nodes, edges);
    }

    private static GraphDefinition renamedWorker(String behavior) {
        return new GraphDefinition(
                List.of(GraphNode.start("start"),
                        new GraphNode("transformer", NodeKind.BEHAVIOR, behavior,
                                Map.of("attempts", 3, "mode", "strict")),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "transformer"), GraphEdge.to("transformer", "end")));
    }

    private static Map<String, Object> reversed(Map<String, Object> source) {
        var entries = new ArrayList<>(source.entrySet());
        Collections.reverse(entries);
        var result = new LinkedHashMap<String, Object>();
        entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
