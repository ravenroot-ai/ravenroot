package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.GraphAdmissionException;
import ai.ravenroot.api.application.GraphAdmissionPurpose;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SagaGraphMlContractTest {
    @Test
    void sagaPropertiesSurviveNormalGraphMlImportExport() {
        byte[] source = graph("trusted").getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            manager.writeGraphMl(output);
        }
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(output.toByteArray()))) {
            var effect = reread.definition().nodes().stream()
                    .filter(node -> node.id().equals("effect")).findFirst().orElseThrow();
            assertEquals("order", effect.properties().get("saga.scope"));
            assertEquals("reserve", effect.properties().get("saga.step"));
            assertEquals("pure", effect.properties().get("saga.participant"));
        }
    }

    @Test
    void structuredAndLegacyAdmissionBothRejectForgedParticipantCapability() {
        byte[] source = graph("untrusted").getBytes(StandardCharsets.UTF_8);
        var registry = registry();
        var structured = new GraphAdmissionValidator(registry, GraphExecutionLimits.DEFAULTS, true);
        assertThrows(GraphAdmissionException.class,
                () -> structured.require(source, GraphAdmissionPurpose.EXECUTION));
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var legacy = new GraphAdmissionValidator(registry, GraphExecutionLimits.DEFAULTS, true);
            assertThrows(IllegalArgumentException.class,
                    () -> legacy.validateLegacy(manager.definition(), ignored -> true));
        }
    }

    @Test
    void structuredAdmissionRejectsSagaWhenOnlyVolatileSemanticsAreAvailable() {
        byte[] source = graph("trusted").getBytes(StandardCharsets.UTF_8);
        var validator = new GraphAdmissionValidator(registry(), GraphExecutionLimits.DEFAULTS, false);
        assertThrows(GraphAdmissionException.class,
                () -> validator.require(source, GraphAdmissionPurpose.EXECUTION));
    }

    @Test
    void structuredAndLegacyAdmissionRejectOutOfRangeSagaDeadline() {
        byte[] source = graph("trusted")
                .replace("<key id=\"outcome\"", "<key id=\"deadline\" for=\"node\" "
                        + "attr.name=\"saga.deadlineMs\" attr.type=\"long\"/><key id=\"outcome\"")
                .replace("<data key=\"participant\">pure</data>",
                        "<data key=\"participant\">pure</data><data key=\"deadline\">0</data>")
                .getBytes(StandardCharsets.UTF_8);
        var structured = new GraphAdmissionValidator(registry(), GraphExecutionLimits.DEFAULTS, true);
        assertThrows(GraphAdmissionException.class,
                () -> structured.require(source, GraphAdmissionPurpose.EXECUTION));
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var legacy = new GraphAdmissionValidator(registry(), GraphExecutionLimits.DEFAULTS, true);
            assertThrows(IllegalArgumentException.class,
                    () -> legacy.validateLegacy(manager.definition(), ignored -> true));
        }
    }

    private static BehaviorRegistry registry() {
        return new BehaviorRegistry().registerFactory(factory("trusted", Set.of("saga-pure")))
                .registerFactory(factory("untrusted", Set.of("side-effect")));
    }

    private static NodeBehaviorFactory factory(String behavior, Set<String> capabilities) {
        return new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(behavior, behavior, "Test", behavior, "actor", false,
                        List.of(), capabilities);
            }
            @Override public NodeHandler create(ai.ravenroot.core.graph.GraphNode node) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        };
    }

    private static String graph(String behavior) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="scope" for="node" attr.name="saga.scope" attr.type="string"/>
                  <key id="step" for="node" attr.name="saga.step" attr.type="string"/>
                  <key id="participant" for="node" attr.name="saga.participant" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="saga" edgedefault="directed">
                    <node id="start"><data key="kind">START</data></node>
                    <node id="effect"><data key="kind">BEHAVIOR</data><data key="behavior">%s</data>
                      <data key="scope">order</data><data key="step">reserve</data>
                      <data key="participant">pure</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge source="start" target="effect"><data key="outcome">continue</data></edge>
                    <edge source="effect" target="end"><data key="outcome">continue</data></edge>
                  </graph>
                </graphml>
                """.formatted(behavior);
    }
}
